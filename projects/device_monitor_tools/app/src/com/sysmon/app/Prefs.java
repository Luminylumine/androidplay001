package com.sysmon.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 全部设置项（SharedPreferences 封装，apply 异步写）。 */
public final class Prefs {

    private static final String FILE = "sysmon";
    private final SharedPreferences sp;

    // 悬浮窗显示项位掩码
    public static final int SHOW_CPU_TOTAL   = 1 << 0;
    public static final int SHOW_CPU_PER     = 1 << 1;
    public static final int SHOW_GPU         = 1 << 2;
    public static final int SHOW_MEM         = 1 << 3;
    public static final int SHOW_BATT_TEMP   = 1 << 4;
    public static final int SHOW_BATT_LEVEL  = 1 << 5;
    public static final int SHOW_BATT_VOLT   = 1 << 6;
    public static final int SHOW_BATT_CURR   = 1 << 7;
    public static final int SHOW_POWER_IN    = 1 << 8;
    public static final int SHOW_POWER_OUT   = 1 << 9;
    public static final int SHOW_POWER_PHONE = 1 << 10;
    public static final int SHOW_NET         = 1 << 11;
    public static final int SHOW_FPS         = 1 << 12;
    public static final int SHOW_ALL = 0xFFFF;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------- 悬浮窗 ----------
    public boolean overlayEnabled() { return sp.getBoolean("overlay_enabled", true); }
    public void setOverlayEnabled(boolean v) { sp.edit().putBoolean("overlay_enabled", v).apply(); }

    public int overlayShowMask() { return sp.getInt("overlay_show_mask", SHOW_ALL); }
    public void setOverlayShowMask(int v) { sp.edit().putInt("overlay_show_mask", v).apply(); }

    public int overlayBgColor() { return sp.getInt("overlay_bg", 0xCC000000); }
    public void setOverlayBgColor(int v) { sp.edit().putInt("overlay_bg", v).apply(); }

    public int overlayFgColor() { return sp.getInt("overlay_fg", 0xFF00E676); }
    public void setOverlayFgColor(int v) { sp.edit().putInt("overlay_fg", v).apply(); }

    public int overlayFontSize() { return sp.getInt("overlay_font", 12); }
    public void setOverlayFontSize(int v) { sp.edit().putInt("overlay_font", v).apply(); }

    public int overlayAlpha() { return sp.getInt("overlay_alpha", 100); } // 0-100
    public void setOverlayAlpha(int v) { sp.edit().putInt("overlay_alpha", v).apply(); }

    public boolean overlayIgnoreTouch() { return sp.getBoolean("overlay_ignore_touch", false); }
    public void setOverlayIgnoreTouch(boolean v) { sp.edit().putBoolean("overlay_ignore_touch", v).apply(); }

    public boolean overlayHideFullscreen() { return sp.getBoolean("overlay_hide_fullscreen", true); }
    public void setOverlayHideFullscreen(boolean v) { sp.edit().putBoolean("overlay_hide_fullscreen", v).apply(); }

    public boolean overlayHideLandscape() { return sp.getBoolean("overlay_hide_landscape", false); }
    public void setOverlayHideLandscape(boolean v) { sp.edit().putBoolean("overlay_hide_landscape", v).apply(); }

    public boolean overlayHideScreenshot() { return sp.getBoolean("overlay_hide_screenshot", true); }
    public void setOverlayHideScreenshot(boolean v) { sp.edit().putBoolean("overlay_hide_screenshot", v).apply(); }

    // 悬浮窗位置（归一化 0-1）
    public float overlayX() { return sp.getFloat("overlay_x", 0.7f); }
    public void setOverlayX(float v) { sp.edit().putFloat("overlay_x", v).apply(); }
    public float overlayY() { return sp.getFloat("overlay_y", 0.1f); }
    public void setOverlayY(float v) { sp.edit().putFloat("overlay_y", v).apply(); }

    // ---------- 采样 ----------
    public int refreshMs() { return sp.getInt("refresh_ms", 2000); }
    public void setRefreshMs(int v) { sp.edit().putInt("refresh_ms", v).apply(); }

    // ---------- 悬浮窗更新帧率 ----------
    /** 悬浮窗刷新间隔 ms（默认 2000 = 0.5Hz）。 */
    public int overlayUpdateMs() { return sp.getInt("overlay_update_ms", 2000); }
    public void setOverlayUpdateMs(int v) { sp.edit().putInt("overlay_update_ms", v).apply(); }

    // ---------- 屏幕帧率 ----------
    public boolean screenFpsEnabled() { return sp.getBoolean("screen_fps_enabled", true); }
    public void setScreenFpsEnabled(boolean v) { sp.edit().putBoolean("screen_fps_enabled", v).apply(); }

    // ---------- 隐藏后台 ----------
    public boolean hideBackground() { return sp.getBoolean("hide_background", false); }
    public void setHideBackground(boolean v) { sp.edit().putBoolean("hide_background", v).apply(); }

    // ---------- 电池电流计积分 ----------
    public boolean battIntegrateEnabled() { return sp.getBoolean("batt_integrate_enabled", true); }
    public void setBattIntegrateEnabled(boolean v) { sp.edit().putBoolean("batt_integrate_enabled", v).apply(); }

    /** 积分采样间隔 ms（默认 5000）。 */
    public int battIntegrateMs() { return sp.getInt("batt_integrate_ms", 5000); }
    public void setBattIntegrateMs(int v) { sp.edit().putInt("batt_integrate_ms", v).apply(); }

    /** 累计积分 mAh（持久化，跨重启保留）。 */
    public float battIntegratedMah() { return sp.getFloat("batt_integrated_mah", 0f); }
    public void setBattIntegratedMah(float v) { sp.edit().putFloat("batt_integrated_mah", v).apply(); }

    /** 累计放出 mAh（正数，持久化）。 */
    public float battIntegratedDischargedMah() { return sp.getFloat("batt_integrated_discharged_mah", 0f); }
    public void setBattIntegratedDischargedMah(float v) { sp.edit().putFloat("batt_integrated_discharged_mah", v).apply(); }

    // ---------- 通道 ----------
    public boolean adbAutoScan() { return sp.getBoolean("adb_auto_scan", true); }
    public void setAdbAutoScan(boolean v) { sp.edit().putBoolean("adb_auto_scan", v).apply(); }

    public String adbEndpoint() { return sp.getString("adb_endpoint", null); }
    public void setAdbEndpoint(String v) { sp.edit().putString("adb_endpoint", v).apply(); }

    // ---------- 参数校准 ----------
    /** 电池电流方向：1 = 正=充电(标准)，-1 = 正=放电(反号)，0 = 未校准。 */
    public int battPolarity() { return sp.getInt("batt_polarity", 0); }
    public void setBattPolarity(int v) { sp.edit().putInt("batt_polarity", v).apply(); }

    /** 电池电流单位倍率：raw_current × scale = µA（标准=1 或 1000 或 0.001）。 */
    public float battCurrentScale() { return sp.getFloat("batt_current_scale", Float.NaN); }
    public void setBattCurrentScale(float v) { sp.edit().putFloat("batt_current_scale", v).apply(); }

    /** 校准结果版本（OTA后应重新校准）。 */
    public String battCalibFingerprint() { return sp.getString("batt_calib_fp", null); }
    public void setBattCalibFingerprint(String v) { sp.edit().putString("batt_calib_fp", v).apply(); }

    // ---------- 绘图（每子选项独立一组，未设置时回退"表格默认格式"） ----------
    public static final String P_ENABLED       = "enabled";
    public static final String P_FREQ_MS       = "freq_ms";
    public static final String P_RETENTION_SEC = "retention_sec";
    public static final String P_MAX_POINTS    = "max_points";
    public static final String P_X_TICKS       = "x_ticks";
    public static final String P_Y_TICKS       = "y_ticks";
    public static final String P_X_GRID        = "x_grid";
    public static final String P_Y_GRID        = "y_grid";
    public static final String P_X_CROSS       = "x_cross";
    public static final String P_Y_CROSS       = "y_cross";
    public static final String P_AXIS_W        = "axis_w";
    public static final String P_POINT_SZ      = "point_sz";
    public static final String P_GRID_W        = "grid_w";

    private static final String[] PLOT_KEYS = {
            P_ENABLED, P_FREQ_MS, P_RETENTION_SEC, P_MAX_POINTS,
            P_X_TICKS, P_Y_TICKS, P_X_GRID, P_Y_GRID, P_X_CROSS, P_Y_CROSS,
            P_AXIS_W, P_POINT_SZ, P_GRID_W
    };

    /** 绘图项取值：本项已设置则用本项，否则回退"表格默认格式"。 */
    public boolean plotBool(String plot, String s) {
        String k = "plot_" + plot + "_" + s;
        return sp.contains(k) ? sp.getBoolean(k, false) : plotDefBool(s);
    }
    public void setPlotBool(String plot, String s, boolean v) {
        sp.edit().putBoolean("plot_" + plot + "_" + s, v).apply();
    }
    public int plotInt(String plot, String s) {
        String k = "plot_" + plot + "_" + s;
        if (sp.contains(k)) return sp.getInt(k, 0);
        if (s.equals(P_FREQ_MS)) {
            // 回退顺序：该项自身默认频率 → 表格默认格式
            com.sysmon.app.collect.PlotItems.Item it =
                    com.sysmon.app.collect.PlotItems.byKey(plot);
            if (it != null && it.defFreqMs > 0) return it.defFreqMs;
        }
        return plotDefInt(s);
    }
    public void setPlotInt(String plot, String s, int v) {
        sp.edit().putInt("plot_" + plot + "_" + s, v).apply();
    }
    /** 恢复默认：删除本项全部设置，回落到"表格默认格式"。 */
    public void removePlot(String plot) {
        SharedPreferences.Editor e = sp.edit();
        for (String s : PLOT_KEYS) e.remove("plot_" + plot + "_" + s);
        e.apply();
    }
    /** 本项是否已显式设置过（用于设置页显示"已自定义/用默认"）。 */
    public boolean plotIsSet(String plot, String s) {
        return sp.contains("plot_" + plot + "_" + s);
    }

    // ---------- 表格默认格式 ----------
    public boolean plotDefBool(String s) {
        String k = "plotdef_" + s;
        return sp.contains(k) ? sp.getBoolean(k, false) : plotDefHardB(s);
    }
    public void setPlotDefBool(String s, boolean v) { sp.edit().putBoolean("plotdef_" + s, v).apply(); }
    public int plotDefInt(String s) {
        String k = "plotdef_" + s;
        return sp.contains(k) ? sp.getInt(k, 0) : plotDefHard(s);
    }
    public void setPlotDefInt(String s, int v) { sp.edit().putInt("plotdef_" + s, v).apply(); }
    /** 恢复出厂：删除单个默认值项（回落到硬编码默认）。 */
    public void removePlotDef(String s) { sp.edit().remove("plotdef_" + s).apply(); }

    /** 硬编码默认：禁用、1Hz、1h、3600点、轴4px、点3px、网格2px。 */
    private static int plotDefHard(String s) {
        switch (s) {
            case P_FREQ_MS:       return 1000;
            case P_RETENTION_SEC: return 3600;
            case P_MAX_POINTS:    return 3600;
            case P_AXIS_W:        return 4;
            case P_POINT_SZ:      return 3;
            case P_GRID_W:        return 2;
            default:              return 0;
        }
    }
    private static boolean plotDefHardB(String s) {
        switch (s) {
            case P_X_TICKS:
            case P_Y_TICKS: return true;   // 坐标轴打点默认开
            default:        return false;  // enabled/网格/格点默认关
        }
    }
}
