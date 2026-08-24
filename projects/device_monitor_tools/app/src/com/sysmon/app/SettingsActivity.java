package com.sysmon.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** 悬浮窗设置子页。 */
public class SettingsActivity extends Activity {

    private Prefs prefs;

    private Switch swOverlay, swIgnoreTouch, swHideFullscreen, swHideLandscape, swHideScreenshot;
    private Button btnOverlayPerm, btnA11y, btnFontMinus, btnFontPlus, btnSave;
    private TextView tvFontSize, tvAlpha, tvOverlayPermStatus, tvA11yStatus;
    private SeekBar sbAlpha;

    private CheckBox cbCpuTotal, cbCpuPer, cbGpu, cbMem, cbBattTemp, cbBattLevel, cbBattVolt;
    private CheckBox cbBattCurr, cbPowerIn, cbPowerOut, cbPowerPhone, cbNet, cbFps;

    private LinearLayout llBgColors, llFgColors;

    private static final int[] BG_COLORS = {
            0xCC000000, 0xFFFFFFFF, 0xCC101418, 0xCC0A2A3A, 0xCC202020, 0xCC0D2416, 0xCC3A1414};
    private static final int[] FG_COLORS = {
            0xFF00E676, 0xFFFFFFFF, 0xFF4DD0E1, 0xFFFFD54F, 0xFFFF5252, 0xFF8BC34A, 0xFFCE93D8};
    private static final String[] FG_NAMES = {"绿", "白", "青", "黄", "红", "草", "粉"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);

        swOverlay = findViewById(R.id.swOverlay);
        swIgnoreTouch = findViewById(R.id.swIgnoreTouch);
        swHideFullscreen = findViewById(R.id.swHideFullscreen);
        swHideLandscape = findViewById(R.id.swHideLandscape);
        swHideScreenshot = findViewById(R.id.swHideScreenshot);
        btnOverlayPerm = findViewById(R.id.btnOverlayPerm);
        tvOverlayPermStatus = findViewById(R.id.tvOverlayPermStatus);
        btnA11y = findViewById(R.id.btnA11y);
        tvA11yStatus = findViewById(R.id.tvA11yStatus);
        btnFontMinus = findViewById(R.id.btnFontMinus);
        btnFontPlus = findViewById(R.id.btnFontPlus);
        tvFontSize = findViewById(R.id.tvFontSize);
        sbAlpha = findViewById(R.id.sbAlpha);
        tvAlpha = findViewById(R.id.tvAlpha);
        btnSave = findViewById(R.id.btnSave);

        cbCpuTotal = findViewById(R.id.cbCpuTotal);
        cbCpuPer = findViewById(R.id.cbCpuPer);
        cbGpu = findViewById(R.id.cbGpu);
        cbMem = findViewById(R.id.cbMem);
        cbBattTemp = findViewById(R.id.cbBattTemp);
        cbBattLevel = findViewById(R.id.cbBattLevel);
        cbBattVolt = findViewById(R.id.cbBattVolt);
        cbBattCurr = findViewById(R.id.cbBattCurrent);
        cbPowerIn = findViewById(R.id.cbPowerIn);
        cbPowerOut = findViewById(R.id.cbPowerOut);
        cbPowerPhone = findViewById(R.id.cbPowerPhone);
        cbNet = findViewById(R.id.cbNet);
        cbFps = findViewById(R.id.cbFps);

        llBgColors = findViewById(R.id.llBgColors);
        llFgColors = findViewById(R.id.llFgColors);

        renderColors();
        loadFields();

        btnOverlayPerm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                }
            }
        });

        btnA11y.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });

        btnFontMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepFont(-1);
            }
        });
        btnFontPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stepFont(1);
            }
        });

        sbAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tvAlpha.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveFields();
                if (prefs.overlayEnabled()) startOverlay();
                else stopOverlay();
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermStatus();
    }

    private void refreshPermStatus() {
        boolean canDraw = Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this);
        tvOverlayPermStatus.setText("悬浮窗权限: " + (canDraw ? "已授予 ✓" : "未授予"));
        tvOverlayPermStatus.setTextColor(canDraw ? 0xFF00E676 : 0xFFFFD54F);

        boolean a11y = isA11yEnabled();
        tvA11yStatus.setText("无障碍服务: " + (a11y ? "已开启" : "未开启（可选）"));
        tvA11yStatus.setTextColor(a11y ? 0xFF00E676 : 0xFF888888);
    }

    private boolean isA11yEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            return enabled.contains(getPackageName() + "/.SysAccessibilityService");
        } catch (Throwable t) {
            return false;
        }
    }

    private void loadFields() {
        swOverlay.setChecked(prefs.overlayEnabled());
        swIgnoreTouch.setChecked(prefs.overlayIgnoreTouch());
        swHideFullscreen.setChecked(prefs.overlayHideFullscreen());
        swHideLandscape.setChecked(prefs.overlayHideLandscape());
        swHideScreenshot.setChecked(prefs.overlayHideScreenshot());

        int mask = prefs.overlayShowMask();
        cbCpuTotal.setChecked((mask & Prefs.SHOW_CPU_TOTAL) != 0);
        cbCpuPer.setChecked((mask & Prefs.SHOW_CPU_PER) != 0);
        cbGpu.setChecked((mask & Prefs.SHOW_GPU) != 0);
        cbMem.setChecked((mask & Prefs.SHOW_MEM) != 0);
        cbBattTemp.setChecked((mask & Prefs.SHOW_BATT_TEMP) != 0);
        cbBattLevel.setChecked((mask & Prefs.SHOW_BATT_LEVEL) != 0);
        cbBattVolt.setChecked((mask & Prefs.SHOW_BATT_VOLT) != 0);
        cbBattCurr.setChecked((mask & Prefs.SHOW_BATT_CURR) != 0);
        cbPowerIn.setChecked((mask & Prefs.SHOW_POWER_IN) != 0);
        cbPowerOut.setChecked((mask & Prefs.SHOW_POWER_OUT) != 0);
        cbPowerPhone.setChecked((mask & Prefs.SHOW_POWER_PHONE) != 0);
        cbNet.setChecked((mask & Prefs.SHOW_NET) != 0);
        cbFps.setChecked((mask & Prefs.SHOW_FPS) != 0);

        int fs = prefs.overlayFontSize();
        tvFontSize.setText(fs + "sp");
        int alpha = prefs.overlayAlpha();
        sbAlpha.setProgress(alpha);
        tvAlpha.setText(alpha + "%");
    }

    private void saveFields() {
        prefs.setOverlayEnabled(swOverlay.isChecked());
        prefs.setOverlayIgnoreTouch(swIgnoreTouch.isChecked());
        prefs.setOverlayHideFullscreen(swHideFullscreen.isChecked());
        prefs.setOverlayHideLandscape(swHideLandscape.isChecked());
        prefs.setOverlayHideScreenshot(swHideScreenshot.isChecked());
        prefs.setOverlayBgColor(selectedBg());
        prefs.setOverlayFgColor(selectedFg());
        prefs.setOverlayFontSize(Integer.parseInt(tvFontSize.getText().toString().replace("sp", "")));
        prefs.setOverlayAlpha(sbAlpha.getProgress());

        int mask = 0;
        if (cbCpuTotal.isChecked()) mask |= Prefs.SHOW_CPU_TOTAL;
        if (cbCpuPer.isChecked()) mask |= Prefs.SHOW_CPU_PER;
        if (cbGpu.isChecked()) mask |= Prefs.SHOW_GPU;
        if (cbMem.isChecked()) mask |= Prefs.SHOW_MEM;
        if (cbBattTemp.isChecked()) mask |= Prefs.SHOW_BATT_TEMP;
        if (cbBattLevel.isChecked()) mask |= Prefs.SHOW_BATT_LEVEL;
        if (cbBattVolt.isChecked()) mask |= Prefs.SHOW_BATT_VOLT;
        if (cbBattCurr.isChecked()) mask |= Prefs.SHOW_BATT_CURR;
        if (cbPowerIn.isChecked()) mask |= Prefs.SHOW_POWER_IN;
        if (cbPowerOut.isChecked()) mask |= Prefs.SHOW_POWER_OUT;
        if (cbPowerPhone.isChecked()) mask |= Prefs.SHOW_POWER_PHONE;
        if (cbNet.isChecked()) mask |= Prefs.SHOW_NET;
        if (cbFps.isChecked()) mask |= Prefs.SHOW_FPS;
        prefs.setOverlayShowMask(mask);
    }

    private int selectedBg() {
        for (int i = 0; i < BG_COLORS.length; i++) {
            View v = llBgColors.getChildAt(i);
            if (v != null && v.getTag() != null && Boolean.TRUE.equals(v.getTag())) {
                return BG_COLORS[i];
            }
        }
        return prefs.overlayBgColor();
    }

    private int selectedFg() {
        for (int i = 0; i < FG_COLORS.length; i++) {
            View v = llFgColors.getChildAt(i);
            if (v != null && v.getTag() != null && Boolean.TRUE.equals(v.getTag())) {
                return FG_COLORS[i];
            }
        }
        return prefs.overlayFgColor();
    }

    private void renderColors() {
        int curBg = prefs.overlayBgColor();
        int curFg = prefs.overlayFgColor();
        llBgColors.removeAllViews();
        llFgColors.removeAllViews();
        for (int i = 0; i < BG_COLORS.length; i++) {
            View v = colorChip(BG_COLORS[i], BG_COLORS[i] == curBg);
            llBgColors.addView(v);
        }
        for (int i = 0; i < FG_COLORS.length; i++) {
            View v = colorChip(FG_COLORS[i], FG_COLORS[i] == curFg);
            TextView label = new TextView(this);
            label.setText(FG_NAMES[i]);
            label.setTextColor(0xFFD0D0D0);
            label.setTextSize(10);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            col.addView(v);
            col.addView(label);
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    int c = (Integer) view.getTag();
                    prefs.setOverlayFgColor(c);
                    renderColors();
                }
            });
            // 用实际 fg color 做 tag
            v.setTag(FG_COLORS[i]);
            ((LinearLayout.LayoutParams) v.getLayoutParams()).leftMargin = 8;
            llFgColors.addView(col);
        }
    }

    private View colorChip(final int color, final boolean selected) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(this);
        int size = (int) (24 * getResources().getDisplayMetrics().density + 0.5f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = 8;
        f.setLayoutParams(lp);
        f.setBackgroundColor(color);
        if (selected) {
            f.setPadding(2, 2, 2, 2);
            android.widget.FrameLayout inner = new android.widget.FrameLayout(this);
            inner.setBackgroundColor(0xFFFFFFFF);
            f.addView(inner);
        }
        f.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int c = (Integer) v.getTag();
                prefs.setOverlayBgColor(c);
                renderColors();
            }
        });
        f.setTag(color);
        return f;
    }

    private void stepFont(int delta) {
        int cur = Integer.parseInt(tvFontSize.getText().toString().replace("sp", ""));
        int next = Math.max(8, Math.min(24, cur + delta));
        tvFontSize.setText(next + "sp");
    }

    private void startOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        startService(new Intent(this, OverlayService.class));
    }

    private void stopOverlay() {
        stopService(new Intent(this, OverlayService.class));
    }
}
