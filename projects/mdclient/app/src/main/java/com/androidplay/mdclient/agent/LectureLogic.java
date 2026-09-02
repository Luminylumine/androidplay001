package com.androidplay.mdclient.agent;

/** Deterministic policy boundary between lecture events and fast/slow agent work. */
public final class LectureLogic {
    private final FastAgentController fast;
    private final SuggestionQueue suggestions;
    public LectureLogic(FastAgentController fast, SuggestionQueue suggestions) { this.fast = fast; this.suggestions = suggestions; }
    public AgentResponse onTranscript(String text, boolean isFinal, long nowMs, String context, long revision) {
        AgentResponse response = fast.onTranscript(text, isFinal, nowMs, context, revision);
        if (response != null && response.getAction() != null) suggestions.offer(new Suggestion(null, response.getText(), response.getAction()));
        return response;
    }
    public SuggestionQueue getSuggestions() { return suggestions; }
}
