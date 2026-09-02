package com.androidplay.mdclient.agent;

public final class AgentRequest {
    private final String transcript; private final String context; private final long documentRevision;
    public AgentRequest(String transcript, String context, long documentRevision) { this.transcript = transcript == null ? "" : transcript; this.context = context == null ? "" : context; this.documentRevision = documentRevision; }
    public String getTranscript() { return transcript; } public String getContext() { return context; } public long getDocumentRevision() { return documentRevision; }
}
