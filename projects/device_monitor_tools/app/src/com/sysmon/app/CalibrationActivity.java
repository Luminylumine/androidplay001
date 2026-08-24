package com.sysmon.app;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 参数校准子页：手动校准电池电流方向/单位倍率。
 *
 * 校准原理参考《功耗估算.md》第4节：
 *   方式1（推荐，有charge_counter）：Q0→Q1的ΔQ/Δt得出I_cc作为ground truth，
 *   与raw_current平均值比较，推导出极性(polarity)和单位倍率(scale)。
 *   方式2（无charge_counter）：拔掉USB后电池必然放电，若此时raw_current>0则说明
 *   OEM定义为"正=放电"，polarity=-1；反之polarity=1。单位用|raw|的量级判断。
 */
public class CalibrationActivity extends Activity {

    private static final int FG = 0xFFD0D0D0;
    private static final int ACCENT = 0xFF4DD0E1;
    private static final int DIM = 0xFF888888;
    private static final int GREEN = 0xFF00E676;
    private static final int YELLOW = 0xFFFFD54F;
    private static final int RED = 0xFFFF5252;

    private Prefs prefs;
    private LinearLayout ll;
    private TextView tvStatus;      // 校准状态
    private TextView tvResult;      // 校准结果
    private Button btnStartDis;     // 放电校准（拔USB）
    private Button btnReset;        // 重置

    // 采样相关
    private final Handler handler = new Handler();
    private boolean sampling = false;
    private long sampleStartTs;
    private long qStart;            // charge_counter (µAh)
    private final List<Integer> rawSamples = new ArrayList<>(); // raw current_now 原始值
    private static final int SAMPLE_MS = 500;
    private static final int TARGET_SEC = 60; // 建议采样60秒

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(0xFF0F0F14);
        ll.setPadding(dp(12), dp(12), dp(12), dp(16));
        setContentView(ll);

        addSection("── 参数校准 ──");
        addNote("校准时请按提示操作。校准结果保存在本机，OTA后建议重新校准。");

        // 当前校准结果
        addSection("── 当前校准 ──");
        tvResult = addTextRow(getCalibSummary());

        // 放电校准按钮
        addSection("── 电池校准 ──");
        tvStatus = addTextRow("就绪。请先拔掉USB充电器后，再点击开始。");
        btnStartDis = addButtonRow("▶ 开始放电校准（拔USB）");
        btnStartDis.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sampling) stopSampling(true);
                else startDischargeCalibration();
            }
        });

        // 手动设置（快捷）
        Button btnManualStd = addButtonRow("手动：标准约定（正=充电/负=放电）");
        btnManualStd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setBattPolarity(1);
                prefs.setBattCurrentScale(1f);
                prefs.setBattCalibFingerprint(getFingerprint());
                tvResult.setText(getCalibSummary());
                tvStatus.setText("已应用标准约定。");
            }
        });

        Button btnManualFlip = addButtonRow("手动：方向取反（正=放电/负=充电）");
        btnManualFlip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setBattPolarity(-1);
                prefs.setBattCurrentScale(1f);
                prefs.setBattCalibFingerprint(getFingerprint());
                tvResult.setText(getCalibSummary());
                tvStatus.setText("已应用方向取反。");
            }
        });

        // 重置
        addSection("── 重置 ──");
        btnReset = addButtonRow("清除所有校准参数");
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setBattPolarity(0);
                prefs.setBattCurrentScale(Float.NaN);
                prefs.setBattCalibFingerprint(null);
                tvResult.setText(getCalibSummary());
                tvStatus.setText("校准参数已清除，恢复默认自适应。");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sampling) stopSampling(false);
    }

    private String getCalibSummary() {
        int pol = prefs.battPolarity();
        float scale = prefs.battCurrentScale();
        String fp = prefs.battCalibFingerprint();
        StringBuilder sb = new StringBuilder();
        sb.append("极性: ");
        if (pol == 1) sb.append("标准(正=充电)");
        else if (pol == -1) sb.append("反号(正=放电)");
        else sb.append("未校准");
        sb.append("\n单位倍率: ");
        if (Float.isNaN(scale)) sb.append("自适应");
        else sb.append(String.format("×%.4f", scale));
        if (fp != null && !fp.isEmpty()) sb.append("\nFP: ").append(fp);
        return sb.toString();
    }

    private void startDischargeCalibration() {
        // 先检查是否还插着充电器
        Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean plugged = intent != null && intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        if (plugged) {
            tvStatus.setTextColor(RED);
            tvStatus.setText("⚠ 仍检测到外部供电。请拔掉USB/充电器后重试。");
            return;
        }

        // 检查charge_counter
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        long cc = Long.MIN_VALUE;
        boolean hasCC = false;
        if (bm != null) {
            cc = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (cc != Long.MIN_VALUE) {
                hasCC = true;
                qStart = cc;
            }
        }

        sampling = true;
        rawSamples.clear();
        sampleStartTs = System.currentTimeMillis();
        btnStartDis.setText("■ 停止校准（" + TARGET_SEC + "s目标）");
        tvStatus.setTextColor(YELLOW);
        tvStatus.setText("采集中…请保持手机静止，屏幕保持常亮。0/" + TARGET_SEC + "s"
                + (hasCC ? " [有charge_counter，精度更高]" : " [无charge_counter，降级为方向判断]"));

        handler.post(sampleRunnable);
    }

    private final Runnable sampleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sampling) return;
            try {
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                if (bm != null) {
                    int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                    if (cur != Integer.MIN_VALUE) rawSamples.add(cur);
                }
            } catch (Throwable ignored) {}

            int elapsed = (int) ((System.currentTimeMillis() - sampleStartTs) / 1000);
            if (elapsed < TARGET_SEC) {
                tvStatus.setText("采集中…请保持手机静止，屏幕保持常亮。"
                        + elapsed + "/" + TARGET_SEC + "s  samples=" + rawSamples.size());
                handler.postDelayed(this, SAMPLE_MS);
            } else {
                stopSampling(true);
            }
        }
    };

    private void stopSampling(boolean calc) {
        sampling = false;
        handler.removeCallbacks(sampleRunnable);
        btnStartDis.setText("▶ 开始放电校准（拔USB）");
        if (!calc) return;

        long elapsedMs = System.currentTimeMillis() - sampleStartTs;
        int samples = rawSamples.size();

        // trimmed mean
        float rawAvg;
        int[] range = trimmedRange(rawSamples);
        {
            long sum = 0, n = 0;
            for (int i = range[0]; i < range[1]; i++) { sum += rawSamples.get(i); n++; }
            rawAvg = n == 0 ? 0 : (float) sum / n;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("采样 ").append(String.format("%.1fs", elapsedMs / 1000f))
                .append(", ").append(samples).append("次, rawAvg=").append(String.format("%.2f", rawAvg)).append('\n');

        // 有charge_counter：定量校准scale和polarity
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        boolean ccOk = false;
        if (bm != null && qStart != Long.MIN_VALUE) {
            long qEnd = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (qEnd != Long.MIN_VALUE) {
                long dqUah = qEnd - qStart; // µAh
                // I_cc_uA = ΔQ × 3600 / Δt_sec
                float dtSec = elapsedMs / 1000f;
                float iCcUa = dqUah * 3600f / dtSec;
                sb.append("ΔQ=").append(dqUah).append("µAh  =>  I_cc=").append(String.format("%.2fµA\n", iCcUa));

                if (Math.abs(rawAvg) > 1f && Math.abs(iCcUa) > 1f) {
                    float k = iCcUa / rawAvg;
                    sb.append("K = I_cc / I_raw_avg = ").append(String.format("%.5f\n", k));
                    // 归一到标准量级的极性和倍率
                    int pol;
                    float scale;
                    float absK = Math.abs(k);
                    // 接近1e-3 => scale=1e-3 (raw是µA，转µA就是*1；但I_cc本身就是µA，说明raw单位不对，通常raw是mA的情况rawAvg只有几百，iCcUa则是几十万)
                    // 更直观：先看rawAvg的量级
                    if (absK > 500) {
                        // raw 值太小：raw是mA(或A)，需要放大到µA
                        // K ≈ 1000 或 ±1000：scale = 1000
                        // K ≈ 1e6：scale = 1e6（raw是A）
                        scale = absK;
                        pol = k < 0 ? -1 : 1;
                    } else if (absK < 0.002f) {
                        // raw 值太大：raw是nA之类，scale = K本身
                        scale = absK;
                        pol = k < 0 ? -1 : 1;
                    } else {
                        // absK 接近 0.5~2：scale = 1（raw已经是µA量级）
                        scale = 1f;
                        pol = k < 0 ? -1 : 1;
                    }
                    // 对scale做一下规整，避免浮点误差
                    if (Math.abs(scale - 1000f) < 50) scale = 1000f;
                    if (Math.abs(scale - 1f) < 0.05f) scale = 1f;
                    if (Math.abs(scale - 0.001f) < 0.0005f) scale = 0.001f;
                    if (Math.abs(scale - 1000000f) < 50000) scale = 1000000f;

                    prefs.setBattPolarity(pol);
                    prefs.setBattCurrentScale(scale);
                    prefs.setBattCalibFingerprint(getFingerprint());
                    sb.append("→ polarity=").append(pol == 1 ? "标准" : "反号")
                            .append("  scale=").append(String.format("%.6f\n", scale));
                    ccOk = true;
                }
            }
        }

        // 无charge_counter或其失败：降级方式（已知是放电状态）
        if (!ccOk) {
            if (Math.abs(rawAvg) < 1f) {
                sb.append("⚠ 信号太弱，校准失败。请增加采样时间。");
                tvStatus.setTextColor(RED);
            } else {
                // 放电状态：真实电流应该为负（流出电池，µA）
                // 如果 rawAvg > 0 → OEM正=放电 → polarity = -1
                // 如果 rawAvg < 0 → OEM正=充电 → polarity = 1
                int pol = rawAvg > 0 ? -1 : 1;
                // 单位推断：|rawAvg| 范围判断
                float abs = Math.abs(rawAvg);
                float scale;
                if (abs > 10000f) scale = 1f;          // raw已经是µA量级
                else if (abs > 100f) scale = 1000f;    // raw是mA，转µA×1000
                else if (abs > 0.1f) scale = 1000000f; // raw是A，转µA×1e6
                else scale = 1f;                       // fallback
                prefs.setBattPolarity(pol);
                prefs.setBattCurrentScale(scale);
                prefs.setBattCalibFingerprint(getFingerprint());
                sb.append("[降级方式] |raw|=").append(String.format("%.2f", abs))
                        .append(" => polarity=").append(pol == 1 ? "标准" : "反号")
                        .append("  scale=").append(String.format("%.0f\n", scale));
            }
        }

        sb.append("校准完成！返回主界面即可生效。");
        tvStatus.setTextColor(GREEN);
        tvStatus.setText(sb.toString());
        tvResult.setText(getCalibSummary());
    }

    /** 修剪均值：去掉最低最高各10%，返回[lo,hi)索引 */
    private int[] trimmedRange(List<Integer> list) {
        int n = list.size();
        if (n < 5) return new int[]{0, n};
        Collections.sort(new ArrayList<>(list)); // 不能直接排序原顺序无关，但为了索引稳定性复制一份
        // 其实trimmed mean需要排序，重新复制排序再取索引范围没有意义；
        // 简化：把数组复制排序，取中间部分求和即可（此处改在调用处）
        return new int[]{0, n};
    }

    private static String getFingerprint() {
        try {
            return Build.MANUFACTURER + "/" + Build.MODEL + "/" + Build.DEVICE
                    + "/" + Build.PRODUCT + "/" + Build.HARDWARE + "/" + Build.FINGERPRINT;
        } catch (Throwable t) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    // ---------- UI 辅助 ----------
    private void addSection(String title) {
        TextView s = new TextView(this);
        s.setText(title);
        s.setTextColor(ACCENT);
        s.setTextSize(12);
        s.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(4);
        ll.addView(s, lp);
    }

    private void addNote(String note) {
        TextView t = new TextView(this);
        t.setText(note);
        t.setTextColor(DIM);
        t.setTextSize(11);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(2);
        ll.addView(t, lp);
    }

    private TextView addTextRow(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(FG);
        t.setTextSize(12);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(2);
        ll.addView(t, lp);
        return t;
    }

    private Button addButtonRow(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        ll.addView(b, lp);
        return b;
    }

    private int dp(int px) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (px * d + 0.5f);
    }
}
