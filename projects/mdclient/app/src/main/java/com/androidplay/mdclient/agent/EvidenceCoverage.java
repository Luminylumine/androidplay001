package com.androidplay.mdclient.agent;

public final class EvidenceCoverage {
    private final int covered; private final int total;
    public EvidenceCoverage(int covered, int total) { if (covered < 0 || total < 0 || covered > total) throw new IllegalArgumentException("coverage"); this.covered = covered; this.total = total; }
    public int getCovered() { return covered; } public int getTotal() { return total; }
    public double ratio() { return total == 0 ? 0.0 : (double) covered / total; }
    public boolean isComplete() { return covered == total; }
}
