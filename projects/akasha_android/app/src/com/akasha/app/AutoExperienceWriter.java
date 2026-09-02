package com.akasha.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto experience writer: collects experiences during Agent operation
 * and writes them to the experience pool with deduplication and importance scoring.
 * 
 * Thread-safe singleton with a pending queue that gets flushed when AgentService is idle.
 */
public class AutoExperienceWriter {

    private static volatile AutoExperienceWriter inst;
    private Context ctx;
    private final List<PendingEntry> pending = new ArrayList<>();
    private int writtenThisRound = 0;
    private static final int MAX_PER_ROUND = 2;

    public static AutoExperienceWriter get(Context ctx) {
        if (inst == null) {
            synchronized (AutoExperienceWriter.class) {
                if (inst == null) inst = new AutoExperienceWriter(ctx.getApplicationContext());
            }
        }
        return inst;
    }

    private AutoExperienceWriter(Context ctx) {
        this.ctx = ctx;
    }

    /** Reset per-round counter. Called at start of each Agent run. */
    public void resetRound() {
        writtenThisRound = 0;
    }

    /**
     * Queue an auto-experience for writing. Won't write immediately - will batch on flush.
     * @param importance 0.0-1.0
     */
    public void queue(String agentId, String agentName, String title,
                      String content, double importance, String sourceSessionId, String tags) {
        if (agentId == null || title == null || title.trim().isEmpty()) return;
        PendingEntry e = new PendingEntry();
        e.agentId = agentId;
        e.agentName = agentName == null ? "" : agentName;
        e.title = title.trim();
        e.content = content == null ? "" : content.trim();
        e.importance = Math.max(0, Math.min(1.0, importance));
        e.sourceSessionId = sourceSessionId == null ? "" : sourceSessionId;
        e.tags = tags == null ? "" : tags;
        synchronized (pending) {
            pending.add(e);
        }
    }

    /** Flush pending entries to DB. Called when AgentService goes idle. */
    public void flush() {
        List<PendingEntry> toWrite;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            toWrite = new ArrayList<>(pending);
            pending.clear();
        }
        
        ExpStore store = new ExpStore(ctx);
        int written = 0;
        int i = 0;
        for (; i < toWrite.size() && written < MAX_PER_ROUND; i++) {
            PendingEntry e = toWrite.get(i);
            try {
                Experience result = store.autoRecord(
                        e.agentId, e.agentName, e.title, e.content,
                        e.importance, e.sourceSessionId, e.tags);
                if (result != null) {
                    written++;
                    writtenThisRound++;
                    CpLog.i("AutoExp", "auto-wrote exp id=" + result.id +
                            " importance=" + e.importance + " title=" + e.title);
                }
            } catch (Throwable t) {
                synchronized (pending) { pending.add(e); }
                CpLog.w("AutoExp", "auto-write failed: " + t);
            }
        }
        if (i < toWrite.size()) {
            synchronized (pending) {
                for (int j = toWrite.size() - 1; j >= i; j--) pending.add(0, toWrite.get(j));
            }
        }
        if (written > 0) {
            CpLog.i("AutoExp", "flushed " + written + " auto experiences");
        }
    }

    // ============ Convenience methods for different hook points ============

    /** Agent successfully completed a task. */
    public void onTaskSuccess(String agentId, String agentName, String sessionId,
                               String goal, String steps, String result) {
        String title = "成功: " + (goal.length() > 30 ? goal.substring(0, 30) + "…" : goal);
        String content = "任务: " + goal + "\n做法: " + (steps == null ? "见对话记录" : steps) +
                "\n结果: " + (result == null ? "成功" : result);
        queue(agentId, agentName, title, content, 0.6, sessionId, "task_success");
    }

    /** Agent recovered from an error. */
    public void onTaskRecovered(String agentId, String agentName, String sessionId,
                                 String error, String fix) {
        String title = "避坑: " + (error.length() > 30 ? error.substring(0, 30) + "…" : error);
        String content = "问题: " + error + "\n解决: " + (fix == null ? "重试或换方法" : fix);
        queue(agentId, agentName, title, content, 0.8, sessionId, "error_recovery");
    }

    /** User confirmed a preference. */
    public void onUserConfirmed(String agentId, String agentName, String sessionId,
                                 String preference) {
        String title = "用户偏好: " + (preference.length() > 30 ? preference.substring(0, 30) + "…" : preference);
        String content = "用户确认: " + preference;
        queue(agentId, agentName, title, content, 0.9, sessionId, "user_preference,constraint");
    }

    /** Tool execution hit a system restriction. */
    public void onToolRestricted(String agentId, String agentName, String sessionId,
                                  String restriction, String workAround) {
        String title = "系统限制: " + (restriction.length() > 30 ? restriction.substring(0, 30) + "…" : restriction);
        String content = "限制: " + restriction + "\n绕行: " + (workAround == null ? "需 shell 权限或 root" : workAround);
        queue(agentId, agentName, title, content, 0.7, sessionId, "system_restriction,device");
    }

    /** Timer triggered an action. */
    public void onTimerTriggered(String agentId, String agentName, String sessionId,
                                  String trigger, String result) {
        String title = "触发: " + (trigger.length() > 30 ? trigger.substring(0, 30) + "…" : trigger);
        String content = "触发源: " + trigger + "\n结果: " + (result == null ? "已触发" : result);
        queue(agentId, agentName, title, content, 0.4, sessionId, "timer_trigger");
    }

    private static class PendingEntry {
        String agentId;
        String agentName;
        String title;
        String content;
        double importance;
        String sourceSessionId;
        String tags;
    }
}
