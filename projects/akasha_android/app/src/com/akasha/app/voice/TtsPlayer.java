package com.akasha.app.voice;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import com.akasha.app.CpLog;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Streams 24k/mono/PCM16 TTS audio (PROTOCOL.md §2: backend output is
 * canonical, the app does NO format conversion). AudioTrack MODE_STREAM,
 * per-sentence chunk queue. interrupt() clears the queue and silences
 * immediately (barge-in path).
 */
public class TtsPlayer {

    private static final String TAG = "TtsPlayer";
    private static final int SAMPLE_RATE = 24000;
    private static final int CHANNEL = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private AudioTrack track;
    private Thread writer;
    private volatile boolean running = false;

    /** Create the track + writer thread. No audio until enqueue(). */
    public synchronized void init() {
        if (track != null) return;
        int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        if (minBuf <= 0) {
            CpLog.e(TAG, "getMinBufferSize failed: " + minBuf);
            return;
        }
        track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE, CHANNEL, ENCODING,
                Math.max(minBuf, 4096),
                AudioTrack.MODE_STREAM);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            CpLog.e(TAG, "AudioTrack not initialized");
            track.release();
            track = null;
            return;
        }
        running = true;
        writer = new Thread(this::writeLoop, "tts-writer");
        writer.start();
        CpLog.i(TAG, "init ok buf=" + minBuf);
    }

    private void writeLoop() {
        while (running) {
            try {
                byte[] chunk = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (chunk == null) continue;
                int off = 0;
                while (off < chunk.length && running) {
                    int n = track.write(chunk, off, chunk.length - off);
                    if (n <= 0) {
                        CpLog.w(TAG, "write failed n=" + n);
                        break;
                    }
                    off += n;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                CpLog.e(TAG, "writeLoop: " + e);
            }
        }
    }

    /** Enqueue one PCM chunk (any size, 24k mono s16le). */
    public void enqueue(byte[] pcm) {
        if (track == null) return;
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            try { track.play(); } catch (Exception ignored) {}
        }
        queue.offer(pcm);
    }

    /** Clear pending audio and silence immediately (barge-in / turn cancel). */
    public void interrupt() {
        int dropped = 0;
        while (queue.poll() != null) dropped++;
        if (track != null && Build.VERSION.SDK_INT >= 29) {
            try {
                track.flush();
                CpLog.i(TAG, "interrupt: flushed track, dropped=" + dropped);
                return;
            } catch (Exception e) {
                CpLog.w(TAG, "flush failed: " + e);
            }
        }
        CpLog.i(TAG, "interrupt: cleared queue, dropped=" + dropped);
    }

    /** Stop the writer and release the track. Idempotent. */
    public synchronized void release() {
        running = false;
        queue.clear();
        Thread w = writer;
        writer = null;
        if (w != null) {
            try { w.join(1000); } catch (InterruptedException ignored) {}
        }
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.stop(); } catch (Exception ignored) {}
            t.release();
            CpLog.i(TAG, "released");
        }
    }
}
