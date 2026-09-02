package com.akasha.app.voice;

import android.content.Context;
import android.content.Intent;

import com.akasha.app.AgentService;
import com.akasha.app.ChatSession;
import com.akasha.app.CpLog;
import com.akasha.app.Prefs;
import com.akasha.app.SessionStore;

/**
 * Task 9 (FR-6): bridges the /ws/call session with AgentService.
 *
 * Downlink (server -> App):
 *   task_goal -> create/join voice session (voiceMode) -> AgentService ACTION_RUN_GOAL
 *
 * Uplink (AgentService -> server, via task_event frames):
 *   say / ask_user / done / error  (AgentEventListener)
 *
 * ask_user voice loop: while the agent waits for an answer
 * (AgentService.currentQuestion != null), the next ASR final is routed as
 * ACTION_ANSWER. Note: the v1 call state machine has no "task" state, so the
 * brain turn on the same final still happens server-side — accepted limitation.
 *
 * Hangup does NOT kill the task (FR-6.3): detach() only removes listeners;
 * AgentService keeps running to done.
 */
public final class TaskBridge implements VoiceCallService.StateListener,
        AgentService.AgentEventListener {

    private static final String TAG = "TaskBridge";
    private static final TaskBridge INST = new TaskBridge();

    public static TaskBridge get() { return INST; }

    private Context appCtx;
    private VoiceCallService svc;
    private Prefs prefs;
    private SessionStore store;
    private String voiceSessionId = null;
    private String currentTaskId = null;
    private boolean attached = false;

    private TaskBridge() {}

    /** Called by VoiceCallService.startCall(). Idempotent-safe. */
    public synchronized void attach(VoiceCallService s) {
        if (attached) detach();
        appCtx = s.getApplicationContext();
        svc = s;
        prefs = new Prefs(appCtx);
        store = new SessionStore(appCtx);

        // create/join voice session (session visibility, FR-6):
        // reuse the caller-provided akashaSessionId when it exists, else new
        String join = s.getAkashaSessionId();
        if (join != null && !join.isEmpty() && store.get(join) != null) {
            voiceSessionId = join;
        } else {
            ChatSession cs = ChatSession.create(prefs.model(),
                    store.uniqueDisplayName("语音通话"));
            store.save(cs);
            voiceSessionId = cs.id;
        }
        attached = true;
        s.addStateListener(this);
        AgentService.addAgentListener(this);
        CpLog.i(TAG, "attach session=" + voiceSessionId
                + " join=" + (join != null && !join.isEmpty()));
    }

    /** Called on hangup. Task keeps running (FR-6.3). */
    public synchronized void detach() {
        if (!attached) return;
        attached = false;
        if (svc != null) svc.removeStateListener(this);
        AgentService.removeAgentListener(this);
        CpLog.i(TAG, "detach session=" + voiceSessionId + " (task keeps running)");
        svc = null;
        voiceSessionId = null;
        currentTaskId = null;
    }

    // ---------------- downlink: task_goal ----------------

    @Override
    public void onTaskGoal(String taskId, String goal, String akashaSessionId) {
        if (!attached || appCtx == null) return;
        currentTaskId = taskId;
        CpLog.i(TAG, "task_goal id=" + taskId + " goal=" + goal);
        Intent i = new Intent(appCtx, AgentService.class)
                .setAction(AgentService.ACTION_RUN_GOAL);
        i.putExtra("text", goal == null ? "" : goal);
        i.putExtra("sessionId", voiceSessionId);
        i.putExtra("voiceMode", true);
        // agentId omitted: AgentService resolves it from the session
        appCtx.startService(i);
    }

    // ---------------- ASR transcript -> session / agent answer ----------------

    @Override
    public void onFinal(String text) {
        if (text == null || text.trim().isEmpty()) return;
        // transcript persistence: user line in the voice session
        if (voiceSessionId != null && store != null) {
            try {
                store.appendChat(voiceSessionId, "user", text,
                        System.currentTimeMillis(), "{\"voice\":true}");
            } catch (Exception e) {
                CpLog.w(TAG, "appendChat transcript: " + e);
            }
        }
        // agent is waiting for an answer -> route this final as the voice answer
        if (AgentService.currentQuestion != null && appCtx != null) {
            CpLog.i(TAG, "final routed to agent answer: " + text);
            Intent i = new Intent(appCtx, AgentService.class)
                    .setAction(AgentService.ACTION_ANSWER);
            i.putExtra("text", text);
            appCtx.startService(i);
        }
    }

    // ---------------- uplink: agent events -> task_event ----------------

    @Override
    public void onSay(String text) {
        uplink("say", text);
    }

    @Override
    public void onAskUser(String question) {
        uplink("ask_user", question);
    }

    @Override
    public void onDone(boolean terminated, String message) {
        uplink("done", terminated ? "任务终止: " + message : "任务完成: " + message);
        currentTaskId = null;
    }

    /**
     * Shared signature from BOTH interfaces (identical erasure):
     * - AgentService.AgentEventListener.onError -> LLM_ERROR / TOOL_FAIL -> uplink
     * - VoiceCallService.StateListener.onError -> server call error (UI shows it;
     *   VoiceCallActivity is a separate listener)
     */
    @Override
    public void onError(String code, String message) {
        if ("LLM_ERROR".equals(code) || "TOOL_FAIL".equals(code)) {
            uplink("error", message);
        } else {
            CpLog.w(TAG, "call error " + code + ": " + message);
        }
    }

    private void uplink(String kind, String text) {
        VoiceCallService s = svc;
        if (!attached || s == null) return;
        s.sendTaskEvent(kind, text, currentTaskId);
    }

    // ---------------- unused StateListener slots ----------------

    @Override
    public void onCallState(String state) { /* UI only (VoiceCallActivity) */ }

    @Override
    public void onPartial(String text) { /* UI only */ }

    @Override
    public void onBrainText(String text) { /* UI only */ }

    @Override
    public void onFirstAudioLatency(long ms) { /* UI only */ }

    @Override
    public void onCallEnded() { /* hangUp() already detached us */ }
}
