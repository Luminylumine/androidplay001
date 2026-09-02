package com.androidplay.mdclient.agent;

public final class FreshnessValidator {
    private final FreshnessPolicy policy;
    public FreshnessValidator(FreshnessPolicy policy) { this.policy = policy == null ? new FreshnessPolicy() : policy; }
    public boolean isFresh(AgentAction action, long nowMs, long currentRevision, long lastActionMs) {
        if (action == null || nowMs < action.getCreatedAtMs()) return false;
        if (nowMs - action.getCreatedAtMs() > policy.getMaxAgeMs()) return false;
        if (action.getDocumentRevision() != currentRevision) return false;
        return lastActionMs < 0 || nowMs - lastActionMs >= policy.getMinActionIntervalMs();
    }
    public boolean isFresh(AgentAction action, long nowMs, long currentRevision) { return isFresh(action, nowMs, currentRevision, -1L); }
}
