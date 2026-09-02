package com.akasha.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat detail for one session (req 2.5: the former main screen, kept as-is).
 * The top-right gear now opens this session's agent settings (model config +
 * permissions + prompt) instead of the global settings.
 *
 *  - top bar: agent run status + settings gear
 *  - center: chat / log list (the only scrolling area)
 *  - bottom: one input box + 4 action buttons
 *      idle : [▶ 启动并发送] [排队任务] [引导(disabled)]
 *      run  : [■ 停止]      [✋ 打断并发送] [➡ 引导] [＋ 排队]
 *      run+ask : [✔ 回答]   [■ 停止]      [➡ 引导] [＋ 排队]
 */
public class ChatActivity extends Activity {

    private static final int REQ_PROJECTION = 1001;
    private static final int REQ_SESSION_SETTINGS = 1002;

    private Prefs prefs;
    private SessionStore store;
    private String sessionId = null;
    private String agentId = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    private TextView tvStatus;
    private EditText etInput;
    private Button btnA, btnB, btnC, btnD;
    private ListView lvChat;
    private final List<AgentService.ChatMsg> chatItems = new ArrayList<>();
    private ChatAdapter chatAdapter;
    private MediaProjectionManager mpm;

    /** Start request held across the (async) screen-capture permission dialog. */
    private String pendingGoal = null;
    private boolean pendingInterrupt = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        store = new SessionStore(this);
        mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        BackgroundHelper.apply(this, findViewById(android.R.id.content), BackgroundHelper.PAGE_CHAT);

        sessionId = getIntent().getStringExtra("sessionId");
        ChatSession s = sessionId != null ? store.get(sessionId) : null;
        agentId = s != null ? s.agentId : null;
        if (s != null) {
            s.unread = false;
            store.save(s);
        }

        tvStatus = (TextView) findViewById(R.id.tvStatus);
        etInput = (EditText) findViewById(R.id.etInput);
        btnA = (Button) findViewById(R.id.btnA);
        btnB = (Button) findViewById(R.id.btnB);
        btnC = (Button) findViewById(R.id.btnC);
        btnD = (Button) findViewById(R.id.btnD);
        lvChat = (ListView) findViewById(R.id.lvChat);
        chatAdapter = new ChatAdapter();
        lvChat.setAdapter(chatAdapter);

        // gear -> per-session settings (FR-6). 只有会话页入口.
        findViewById(R.id.btnSettingsTop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sessionId == null) return;
                Intent i = new Intent(ChatActivity.this, SessionSettingsActivity.class);
                i.putExtra(SessionSettingsActivity.EXTRA_SESSION_ID, sessionId);
                startActivityForResult(i, REQ_SESSION_SETTINGS);
            }
        });

        btnA.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = input();
                if (AgentService.isSessionRunning(sessionId) && AgentService.sessionQuestion(sessionId) != null) {
                    // replying to the agent's question
                    if (text.isEmpty()) {
                        text = "(用户选择跳过此提问，请自行决定安全合理的做法)";
                    }
                    answer(text);
                } else if (AgentService.isSessionRunning(sessionId)) {
                    stopAgent();
                } else {
                    String goal = text.isEmpty() ? defaultGoal() : text;
                    startAgent(goal, false);
                }
            }
        });

        btnB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = input();
                if (AgentService.isSessionRunning(sessionId)) {
                    if (text.isEmpty()) {
                        Toast.makeText(ChatActivity.this, "输入新任务内容再打断", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startAgent(text, true);
                } else {
                    if (text.isEmpty()) {
                        Toast.makeText(ChatActivity.this, "输入要排队的任务", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!AgentService.enqueueTaskForSession(sessionId, text)) {
                        Toast.makeText(ChatActivity.this, "Agent 未运行，无法排队", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        btnC.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = input();
                if (!AgentService.isSessionRunning(sessionId)) {
                    Toast.makeText(ChatActivity.this, "Agent 未运行，无任务可引导", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (text.isEmpty()) {
                    Toast.makeText(ChatActivity.this, "输入引导内容", Toast.LENGTH_SHORT).show();
                    return;
                }
                AgentService.addGuideToSession(text, sessionId);
            }
        });

        btnD.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = input();
                if (text.isEmpty()) {
                    Toast.makeText(ChatActivity.this, "输入要排队的任务", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!AgentService.enqueueTaskForSession(sessionId, text)) {
                    Toast.makeText(ChatActivity.this, "Agent 未运行，无法排队", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private String input() {
        return etInput.getText().toString().trim();
    }

    /** A typed goal is one task only. Defaults are explicitly configured by scope. */
    private String defaultGoal() {
        ChatSession session = sessionId == null ? null : store.get(sessionId);
        if (session != null && session.defaultGoal != null && !session.defaultGoal.trim().isEmpty()) {
            return session.defaultGoal.trim();
        }
        ModelInfo agent = prefs.effectiveModel(agentId);
        if (agent != null && agent.defaultGoal != null && !agent.defaultGoal.trim().isEmpty()) {
            return agent.defaultGoal.trim();
        }
        return prefs.goal();
    }

    private void clearInput() {
        etInput.setText("");
    }

    @Override
    protected void onResume() {
        super.onResume();
        ShellChannel.reset();
        ShellChannel.ensure();
        refresh();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    private void refresh() {
        boolean run = AgentService.isSessionRunning(sessionId);
        // 顶栏太窄：只显示名字 + 是否运行（通道状态移入「全局→设置」）
        StringBuilder sb = new StringBuilder();
        if (s_title() != null) sb.append(s_title()).append("  ");
        if (run) sb.append("● 运行中");
        else sb.append("○ 未运行");
        tvStatus.setText(sb.toString());

        // buttons
        if (run && AgentService.sessionQuestion(sessionId) != null) {
            btnA.setText("✔ 回答");
            btnB.setText("■ 停止");
            btnC.setText("➡ 引导");
            btnD.setText("＋ 排队");
            btnD.setVisibility(View.VISIBLE);
            btnC.setEnabled(true);
        } else if (run) {
            btnA.setText("■ 停止");
            btnB.setText("✋ 打断并发送");
            btnC.setText("➡ 引导");
            btnD.setText("＋ 排队");
            btnD.setVisibility(View.VISIBLE);
            btnC.setEnabled(true);
        } else {
            btnA.setText("▶ 启动并发送");
            btnB.setText("排队任务");
            btnC.setText("引导(需运行中)");
            btnD.setVisibility(View.GONE);
            btnC.setEnabled(false);
        }

        List<AgentService.ChatMsg> snapshot;
        boolean live = AgentService.isSessionRunning(sessionId);
        if (live) {
            snapshot = new ArrayList<>(AgentService.sessionChat(sessionId));
        } else {
            // persisted history of this session (independent context, req 2.2)
            snapshot = new ArrayList<>();
            if (sessionId != null) {
                for (SessionStore.Line ln : store.loadChat(sessionId)) {
                    snapshot.add(new AgentService.ChatMsg(ln.type, ln.text, ln.meta));
                }
            }
        }
        chatItems.clear();
        chatItems.addAll(snapshot);
        chatAdapter.notifyDataSetChanged();
    }

    private String s_title() {
        if (sessionId == null) return null;
        ChatSession s = store.get(sessionId);
        return s != null ? s.title : null;
    }

    private void answer(String text) {
        startService(new Intent(this, AgentService.class)
                .setAction(AgentService.ACTION_ANSWER).putExtra("text", text).putExtra("sessionId", sessionId));
        clearInput();
    }

    private void stopAgent() {
        startService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_STOP)
                .putExtra("sessionId", sessionId));
    }

    private void startAgent(String goal, boolean interrupt) {
        if (!ControlService.ready()) {
            if (ControlService.enableWithShizuku()) {
                Toast.makeText(this, "已通过 Shizuku 自动授予无障碍，正在连接…", Toast.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        if (ControlService.ready()) startAgent(goal, interrupt);
                        else showAccessibilitySettings();
                    }
                }, 1500);
                return;
            }
            showAccessibilitySettings();
            return;
        }
        if (TextUtils.isEmpty(prefs.agentApiKey(agentId))) {
            new AlertDialog.Builder(this)
                    .setTitle("未配置 API Key")
                    .setMessage("请先在通讯录中打开该 Agent，填写 API Key。")
                    .show();
            return;
        }
        if (TextUtils.isEmpty(goal)) {
            new AlertDialog.Builder(this)
                    .setTitle("任务目标为空")
                    .setMessage("没有任务目标时 Agent 只会观察屏幕（输出 wait）。确定继续？")
                    .setPositiveButton("继续", (d, w) -> prepareStart("", interrupt))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        prepareStart(goal, interrupt);
    }

    private void showAccessibilitySettings() {
        if (!ControlService.ready()) {
            new AlertDialog.Builder(this)
                    .setTitle("无障碍服务未启用")
                    .setMessage("Shizuku 自动授权不可用或系统尚未绑定。需要开启 Akasha 的无障碍服务才能执行点击/滑动/输入。现在去设置吗？")
                    .setPositiveButton("去设置", (d, w) ->
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    private void prepareStart(final String goal, final boolean interrupt) {
        if (!ScreenShotService.active()) {
            // permission dialog is async; remember the pending request
            pendingGoal = goal;
            pendingInterrupt = interrupt;
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        } else {
            beginRun(goal, interrupt);
        }
    }

    private void beginRun(String goal, boolean interrupt) {
        Intent i = new Intent(this, AgentService.class);
        if (interrupt) {
            i.setAction(AgentService.ACTION_INT).putExtra("text", goal).putExtra("keep", true);
        } else {
            i.setAction(AgentService.ACTION_RUN_GOAL).putExtra("text", goal);
        }
        if (sessionId != null) i.putExtra("sessionId", sessionId);
        if (agentId != null) i.putExtra("agentId", agentId);
        startService(i);
        clearInput();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            CpLog.i("Akasha", "onActivityResult rc=" + resultCode
                    + " data=" + (data != null ? data.getData() : "null"));
            if (resultCode == Activity.RESULT_OK && data != null) {
                Intent i = new Intent(this, ScreenShotService.class)
                        .setAction(ScreenShotService.ACTION_GRAB)
                        .setData(data.getData())
                        .putExtra("consent", data);
                startService(i);
                String g = pendingGoal == null ? "" : pendingGoal;
                boolean it = pendingInterrupt;
                pendingGoal = null;
                pendingInterrupt = false;
                beginRun(g, it);
            } else {
                AgentService.log("屏幕捕获授权被拒绝 — 无法运行 Agent");
            }
        }
        if (requestCode == REQ_SESSION_SETTINGS && resultCode == RESULT_OK) {
            refresh();
        }
    }

    private class ChatAdapter extends android.widget.BaseAdapter {
        @Override
        public int getCount() {
            return chatItems.size();
        }

        @Override
        public Object getItem(int position) {
            return chatItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_chat_msg, parent, false);
            }
            final AgentService.ChatMsg m = chatItems.get(position);
            final TextView tv = (TextView) v.findViewById(R.id.tvMsg);
            View spL = v.findViewById(R.id.spLeft);
            View spR = v.findViewById(R.id.spRight);
            tv.setText(m.text);
            // long-press a system line with detail -> full-screen log detail (req 5)
            tv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View vv) {
                    if (AgentService.ChatMsg.SYSTEM.equals(m.type) && m.meta != null) {
                        startActivity(new Intent(ChatActivity.this, LogDetailActivity.class)
                                .putExtra("text", m.text)
                                .putExtra("meta", m.meta)
                                .putExtra("sessionId", sessionId));
                        return true;
                    }
                    return false;
                }
            });
            if (AgentService.ChatMsg.USER.equals(m.type)) {
                setWeight(spL, 1f);
                setWeight(spR, 0.01f);
                tv.setBackgroundColor(0xFF0A66C2);
                tv.setTextColor(0xFFFFFFFF);
                tv.setGravity(android.view.Gravity.END | android.view.Gravity.TOP);
                tv.setTextSize(13);
                tv.setTypeface(null, android.graphics.Typeface.NORMAL);
            } else if (AgentService.ChatMsg.AGENT.equals(m.type)) {
                setWeight(spL, 0.01f);
                setWeight(spR, 1f);
                tv.setBackgroundColor(0xFFE8F0FE);
                tv.setTextColor(0xFF222222);
                tv.setGravity(android.view.Gravity.START | android.view.Gravity.TOP);
                tv.setTextSize(13);
                tv.setTypeface(null, android.graphics.Typeface.NORMAL);
            } else {
                setWeight(spL, 0.5f);
                setWeight(spR, 0.5f);
                tv.setBackgroundColor(0xFFE9E9E9);
                tv.setTextColor(0xFF555555);
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setTextSize(11);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL);
            }
            return v;
        }

        private void setWeight(View view, float w) {
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) view.getLayoutParams();
            lp.weight = w;
            view.setLayoutParams(lp);
        }
    }
}
