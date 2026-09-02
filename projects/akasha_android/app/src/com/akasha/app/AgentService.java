package com.akasha.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Foreground service running the autonomous screen-agent loop:
 * screenshot -> vision LLM -> action JSON -> execute -> repeat.
 *
 * Message modes from the UI:
 *  - RUN / RUN_GOAL : (re)start with a goal (fresh context)
 *  - INT            : interrupt the current run, start new task (optionally keep history)
 *  - guide          : steer the running task without interrupting (injected each round)
 *  - queueTask      : run as next task after the current one finishes
 *
 * Model/API settings are read at task start only, so changing them while a
 * task runs takes effect on the next start/interrupt, never mid-round.
 */
public class AgentService extends Service {

    public static final String ACTION_RUN = "com.akasha.app.RUN";
    public static final String ACTION_RUN_GOAL = "com.akasha.app.RUN_GOAL";
    public static final String ACTION_INT = "com.akasha.app.INT";
    public static final String ACTION_STOP = "com.akasha.app.STOP";
    public static final String ACTION_ANSWER = "com.akasha.app.ANSWER";
    public static final String ACTION_GUIDE = "com.akasha.app.ACTION_GUIDE";
    public static final String ACTION_QUEUE = "com.akasha.app.QUEUE";

    public static final LinkedList<String> LOG = new LinkedList<>();
    /** Legacy last-task fields. They are not worker context; use the query APIs below. */
    @Deprecated public static volatile boolean running = false;
    @Deprecated public static volatile String currentQuestion = null;
    /** Goal of the task the current loop is executing. */
    @Deprecated public static volatile String currentGoal = "";
    /** Session the current/last task belongs to (chat history is per-session). */
    @Deprecated public static volatile String currentSessionId = null;
    /** Agent (model) of the current/last task. */
    @Deprecated public static volatile String currentAgentId = null;

    /**
     * Per-agent recent (assistant, user-observation) pairs. Keyed by agentId.
     * All sessions of the same agent share the same HISTORY window (FR-3.1).
     * Old callers still access HISTORY for backwards-compat — code inside this
     * class MUST use agentHistory(currentAgentId) instead.
     */
    @Deprecated
    public static final LinkedList<String[]> HISTORY = new LinkedList<>();

    /** Retained only for source compatibility. Active workers keep history per session. */
    private static final java.util.Map<String, LinkedList<String[]>> AGENT_HISTORY =
            new java.util.HashMap<>();

    /** Accessor: get or create the HISTORY window for a given agent. */
    static LinkedList<String[]> agentHistory(String agentId) {
        String key = agentId == null ? "__none__" : agentId;
        synchronized (AGENT_HISTORY) {
            LinkedList<String[]> h = AGENT_HISTORY.get(key);
            if (h == null) { h = new LinkedList<>(); AGENT_HISTORY.put(key, h); }
            return h;
        }
    }

    private static final Map<String, Task> TASKS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Task> WORKER_TASK = new ThreadLocal<>();
    private static final AtomicInteger NEXT_TASK_ID = new AtomicInteger();
    private static final int MAX_OBSERVATION_CHARS = 10000;
    private static final int MAX_HISTORY_ENTRY_CHARS = 6000;
    private static final int MAX_HISTORY_CHARS = 24000;
    private static final int MAX_APP_LIST_CHARS = 3000;
    private static final Object DEVICE_LOCK = new Object();
    /** The sole task allowed to observe or operate the physical device. */
    private static Task deviceOwner;
    /** Static context for use in static helper methods. */
    private static Context staticCtx = null;

    /** One line of the conversation view: system log / user instruction / agent reply. */
    public static class ChatMsg {
        public static final String SYSTEM = "system";
        public static final String USER = "user";
        public static final String AGENT = "agent";
        public final String type;
        public final String text;
        /** Optional JSON detail for system lines: {raw,tool,err,hint,kind}.
         *  Long-press opens the full-screen log detail (req 5). */
        public final String meta;

        public ChatMsg(String type, String text) {
            this(type, text, null);
        }

        public ChatMsg(String type, String text, String meta) {
            this.type = type;
            this.text = text;
            this.meta = meta;
        }
    }

    /** Legacy global chat view. Active chat is available through sessionChat(sessionId). */
    @Deprecated public static final LinkedList<ChatMsg> CHAT = new LinkedList<>();

    private static final class Task {
        final String sessionId;
        final String agentId;
        final String goal;
        final AgentConfig config;
        final ModelInfo profile;
        final String sessionPrompt;
        final int intervalMs;
        final int maxTokens;
        final int maxRounds;
        final String appList;
        final boolean suppressTaskDoneTrigger;
        final LinkedList<String[]> history = new LinkedList<>();
        final LinkedList<ChatMsg> chat = new LinkedList<>();
        final LinkedList<String> guides = new LinkedList<>();
        final LinkedList<String> queuedGoals = new LinkedList<>();
        final LinkedList<Boolean> queuedSuppressTaskDone = new LinkedList<>();
        final ArrayDeque<byte[]> shots = new ArrayDeque<>();
        final Object answerLock = new Object();
        volatile boolean stopped;
        volatile boolean wantLook;
        volatile String question;
        volatile String pendingAnswer;
        volatile Thread workerThread;

        Task(String sessionId, String agentId, String goal, AgentConfig config, ModelInfo profile,
                String sessionPrompt, int intervalMs, int maxTokens, int maxRounds, String appList,
                boolean suppressTaskDoneTrigger) {
            this.sessionId = sessionId;
            this.agentId = agentId;
            this.goal = goal;
            this.config = config;
            this.profile = profile;
            this.sessionPrompt = sessionPrompt;
            this.intervalMs = intervalMs;
            this.maxTokens = maxTokens;
            this.maxRounds = maxRounds;
            this.appList = appList;
            this.suppressTaskDoneTrigger = suppressTaskDoneTrigger;
        }
    }

    public static boolean isSessionRunning(String sessionId) {
        return sessionId != null && TASKS.containsKey(sessionId);
    }

    public static String sessionQuestion(String sessionId) {
        Task t = sessionId == null ? null : TASKS.get(sessionId);
        return t == null ? null : t.question;
    }

    public static List<ChatMsg> sessionChat(String sessionId) {
        Task t = sessionId == null ? null : TASKS.get(sessionId);
        if (t == null) return Collections.emptyList();
        synchronized (t.chat) { return new ArrayList<>(t.chat); }
    }

    public static boolean isAgentRunning(String agentId) {
        if (agentId == null) return false;
        for (Task t : TASKS.values()) if (agentId.equals(t.agentId)) return true;
        return false;
    }

    private static String deviceOwnerDescription(Task requester) {
        synchronized (DEVICE_LOCK) {
            if (deviceOwner == null || deviceOwner == requester) return null;
            String name = deviceOwner.profile == null || deviceOwner.profile.name == null
                    ? deviceOwner.agentId : deviceOwner.profile.name;
            return name + " / " + deviceOwner.sessionId;
        }
    }

    private static boolean ownsDevice(Task task) {
        synchronized (DEVICE_LOCK) { return deviceOwner == task; }
    }

    private static AgentToolResult acquireDevice(Task task) {
        synchronized (DEVICE_LOCK) {
            if (deviceOwner == null || deviceOwner == task) {
                deviceOwner = task;
                return AgentToolResult.ok("已获得手机操作锁；请完成当前手机操作后 release_device");
            }
            return AgentToolResult.err(AgentErrorCodes.DEVICE_LOCK_HELD,
                    deviceOwnerDescription(task));
        }
    }

    private static void releaseDevice(Task task) {
        synchronized (DEVICE_LOCK) {
            if (deviceOwner == task) deviceOwner = null;
        }
    }

    public static void stopSession(String sessionId) {
        if (sessionId == null) {
            for (String id : new ArrayList<>(TASKS.keySet())) stopSession(id);
            return;
        }
        Task t = sessionId == null ? null : TASKS.get(sessionId);
        if (t != null) {
            t.stopped = true;
            t.question = null;
            // Keep the lock until the worker's finally block. The current shell
            // operation may still be running after interrupt is requested.
            Thread thread = t.workerThread;
            if (thread != null) thread.interrupt();
            synchronized (t.answerLock) { t.answerLock.notifyAll(); }
        }
    }

    public static void answerSession(String sessionId, String text) {
        Task t = sessionId == null ? null : TASKS.get(sessionId);
        if (t != null) {
            t.pendingAnswer = text;
            synchronized (t.answerLock) { t.answerLock.notifyAll(); }
        }
    }

    /** Appends a system line to exactly one session, whether or not it is running. */
    public static void logToSession(String sessionId, String text) {
        Task t = sessionId == null ? null : TASKS.get(sessionId);
        if (t != null) taskLog(t, text, null);
        else if (chatStore != null && sessionId != null)
            chatStore.appendChat(sessionId, ChatMsg.SYSTEM, text, System.currentTimeMillis(), null);
    }

    /** Steering note: applies to the currently running task from the next round on. */
    public static void addGuide(String text) {
        Task t = WORKER_TASK.get();
        if (t != null) addGuideToSession(text, t.sessionId);
    }

    /** Steering note: sends a guide to a specific session (for session-level timer triggers). */
    public static void addGuideToSession(String text, String sessionId) {
        if (text == null || text.isEmpty()) return;
        Task task = sessionId == null ? null : TASKS.get(sessionId);
        if (task != null) {
            synchronized (task.guides) {
                task.guides.addLast(text);
                while (task.guides.size() > 20) task.guides.removeFirst();
            }
            taskLog(task, "已加入引导: " + text, null);
            return;
        }
        if (chatStore != null && sessionId != null) {
            chatStore.appendNote(sessionId, text);
        }
        if (task == null) {
            Context c = staticCtx;
            if (c != null && sessionId != null) {
                String aid = null;
                try {
                    ChatSession s = new SessionStore(c).get(sessionId);
                    if (s != null) aid = s.agentId;
                } catch (Exception ignored) {}
                if (aid != null && !aid.isEmpty()) {
                    try {
                        Intent i = new Intent(c, AgentService.class).setAction(ACTION_GUIDE);
                        i.putExtra("text", text);
                        i.putExtra("sessionId", sessionId);
                        i.putExtra("agentId", aid);
                        c.startService(i);
                    } catch (Throwable t) {
                        CpLog.e("Akasha", "addGuideToSession startSvc: " + t);
                    }
                }
            }
        }
    }

    public static void enqueueTask(String text) {
        Task t = WORKER_TASK.get();
        if (t != null) enqueueTaskForSession(t.sessionId, text);
    }

    /** Queue a follow-up only behind the task in this same session. */
    public static boolean enqueueTaskForSession(String sessionId, String text) {
        return enqueueTaskForSession(sessionId, text, false);
    }

    private static boolean enqueueTaskForSession(String sessionId, String text,
                                                 boolean suppressTaskDoneTrigger) {
        if (text == null || text.trim().isEmpty()) return false;
        Task task = sessionId == null ? null : TASKS.get(sessionId);
        if (task == null) return false;
        synchronized (task.queuedGoals) {
            task.queuedGoals.addLast(text.trim());
            task.queuedSuppressTaskDone.addLast(suppressTaskDoneTrigger);
        }
        taskLog(task, "已排队: " + text.trim(), null);
        return true;
    }

    /** When true, the next round's user message carries a screenshot. */
    /** Per-session chat persistence (set in onCreate). */
    private static SessionStore chatStore = null;
    /** Permission/prompt snapshot of the running task's agent (req 6/7). */

    public static void log(String s) {
        log(s, null);
    }

    /** System line with optional detail payload (long-press -> log detail screen). */
    public static void log(String s, String meta) {
        synchronized (LOG) {
            LOG.addLast("[" + ts() + "] " + s);
            while (LOG.size() > 300) LOG.removeFirst();
        }
        CpLog.i("Akasha", s);
        chat(ChatMsg.SYSTEM, s, meta);
    }

    public static void chat(String type, String text) {
        chat(type, text, null);
    }

    public static void chat(String type, String text, String meta) {
        Task task = WORKER_TASK.get();
        if (task != null) {
            taskChat(task, type, text, meta);
            return;
        }
        synchronized (CHAT) {
            CHAT.addLast(new ChatMsg(type, text, meta));
            while (CHAT.size() > 200) CHAT.removeFirst();
        }
        // Calls made outside a worker are process diagnostics only. They must never be
        // attributed to whichever session happened to start most recently.
    }

    private static void taskChat(Task task, String type, String text, String meta) {
        if (TASKS.get(task.sessionId) != task) return;
        synchronized (task.chat) {
            task.chat.addLast(new ChatMsg(type, text, meta));
            while (task.chat.size() > 200) task.chat.removeFirst();
        }
        if (chatStore != null) chatStore.appendChat(task.sessionId, type, text, System.currentTimeMillis(), meta);
    }

    private static void taskLog(Task task, String text, String meta) {
        synchronized (LOG) {
            LOG.addLast("[" + ts() + "] " + text);
            while (LOG.size() > 300) LOG.removeFirst();
        }
        CpLog.i("Akasha", text);
        taskChat(task, ChatMsg.SYSTEM, text, meta);
    }

    private static String ts() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        staticCtx = getApplicationContext();
        CpLog.init(this); // idempotent; needed if service starts without MainActivity (boot)
        CpLog.i("Akasha", "=== AgentService onCreate ===");
        ShellChannel.init(this);
        ShellChannel.ensure();
        TimerEngine.init(this.getApplicationContext()); // 闹钟/事件唤醒(开机自启场景也要生效)
        chatStore = new SessionStore(this);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel("agent",
                "Akasha Agent", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        NotificationChannel msg = new NotificationChannel("agent_msg",
                "Agent 消息", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(msg);
        // foreground service so the 24h agent survives backgrounding
        startForeground(1, buildNotif("Akasha", "Agent 待命"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent != null ? intent.getAction() : null;
        if (act == null) act = ACTION_RUN;
        if (ACTION_STOP.equals(act)) {
            stopSession(intent.getStringExtra("sessionId"));
            return START_NOT_STICKY;
        }
        if (ACTION_ANSWER.equals(act)) {
            answerSession(intent.getStringExtra("sessionId"), intent.getStringExtra("text"));
            return START_NOT_STICKY;
        } else if (ACTION_GUIDE.equals(act)) {
            String text = intent.getStringExtra("text");
            String sid = intent.getStringExtra("sessionId");
            String aid = intent.getStringExtra("agentId");
            if (sid != null && !sid.isEmpty()) {
                ChatSession s = new SessionStore(this).get(sid);
                if (s != null && (aid == null || aid.isEmpty())) aid = s.agentId;
            }
            if (text != null && !text.isEmpty()) {
                addGuideToSession(text, sid);
            }
            if (aid != null && !aid.isEmpty()) {
                // A guide is context, never a replacement task goal.
                if (!isSessionRunning(sid)) startTask("", false, sid, aid,
                        intent.getBooleanExtra("suppressTaskDone", false));
            }
            return START_NOT_STICKY;
        } else if (ACTION_QUEUE.equals(act)) {
            String sid = intent.getStringExtra("sessionId");
            String text = intent.getStringExtra("text");
            if (!enqueueTaskForSession(sid, text,
                    intent.getBooleanExtra("suppressTaskDone", false))) {
                startTask(text, false, sid, intent.getStringExtra("agentId"),
                        intent.getBooleanExtra("suppressTaskDone", false));
            }
            return START_NOT_STICKY;
        }
        if (ACTION_RUN.equals(act)) {
            Prefs p = new Prefs(this);
            startTask("", false, resolveBootSession(p), null);
        } else if (ACTION_RUN_GOAL.equals(act)) {
            startTask(intent.getStringExtra("text"), false,
                    intent.getStringExtra("sessionId"), intent.getStringExtra("agentId"),
                    intent.getBooleanExtra("suppressTaskDone", false));
        } else if (ACTION_INT.equals(act)) {
            startTask(intent.getStringExtra("text"), intent.getBooleanExtra("keep", false),
                    intent.getStringExtra("sessionId"), intent.getStringExtra("agentId"),
                    intent.getBooleanExtra("suppressTaskDone", false));
        }
        return START_NOT_STICKY;
    }

    /** Boot/auto-start has no UI session: reuse the most recent, else create one. */
    private String resolveBootSession(Prefs p) {
        SessionStore st = chatStore != null ? chatStore : new SessionStore(this);
        List<ChatSession> l = st.listSorted();
        if (!l.isEmpty()) return l.get(0).id;
        String defAgent = p.model();
        String defName = defAgent;
        for (ModelInfo m : p.models()) {
            if (m.id.equals(defAgent)) {
                defName = m.name;
                break;
            }
        }
        ChatSession s = ChatSession.create(defAgent, st.uniqueDisplayName(defName));
        st.save(s);
        return s.id;
    }

    /** Starts an independent task. A new task only replaces an existing task in this session. */
    private void startTask(String goal, boolean keepHistory, String sessionId, String agentId) {
        startTask(goal, keepHistory, sessionId, agentId, false);
    }

    private void startTask(String goal, boolean keepHistory, String sessionId, String agentId,
                           boolean suppressTaskDoneTrigger) {
        if (goal == null) goal = "";
        if (sessionId == null) sessionId = resolveBootSession(new Prefs(this));
        if (agentId == null && sessionId != null && chatStore != null) {
            ChatSession s = chatStore.get(sessionId);
            if (s != null) agentId = s.agentId;
        }
        if (agentId == null) agentId = new Prefs(this).model();
        if (goal.trim().isEmpty()) goal = resolveDefaultGoal(sessionId, agentId);
        stopSession(sessionId);
        ModelInfo profile = snapshotProfile(findProfile(agentId));
        if (profile == null) {
            logToSession(sessionId, "任务无法启动：Agent 不存在或已被删除 (" + agentId + ")");
            return;
        }
        Prefs prefs = new Prefs(this);
        int maxRounds = Math.max(0, prefs.historyRounds());
        String sessionPrompt = null;
        // Snapshot every task input before its worker starts.
        if (chatStore != null && sessionId != null) {
            ChatSession cs = chatStore.get(sessionId);
            if (cs != null) sessionPrompt = cs.customPrompt;
        }
        AgentConfig cfg = AgentConfig.resolve(this, profile);
        final Task task = new Task(sessionId, agentId, goal, cfg, profile, sessionPrompt,
                Math.max(1000, prefs.intervalMs()),
                Math.max(256, Math.min(prefs.maxTokens(), profile.maxOut > 0 ? profile.maxOut : prefs.maxTokens())),
                maxRounds, buildAppList(), suppressTaskDoneTrigger);
        if (chatStore != null) {
            for (String n : chatStore.consumeNotes(sessionId)) {
                task.guides.addFirst(n);
            }
        }
        // A fresh goal starts with a clean working context; explicit continuation keeps the tail.
        if (keepHistory) rebuildSessionHistory(sessionId, task.history, maxRounds);
        Task old = TASKS.put(sessionId, task);
        if (old != null) old.stopped = true;
        running = !TASKS.isEmpty();
        Thread worker = new Thread(() -> loop(task), "agent-loop-" + NEXT_TASK_ID.incrementAndGet());
        task.workerThread = worker;
        worker.start();
    }

    /** FR-3.2 半满批删 + 统一 agentHistory 写入入口。 */
    static void pushHistory(String agentId, int maxRounds, String assistantContent, String userObs) {
        LinkedList<String[]> hq = agentHistory(agentId);
        synchronized (hq) {
            if (hq.size() >= Math.max(2, maxRounds)) {
                int drop = Math.max(1, hq.size() / 2); // 半满截断
                for (int i = 0; i < drop; i++) hq.removeFirst();
                CpLog.d("HISTORY", "agent=" + agentId + " truncated first=" + drop
                        + " size=" + hq.size());
            }
            hq.addLast(new String[]{
                    assistantContent == null ? "" : assistantContent,
                    userObs == null ? "" : userObs
            });
        }
    }

    /** Resolve the configured default without ever borrowing another session's active goal. */
    private String resolveDefaultGoal(String sessionId, String agentId) {
        if (chatStore != null && sessionId != null) {
            ChatSession s = chatStore.get(sessionId);
            if (s != null && s.defaultGoal != null && !s.defaultGoal.trim().isEmpty()) {
                return s.defaultGoal.trim();
            }
        }
        ModelInfo model = findProfile(agentId);
        if (model != null && model.defaultGoal != null && !model.defaultGoal.trim().isEmpty()) {
            return model.defaultGoal.trim();
        }
        return new Prefs(this).goal();
    }

    private static void pushHistory(Task task, int maxRounds, String assistantContent, String userObs) {
        synchronized (task.history) {
            if (task.history.size() >= Math.max(2, maxRounds)) {
                int drop = Math.max(1, task.history.size() / 2);
                for (int i = 0; i < drop; i++) task.history.removeFirst();
            }
            task.history.addLast(new String[]{clip(assistantContent, MAX_HISTORY_ENTRY_CHARS),
                    clip(userObs, MAX_HISTORY_ENTRY_CHARS)});
            int chars = 0;
            for (String[] pair : task.history) chars += pair[0].length() + pair[1].length();
            while (chars > MAX_HISTORY_CHARS && !task.history.isEmpty()) {
                String[] old = task.history.removeFirst();
                chars -= old[0].length() + old[1].length();
            }
        }
        if (chatStore != null && TASKS.get(task.sessionId) == task) {
            chatStore.appendContext(task.sessionId, clip(assistantContent, MAX_HISTORY_ENTRY_CHARS),
                    clip(userObs, MAX_HISTORY_ENTRY_CHARS), System.currentTimeMillis());
        }
    }

    /** Rebuild only this session's assistant/observation pairs. */
    private void rebuildSessionHistory(String sessionId, LinkedList<String[]> out, int maxRounds) {
        if (sessionId == null || chatStore == null) return;
        try {
            List<SessionStore.ContextLine> context = chatStore.loadContext(sessionId);
            if (!context.isEmpty()) {
                int from = Math.max(0, context.size() - Math.max(1, maxRounds));
                for (int i = from; i < context.size(); i++) {
                    SessionStore.ContextLine line = context.get(i);
                    out.addLast(new String[]{clip(line.assistant, MAX_HISTORY_ENTRY_CHARS),
                            clip(line.user, MAX_HISTORY_ENTRY_CHARS)});
                }
                return;
            }
            String lastAgent = null;
            for (SessionStore.Line ln : chatStore.loadChat(sessionId)) {
                if (ln == null) continue;
                if ("agent".equals(ln.type)) lastAgent = ln.text;
                else if ("user".equals(ln.type) && lastAgent != null) {
                    out.addLast(new String[]{lastAgent, ln.text});
                    lastAgent = null;
                }
            }
            synchronized (out) {
                while (out.size() > maxRounds) out.removeFirst();
            }
        } catch (Exception e) {
            CpLog.w("HISTORY", "session rebuild fail: " + e);
        }
    }

    private static String sanitizeId(String s) {
        return s == null ? "x" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private ModelInfo findProfile(String agentId) {
        if (agentId == null) return null;
        for (ModelInfo m : new Prefs(this).models()) {
            if (m.id.equals(agentId)) return m;
        }
        return null;
    }

    private static ModelInfo snapshotProfile(ModelInfo source) {
        if (source == null) return null;
        ModelInfo copy = new ModelInfo(source.id, source.name, source.vision, source.ctxIn, source.maxOut);
        copy.permShell = source.permShell; copy.permA11y = source.permA11y; copy.permFile = source.permFile;
        copy.permMedia = source.permMedia; copy.permPhoto = source.permPhoto; copy.permMusic = source.permMusic;
        copy.permExpRead = source.permExpRead; copy.permExpWrite = source.permExpWrite;
        copy.pinned = source.pinned; copy.baseUrl = source.baseUrl; copy.apiKey = source.apiKey;
        copy.autoStart = source.autoStart; copy.customPrompt = source.customPrompt;
        copy.defaultGoal = source.defaultGoal;
        return copy;
    }

    /** The foreground notification is process-wide; task notifications target sessions directly. */
    private PendingIntent openSessionPi() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 3, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Foreground/app notification: current operation (全局系统操作走应用通知). */
    private Notification buildNotif(String title, String text) {
        PendingIntent stopPi = PendingIntent.getService(this, 2,
                new Intent(this, AgentService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, "agent")
                .setContentTitle(title)
                .setContentText(text == null ? "" : text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setContentIntent(openSessionPi())
                .addAction(new Notification.Action.Builder(null, "停止", stopPi).build());
        if (text != null) {
            b.setStyle(new Notification.BigTextStyle().bigText(text));
        }
        return b.build();
    }

    private void updateNotif(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(1, buildNotif(title, text));
        } catch (Exception ignored) {}
    }

    private void updateTaskNotif(Task task, String title, String text) {
        try {
            Intent open = new Intent(this, ChatActivity.class).putExtra("sessionId", task.sessionId);
            PendingIntent pi = PendingIntent.getActivity(this, 500 + (task.sessionId.hashCode() & 0x7fff), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, "agent")
                    .setContentTitle(title).setContentText(text == null ? "" : text)
                    .setSmallIcon(android.R.drawable.ic_menu_compass).setOngoing(true)
                    .setContentIntent(pi).build();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(
                    100 + (task.sessionId.hashCode() & 0x7fffffff) % 100000, n);
        } catch (Exception ignored) {}
    }

    /**
     * Agent message notification (agent 走消息通知): shows the agent's say
     * output like a chat message; coalesced per session; tap opens the session.
     */
    private void notifyAgentMessage(Task task, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            String name = agentName(task);
            Intent open = new Intent(this, ChatActivity.class).putExtra("sessionId", task.sessionId);
            PendingIntent pi = PendingIntent.getActivity(this, 3 + (task.sessionId.hashCode() & 0x7fff), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, "agent_msg")
                    .setContentTitle(name)
                    .setContentText(text == null ? "" : text)
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .build();
            int id = 100 + (task.sessionId.hashCode() & 0x7fffffff) % 100000;
            nm.notify(id, n);
        } catch (Exception ignored) {}
    }

    /** Outer loop: runs one task, then the next queued task, until stopped/queue empty. */
    private void loop(final Task task) {
        PowerManager.WakeLock wl = null;
        try {
            WORKER_TASK.set(task);
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "akasha:agent:" + task.sessionId);
            wl.acquire(6 * 60 * 60 * 1000L);
            runTask(task);
            String next;
            boolean suppressNext = false;
            synchronized (task.queuedGoals) {
                next = task.queuedGoals.pollFirst();
                if (next != null && !task.queuedSuppressTaskDone.isEmpty()) {
                    suppressNext = task.queuedSuppressTaskDone.pollFirst();
                }
            }
            if (!task.stopped && next != null) {
                startTask(next, false, task.sessionId, task.agentId, suppressNext);
            }
        } catch (Exception e) {
            taskLog(task, "Agent 异常: " + e, null);
        } finally {
            releaseDevice(task);
            if (wl != null && wl.isHeld()) wl.release();
            if (TASKS.remove(task.sessionId, task)) {
                task.question = null;
                taskLog(task, "Agent 已停止", null);
            }
            running = !TASKS.isEmpty();
            // Flush completed-task memories even when other sessions remain active.
            AutoExperienceWriter.get(this).flush();
            WORKER_TASK.remove();
        }
    }

    /** Inner loop: one task, round by round, until done / interrupted. */
    private void runTask(final Task task) {
        String goal = task.goal;
        String agentId = task.agentId;
        String baseUrl = task.config.baseUrl;
        String apiKey = task.config.apiKey;
        String model = task.profile != null ? task.profile.id : task.config.modelId;
        int intervalMs = task.intervalMs;
        int maxTokens = task.maxTokens;
        int maxRounds = task.maxRounds;
        String appList = task.appList;
        taskLog(task, "任务开始 model=" + model + " goal=" + (goal.isEmpty() ? "(无)" : goal), null);
        taskChat(task, ChatMsg.USER, "任务目标: " + goal, null);

        // 测试会话: 跳过 LLM 调用, 直接触发 onTaskDone
        if ("__test__".equals(agentId)) {
            log("测试会话: 跳过 LLM, 直接触发任务终止");
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    android.widget.Toast.makeText(AgentService.this,
                            "测试会话触发: " + task.goal, android.widget.Toast.LENGTH_SHORT).show();
                }
            });
            try {
                TimerEngine.onTaskDone(this, agentId, task.sessionId, true, "测试会话触发");
            } catch (Throwable t) {
                CpLog.w("Akasha", "TimerEngine.onTaskDone(test): " + t);
            }
            return;
        }

        // If the a11y service died (Huawei may kill our process) while the
        // system still shows it enabled, force AMS to rebind before starting.
        if (!ControlService.ready()) {
            boolean enabled = ControlService.enableWithShizuku();
            boolean rb = enabled || ControlService.rebind();
            log(enabled ? "已通过 Shizuku 自动授予无障碍，等待系统绑定…"
                    : "无障碍服务未就绪，尝试重新绑定…");
            log("无障碍授权/重绑已尝试=" + rb + "（约1-2秒后生效）");
            sleep(1500);
            if (ControlService.ready()) log("无障碍服务已恢复");
        }

        while (!task.stopped && TASKS.get(task.sessionId) == task) {
            // --- observation (text-first; screenshot only on demand) ---
            boolean hasDevice = ownsDevice(task);
            String a11y = null;
            if (!hasDevice) {
                String owner = deviceOwnerDescription(task);
                a11y = "【手机操作锁】" + (owner == null
                        ? "当前未持有。需要读屏或操作手机时先输出 acquire_device。"
                        : "当前由 " + owner + " 持有。不要读屏或操作手机；可处理非设备任务，或 wait 后输出 acquire_device。");
            } else if (task.profile != null && task.profile.permA11y && ControlService.ready()) {
                a11y = ControlService.dumpText(120);
            }
            if (hasDevice && task.profile != null && task.profile.permA11y && (a11y == null
                    || a11y.contains("无可读文本")
                    || a11y.contains("未启用")
                    || a11y.contains("取不到"))) {
                String viaShell = uiDumpViaShell();
                if (viaShell != null && !viaShell.contains("无可读文本")) a11y = viaShell;
            }
            if (a11y == null) {
                a11y = task.profile != null && !task.profile.permA11y
                        ? "(本 Agent 未授予屏幕读取权限；不可读取或截图手机界面)"
                        : "(屏幕文本不可用)";
            }

            boolean needImage = hasDevice && task.profile != null && task.profile.permA11y
                    && task.profile.vision && (task.wantLook || a11y.contains("屏幕文本不可用"));
            task.wantLook = false;

            String jpegB64 = null;
            if (needImage) {
                Bitmap bmp = ScreenShotService.active()
                        ? ScreenShotService.grab()
                        : screenShotViaShell();
                if (bmp != null) {
                    jpegB64 = bmpToJpegB64(bmp);
                    bmp.recycle();
                }
            }
            if (jpegB64 != null) pushShot(task, jpegB64);
            if (hasDevice && jpegB64 == null && a11y.contains("屏幕文本不可用")) {
                // nothing usable at all
                log("截屏/无障碍/shell 均不可用 — 请在设置中授权 Dhizuku 或 Shizuku，或完成屏幕录制授权");
                sleep(5000);
                continue;
            }

            a11y = clip(a11y, MAX_OBSERVATION_CHARS);
            String obs = "【屏幕文本(无障碍)】\n" + a11y
                    + "\n（带 [n] C 前缀的节点可点击，C=clickable；可用 tap_idx n 或 tap_text 操作）";
            if (jpegB64 != null) {
                obs += "\n【屏幕截图】本轮附带截图，可输出坐标动作；不需要时别再输出 look。";
            }
            obs += "\n请根据任务给出下一步动作（只输出一行 JSON）。";

            // diagnostics: log what we actually send the LLM each round (truncated)
            String obsLog = obs.replace('\n', '⏎');
            if (obsLog.length() > 1500) obsLog = obsLog.substring(0, 1500) + "…";
            CpLog.d("Akasha", "obs: " + obsLog);

            List<LlmClient.Msg> msgs = new ArrayList<>();
            String pkg = hasDevice ? getForegroundPackage() : "";
            String expSection = "";
            if (task.profile != null && task.profile.permExpRead) {
                expSection = new ExpStore(this).retrieveRelevant(goal, pkg, task.agentId, 5, 1200);
            }
            String systemPrompt = buildSystemPrompt(task, goal, appList);
            if (expSection != null && !expSection.isEmpty()) {
                systemPrompt = systemPrompt + "\n" + expSection;
            }
            msgs.add(new LlmClient.Msg("system", systemPrompt));
            LinkedList<String[]> hq = task.history;
            synchronized (hq) {
                for (String[] h : hq) {
                    msgs.add(new LlmClient.Msg("assistant", h[0]));
                    msgs.add(new LlmClient.Msg("user", h[1]));
                }
            }
            CpLog.d("HISTORY", "session=" + task.sessionId + " size=" + hq.size());
            JSONArray contentArr;
            try {
                contentArr = new JSONArray();
                contentArr.put(new JSONObject().put("type", "text").put("text", obs));
                if (jpegB64 != null) {
                    contentArr.put(new JSONObject()
                            .put("type", "image_url")
                            .put("image_url", new JSONObject()
                                    .put("url", "data:image/jpeg;base64," + jpegB64)));
                }
            } catch (Exception e) {
                sleep(2000);
                continue;
            }
            msgs.add(new LlmClient.Msg("user", contentArr));

            String content;
            try {
                content = LlmClient.chat(baseUrl, apiKey, model, maxTokens, msgs);
            } catch (Exception e) {
                String meta = metaJson("llm_error", null, null, "LLM 调用失败: " + e.getMessage());
                log("LLM 调用失败: " + e.getMessage(), meta);
                sleep(8000);
                continue;
            }
            CpLog.d("Akasha", "llm: " + truncate(content, 400));

            if (!activeTask(task)) return;

            ActionParser.Action a = ActionParser.parse(content);
            if (!a.ok) {
                // parse failure → centralized error_code (no inline text; ChatGPT spec v2)
                final String code, detail;
                if (content == null || content.trim().isEmpty()) {
                    code = AgentErrorCodes.LLM_EMPTY_OUTPUT; detail = "";
                } else if (content.indexOf('{') < 0) {
                    code = AgentErrorCodes.LLM_NO_JSON_ACTION; detail = clipLine(content);
                } else {
                    code = AgentErrorCodes.LLM_JSON_INVALID; detail = a.parseErr;
                }
                String llmH = AgentErrorMessages.getForLLM(code, detail);
                String usrH = AgentErrorMessages.getForUser(code, detail);
                String meta = metaJson("parse_fail", null, content,
                        code + " | " + detail + " → " + llmH);
                CpLog.w("Akasha", "动作匹配失败: " + code + " detail=" + detail);
                log(usrH, meta);
                chat(ChatMsg.AGENT, "⚠ " + usrH);
                pushHistory(task, maxRounds, content, llmH);
                sleep(intervalMs);
                continue;
            }

            // --- say: agent speaks in chat, split on sentence-ending punctuation (req 3) ---
            if ("say".equals(a.type)) {
                String said = a.message == null ? "" : a.message.trim();
                if (said.isEmpty()) {
                    String code = AgentErrorCodes.ACTION_EXECUTION_EXCEPTION;
                    String detail = "say 动作缺少 message 内容";
                    String usrH = AgentErrorMessages.getForUser(code, detail);
                    String llmH = AgentErrorMessages.getForLLM(code, detail);
                    String meta = metaJson("param_fail", "say", content,
                            code + " | " + detail + " → " + llmH);
                    CpLog.w("Akasha", "say 缺少 message");
                    log(usrH, meta);
                    chat(ChatMsg.AGENT, "⚠ " + usrH);
                    sleep(intervalMs);
                    continue;
                }
                for (String seg : splitSentences(said)) chat(ChatMsg.AGENT, seg);
                notifyAgentMessage(task, said);
                pushHistory(task, maxRounds, content, said);
                sleep(intervalMs);
                continue;
            }

            AgentToolResult tres;
            String res;
            if ("done".equals(a.type) || "ask_user".equals(a.type) || "terminate".equals(a.type)) {
                res = a.type;
                tres = null;
            } else {
                if (!activeTask(task)) return;
                tres = execute(task, a);
                res = tres.llmHint(); // goes back into LLM observation
                // ops are NOT echoed into the chat (req 4); only failures with ⚠
                // no-match codes (search no hit) are intentionally NOT shown in chat
                if (!tres.success) {
                    String meta = metaJson("tool_fail", a.type, content, tres.rawError()
                            + " → " + tres.llmHint());
                    CpLog.w("Akasha", "工具失败: " + tres.rawError());
                    log(tres.userHint() == null ? tres.llmHint() : tres.userHint(), meta);
                    if (tres.userHint() != null) chat(ChatMsg.AGENT, "⚠ " + tres.userHint());
                    AutoExperienceWriter.get(this).onTaskRecovered(
                            task.agentId, agentName(task), task.sessionId,
                            "Tool error: " + tres.rawError(), "Will retry or use alternative approach");
                    if (tres.errorCode != null && (tres.errorCode.contains("PERMISSION") || tres.errorCode.contains("DENIED"))) {
                        AutoExperienceWriter.get(this).onToolRestricted(
                                task.agentId, agentName(task), task.sessionId,
                                "Permission denied: " + a.type + (a.cmd != null ? " " + a.cmd : ""), "Try with shell/root access or alternative method");
                    }
                } else {
                    // success = only system-log (no chat bubble; req 4)
                    CpLog.i("Akasha", "ok: " + a.type + " → "
                            + (res.length() < 160 ? res : res.substring(0, 160) + "…"));
                }
            }

            if ("done".equals(a.type) || "terminate".equals(a.type)) {
                if (!activeTask(task)) return;
                boolean term = "terminate".equals(a.type);
                String msg = a.message == null ? "" : a.message.trim();
                if (!msg.isEmpty()) {
                    for (String seg : splitSentences(msg)) chat(ChatMsg.AGENT, seg);
                    notifyAgentMessage(task, msg);
                }
                log((term ? "任务终止: " : "任务完成: ") + msg);
                updateTaskNotif(task, (term ? "任务终止: " : "任务完成: ") + msg, msg);
                // 事件唤醒钩子: 任务结束/终止 (TimerWakeup「任务完成」事件)
                try {
                    if (!task.suppressTaskDoneTrigger) {
                        TimerEngine.onTaskDone(this, task.agentId, task.sessionId, term, msg);
                    }
                    AutoExperienceWriter.get(this).onTaskSuccess(
                            task.agentId, agentName(task), task.sessionId,
                            task.goal,
                            "", msg);
                } catch (Throwable t) {
                    CpLog.w("Akasha", "TimerEngine.onTaskDone: " + t);
                }
                return;
            }
            if ("ask_user".equals(a.type)) {
                task.question = a.question;
                chat(ChatMsg.AGENT, "❓ 提问: " + a.question + "（请在底部输入框回答）");
                updateTaskNotif(task, "Agent 提问: " + a.question, a.question);
                String answer = waitForAnswer(task);
                task.question = null;
                if (answer == null) return;
                res = "用户回答: " + answer;
                chat(ChatMsg.USER, "回答: " + answer);
                AutoExperienceWriter.get(this).onUserConfirmed(
                        task.agentId, agentName(task), task.sessionId,
                        answer);
            }

            pushHistory(task, maxRounds, content, res);
            sleep(intervalMs);
        }
    }

    private AgentToolResult execute(Task task, ActionParser.Action a) {
        if (!activeTask(task)) return AgentToolResult.err(AgentErrorCodes.TASK_CANCELLED, "");
        if ("acquire_device".equals(a.type)) return acquireDevice(task);
        if ("release_device".equals(a.type)) {
            if (!ownsDevice(task)) return AgentToolResult.err(AgentErrorCodes.DEVICE_LOCK_REQUIRED, "");
            releaseDevice(task);
            return AgentToolResult.ok("已释放手机操作锁");
        }
        if (isDeviceAction(a.type) && !ownsDevice(task)) {
            return AgentToolResult.err(AgentErrorCodes.DEVICE_LOCK_REQUIRED, "");
        }
        if (("look".equals(a.type) || "a11y_text".equals(a.type)
                || "tap_text".equals(a.type) || "tap_idx".equals(a.type)
                || "tap".equals(a.type) || "double_tap".equals(a.type)
                || "swipe".equals(a.type) || "type".equals(a.type)
                || "key".equals(a.type) || "open_app".equals(a.type)
                || "web_open".equals(a.type))
                && !hasPerm("a11y")) {
            return AgentToolResult.err(AgentErrorCodes.AGENT_A11Y_PERMISSION_DENIED, "");
        }
        // --- per-agent permission gate (req 6): centralized error_codes ---
        if ("file_ls".equals(a.type) || "file_read".equals(a.type)
                || "file_write".equals(a.type) || "file_search".equals(a.type)) {
            if (!hasPerm("file")) return AgentToolResult.err(
                    AgentErrorCodes.AGENT_FILE_PERMISSION_DENIED, "");
            String deny = fileCategoryDeny(a);
            if (deny != null) return AgentToolResult.err(deny, a.path == null ? "" : a.path);
        }
        if ("shell".equals(a.type) && !hasPerm("shell")) {
            return AgentToolResult.err(AgentErrorCodes.SHELL_AGENT_PERMISSION_DENIED, "");
        }
        if ("a11y_text".equals(a.type) || "tap_text".equals(a.type) || "tap_idx".equals(a.type)
                || "tap".equals(a.type) || "double_tap".equals(a.type) || "swipe".equals(a.type)
                || "type".equals(a.type) || "key".equals(a.type) || "open_app".equals(a.type)) {
            if (!hasPerm("a11y")) return AgentToolResult.err(
                    AgentErrorCodes.AGENT_A11Y_PERMISSION_DENIED, "");
        }
        if ("exp_record".equals(a.type) || "exp_delete".equals(a.type)) {
            if (task.profile != null && !task.profile.permExpWrite) {
                return AgentToolResult.err(AgentErrorCodes.EXP_NO_WRITE_PERM, "");
            }
        }
        if ("exp_search".equals(a.type)) {
            if (task.profile != null && !task.profile.permExpRead) {
                return AgentToolResult.err(AgentErrorCodes.EXP_NO_READ_PERM, "");
            }
        }

        try {
            switch (a.type) {
                case "wait":
                    sleep(Math.min(30000, Math.max(200, a.ms)));
                    return AgentToolResult.ok("wait " + a.ms + "ms");
                case "chat_search": {
                    ChatSearch.Q sq = new ChatSearch.Q();
                    sq.query = a.message == null ? "" : a.message;
                    sq.scope = a.scope;
                    sq.fromTs = a.fromTs;
                    sq.toTs = a.toTs;
                    sq.role = a.role;
                    sq.senderAgentIds = a.senderAgentIds;
                    ChatSearch.R res = ChatSearch.query(this, sq, task.agentId, task.sessionId);
                    String body = res.hint;
                    if (body == null || body.isEmpty()) body = "无命中";
                    return AgentToolResult.ok(body);
                }
                case "look":
                    task.wantLook = true;
                    return AgentToolResult.ok("下一轮将附带截图");
                case "file_ls":
                    return fileLs(a.path);
                case "file_read":
                    return fileRead(a.path);
                case "file_write":
                    return fileWrite(a.path, a.content);
                case "file_search":
                    return fileSearch(a.pattern);
                case "web_open":
                    return AgentToolResult.ok(webOpen(a.url, a.pkg));
                case "app_list":
                    return AgentToolResult.ok(appList(a.query));
                case "clipboard_set":
                    return AgentToolResult.ok(clipSet(a.text));
                case "clipboard_get":
                    return AgentToolResult.ok(clipGet());
                case "a11y_text":
                    if (!ControlService.ready())
                        return AgentToolResult.err(AgentErrorCodes.A11Y_NOT_ENABLED, "");
                    return AgentToolResult.ok(
                            "已刷新（下一轮观察即最新文本树）: " + clipLine(ControlService.dumpText(120)));
                case "shell":
                    return shellCmd(a.cmd);
                case "exp_record":
                    return expRecord(a);
                case "exp_search":
                    return expSearch(a);
                case "exp_delete":
                    return expDelete(a);
                case "tap_text": {
                    if (!ControlService.ready())
                        return AgentToolResult.err(AgentErrorCodes.A11Y_NOT_ENABLED, "");
                    String q = a.query != null ? a.query : a.text;
                    String out = ControlService.tapText(q);
                    return out.contains("未找到")
                            ? AgentToolResult.err(AgentErrorCodes.A11Y_TEXT_NOT_FOUND,
                                    q == null ? "" : q)
                            : AgentToolResult.ok(out);
                }
                case "tap_idx": {
                    if (!ControlService.ready())
                        return AgentToolResult.err(AgentErrorCodes.A11Y_NOT_ENABLED, "");
                    String out = ControlService.tapIdx(a.idx);
                    if (out.contains("越界"))
                        return AgentToolResult.err(AgentErrorCodes.A11Y_INDEX_OUT_OF_RANGE,
                                String.valueOf(a.idx));
                    return AgentToolResult.ok(out);
                }
                default:
                    break;
            }

            // --- screen gestures: need accessibility ---
            if (!ControlService.ready()) {
                return AgentToolResult.err(AgentErrorCodes.A11Y_NOT_ENABLED, "");
            }
            DisplayMetrics dm = new DisplayMetrics();
            ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getMetrics(dm);
            int W = dm.widthPixels, H = dm.heightPixels;
            switch (a.type) {
                case "tap":
                    boolean t = ControlService.tapPx(
                            clamp((int) (a.x * W), W), clamp((int) (a.y * H), H));
                    return t
                            ? AgentToolResult.ok("tap@(" + (int) (a.x * W) + "," + (int) (a.y * H) + ")")
                            : AgentToolResult.err(AgentErrorCodes.A11Y_TAP_FAILED, "");
                case "double_tap":
                    boolean d = ControlService.doubleTapPx(
                            clamp((int) (a.x * W), W), clamp((int) (a.y * H), H));
                    return d ? AgentToolResult.ok("double_tap")
                            : AgentToolResult.err(AgentErrorCodes.A11Y_DOUBLE_TAP_FAILED, "");
                case "swipe":
                    boolean s = ControlService.swipePx(
                            clamp((int) (a.x1 * W), W), clamp((int) (a.y1 * H), H),
                            clamp((int) (a.x2 * W), W), clamp((int) (a.y2 * H), H), a.ms);
                    return s ? AgentToolResult.ok("swipe")
                            : AgentToolResult.err(AgentErrorCodes.A11Y_SWIPE_FAILED, "");
                case "type":
                    boolean ty = ControlService.typeText(a.text);
                    return ty ? AgentToolResult.ok("type")
                            : AgentToolResult.err(AgentErrorCodes.A11Y_NO_FOCUSED_INPUT, "");
                case "key":
                    boolean k = ControlService.globalKey(a.key);
                    return k ? AgentToolResult.ok("key " + a.key)
                            : AgentToolResult.err(AgentErrorCodes.A11Y_KEY_FAILED, "");
                case "open_app": {
                    String oa = openApp(a.pkg);
                    if (oa.startsWith("无法打开"))
                        return AgentToolResult.err(AgentErrorCodes.A11Y_OPEN_APP_FAILED,
                                a.pkg == null ? "" : a.pkg);
                    if (oa.startsWith("打开失败"))
                        return AgentToolResult.err(AgentErrorCodes.A11Y_OPEN_APP_EXCEPTION, oa);
                    return AgentToolResult.ok(oa);
                }
                case "wait":
                    sleep(Math.min(30000, Math.max(200, a.ms)));
                    return AgentToolResult.ok("wait " + a.ms + "ms");
                default:
                    return AgentToolResult.err(AgentErrorCodes.ACTION_UNKNOWN,
                            a.type == null ? "" : a.type);
            }
        } catch (Exception e) {
            return AgentToolResult.err(AgentErrorCodes.ACTION_EXECUTION_EXCEPTION,
                    a.type + " | " + e);
        }
    }

    /**
     * Returns a FILE_CATEGORY_* error code if the file operation is blocked by
     * the agent's per-category permission gate, or null if allowed.
     */
    private String fileCategoryDeny(ActionParser.Action a) {
        Task task = WORKER_TASK.get();
        ModelInfo p = task == null ? null : task.profile;
        if (p == null) return null;
        String path = a.path == null ? "" : a.path.toLowerCase(Locale.ROOT);
        if (!p.permPhoto && looksLikePhoto(path))
            return AgentErrorCodes.FILE_CATEGORY_PHOTO_DENIED;
        if (!p.permMedia && looksLikeMedia(path))
            return AgentErrorCodes.FILE_CATEGORY_MEDIA_DENIED;
        if (!p.permMusic && looksLikeMusic(path))
            return AgentErrorCodes.FILE_CATEGORY_MUSIC_DENIED;
        return null;
    }

    // ---------------- per-agent permission helpers (req 6) ----------------

    private boolean hasPerm(String k) {
        Task task = WORKER_TASK.get();
        ModelInfo p = task == null ? null : task.profile;
        if (p == null) return true; // legacy/no session: keep old behavior
        switch (k) {
            case "shell": return p.permShell;
            case "a11y": return p.permA11y;
            case "file": return p.permFile;
            case "photo": return p.permPhoto;
            case "media": return p.permMedia;
            case "music": return p.permMusic;
            default: return true;
        }
    }

    // ---------------- chat formatting helpers (req 3/4/5) ----------------

    /** Split agent speech at sentence-ending punctuation, keeping the marks. */
    private static List<String> splitSentences(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            cur.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                    || c == '；' || c == ';' || c == '\n') {
                String t = cur.toString().trim();
                if (!t.isEmpty()) out.add(t);
                cur.setLength(0);
            }
        }
        String t = cur.toString().trim();
        if (!t.isEmpty()) out.add(t);
        if (out.isEmpty()) out.add(s == null ? "" : s);
        return out;
    }

    private static String firstLine(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Detail payload behind a system line (long-press -> LogDetailActivity). */
    private static String metaJson(String kind, String tool, String raw, String err) {
        try {
            JSONObject o = new JSONObject();
            o.put("kind", kind);
            if (tool != null) o.put("tool", tool);
            o.put("raw", raw == null ? "" :
                    (raw.length() > 20000 ? raw.substring(0, 20000) : raw));
            o.put("err", err == null ? "" : err);
            String hint;
            switch (kind) {
                case "parse_fail":
                    hint = "模型必须只输出一行 JSON 动作（不要附加解释文字）。示例: {\"action\":\"tap_text\",\"query\":\"设置\"}。常见原因: 多余解释、JSON 语法错误、缺少必填字段。";
                    break;
                case "param_fail":
                    hint = (tool == null ? "" : "动作 " + tool + " 的输入或参数不符合规范。")
                            + " 请对照系统提示词中的工具说明修正参数后重试。";
                    break;
                case "tool_fail":
                    hint = (tool == null ? "" : "动作 " + tool + " 执行被拒或失败。")
                            + " 请检查参数是否符合系统提示词中的工具说明；若是权限类错误，告知用户到「通讯录→该 Agent→权限管理」开启。";
                    break;
                case "llm_error":
                default:
                    hint = "模型接口调用失败（网络/鉴权/限流）。任务将自动重试；若持续失败请检查设置中的 Base URL 与 API Key。";
                    break;
            }
            o.put("hint", hint);
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- screenshot ring for exp_record (req 6) ----------------

    /** JPEG bytes of recent grabbed screens (newest last, capped). */
    private static final int SHOT_RING_MAX = 10;

    private static void pushShot(Task task, String jpegB64) {
        try {
            byte[] b = android.util.Base64.decode(jpegB64, android.util.Base64.DEFAULT);
            if (b == null || b.length == 0) return;
            synchronized (task.shots) {
                task.shots.addLast(b);
                while (task.shots.size() > SHOT_RING_MAX) task.shots.removeFirst();
            }
        } catch (Exception ignored) {}
    }

    private static boolean activeTask(Task task) {
        return task != null && !task.stopped
                && TASKS.get(task.sessionId) == task
                && !Thread.currentThread().isInterrupted();
    }

    private static boolean isDeviceAction(String action) {
        return "look".equals(action) || "a11y_text".equals(action) || "tap_text".equals(action)
                || "tap_idx".equals(action) || "tap".equals(action) || "double_tap".equals(action)
                || "swipe".equals(action) || "type".equals(action) || "key".equals(action)
                || "open_app".equals(action) || "web_open".equals(action) || "shell".equals(action)
                || "clipboard_set".equals(action) || "clipboard_get".equals(action);
    }

    // ---------------- experience pool tools (req 6) ----------------

    private ExpStore expStore() {
        return new ExpStore(this);
    }

    private AgentToolResult expRecord(ActionParser.Action a) {
        // perm gate already in execute
        String title = a.title == null ? "" : a.title.trim();
        String content = a.content == null ? "" : a.content.trim();
        if (title.isEmpty() && content.isEmpty()) {
            return AgentToolResult.err(AgentErrorCodes.EXP_RECORD_MISSING_CONTENT, "");
        }
        if (title.isEmpty()) title = content.length() > 20 ? content.substring(0, 20) + "…" : content;
        int n = a.shots;
        Task task = WORKER_TASK.get();
        if (n < 0) n = Math.min(2, task.shots.size());
        List<Bitmap> shots = new ArrayList<>();
        synchronized (task.shots) {
            int from = Math.max(0, task.shots.size() - Math.min(9, Math.max(0, n)));
            int i = 0;
            for (byte[] bytes : task.shots) {
                if (i++ >= from) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) shots.add(bmp);
                }
            }
        }
        Experience e = expStore().record(
                task.agentId, agentName(task), title, content, shots);
        if (e == null) return AgentToolResult.err(AgentErrorCodes.EXP_NO_WRITE_PERM, "");
        CpLog.i("Akasha", "exp_record id=" + e.id + " title=" + title + " shots=" + shots.size());
        return AgentToolResult.ok(
                "已记录经验 id=" + e.id + "（" + title + "）截图" + shots.size() + "张");
    }

    private AgentToolResult expSearch(ActionParser.Action a) {
        // perm gate already in execute
        String q = a.query == null ? "" : a.query.trim();
        if (q.isEmpty()) {
            return AgentToolResult.err(AgentErrorCodes.EXP_SEARCH_QUERY_EMPTY, "");
        }
        List<Experience> hits = expStore().searchForAgent(WORKER_TASK.get().agentId, q);
        if (hits.size() > 5) hits = hits.subList(0, 5);
        if (hits.isEmpty()) {
            return AgentToolResult.err(AgentErrorCodes.EXP_SEARCH_NO_MATCH, q);
        }
        StringBuilder sb = new StringBuilder();
        for (Experience e : hits) {
            sb.append("[").append(e.agentName).append("] ").append(e.title).append('\n')
              .append(e.content).append('\n');
        }
        String r = sb.toString();
        return AgentToolResult.ok(r.length() > 4000 ? r.substring(0, 4000) + "\n…(truncated)" : r);
    }

    private AgentToolResult expDelete(ActionParser.Action a) {
        // perm gate already in execute
        String id = a.id != null ? a.id : (a.query != null ? a.query : a.title);
        if (id == null || id.trim().isEmpty()) {
            return AgentToolResult.err(AgentErrorCodes.EXP_DELETE_TARGET_EMPTY, "");
        }
        int n = expStore().removeByAgent(
                WORKER_TASK.get().agentId, id.trim());
        if (n <= 0) {
            return AgentToolResult.err(AgentErrorCodes.EXP_DELETE_NOT_OWNED_OR_NOT_FOUND, id);
        }
        return AgentToolResult.ok("已删除 " + n + " 条经验");
    }

    private String agentName(Task task) {
        if (task != null && task.profile != null && task.profile.name != null) return task.profile.name;
        return task == null ? "unknown" : task.agentId;
    }

    /** Category restrictions for explicit file paths (相册/媒体/音乐). */
    private static boolean looksLikePhoto(String p) {
        return p.contains("pictures") || p.contains("dcim")
                || p.contains("album") || p.contains("screenshot");
    }
    private static boolean looksLikeMedia(String p) {
        return p.contains("movies") || p.contains("video") || p.contains("podcasts");
    }
    private static boolean looksLikeMusic(String p) {
        return p.contains("music") || p.contains("ringtone");
    }

    // ---------------- shell-ish tools ----------------

    private AgentToolResult shellCmd(String cmd) {
        // permission gate already in execute() — here just run
        if (!ShellChannel.available()) {
            return AgentToolResult.err(AgentErrorCodes.SHELL_CHANNEL_UNAVAILABLE, "");
        }
        if (cmd == null || cmd.trim().isEmpty()) {
            return AgentToolResult.err(AgentErrorCodes.SHELL_CMD_EMPTY, "");
        }
        String c = cmd.trim();
        String lc = c.toLowerCase(java.util.Locale.ROOT);
        if (!isAllowedShellCommand(c)
                || lc.contains("reboot") || lc.contains("shutdown") || lc.contains("fastboot")
                || lc.contains("pm uninstall") || lc.contains("pm clear")
                || lc.contains("rm -rf /") || lc.contains("dd if=") || lc.contains("mkfs")) {
            return AgentToolResult.err(AgentErrorCodes.SHELL_DANGEROUS_COMMAND_REJECTED, c);
        }
        String r = ShellChannel.exec(c);
        if (r == null) return AgentToolResult.err(AgentErrorCodes.SHELL_CHANNEL_DISCONNECTED, c);
        String uid = ShellChannel.svcUid();
        if (uid != null && !"2000".equals(uid)) {
            r = "(注: 当前通道为 app 级 uid " + uid + "，非 shell，系统命令可能受限)\n" + r;
        }
        if (r.length() > 6000) r = r.substring(0, 6000) + "\n…(truncated)";
        return AgentToolResult.ok(r);
    }

    private static boolean isAllowedShellCommand(String command) {
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) return false;
        for (char ch : new char[]{';', '|', '&', '`', '$', '>', '<', '(', ')'}) {
            if (command.indexOf(ch) >= 0) return false;
        }
        int end = 0;
        while (end < command.length() && !Character.isWhitespace(command.charAt(end))) end++;
        String executable = command.substring(0, end);
        int slash = executable.lastIndexOf('/');
        if (slash >= 0) executable = executable.substring(slash + 1);
        return executable.matches("[A-Za-z0-9_.-]+")
                && (executable.equals("id") || executable.equals("getprop")
                || executable.equals("dumpsys") || executable.equals("screencap")
                || executable.equals("uiautomator") || executable.equals("input")
                || executable.equals("am") || executable.equals("settings")
                || executable.equals("pm") || executable.equals("cmd")
                || executable.equals("logcat") || executable.equals("head")
                || executable.equals("cat") || executable.equals("rm")
                || executable.equals("echo"));
    }

    /** Screen text tree via `uiautomator dump` when the a11y service is off. */
    private String uiDumpViaShell() {
        Task task = WORKER_TASK.get();
        if (task != null && task.profile != null && !task.profile.permShell) return null;
        if (!ShellChannel.available()) return null;
        String r = ShellChannel.exec(
                "uiautomator dump /sdcard/akasha_ui.xml >/dev/null 2>&1; "
                + "head -c 60000 /sdcard/akasha_ui.xml 2>/dev/null; rm -f /sdcard/akasha_ui.xml");
        if (r == null || r.contains("NoSuchFile") || r.trim().length() < 10) return null;
        int nl = r.indexOf('\n');
        String xml = nl >= 0 ? r.substring(nl + 1) : r;
        if (!xml.contains("<node")) return null;
        java.util.regex.Pattern node = java.util.regex.Pattern.compile("<node[^>]*>");
        java.util.regex.Matcher m = node.matcher(xml);
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (m.find() && idx < 120) {
            String chunk = m.group();
            String text = attr(chunk, "text");
            String desc = attr(chunk, "content-desc");
            String bounds = attr(chunk, "bounds");
            boolean clickable = chunk.contains("clickable=\"true\"");
            String label = (text != null && !text.isEmpty()) ? text
                    : (desc != null && !desc.isEmpty()) ? desc : "";
            if (label.isEmpty() && !clickable) continue;
            int cx = -1, cy = -1;
            java.util.regex.Matcher bm = java.util.regex.Pattern
                    .compile("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matcher(bounds == null ? "" : bounds);
            if (bm.find()) {
                cx = (Integer.parseInt(bm.group(1)) + Integer.parseInt(bm.group(3))) / 2;
                cy = (Integer.parseInt(bm.group(2)) + Integer.parseInt(bm.group(4))) / 2;
            }
            label = label.replace('\n', ' ').trim();
            if (label.length() > 60) label = label.substring(0, 60) + "…";
            sb.append('[').append(idx++).append("] ")
              .append(clickable ? "C" : "-").append(' ')
              .append(label).append(" @(").append(cx).append(',').append(cy).append(")\n");
        }
        return sb.length() == 0 ? "(无可读文本节点 — 建议 look 截图)" : sb.toString();
    }

    private static String attr(String nodeXml, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(name + "=\"([^\"]*)\"").matcher(nodeXml);
        return m.find() ? m.group(1) : null;
    }

    /** Screenshot via shell `screencap` when mediaProjection is not authorized. */
    private Bitmap screenShotViaShell() {
        Task task = WORKER_TASK.get();
        if (task != null && task.profile != null && !task.profile.permShell) return null;
        if (!ShellChannel.available()) return null;
        String r = ShellChannel.exec("screencap -p /sdcard/akasha_shot.png 2>/dev/null && echo CLAW_OK");
        if (r == null || !r.contains("CLAW_OK")) return null;
        File f = new File(Environment.getExternalStorageDirectory(), "akasha_shot.png");
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }

    private static String clipLine(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private AgentToolResult fileLs(String path) {
        if (path == null || path.trim().isEmpty()) {
            return AgentToolResult.err(AgentErrorCodes.FILE_PATH_EMPTY, "");
        }
        File f = resolve(path);
        if (f == null) {
            return AgentToolResult.err(AgentErrorCodes.FILE_PATH_OUT_OF_SCOPE, path);
        }
        if (!f.isDirectory()) {
            return AgentToolResult.err(AgentErrorCodes.FILE_DIR_NOT_FOUND, path);
        }
        File[] fs;
        try {
            fs = f.listFiles();
        } catch (Exception e) {
            return AgentToolResult.err(AgentErrorCodes.FILE_DIR_READ_DENIED, path);
        }
        if (fs == null) {
            return AgentToolResult.err(AgentErrorCodes.FILE_DIR_READ_DENIED, path);
        }
        StringBuilder sb = new StringBuilder();
        Arrays.sort(fs);
        for (int i = 0; i < fs.length && i < 80; i++) {
            File x = fs[i];
            sb.append(x.isDirectory() ? "D " : "F ")
              .append(x.getName()).append(' ').append(x.length()).append('\n');
        }
        if (fs.length > 80) sb.append("…共 ").append(fs.length).append(" 项\n");
        return AgentToolResult.ok(sb.length() == 0 ? "(空目录)" : sb.toString());
    }

    private AgentToolResult fileRead(String path) {
        if (path == null || path.trim().isEmpty()) {
            return AgentToolResult.err(AgentErrorCodes.FILE_PATH_EMPTY, "");
        }
        File f = resolve(path);
        if (f == null) {
            return AgentToolResult.err(AgentErrorCodes.FILE_PATH_OUT_OF_SCOPE, path);
        }
        if (!f.isFile()) {
            return AgentToolResult.err(AgentErrorCodes.FILE_NOT_FOUND, path);
        }
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[48 * 1024];
            int n = in.read(buf);
            String s = new String(buf, 0, n < 0 ? 0 : n, "UTF-8");
            boolean more = f.length() > buf.length;
            return AgentToolResult.ok(
                    (more ? "(仅前 48KB，总大小 " + f.length() + ")\n" : "") + s);
        } catch (Exception e) {
            return AgentToolResult.err(AgentErrorCodes.FILE_READ_FAILED,
                    f.getAbsolutePath() + " | " + e);
        }
    }

    private AgentToolResult fileWrite(String path, String content) {
        if (path == null || path.trim().isEmpty()) {
            return AgentToolResult.err(AgentErrorCodes.FILE_PATH_EMPTY, "");
        }
        File f = resolve(path);
        if (f == null) {
            return AgentToolResult.err(AgentErrorCodes.FILE_PATH_OUT_OF_SCOPE, path);
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            return AgentToolResult.err(AgentErrorCodes.FILE_PARENT_NOT_FOUND,
                    parent.getAbsolutePath());
        }
        if (content == null) content = "";
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes("UTF-8"));
            return AgentToolResult.ok(
                    "已写入 " + f.getAbsolutePath() + " (" + content.length() + " 字符)");
        } catch (Exception e) {
            return AgentToolResult.err(AgentErrorCodes.FILE_WRITE_FAILED,
                    f.getAbsolutePath() + " | " + e);
        }
    }

    private AgentToolResult fileSearch(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return AgentToolResult.err(AgentErrorCodes.FILE_SEARCH_PATTERN_EMPTY, "");
        }
        final String p = pattern.trim().toLowerCase(Locale.ROOT);
        final List<String> hits = new ArrayList<>();
        final File root = Environment.getExternalStorageDirectory();
        if (!root.canRead()) {
            return AgentToolResult.err(AgentErrorCodes.FILE_STORAGE_PERMISSION_DENIED, "");
        }
        searchWalk(root, p, 0, hits, 60000);
        if (hits.isEmpty()) {
            return AgentToolResult.err(AgentErrorCodes.FILE_SEARCH_NO_MATCH, pattern);
        }
        return AgentToolResult.ok("匹配 " + hits.size() + " 个:\n" + String.join("\n", hits));
    }

    private void searchWalk(File dir, String p, int depth, List<String> hits, int budget) {
        if (depth > 5 || hits.size() >= 50 || budget <= 0) return;
        File[] fs;
        try {
            fs = dir.listFiles();
        } catch (Exception e) {
            return;
        }
        if (fs == null) return;
        for (File f : fs) {
            if (budget <= 0 || hits.size() >= 50) return;
            budget -= 10;
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.contains(p)) {
                hits.add(f.getAbsolutePath() + (f.isDirectory() ? "/" : " (" + f.length() + "B)"));
            } else if (f.isDirectory() && !f.getName().startsWith(".")) {
                searchWalk(f, p, depth + 1, hits, budget);
            }
        }
    }

    private File resolve(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        String s = path.trim();
        File base = Environment.getExternalStorageDirectory();
        if (s.startsWith("/sdcard/")) s = s.substring("/sdcard/".length());
        else if (s.equals("/sdcard")) s = "";
        if (s.startsWith("/")) s = s.substring(1);
        File f = s.isEmpty() ? base : new File(base, s);
        try {
            String cf = f.getCanonicalPath();
            String cb = base.getCanonicalPath();
            if (!cf.equals(cb) && !cf.startsWith(cb + File.separator)) return null;
        } catch (Exception e) {
            return null;
        }
        return f;
    }

    private String webOpen(String url, String pkg) {
        if (url == null || url.trim().isEmpty()) return "url 为空";
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url.trim()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pkg != null && !pkg.trim().isEmpty()) i.setPackage(pkg.trim());
            startActivity(i);
            return "已在浏览器打开: " + url;
        } catch (Exception e) {
            return "打开失败: " + e;
        }
    }

    private String appList(String query) {
        try {
            List<PackageInfo> pkgs = getPackageManager().getInstalledPackages(0);
            List<String> names = new ArrayList<>();
            String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
            for (PackageInfo pi : pkgs) {
                if (q.length() > 0
                        && !pi.packageName.toLowerCase(Locale.ROOT).contains(q)
                        && !pi.applicationInfo.loadLabel(getPackageManager()).toString().toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
                Intent li = getPackageManager().getLaunchIntentForPackage(pi.packageName);
                if (li == null) continue;
                names.add(pi.packageName + ":" + pi.applicationInfo.loadLabel(getPackageManager()).toString());
                if (names.size() >= 60) break;
            }
            return names.isEmpty() ? "(无匹配应用)" : String.join("\n", names);
        } catch (Exception e) {
            return "(无)";
        }
    }

    private String clipSet(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("akasha", text == null ? "" : text));
            return "已写入剪贴板 (" + (text == null ? 0 : text.length()) + " 字符)";
        } catch (Exception e) {
            return "剪贴板写入失败: " + e;
        }
    }

    private String clipGet() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                return t == null ? "(剪贴板为空)" : clipLine(t.toString());
            }
            return "(剪贴板为空)";
        } catch (Exception e) {
            return "剪贴板读取失败: " + e;
        }
    }

    private String openApp(String pkg) {
        if (pkg == null) return "open_app 缺少 package";
        Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) return "应用未安装: " + pkg;
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(li);
        return "open_app " + pkg;
    }

    private String waitForAnswer(final Task task) {
        long deadline = System.currentTimeMillis() + 30 * 60 * 1000L;
        synchronized (task.answerLock) {
            while (task.pendingAnswer == null && !task.stopped && TASKS.get(task.sessionId) == task) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    task.answerLock.wait(Math.min(left, 1000));
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            String a = !task.stopped && TASKS.get(task.sessionId) == task ? task.pendingAnswer : null;
            task.pendingAnswer = null;
            return a;
        }
    }

    private String bmpToJpegB64(Bitmap bmp) {
        try {
            float scale = Math.min(1f, 900f / (float) bmp.getWidth());
            Bitmap out = scale < 1f
                    ? Bitmap.createScaledBitmap(bmp, (int) (bmp.getWidth() * scale), (int) (bmp.getHeight() * scale), true)
                    : bmp;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.JPEG, 60, bos);
            if (out != bmp) out.recycle();
            return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildAppList() {
        try {
            List<PackageInfo> pkgs = getPackageManager().getInstalledPackages(0);
            List<String> names = new ArrayList<>();
            for (PackageInfo pi : pkgs) {
                if ((pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                Intent li = getPackageManager().getLaunchIntentForPackage(pi.packageName);
                if (li == null) continue;
                names.add(pi.packageName + ":" + pi.applicationInfo.loadLabel(getPackageManager()).toString());
                if (names.size() >= 40) break;
            }
            return names.isEmpty() ? "(无)" : String.join(", ", names);
        } catch (Exception e) {
            return "(无)";
        }
    }

    /**
     * System prompt assembly (req 6/7):
     * goal + live guide + (会话提示词 | 模型提示词 | 系统提示词)
     *   + permission-filtered tool docs + installed apps.
     * 三级回退: 会话提示词(空)→ 模型提示词(空)→ 系统提示词 AgentPrompts.defaultBase(this)(空=内置默认)。
     */
    private String buildSystemPrompt(Task task, String goal, String appList) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务目标: ").append(goal.isEmpty() ? "（无明确目标，输出 wait 或处理非设备任务）" : clip(goal, 4000)).append("\n");
        synchronized (task.guides) {
            if (!task.guides.isEmpty()) {
                sb.append("用户实时引导(必须优先遵循):\n");
                for (String g : task.guides) sb.append("- ").append(clip(g, 1000)).append("\n");
            }
        }
        String[] base = AgentPrompts.resolveBase(this, task.sessionPrompt, task.profile);
        CpLog.d("Akasha", "prompt source: " + base[1]);
        sb.append(clip(base[0], 12000)).append("\n");
        sb.append(AgentPrompts.toolDocs(task.profile)).append("\n")
          .append("已装应用: ").append(clip(appList, MAX_APP_LIST_CHARS)).append("\n");
        return sb.toString();
    }

    private String getForegroundPackage() {
        try {
            if (ShellChannel.available()) {
                String r = ShellChannel.exec("dumpsys window | grep mCurrentFocus");
                if (r != null && !r.isEmpty()) {
                    int idx = r.indexOf("u0 ");
                    if (idx >= 0) {
                        String pkg = r.substring(idx + 3).trim();
                        int slash = pkg.indexOf('/');
                        if (slash > 0) return pkg.substring(0, slash);
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : (v >= max ? max - 1 : v);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** Keep prompt entries bounded while retaining both the beginning and outcome. */
    private static String clip(String s, int max) {
        if (s == null) return "";
        if (max < 16 || s.length() <= max) return s;
        int head = Math.max(8, max * 2 / 3);
        int tail = Math.max(8, max - head);
        return s.substring(0, head) + "\n…(已裁剪 " + s.length() + " 字符)…\n"
                + s.substring(s.length() - tail);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
