package com.sysmon.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.sysmon.app.collect.MonitorEngine;
import com.sysmon.app.collect.SysData;

import java.util.Locale;

/**
 * 悬浮窗服务：TYPE_APPLICATION_OVERLAY，可拖动，按设置显示指标子集。
 * - 截屏忽略：对窗口加 FLAG_SECURE（内容不进入截图/录屏）
 * - 全屏/横屏隐藏：横屏用 Configuration；全屏用无障碍窗口边界启发式
 */
public class OverlayService extends Service {

    private static final String CHANNEL_ID = "sysmon_overlay";
    private static final int NOTIF_ID = 1;

    /** 供无障碍服务回调全屏状态。 */
    static volatile OverlayService instance;

    private WindowManager wm;
    private View root;
    private TextView tv;
    private WindowManager.LayoutParams params;
    private Prefs prefs;
    private MonitorEngine engine;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean visible = true;
    private boolean fullscreenNow = false;
    private boolean landscapeNow = false;
    private float nx = 0.7f, ny = 0.1f; // 归一化位置（相对可移动范围）

    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            SysData d = engine.latest();
            SysLog.w("overlay update: cpu=" + (d.hasCpu() ? String.format("%.0f%%", d.cpuTotal) : "N/A")
                    + " mem=" + (d.hasMem() ? (d.memTotal / 1024 + "MB") : "N/A")
                    + " batt=" + (d.battLevel >= 0 ? (d.battLevel + "%") : "N/A")
                    + " gpu=" + (d.hasGpu() ? String.format("%.0f%%", d.gpuUtil) : "N/A"));
            updateText(d);
            handler.postDelayed(this, prefs.overlayUpdateMs());
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        SysLog.w("OverlayService onCreate");
        prefs = new Prefs(this);
        engine = MonitorEngine.get(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        startForegroundCompat();
        buildWindow();
        engine.start();
        // 注意：不在这里设置 lightMode，由 MainActivity 统一控制
        handler.post(updater);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        applyConfig();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacks(updater);
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (Throwable ignored) {}
            root = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- 窗口 ----------

    private void buildWindow() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        root = inflater.inflate(R.layout.overlay_window, null);
        tv = root.findViewById(R.id.overlayText);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        root.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int startX, startY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = params.x;
                        startY = params.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - downRawX);
                        int dy = (int) (event.getRawY() - downRawY);
                        if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved = true;
                        if (moved) {
                            params.x = startX + dx;
                            params.y = startY + dy;
                            try {
                                wm.updateViewLayout(root, params);
                            } catch (Throwable ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) savePosition();
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(root, params);
        } catch (Throwable t) {
            SysLog.e("overlay addView failed: " + t);
        }
    }

    /** 应用全部外观/行为设置。 */
    private void applyConfig() {
        if (root == null) return;
        int bg = prefs.overlayBgColor();
        int fg = prefs.overlayFgColor();
        int alpha = prefs.overlayAlpha();
        int fontSize = prefs.overlayFontSize();

        root.setBackgroundColor(bg);
        tv.setTextColor(fg);
        tv.setTextSize(fontSize);
        root.setAlpha(alpha / 100f);

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (prefs.overlayIgnoreTouch()) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        if (prefs.overlayHideScreenshot()) {
            flags |= WindowManager.LayoutParams.FLAG_SECURE;
        }
        params.flags = flags;
        try {
            wm.updateViewLayout(root, params);
        } catch (Throwable ignored) {}

        checkVisibility();
    }

    /** 根据全屏/横屏设置决定显示或隐藏。 */
    private void checkVisibility() {
        boolean shouldShow = true;
        if (prefs.overlayHideLandscape() && landscapeNow) shouldShow = false;
        if (prefs.overlayHideFullscreen() && fullscreenNow) shouldShow = false;

        if (shouldShow && !visible) {
            try {
                wm.addView(root, params);
                visible = true;
            } catch (Throwable ignored) {}
        } else if (!shouldShow && visible) {
            try {
                wm.removeView(root);
                visible = false;
            } catch (Throwable ignored) {}
        }
    }

    /** 无障碍服务回调：全屏状态变化。 */
    public void onFullscreenChanged(boolean fullscreen) {
        fullscreenNow = fullscreen;
        handler.post(new Runnable() {
            @Override
            public void run() {
                checkVisibility();
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        landscapeNow = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        // 旋转后按归一化位置重算并 clamp
        handler.post(new Runnable() {
            @Override
            public void run() {
                reposition();
                checkVisibility();
            }
        });
    }

    private void reposition() {
        if (root == null || params == null) return;
        try {
            android.graphics.Point size = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(size);
            int w = root.getWidth();
            int h = root.getHeight();
            int availW = Math.max(1, size.x - w);
            int availH = Math.max(1, size.y - h);
            params.x = (int) (nx * availW);
            params.y = (int) (ny * availH);
            params.x = Math.max(0, Math.min(params.x, availW));
            params.y = Math.max(0, Math.min(params.y, availH));
            wm.updateViewLayout(root, params);
        } catch (Throwable ignored) {}
    }

    private void savePosition() {
        try {
            android.graphics.Point size = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(size);
            int w = root.getWidth();
            int h = root.getHeight();
            int availW = Math.max(1, size.x - w);
            int availH = Math.max(1, size.y - h);
            nx = (float) params.x / availW;
            ny = (float) params.y / availH;
            prefs.setOverlayX(nx);
            prefs.setOverlayY(ny);
        } catch (Throwable ignored) {}
    }

    // ---------- 内容 ----------

    private void updateText(SysData d) {
        if (tv == null) return;
        StringBuilder sb = new StringBuilder();
        int mask = prefs.overlayShowMask();
        if ((mask & Prefs.SHOW_CPU_TOTAL) != 0) {
            sb.append("CPU ").append(fmtPct(d.cpuTotal)).append('\n');
        }
        if ((mask & Prefs.SHOW_CPU_PER) != 0 && d.cpuPer.length > 0) {
            StringBuilder cores = new StringBuilder();
            for (int i = 0; i < d.cpuPer.length; i++) {
                if (i > 0) cores.append(' ');
                cores.append(fmtPct(d.cpuPer[i]));
            }
            sb.append("CORE ").append(cores).append('\n');
        }
        if ((mask & Prefs.SHOW_GPU) != 0) {
            sb.append("GPU ").append(fmtPct(d.gpuUtil));
            if (d.gpuFreq > 0) sb.append(' ').append(d.gpuFreq).append("MHz");
            sb.append('\n');
        }
        if ((mask & Prefs.SHOW_MEM) != 0) {
            sb.append("MEM ").append(fmtMem(d)).append('\n');
        }
        if ((mask & Prefs.SHOW_BATT_LEVEL) != 0) {
            sb.append("BAT ").append(d.battLevel >= 0 ? d.battLevel + "%" : "N/A");
            if (d.charging()) sb.append(" ⚡");
            sb.append('\n');
        }
        if ((mask & Prefs.SHOW_BATT_TEMP) != 0) {
            sb.append("TEMP ").append(d.battTempC >= 0 ? String.format(Locale.US, "%.1f°C", d.battTempC) : "N/A").append('\n');
        }
        if ((mask & Prefs.SHOW_BATT_VOLT) != 0) {
            sb.append("VOLT ").append(d.battVolt > 0 ? d.battVolt + "mV" : "N/A").append('\n');
        }
        if ((mask & Prefs.SHOW_BATT_CURR) != 0) {
            sb.append("CURR ").append(d.hasBattCurrent() ? fmtCurrent(d.battCurrent) : "N/A").append('\n');
        }
        if ((mask & Prefs.SHOW_POWER_IN) != 0) {
            sb.append("IN ").append(fmtPower(d.powerIn)).append('\n');
        }
        if ((mask & Prefs.SHOW_POWER_OUT) != 0) {
            sb.append("OUT ").append(fmtPower(d.powerOut)).append('\n');
        }
        if ((mask & Prefs.SHOW_POWER_PHONE) != 0) {
            sb.append("PHONE ").append(fmtPower(d.powerPhone)).append('\n');
        }
        if ((mask & Prefs.SHOW_NET) != 0) {
            sb.append("NET ↓").append(fmtRate(d.netRxRate)).append(" ↑").append(fmtRate(d.netTxRate)).append('\n');
        }
        if ((mask & Prefs.SHOW_FPS) != 0) {
            sb.append("FPS ").append(Float.isNaN(d.screenFps) ? "N/A" : String.format(Locale.US, "%.0f", d.screenFps)).append('\n');
        }
        tv.setText(sb.toString());
    }

    private String fmtPct(float v) {
        return Float.isNaN(v) ? "--%" : String.format(Locale.US, "%3.0f%%", v);
    }

    private String fmtMem(SysData d) {
        if (!d.hasMem()) return "N/A";
        return String.format(Locale.US, "%d/%dMB", d.memUsedKB() / 1024, d.memTotal / 1024);
    }

    private String fmtPower(float p) {
        if (Float.isNaN(p)) return "N/A";
        // 当功率值较大时显示 W，较小时显示 mW
        if (Math.abs(p) >= 1000f) {
            return String.format(Locale.US, "%.2fW", p / 1000f);
        } else if (Math.abs(p) >= 1f) {
            return String.format(Locale.US, "%.1fmW", p);
        } else {
            return String.format(Locale.US, "%.0fmW", p);
        }
    }

    private String fmtCurrent(int ma) {
        if (ma == Integer.MIN_VALUE) return "N/A";
        // 电流单位是 mA，直接显示
        return String.format(Locale.US, "%.2fmA", ma / 1.0f);
    }

    private String fmtRate(float kb) {
        return Float.isNaN(kb) ? "--" : String.format(Locale.US, "%.0fK", kb);
    }

    // ---------- 前台通知 ----------

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "SysMon 悬浮窗",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("SysMon")
                    .setContentText("悬浮窗运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setOngoing(true)
                    .build();
            startForeground(NOTIF_ID, n);
        } else {
            startForeground(NOTIF_ID, new Notification.Builder(this)
                    .setContentTitle("SysMon")
                    .setContentText("悬浮窗运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .build());
        }
    }
}
