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
import android.widget.TextView;
import android.widget.Toast;

/**
 * Per-session settings (FR-6).
 * Entry: ChatActivity top gear. Does NOT expose agent-level API/perm/pool/prompt.
 *   - ⏰ Timer & wake-up (delegates to TimerWakeupActivity, still per-agent store)
 *   - Session display name (rename in place, unique across sessions)
 *   - Delete this session (final red action at the bottom)
 */
public class SessionSettingsActivity extends Activity {

    public static final String EXTRA_SESSION_ID = "sessionId";

    private SessionStore store;
    private Prefs prefs;
    private ChatSession session;

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

        // row 3: delete (red card)
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
