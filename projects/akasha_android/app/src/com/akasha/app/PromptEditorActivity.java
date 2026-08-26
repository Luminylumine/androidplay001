package com.akasha.app;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 提示词编辑子页面（会话级 / 系统级共用一套界面，仅备注与存取目标不同）。
 *  - scope=session : 编辑 ChatSession.customPrompt（需 EXTRA_SESSION_ID），
 *                    空 = 回退该 Agent 的模型提示词。
 *  - scope=system  : 编辑全局系统提示词（Prefs.systemPrompt），
 *                    空 = 恢复内置默认 AgentPrompts.builtInBase()。
 *  回退链: 会话提示词 → 模型提示词 → 系统提示词（AgentPrompts.resolveBase）。
 *  页面整体 ScrollView 包裹，后期增加字段不影响布局。
 */
public class PromptEditorActivity extends Activity {

    public static final String EXTRA_SCOPE = "scope";
    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String SCOPE_SESSION = "session";
    public static final String SCOPE_SYSTEM = "system";

    private Prefs prefs;
    private String scope = SCOPE_SYSTEM;
    private String sessionId = null;
    private ChatSession session = null;   // scope=session
    private ModelInfo model = null;       // scope=session 时的所属 Agent

    private EditText etPrompt;
    private TextView tvRef, tvEffective;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prompt_editor);
        prefs = new Prefs(this);

        scope = getIntent().getStringExtra(EXTRA_SCOPE);
        if (scope == null) scope = SCOPE_SYSTEM;
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);

        final TextView title = (TextView) findViewById(R.id.tvPeTitle);
        final TextView hint = (TextView) findViewById(R.id.tvPeHint);
        etPrompt = (EditText) findViewById(R.id.etPePrompt);
        tvRef = (TextView) findViewById(R.id.tvPeRef);
        tvEffective = (TextView) findViewById(R.id.tvPeEffective);
        final TextView refLabel = (TextView) findViewById(R.id.tvPeRefLabel);

        if (SCOPE_SESSION.equals(scope)) {
            session = new SessionStore(this).get(sessionId);
            if (session == null) {
                Toast.makeText(this, "会话不存在", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            model = findModel(session.agentId);
            title.setText("会话提示词");
            hint.setText("会话级提示词（仅本对话生效）。提示词回退链: 会话提示词 → 模型提示词 → 系统提示词。"
                    + "留空保存 = 回退到模型提示词。下方提示词长按 3 秒直接复制到剪贴板。");
            etPrompt.setHint("留空保存 = 回退到模型提示词");
            etPrompt.setText(session.customPrompt == null ? "" : session.customPrompt);
            String mp = (model != null && model.customPrompt != null) ? model.customPrompt : "";
            refLabel.setText("回退参考（本对话留空时实际使用）: 模型提示词");
            tvRef.setText(mp.isEmpty()
                    ? "（模型提示词未设置 → 实际回退到系统提示词）\n" + AgentPrompts.defaultBase(this)
                    : mp);
            refreshEffective();
        } else {
            title.setText("系统提示词");
            hint.setText("系统提示词（提示词回退链最底层: 会话/模型提示词都为空时使用；对所有 Agent 的全部对话生效）。"
                    + "留空保存 = 恢复内置默认提示词。下方提示词长按 3 秒直接复制到剪贴板。");
            etPrompt.setHint("留空保存 = 恢复内置默认提示词");
            etPrompt.setText(prefs.systemPrompt() == null ? "" : prefs.systemPrompt());
            refLabel.setText("内置默认提示词（参考）:");
            tvRef.setText(AgentPrompts.builtInBase());
            refreshEffective();
        }
        etPrompt.setSelection(etPrompt.getText().length());
        etPrompt.addTextChangedListener(effectiveWatcher);

        attachCopy3s(tvRef, "参考提示词");
        attachCopy3s(tvEffective, "当前生效提示词");

        findViewById(R.id.btnPeBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        ((Button) findViewById(R.id.btnPeSave)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                save();
            }
        });
    }

    private void save() {
        String raw = etPrompt.getText().toString().trim();
        if (SCOPE_SESSION.equals(scope) && session != null) {
            session.customPrompt = raw.isEmpty() ? null : raw;
            new SessionStore(this).save(session);
        } else {
            prefs.systemPrompt(raw.isEmpty() ? null : raw);
        }
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void refreshEffective() {
        String src, body;
        if (SCOPE_SESSION.equals(scope)) {
            String[] r = AgentPrompts.resolveBase(this,
                    etPrompt.getText().toString().trim(), model);
            src = r[1];
            body = r[0];
            tvEffective.setText("当前生效（按编辑框内容实时计算）: " + src
                    + "\n【角色/规则】\n" + body
                    + "\n\n【可用工具(按当前权限)】\n" + AgentPrompts.toolDocs(model));
        } else {
            String cur = etPrompt.getText().toString().trim();
            src = cur.isEmpty() ? "系统提示词(内置默认)" : "系统提示词(自定义)";
            body = cur.isEmpty() ? AgentPrompts.builtInBase() : cur;
            tvEffective.setText("当前系统提示词（按编辑框内容实时计算）: " + src
                    + "\n【角色/规则】\n" + body);
        }
    }

    private ModelInfo findModel(String agentId) {
        if (agentId == null) return null;
        for (ModelInfo m : prefs.models()) {
            if (agentId.equals(m.id)) return m;
        }
        return null;
    }

    /** Long-press ~3s copies the view's text to the clipboard. */
    private void attachCopy3s(final TextView tv, final String what) {
        tv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                try {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(what, tv.getText().toString()));
                    Toast.makeText(PromptEditorActivity.this, "已复制: " + what,
                            Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(PromptEditorActivity.this, "复制失败", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
    }

    /** 编辑内容变化时实时刷新"当前生效"预览。 */
    private final android.text.TextWatcher effectiveWatcher =
            new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) { refreshEffective(); }
            };
}
