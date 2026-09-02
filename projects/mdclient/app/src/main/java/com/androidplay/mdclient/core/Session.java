package com.androidplay.mdclient.core;

public final class Session {
    public final String id;
    public final String courseId;
    public final long startedElapsedNs;
    public final long endedElapsedNs;
    public final long startedWallMs;
    public final long endedWallMs;
    public final String status;

    public Session(String id, String courseId, long startedElapsedNs, long endedElapsedNs,
                   long startedWallMs, long endedWallMs, String status) {
        this.id = id; this.courseId = courseId; this.startedElapsedNs = startedElapsedNs;
        this.endedElapsedNs = endedElapsedNs; this.startedWallMs = startedWallMs;
        this.endedWallMs = endedWallMs; this.status = status;
    }
}
