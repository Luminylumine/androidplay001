package com.akasha.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * WeChat-style home (req 2):
 *  - top bar: title (+ reserved slot for future header/pinned controls)
 *  - content: 4 kept-alive tabs toggled by the bottom nav
 *      聊天  : session list (one row each: bold title + gray last message,
 *              swipe-left -> 删除/置顶/设为未读, scroll for many sessions)
 *      通讯录: all agents (models), tap -> model settings
 *      发现  : blank for now
 *      设置  : global settings (SettingsPanel)
 *  - ＋ (top-right): new chat session
 *
 * Chat detail is a separate ChatActivity (the former main screen, untouched).
 */
public class MainActivity extends Activity {

    private Prefs prefs;
    private SessionStore store;

    private View tabChat, tabContacts, tabDiscover, tabSettings;
    private int activeTab = 0; // 0 chat, 1 contacts, 2 discover, 3 settings

    private ListView lvSessions;
    private TextView tvSessionsEmpty;
    private SessionAdapter sessionAdapter;
    private List<ChatSession> sessions = new ArrayList<>();

    private ListView lvAgents;
    private TextView tvAgentsEmpty;
    private AgentAdapter agentAdapter;
    private List<ModelInfo> agents = new ArrayList<>();

    // 发现 tab = 子目录入口列表（全局经验池等）
    private ListView lvDiscover;
    private DiscoverAdapter discoverAdapter;
    private ExpStore exps; // initialized in onCreate (needs attached context)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        prefs = new Prefs(this);
        store = new SessionStore(this);
        CpLog.init(this); // start file logging before anything else
        CpLog.i("Akasha", "=== app start (home onCreate) ===");
        ShellChannel.init(this);
        TimerEngine.init(this.getApplicationContext());

        // storage access lets the agent read/write /sdcard files directly
        // (works on Android 10 with targetSdk 29 + requestLegacyExternalStorage)
        java.util.List<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 1001);
        }

        BackgroundHelper.apply(this, findViewById(R.id.homeRoot), BackgroundHelper.PAGE_HOME);

        exps = new ExpStore(this);
        // retention: async prune on app entry (req: 每次进入软件时异步排查删除老经验)
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    exps.applyRetention();
                } catch (Exception ignored) {}
            }
        }).start();

        android.widget.FrameLayout fl = (android.widget.FrameLayout) findViewById(R.id.flContent);
        tabChat = getLayoutInflater().inflate(R.layout.view_tab_chat, (ViewGroup) fl, false);
        tabContacts = getLayoutInflater().inflate(R.layout.view_tab_contacts, (ViewGroup) fl, false);
        tabDiscover = getLayoutInflater().inflate(R.layout.view_tab_discover, (ViewGroup) fl, false);
        tabSettings = getLayoutInflater().inflate(R.layout.view_tab_settings, (ViewGroup) fl, false);
        fl.addView(tabChat);
        fl.addView(tabContacts);
        fl.addView(tabDiscover);
        fl.addView(tabSettings);

        lvSessions = (ListView) tabChat.findViewById(R.id.lvSessions);
        tvSessionsEmpty = (TextView) tabChat.findViewById(R.id.tvSessionsEmpty);
        sessionAdapter = new SessionAdapter();
        lvSessions.setAdapter(sessionAdapter);

        lvAgents = (ListView) tabContacts.findViewById(R.id.lvAgents);
        tvAgentsEmpty = (TextView) tabContacts.findViewById(R.id.tvAgentsEmpty);
        agentAdapter = new AgentAdapter();
        lvAgents.setAdapter(agentAdapter);

        lvDiscover = (ListView) tabDiscover.findViewById(R.id.lvDiscover);
        discoverAdapter = new DiscoverAdapter();
        lvDiscover.setAdapter(discoverAdapter);

        // “全局” tab 现在只是一个入口行 → 进入独立设置页
        tabSettings.findViewById(R.id.llSettingsEntry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        // btnNewChatTop click behavior changes per tab (set in switchTab).
        // We still need to attach a default listener; switchTab(0) will overwrite it.
        findViewById(R.id.btnNewChatTop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newChat();
            }
        });

        TextView tab0 = (TextView) findViewById(R.id.tabChat);
        TextView tab1 = (TextView) findViewById(R.id.tabContacts);
        TextView tab2 = (TextView) findViewById(R.id.tabDiscover);
        TextView tab3 = (TextView) findViewById(R.id.tabSettings);
        tab0.setOnClickListener(v -> switchTab(0));
        tab1.setOnClickListener(v -> switchTab(1));
        tab2.setOnClickListener(v -> switchTab(2));
        tab3.setOnClickListener(v -> switchTab(3));
        switchTab(0);
    }

    private void switchTab(int idx) {
        activeTab = idx;
        View[] tabs = {tabChat, tabContacts, tabDiscover, tabSettings};
        for (int i = 0; i < tabs.length; i++) tabs[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        TextView tab0 = (TextView) findViewById(R.id.tabChat);
        TextView tab1 = (TextView) findViewById(R.id.tabContacts);
        TextView tab2 = (TextView) findViewById(R.id.tabDiscover);
        TextView tab3 = (TextView) findViewById(R.id.tabSettings);
        TextView[] nav = {tab0, tab1, tab2, tab3};
        for (int i = 0; i < nav.length; i++) {
            boolean on = i == idx;
            nav[i].setTextColor(on ? 0xFF0A66C2 : 0xFF888888);
            nav[i].setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }

        // ---------------------- top bar per-tab diff (req §1) ----------------------
        updateTopBar(idx);

        if (idx == 0) refreshSessions();
        if (idx == 1) refreshAgents();
        if (idx == 2) refreshDiscover();
    }

    /**
     * 根据当前 tab 更新标题和顶栏 + 号行为。集中收口方便其它页面/resume 里复用。
     */
    private void updateTopBar(int idx) {
        TextView title = (TextView) findViewById(R.id.tvTitle);
        View plus = findViewById(R.id.btnNewChatTop);
        switch (idx) {
            case 0: // 聊天: 标题 "Akasha" + ＋ = 新增会话
                title.setText("Akasha");
                plus.setVisibility(View.VISIBLE);
                plus.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { newChat(); }
                });
                break;
            case 1: // 通讯录: 标题 "通讯录" + ＋ = 新增 Agent
                title.setText("通讯录");
                plus.setVisibility(View.VISIBLE);
                plus.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { addAgentFromContacts(); }
                });
                break;
            case 2: // 发现: 标题 "发现" + ＋ = 新建组经验池
                title.setText("发现");
                plus.setVisibility(View.VISIBLE);
                plus.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { createPool(); }
                });
                break;
            case 3: // 全局(原设置): 标题 "全局" + ＋ 去掉
                title.setText("全局");
                plus.setVisibility(View.GONE);
                plus.setOnClickListener(null);
                break;
        }
    }

    /** 通讯录 ＋ 入口 = 新增 Agent (对齐 SettingsPanel 里的对话框内容但编辑路径统一) */
    private void addAgentFromContacts() {
        Intent i = new Intent(this, ModelSettingsActivity.class);
        i.putExtra(ModelSettingsActivity.EXTRA_NEW_AGENT, true);
        startActivity(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ShellChannel.reset();
        ShellChannel.ensure();
        TimerEngine.init(this.getApplicationContext());
        if (activeTab == 0) refreshSessions();
        if (activeTab == 1) refreshAgents();
        if (activeTab == 2) refreshDiscover();
    }

    // ---------------- sessions ----------------

    private void refreshSessions() {
        sessions = store.listSorted();
        sessionAdapter.notifyDataSetChanged();
        tvSessionsEmpty.setVisibility(sessions.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void newChat() {
        List<ModelInfo> list = prefs.models();
        if (list.isEmpty()) {
            Toast.makeText(this, "先在「设置」里添加模型", Toast.LENGTH_SHORT).show();
            return;
        }
        if (list.size() == 1) {
            openNewSession(list.get(0));
            return;
        }
        final ModelInfo[] arr = list.toArray(new ModelInfo[0]);
        String[] names = new String[arr.length];
        for (int i = 0; i < arr.length; i++) names[i] = arr[i].name;
        new AlertDialog.Builder(this)
                .setTitle("选择 Agent")
                .setItems(names, (d, w) -> openNewSession(arr[w]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void openNewSession(ModelInfo m) {
        ChatSession s = ChatSession.create(m.id, store.uniqueDisplayName(m.name));
        store.save(s);
        refreshSessions();
        openChat(s.id);
    }

    private void openChat(String sessionId) {
        ChatSession s = store.get(sessionId);
        if (s != null && s.unread) {
            s.unread = false;
            store.save(s);
        }
        openChatActivity(sessionId);
    }

    private void openChatActivity(String sessionId) {
        startActivity(new Intent(this, ChatActivity.class).putExtra("sessionId", sessionId));
    }

    private class SessionAdapter extends BaseAdapter {
        /** session id whose swipe-left panel is currently open (at most one). */
        private String openPanelId = null;
        private boolean suppressClick = false;

        @Override
        public int getCount() {
            return sessions.size();
        }

        @Override
        public Object getItem(int position) {
            return sessions.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final ChatSession s = sessions.get(position);
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_session, parent, false);
                bindSwipe(row);
            }
            TextView title = (TextView) row.findViewById(R.id.tvSessionTitle);
            TextView last = (TextView) row.findViewById(R.id.tvSessionLast);
            title.setText((s.unread ? "● " : (s.pinned ? "📌 " : "")) + s.title);
            String prefix = "";
            if ("user".equals(s.lastMsgRole)) prefix = "我: ";
            else if ("agent".equals(s.lastMsgRole)) prefix = "";
            else prefix = "系统: ";
            last.setText((s.lastMsgTime > 0 ? tsOf(s.lastMsgTime) + " " : "") + prefix + s.lastMsg);

            Button pin = (Button) row.findViewById(R.id.btnSessPin);
            pin.setText(s.pinned ? "取消置顶" : "置顶");
            Button unread = (Button) row.findViewById(R.id.btnSessUnread);
            unread.setText(s.unread ? "取消未读" : "设为未读");

            // reset panel state (reuse guard)
            View panel = row.findViewById(R.id.llSessionActions);
            panel.setVisibility(openPanelId != null && openPanelId.equals(s.id) ? View.VISIBLE : View.GONE);
            return row;
        }

        /** The session currently displayed in this (possibly reused) row. */
        private ChatSession sessionAt(View row) {
            int pos = lvSessions.getPositionForView(row);
            if (pos < 0 || pos >= sessions.size()) return null;
            return sessions.get(pos);
        }

        private void bindSwipe(final View row) {
            final float TH = 60; // px threshold, no animation
            row.setOnTouchListener(new View.OnTouchListener() {
                private float startX;
                private boolean openedThisGesture;

                @Override
                public boolean onTouch(View v, MotionEvent ev) {
                    ChatSession s = sessionAt(row);
                    View panel = row.findViewById(R.id.llSessionActions);
                    switch (ev.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startX = ev.getX();
                            openedThisGesture = false;
                            return false;
                        case MotionEvent.ACTION_MOVE:
                            if (openedThisGesture || s == null) return false;
                            float dx = ev.getX() - startX;
                            if (dx < -TH && panel.getVisibility() != View.VISIBLE) {
                                if (openPanelId != null && !openPanelId.equals(s.id)) refreshSessions();
                                openPanelId = s.id;
                                panel.setVisibility(View.VISIBLE);
                                openedThisGesture = true;
                                suppressClick = true;
                            } else if (dx > TH && panel.getVisibility() == View.VISIBLE) {
                                openPanelId = null;
                                panel.setVisibility(View.GONE);
                                openedThisGesture = true;
                                suppressClick = true;
                            }
                            return false;
                        case MotionEvent.ACTION_UP:
                            if (panel.getVisibility() == View.VISIBLE && !openedThisGesture) {
                                // plain tap on content while panel open -> close panel, no navigate
                                openPanelId = null;
                                panel.setVisibility(View.GONE);
                                suppressClick = true;
                            }
                            return false;
                        default:
                            return false;
                    }
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (suppressClick) {
                        suppressClick = false;
                        return;
                    }
                    ChatSession s = sessionAt(row);
                    if (s != null) openChat(s.id);
                }
            });
            row.findViewById(R.id.btnSessDelete).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final ChatSession s = sessionAt(row);
                    if (s == null) return;
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("删除会话")
                            .setMessage("删除会话「" + s.title + "」及其聊天记录？")
                            .setPositiveButton("删除", (d, w) -> {
                                store.remove(s.id);
                                refreshSessions();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                }
            });
            row.findViewById(R.id.btnSessPin).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ChatSession s = sessionAt(row);
                    if (s == null) return;
                    s.pinned = !s.pinned;
                    store.save(s);
                    refreshSessions();
                }
            });
            row.findViewById(R.id.btnSessUnread).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ChatSession s = sessionAt(row);
                    if (s == null) return;
                    s.unread = !s.unread;
                    store.save(s);
                    refreshSessions();
                }
            });
        }

        private String tsOf(long t) {
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(t));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            boolean ok = false;
            for (int g : grantResults) ok |= g == PackageManager.PERMISSION_GRANTED;
            AgentService.log(ok ? "存储权限: 已授予" : "存储权限: 被拒绝（file_* 工具不可用）");
        }
    }

    // ---------------- contacts (agents) ----------------

    private void refreshAgents() {
        java.util.List<ModelInfo> raw = prefs.models();
        // 排序: 置顶(pinned=true)排在前, 其余按原顺序
        agents = new ArrayList<>();
        java.util.List<ModelInfo> tail = new ArrayList<>();
        for (ModelInfo m : raw) {
            if (m.pinned) agents.add(m); else tail.add(m);
        }
        agents.addAll(tail);
        agentAdapter.notifyDataSetChanged();
        tvAgentsEmpty.setVisibility(agents.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private class AgentAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return agents.size();
        }

        @Override
        public Object getItem(int position) {
            return agents.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final ModelInfo m = agents.get(position);
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_agent, parent, false);
                bindAgentLongPress(v);
            }
            // FR8.2 黑体大字展示名（m.name） + 灰色小字模型名 (m.meta())
            TextView name = (TextView) v.findViewById(R.id.tvAgentName);
            name.setText((m.pinned ? "📌 " : "") + (m.name == null || m.name.isEmpty() ? m.id : m.name));
            name.setTextSize(18);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            boolean runningHere = AgentService.running
                    && m.id != null && m.id.equals(AgentService.currentAgentId);
            // 灰色小字：模型 meta
            TextView meta = (TextView) v.findViewById(R.id.tvAgentMeta);
            meta.setText(m.meta());
            meta.setTextSize(13);
            meta.setTextColor(0xFF999999);
            // 运行状态 独立小 label
            TextView run = (TextView) v.findViewById(R.id.tvAgentRun);
            if (run != null) {
                run.setText(runningHere ? "● 运行中" : "○ 未运行");
                run.setTextColor(runningHere ? 0xFF0A66C2 : 0xFF999999);
            }
            v.setTag(R.id.tvAgentName, m.id); // store agentId for long-press handler
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    startActivity(new Intent(MainActivity.this, ModelSettingsActivity.class)
                            .putExtra("agentId", m.id));
                }
            });
            return v;
        }

        private void bindAgentLongPress(final View row) {
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    final String agentId = (String) row.getTag(R.id.tvAgentName);
                    if (agentId == null) return false;
                    ModelInfo target = null;
                    for (ModelInfo mi : agents) {
                        if (agentId.equals(mi.id)) { target = mi; break; }
                    }
                    if (target == null) return false;
                    showAgentActions(target);
                    return true;
                }
            });
        }
    }

    /** 通讯录长按 Agent: 置顶 / 删除 (配色对齐聊天左滑三键) */
    private void showAgentActions(final ModelInfo m) {
        final String PIN = m.pinned ? "取消置顶" : "置顶";
        final String DEL = "删除";
        // 自定义按钮配色: 置顶=#0A66C2白字, 删除=#D9433B白字
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        panel.setPadding(pad, 0, pad, 0);

        Button btnPin = new Button(this);
        btnPin.setText(PIN);
        btnPin.setTextColor(0xFFFFFFFF);
        btnPin.setBackgroundColor(0xFF0A66C2);
        btnPin.setTextSize(15);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (int) (getResources().getDisplayMetrics().density * 44));
        lp.topMargin = (int) (getResources().getDisplayMetrics().density * 12);
        panel.addView(btnPin, lp);

        Button btnDel = new Button(this);
        btnDel.setText(DEL);
        btnDel.setTextColor(0xFFFFFFFF);
        btnDel.setBackgroundColor(0xFFD9433B);
        btnDel.setTextSize(15);
        android.widget.LinearLayout.LayoutParams lp2 =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (int) (getResources().getDisplayMetrics().density * 44));
        lp2.topMargin = (int) (getResources().getDisplayMetrics().density * 10);
        lp2.bottomMargin = (int) (getResources().getDisplayMetrics().density * 12);
        panel.addView(btnDel, lp2);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Agent: " + m.name)
                .setView(panel)
                .setNegativeButton("取消", null)
                .create();

        final ModelInfo fTarget = m;
        btnPin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                java.util.List<ModelInfo> all = prefs.models();
                for (ModelInfo mi : all) {
                    if (fTarget.id.equals(mi.id)) { mi.pinned = !mi.pinned; break; }
                }
                prefs.saveModels(all);
                refreshAgents();
                dlg.dismiss();
            }
        });
        btnDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("删除 Agent")
                        .setMessage("删除 Agent「" + fTarget.name + "」？\n该 Agent 的历史会话仍会保留。")
                        .setPositiveButton("删除", (d, w) -> {
                            java.util.List<ModelInfo> all = prefs.models();
                            java.util.List<ModelInfo> keep = new ArrayList<>();
                            for (ModelInfo mi : all) {
                                if (!fTarget.id.equals(mi.id)) keep.add(mi);
                            }
                            prefs.saveModels(keep);
                            refreshAgents();
                            dlg.dismiss();
                        })
                        .setNegativeButton("取消", (d, w) -> dlg.dismiss())
                        .show();
            }
        });
        dlg.show();
    }

    // ---------------- 发现: 子目录入口列表 (req 1) · 多池版 ----------------

    private java.util.List<PoolInfo> poolsCache = new java.util.ArrayList<>();

    private void refreshDiscover() {
        poolsCache = exps.listPools();
        discoverAdapter.notifyDataSetChanged();
        TextView tvEmpty = (TextView) tabDiscover.findViewById(R.id.tvDiscoverEmpty);
        if (tvEmpty != null) {
            tvEmpty.setVisibility(poolsCache.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private class DiscoverAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return poolsCache.size();
        }

        @Override
        public Object getItem(int position) {
            return poolsCache.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final PoolInfo p = poolsCache.get(position);
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_discover_entry, parent, false);
                v.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View row) {
                        Object o = row.getTag(R.id.tvEntryTitle);
                        if (!(o instanceof String)) return false;
                        PoolInfo pp = findPoolById((String) o);
                        if (pp == null) return false;
                        showPoolActions(pp);
                        return true;
                    }
                });
            }
            v.setTag(R.id.tvEntryTitle, p.id);
            String title = (p.pinned ? "📌 " : "") + (p.enabled ? "" : "❌ ") + p.name;
            ((TextView) v.findViewById(R.id.tvEntryTitle)).setText(title);
            String sub = p.type == PoolInfo.TYPE_GLOBAL
                    ? "全局经验池 · 当前 " + p.expCount + " 条 · 所有 Agent 默认可读写"
                    : (p.enabled ? "组经验池" : "（已禁用）组经验池")
                    + " · 当前 " + p.expCount + " 条";
            ((TextView) v.findViewById(R.id.tvEntrySub)).setText(sub);
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Intent i = new Intent(MainActivity.this, ExperiencePoolActivity.class);
                    i.putExtra(ExperiencePoolActivity.EXTRA_POOL_ID, p.id);
                    i.putExtra(ExperiencePoolActivity.EXTRA_POOL_NAME, p.name);
                    startActivity(i);
                }
            });
            return v;
        }
    }

    private PoolInfo findPoolById(String id) {
        for (PoolInfo p : poolsCache) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /** 发现页长按经验池: 置顶(蓝)/禁用↔启用(黄)/删除(红, GLOBAL 不显示) */
    private void showPoolActions(final PoolInfo p) {
        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        panel.setPadding(pad, 0, pad, 0);

        final String pinTxt = p.pinned ? "取消置顶" : "置顶";
        final String toggleTxt = p.enabled ? "暂时禁用" : "恢复启用";
        final android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (int) (getResources().getDisplayMetrics().density * 44));
        lp.topMargin = (int) (getResources().getDisplayMetrics().density * 12);

        Button btnPin = new Button(this);
        btnPin.setText(pinTxt);
        btnPin.setTextColor(0xFFFFFFFF);
        btnPin.setBackgroundColor(0xFF0A66C2);  // 蓝底白字
        btnPin.setTextSize(15);
        panel.addView(btnPin, lp);

        Button btnToggle = new Button(this);
        btnToggle.setText(toggleTxt);
        btnToggle.setTextColor(0xFF000000);
        btnToggle.setBackgroundColor(0xFFFFD54F);  // 黄底黑字
        btnToggle.setTextSize(15);
        android.widget.LinearLayout.LayoutParams lp2 = new android.widget.LinearLayout.LayoutParams(lp);
        panel.addView(btnToggle, lp2);

        Button btnDel = null;
        if (!p.isGlobal()) {
            btnDel = new Button(this);
            btnDel.setText("删除");
            btnDel.setTextColor(0xFFFFFFFF);
            btnDel.setBackgroundColor(0xFFD9433B);  // 红底白字
            btnDel.setTextSize(15);
            android.widget.LinearLayout.LayoutParams lp3 = new android.widget.LinearLayout.LayoutParams(lp);
            lp3.bottomMargin = (int) (getResources().getDisplayMetrics().density * 12);
            panel.addView(btnDel, lp3);
        }

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("经验池: " + p.name)
                .setView(panel)
                .setNegativeButton("取消", null)
                .create();
        final Button fDel = btnDel;
        btnPin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exps.setPoolPinned(p.id, !p.pinned);
                refreshDiscover();
                dlg.dismiss();
            }
        });
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exps.setPoolEnabled(p.id, !p.enabled);
                refreshDiscover();
                dlg.dismiss();
            }
        });
        if (fDel != null) {
            fDel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("删除经验池")
                            .setMessage("删除组池「" + p.name + "」？\n共享给其他池的经验不会被删，仅在这个池独有的会被一并清理。")
                            .setPositiveButton("删除", (d, w) -> {
                                exps.deletePool(p.id);
                                refreshDiscover();
                                dlg.dismiss();
                            })
                            .setNegativeButton("取消", (d, w) -> dlg.dismiss())
                            .show();
                }
            });
        }
        dlg.show();
    }

    /** 发现页 + 号: 新建组经验池 (输入名, 拒绝空/重复) */
    private void createPool() {
        final EditText input = new EditText(this);
        input.setHint("组经验池名称，如: android-root");
        input.setSingleLine(true);
        int dp8 = (int) (getResources().getDisplayMetrics().density * 16);
        input.setPadding(dp8, dp8, dp8, dp8);
        final AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("新建组经验池")
                .setView(input)
                .setPositiveButton("创建", null)
                .setNegativeButton("取消", null);
        final AlertDialog dlg = b.create();
        dlg.setOnDismissListener(dialog -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        });
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = input.getText() == null ? "" : input.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(MainActivity.this, "请输入池名称", Toast.LENGTH_SHORT).show();
                    return;
                }
                for (PoolInfo pp : poolsCache) {
                    if (name.equals(pp.name)) {
                        Toast.makeText(MainActivity.this, "已存在同名池", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                String id = exps.createPool(name);
                if (id == null) {
                    Toast.makeText(MainActivity.this, "创建失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(MainActivity.this, "已创建", Toast.LENGTH_SHORT).show();
                refreshDiscover();
                dlg.dismiss();
            }
        });
    }
}
