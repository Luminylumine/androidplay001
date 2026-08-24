package com.akasha.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * "更多(N)" 页：当任务完成触发源中「已勾选的独立会话 > 4」，原 5-… 号折叠，点击「更多(N)」
 * 跳到此页。三路独立排序（FR8.2）：
 *   - G1：用户已勾选 → 强制置顶
 *   - G2：对话本身是 pinned 的（📌），但未勾选
 *   - G3：余下会话
 *   三组内部各自按用户选择的 spinner 方式独立排序（字母/时间 正序/倒序）。
 *
 *   也支持通过「搜索」按钮顶部切换到 SearchPickerActivity（如果有需要）。
 */
public class SearchPickerActivity extends Activity {

    public static final String EXTRA_SELECTED = "selected";    // JSON Array<String> (session ids)
    public static final String EXTRA_RESULT = "selected";      // JSON Array<String>

    public static final int SORT_ALPHA_AZ = 0;
    public static final int SORT_ALPHA_ZA = 1;
    public static final int SORT_TIME_ASC = 2;
    public static final int SORT_TIME_DESC = 3;

    private static class Row {
        ChatSession session;
        String agentDisplayName;
        int group;   // 1=checked, 2=pinned-not-checked, 3=rest
    }

    private final List<Row> rows = new ArrayList<>();
    private final java.util.Set<String> checked = new java.util.HashSet<>();
    private ListView lv;
    private BaseAdapter adapter;
    private int sortMode = SORT_TIME_DESC;
    private Spinner spSort;
    private TextView tvSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trigger_picker);
        BackgroundHelper.apply(this, findViewById(R.id.pickerRoot), BackgroundHelper.PAGE_MODEL);

        ((TextView) findViewById(R.id.tvPkTitle)).setText("任务完成 · 触发源（更多）");
        final Button btnClear = (Button) findViewById(R.id.btnPkClear);
        btnClear.setVisibility(View.VISIBLE);
        btnClear.setText("三路排序");
        final Button btnOk = (Button) findViewById(R.id.btnPkOk);
        btnOk.setText("保存");
        ((Button) findViewById(R.id.btnPkCancel)).setText("取消");

        // 在标题下方插入一排：摘要 + 排序选择器
        LinearLayout root = (LinearLayout) findViewById(R.id.pickerRoot);
        LinearLayout subBar = new LinearLayout(this);
        subBar.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        subBar.setPadding(pad, pad, pad, pad);
        subBar.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        // 插入到 lvPicker 之前
        int idx = root.indexOfChild(findViewById(R.id.lvPicker));
        root.addView(subBar, idx, blp);

        tvSummary = new TextView(this);
        tvSummary.setTextColor(0xFF0A66C2);
        tvSummary.setTextSize(12);
        subBar.addView(tvSummary);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(-1, -2);
        r2lp.topMargin = dp(6);
        r2.setLayoutParams(r2lp);

        TextView lab = new TextView(this);
        lab.setText("三组各自排序: ");
        lab.setTextColor(0xFF666666);
        lab.setTextSize(12);
        r2.addView(lab);
        spSort = new Spinner(this);
        android.widget.ArrayAdapter<String> sad = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{"字母 A→Z", "字母 Z→A", "时间 旧→新", "时间 新→旧"});
        sad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSort.setAdapter(sad);
        spSort.setSelection(3);
        spSort.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                sortMode = pos;
                resort();
                adapter.notifyDataSetChanged();
            }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(0, -2, 1);
        splp.leftMargin = dp(4);
        r2.addView(spSort, splp);
        subBar.addView(r2);

        // 初始化
        String raw = getIntent().getStringExtra(EXTRA_SELECTED);
        if (raw != null) try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) checked.add(arr.optString(i));
        } catch (Exception ignored) {}

        buildRows();
        lv = (ListView) findViewById(R.id.lvPicker);
        lv.setAdapter(adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int p) { return rows.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                View v = cv;
                if (v == null) v = getLayoutInflater().inflate(R.layout.item_trigger_source, parent, false);
                final Row it = rows.get(p);
                CheckBox cb = (CheckBox) v.findViewById(R.id.cbSource);
                TextView t = (TextView) v.findViewById(R.id.tvSourceTitle);
                TextView s = (TextView) v.findViewById(R.id.tvSourceSub);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                t.setTextSize(16);
                t.setText(it.agentDisplayName == null ? "" : it.agentDisplayName);
                s.setTextColor(0xFF666666);
                s.setTextSize(13);
                s.setText(groupLabel(it.group) + (it.session.pinned ? " 📌 " : "")
                        + displayName(it.session));
                cb.setOnCheckedChangeListener(null);
                cb.setChecked(checked.contains(it.session.id));
                cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                        if (c) checked.add(it.session.id);
                        else checked.remove(it.session.id);
                        resort();
                        updateSummary();
                        notifyAd();
                    }
                });
                v.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View vv) {
                        boolean had = checked.contains(it.session.id);
                        if (had) checked.remove(it.session.id); else checked.add(it.session.id);
                        resort();
                        updateSummary();
                        notifyAd();
                    }
                });
                return v;
            }
        });

        findViewById(R.id.btnPkCancel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 三路排序按钮 复用: 切一下 spinner 就重新排序
                int next = (sortMode + 1) % 4;
                spSort.setSelection(next);
            }
        });
        btnOk.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                JSONArray arr = new JSONArray();
                for (String s : checked) arr.put(s);
                Intent data = new Intent().putExtra(EXTRA_RESULT, arr.toString());
                setResult(RESULT_OK, data);
                finish();
            }
        });

        updateSummary();
    }

    private void notifyAd() { adapter.notifyDataSetChanged(); }

    private void updateSummary() {
        int g1 = 0, g2 = 0, g3 = 0;
        for (Row r : rows) {
            if (r.group == 1) g1++;
            else if (r.group == 2) g2++;
            else g3++;
        }
        tvSummary.setText("已勾选: " + (checked.size()) + " (强置顶) · 未勾置顶: " + g2
                + " · 其他: " + g3 + " — 三组独立排序");
    }

    private static String groupLabel(int g) {
        if (g == 1) return "[✓] ";
        if (g == 2) return "[📌] ";
        return "[·] ";
    }

    private static String displayName(ChatSession s) {
        String d = s.displayName == null ? s.title : s.displayName;
        return (d == null || d.isEmpty()) ? "会话" : d;
    }

    private void buildRows() {
        SessionStore st = new SessionStore(this);
        Prefs prefs = new Prefs(this);
        final java.util.Map<String, String> agentName = new java.util.HashMap<>();
        for (ModelInfo m : prefs.models()) agentName.put(m.id, m.name == null ? m.id : m.name);
        rows.clear();
        for (ChatSession s : st.listSorted()) {
            Row r = new Row();
            r.session = s;
            r.agentDisplayName = agentName.containsKey(s.agentId)
                    ? agentName.get(s.agentId) : (s.agentId == null ? "" : s.agentId);
            rows.add(r);
        }
        resort();
    }

    private void resort() {
        // 先分 G1 / G2 / G3
        for (Row r : rows) {
            if (checked.contains(r.session.id)) r.group = 1;
            else if (r.session.pinned) r.group = 2;
            else r.group = 3;
        }
        final Comparator<Row> inner = innerComparator(sortMode);
        Collections.sort(rows, new Comparator<Row>() {
            @Override public int compare(Row a, Row b) {
                if (a.group != b.group) return a.group - b.group;
                return inner.compare(a, b);
            }
        });
    }

    private static Comparator<Row> innerComparator(int mode) {
        switch (mode) {
            case SORT_ALPHA_AZ: return new Comparator<Row>() {
                @Override public int compare(Row a, Row b) {
                    return nvl(displayName(a.session)).compareToIgnoreCase(nvl(displayName(b.session)));
                }};
            case SORT_ALPHA_ZA: return new Comparator<Row>() {
                @Override public int compare(Row a, Row b) {
                    return nvl(displayName(b.session)).compareToIgnoreCase(nvl(displayName(a.session)));
                }};
            case SORT_TIME_ASC: return new Comparator<Row>() {
                @Override public int compare(Row a, Row b) {
                    return Long.compare(a.session.lastMsgTime, b.session.lastMsgTime);
                }};
            case SORT_TIME_DESC:
            default: return new Comparator<Row>() {
                @Override public int compare(Row a, Row b) {
                    return Long.compare(b.session.lastMsgTime, a.session.lastMsgTime);
                }};
        }
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
