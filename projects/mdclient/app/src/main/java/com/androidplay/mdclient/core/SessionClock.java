package com.androidplay.mdclient.core;

import android.os.SystemClock;

/** Monotonic timestamps used for ordering a live session. */
public final class SessionClock {
    private SessionClock() {}

    public static long elapsedRealtimeNanos() {
        return SystemClock.elapsedRealtimeNanos();
    }
}
