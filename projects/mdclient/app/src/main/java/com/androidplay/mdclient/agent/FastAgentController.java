package com.androidplay.mdclient.agent;

/** Synchronous controller: only final transcript segments can invoke the backend. */
public final class FastAgentController {
    private final AgentBackend backend;
    private final long minIntervalMs;
    private long lastRequestMs = Long.MIN_VALUE;
    public FastAgentController(AgentBackend backend, long minIntervalMs) {
        if (backend == null || minIntervalMs < 0) throw new IllegalArgumentException("backend/policy");
        this.backend = backend; this.minIntervalMs = minIntervalMs;
    }
    public AgentResponse onTranscript(String transcript, boolean isFinal, long nowMs, String context, long revision) {
        if (!isFinal || transcript == null || transcript.trim().isEmpty()) return null;
        if (lastRequestMs != Long.MIN_VALUE && nowMs - lastRequestMs < minIntervalMs) return null;
        lastRequestMs = nowMs;
        return backend.respond(new AgentRequest(transcript, context, revision));
    }
    public AgentResponse onFinalTranscript(String transcript, long nowMs, String context, long revision) { return onTranscript(transcript, true, nowMs, context, revision); }
    public long getLastRequestMs() { return lastRequestMs; }
}
