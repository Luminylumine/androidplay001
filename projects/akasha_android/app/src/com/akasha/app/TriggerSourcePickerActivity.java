package com.akasha.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
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
 * 触发源多选页 (任务完成→聊天会话 / 打开软件→用户应用)。
 *  - 顶栏: 取消(红) / 清空(黄) / 确定(蓝)
 *  - 只点方框勾选/取消(取消不删除已存的触发源设置); 行本身不响应点击
 *  - 可上下滑动
 */
public class TriggerSourcePickerActivity extends Activity {

    /** 触发源特殊 id: 任意会话(全部) */
    public static final String ALL_SESSIONS = "__all__";
    /** 触发源特殊 id 前缀: 该模型的全部会话 (agent:<agentId>) */
    public static final String AGENT_PREFIX = "agent:";

    public static final String EXTRA_AGENT_ID = "agentId";
    public static final String EXTRA_AGENT_NAME = "agentName";
    public static final String EXTRA_EVENT = "event";      // "task_done" | "app_open"
    public static final String EXTRA_SELECTED = "selected"; // JSON 数组字符串
    public static final String EXTRA_RESULT = "selected";   // 返回 extra key

    private static class Item {
        String id;
        String title;
        String sub;
        Item(String id, String title, String sub) {
            this.id = id; this.title = title; this.sub = sub;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final List<CheckBox> boxes = new ArrayList<>();
    private ListView lv;
    private String event = "task_done";
    /** 当前已选中集合（与 boxes 同步，用于 AgentDialogsPicker 回传后替换） */
    private final java.util.Set<String> selectedIds = new java.util.HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trigger_picker);
        BackgroundHelper.apply(this, findViewById(R.id.pickerRoot), BackgroundHelper.PAGE_MODEL);

        event = getIntent().getStringExtra(EXTRA_EVENT);
        if (event == null) event = "task_done";

        TextView title = (TextView) findViewById(R.id.tvPkTitle);
        String agentName = getIntent().getStringExtra(EXTRA_AGENT_NAME);
        title.setText(("task_done".equals(event)
                ? "任务完成 · 选择监听的会话"
                : "打开软件 · 选择触发应用")
                + (agentName == null ? "" : "（" + agentName + "）"));

        lv = (ListView) findViewById(R.id.lvPicker);
        loadItems();
        final List<CheckBox> finalBoxes = boxes;
        lv.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() { return items.size(); }
            @Override
            public Object getItem(int p) { return items.get(p); }
            @Override
            public long getItemId(int p) { return p; }
            @Override
            public View getView(int p, View cv, ViewGroup parent) {
                final Item it = items.get(p);
                View v = cv;
                LinearLayout root;
                CheckBox cb;
                TextView t, s;
                if (v == null) {
                    v = getLayoutInflater().inflate(R.layout.item_trigger_source, parent, false);
                    root = (LinearLayout) v.findViewById(R.id.rowSource);
                    cb = (CheckBox) v.findViewById(R.id.cbSource);
                    t = (TextView) v.findViewById(R.id.tvSourceTitle);
                    s = (TextView) v.findViewById(R.id.tvSourceSub);
                } else {
                    root = (LinearLayout) v.findViewById(R.id.rowSource);
                    cb = (CheckBox) v.findViewById(R.id.cbSource);
                    t = (TextView) v.findViewById(R.id.tvSourceTitle);
                    s = (TextView) v.findViewById(R.id.tvSourceSub);
                }
                cb.setOnCheckedChangeListener(null);
                t.setText(it.title);
                s.setText(it.sub == null || it.sub.isEmpty() ? " " : it.sub);
                cb.setChecked(boxes.get(p).isChecked());
                cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        finalBoxes.get(p).setChecked(isChecked);
                        if (isChecked) selectedIds.add(it.id);
                        else selectedIds.remove(it.id);
                    }
                });
                return v;
            }
        });

        findViewById(R.id.btnPkCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnPkClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (CheckBox c : boxes) c.setChecked(false);
                lv.setAdapter((BaseAdapter) lv.getAdapter()); // refresh
            }
        });
        findViewById(R.id.btnPkOk).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONArray arr = new JSONArray();
                for (int i = 0; i < items.size(); i++) {
                    if (boxes.get(i).isChecked()) arr.put(items.get(i).id);
                }
                Intent data = new Intent().putExtra(EXTRA_RESULT, arr.toString());
                setResult(RESULT_OK, data);
                finish();
            }
        });
    }

    private void loadItems() {
        String agentId = getIntent().getStringExtra(EXTRA_AGENT_ID);
        final Prefs prefs = new Prefs(this);
        if ("task_done".equals(event)) {
            // 首位: 任意会话 —— 该 Agent 任何会话完成/终止都触发
            items.add(new Item(ALL_SESSIONS,
                    "任意会话", "任意会话出现 任务完成/终止 都触发（全部模型级）"));
            SessionStore st = new SessionStore(this);
            List<ChatSession> ss = st.listSorted();
            List<ModelInfo> models = prefs.models();
            // 展示名重复时自动加 (1)(2) 避免歧义（仅显示用, 不改存储名）
            final java.util.Map<String, Integer> seen = new java.util.HashMap<>();
            // 按 Agent 分组: 该模型的全部会话行(勾选=其全部会话) + 会话行(模型名黑体大 + 展示名灰色小)
            for (ModelInfo m : models) {
                int cnt = 0;
                for (ChatSession s : ss) if (m.id != null && m.id.equals(s.agentId)) cnt++;
                Item grp = new Item(AGENT_PREFIX + m.id,
                        "所有" + (m.name == null ? "" : m.name),
                        "（勾选本行 = 该模型的全部会话触发）。当前共 " + cnt + " 个会话");
                items.add(grp);
                for (ChatSession s : ss) {
                    if (m.id == null || !m.id.equals(s.agentId)) continue;
                    items.add(new Item(s.id,
                            (s.pinned ? "📌 " : "") + (s.displayName != null && !s.displayName.isEmpty() ? s.displayName : uniqueTitle(seen, s)),
                            (m.name == null || m.name.isEmpty() ? (m.id == null ? "" : m.id) : m.name)));
                }
            }
            // 孤儿会话(所属 Agent 已删除)
            for (ChatSession s : ss) {
                boolean known = false;
                for (ModelInfo m : models) {
                    if (m.id != null && m.id.equals(s.agentId)) { known = true; break; }
                }
                if (known) continue;
                items.add(new Item(s.id,
                        (s.pinned ? "📌 " : "") + (s.displayName != null && !s.displayName.isEmpty() ? s.displayName : uniqueTitle(seen, s)),
                        agentName(prefs, s.agentId)));
            }
        } else {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            List<ApplicationInfo> userApps = new ArrayList<>();
            for (ApplicationInfo ai : apps) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                userApps.add(ai);
            }
            Collections.sort(userApps, new Comparator<ApplicationInfo>() {
                @Override
                public int compare(ApplicationInfo a, ApplicationInfo b) {
                    String la = String.valueOf(pm.getApplicationLabel(a));
                    String lb = String.valueOf(pm.getApplicationLabel(b));
                    return la.compareToIgnoreCase(lb);
                }
            });
            for (ApplicationInfo ai : userApps) {
                items.add(new Item(ai.packageName, String.valueOf(pm.getApplicationLabel(ai)), ai.packageName));
            }
        }

        // 初始勾选状态
        String sel = getIntent().getStringExtra(EXTRA_SELECTED);
        final java.util.Set<String> selSet = new java.util.HashSet<>();
        if (sel != null) {
            try {
                JSONArray arr = new JSONArray(sel);
                for (int i = 0; i < arr.length(); i++) selSet.add(arr.optString(i));
            } catch (Exception ignored) {}
        }
        for (Item it : items) {
            CheckBox cb = new CheckBox(this);
            cb.setClickable(true);
            cb.setChecked(selSet.contains(it.id));
            boxes.add(cb);
        }
    }

    /** 展示名: 同名第 2/3... 次出现自动加 (1)/(2)... 避免重复 */
    private static String uniqueTitle(java.util.Map<String, Integer> seen, ChatSession s) {
        String t = (s.displayName != null && !s.displayName.isEmpty()) ? s.displayName :
                   ((s.title == null || s.title.isEmpty()) ? "会话" : s.title);
        int n = seen.containsKey(t) ? seen.get(t) : 0;
        seen.put(t, n + 1);
        return n == 0 ? t : t + "(" + n + ")";
    }

    private static String agentName(Prefs prefs, String agentId) {
        for (ModelInfo m : prefs.models()) {
            if (m.id != null && m.id.equals(agentId)) return m.name;
        }
        return agentId == null ? "" : agentId;
    }
}
