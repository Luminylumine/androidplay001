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
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 定时器与唤醒 (per-agent, 避免 Agent 24h 工作):
 *  - 闹钟式定时唤醒: 24h 绝对时间(数字输入) + 重复(周一~日, 全选=每天 / 每月N号, 超月取最后一天)
 *      每月号框: 输入一个后点「+ 增加一天」追加框; 空白框保存时丢弃, 操作期间不删
 *  - 事件唤醒: 任务完成(聊天会话多选) / 打开软件(用户应用多选)
 *      点方框=勾选/取消(不删触发源设置); 点设置项=进触发源选择; 确定后自动弹触发结果
 *  - 倒计时: H/M/S 数字输入 + 开始/暂停/重置, 计时直接改写方框; 到 0 触发 (预留扩展)
 *  - 顶栏: 退出(红, 放弃) / 清空(黄, 重置并保存) / 保存(蓝); 系统返回手势弹三选(不保存/保存/取消)
 */
public class TimerWakeupActivity extends Activity {

    private static final int REQ_PICKER = 1;
    private static final int REQ_RESULT = 2;
    private static final int REQ_SEARCH_PICKER = 3;
    private static final String[] DOW = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    public static final String EXTRA_CONTEXT_TYPE = "contextType";
    public static final String EXTRA_SESSION_ID = "sessionId";
    private static final String CTX_AGENT = "agent";
    private static final String CTX_SESSION = "session";

    private Prefs prefs;
    private String agentId;
    private String agentName;
    private String contextType;
    private String sessionId;
    private TimerConfig cfg;
    private boolean dirty = false;

    private LinearLayout body;

    // 闹钟
    private boolean alarmExpanded = false;
    private CheckBox cbAlarmOn;
    private EditText etAlarmH, etAlarmM;
    private final CheckBox[] cbDays = new CheckBox[7];
    private LinearLayout llMonthDays;

    // 事件
    private CheckBox cbTaskDoneOn, cbAppOpenOn;
    private TextView tvTaskDoneSum, tvAppOpenSum;

    // 倒计时
    private boolean cdExpanded = false;
    private EditText etCdH, etCdM, etCdS;
    private long cdInitialSec = 0;

    /** 当前正在走"选源→选结果"流程的唤醒源 */
    private String activeEvent = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timer_wakeup);
        BackgroundHelper.apply(this, findViewById(R.id.timerRoot), BackgroundHelper.PAGE_MODEL);

        prefs = new Prefs(this);
        agentId = getIntent().getStringExtra("agentId");
        agentName = getIntent().getStringExtra("agentName");
        contextType = getIntent().getStringExtra(EXTRA_CONTEXT_TYPE);
        if (contextType == null || contextType.isEmpty()) contextType = CTX_AGENT;
        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        if (agentId == null || agentId.isEmpty()) {
            Toast.makeText(this, "缺少 agentId", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (CTX_SESSION.equals(contextType) && (sessionId == null || sessionId.isEmpty())) {
            Toast.makeText(this, "缺少 sessionId", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (CTX_SESSION.equals(contextType)) {
            cfg = prefs.sessionTimer(sessionId);
        } else {
            cfg = prefs.timer(agentId);
        }

        TextView title = (TextView) findViewById(R.id.tvTwTitle);
        String titlePrefix = CTX_SESSION.equals(contextType) ? "会话级" : "模型级";
        title.setText("定时器与唤醒 — " + titlePrefix + " — "
                + (agentName == null || agentName.isEmpty() ? agentId : agentName));

        body = (LinearLayout) findViewById(R.id.llTimerBody);

        findViewById(R.id.btnTwSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { saveAndExit(); }
        });
        findViewById(R.id.btnTwClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(TimerWakeupActivity.this)
                        .setTitle("清空")
                        .setMessage("清空本" + (CTX_SESSION.equals(contextType) ? "会话" : "Agent") + "的全部定时器与唤醒设置？")
                        .setPositiveButton("清空", (d, w) -> {
                            cfg = TimerConfig.def();
                            dirty = false;
                            TimerEngine.resetCountdown();
                            alarmExpanded = false;
                            cdExpanded = false;
                            cdInitialSec = 0;
                            if (CTX_SESSION.equals(contextType)) {
                                prefs.clearSessionTimer(sessionId);
                            } else {
                                prefs.clearTimer(agentId);
                            }
                            build();
                            Toast.makeText(TimerWakeupActivity.this, "已清空", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        findViewById(R.id.btnTwExit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { finish(); } // 退出 = 放弃未保存修改
        });

        build();
    }

    // ================= UI 构建 =================

    private void build() {
        body.removeAllViews();
        // 初始化每行的视图引用(重建后重新绑定)
        cbAlarmOn = null; etAlarmH = null; etAlarmM = null;
        for (int i = 0; i < 7; i++) cbDays[i] = null;
        llMonthDays = null;
        cbTaskDoneOn = null; cbAppOpenOn = null;
        tvTaskDoneSum = null; tvAppOpenSum = null;
        etCdH = null; etCdM = null; etCdS = null;

        // ---- 1) 闹钟式定时唤醒 ----
        addSectionHeader("⏰ 闹钟式定时唤醒", alarmSummary(),
                alarmExpanded ? "▾" : "▸", v -> {
                    if (alarmExpanded) collectAlarm(); // 折叠前先收集, 避免丢输入
                    alarmExpanded = !alarmExpanded;
                    build();
                });
        if (alarmExpanded) buildAlarmBody();

        // ---- 2) 事件唤醒: 任务完成 ----
        addEventRow(true,
                "任务完成",
                cfg.taskDoneEnabled ? taskDoneSum() : "未启用",
                cfg.taskDoneEnabled,
                checked -> { cfg.taskDoneEnabled = checked; dirty = true;
                    tvTaskDoneSum.setText(checked ? taskDoneSum() : "未启用");
                },
                v -> openPicker("task_done"),
                taskDoneMoreCount() > 4 ? (v -> openSearchPicker()) : null);

        // ---- 3) 事件唤醒: 打开软件 ----
        addEventRow(false,
                "打开软件",
                cfg.appOpenEnabled
                        ? "监听 " + cfg.appOpenPackages.size() + " 个应用 · " + cfg.appOpenResult.describe()
                        : "未启用",
                cfg.appOpenEnabled,
                checked -> { cfg.appOpenEnabled = checked; dirty = true;
                    tvAppOpenSum.setText(checked
                            ? "监听 " + cfg.appOpenPackages.size() + " 个应用 · " + cfg.appOpenResult.describe()
                            : "未启用");
                },
                v -> openPicker("app_open"),
                null);

        // ---- 4) 倒计时定时器 ----
        addSectionHeader("⏳ 倒计时定时器",
                (cfg.cdHour + cfg.cdMinute + cfg.cdSecond) > 0
                        ? cfg.cdHour + "时" + cfg.cdMinute + "分" + cfg.cdSecond + "秒 · " + cfg.countdownResult.describe()
                        : "未设置",
                cdExpanded ? "▾" : "▸", v -> {
                    if (cdExpanded) collectCountdown(); // 折叠前先收集
                    cdExpanded = !cdExpanded;
                    build();
                });
        if (cdExpanded) buildCountdownBody();
    }

    /** 卡片头: 标题 + 摘要 + 展开箭头, 点击回调。 */
    private void addSectionHeader(String title, String sum, String arrow, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        int m8 = dp(8);
        clp.setMargins(dp(12), m8, dp(12), 0);
        card.setLayoutParams(clp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(10), dp(12), dp(10));
        head.setClickable(true);
        head.setFocusable(true);
        head.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        t.setLayoutParams(tlp);
        head.addView(t);

        TextView s = new TextView(this);
        s.setText(sum);
        s.setTextSize(11);
        s.setTextColor(0xFF999999);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(s);

        TextView a = new TextView(this);
        a.setText(arrow);
        a.setTextSize(14);
        a.setTextColor(0xFFCCCCCC);
        head.addView(a);

        head.setOnClickListener(onClick);
        card.addView(head);

        // 分隔线
        View div = new View(this);
        div.setBackgroundColor(0xFFEEEEEE);
        card.addView(div, new LinearLayout.LayoutParams(-1, 1));

        body.addView(card);
    }

    /** 事件唤醒行: [勾选框] 名称 + 摘要 + › ；勾方框=启用/取消(保留触发源), 点行=进触发源选择。
     *  extraBtn: 非null 时，在 摘要行 右侧加一个蓝色文字按钮（如"更多(N)"）。*/
    private void addEventRow(boolean isTaskDone, String title, String sum, boolean checked,
                             final CompoundLike onCheck, final View.OnClickListener onRowClick,
                             final View.OnClickListener extraBtn) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(12), dp(10), dp(12), dp(10));
        head.setClickable(true);
        head.setFocusable(true);
        head.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        int m8 = dp(8);
        hlp.setMargins(dp(12), m8, dp(12), 0);
        head.setLayoutParams(hlp);

        final CheckBox cb = new CheckBox(this);
        cb.setOnCheckedChangeListener(null);
        cb.setChecked(checked);
        cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                onCheck.run(isChecked);
            }
        });
        if (isTaskDone) cbTaskDoneOn = cb; else cbAppOpenOn = cb;
        head.addView(cb);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        tlp.leftMargin = dp(6);
        texts.setLayoutParams(tlp);
        TextView t = new TextView(this);
        t.setText("✔ " + title);
        t.setTextSize(14);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        texts.addView(t);

        // 第二行: 摘要左侧 + 更多(N) 按钮（右侧）
        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView s = new TextView(this);
        s.setText(sum);
        s.setTextSize(11);
        s.setTextColor(0xFF999999);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, -2, 1);
        slp.topMargin = dp(2);
        subRow.addView(s, slp);
        if (isTaskDone) tvTaskDoneSum = s; else tvAppOpenSum = s;

        if (extraBtn != null && isTaskDone) {
            Button more = new Button(this);
            more.setText("更多(" + taskDoneMoreCount() + ")");
            more.setTextColor(0xFFFFFFFF);
            more.setTextSize(11);
            more.setBackgroundColor(0xFF0A66C2);
            more.setMinWidth(0);
            more.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
            mlp.leftMargin = dp(6);
            more.setLayoutParams(mlp);
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    v.setTag(null); // prevent parent click propagation
                    extraBtn.onClick(v);
                }
            });
            subRow.addView(more);
        }
        texts.addView(subRow, new LinearLayout.LayoutParams(-1, -2));
        head.addView(texts);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(16);
        arrow.setTextColor(0xFFCCCCCC);
        head.addView(arrow);

        head.setOnClickListener(onRowClick);
        body.addView(head);

        View div = new View(this);
        div.setBackgroundColor(0xFFEEEEEE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, 1);
        dlp.setMargins(dp(12), 0, dp(12), 0);
        div.setLayoutParams(dlp);
        body.addView(div);
    }

    /** 事件勾选回调小接口。 */
    private interface CompoundLike {
        void run(boolean checked);
    }

    // ---------------- 闹钟 body ----------------

    private void buildAlarmBody() {
        LinearLayout card = lastCard();
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(12), dp(10), dp(12), dp(12));
        inner.setBackgroundColor(0xFFFAFAFA);
        card.addView(inner, new LinearLayout.LayoutParams(-1, -2));

        cbAlarmOn = new CheckBox(this);
        cbAlarmOn.setText("启用闹钟");
        cbAlarmOn.setTextSize(13);
        cbAlarmOn.setChecked(cfg.alarmEnabled);
        cbAlarmOn.setOnCheckedChangeListener((c, isChecked) -> {
            cfg.alarmEnabled = isChecked;
            dirty = true;
        });
        inner.addView(cbAlarmOn);

        // 时间 (24h 数字输入)
        addLabel(inner, "唤醒时间（24 小时制，数字输入）");
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        inner.addView(timeRow, new LinearLayout.LayoutParams(-1, -2));
        etAlarmH = newNumEdit(23);
        timeRow.addView(etAlarmH, new LinearLayout.LayoutParams(dp(56), -2));
        timeRow.addView(colon());
        etAlarmM = newNumEdit(59);
        timeRow.addView(etAlarmM, new LinearLayout.LayoutParams(dp(56), -2));
        timeRow.addView(new TextView2("  时 : 分"));

        // 重复: 一周 7 天
        addLabel(inner, "重复（勾选周几；7 天全选 = 每天）");
        LinearLayout dowRow1 = new LinearLayout(this);
        LinearLayout dowRow2 = new LinearLayout(this);
        dowRow1.setOrientation(LinearLayout.HORIZONTAL);
        dowRow2.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 7; i++) {
            final int di = i;
            final CheckBox cb = new CheckBox(this);
            cb.setText(DOW[i]);
            cb.setTextSize(12);
            cb.setChecked(cfg.alarmDays[i]);
            cb.setOnCheckedChangeListener((c, isChecked) -> {
                cfg.alarmDays[di] = isChecked;
                dirty = true;
            });
            cbDays[i] = cb;
            (i < 4 ? dowRow1 : dowRow2).addView(cb,
                    new LinearLayout.LayoutParams(0, -2, 1));
        }
        inner.addView(dowRow1, new LinearLayout.LayoutParams(-1, -2));
        inner.addView(dowRow2, new LinearLayout.LayoutParams(-1, -2));

        // 每月 N 号 (手动输入, 逐个增框)
        addLabel(inner, "每月 N 号（手动输入；超过当月天数取当月最后一天；空白框保存时自动丢弃）");
        llMonthDays = new LinearLayout(this);
        llMonthDays.setOrientation(LinearLayout.VERTICAL);
        inner.addView(llMonthDays, new LinearLayout.LayoutParams(-1, -2));

        // 初始: 已存的非空号各一框; 若全空则给 1 个空白框
        List<String> initial = new ArrayList<>();
        for (String s : cfg.alarmMonthDays) if (s != null && !s.isEmpty()) initial.add(s);
        if (initial.isEmpty()) initial.add("");
        for (String s : initial) addMonthDayBox(s, false);
        addMonthDayButton();

        // 触发结果
        addResultRow(inner, "闹钟", cfg.alarmResult, "alarm");
    }

    /** 每月号输入框行: [框] [+ 增加一天](仅最后一行有)。 */
    private void addMonthDayBox(final String value, boolean withButton) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(4);
        row.setLayoutParams(rlp);

        EditText e = newNumEdit(31);
        e.setHint("1-31");
        if (value != null && !value.isEmpty()) e.setText(value);
        row.addView(e, new LinearLayout.LayoutParams(dp(64), -2));

        if (withButton) {
            Button addBtn = new Button(this);
            addBtn.setText("+ 增加一天");
            addBtn.setTextSize(11);
            addBtn.setMinWidth(0);
            addBtn.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.leftMargin = dp(8);
            addBtn.setLayoutParams(blp);
            // 点击后: 把"当前最后一个框+按钮"变成普通框, 再追加新框+按钮
            addBtn.setOnClickListener(v -> {
                // 找到 llMonthDays 里最后一个带按钮的行, 移除其按钮, 追加新行
                for (int i = llMonthDays.getChildCount() - 1; i >= 0; i--) {
                    View ch = llMonthDays.getChildAt(i);
                    if (ch instanceof LinearLayout) {
                        LinearLayout lr = (LinearLayout) ch;
                        for (int j = lr.getChildCount() - 1; j >= 0; j--) {
                            if (lr.getChildAt(j) instanceof Button) {
                                lr.removeViewAt(j);
                                break;
                            }
                        }
                        break;
                    }
                }
                addMonthDayBox("", true);
                dirty = true;
            });
            row.addView(addBtn);
        }
        llMonthDays.addView(row);
    }

    private void addMonthDayButton() {
        // 追加一个带 + 按钮的空行 (输入一个后再增一个)
        addMonthDayBox("", true);
    }

    // ---------------- 倒计时 body ----------------

    private void buildCountdownBody() {
        LinearLayout card = lastCard();
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(12), dp(10), dp(12), dp(12));
        inner.setBackgroundColor(0xFFFAFAFA);
        card.addView(inner, new LinearLayout.LayoutParams(-1, -2));

        addLabel(inner, "倒计时（数字输入；开始后直接改写下方方框；到 0 触发）");
        LinearLayout cdRow = new LinearLayout(this);
        cdRow.setOrientation(LinearLayout.HORIZONTAL);
        cdRow.setGravity(Gravity.CENTER_VERTICAL);
        inner.addView(cdRow, new LinearLayout.LayoutParams(-1, -2));
        etCdH = newNumEdit(23);
        etCdH.setText(String.valueOf(cfg.cdHour));
        cdRow.addView(etCdH, new LinearLayout.LayoutParams(dp(56), -2));
        cdRow.addView(new TextView2(" 时"));
        etCdM = newNumEdit(59);
        etCdM.setText(pad2(cfg.cdMinute));
        cdRow.addView(etCdM, new LinearLayout.LayoutParams(dp(56), -2));
        cdRow.addView(new TextView2(" 分"));
        etCdS = newNumEdit(59);
        etCdS.setText(pad2(cfg.cdSecond));
        cdRow.addView(etCdS, new LinearLayout.LayoutParams(dp(56), -2));
        cdRow.addView(new TextView2(" 秒"));

        // 恢复正在进行的倒计时UI
        if (TimerEngine.countdownRunning()) {
            long remain = TimerEngine.countdownRemainingMs() / 1000;
            etCdH.setText(String.valueOf(remain / 3600));
            etCdM.setText(pad2((int) ((remain % 3600) / 60)));
            etCdS.setText(pad2((int) (remain % 60)));
            cdUiUpdater.run(); // 注册定期更新
        }

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(-1, -2);
        brlp.topMargin = dp(8);
        btnRow.setLayoutParams(brlp);
        Button bStart = new Button(this);
        bStart.setText("开始");
        bStart.setMinWidth(0);
        bStart.setTextSize(12);
        bStart.setBackgroundColor(0xFF0A66C2);
        bStart.setTextColor(0xFFFFFFFF);
        Button bPause = new Button(this);
        bPause.setText("暂停");
        bPause.setMinWidth(0);
        bPause.setTextSize(12);
        bPause.setBackgroundColor(0xFFFFC107);
        bPause.setTextColor(0xFF000000);
        Button bReset = new Button(this);
        bReset.setText("重置");
        bReset.setMinWidth(0);
        bReset.setTextSize(12);
        bReset.setBackgroundColor(0xFFD9433B);
        bReset.setTextColor(0xFFFFFFFF);
        btnRow.addView(bStart, new LinearLayout.LayoutParams(0, -2, 1));
        btnRow.addView(bPause, new LinearLayout.LayoutParams(0, -2, 1));
        btnRow.addView(bReset, new LinearLayout.LayoutParams(0, -2, 1));
        inner.addView(btnRow);

        bStart.setOnClickListener(v -> {
            long total = cdSecondsFromBoxes();
            if (total <= 0) {
                Toast.makeText(this, "请先输入大于 0 的时间", Toast.LENGTH_SHORT).show();
                return;
            }
            cdInitialSec = total;
            cfg.countdownEnabled = true;
            cfg.cdHour = (int) (total / 3600);
            cfg.cdMinute = (int) ((total % 3600) / 60);
            cfg.cdSecond = (int) (total % 60);
            dirty = true;
            TimerEngine.startCountdown(agentId, total, cdUiUpdater);
            Toast.makeText(this, "倒计时开始", Toast.LENGTH_SHORT).show();
        });
        bPause.setOnClickListener(v -> {
            if (!TimerEngine.countdownRunning()) {
                Toast.makeText(this, "未在计时", Toast.LENGTH_SHORT).show();
                return;
            }
            TimerEngine.pauseCountdown();
        });
        bReset.setOnClickListener(v -> {
            TimerEngine.resetCountdown();
            long back = cdInitialSec > 0 ? cdInitialSec : 0;
            etCdH.setText(String.valueOf(back / 3600));
            etCdM.setText(pad2((int) ((back % 3600) / 60)));
            etCdS.setText(pad2((int) (back % 60)));
        });

        TextView note = new TextView2("说明: 计时仅在本 App 进程存活期间有效（预留扩展: 系统级保活/AlarmManager）。");
        note.setTextColor(0xFF999999);
        note.setTextSize(10);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = dp(8);
        inner.addView(note, nlp);

        addResultRow(inner, "倒计时", cfg.countdownResult, "countdown");
    }

    /** 倒计时 UI 更新: 直接把剩余时间写回方框 (无单独显示区)。 */
    private final Runnable cdUiUpdater = new Runnable() {
        @Override
        public void run() {
            if (etCdH == null || etCdM == null || etCdS == null) return;
            long s = TimerEngine.countdownRemainingMs() / 1000;
            etCdH.setText(String.valueOf(s / 3600));
            etCdM.setText(pad2((int) ((s % 3600) / 60)));
            etCdS.setText(pad2((int) (s % 60)));
        }
    };

    // ---------------- 触发结果行 ----------------

    private void addResultRow(LinearLayout parent, String what, TimerConfig.TriggerResult tr, final String event) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(10);
        row.setLayoutParams(rlp);

        TextView t = new TextView(this);
        t.setText("触发结果 · " + what);
        t.setTextSize(13);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1);
        t.setLayoutParams(tlp);
        row.addView(t);

        TextView s = new TextView(this);
        s.setText(tr.describe() + "  ›");
        s.setTextSize(11);
        s.setTextColor(0xFF0A66C2);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(s);

        row.setOnClickListener(v -> openResult(event));
        parent.addView(row);
    }

    // ================= 导航: 选源 → 选结果 =================

    private void openSearchPicker() {
        Intent i = new Intent(this, SearchPickerActivity.class);
        // 只把 任务完成里 单独的会话 id 作为已勾选
        List<String> standalone = new ArrayList<>();
        for (String s : cfg.taskDoneSessions) {
            if (TriggerSourcePickerActivity.ALL_SESSIONS.equals(s)) continue;
            if (s.startsWith(TriggerSourcePickerActivity.AGENT_PREFIX)) continue;
            standalone.add(s);
        }
        i.putExtra(SearchPickerActivity.EXTRA_SELECTED, new JSONArray(standalone).toString());
        startActivityForResult(i, REQ_SEARCH_PICKER);
    }

    /** 任务完成里「单独会话」的数量（排除 ALL_SESSIONS 和 AGENT_PREFIX:* 组选），
     *  超过 4 时显示"更多(N)"按钮。 */
    private int taskDoneMoreCount() {
        int n = 0;
        for (String s : cfg.taskDoneSessions) {
            if (TriggerSourcePickerActivity.ALL_SESSIONS.equals(s)) continue;
            if (s.startsWith(TriggerSourcePickerActivity.AGENT_PREFIX)) continue;
            n++;
        }
        return n;
    }

    private void openPicker(String event) {
        activeEvent = event;
        Intent i = new Intent(this, TriggerSourcePickerActivity.class);
        i.putExtra(TriggerSourcePickerActivity.EXTRA_AGENT_ID, agentId);
        i.putExtra(TriggerSourcePickerActivity.EXTRA_AGENT_NAME, agentName);
        i.putExtra(TriggerSourcePickerActivity.EXTRA_EVENT, event);
        i.putExtra(TriggerSourcePickerActivity.EXTRA_SELECTED,
                "task_done".equals(event)
                        ? new JSONArray(cfg.taskDoneSessions).toString()
                        : new JSONArray(cfg.appOpenPackages).toString());
        startActivityForResult(i, REQ_PICKER);
    }

    private void openResult(String event) {
        activeEvent = event;
        Intent i = new Intent(this, TriggerResultActivity.class);
        i.putExtra(TriggerResultActivity.EXTRA_EVENT, event);
        i.putExtra(TriggerResultActivity.EXTRA_AGENT_NAME, agentName);
        TimerConfig.TriggerResult tr;
        switch (event) {
            case "task_done": tr = cfg.taskDoneResult; break;
            case "app_open": tr = cfg.appOpenResult; break;
            case "alarm": tr = cfg.alarmResult; break;
            default: tr = cfg.countdownResult; break;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("type", tr.type);
            o.put("sendMode", tr.sendMode);
            o.put("message", tr.message == null ? "" : tr.message);
            i.putExtra(TriggerResultActivity.EXTRA_RESULT, o.toString());
        } catch (Exception ignored) {}
        startActivityForResult(i, REQ_RESULT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (activeEvent == null) return;
        try {
            if (requestCode == REQ_PICKER) {
                if (resultCode == RESULT_OK && data != null) {
                    String sel = data.getStringExtra(TriggerSourcePickerActivity.EXTRA_RESULT);
                    List<String> list = new ArrayList<>();
                    if (sel != null) {
                        JSONArray arr = new JSONArray(sel);
                        for (int i = 0; i < arr.length(); i++) list.add(arr.optString(i));
                    }
                    if ("task_done".equals(activeEvent)) {
                        cfg.taskDoneSessions = list;
                        cfg.taskDoneEnabled = !list.isEmpty();
                    } else if ("app_open".equals(activeEvent)) {
                        cfg.appOpenPackages = list;
                        cfg.appOpenEnabled = !list.isEmpty();
                    }
                    dirty = true;
                }
                // 调整完触发源 → 自动弹出触发结果窗口
                openResult(activeEvent);
                return;
            }
            if (requestCode == REQ_SEARCH_PICKER) {
                if (resultCode == RESULT_OK && data != null) {
                    String sel = data.getStringExtra(SearchPickerActivity.EXTRA_RESULT);
                    java.util.Set<String> replaceStandalone = new java.util.HashSet<>();
                    if (sel != null) {
                        JSONArray arr = new JSONArray(sel);
                        for (int i = 0; i < arr.length(); i++) replaceStandalone.add(arr.optString(i));
                    }
                    // 只替换 「单独会话」 条目，保留 ALL_SESSIONS / agent:* 勾选不动
                    List<String> newList = new ArrayList<>();
                    for (String old : cfg.taskDoneSessions) {
                        if (TriggerSourcePickerActivity.ALL_SESSIONS.equals(old)
                                || old.startsWith(TriggerSourcePickerActivity.AGENT_PREFIX)) {
                            newList.add(old);
                        }
                    }
                    for (String s : replaceStandalone) if (!newList.contains(s)) newList.add(s);
                    cfg.taskDoneSessions = newList;
                    cfg.taskDoneEnabled = !newList.isEmpty();
                    dirty = true;
                }
                build();
                return;
            }
            if (requestCode == REQ_RESULT) {
                if (resultCode == RESULT_OK && data != null) {
                    String j = data.getStringExtra(TriggerResultActivity.EXTRA_RESULT);
                    if (j != null) {
                        JSONObject o = new JSONObject(j);
                        TimerConfig.TriggerResult tr = new TimerConfig.TriggerResult();
                        tr.type = o.optInt("type", TimerConfig.TRIG_SEND_CONTINUE);
                        tr.sendMode = o.optInt("sendMode", TimerConfig.MODE_QUEUE);
                        tr.message = o.optString("message", "");
                        switch (activeEvent) {
                            case "task_done": cfg.taskDoneResult = tr; break;
                            case "app_open": cfg.appOpenResult = tr; break;
                            case "alarm": cfg.alarmResult = tr; break;
                            default: cfg.countdownResult = tr; break;
                        }
                        dirty = true;
                    }
                }
                // 无论确定还是取消, 都刷新 (选源阶段的改动已写入 cfg)
                build();
            }
        } catch (Exception e) {
            CpLog.w("TimerWakeup", "onActivityResult: " + e);
        }
    }

    // ================= 保存 / 清空 / 返回 =================

    private void collectAlarm() {
        cfg.alarmEnabled = cbAlarmOn != null && cbAlarmOn.isChecked();
        cfg.alarmHour = clampInt(etAlarmH == null ? null : etAlarmH.getText().toString().trim(), 23, -1);
        cfg.alarmMinute = clampInt(etAlarmM == null ? null : etAlarmM.getText().toString().trim(), 59, 0);
        for (int i = 0; i < 7; i++) {
            cfg.alarmDays[i] = cbDays[i] != null && cbDays[i].isChecked();
        }
        // 每月号: 收集所有非空框 (空白框丢弃)
        List<String> days = new ArrayList<>();
        if (llMonthDays != null) {
            for (int i = 0; i < llMonthDays.getChildCount(); i++) {
                View ch = llMonthDays.getChildAt(i);
                if (!(ch instanceof LinearLayout)) continue;
                for (int j = 0; j < ((LinearLayout) ch).getChildCount(); j++) {
                    View e = ((LinearLayout) ch).getChildAt(j);
                    if (e instanceof EditText) {
                        String v = ((EditText) e).getText().toString().trim();
                        if (!v.isEmpty()) days.add(v);
                    }
                }
            }
        }
        cfg.alarmMonthDays = days;
    }

    private void collectCountdown() {
        long total = cdSecondsFromBoxes();
        cfg.cdHour = (int) (total / 3600);
        cfg.cdMinute = (int) ((total % 3600) / 60);
        cfg.cdSecond = (int) (total % 60);
        cfg.countdownEnabled = total > 0;
    }

    private long cdSecondsFromBoxes() {
        long h = Math.max(0, Math.min(99, parseInt0(etCdH == null ? "" : etCdH.getText().toString().trim())));
        long m = Math.max(0, Math.min(59, parseInt0(etCdM == null ? "" : etCdM.getText().toString().trim())));
        long s = Math.max(0, Math.min(59, parseInt0(etCdS == null ? "" : etCdS.getText().toString().trim())));
        return h * 3600 + m * 60 + s;
    }

    private void saveAndExit() {
        if (alarmExpanded) collectAlarm();
        if (cdExpanded) collectCountdown();
        if (CTX_SESSION.equals(contextType)) {
            prefs.saveSessionTimer(sessionId, cfg);
        } else {
            prefs.saveTimer(agentId, cfg);
        }
        dirty = false;
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public void onBackPressed() {
        if (dirty) {
            new AlertDialog.Builder(this)
                    .setTitle("未保存")
                    .setMessage("是否保存当前定时器设置？")
                    .setPositiveButton("保存", (d, w) -> saveAndExit())
                    .setNeutralButton("不保存", (d, w) -> finish())
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    // ================= 小工具 =================

    private String alarmSummary() {
        if (!cfg.alarmEnabled || cfg.alarmHour < 0) return "未设置";
        StringBuilder b = new StringBuilder();
        b.append(pad2(cfg.alarmHour)).append(":").append(pad2(cfg.alarmMinute));
        if (cfg.allDays()) b.append(" 每天");
        else if (cfg.anyDay()) {
            List<String> ds = new ArrayList<>();
            for (int i = 0; i < 7; i++) if (cfg.alarmDays[i]) ds.add(DOW[i].substring(1));
            b.append(" ").append(joinStr(ds, ""));
        }
        if (!cfg.alarmMonthDays.isEmpty()) b.append(" 每月").append(joinStr(cfg.alarmMonthDays, "/")).append("号");
        return b.toString();
    }

    /** 刚加进 body 的卡片(分隔线之上的那个 LinearLayout)。 */
    private LinearLayout lastCard() {
        for (int i = body.getChildCount() - 1; i >= 0; i--) {
            View v = body.getChildAt(i);
            if (v instanceof LinearLayout) return (LinearLayout) v;
        }
        return null;
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView t = new TextView2(text);
        t.setTextSize(12);
        t.setTextColor(0xFF666666);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(8);
        parent.addView(t, tlp);
    }

    private EditText newNumEdit(int maxLen) {
        EditText e = new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        e.setTextSize(14);
        return e;
    }

    private TextView colon() {
        TextView t = new TextView2(":");
        t.setTextSize(16);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        return t;
    }

    /** 极简 TextView (避免到处 new+set 三行)。 */
    private class TextView2 extends TextView {
        TextView2(String text) {
            super(TimerWakeupActivity.this);
            setText(text);
            setTextSize(12);
            setTextColor(0xFF666666);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String pad2(int v) {
        return String.format("%02d", v);
    }

    private static int parseInt0(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int clampInt(String s, int max, int defIfEmpty) {
        if (s == null || s.isEmpty()) return defIfEmpty;
        try {
            int v = Integer.parseInt(s);
            if (v < 0) return defIfEmpty;
            return Math.min(v, max);
        } catch (Exception e) {
            return defIfEmpty;
        }
    }

    private String taskDoneSum() {
        if (cfg.taskDoneSessions.isEmpty())
            return "未选择触发源 · " + cfg.taskDoneResult.describe();
        if (cfg.taskDoneSessions.contains(TriggerSourcePickerActivity.ALL_SESSIONS))
            return "监听 任意会话 · " + cfg.taskDoneResult.describe();
        return "触发源 " + cfg.taskDoneSessions.size() + " 项 · " + cfg.taskDoneResult.describe();
    }

    private static String joinStr(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(l.get(i));
        }
        return sb.toString();
    }
}
