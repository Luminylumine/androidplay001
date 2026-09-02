package com.androidplay.mdclient.agent;

public final class AgentAction {
    public enum Type { SUGGEST, INSERT, REPLACE, CONSOLIDATE }
    private final Type type; private final String targetBlockId; private final String text;
    private final long documentRevision; private final long createdAtMs;
    public AgentAction(Type type, String targetBlockId, String text, long documentRevision, long createdAtMs) {
        if (type == null) throw new IllegalArgumentException("type");
        this.type = type; this.targetBlockId = targetBlockId; this.text = text == null ? "" : text;
        this.documentRevision = documentRevision; this.createdAtMs = createdAtMs;
    }
    public Type getType() { return type; } public String getTargetBlockId() { return targetBlockId; }
    public String getText() { return text; } public long getDocumentRevision() { return documentRevision; }
    public long getCreatedAtMs() { return createdAtMs; }
}
