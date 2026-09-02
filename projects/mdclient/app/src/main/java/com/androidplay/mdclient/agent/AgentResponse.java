package com.androidplay.mdclient.agent;

public final class AgentResponse {
    private final String text; private final AgentAction action;
    public AgentResponse(String text, AgentAction action) { this.text = text == null ? "" : text; this.action = action; }
    public String getText() { return text; } public AgentAction getAction() { return action; }
}
