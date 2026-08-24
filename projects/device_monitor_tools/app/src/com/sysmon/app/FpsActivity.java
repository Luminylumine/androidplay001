package com.sysmon.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/** 屏幕帧率 + 悬浮窗更新帧率子页。 */
public class FpsActivity extends Activity {

    private static final int FG = 0xFFD0D0D0;
    private static final int ACCENT = 0xFF4DD0E1;
    private static final int DIM = 0xFF888888;
    private static final int GREEN = 0xFF00E676;

    private Prefs prefs;
    private LinearLayout ll;
    private Switch swFps;
    private TextView tvUpdateRate;

    private static final int[] UPDATE_RATES = {500, 1000, 2000, 3000, 5000};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(0xFF0F0F14);
        ll.setPadding(dp(12), dp(12), dp(12), dp(16));
        setContentView(ll);

        addSection("── 屏幕帧率 ──");

        LinearLayout rowFps = new LinearLayout(this);
        rowFps.setOrientation(LinearLayout.HORIZONTAL);
        rowFps.setGravity(Gravity.CENTER_VERTICAL);
        TextView labFps = new TextView(this);
        labFps.setText("悬浮窗显示帧率");
        labFps.setTextColor(FG);
        labFps.setTextSize(13);
        labFps.setTypeface(android.graphics.Typeface.MONOSPACE);
        labFps.setWidth(dp(110));
        swFps = new Switch(this);
        swFps.setChecked(prefs.screenFpsEnabled());
        rowFps.addView(labFps);
        rowFps.addView(swFps);
        ll.addView(rowFps, lp());

        TextView note = new TextView(this);
        note.setText("帧率统计仅用于悬浮窗显示，后台不额外读取非悬浮窗监控量。");
        note.setTextColor(DIM);
        note.setTextSize(11);
        note.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams noteLp = lp();
        noteLp.topMargin = dp(4);
        ll.addView(note, noteLp);

        addSection("── 悬浮窗更新帧率 ──");
        tvUpdateRate = addTextRow("当前", fmtRate(prefs.overlayUpdateMs()));
        LinearLayout rateRow = new LinearLayout(this);
        rateRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int r : UPDATE_RATES) {
            Button b = new Button(this);
            b.setText(r / 1000.0 + "s");
            b.setTextSize(11);
            b.setMinWidth(0);
            b.setAllCaps(false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setOverlayUpdateMs(r);
                    tvUpdateRate.setText(fmtRate(r));
                }
            });
            rateRow.addView(b);
        }
        ll.addView(rateRow, lp());

        swFps.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.setScreenFpsEnabled(isChecked);
            }
        });
    }

    private String fmtRate(int ms) {
        return String.format(Locale.US, "%.1fs", ms / 1000.0);
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
