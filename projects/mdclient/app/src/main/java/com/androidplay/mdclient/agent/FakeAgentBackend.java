package com.androidplay.mdclient.agent;

public final class FakeAgentBackend implements AgentBackend {
    private final String response;
    public FakeAgentBackend() { this("fake suggestion"); }
    public FakeAgentBackend(String response) { this.response = response == null ? "" : response; }
    public AgentResponse respond(AgentRequest request) { return new AgentResponse(response, null); }
}
