package com.androidplay.mdclient.agent;

import java.util.ArrayList;
import java.util.List;

public final class ReplayAgentBackend implements AgentBackend {
    private final List<AgentResponse> responses; private int index;
    public ReplayAgentBackend(List<AgentResponse> responses) { this.responses = new ArrayList<AgentResponse>(responses); }
    public synchronized AgentResponse respond(AgentRequest request) {
        if (index >= responses.size()) return new AgentResponse("", null);
        return responses.get(index++);
    }
    public synchronized int getReplayIndex() { return index; }
}
