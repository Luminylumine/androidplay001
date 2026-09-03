package com.androidplay.mdclient.audio;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.content.pm.ServiceInfo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;

/** Owns the one microphone reader for a lecture session. */
public final class LectureAudioService extends Service {
    public static final String ACTION_START = "com.androidplay.mdclient.audio.START";
    public static final String ACTION_STOP = "com.androidplay.mdclient.audio.STOP";
    public static final String EXTRA_SESSION_ID = "com.androidplay.mdclient.audio.SESSION_ID";
    public static final String EVENT_AUDIO_STARTED = "audio.started";
    public static final String EVENT_AUDIO_STOPPED = "audio.stopped";
    public static final String EVENT_AUDIO_ERROR = "audio.error";
    public static final String EVENT_AUDIO_PERMISSION = "audio.permission";
    public static final String EVENT_AUDIO_TIMESTAMP = "audio.timestamp";
    public static final String EVENT_AUDIO_DROPPED = "audio.dropped";
    public static final String EVENT_AUDIO_CAPTURE_DROP = "audio.capture_drop";
    public static final String EVENT_ASR_CONSUMER_DROP = "audio.asr_consumer_drop";
    public static final String CHANNEL_ID = "lecture_audio";
    public static final int NOTIFICATION_ID = 2401;
    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNELS = 1;
    private static final AudioFrameBus FRAME_BUS = new AudioFrameBus(64);

    public interface EventSink {
        void onAudioEvent(String type, String json);
    }

    private static volatile EventSink eventSink;

    public static void setEventSink(EventSink sink) { eventSink = sink; }
    public static AudioFrameBus audioFrameBus() { return FRAME_BUS; }

    public static Intent startIntent(Context context) {
        return new Intent(context, LectureAudioService.class).setAction(ACTION_START);
    }

    public static Intent startIntent(Context context, String sessionId) {
        return startIntent(context).putExtra(EXTRA_SESSION_ID, sessionId);
    }

    public static Intent stopIntent(Context context) {
        return new Intent(context, LectureAudioService.class).setAction(ACTION_STOP);
    }

    private final Object stateLock = new Object();
    private AudioRecord recorder;
    private Thread readerThread;
    private Thread writerThread;
    private ArrayBlockingQueue<Chunk> queue;
    private volatile boolean reading;
    private File sessionDir;
    private File partFile;
    private BufferedWriter events;
    private long sequence;
    private long pcmBytes;
    private volatile boolean writerFailed;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) start(intent == null ? null : intent.getStringExtra(EXTRA_SESSION_ID));
        else if (ACTION_STOP.equals(action)) stop(true);
        return START_NOT_STICKY;
    }

    private void start(String requestedSession) {
        synchronized (stateLock) {
            if (reading) return;
            if (Build.VERSION.SDK_INT < 29 || Build.VERSION.SDK_INT > 35) {
                emit(EVENT_AUDIO_ERROR, "{\"reason\":\"unsupported_api\",\"api\":" + Build.VERSION.SDK_INT + "}");
                stopSelf();
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                emit(EVENT_AUDIO_PERMISSION, "{\"granted\":false}");
                stopSelf();
                return;
            }
            try {
                createSession(requestedSession);
                createChannel();
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                else startForeground(NOTIFICATION_ID, notification());
                int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IOException("invalid AudioRecord buffer: " + min);
                recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 3200));
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("AudioRecord not initialized");
                queue = new ArrayBlockingQueue<>(64);
                writerFailed = false;
                writerThread = new Thread(this::writeLoop, "lecture-audio-writer");
                writerThread.start();
                recorder.startRecording();
                reading = true;
                final AudioRecord current = recorder;
                readerThread = new Thread(() -> readLoop(current), "lecture-audio-reader");
                readerThread.start();
                emit(EVENT_AUDIO_STARTED, "{\"sampleRate\":16000,\"channels\":1,\"encoding\":\"PCM16\"}");
            } catch (Exception e) {
                emit(EVENT_AUDIO_ERROR, jsonReason(e));
                stop(false);
            }
        }
    }

    private void readLoop(AudioRecord current) {
        short[] samples = new short[1600];
        AudioTimestamp timestamp = new AudioTimestamp();
        long frames = 0;
        long nextTimestamp = SAMPLE_RATE * 5L;
        boolean readFailed = false;
        while (reading && current == recorder) {
            int count = current.read(samples, 0, samples.length);
            if (count < 0) {
                readFailed = true;
                offer(Chunk.event(EVENT_AUDIO_ERROR, "{\"reason\":\"read\",\"code\":" + count + "}"));
                break;
            }
            if (count == 0) continue;
            byte[] pcm = new byte[count * 2];
            ByteBuffer bytes = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) bytes.putShort(samples[i]);
            frames += count;
            if (frames >= nextTimestamp) {
                nextTimestamp += SAMPLE_RATE * 5L;
                int rc = current.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC);
                long elapsed = SystemClock.elapsedRealtimeNanos();
                offer(Chunk.event(EVENT_AUDIO_TIMESTAMP, "{\"frames\":" + frames + ",\"rc\":" + rc
                        + ",\"audioNs\":" + timestamp.nanoTime + ",\"elapsedNs\":" + elapsed
                        + ",\"offsetNs\":" + (timestamp.nanoTime - elapsed) + "}"));
            }
            if (!queue.offer(Chunk.pcm(pcm))) {
                offer(Chunk.event(EVENT_AUDIO_CAPTURE_DROP, "{\"bytes\":" + pcm.length + "}"));
            }
            short[] frame = new short[count];
            System.arraycopy(samples, 0, frame, 0, count);
            if (FRAME_BUS.publish(new AudioFrameBus.Frame(frame, frames - count, frames, timestamp.nanoTime)) > 0)
                offer(Chunk.event(EVENT_ASR_CONSUMER_DROP, "{\"frames\":" + count + "}"));
        }
        if (readFailed && reading && current == recorder) stop(false);
    }

    private void stop(boolean normal) {
        AudioRecord current;
        Thread reader;
        Thread writer;
        synchronized (stateLock) {
            if (!reading && recorder == null && writerThread == null) { stopSelf(); return; }
            reading = false;
            current = recorder;
            reader = readerThread;
            writer = writerThread;
            if (current != null) try { current.stop(); } catch (Exception ignored) { }
        }
        if (reader != null && reader != Thread.currentThread()) join(reader, 1500);
        synchronized (stateLock) {
            if (queue != null) {
                while (!queue.offer(Chunk.end(normal && !writerFailed))) {
                    if (writer == null || !writer.isAlive()) break;
                    try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        if (writer != null && writer != Thread.currentThread()) join(writer, 3000);
        synchronized (stateLock) {
            if (current != null) current.release();
            recorder = null; readerThread = null; writerThread = null; queue = null;
            emit(EVENT_AUDIO_STOPPED, "{\"finalized\":" + normal + "}");
            if (events != null) try { events.close(); } catch (IOException ignored) { }
            events = null;
        }
        stopForeground(true);
        stopSelf();
    }

    private void writeLoop() {
        boolean normal = false;
        try (FileOutputStream out = new FileOutputStream(partFile, false)) {
            out.write(new byte[44]);
            while (true) {
                Chunk chunk = queue.take();
                if (chunk.end) { normal = chunk.normal; break; }
                if (chunk.pcm != null) { out.write(chunk.pcm); pcmBytes += chunk.pcm.length; }
                else writeEvent(chunk.type, chunk.json);
            }
            out.flush();
            if (normal) finalizeWav();
        } catch (Exception e) {
            writerFailed = true;
            emit(EVENT_AUDIO_ERROR, jsonReason(e));
        }
    }

    private void createSession(String requested) throws IOException {
        String id = requested == null || requested.length() == 0 ? "lecture-" + System.currentTimeMillis() : requested.replaceAll("[^A-Za-z0-9._-]", "_");
        sessionDir = new File(new File(getFilesDir(), "sessions"), id);
        if (!sessionDir.mkdirs() && !sessionDir.isDirectory()) throw new IOException("cannot create session directory");
        partFile = new File(sessionDir, "audio.pcm.part");
        events = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(sessionDir, "events.jsonl"), true), StandardCharsets.UTF_8));
        pcmBytes = 0;
    }

    private void finalizeWav() throws IOException {
        File wav = new File(sessionDir, "audio.wav");
        try (RandomAccessFile file = new RandomAccessFile(partFile, "rw")) {
            file.seek(0); file.write(wavHeader(pcmBytes));
        }
        if (!partFile.renameTo(wav)) throw new IOException("cannot finalize WAV");
    }

    private byte[] wavHeader(long dataSize) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'}).putInt((int) (36 + dataSize)).put(new byte[]{'W','A','V','E','f','m','t',' '});
        b.putInt(16).putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put(new byte[]{'d','a','t','a'}).putInt((int) dataSize); return b.array();
    }

    private void offer(Chunk item) { if (queue != null) queue.offer(item); }
    private void writeEvent(String type, String json) { emit(type, json); }
    private synchronized void emit(String type, String json) {
        EventSink sink = eventSink; if (sink != null) try { sink.onAudioEvent(type, json); } catch (RuntimeException ignored) { }
        if (events != null) try { events.write("{\"seq\":" + (++sequence) + ",\"eventTimeNs\":" + SystemClock.elapsedRealtimeNanos() + ",\"type\":\"" + type + "\",\"data\":" + json + "}"); events.newLine(); events.flush(); } catch (IOException ignored) { }
    }

    private String jsonReason(Exception e) { return "{\"reason\":\"" + e.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"; }
    private void join(Thread t, long ms) { try { t.join(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    private void createChannel() { NotificationManager n = getSystemService(NotificationManager.class); n.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Lecture audio", NotificationManager.IMPORTANCE_LOW)); }
    private Notification notification() { return new Notification.Builder(this, CHANNEL_ID).setContentTitle("Lecture audio").setContentText("Recording").setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build(); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { stop(false); super.onDestroy(); }

    private static final class Chunk {
        final byte[] pcm; final String type; final String json; final boolean end; final boolean normal;
        private Chunk(byte[] p, String t, String j, boolean e, boolean n) { pcm=p; type=t; json=j; end=e; normal=n; }
        static Chunk pcm(byte[] p) { return new Chunk(p, null, null, false, false); }
        static Chunk event(String t, String j) { return new Chunk(null, t, j, false, false); }
        static Chunk end(boolean n) { return new Chunk(null, null, null, true, n); }
    }
}
