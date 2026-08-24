package com.sysmon.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 表格默认格式页：各绘图子选项未显式设置时的回退值。
 * 默认：不使能、1Hz、1h、3600 点、轴 4px、点 3px、网格 2px。
 */
public class DrawDefaultsActivity extends Activity {

    private static final int FG = 0xFFD0D0D0;
    private static final int ACCENT = 0xFF4DD0E1;
    private static final int DIM = 0xFF888888;

    private Prefs prefs;
    private LinearLayout ll;

    private static final String[] FREQ_UNITS = {"ms", "s"};
    private static final int[] FREQ_UNIT_MS = {1, 1000};
    private static final String[] RET_UNITS = {"s", "m", "h", "d"};
    private static final int[] RET_UNIT_SEC = {1, 60, 3600, 86400};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setBackgroundColor(0xFF0F0F14);
        ll.setPadding(dp(12), dp(12), dp(12), dp(16));
        setContentView(ll);

        addSection("── 表格默认格式 ──");

        TextView note = new TextView(this);
        note.setText("各绘图子选项未单独设置时的回退值。");
        note.setTextColor(DIM);
        note.setTextSize(11);
        note.setTypeface(android.graphics.Typeface.MONOSPACE);
        ll.addView(note, lp());

        // 默认采样使能
        LinearLayout rowEn = new LinearLayout(this);
        rowEn.setOrientation(LinearLayout.HORIZONTAL);
        rowEn.setGravity(Gravity.CENTER_VERTICAL);
        rowEn.addView(makeLabel("默认采样使能", 110));
        Switch sw = new Switch(this);
        sw.setChecked(prefs.plotDefBool(Prefs.P_ENABLED));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.setPlotDefBool(Prefs.P_ENABLED, isChecked);
            }
        });
        rowEn.addView(sw);
        ll.addView(rowEn, lp());

        // 默认采样频率
        addSection("默认采样频率");
        addNumRow("频率", FREQ_UNITS, FREQ_UNIT_MS, prefs.plotDefInt(Prefs.P_FREQ_MS), 1,
                Prefs.P_FREQ_MS);

        // 默认保留时间
        addSection("默认保留采样时间");
        addNumRow("保留时长", RET_UNITS, RET_UNIT_SEC, prefs.plotDefInt(Prefs.P_RETENTION_SEC), 1,
                Prefs.P_RETENTION_SEC);

        // 默认最大点数
        addSection("默认最多保留采样点");
        addNumRowPlain("点数上限", prefs.plotDefInt(Prefs.P_MAX_POINTS), 2, Prefs.P_MAX_POINTS);

        // 默认格式
        addSection("默认 X 轴格式");
        addCheckRow("坐标轴打点", prefs.plotDefBool(Prefs.P_X_TICKS), Prefs.P_X_TICKS);
        addCheckRow("纵向网格线", prefs.plotDefBool(Prefs.P_X_GRID), Prefs.P_X_GRID);
        addCheckRow("格点(交叉点)", prefs.plotDefBool(Prefs.P_X_CROSS), Prefs.P_X_CROSS);

        addSection("默认 Y 轴格式");
        addCheckRow("坐标轴打点", prefs.plotDefBool(Prefs.P_Y_TICKS), Prefs.P_Y_TICKS);
        addCheckRow("横向网格线", prefs.plotDefBool(Prefs.P_Y_GRID), Prefs.P_Y_GRID);
        addCheckRow("格点(交叉点)", prefs.plotDefBool(Prefs.P_Y_CROSS), Prefs.P_Y_CROSS);

        // 默认粗细
        addSection("默认粗细（像素）");
        addNumRowPlain("坐标轴粗细", prefs.plotDefInt(Prefs.P_AXIS_W), 1, Prefs.P_AXIS_W);
        addNumRowPlain("数据点大小(方形)", prefs.plotDefInt(Prefs.P_POINT_SZ), 1, Prefs.P_POINT_SZ);
        addNumRowPlain("网格/格点粗细", prefs.plotDefInt(Prefs.P_GRID_W), 1, Prefs.P_GRID_W);

        // 恢复出厂
        Button btnFactory = new Button(this);
        btnFactory.setText("恢复出厂默认");
        btnFactory.setTextSize(12);
        btnFactory.setAllCaps(false);
        btnFactory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String[] keys = {Prefs.P_ENABLED, Prefs.P_FREQ_MS, Prefs.P_RETENTION_SEC,
                        Prefs.P_MAX_POINTS, Prefs.P_X_TICKS, Prefs.P_Y_TICKS, Prefs.P_X_GRID,
                        Prefs.P_Y_GRID, Prefs.P_X_CROSS, Prefs.P_Y_CROSS,
                        Prefs.P_AXIS_W, Prefs.P_POINT_SZ, Prefs.P_GRID_W};
                for (String k : keys) {
                    prefs.removePlotDef(k);
                }
                recreate();
            }
        });
        ll.addView(btnFactory, lp());
    }

    // ---------- UI 辅助 ----------

    private void addNumRow(String label, String[] units, int[] unitBase, int curBase, int min,
                           final String defKey) {
        int ui = 0;
        for (int i = units.length - 1; i >= 0; i--) {
            if (curBase >= unitBase[i] && curBase % unitBase[i] == 0) { ui = i; break; }
        }
        int displayVal = Math.max(1, curBase / unitBase[ui]);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(makeLabel(label, 90));

        EditText et = new EditText(this);
        et.setText(String.valueOf(displayVal));
        et.setTextColor(FG);
        et.setTextSize(12);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(dp(70), -2);
        etLp.leftMargin = dp(6);
        row.addView(et, etLp);

        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, units));
        sp.setSelection(ui);
        row.addView(sp);

        Button b = new Button(this);
        b.setText("保存");
        b.setTextSize(11);
        b.setMinWidth(0);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    int num = Integer.parseInt(et.getText().toString().trim());
                    int sel = sp.getSelectedItemPosition();
                    int val = num * unitBase[sel];
                    if (val < min) val = min;
                    prefs.setPlotDefInt(defKey, val);
                    toast(label + " = " + num + " " + units[sel]);
                } catch (NumberFormatException e) {
                    toast("数值无效");
                }
            }
        });
        row.addView(b);
        ll.addView(row, lp());
    }

    private void addNumRowPlain(String label, int cur, int min, final String defKey) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(makeLabel(label, 110));

        EditText et = new EditText(this);
        et.setText(String.valueOf(cur));
        et.setTextColor(FG);
        et.setTextSize(12);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(dp(70), -2);
        etLp.leftMargin = dp(6);
        row.addView(et, etLp);

        Button b = new Button(this);
        b.setText("保存");
        b.setTextSize(11);
        b.setMinWidth(0);
        b.setAllCaps(false);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    int num = Integer.parseInt(et.getText().toString().trim());
                    if (num < min) num = min;
                    prefs.setPlotDefInt(defKey, num);
                    toast(label + " = " + num);
                } catch (NumberFormatException e) {
                    toast("数值无效");
                }
            }
        });
        row.addView(b);
        ll.addView(row, lp());
    }

    private void addCheckRow(String label, boolean checked, final String defKey) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(checked);
        cb.setTextColor(FG);
        cb.setTextSize(12);
        cb.setTypeface(android.graphics.Typeface.MONOSPACE);
        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.setPlotDefBool(defKey, isChecked);
            }
        });
        ll.addView(cb, lp());
    }

    private TextView makeLabel(String text, int widthDp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(DIM);
        t.setTextSize(12);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setWidth(dp(widthDp));
        return t;
    }

    private void addSection(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(ACCENT);
        t.setTextSize(12);
        t.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams l = lp();
        l.topMargin = dp(10);
        l.bottomMargin = dp(4);
        ll.addView(t, l);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
