package com.akasha.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-agent 设置 (重构 2026-08-24):
 *  - 索引页: 只列设置项名称(定时与唤醒/基本信息/权限/API/经验池/模型级Prompt)
 *  - Prompt 为模型级: 回退链 会话提示词→模型提示词→系统默认 (会话级在 SessionSettingsActivity 改)
 *  - 子页面: 每项独立页面(section extra), 保存后返回索引
 *  - 定时器与唤醒 → TimerWakeupActivity (闹钟/事件/倒计时)
 */
public class ModelSettingsActivity extends Activity {

    public static final String EXTRA_NEW_AGENT = "new_agent";
    public static final String EXTRA_SECTION = "section";
    public static final String SEC_INDEX = "";
    public static final String SEC_TIMER = "timer";
    public static final String SEC_BASIC = "basic";
    public static final String SEC_PERMS = "perms";
    public static final String SEC_API = "api";
    public static final String SEC_POOL = "pool";
    public static final String SEC_PROMPT = "prompt";

    private Prefs prefs;
    private SessionStore store;
    private String agentId;
    private ModelInfo m;
    private boolean isNewAgent = false;
    private String section = SEC_INDEX;

    private TextView tvMsTitle, btnMsBack;
    private ScrollView svIndex, svDetail;
    private LinearLayout llIndex;

    // 基本信息
    private EditText etModelId, etModelName, etModelCtx, etModelOut;
    private CheckBox cbVision;
    // API
    private EditText etAgentUrl, etAgentKey;
    private CheckBox cbAgentAutoStart;
    private boolean agentKeyVisible = false;
    // 权限
    private CheckBox cbPermShell, cbPermA11y, cbPermFile, cbPermPhoto,
            cbPermMedia, cbPermMusic, cbPermExpWrite, cbPermExpRead;
    // 经验池
    private LinearLayout llPoolPerms;
    private static class PoolPermRow {
        PoolInfo pool;
        CheckBox cbRead, cbWrite, cbDel;
    }
    private final List<PoolPermRow> poolRows = new ArrayList<>();
    // Prompt
    private EditText etCustomPrompt;
    private TextView tvDefaultPrompt, tvEffectivePrompt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_settings);
        prefs = new Prefs(this);
        store = new SessionStore(this);
        BackgroundHelper.apply(this, findViewById(R.id.modelRoot), BackgroundHelper.PAGE_MODEL);

        isNewAgent = getIntent().getBooleanExtra(EXTRA_NEW_AGENT, false);
        section = getIntent().getStringExtra(EXTRA_SECTION);
        if (section == null) section = SEC_INDEX;
        agentId = getIntent().getStringExtra("agentId");

        tvMsTitle = (TextView) findViewById(R.id.tvMsTitle);
        btnMsBack = (TextView) findViewById(R.id.btnMsBack);
        svIndex = (ScrollView) findViewById(R.id.svIndex);
        svDetail = (ScrollView) findViewById(R.id.svDetail);
        llIndex = (LinearLayout) findViewById(R.id.llIndex);

        etModelId = (EditText) findViewById(R.id.etModelId);
        etModelName = (EditText) findViewById(R.id.etModelName);
        etModelCtx = (EditText) findViewById(R.id.etModelCtx);
        etModelOut = (EditText) findViewById(R.id.etModelOut);
        cbVision = (CheckBox) findViewById(R.id.cbVision);
        etAgentUrl = (EditText) findViewById(R.id.etAgentUrl);
        etAgentKey = (EditText) findViewById(R.id.etAgentKey);
        cbAgentAutoStart = (CheckBox) findViewById(R.id.cbAgentAutoStart);
        cbPermShell = (CheckBox) findViewById(R.id.cbPermShell);
        cbPermA11y = (CheckBox) findViewById(R.id.cbPermA11y);
        cbPermFile = (CheckBox) findViewById(R.id.cbPermFile);
        cbPermPhoto = (CheckBox) findViewById(R.id.cbPermPhoto);
        cbPermMedia = (CheckBox) findViewById(R.id.cbPermMedia);
        cbPermMusic = (CheckBox) findViewById(R.id.cbPermMusic);
        cbPermExpWrite = (CheckBox) findViewById(R.id.cbPermExpWrite);
        cbPermExpRead = (CheckBox) findViewById(R.id.cbPermExpRead);
        llPoolPerms = (LinearLayout) findViewById(R.id.llPoolPerms);
        etCustomPrompt = (EditText) findViewById(R.id.etCustomPrompt);
        tvDefaultPrompt = (TextView) findViewById(R.id.tvDefaultPrompt);
        tvEffectivePrompt = (TextView) findViewById(R.id.tvEffectivePrompt);

        // 载入 Agent (new agent = 空白占位)
        if (isNewAgent) {
            m = new ModelInfo();
            m.id = "";
            m.name = "";
            m.vision = true;
            m.ctxIn = 128000;
            m.maxOut = 65536;
            m.permShell = m.permA11y = m.permFile = m.permPhoto = true;
            m.permMedia = m.permMusic = m.permExpRead = m.permExpWrite = true;
        } else {
            m = findAgent(agentId);
            if (m == null) {
                Toast.makeText(this, "Agent 不存在", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        }

        btnMsBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); }
        });

        ((Button) findViewById(R.id.btnModelSave)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { saveCurrentSection(); }
        });

        findViewById(R.id.btnAgentKeyVis).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                agentKeyVisible = !agentKeyVisible;
                etAgentKey.setInputType(agentKeyVisible
                        ? android.text.InputType.TYPE_CLASS_TEXT
                        : android.text.InputType.TYPE_CLASS_TEXT
                          | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                etAgentKey.setSelection(etAgentKey.getText().length());
                ((Button) v).setText(agentKeyVisible ? "隐藏" : "显示");
            }
        });

        findViewById(R.id.btnModelTest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collectApiIntoModel();
                persistModel();
                final String model = m.id;
                AgentConfig cfg = AgentConfig.resolve(ModelSettingsActivity.this, m);
                Toast.makeText(ModelSettingsActivity.this, "正在测试 " + model + " ...", Toast.LENGTH_SHORT).show();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String res = LlmClient.preflight(cfg.baseUrl, cfg.apiKey, model);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                new AlertDialog.Builder(ModelSettingsActivity.this)
                                        .setTitle(res.startsWith("OK") ? "连接正常" : "连接失败")
                                        .setMessage(res)
                                        .setPositiveButton("知道了", null)
                                        .show();
                            }
                        });
                    }
                }).start();
            }
        });

        tvDefaultPrompt.setText(AgentPrompts.defaultBase(this));
        attachCopy3s(tvDefaultPrompt, "系统提示词");
        attachCopy3s(tvEffectivePrompt, "当前生效提示词");

        if (SEC_INDEX.equals(section)) {
            enterIndex();
        } else {
            enterDetail();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (SEC_INDEX.equals(section) && m != null) {
            // 子页面保存后回来: 重新载入并刷新索引
            m = isNewAgent ? m : findAgent(agentId);
            if (m == null) { finish(); return; }
            buildIndex();
        }
    }

    // ================= 索引页 =================

    private void enterIndex() {
        svIndex.setVisibility(View.VISIBLE);
        svDetail.setVisibility(View.GONE);
        tvMsTitle.setText(isNewAgent ? "新增 Agent" : (m.name == null ? m.id : m.name));
        btnMsBack.setText(isNewAgent ? "‹ 取消" : "‹ 通讯录");
        buildIndex();
    }

    private void buildIndex() {
        llIndex.removeAllViews();
        if (isNewAgent) {
            addIndexRow("基本信息", "模型 ID / 名称 / 多模态 / 上下文 tokens", SEC_BASIC);
            TextView hint = new TextView(this);
            hint.setText("请先填写模型 ID 并保存，之后才能配置 定时器与唤醒 / 权限 / API / 经验池 / Prompt。");
            hint.setTextSize(12);
            hint.setTextColor(0xFF999999);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
            int m8 = (int) (8 * getResources().getDisplayMetrics().density);
            hlp.setMargins(m8, m8, m8, m8);
            llIndex.addView(hint, hlp);
            return;
        }

        TimerConfig tc = prefs.timer(agentId);
        addIndexRow("⏰ 定时器与唤醒", timerSummary(tc), SEC_TIMER);
        addIndexRow("基本信息", m.id + " · " + m.meta(), SEC_BASIC);
        addIndexRow("权限管理", permCountSummary(m), SEC_PERMS);
        addIndexRow("API 与自启", apiSummary(m), SEC_API);
        addIndexRow("经验池与权限", poolSummary(m), SEC_POOL);
        addIndexRow("模型级 Prompt",
                (m.customPrompt != null && !m.customPrompt.isEmpty())
                        ? "已启用（空则回退系统提示词；会话级可再覆盖）"
                        : "回退系统提示词（会话级可再覆盖）", SEC_PROMPT);

        // 删除 (红色, 仅已存在的 Agent)
        Button del = new Button(this);
        del.setText("删除此模型（相关会话一并删除）");
        del.setTextColor(0xFFFFFFFF);
        del.setBackgroundColor(0xFFD9433B);
        del.setTextSize(13);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        int m16 = (int) (16 * getResources().getDisplayMetrics().density);
        int m10 = (int) (10 * getResources().getDisplayMetrics().density);
        dlp.setMargins(m16, m10, m16, m16);
        del.setLayoutParams(dlp);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });
        llIndex.addView(del);
    }

    /** 索引行: 名称(粗) + 摘要(灰) + › ，点击进子页面。 */
    private void addIndexRow(final String title, final String sub, final String sec) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        int m16 = (int) (16 * getResources().getDisplayMetrics().density);
        int m6 = (int) (6 * getResources().getDisplayMetrics().density);
        rlp.setMargins(m16, m6, m16, m6);
        row.setLayoutParams(rlp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        texts.setLayoutParams(tlp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        texts.addView(t);

        TextView s = new TextView(this);
        s.setText(sub == null ? "" : sub);
        s.setTextSize(12);
        s.setTextColor(0xFF999999);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = (int) (2 * getResources().getDisplayMetrics().density);
        texts.addView(s, slp);
        row.addView(texts);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(18);
        arrow.setTextColor(0xFFCCCCCC);
        row.addView(arrow);

        // 底部分隔线
        View div = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 1);
        dlp.setMargins(m16, 0, m16, 0);
        div.setLayoutParams(dlp);
        div.setBackgroundColor(0xFFEEEEEE);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { openSection(sec); }
        });
        llIndex.addView(row);
        llIndex.addView(div);
    }

    private void openSection(String sec) {
        if (SEC_TIMER.equals(sec)) {
            if (isNewAgent || agentId == null) {
                Toast.makeText(this, "先保存基本信息", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, TimerWakeupActivity.class);
            i.putExtra(TimerWakeupActivity.EXTRA_CONTEXT_TYPE, "agent");
            i.putExtra("agentId", agentId);
            i.putExtra("agentName", m.name == null ? m.id : m.name);
            startActivity(i);
            return;
        }
        Intent i = new Intent(this, ModelSettingsActivity.class)
                .putExtra(EXTRA_SECTION, sec)
                .putExtra("agentId", agentId)
                .putExtra(EXTRA_NEW_AGENT, isNewAgent);
        startActivity(i);
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除模型")
                .setMessage("删除「" + (m.name == null ? m.id : m.name) + "」及其全部会话？")
                .setPositiveButton("删除", (d, w) -> {
                    for (ChatSession s : store.list()) {
                        if (m.id.equals(s.agentId)) store.remove(s.id);
                    }
                    List<ModelInfo> list = prefs.models();
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).id.equals(m.id)) { list.remove(i); break; }
                    }
                    prefs.saveModels(list);
                    prefs.clearTimer(agentId);
                    if (prefs.model().equals(m.id)) prefs.model("");
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 子页面 =================

    private void enterDetail() {
        svIndex.setVisibility(View.GONE);
        svDetail.setVisibility(View.VISIBLE);
        View basic = findViewById(R.id.llBasicBlock);
        View api = findViewById(R.id.llApiBlock);
        View perms = findViewById(R.id.llPermBlock);
        View pool = findViewById(R.id.llPoolBlock);
        View prompt = findViewById(R.id.llPromptBlock);
        basic.setVisibility(View.GONE);
        api.setVisibility(View.GONE);
        perms.setVisibility(View.GONE);
        pool.setVisibility(View.GONE);
        prompt.setVisibility(View.GONE);

        switch (section) {
            case SEC_BASIC:
                tvMsTitle.setText("基本信息");
                basic.setVisibility(View.VISIBLE);
                populateBasic();
                break;
            case SEC_API:
                tvMsTitle.setText("API 与自启");
                api.setVisibility(View.VISIBLE);
                populateApi();
                break;
            case SEC_PERMS:
                tvMsTitle.setText("权限管理");
                perms.setVisibility(View.VISIBLE);
                populatePerms();
                break;
            case SEC_POOL:
                tvMsTitle.setText("经验池与权限");
                pool.setVisibility(View.VISIBLE);
                buildPoolPermRows();
                break;
            case SEC_PROMPT:
                tvMsTitle.setText("模型级 Prompt");
                prompt.setVisibility(View.VISIBLE);
                populatePrompt();
                break;
            default:
                tvMsTitle.setText("设置");
        }
        btnMsBack.setText("‹ 返回");
    }

    private void populateBasic() {
        etModelId.setText(m.id);
        etModelId.setEnabled(isNewAgent);
        if (isNewAgent) etModelId.setHint("必填，如 qwen3.8-chat，保存后不可改");
        etModelName.setText(m.name == null ? "" : m.name);
        etModelCtx.setText(String.valueOf(m.ctxIn));
        etModelOut.setText(String.valueOf(m.maxOut));
        cbVision.setChecked(m.vision);
    }

    private void populateApi() {
        etAgentUrl.setText(m.baseUrl == null ? "" : m.baseUrl);
        etAgentKey.setText(m.apiKey == null ? "" : m.apiKey);
        cbAgentAutoStart.setChecked(m.autoStart);
    }

    private void populatePerms() {
        cbPermShell.setChecked(m.permShell);
        cbPermA11y.setChecked(m.permA11y);
        cbPermFile.setChecked(m.permFile);
        cbPermPhoto.setChecked(m.permPhoto);
        cbPermMedia.setChecked(m.permMedia);
        cbPermMusic.setChecked(m.permMusic);
        cbPermExpWrite.setChecked(m.permExpWrite);
        cbPermExpRead.setChecked(m.permExpRead);
    }

    private void populatePrompt() {
        etCustomPrompt.setText(m.customPrompt == null ? "" : m.customPrompt);
        showEffectivePrompt();
    }

    private void collectApiIntoModel() {
        String bu = etAgentUrl.getText().toString().trim();
        m.baseUrl = bu.isEmpty() ? null : bu;
        String ak = etAgentKey.getText().toString().trim();
        m.apiKey = ak.isEmpty() ? null : ak;
        m.autoStart = cbAgentAutoStart.isChecked();
    }

    private void saveCurrentSection() {
        switch (section) {
            case SEC_BASIC: {
                if (isNewAgent) {
                    String newId = etModelId.getText().toString().trim();
                    if (newId.isEmpty()) {
                        Toast.makeText(this, "请填写模型 ID", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    for (ModelInfo x : prefs.models()) {
                        if (x.id.equals(newId)) {
                            Toast.makeText(this, "已存在同 ID 模型", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    m.id = newId;
                    agentId = newId;
                }
                String name = etModelName.getText().toString().trim();
                String raw = name.isEmpty() ? m.id : name;
                // 展示名维度判重: 允许同一 API 有多个 Agent 对象 (满射), 但展示名必须互不相同
                m.name = prefs.uniqueAgentName(raw, m.id);
                m.vision = cbVision.isChecked();
                m.ctxIn = parseIntDef(etModelCtx, 128000);
                m.maxOut = parseIntDef(etModelOut, 65536);
                break;
            }
            case SEC_API:
                collectApiIntoModel();
                break;
            case SEC_PERMS:
                m.permShell = cbPermShell.isChecked();
                m.permA11y = cbPermA11y.isChecked();
                m.permFile = cbPermFile.isChecked();
                m.permPhoto = cbPermPhoto.isChecked();
                m.permMedia = cbPermMedia.isChecked();
                m.permMusic = cbPermMusic.isChecked();
                m.permExpWrite = cbPermExpWrite.isChecked();
                m.permExpRead = cbPermExpRead.isChecked();
                break;
            case SEC_POOL:
                savePoolPermRows();
                break;
            case SEC_PROMPT: {
                String cp = etCustomPrompt.getText().toString().trim();
                m.customPrompt = cp.isEmpty() ? null : cp;
                break;
            }
            default:
                return;
        }
        persistModel();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        if (isNewAgent) isNewAgent = false;
        finish();
    }

    private void persistModel() {
        List<ModelInfo> list = prefs.models();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(m.id)) {
                list.set(i, m);
                found = true;
                break;
            }
        }
        if (!found) list.add(m);
        prefs.saveModels(list);
    }

    // ---------- Pool 权限动态网格 ----------

    private void buildPoolPermRows() {
        poolRows.clear();
        llPoolPerms.removeAllViews();
        ExpStore store = new ExpStore(this);
        List<PoolInfo> pools = store.listPools();
        if (pools.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("(暂无可加入的经验池，可先在发现页 + 创建池)");
            t.setTextSize(12);
            t.setTextColor(0xFF999999);
            llPoolPerms.addView(t);
            return;
        }
        final int H = (int) (44 * getResources().getDisplayMetrics().density);
        final int m4 = (int) (4 * getResources().getDisplayMetrics().density);
        for (PoolInfo p : pools) {
            final PoolPermRow r = new PoolPermRow();
            r.pool = p;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setWeightSum(10);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, H);
            rlp.topMargin = m4;
            row.setLayoutParams(rlp);

            TextView name = new TextView(this);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 6);
            nlp.gravity = Gravity.CENTER_VERTICAL;
            name.setLayoutParams(nlp);
            String prefix = p.isGlobal() ? "🌐 " : "📦 ";
            if (!p.enabled) prefix += "❌";
            name.setText(prefix + p.name);
            name.setTextSize(13);
            row.addView(name);

            r.cbRead = mkPoolCb();
            r.cbWrite = mkPoolCb();
            r.cbDel = mkPoolCb();

            int flags = Perms.poolFlags(ModelSettingsActivity.this, agentId, p.id);
            r.cbRead.setChecked((flags & PoolInfo.POOL_READ) != 0);
            r.cbWrite.setChecked((flags & PoolInfo.POOL_WRITE) != 0);
            r.cbDel.setChecked((flags & PoolInfo.POOL_DELETE) != 0);

            // 交互保护: 写/删 必 先有读；勾读会自动给写默认勾(仅首次)；取消读会清写/删
            r.cbRead.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!r.cbRead.isChecked()) {
                        r.cbWrite.setChecked(false);
                        r.cbDel.setChecked(false);
                    } else if (!r.cbWrite.isChecked() && !r.cbDel.isChecked()) {
                        r.cbWrite.setChecked(true);
                    }
                }
            });
            r.cbWrite.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (r.cbWrite.isChecked()) r.cbRead.setChecked(true);
                }
            });
            r.cbDel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (r.cbDel.isChecked()) r.cbRead.setChecked(true);
                }
            });

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1.33f);
            clp.gravity = Gravity.CENTER;
            r.cbRead.setLayoutParams(clp);
            r.cbWrite.setLayoutParams(clp);
            r.cbDel.setLayoutParams(clp);
            row.addView(r.cbRead);
            row.addView(r.cbWrite);
            row.addView(r.cbDel);

            llPoolPerms.addView(row);
            poolRows.add(r);
        }
    }

    private CheckBox mkPoolCb() {
        CheckBox c = new CheckBox(this);
        c.setGravity(Gravity.CENTER);
        return c;
    }

    /** 将 poolRows 里的勾选结果写回 agent_pool_access。 */
    private void savePoolPermRows() {
        if (agentId == null || agentId.isEmpty()) return;
        for (PoolPermRow r : poolRows) {
            int f = 0;
            if (r.cbRead.isChecked()) f |= PoolInfo.POOL_READ;
            if (r.cbWrite.isChecked()) f |= PoolInfo.POOL_WRITE;
            if (r.cbDel.isChecked()) f |= PoolInfo.POOL_DELETE;
            Perms.setPoolFlags(ModelSettingsActivity.this, agentId, r.pool.id, f);
        }
    }

    // ---------- Prompt ----------

    private void showEffectivePrompt() {
        // 这里展示的是"模型级"回退结果; 单个对话还可在会话设置里用会话提示词覆盖
        String base = (m.customPrompt != null && !m.customPrompt.isEmpty())
                ? m.customPrompt
                : AgentPrompts.defaultBase(this);
        String tools = AgentPrompts.toolDocs(m);
        tvEffectivePrompt.setText("当前模型级生效: " + (m.customPrompt != null ? "模型提示词" : "系统提示词")
                + "（回退链: 会话提示词 → 模型提示词 → 系统提示词；会话级在「对话→设置」里改）"
                + "\n【角色/规则】\n" + base
                + "\n\n【可用工具(按当前权限)】\n" + tools);
        tvEffectivePrompt.setVisibility(View.VISIBLE);
    }

    // ---------- 摘要 ----------

    private static final String[] DOW_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private String timerSummary(TimerConfig tc) {
        List<String> parts = new ArrayList<>();
        if (tc.alarmConfigured()) {
            StringBuilder b = new StringBuilder();
            b.append(tc.alarmHour).append(":").append(String.format("%02d", tc.alarmMinute));
            if (tc.allDays()) b.append(" 每天");
            else if (tc.anyDay()) {
                List<String> ds = new ArrayList<>();
                for (int i = 0; i < 7; i++) if (tc.alarmDays[i]) ds.add(DOW_NAMES[i]);
                b.append(" ").append(join(ds, ""));
            }
            if (!tc.alarmMonthDays.isEmpty()) b.append(" 每月").append(join(tc.alarmMonthDays, "/")).append("号");
            parts.add(b.toString());
        }
        if (tc.taskDoneEnabled) parts.add("任务完成×" + tc.taskDoneSessions.size());
        if (tc.appOpenEnabled) parts.add("打开软件×" + tc.appOpenPackages.size());
        if (tc.countdownEnabled) parts.add("倒计时 " + tc.cdHour + "时" + tc.cdMinute + "分" + tc.cdSecond + "秒");
        return parts.isEmpty() ? "未设置" : join(parts, "　");
    }

    private String permCountSummary(ModelInfo m) {
        int n = 0;
        if (m.permShell) n++;
        if (m.permA11y) n++;
        if (m.permFile) n++;
        if (m.permPhoto) n++;
        if (m.permMedia) n++;
        if (m.permMusic) n++;
        if (m.permExpWrite) n++;
        if (m.permExpRead) n++;
        return n + "/8 项已开启";
    }

    private String apiSummary(ModelInfo m) {
        String b = m.baseUrl != null && !m.baseUrl.isEmpty() ? m.baseUrl : "全局默认";
        return b + (m.autoStart ? " · 开机自启" : "");
    }

    private String poolSummary(ModelInfo m) {
        ExpStore es = new ExpStore(this);
        int n = 0;
        for (PoolInfo p : es.listPools()) {
            if ((Perms.poolFlags(this, m.id, p.id) & PoolInfo.POOL_READ) != 0) n++;
        }
        return "已加入 " + n + " 个池";
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private int parseIntDef(EditText e, int def) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return v > 0 ? v : def;
        } catch (Exception ex) {
            return def;
        }
    }

    private ModelInfo findAgent(String agentId) {
        if (agentId == null) return null;
        for (ModelInfo x : prefs.models()) {
            if (x.id.equals(agentId)) return x;
        }
        return null;
    }

    /** Long-press ~3s copies the view's text to the clipboard. */
    private void attachCopy3s(final TextView tv, final String what) {
        tv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(what, tv.getText().toString()));
                    Toast.makeText(ModelSettingsActivity.this, "已复制: " + what, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(ModelSettingsActivity.this, "复制失败", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
    }
}
