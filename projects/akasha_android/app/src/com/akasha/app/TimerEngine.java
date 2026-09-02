package com.akasha.app;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「定时器与唤醒」执行引擎（避免 Agent 24h 工作，在必要时唤醒）。
 *
 *  触发结果分两级:
 *  - 会话级 (fireForSession): 向已存在的会话发送, 不新建
 *  - 模型级 (fireForAgent): 始终新建会话 + 发送
 *
 *  1) 闹钟式定时唤醒: 30s tick 比对 24h 时间 + 重复规则(周一~日/每月N号, 钳制到当月最后一天)
 *  2) 事件唤醒:
 *     - 任务完成/终止: AgentService 在 done/terminate 时回调 onTaskDone()
 *     - 打开软件:     ControlService(无障碍) 在前台窗口变化时回调 onAppForeground()
 *  3) 倒计时: 1s 循环直接驱动 UI 方框; 归零触发 (功能预留扩展:
 *     目前仅 App 进程存活期间有效, 后续可扩展 AlarmManager/前台服务保活)
 */
public final class TimerEngine {

    private static final String TAG = "TimerEngine";

    private static volatile Context appCtx = null;
    private static volatile boolean started = false;

    private static HandlerThread worker;
    private static Handler workerH;

    // 30s 闹钟 tick
    private static final Runnable alarmTick = new Runnable() {
        @Override
        public void run() {
            try {
                checkAlarms();
            } catch (Throwable t) {
                CpLog.e(TAG, "alarmTick: " + t);
            }
            if (workerH != null) workerH.postDelayed(this, 30_000L);
        }
    };

    // ---- 倒计时状态 (1s 循环) ----
    private static volatile boolean cdRunning = false;
    private static volatile String cdAgentId = null;
    private static volatile long cdRemainingMs = 0;
    private static volatile long cdLastMs = 0;
    private static volatile boolean cdFired = false;
    /** UI 回调(每 1s 与状态变化时, 主线程): 参数 = 剩余毫秒 */
    private static volatile Runnable cdListener = null;

    private static final Runnable cdTick = new Runnable() {
        @Override
        public void run() {
            if (!cdRunning) return;
            long now = System.currentTimeMillis();
            cdRemainingMs -= (now - cdLastMs);
            cdLastMs = now;
            if (cdRemainingMs <= 0) {
                cdRemainingMs = 0;
                cdRunning = false;
                if (!cdFired) {
                    cdFired = true;
                    String aid = cdAgentId;
                    Context c = appCtx;
                    if (aid != null && c != null) {
                        Prefs p = new Prefs(c);
                        CpLog.i(TAG, "倒计时归零 agent=" + aid);
                        fireForAgent(aid, p.timer(aid).countdownResult, "倒计时归零");
                    }
                }
            }
            notifyUi();
            if (cdRunning && workerH != null) workerH.postDelayed(this, 1000L);
        }
    };

    // 触发去重 (跨进程重启: 闹钟 key 落 Prefs)
    private static volatile String lastAlarmKey = "";
    private static final Map<String, Long> firedAlarmKeys = new HashMap<>();
    private static volatile String lastAppKey = "";
    private static volatile long lastAppKeyTs = 0;

    private TimerEngine() {}

    /** 幂等初始化: MainActivity / AgentService 启动时调用。 */
    public static synchronized void init(Context ctx) {
        if (ctx == null) return;
        Context c = ctx.getApplicationContext();
        appCtx = c;
        if (started) return;
        started = true;
        worker = new HandlerThread("timer-engine");
        worker.start();
        workerH = new Handler(worker.getLooper());
        lastAlarmKey = new Prefs(c).raw().getString("timer_last_alarm_key", "");
        if (!lastAlarmKey.isEmpty()) firedAlarmKeys.put(lastAlarmKey, System.currentTimeMillis());
        workerH.postDelayed(alarmTick, 3_000L); // 首次 3s 后进入 30s 循环节拍
        CpLog.i(TAG, "engine started");
    }

    // ================= 1) 闹钟 =================

    private static void checkAlarms() {
        Context c = appCtx;
        if (c == null) return;
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        int mi = now.get(Calendar.MINUTE);
        int dow = now.get(Calendar.DAY_OF_WEEK);
        int dowIdx = (dow == Calendar.SUNDAY) ? 6 : (dow - Calendar.MONDAY); // 0=周一..6=周日
        int dom = now.get(Calendar.DAY_OF_MONTH);
        int maxDom = now.getActualMaximum(Calendar.DAY_OF_MONTH);
        String dateKey = now.get(Calendar.YEAR) + "-" + (now.get(Calendar.MONTH) + 1) + "-" + dom;

        Prefs p = new Prefs(c);
        for (ModelInfo m : p.models()) {
            TimerConfig cfg = p.timer(m.id);
            if (!cfg.alarmConfigured() || cfg.alarmHour != h || cfg.alarmMinute != mi) continue;

            boolean dayOk = cfg.allDays() || (cfg.anyDay() && cfg.alarmDays[dowIdx]);
            boolean monthOk = false;
            for (String s : cfg.alarmMonthDays) {
                try {
                    int d = Integer.parseInt(s.trim());
                    if (d < 1) continue;
                    if (Math.min(d, maxDom) == dom) { monthOk = true; break; } // 超月取最后一天
                } catch (Exception ignored) {}
            }
            if (!dayOk && !monthOk) continue;

            String key = m.id + "|" + dateKey + "|" + h + ":" + mi;
            if (alarmAlreadyFired(key)) continue;
            lastAlarmKey = key;
            p.raw().edit().putString("timer_last_alarm_key", key).apply();
            CpLog.i(TAG, "闹钟触发 agent=" + m.id + " at=" + h + ":" + mi);
            fireForAgent(m.id, cfg.alarmResult, "定时闹钟 " + h + ":" + String.format("%02d", mi));
        }

        // === 默认闹钟触发 (alarmHour < 0): 立即触发一次, 触发后自动关闭 ===
        for (ModelInfo m : p.models()) {
            TimerConfig cfg = p.timer(m.id);
            if (!cfg.alarmEnabled || cfg.alarmHour >= 0) continue;
            String key = m.id + "|" + "default" + "|" + dateKey + "|" + h + ":" + mi;
            if (alarmAlreadyFired(key)) continue;
            lastAlarmKey = key;
            p.raw().edit().putString("timer_last_alarm_key", key).apply();
            CpLog.i(TAG, "默认闹钟触发 agent=" + m.id);
            fireForAgent(m.id, cfg.alarmResult, "默认闹钟 " + h + ":" + mi);
            // 触发后自动关闭
            cfg.alarmEnabled = false;
            p.saveTimer(m.id, cfg);
        }

        // === 会话级闹钟扫描 ===
        SessionStore ss = new SessionStore(c);
        for (ChatSession s : ss.list()) {
            if (s.id == null) continue;
            TimerConfig scfg = p.sessionTimer(s.id);
            if (!scfg.alarmConfigured() || scfg.alarmHour != h || scfg.alarmMinute != mi) continue;
            boolean sdayOk = scfg.allDays() || (scfg.anyDay() && scfg.alarmDays[dowIdx]);
            boolean smonthOk = false;
            for (String sd : scfg.alarmMonthDays) {
                try {
                    int d = Integer.parseInt(sd.trim());
                    if (d < 1) continue;
                    if (Math.min(d, maxDom) == dom) { smonthOk = true; break; }
                } catch (Exception ignored) {}
            }
            if (!sdayOk && !smonthOk) continue;
            String skey = "s" + s.id + "|" + dateKey + "|" + h + ":" + mi;
            if (alarmAlreadyFired(skey)) continue;
            lastAlarmKey = skey;
            p.raw().edit().putString("timer_last_alarm_key", skey).apply();
            CpLog.i(TAG, "闹钟触发[会话级] session=" + s.id + " at=" + h + ":" + mi);
            fireForSession(s.id, scfg.alarmResult, "定时闹钟 " + h + ":" + String.format("%02d", mi));
        }

        // === 会话级默认闹钟触发 ===
        SessionStore ssDef = new SessionStore(c);
        for (ChatSession s : ssDef.list()) {
            if (s.id == null) continue;
            TimerConfig scfg = p.sessionTimer(s.id);
            if (!scfg.alarmEnabled || scfg.alarmHour >= 0) continue;
            String skey = "s" + s.id + "|" + "default" + "|" + dateKey + "|" + h + ":" + mi;
            if (alarmAlreadyFired(skey)) continue;
            lastAlarmKey = skey;
            p.raw().edit().putString("timer_last_alarm_key", skey).apply();
            CpLog.i(TAG, "默认闹钟触发[会话级] session=" + s.id);
            fireForSession(s.id, scfg.alarmResult, "默认闹钟 " + h + ":" + mi);
            scfg.alarmEnabled = false;
            p.saveSessionTimer(s.id, scfg);
        }
    }

    // ================= 2) 事件 =================

    /** AgentService: 任务 done/terminate 时调用。 */
    public static void onTaskDone(Context svcCtx, String agentId, String sessionId,
                                  boolean terminated, String msg) {
        Context c = appCtx != null ? appCtx
                : (svcCtx == null ? null : svcCtx.getApplicationContext());
        if (c == null || agentId == null || sessionId == null) return;
        Prefs p = new Prefs(c);

        // === 1. 会话级触发检查 ===
        // 检查完成的会话自身的 session 级定时器
        TimerConfig selfSessCfg = p.sessionTimer(sessionId);
        if (selfSessCfg.taskDoneEnabled && hitTaskDone(c, selfSessCfg, agentId, sessionId)) {
            CpLog.i(TAG, (terminated ? "任务终止" : "任务完成") + " 触发[会话级] session=" + sessionId);
            fireForSession(sessionId, selfSessCfg.taskDoneResult,
                    (terminated ? "任务终止" : "任务完成") + (msg == null || msg.isEmpty() ? "" : " " + msg));
        }

        // 检查所有其他会话的 session 级定时器
        java.util.List<ChatSession> allSessions = new SessionStore(c).list();
        for (ChatSession s : allSessions) {
            if (s.id == null || s.id.equals(sessionId)) continue;
            TimerConfig sc = p.sessionTimer(s.id);
            if (!sc.taskDoneEnabled) continue;
            if (hitTaskDone(c, sc, agentId, sessionId)) {
                CpLog.i(TAG, (terminated ? "任务终止" : "任务完成") + " 触发[会话级监听] 监听会话=" + s.id + " 源会话=" + sessionId);
                fireForSession(s.id, sc.taskDoneResult,
                        (terminated ? "任务终止" : "任务完成") + (msg == null || msg.isEmpty() ? "" : " " + msg));
            }
        }

        // === 2. 模型级触发检查 ===
        TimerConfig agentCfg = p.timer(agentId);
        if (agentCfg.taskDoneEnabled && hitTaskDone(c, agentCfg, agentId, sessionId)) {
            CpLog.i(TAG, (terminated ? "任务终止" : "任务完成") + " 触发[模型级] agent=" + agentId);
            fireForAgent(agentId, agentCfg.taskDoneResult,
                    (terminated ? "任务终止" : "任务完成") + (msg == null || msg.isEmpty() ? "" : " " + msg));
        }

        // 检查所有其他模型的 agent 级定时器
        for (ModelInfo m : p.models()) {
            if (m.id == null || m.id.equals(agentId)) continue;
            TimerConfig ac = p.timer(m.id);
            if (!ac.taskDoneEnabled) continue;
            if (hitTaskDone(c, ac, agentId, sessionId)) {
                CpLog.i(TAG, (terminated ? "任务终止" : "任务完成") + " 触发[模型级监听] 监听模型=" + m.id + " 源会话=" + sessionId);
                fireForAgent(m.id, ac.taskDoneResult,
                        (terminated ? "任务终止" : "任务完成") + (msg == null || msg.isEmpty() ? "" : " " + msg));
            }
        }
    }

    /**
     * 命中规则:
     *  1. 选了 "任意会话"(__all__)           → 命中
     *  2. 选了具体会话 id                    → 命中
     *  3. 选了 "某模型的全部会话"(agent:<id>) → 完成会话的 agentId 等于该 id 时命中
     */
    private static boolean hitTaskDone(Context c, TimerConfig cfg, String agentId, String sessionId) {
        java.util.List<String> sel = cfg.taskDoneSessions;
        if (sel.contains(TriggerSourcePickerActivity.ALL_SESSIONS)) return true;
        if (sel.contains(sessionId)) return true;
        for (String s : sel) {
            if (s == null || !s.startsWith(TriggerSourcePickerActivity.AGENT_PREFIX)) continue;
            String aid = s.substring(TriggerSourcePickerActivity.AGENT_PREFIX.length());
            if (aid != null && aid.equals(agentId)) return true;
        }
        return false;
    }

    private static boolean alarmAlreadyFired(String key) {
        long now = System.currentTimeMillis();
        synchronized (firedAlarmKeys) {
            java.util.Iterator<Map.Entry<String, Long>> it = firedAlarmKeys.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > 3L * 86400000L) it.remove();
            }
            if (firedAlarmKeys.containsKey(key)) return true;
            firedAlarmKeys.put(key, now);
            return false;
        }
    }

    /** ControlService: 前台窗口变化(某 App 被打开)时调用。 */
    public static void onAppForeground(String pkg) {
        Context c = appCtx;
        if (c == null || pkg == null || pkg.isEmpty()) return;
        if (pkg.startsWith("com.akasha.app")) return;
        long now = System.currentTimeMillis();
        Prefs p = new Prefs(c);
        for (ModelInfo m : p.models()) {
            TimerConfig cfg = p.timer(m.id);
            if (!cfg.appOpenEnabled) continue;
            if (!cfg.appOpenPackages.contains(pkg)) continue;
            String key = m.id + "|" + pkg;
            synchronized (TimerEngine.class) {
                if (key.equals(lastAppKey) && now - lastAppKeyTs < 5_000L) continue;
                lastAppKey = key;
                lastAppKeyTs = now;
            }
            CpLog.i(TAG, "打开软件触发 agent=" + m.id + " pkg=" + pkg);
            fireForAgent(m.id, cfg.appOpenResult, "打开软件 " + pkg);
        }

        // === 会话级 app_open 扫描 ===
        SessionStore ss = new SessionStore(c);
        for (ChatSession s : ss.list()) {
            if (s.id == null) continue;
            TimerConfig scfg = p.sessionTimer(s.id);
            if (!scfg.appOpenEnabled) continue;
            if (!scfg.appOpenPackages.contains(pkg)) continue;
            String skey = "s" + s.id + "|" + pkg;
            synchronized (TimerEngine.class) {
                if (skey.equals(lastAppKey) && now - lastAppKeyTs < 5_000L) continue;
                lastAppKey = skey;
                lastAppKeyTs = now;
            }
            CpLog.i(TAG, "打开软件触发[会话级] session=" + s.id + " pkg=" + pkg);
            fireForSession(s.id, scfg.appOpenResult, "打开软件 " + pkg);
        }
    }

    // ================= 3) 倒计时 =================

    public static synchronized void startCountdown(String agentId, long totalSec, Runnable uiListener) {
        ensureWorker();
        cdAgentId = agentId;
        cdListener = uiListener;
        cdRemainingMs = Math.max(0, totalSec) * 1000L;
        cdFired = false;
        if (!cdRunning) {
            cdRunning = true;
            cdLastMs = System.currentTimeMillis();
            if (workerH != null) workerH.postDelayed(cdTick, 1000L);
        }
        notifyUi();
    }

    public static synchronized void pauseCountdown() {
        cdRunning = false;
        if (workerH != null) workerH.removeCallbacks(cdTick);
        notifyUi();
    }

    public static synchronized void resetCountdown() {
        cdRunning = false;
        cdFired = false;
        if (workerH != null) workerH.removeCallbacks(cdTick);
        cdRemainingMs = 0;
        cdAgentId = null;
        notifyUi();
    }

    public static boolean countdownRunning() { return cdRunning; }

    public static long countdownRemainingMs() { return cdRemainingMs; }

    private static void ensureWorker() {
        if (workerH == null && appCtx != null) {
            worker = new HandlerThread("timer-engine");
            worker.start();
            workerH = new Handler(worker.getLooper());
        }
    }

    private static void notifyUi() {
        final Runnable l = cdListener;
        if (l == null) return;
        new Handler(Looper.getMainLooper()).post(l);
    }

    // ================= 触发执行 =================

    /** 会话级触发: 向已存在的会话发送, 不新建。 */
    public static void fireForSession(String sessionId, TimerConfig.TriggerResult tr, String origin) {
        final Context c = appCtx;
        if (c == null || sessionId == null) {
            CpLog.w(TAG, "fireForSession skipped: no context/session (" + origin + ")");
            return;
        }
        if (tr == null) tr = TimerConfig.TriggerResult.def();
        String agentId = null;
        try {
            ChatSession s = new SessionStore(c).get(sessionId);
            if (s != null) agentId = s.agentId;
        } catch (Exception ignored) {}
        CpLog.i(TAG, "定时器触发[会话级:" + origin + "] session=" + sessionId + " agent=" + agentId);
        boolean suppressTaskDone = isTaskDoneOrigin(origin);

        if (tr.type == TimerConfig.TRIG_SEND_CONTINUE) {
            startSvc(c, AgentService.ACTION_RUN_GOAL, "继续", sessionId, agentId, suppressTaskDone);
            return;
        }

        String text = (tr.message == null || tr.message.trim().isEmpty())
                ? "执行既定任务" : tr.message.trim();
        if (tr.sendMode == TimerConfig.MODE_GUIDE) {
            startSvc(c, AgentService.ACTION_GUIDE, text, sessionId, agentId, suppressTaskDone);
        } else if (tr.sendMode == TimerConfig.MODE_INTERRUPT) {
            startSvc(c, AgentService.ACTION_INT, text, sessionId, agentId, suppressTaskDone);
        } else if (tr.sendMode == TimerConfig.MODE_QUEUE) {
            startSvc(c, AgentService.ACTION_QUEUE, text, sessionId, agentId, suppressTaskDone);
        } else {
            startSvc(c, AgentService.ACTION_RUN_GOAL, text, sessionId, agentId, suppressTaskDone);
        }
    }

    /** 模型级触发: 始终新建会话 + 发送。 */
    public static void fireForAgent(String agentId, TimerConfig.TriggerResult tr, String origin) {
        final Context c = appCtx;
        if (c == null || agentId == null) {
            CpLog.w(TAG, "fireForAgent skipped: no context/agent (" + origin + ")");
            return;
        }
        if (tr == null) tr = TimerConfig.TriggerResult.def();

        String name = agentId;
        for (ModelInfo m : new Prefs(c).models()) {
            if (agentId.equals(m.id)) { name = m.name; break; }
        }
        SessionStore st = new SessionStore(c);
        ChatSession newS = ChatSession.create(agentId, st.uniqueDisplayName(name));
        st.save(newS);
        String newSessionId = newS.id;

        CpLog.i(TAG, "定时器触发[模型级:" + origin + "] agent=" + agentId + " 新会话=" + newSessionId);
        boolean suppressTaskDone = isTaskDoneOrigin(origin);

        // Auto-record timer trigger
        AutoExperienceWriter.get(c).onTimerTriggered(
                agentId, name, newSessionId, "定时触发", "已触发: " + origin);

        if (tr.type == TimerConfig.TRIG_SEND_CONTINUE) {
            startSvc(c, AgentService.ACTION_RUN_GOAL, "继续", newSessionId, agentId, suppressTaskDone);
            return;
        }
        String text = (tr.message == null || tr.message.trim().isEmpty())
                ? "执行既定任务" : tr.message.trim();
        startSvc(c, AgentService.ACTION_RUN_GOAL, text, newSessionId, agentId, suppressTaskDone);
    }

    private static void startSvc(Context c, String action, String text, String sessionId, String agentId) {
        startSvc(c, action, text, sessionId, agentId, false);
    }

    private static void startSvc(Context c, String action, String text, String sessionId, String agentId,
                                 boolean suppressTaskDone) {
        try {
            Intent i = new Intent(c, AgentService.class).setAction(action);
            i.putExtra("text", text == null ? "" : text);
            if (sessionId != null) i.putExtra("sessionId", sessionId);
            if (agentId != null) i.putExtra("agentId", agentId);
            if (suppressTaskDone) i.putExtra("suppressTaskDone", true);
            c.startService(i);
        } catch (Throwable t) {
            CpLog.e(TAG, "startSvc(" + action + "): " + t);
        }
    }

    private static boolean isTaskDoneOrigin(String origin) {
        return origin != null && (origin.contains("任务完成") || origin.contains("任务终止"));
    }
}
