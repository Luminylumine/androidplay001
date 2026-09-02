package com.androidplay.mdclient.agent;

public final class SlowConsolidator {
    private final AgentBackend backend;
    public SlowConsolidator(AgentBackend backend) { if (backend == null) throw new IllegalArgumentException("backend"); this.backend = backend; }
    public AgentResponse consolidate(String transcript, String context, long revision) {
        return backend.respond(new AgentRequest(transcript, context, revision));
    }
}
