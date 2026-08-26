package com.sysmon.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.sysmon.app.collect.MonitorEngine;
import com.sysmon.app.collect.PlotItems;
import com.sysmon.app.collect.PlotRecorder;
import com.sysmon.app.collect.PlotStore;
import com.sysmon.app.collect.SysData;

import java.util.Locale;

/**
 * 主页：htop/btop 风格系统监控。
 * 底部导航：监控 / 设置。
 */
public class MainActivity extends Activity {

    private static final int FG = 0xFFD0D0D0;
    private static final int ACCENT = 0xFF4DD0E1;
    private static final int DIM = 0xFF888888;
    private static final int GREEN = 0xFF00E676;
    private static final int YELLOW = 0xFFFFD54F;
    private static final int RED = 0xFFFF5252;

    private LinearLayout llContent;
    private android.widget.ScrollView scrollMonitor;
    private android.widget.ScrollView scrollDraw;
    private LinearLayout llDraw;
    private TextView tvTitle, tvChannel;
    private TextView tabMonitor, tabDraw, tabSettings;
    private Prefs prefs;
    private MonitorEngine engine;
    private PlotRecorder plotRecorder;

    // 绘图页
    private PlotView[] plotViews;
    private boolean[] plotExpanded;
    private TextView[] plotTitleTvs;
    private int currentTab = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    // 保留旧引用以兼容layout
    @SuppressWarnings("unused")
    private View llChannel;
    @SuppressWarnings("unused")
    private LinearLayout llChannelInner;

    // 监控行引用
    private BarView barCpuTotal;
    private TextView tvCpuTotal;
    private View coresWrapView;  // 每核行容器
    private BarView barGpu;
    private TextView tvGpu;
    private BarView barMem;
    private TextView tvMem;
    private BarView barSwap;
    private TextView tvSwap;
    private TextView tvBatt, tvTemp, tvVolt, tvCurr, tvPowerIn, tvPowerOut, tvPowerPhone, tvNet, tvLoad, tvUptime, tvCpuTemp;
    private TextView tvFps, tvBattCharged, tvBattDischarged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        engine = MonitorEngine.get(this);

        llContent = findViewById(R.id.llContent);
        scrollMonitor = findViewById(R.id.scrollMonitor);
        scrollDraw = findViewById(R.id.scrollDraw);
        llDraw = findViewById(R.id.llDraw);
        llChannel = findViewById(R.id.llChannelWrap);
        llChannelInner = findViewById(R.id.llChannel);
        tvTitle = findViewById(R.id.tvTitle);
        tvChannel = findViewById(R.id.tvChannel);
        tabMonitor = findViewById(R.id.tabMonitor);
        tabDraw = findViewById(R.id.tabDraw);
        tabSettings = findViewById(R.id.tabChannel);

        plotRecorder = PlotRecorder.get(this);
        plotRecorder.start();

        buildMonitorRows();
        buildPlotRows();
        buildSettingsRows();

        tabMonitor.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(0);
            }
        });
        tabDraw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(1);
            }
        });
        tabSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab(2);
            }
        });

        Privilege.init(this);
        applyHideBackground();
        switchTab(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Privilege.reset();
        Privilege.ensure();
        engine.start();
        // 主界面可见：全量采样
        engine.setLightMode(false);
        // 自动请求 Shizuku 权限（如果 Shizuku 在运行但未授权）
        int ss = Privilege.shizukuStatus();
        SysLog.w("shizukuStatus=" + ss + " installed=" + Privilege.shizukuInstalled());
        if (ss == 0) {
            SysLog.w("Shizuku running but not authorized, requesting permission...");
            Privilege.requestShizukuPermission();
        }
        // 自动启动悬浮窗（如果已开启）
        if (prefs.overlayEnabled()) {
            try {
                startService(new Intent(this, OverlayService.class));
            } catch (Throwable t) {
                SysLog.w("auto start overlay: " + t);
            }
        }
        updatePlotRowStates();
        if (currentTab == 1) refreshPlots();
        refreshBatteryOptState();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        // 主界面不可见：若悬浮窗在运行则进入轻量模式
        if (prefs.overlayEnabled()) {
            engine.setLightMode(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(ticker);
    }

    private void switchTab(int idx) {
        currentTab = idx;
        // 必须切换整个 ScrollView（weight=1），只隐藏内部 LinearLayout 会留下半屏空白
        if (scrollMonitor != null) scrollMonitor.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        llContent.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        if (scrollDraw != null) scrollDraw.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        if (llChannel != null) llChannel.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        setTab(tabMonitor, idx == 0);
        setTab(tabDraw, idx == 1);
        setTab(tabSettings, idx == 2);
        if (idx == 1) refreshPlots();
    }

    private void setTab(TextView t, boolean sel) {
        t.setTextColor(sel ? ACCENT : DIM);
        t.setTextSize(sel ? 14 : 13);
        t.setTypeface(android.graphics.Typeface.MONOSPACE, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    // ---------- 监控行 ----------

    private void buildMonitorRows() {
        addSection(llContent, "── CPU ──");
        BarRow total = addBarRow(llContent, "CPU");
        barCpuTotal = total.bar;
        tvCpuTotal = total.tv;

        LinearLayout coresWrap = new LinearLayout(this);
        coresWrap.setOrientation(LinearLayout.VERTICAL);
        coresWrap.setTag("coresWrap");
        llContent.addView(coresWrap, lp());
        coresWrapView = coresWrap;
        // 每核行动态创建（采样后按核数填充）
        for (int i = 0; i < 8; i++) {
            BarRow r = addBarRow(coresWrap, "CPU" + i);
            r.bar.setVisibility(View.GONE);
            r.tv.setVisibility(View.GONE);
        }

        addSection(llContent, "── GPU ──");
        BarRow gpu = addBarRow(llContent, "GPU");
        barGpu = gpu.bar;
        tvGpu = gpu.tv;

        addSection(llContent, "── 内存 ──");
        BarRow mem = addBarRow(llContent, "MEM");
        barMem = mem.bar;
        tvMem = mem.tv;
        BarRow swap = addBarRow(llContent, "SWAP");
        barSwap = swap.bar;
        tvSwap = swap.tv;

        addSection(llContent, "── 电池 ──");
        tvBatt = addTextRow(llContent, "容量");
        tvTemp = addTextRow(llContent, "温度");
        tvVolt = addTextRow(llContent, "电压");
        tvCurr = addTextRow(llContent, "电流");
        tvPowerIn = addTextRow(llContent, "输入功率");
        tvPowerOut = addTextRow(llContent, "输出功率");
        tvPowerPhone = addTextRow(llContent, "整机功率");
        tvCpuTemp = addTextRow(llContent, "CPU温度");

        addSection(llContent, "── 网络 ──");
        tvNet = addTextRow(llContent, "速率");

        addSection(llContent, "── 屏幕 ──");
        tvFps = addTextRow(llContent, "帧率");

        addSection(llContent, "── 电池积分 ──");
        tvBattCharged = addTextRow(llContent, "充入");
        tvBattDischarged = addTextRow(llContent, "放出");

        addSection(llContent, "── 负载 / 运行 ──");
        tvLoad = addTextRow(llContent, "负载");
        tvUptime = addTextRow(llContent, "运行");
    }

    private void addSection(LinearLayout parent, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ACCENT);
        t.setTextSize(13);
        t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = lp();
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(4);
        parent.addView(t, lp);
    }

    private static class BarRow {
        BarView bar;
        TextView tv;
    }

    private BarRow addBarRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(FG);
        lab.setTextSize(12);
        lab.setTypeface(android.graphics.Typeface.MONOSPACE);
        lab.setWidth(dp(52));

        BarView bar = new BarView(this);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        barLp.leftMargin = dp(4);
        barLp.rightMargin = dp(6);

        TextView val = new TextView(this);
        val.setTextColor(GREEN);
        val.setTextSize(12);
        val.setTypeface(android.graphics.Typeface.MONOSPACE);
        val.setWidth(dp(64));
        val.setGravity(Gravity.END);

        row.addView(lab);
        row.addView(bar, barLp);
        row.addView(val);
        parent.addView(row, lp());

        BarRow r = new BarRow();
        r.bar = bar;
        r.tv = val;
        return r;
    }

    private TextView addTextRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(DIM);
        lab.setTextSize(12);
        lab.setTypeface(android.graphics.Typeface.MONOSPACE);
        lab.setWidth(dp(52));

        TextView val = new TextView(this);
        val.setTextColor(FG);
        val.setTextSize(12);
        val.setTypeface(android.graphics.Typeface.MONOSPACE);

        row.addView(lab);
        row.addView(val);
        parent.addView(row, lp());
        return val;
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- 刷新 ----------

    private void refresh() {
        SysData d = engine.latest();
        String src = Privilege.source();
        tvChannel.setText("通道:" + (src == null ? "无权限" : src));
        tvChannel.setTextColor(src == null ? YELLOW : GREEN);

        // CPU
        if (d.hasCpu()) {
            barCpuTotal.setValue(d.cpuTotal);
            tvCpuTotal.setText(fmtPct(d.cpuTotal));
        } else {
            barCpuTotal.setValue(0);
            tvCpuTotal.setText("N/A");
        }
        // 每核
        int n = d.cpuPer.length;
        LinearLayout coresWrap = (coresWrapView instanceof LinearLayout) ? (LinearLayout) coresWrapView : null;
        if (coresWrap != null) {
            int childCount = coresWrap.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = coresWrap.getChildAt(i);
                if (child instanceof LinearLayout) {
                    LinearLayout row = (LinearLayout) child;
                    if (row.getChildCount() >= 3) {
                        BarView bar = (BarView) row.getChildAt(1);
                        TextView tv = (TextView) row.getChildAt(2);
                        if (i < n) {
                            bar.setVisibility(View.VISIBLE);
                            tv.setVisibility(View.VISIBLE);
                            bar.setValue(d.cpuPer[i]);
                            tv.setText(fmtPct(d.cpuPer[i]));
                        } else {
                            bar.setVisibility(View.GONE);
                            tv.setVisibility(View.GONE);
                        }
                    }
                }
            }
        }

        // GPU
        if (d.hasGpu()) {
            barGpu.setValue(d.gpuUtil);
            tvGpu.setText(fmtPct(d.gpuUtil) + (d.gpuFreq > 0 ? " " + d.gpuFreq + "MHz" : ""));
        } else {
            barGpu.setValue(0);
            tvGpu.setText("N/A");
        }

        // 内存
        if (d.hasMem()) {
            barMem.setValue(d.memUsedPct());
            tvMem.setText(String.format(Locale.US, "%d/%dMB", d.memUsedKB() / 1024, d.memTotal / 1024));
            if (d.swapTotal > 0) {
                long used = d.swapTotal - d.swapFree;
                barSwap.setValue(used * 100f / d.swapTotal);
                tvSwap.setText(String.format(Locale.US, "%d/%dMB", used / 1024, d.swapTotal / 1024));
            } else {
                barSwap.setValue(0);
                tvSwap.setText("N/A");
            }
        } else {
            barMem.setValue(0);
            tvMem.setText("N/A");
            barSwap.setValue(0);
            tvSwap.setText("N/A");
        }

        // 电池
        tvBatt.setText(d.battLevel >= 0 ? d.battLevel + "%" + (d.charging() ? " 充电中" : " 放电中") : "N/A");
        tvTemp.setText(d.battTempC >= 0 ? String.format(Locale.US, "%.1f°C", d.battTempC) : "N/A");
        tvVolt.setText(d.battVolt > 0 ? d.battVolt + " mV" : "N/A");
        tvCurr.setText(d.hasBattCurrent() ? fmtCurrent(d.battCurrent) : "N/A");
        tvPowerIn.setText(fmtPower(d.powerIn));
        tvPowerOut.setText(fmtPower(d.powerOut));
        tvPowerPhone.setText(fmtPower(d.powerPhone));
        tvCpuTemp.setText(Float.isNaN(d.cpuTemp) ? "N/A" : String.format(Locale.US, "%.1f°C", d.cpuTemp));

        // 网络
        tvNet.setText(String.format(Locale.US, "↓%s  ↑%s  (%s)",
                fmtRate(d.netRxRate), fmtRate(d.netTxRate), d.netIface.isEmpty() ? "?" : d.netIface));

        // 屏幕帧率
        tvFps.setText(Float.isNaN(d.screenFps) ? "N/A" : String.format(Locale.US, "%.0f Hz", d.screenFps));

        // 电池积分
        tvBattCharged.setText(Float.isNaN(d.battChargedMah) ? "N/A" : String.format(Locale.US, "%.1f mAh", d.battChargedMah));
        tvBattDischarged.setText(Float.isNaN(d.battDischargedMah) ? "N/A" : String.format(Locale.US, "%.1f mAh", d.battDischargedMah));

        // 负载 / 运行
        tvLoad.setText(Float.isNaN(d.load1) ? "N/A" : String.format(Locale.US, "%.2f %.2f %.2f", d.load1, d.load5, d.load15));
        tvUptime.setText(d.uptimeSec >= 0 ? fmtUptime(d.uptimeSec) : "N/A");

        // 绘图页可见时同步刷新展开的图
        if (currentTab == 1) refreshPlots();
    }

    private String fmtPct(float v) {
        return Float.isNaN(v) ? "--%" : String.format(Locale.US, "%3.0f%%", v);
    }

    private String fmtPower(float p) {
        if (Float.isNaN(p)) return "N/A";
        // 当功率值较大时显示 W，较小时显示 mW
        if (Math.abs(p) >= 1000f) {
            return String.format(Locale.US, "%.2f W", p / 1000f);
        } else if (Math.abs(p) >= 1f) {
            return String.format(Locale.US, "%.1f mW", p);
        } else {
            return String.format(Locale.US, "%.0f mW", p);
        }
    }

    private String fmtCurrent(int ma) {
        if (ma == Integer.MIN_VALUE) return "N/A";
        // 电流单位是 mA，直接显示
        return String.format(Locale.US, "%.2f mA", ma / 1.0f);
    }

    private String fmtRate(float kb) {
        return Float.isNaN(kb) ? "--" : String.format(Locale.US, "%.0fK/s", kb);
    }

    private String fmtUptime(long sec) {
        long d = sec / 86400, h = (sec % 86400) / 3600, m = (sec % 3600) / 60;
        return d > 0 ? String.format(Locale.US, "%dd%dh", d, h) : String.format(Locale.US, "%dh%dm", h, m);
    }

    // ---------- 绘图页 ----------

    /** 构建子选项行：标题 + 展开（手风琴）+ 右侧"设置"按钮。 */
    private void buildPlotRows() {
        addSection(llDraw, "── 绘图 ──");

        // 全局操作行：重置 / 失能 / 使能（应用于所有绘图对象）
        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setGravity(Gravity.CENTER_VERTICAL);
        addActionButton(actRow, "重置", "所有项回落到表格默认格式", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (PlotItems.Item it : PlotItems.ALL) prefs.removePlot(it.key);
                android.widget.Toast.makeText(MainActivity.this,
                        "已重置全部绘图项为默认", android.widget.Toast.LENGTH_SHORT).show();
                updatePlotRowStates();
                refreshPlots();
            }
        });
        addActionButton(actRow, "失能", "全部停止采样", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (PlotItems.Item it : PlotItems.ALL)
                    prefs.setPlotBool(it.key, Prefs.P_ENABLED, false);
                android.widget.Toast.makeText(MainActivity.this,
                        "已全部失能", android.widget.Toast.LENGTH_SHORT).show();
                updatePlotRowStates();
                refreshPlots();
            }
        });
        addActionButton(actRow, "使能", "全部开始采样", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (PlotItems.Item it : PlotItems.ALL)
                    prefs.setPlotBool(it.key, Prefs.P_ENABLED, true);
                android.widget.Toast.makeText(MainActivity.this,
                        "已全部使能", android.widget.Toast.LENGTH_SHORT).show();
                updatePlotRowStates();
                refreshPlots();
            }
        });
        llDraw.addView(actRow, lp());

        int n = PlotItems.ALL.length;
        plotViews = new PlotView[n];
        plotExpanded = new boolean[n];
        plotTitleTvs = new TextView[n];

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final PlotItems.Item it = PlotItems.ALL[i];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            final TextView t = new TextView(this);
            plotTitleTvs[idx] = t;
            t.setText((plotExpanded[idx] ? "▼ " : "▶ ") + it.title + " (" + it.unit + ")");
            t.setTextColor(FG);
            t.setTextSize(13);
            t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            t.setId(View.generateViewId());
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, -2, 1);
            row.addView(t, tLp);

            Button b = new Button(this);
            b.setText("设置");
            b.setTextSize(11);
            b.setMinWidth(0);
            b.setAllCaps(false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent in = new Intent(MainActivity.this, DrawSettingActivity.class);
                    in.putExtra("plot", it.key);
                    startActivity(in);
                }
            });
            row.addView(b);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    plotExpanded[idx] = !plotExpanded[idx];
                    updatePlotRowStates();
                    plotViews[idx].setVisibility(plotExpanded[idx] ? View.VISIBLE : View.GONE);
                    if (plotExpanded[idx]) refreshPlots();
                }
            });
            llDraw.addView(row, lp());

            PlotView pv = new PlotView(this);
            pv.setVisibility(View.GONE);
            plotViews[idx] = pv;
            llDraw.addView(pv, lp());
        }
        updatePlotRowStates();
    }

    /** 刷新每行标题的 使能(●)/失能(○) 指示。 */
    private void updatePlotRowStates() {
        if (plotTitleTvs == null) return;
        for (int i = 0; i < PlotItems.ALL.length; i++) {
            PlotItems.Item it = PlotItems.ALL[i];
            boolean en = prefs.plotBool(it.key, Prefs.P_ENABLED);
            plotTitleTvs[i].setText((plotExpanded[i] ? "▼ " : "▶ ") + (en ? "● " : "○ ")
                    + it.title + " (" + it.unit + ")");
            plotTitleTvs[i].setTextColor(en ? GREEN : DIM);
        }
    }

    /** 已使能但暂无数据时的提示。 */
    private String emptyHint(int count, PlotItems.Item it) {
        if (count > 0) return null;
        if (it.key.equals("cpu_use") && !Privilege.available())
            return "无数据（需 Shizuku/Dhizuku/ADB 通道）";
        return "无数据";
    }

    /** 刷新所有展开的图（数据 + 样式）。失能项不显示旧数据，显示"已失能"。 */
    private void refreshPlots() {
        if (plotViews == null) return;
        for (int i = 0; i < PlotItems.ALL.length; i++) {
            if (!plotExpanded[i]) continue;
            PlotItems.Item it = PlotItems.ALL[i];
            int maxPts = prefs.plotInt(it.key, Prefs.P_MAX_POINTS);
            if (maxPts < 10) maxPts = 10;
            long[] tsBuf = new long[maxPts + 16];
            float[] valBuf = new float[maxPts + 16];
            int c = 0;
            boolean en = prefs.plotBool(it.key, Prefs.P_ENABLED);
            if (en) {
                PlotStore st = plotRecorder.store(it.key);
                c = st.snapshot(tsBuf, valBuf);
            }
            plotViews[i].setData(tsBuf, valBuf, c, prefs.plotInt(it.key, Prefs.P_FREQ_MS));
            plotViews[i].setHint(en ? emptyHint(c, it) : "已失能");
            plotViews[i].setStyle(
                    it.title + " (" + it.unit + ")",
                    prefs.plotBool(it.key, Prefs.P_X_TICKS),
                    prefs.plotBool(it.key, Prefs.P_Y_TICKS),
                    prefs.plotBool(it.key, Prefs.P_X_GRID),
                    prefs.plotBool(it.key, Prefs.P_Y_GRID),
                    prefs.plotBool(it.key, Prefs.P_X_CROSS),
                    prefs.plotBool(it.key, Prefs.P_Y_CROSS),
                    prefs.plotInt(it.key, Prefs.P_AXIS_W),
                    prefs.plotInt(it.key, Prefs.P_POINT_SZ),
                    prefs.plotInt(it.key, Prefs.P_GRID_W));
            plotViews[i].invalidate();
        }
    }

    // ---------- 设置页 ----------

    private Switch swHideBg;
    private TextView tvBatteryOpt;

    private void buildSettingsRows() {
        addSection(llChannelInner, "── 设置 ──");

        // 后台保活（长期采集关键项）
        addSection(llChannelInner, "── 后台保活（长期采集） ──");
        LinearLayout rowKeep = new LinearLayout(this);
        rowKeep.setOrientation(LinearLayout.HORIZONTAL);
        rowKeep.setGravity(Gravity.CENTER_VERTICAL);
        rowKeep.setPadding(0, dp(6), 0, dp(6));
        tvBatteryOpt = new TextView(this);
        tvBatteryOpt.setTextSize(12);
        tvBatteryOpt.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams keepLp = new LinearLayout.LayoutParams(0, -2, 1);
        rowKeep.addView(tvBatteryOpt, keepLp);
        Button bKeep = new Button(this);
        bKeep.setText("申请豁免");
        bKeep.setTextSize(11);
        bKeep.setMinWidth(0);
        bKeep.setAllCaps(false);
        bKeep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestBatteryOptExemption();
            }
        });
        rowKeep.addView(bKeep);
        llChannelInner.addView(rowKeep, lp());
        TextView keepNote = new TextView(this);
        keepNote.setText("长期后台监控（绘图/悬浮窗）需要保活，否则系统杀进程后数据断线。"
                + "华为/EMUI 另需：设置→电池→启动管理→SysMon→关闭自动管理（允许后台活动）。");
        keepNote.setTextColor(DIM);
        keepNote.setTextSize(11);
        keepNote.setTypeface(android.graphics.Typeface.MONOSPACE);
        llChannelInner.addView(keepNote, lp());
        refreshBatteryOptState();

        // 悬浮窗设置
        addEntryRow(llChannelInner, "悬浮窗设置", "外观/显示项/行为", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        // 权限通道
        addEntryRow(llChannelInner, "权限通道", "Shizuku/Dhizuku/ADB", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ChannelActivity.class));
            }
        });

        // 屏幕帧率
        addEntryRow(llChannelInner, "屏幕帧率", "帧率统计/悬浮窗更新频率", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, FpsActivity.class));
            }
        });

        // 电池积分
        addEntryRow(llChannelInner, "电池积分", "电流计积分/累计电量", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, BatteryActivity.class));
            }
        });

        // 参数校准
        addEntryRow(llChannelInner, "参数校准", "电池电流方向/单位倍率校准", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, CalibrationActivity.class));
            }
        });

        // 表格默认格式
        addEntryRow(llChannelInner, "表格默认格式", "绘图默认:使能/频率/时长/点数/格式", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DrawDefaultsActivity.class));
            }
        });

        // 隐藏后台
        addSection(llChannelInner, "── 隐藏后台 ──");
        LinearLayout rowHide = new LinearLayout(this);
        rowHide.setOrientation(LinearLayout.HORIZONTAL);
        rowHide.setGravity(Gravity.CENTER_VERTICAL);
        TextView labHide = new TextView(this);
        labHide.setText("隐藏后台");
        labHide.setTextColor(FG);
        labHide.setTextSize(13);
        labHide.setTypeface(android.graphics.Typeface.MONOSPACE);
        labHide.setWidth(dp(90));
        swHideBg = new Switch(this);
        swHideBg.setChecked(prefs.hideBackground());
        swHideBg.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.setHideBackground(isChecked);
                applyHideBackground();
            }
        });
        rowHide.addView(labHide);
        rowHide.addView(swHideBg);
        llChannelInner.addView(rowHide, lp());

        TextView note = new TextView(this);
        note.setText("隐藏后台：开启后最近任务不显示应用截图，截屏/录屏不暴露内容。");
        note.setTextColor(DIM);
        note.setTextSize(11);
        note.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams noteLp = lp();
        noteLp.topMargin = dp(4);
        llChannelInner.addView(note, noteLp);
    }

    /** 刷新"电池优化豁免"状态显示。 */
    private void refreshBatteryOptState() {
        if (tvBatteryOpt == null) return;
        boolean ok = batteryOptGranted();
        tvBatteryOpt.setText(ok ? "电池优化：已豁免" : "电池优化：未豁免（后台可能被杀）");
        tvBatteryOpt.setTextColor(ok ? GREEN : YELLOW);
    }

    private boolean batteryOptGranted() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 请求忽略电池优化（系统对话框，一次性授权）。 */
    private void requestBatteryOptExemption() {
        try {
            if (batteryOptGranted()) {
                android.widget.Toast.makeText(this, "已豁免电池优化", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            android.widget.Toast.makeText(this,
                    "请手动在 设置→电池 中搜索本应用并关闭优化", android.widget.Toast.LENGTH_LONG).show();
        }
        refreshBatteryOptState();
    }

    /** 绘图页全局操作按钮（三等分宽）。 */
    private void addActionButton(LinearLayout parent, String label, String desc, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setContentDescription(desc);
        b.setTextSize(12);
        b.setMinWidth(0);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        parent.addView(b, lp);
    }

    /** 设置入口行：标签 + 描述 + 按钮。 */
    private void addEntryRow(LinearLayout parent, String title, String desc, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(FG);
        t.setTextSize(13);
        t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(DIM);
        d.setTextSize(11);
        d.setTypeface(android.graphics.Typeface.MONOSPACE);
        col.addView(t);
        col.addView(d);

        Button b = new Button(this);
        b.setText("进入");
        b.setTextSize(11);
        b.setMinWidth(0);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);

        row.addView(col);
        row.addView(b);
        parent.addView(row, lp());
    }

    /** 应用隐藏后台设置：FLAG_SECURE + 最近任务截图禁用（隐藏 API 用反射）。 */
    private void applyHideBackground() {
        boolean hide = prefs.hideBackground();
        try {
            if (hide) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    java.lang.reflect.Method m = Activity.class.getMethod("setRecentsScreenshotEnabled", boolean.class);
                    m.invoke(this, !hide);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            SysLog.w("applyHideBackground: " + t);
        }
    }
}
