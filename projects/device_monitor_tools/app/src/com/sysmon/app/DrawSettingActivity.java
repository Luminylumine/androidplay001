package com.sysmon.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.sysmon.app.collect.PlotItems;
import com.sysmon.app.collect.PlotRecorder;

import java.util.Locale;

/**
 * 绘图子选项设置页：采样（使能/频率/保留时间/最大点数/清空）
 * + 格式（x/y 独立的打点/网格/格点）+ 粗细（轴/点/网格，px）。
 * Intent extra: "plot" = 子选项 key。
 */
public class DrawSettingActivity extends Activity {

    private static final int FG = 0xFFD0D0D0;
    private static final int ACCENT = 0xFF4DD0E1;
    private static final int DIM = 0xFF888888;
    private static final int GREEN = 0xFF00E676;

    private Prefs prefs;
    private LinearLayout ll;
    private String plotKey;
    private String plotTitle;

    private static final String[] FREQ_UNITS = {"ms", "s"};
    private static final int[] FREQ_UNIT_MS = {1, 1000};
    private static final String[] RET_UNITS = {"s", "m", "h", "d"};
    private static final int[] RET_UNIT_SEC = {1, 60, 3600, 86400};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        plotKey = getIntent().getStringExtra("plot");
        PlotItems.Item it = PlotItems.byKey(plotKey);
        plotTitle = it != null ? it.title : plotKey;

        ScrollView root = new ScrollView(this);
        root.setBackgroundColor(0xFF0F0F14);
        ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(12), dp(12), dp(12), dp(16));
        root.addView(ll);
        setContentView(root);

        addSection("── " + plotTitle + " · 设置 ──");

        // 采样使能
        LinearLayout rowEn = new LinearLayout(this);
        rowEn.setOrientation(LinearLayout.HORIZONTAL);
        rowEn.setGravity(Gravity.CENTER_VERTICAL);
        rowEn.addView(makeLabel("采样使能", 90));
        Switch sw = new Switch(this);
        sw.setChecked(prefs.plotBool(plotKey, Prefs.P_ENABLED));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                prefs.setPlotBool(plotKey, Prefs.P_ENABLED, isChecked);
            }
        });
        rowEn.addView(sw);
        ll.addView(rowEn, lp());

        // 采样频率
        addSection("采样频率");
        addNumRow("频率", FREQ_UNITS, FREQ_UNIT_MS, prefs.plotInt(plotKey, Prefs.P_FREQ_MS), 1,
                new IntSetter() {
                    @Override
                    public void set(int v) {
                        prefs.setPlotInt(plotKey, Prefs.P_FREQ_MS, v);
                    }
                });

        // 保留采样时间
        addSection("保留采样时间");
        addNumRow("保留时长", RET_UNITS, RET_UNIT_SEC, prefs.plotInt(plotKey, Prefs.P_RETENTION_SEC), 1,
                new IntSetter() {
                    @Override
                    public void set(int v) {
                        prefs.setPlotInt(plotKey, Prefs.P_RETENTION_SEC, v);
                    }
                });

        // 最多保留采样点数目
        addSection("最多保留采样点");
        addNumRowPlain("点数上限", prefs.plotInt(plotKey, Prefs.P_MAX_POINTS), 2,
                new IntSetter() {
                    @Override
                    public void set(int v) {
                        prefs.setPlotInt(plotKey, Prefs.P_MAX_POINTS, v);
                    }
                });

        // 清空数据
        Button btnClear = new Button(this);
        btnClear.setText("清空数据");
        btnClear.setTextSize(12);
        btnClear.setAllCaps(false);
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PlotRecorder.get(DrawSettingActivity.this).store(plotKey).clear();
                toast("已清空");
            }
        });
        ll.addView(btnClear, lp());

        // X 轴格式
        addSection("X 轴格式");
        addCheckRow("坐标轴打点", prefs.plotBool(plotKey, Prefs.P_X_TICKS),
                boolSetter(plotKey, Prefs.P_X_TICKS));
        addCheckRow("纵向网格线", prefs.plotBool(plotKey, Prefs.P_X_GRID),
                boolSetter(plotKey, Prefs.P_X_GRID));
        addCheckRow("格点(交叉点)", prefs.plotBool(plotKey, Prefs.P_X_CROSS),
                boolSetter(plotKey, Prefs.P_X_CROSS));

        // Y 轴格式
        addSection("Y 轴格式");
        addCheckRow("坐标轴打点", prefs.plotBool(plotKey, Prefs.P_Y_TICKS),
                boolSetter(plotKey, Prefs.P_Y_TICKS));
        addCheckRow("横向网格线", prefs.plotBool(plotKey, Prefs.P_Y_GRID),
                boolSetter(plotKey, Prefs.P_Y_GRID));
        addCheckRow("格点(交叉点)", prefs.plotBool(plotKey, Prefs.P_Y_CROSS),
                boolSetter(plotKey, Prefs.P_Y_CROSS));

        // 粗细
        addSection("粗细（像素）");
        addNumRowPlain("坐标轴粗细", prefs.plotInt(plotKey, Prefs.P_AXIS_W), 1,
                intSetter(plotKey, Prefs.P_AXIS_W));
        addNumRowPlain("数据点大小(方形)", prefs.plotInt(plotKey, Prefs.P_POINT_SZ), 1,
                intSetter(plotKey, Prefs.P_POINT_SZ));
        addNumRowPlain("网格/格点粗细", prefs.plotInt(plotKey, Prefs.P_GRID_W), 1,
                intSetter(plotKey, Prefs.P_GRID_W));

        // 恢复默认
        addSection("默认值");
        Button btnReset = new Button(this);
        btnReset.setText("恢复默认（回落表格默认格式）");
        btnReset.setTextSize(12);
        btnReset.setAllCaps(false);
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.removePlot(plotKey);
                recreate();
            }
        });
        ll.addView(btnReset, lp());
    }

    // ---------- UI 辅助 ----------

    interface IntSetter { void set(int v); }

    private IntSetter intSetter(final String plot, final String s) {
        return new IntSetter() {
            @Override
            public void set(int v) {
                prefs.setPlotInt(plot, s, v);
            }
        };
    }

    private BooleanSetter boolSetter(final String plot, final String s) {
        return new BooleanSetter() {
            @Override
            public void set(boolean v) {
                prefs.setPlotBool(plot, s, v);
            }
        };
    }

    /** 数值 + 单位下拉 输入行。curBase 为基准单位当前值。 */
    private void addNumRow(String label, String[] units, int[] unitBase, int curBase, int min,
                           IntSetter setter) {
        // 选择最合适的显示单位：基准值能被整除且数值>=1 的最大单位
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
        et.setHint("数值");
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
                    setter.set(val);
                    toast(label + " = " + num + " " + units[sel]);
                } catch (NumberFormatException e) {
                    toast("数值无效");
                }
            }
        });
        row.addView(b);
        ll.addView(row, lp());
    }

    /** 纯数值输入行（像素/点数）。 */
    private void addNumRowPlain(String label, int cur, int min, IntSetter setter) {
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
                    setter.set(num);
                    toast(label + " = " + num);
                } catch (NumberFormatException e) {
                    toast("数值无效");
                }
            }
        });
        row.addView(b);
        ll.addView(row, lp());
    }

    private void addCheckRow(String label, boolean checked, final BooleanSetter setter) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(checked);
        cb.setTextColor(FG);
        cb.setTextSize(12);
        cb.setTypeface(android.graphics.Typeface.MONOSPACE);
        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                setter.set(isChecked);
            }
        });
        ll.addView(cb, lp());
    }

    interface BooleanSetter { void set(boolean v); }

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
