package com.sysmon.app.collect;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.WindowManager;

import com.sysmon.app.Privilege;
import com.sysmon.app.SysLog;

/**
 * 实时屏幕帧率统计。
 * - 有 shell 通道：解析 `dumpsys SurfaceFlinger --latency` 计算真实屏幕刷新帧率
 * - 有 Context：用 WindowManager 获取显示刷新率（近似值，恒定帧率）
 * - 最后回退：Choreographer 测量本应用渲染帧率
 * 结果写入 SysData.screenFps。
 */
public class ScreenFps {

    private static final long FPS_INTERVAL_MS = 1000;

    private Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Choreographer 回退测量
    private long lastFrameTs = 0;
    private int frameCount = 0;
    private float appFps = Float.NaN;
    private boolean choreoStarted = false;

    // SurfaceFlinger 测量
    private long lastShellTs = 0;
    private float shellFps = Float.NaN;

    // WindowManager 显示刷新率缓存
    private float displayRefreshRate = Float.NaN;
    private long lastDisplayCheckTs = 0;
    private boolean displayChecked = false;

    public ScreenFps() {}

    public void setContext(Context ctx) {
        this.ctx = ctx != null ? ctx.getApplicationContext() : null;
        SysLog.w("screenFps: setContext " + (this.ctx != null ? "ok" : "null"));
    }

    /** 采样一次，返回当前帧率（Hz），不可用返回 NaN。 */
    public float sample() {
        // 1. 尝试 shell 通道（最准确）
        float fps = sampleShell();
        if (!Float.isNaN(fps)) return fps;

        // 2. 尝试 WindowManager 显示刷新率（需要 Context）
        fps = sampleDisplayRefresh();
        if (!Float.isNaN(fps)) return fps;

        // 3. 回退到 Choreographer（需要 Looper 线程）
        fps = sampleChoreographer();
        return fps;
    }

    /** 通过 SurfaceFlinger 计算真实屏幕帧率（需 shell）。 */
    private float sampleShell() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastShellTs < FPS_INTERVAL_MS) {
            return shellFps;
        }
        lastShellTs = now;
        try {
            String out = Privilege.exec("dumpsys SurfaceFlinger --latency 2>/dev/null");
            if (out == null || out.isEmpty()) {
                SysLog.w("screenFps shell: empty output, fallback");
                return shellFps;
            }
            String[] lines = out.split("\n");
            if (lines.length < 2) {
                SysLog.w("screenFps shell: too few lines (" + lines.length + "), output=" + out.substring(0, Math.min(100, out.length())));
                return shellFps;
            }

            // 第 0 行是刷新周期(ns)，其余是帧时间戳
            long refreshPeriod = 0;
            try {
                refreshPeriod = Long.parseLong(lines[0].trim());
            } catch (NumberFormatException e) {
                SysLog.w("screenFps shell: parse refreshPeriod failed, line0=" + lines[0].trim());
            }

            long nowNs = System.nanoTime();
            int frames = 0;
            for (int i = 1; i < lines.length; i++) {
                String[] cols = lines[i].trim().split("\\s+");
                if (cols.length < 2) continue;
                try {
                    long actualPresent = Long.parseLong(cols[1]);
                    if (actualPresent > 0 && nowNs - actualPresent < 1_000_000_000L) {
                        frames++;
                    }
                } catch (NumberFormatException ignored) {}
            }
            if (frames > 0) {
                shellFps = frames;
            } else if (refreshPeriod > 0) {
                shellFps = 1_000_000_000f / refreshPeriod;
                SysLog.w("screenFps shell: no active frames, period=" + refreshPeriod + "ns fps=" + shellFps);
            }
        } catch (Throwable t) {
            SysLog.w("screenFps shell: " + t);
        }
        return shellFps;
    }

    /** 通过 WindowManager 获取屏幕刷新率（恒定值，无需 shell）。 */
    private float sampleDisplayRefresh() {
        if (ctx == null) {
            if (!displayChecked) {
                SysLog.w("screenFps display: ctx is null, skip");
                displayChecked = true;
            }
            return Float.NaN;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastDisplayCheckTs < 5000 && !Float.isNaN(displayRefreshRate)) {
            return displayRefreshRate;
        }
        lastDisplayCheckTs = now;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                SysLog.w("screenFps display: WindowManager is null");
                return Float.NaN;
            }
            android.view.Display display = wm.getDefaultDisplay();
            if (display == null) {
                SysLog.w("screenFps display: DefaultDisplay is null");
                return Float.NaN;
            }
            displayRefreshRate = display.getRefreshRate();
            SysLog.w("screenFps display: refreshRate=" + displayRefreshRate + " (display=" + display + ")");
            return displayRefreshRate;
        } catch (Throwable t) {
            SysLog.w("screenFps display: " + t);
            return Float.NaN;
        }
    }

    /** Choreographer 回退：测量本应用渲染帧率。 */
    private float sampleChoreographer() {
        if (!choreoStarted) {
            choreoStarted = true;
            // 在主线程注册 Choreographer 回调
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Choreographer.getInstance().postFrameCallback(frameCallback);
                        SysLog.w("screenFps choreographer: callback registered on main thread");
                    } catch (Throwable t) {
                        SysLog.w("screenFps choreographer: register failed: " + t);
                    }
                }
            });
        }
        return appFps;
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            long now = SystemClock.elapsedRealtime();
            if (lastFrameTs > 0) {
                frameCount++;
                if (now - lastFrameTs >= FPS_INTERVAL_MS) {
                    appFps = frameCount * 1000f / (now - lastFrameTs);
                    frameCount = 0;
                    lastFrameTs = now;
                    SysLog.w("screenFps choreographer: appFps=" + appFps);
                }
            } else {
                lastFrameTs = now;
            }
            try {
                Choreographer.getInstance().postFrameCallback(this);
            } catch (Throwable ignored) {}
        }
    };
}
