package com.androidplay.mdclient.transcription;

import android.os.Handler;
import android.os.Looper;
import com.androidplay.mdclient.core.SessionClock;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Replays JSONL transcript fixtures at a controllable processing speed. */
public final class ReplayTranscriptionProvider implements TranscriptionProvider {
    private final InputStream fixture;
    private final double speed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean running;

    public ReplayTranscriptionProvider(InputStream fixture, double speed) {
        if (fixture == null || speed <= 0) throw new IllegalArgumentException("fixture/speed");
        this.fixture = fixture; this.speed = speed;
    }

    @Override public synchronized void start(final String sessionId, final Listener listener) {
        stop(); running = true;
        new Thread(() -> replay(listener), "mdclient-replay-asr").start();
    }

    private void replay(final Listener listener) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(fixture, StandardCharsets.UTF_8))) {
            String line; long delay = 0;
            while (running && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JSONObject json = new JSONObject(line);
                final String id = json.optString("id", "replay-" + delay);
                final String text = json.optString("text", json.optString("transcript", ""));
                final boolean isFinal = json.optBoolean("final", "final".equals(json.optString("type")));
                final String replaces = json.has("replacesSegmentId") ? json.optString("replacesSegmentId") : null;
                delay += Math.max(0, json.optLong("delayMs", 200));
                long processingDelay = (long) (delay / speed);
                handler.postDelayed(() -> { if (running) listener.onSegment(new TranscriptSegment(id, "replay", 0, 0, SessionClock.elapsedRealtimeNanos(), text, isFinal, replaces)); }, processingDelay);
            }
        } catch (Exception e) {
            handler.post(() -> { if (running) listener.onError("ASR_REPLAY_FAILED", e.toString()); });
        }
    }

    @Override public void acceptAudio(short[] samples, long frameStart, long frameEnd, long audioTimeNs) { }

    @Override public synchronized void stop() { running = false; handler.removeCallbacksAndMessages(null); }
}
