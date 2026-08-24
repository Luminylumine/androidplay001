package com.akasha.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 通讯录对象（Agent）的「所有会话」选择页。
 *
 * 进入条件（FR8.3 语义）：
 *   - 若从 TriggerSourcePickerActivity 「任务完成」页 点某个 Agent 行的 内容区域 进来：
 *         intent 带 AGENT_ID / AGENT_NAME / EXTRA_PRESELECTED / EXTRA_FORCE_CHECKS = true
 *         则 进入后 顶部 有 【取消 / 保存】， 返回后 默认"保存"（FORCE_CHECKS=true）
 *   - 若只是从其他页（如通讯录）进入，不带 EXTRA_FORCE_CHECKS 或 = false：
 *         返回后不自动勾选（仅用于查看）
 */
public class AgentDialogsPickerActivity extends Activity {

    public static final String EXTRA_AGENT_ID = "agentId";
    public static final String EXTRA_AGENT_NAME = "agentName";
    public static final String EXTRA_FORCE_CHECKS = "forceChecks"; // true → 返回时自动保存
    public static final String EXTRA_PRESELECTED = "preSelected";  // JSON 数组字符串
    public static final String EXTRA_RESULT = "selected";          // JSON 数组字符串

    private static class Row {
        ChatSession session;
        Row(ChatSession s) { this.session = s; }
    }

    private final List<Row> rows = new ArrayList<>();
    private final List<CheckBox> boxes = new ArrayList<>();
    private ListView lv;
    private boolean forceChecks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trigger_picker);
        BackgroundHelper.apply(this, findViewById(R.id.pickerRoot), BackgroundHelper.PAGE_MODEL);

        final String agentId = getIntent().getStringExtra(EXTRA_AGENT_ID);
        final String agentName = getIntent().getStringExtra(EXTRA_AGENT_NAME);
        forceChecks = getIntent().getBooleanExtra(EXTRA_FORCE_CHECKS, false);

        ((TextView) findViewById(R.id.tvPkTitle)).setText(
                (agentName == null ? "" : agentName) + " · 全部对话");

        final Button btnOk = (Button) findViewById(R.id.btnPkOk);
        btnOk.setText("保存");
        final Button btnClear = (Button) findViewById(R.id.btnPkClear);
        btnClear.setVisibility(View.GONE);
        ((Button) findViewById(R.id.btnPkCancel)).setText("取消");

        lv = (ListView) findViewById(R.id.lvPicker);
        buildRows(agentId);
        lv.setAdapter(new BaseAdapter() {
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
                // 黑体大字：Agent名；灰色小字：会话displayName
                t.setText(agentName == null ? "" : agentName);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                t.setTextSize(16);
                s.setText((it.session.pinned ? "📌 " : "") + displayName(it.session));
                s.setTextColor(0xFF666666);
                s.setTextSize(13);

                cb.setOnCheckedChangeListener(null);
                cb.setChecked(boxes.get(p).isChecked());
                cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                        boxes.get(rows.indexOf(it)).setChecked(checked);
                    }
                });
                // 点击行 = 勾选/取消整个行（这页本来就专门做选择）
                v.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View vv) {
                        boolean old = boxes.get(rows.indexOf(it)).isChecked();
                        boxes.get(rows.indexOf(it)).setChecked(!old);
                        ((BaseAdapter) lv.getAdapter()).notifyDataSetChanged();
                    }
                });
                return v;
            }
        });

        findViewById(R.id.btnPkCancel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        btnOk.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent data = new Intent().putExtra(EXTRA_RESULT, packSelected());
                setResult(RESULT_OK, data);
                finish();
            }
        });
    }

    @Override
    public void finish() {
        if (forceChecks) {
            // FR8.3 已勾选者 进入新页，返回默认保存
            Intent data = new Intent().putExtra(EXTRA_RESULT, packSelected());
            setResult(RESULT_OK, data);
        }
        super.finish();
    }

    private String packSelected() {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < rows.size(); i++) {
            if (boxes.get(i).isChecked()) arr.put(rows.get(i).session.id);
        }
        return arr.toString();
    }

    private void buildRows(String agentId) {
        SessionStore st = new SessionStore(this);
        List<ChatSession> all = st.listSorted();
        for (ChatSession s : all) {
            if (s.agentId == null || agentId == null || !agentId.equals(s.agentId)) continue;
            rows.add(new Row(s));
        }
        // 置顶优先，其余按更新时间
        Collections.sort(rows, new Comparator<Row>() {
            @Override public int compare(Row a, Row b) {
                int pa = a.session.pinned ? 1 : 0;
                int pb = b.session.pinned ? 1 : 0;
                if (pa != pb) return pb - pa;
                return Long.compare(b.session.lastMsgTime, a.session.lastMsgTime);
            }
        });

        final java.util.Set<String> sel = new java.util.HashSet<>();
        String raw = getIntent().getStringExtra(EXTRA_PRESELECTED);
        if (raw != null) try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) sel.add(arr.optString(i));
        } catch (Exception ignored) {}

        for (Row r : rows) {
            CheckBox cb = new CheckBox(this);
            cb.setChecked(sel.contains(r.session.id));
            boxes.add(cb);
        }
    }

    private static String displayName(ChatSession s) {
        String d = s.displayName == null ? s.title : s.displayName;
        return (d == null || d.isEmpty()) ? "会话" : d;
    }
}
