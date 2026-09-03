package com.androidplay.mdclient.transcription;

import android.os.Handler;
import android.os.Looper;
import com.androidplay.mdclient.core.SessionClock;

/** Deterministic provider for UI/replay development; it never opens a microphone. */
public final class FakeTranscriptionProvider implements TranscriptionProvider {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean running;

    @Override public synchronized void start(String sessionId, Listener listener) {
        stop(); this.listener = listener; running = true;
        emit("fake-1", "今天我们讲傅里", false, 500, null);
        emit("fake-2", "今天我们讲傅里叶变换", true, 1200, "fake-1");
    }

    private void emit(final String id, final String text, final boolean isFinal, long delay, final String replaces) {
        handler.postDelayed(() -> { Listener current = listener; if (running && current != null) current.onSegment(new TranscriptSegment(id, "fake", 0, 0, SessionClock.elapsedRealtimeNanos(), text, isFinal, replaces)); }, delay);
    }

    @Override public void acceptAudio(short[] samples, long frameStart, long frameEnd, long audioTimeNs) { }

    @Override public synchronized void stop() { running = false; listener = null; handler.removeCallbacksAndMessages(null); }
}
