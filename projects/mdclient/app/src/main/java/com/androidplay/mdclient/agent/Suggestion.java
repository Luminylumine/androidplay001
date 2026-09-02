package com.androidplay.mdclient.agent;

public final class Suggestion {
    private final String id; private final String text; private final AgentAction action;
    public Suggestion(String id, String text, AgentAction action) { this.id = id; this.text = text == null ? "" : text; this.action = action; }
    public String getId() { return id; } public String getText() { return text; } public AgentAction getAction() { return action; }
}
