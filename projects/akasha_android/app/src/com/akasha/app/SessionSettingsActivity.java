package com.akasha.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Per-session settings (FR-6).
 * Entry: ChatActivity top gear.
 *   - ⏰ Timer & wake-up (delegates to TimerWakeupActivity, still per-agent store)
 *   - Session display name (rename in place, unique across sessions)
 *   - 会话提示词: 点击进入 PromptEditorActivity 子页面编辑
 *     (回退链: 会话提示词 → 模型提示词 → 系统提示词; 模型级/系统级不在本页面改)
 *   - 查看当前生效提示词 (显示回退链实际命中的那一层 + 工具清单)
 *   - Delete this session (final red action at the bottom)
 */
public class SessionSettingsActivity extends Activity {

    public static final String EXTRA_SESSION_ID = "sessionId";

    private static final int REQ_PROMPT = 100;

    private SessionStore store;
    private Prefs prefs;
    private ChatSession session;
    // 会话提示词行的小字 (子页面返回后原地刷新)
    private TextView tvSessionPromptSub;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_settings);
        store = new SessionStore(this);
        prefs = new Prefs(this);

        String sid = getIntent().getStringExtra(EXTRA_SESSION_ID);
        session = store.get(sid);
        if (session == null) {
            Toast.makeText(this, "会话不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final TextView title = (TextView) findViewById(R.id.tvSsTitle);
        title.setText(session.displayName == null ? "" : session.displayName);

        final LinearLayout body = (LinearLayout) findViewById(R.id.llSsBody);

        // row 1: Timer & wake-up
        body.addView(row(
                "⏰ 定时器与唤醒",
                (agentName() == null ? "（本对话所属 Agent）" : agentName())
                        + " → 闹钟式/事件/倒计时 唤醒",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Intent i = new Intent(SessionSettingsActivity.this, TimerWakeupActivity.class);
                        i.putExtra(TimerWakeupActivity.EXTRA_CONTEXT_TYPE, "session");
                        i.putExtra(TimerWakeupActivity.EXTRA_SESSION_ID, session.id);
                        i.putExtra("agentId", session.agentId);
                        i.putExtra("agentName", agentName());
                        startActivity(i);
                    }
                }));

        // row 2: session display name (edits ChatSession.displayName only,
        // NEVER touches ModelInfo.name)
        body.addView(row(
                "对话名",
                currentDisplayName(),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        final EditText et = new EditText(SessionSettingsActivity.this);
                        et.setText(currentDisplayName());
                        et.setSelection(et.getText().length());
                        new AlertDialog.Builder(SessionSettingsActivity.this)
                                .setTitle("修改对话名")
                                .setView(et)
                                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        String raw = et.getText() == null
                                                ? "" : et.getText().toString().trim();
                                        String next;
                                        if (raw.isEmpty()) {
                                            Toast.makeText(SessionSettingsActivity.this,
                                                    "对话名不能为空", Toast.LENGTH_SHORT).show();
                                            return;
                                        }
                                        // 如果和现名一样就不做去重
                                        if (raw.equals(session.displayName)) {
                                            return;
                                        }
                                        java.util.Set<String> used = new java.util.HashSet<>();
                                        for (ChatSession s : store.list()) {
                                            if (s.id.equals(session.id)) continue;
                                            if (s.displayName != null) used.add(s.displayName);
                                        }
                                        next = SessionStore.dedupAgainst(used, raw);
                                        session.displayName = next;
                                        session.title = next; // legacy mirror
                                        store.save(session);
                                        title.setText(next);
                                        rebuildBody(body);
                                        setResult(RESULT_OK);
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                }));

        // row 3: per-session default goal. Empty follows the Agent type then global fallback.
        body.addView(row(
                "默认任务目标（本对话）",
                sessionDefaultGoalSub(),
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        final EditText et = new EditText(SessionSettingsActivity.this);
                        et.setText(session.defaultGoal == null ? "" : session.defaultGoal);
                        et.setHint("留空 = 跟随 Agent type / 全局默认");
                        et.setMinLines(3);
                        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
                        new AlertDialog.Builder(SessionSettingsActivity.this)
                                .setTitle("默认任务目标")
                                .setView(et)
                                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        String value = et.getText() == null ? "" : et.getText().toString().trim();
                                        session.defaultGoal = value.isEmpty() ? null : value;
                                        store.save(session);
                                        setResult(RESULT_OK);
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                }));

        // row 4: 会话提示词 (点击进入子页面编辑; 空 = 回退模型提示词)
        {
            LinearLayout r = promptRow("💬 会话提示词（本对话）", sessionPromptSub());
            tvSessionPromptSub = (TextView) r.getChildAt(1);
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent i = new Intent(SessionSettingsActivity.this, PromptEditorActivity.class)
                            .putExtra(PromptEditorActivity.EXTRA_SCOPE, PromptEditorActivity.SCOPE_SESSION)
                            .putExtra(PromptEditorActivity.EXTRA_SESSION_ID, session.id);
                    startActivityForResult(i, REQ_PROMPT);
                }
            });
            body.addView(r);
        }

        // row 4: 查看当前生效提示词
        body.addView(row(
                "👀 查看当前生效提示词",
                "回退链: 会话提示词 → 模型提示词 → 系统提示词",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        showEffectivePrompt();
                    }
                }));

        // row 5: delete (red card)
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        Button del = new Button(this);
        del.setLayoutParams(lp);
        del.setText("删除本对话（不可撤销）");
        del.setTextColor(Color.WHITE);
        del.setBackgroundColor(0xFFD32F2F);
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(SessionSettingsActivity.this)
                        .setTitle("删除对话？")
                        .setMessage("「" + currentDisplayName() + "」的聊天记录将被删除。")
                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                store.remove(session.id);
                                setResult(RESULT_OK);
                                finish();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        body.addView(del);

        ((Button) findViewById(R.id.btnSsBack)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        ((Button) findViewById(R.id.btnSsSave)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setResult(RESULT_OK);
                finish();
            }
        });
    }

    private void rebuildBody(LinearLayout body) {
        // 简单实现: 只改文字不重建, 但外部接口保留以便扩展
    }

    private String currentDisplayName() {
        return (session == null || session.displayName == null
                || session.displayName.isEmpty()) ? "(未命名)" : session.displayName;
    }

    private String agentName() {
        if (session == null || session.agentId == null) return null;
        for (ModelInfo m : prefs.models()) {
            if (session.agentId.equals(m.id)) return m.name == null ? m.id : m.name;
        }
        return session.agentId;
    }

    private String sessionDefaultGoalSub() {
        if (session != null && session.defaultGoal != null && !session.defaultGoal.trim().isEmpty()) {
            return session.defaultGoal.trim();
        }
        ModelInfo model = agentModel();
        if (model != null && model.defaultGoal != null && !model.defaultGoal.trim().isEmpty()) {
            return "跟随 Agent: " + model.defaultGoal.trim();
        }
        String global = prefs.goal();
        return global == null || global.trim().isEmpty() ? "未设置" : "跟随全局: " + global.trim();
    }

    // ---------- 会话提示词 ----------

    /** 提示词编辑行: 与 row() 相同结构, 调用方取 getChildAt(1) 刷新小字。 */
    private LinearLayout promptRow(String title, String sub) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        r.setPadding(pad, pad, pad, pad);
        r.setBackgroundColor(Color.WHITE);
        r.setClickable(true);
        r.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        r.setLayoutParams(lp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.BLACK);
        t.setTextSize(16);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        r.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(0xFF666666);
        s.setTextSize(13);
        r.addView(s);
        return r;
    }

    private String sessionPromptRaw() {
        if (session == null) return "";
        return session.customPrompt == null ? "" : session.customPrompt;
    }

    private String sessionPromptSub() {
        return sessionPromptRaw().trim().isEmpty()
                ? "未设置（跟随模型提示词）" : "已启用自定义";
    }

    private ModelInfo agentModel() {
        if (session == null || session.agentId == null) return null;
        for (ModelInfo m : prefs.models()) {
            if (session.agentId.equals(m.id)) return m;
        }
        return null;
    }

    private void refreshPromptRows() {
        if (tvSessionPromptSub != null) tvSessionPromptSub.setText(sessionPromptSub());
    }

    /** 从会话提示词子页面返回: 刷新摘要并通知 ChatActivity。 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROMPT && resultCode == RESULT_OK) {
            ChatSession fresh = store.get(session.id);
            if (fresh != null) session.customPrompt = fresh.customPrompt;
            refreshPromptRows();
            setResult(RESULT_OK);
        }
    }

    /** 显示回退链实际命中的提示词 + 工具清单, 可复制。 */
    private void showEffectivePrompt() {
        ModelInfo m = agentModel();
        String[] base = AgentPrompts.resolveBase(this, sessionPromptRaw(), m);
        String full = "来源: " + base[1]
                + "\n回退链: 会话提示词 → 模型提示词 → 系统提示词"
                + "\n\n【角色/规则】\n" + base[0]
                + "\n\n【可用工具(按当前权限)】\n" + AgentPrompts.toolDocs(m);
        TextView tv = new TextView(this);
        tv.setText(full);
        tv.setTextSize(11);
        tv.setTextColor(0xFF555555);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("当前生效提示词（" + base[1] + "）")
                .setView(sv)
                .setPositiveButton("复制全文", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager)
                                            getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "当前生效提示词", full));
                            Toast.makeText(SessionSettingsActivity.this, "已复制",
                                    Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Toast.makeText(SessionSettingsActivity.this, "复制失败",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** Index row: title bold / sub gray / clickable. */
    private View row(String title, String sub, View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        r.setPadding(pad, pad, pad, pad);
        r.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        r.setLayoutParams(lp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.BLACK);
        t.setTextSize(16);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        r.addView(t);
        TextView s = new TextView(this);
        s.setText(sub == null ? "" : sub);
        s.setTextColor(0xFF666666);
        s.setTextSize(13);
        r.addView(s);
        if (onClick != null) r.setOnClickListener(onClick);
        return r;
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }
}
