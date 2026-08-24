package com.akasha.app;

/**
 * Scheduler abstraction for the future timer phase (定时器) and event phase
 * (事件唤醒/通知). The next phases implement a concrete scheduler (e.g. on
 * AlarmManager / WorkManager) and register it here; today a no-op keeps the
 * interface stable and callable.
 */
public interface AgentScheduler {

    /** Schedule a one-shot wake-up at the given epoch millis. */
    void scheduleAt(long when, String sessionId, String reason);

    /** Schedule a repeating wake-up every periodMs starting at firstAt. */
    void scheduleRepeating(long firstAt, long periodMs, String sessionId, String reason);

    /** Cancel everything for a session (or all if sessionId is null). */
    void cancel(String sessionId);

    /** No-op implementation used until the timer phase lands. */
    class Noop implements AgentScheduler {
        @Override
        public void scheduleAt(long when, String sessionId, String reason) {}

        @Override
        public void scheduleRepeating(long firstAt, long periodMs, String sessionId, String reason) {}

        @Override
        public void cancel(String sessionId) {}
    }
}
