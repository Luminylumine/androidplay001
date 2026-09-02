package com.androidplay.mdclient.agent;

public final class FreshnessPolicy {
    private final long maxAgeMs;
    private final long minActionIntervalMs;
    public FreshnessPolicy() { this(30000L, 1000L); }
    public FreshnessPolicy(long maxAgeMs, long minActionIntervalMs) {
        if (maxAgeMs < 0 || minActionIntervalMs < 0) throw new IllegalArgumentException("negative policy");
        this.maxAgeMs = maxAgeMs; this.minActionIntervalMs = minActionIntervalMs;
    }
    public long getMaxAgeMs() { return maxAgeMs; }
    public long getMinActionIntervalMs() { return minActionIntervalMs; }
}
