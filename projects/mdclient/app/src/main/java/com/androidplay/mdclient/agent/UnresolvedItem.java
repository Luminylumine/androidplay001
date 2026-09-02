package com.androidplay.mdclient.agent;

public final class UnresolvedItem {
    private final String id; private final String text; private final String reason;
    public UnresolvedItem(String id, String text, String reason) { this.id = id; this.text = text; this.reason = reason; }
    public String getId() { return id; } public String getText() { return text; } public String getReason() { return reason; }
}
