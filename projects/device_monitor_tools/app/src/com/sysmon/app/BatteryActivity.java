package com.sysmon.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.sysmon.app.collect.MonitorEngine;

import java.util.Locale;

/** 电池电流计积分子页：开关、积分帧率、累计值、清零。 */
public class BatteryActivity extends Activity {

    private static final int FG = 0xFFD0D0D0;
    private static final int ACCENT = 0xFF4DD0E1;
    private static final int DIM = 0xFF888888;
    private static final int GREEN = 0xFF00E676;

    private Prefs prefs;
    private LinearLayout ll;
    private Switch swEnable;
    private TextView tvRate, tvCharged, tvDischarged;

    private static final int[] RATES = {1000, 2000, 5000, 10000, 30000};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(0xFF0F0F14);
        ll.setPadding(dp(12), dp(12), dp(12), dp(16));
        setContentView(ll);

        addSection("── 电池电流计积分 ──");

        // 开关
        LinearLayout rowEnable = new LinearLayout(this);
        rowEnable.setOrientation(LinearLayout.HORIZONTAL);
        rowEnable.setGravity(Gravity.CENTER_VERTICAL);
        TextView labEnable = new TextView(this);
        labEnable.setText("启用积分");
        labEnable.setTextColor(FG);
        labEnable.setTextSize(13);
        labEnable.setTypeface(android.graphics.Typeface.MONOSPACE);
        labEnable.setWidth(dp(90));
        swEnable = new Switch(this);
        swEnable.setChecked(prefs.battIntegrateEnabled());
        rowEnable.addView(labEnable);
        rowEnable.addView(swEnable);
        ll.addView(rowEnable, lp());

        // 积分帧率
        addSection("积分帧率");
        tvRate = addTextRow("当前", fmtRate(prefs.battIntegrateMs()));
        LinearLayout rateRow = new LinearLayout(this);
        rateRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int r : RATES) {
            Button b = new Button(this);
            b.setText(r / 1000 + "s");
            b.setTextSize(11);
            b.setMinWidth(0);
            b.setAllCaps(false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setBattIntegrateMs(r);
                    tvRate.setText(fmtRate(r));
                }
            });
            rateRow.addView(b);
        }
        ll.addView(rateRow, lp());

        // 累计值
        addSection("累计");
        tvCharged = addTextRow("充入", "");
        tvDischarged = addTextRow("放出", "");

        Button btnReset = new Button(this);
        btnReset.setText("清零累计");
        btnReset.setTextSize(12);
        btnReset.setAllCaps(false);
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MonitorEngine.get(BatteryActivity.this).batteryIntegrator().reset();
                refreshValues();
            }
        });
        ll.addView(btnReset, lp());

        swEnable.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.setBattIntegrateEnabled(isChecked);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshValues();
    }

    private void refreshValues() {
        MonitorEngine engine = MonitorEngine.get(this);
        tvCharged.setText(String.format(Locale.US, "%.1f mAh", engine.batteryIntegrator().chargedMah()));
        tvDischarged.setText(String.format(Locale.US, "%.1f mAh", engine.batteryIntegrator().dischargedMah()));
    }

    private String fmtRate(int ms) {
        return ms / 1000 + "s";
    }

    private void addSection(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ACCENT);
        t.setTextSize(13);
        t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = lp();
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(4);
        ll.addView(t, lp);
    }

    private TextView addTextRow(String label, String value) {
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
        val.setText(value);
        val.setTextColor(GREEN);
        val.setTextSize(12);
        val.setTypeface(android.graphics.Typeface.MONOSPACE);

        row.addView(lab);
        row.addView(val);
        ll.addView(row, lp());
        return val;
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
