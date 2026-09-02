package com.akasha.app.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import com.akasha.app.CpLog;
import com.akasha.app.Prefs;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Voice call foreground service: mic capture (16k mono PCM16, 100ms frames)
 * uplink + /ws/call session (PROTOCOL.md §3) + TTS playback.
 *
 * Mic lifecycle (voice-bridge §22): recording is only allowed while the call
 * is active; every stop path (user hangup / WS disconnect / service destroy /
 * exception) must stop()+release() the AudioRecord. No background listening.
 */
public class VoiceCallService extends Service {

    private static final String TAG = "VoiceCall";
    private static final String CHANNEL_ID = "voice_call";
    private static final int NOTIF_ID = 1;

    private static final int MIC_RATE = 16000;
    private static final int MIC_FRAME_BYTES = 3200; // 100ms @16k s16 mono
    private static final int LOG_EVERY_FRAMES = 100;  // ~10s

    public static final String ACTION_START = "com.akasha.app.voice.START";
    public static final String ACTION_HANGUP = "com.akasha.app.voice.HANGUP";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_SESSION = "akashaSessionId";

    /** UI hooks (Task 8 wires VoiceCallActivity; Task 9 wires TaskBridge). */
    public interface StateListener {
        void onCallState(String state);          // listening/thinking/speaking/ended
        void onPartial(String text);
        void onFinal(String text);
        void onBrainText(String text);
        void onTaskGoal(String taskId, String goal, String akashaSessionId);
        void onError(String code, String message);
        void onFirstAudioLatency(long ms);       // final -> first TTS PCM, per turn
        void onCallEnded();                      // call is over (any end path)
    }

    public String getAkashaSessionId() {
        return akashaSessionId;
    }

    /** Uplink an agent task event (PROTOCOL.md §3.1 task_event). Task 9. */
    public void sendTaskEvent(String kind, String text, String taskId) {
        CallWsClient w = ws;
        if (w == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("type", "task_event");
            o.put("kind", kind);
            o.put("text", text == null ? "" : text);
            if (taskId != null && !taskId.isEmpty()) o.put("taskId", taskId);
            w.sendText(o.toString());
        } catch (Exception e) {
            CpLog.w(TAG, "task_event uplink failed: " + e);
        }
    }

    // ---------------- UI state (Task 8, polled by VoiceCallActivity) ----------------

    public boolean serverOk() {
        CallWsClient w = ws;
        return w != null && w.isConnected();
    }

    public boolean micOk() {
        AudioRecord r = record;
        return r != null && r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
    }

    public boolean ttsOk() {
        return tts != null;
    }

    public boolean isHungUp() {
        return hungUp;
    }

    private static volatile VoiceCallService inst = null;
    public static VoiceCallService get() { return inst; }
    public static boolean active() { return inst != null; }

    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private CallWsClient ws;
    private TtsPlayer tts;
    private AudioRecord record;
    private Thread micThread;
    private volatile boolean micRunning = false;
    private volatile boolean hungUp = false;
    private volatile String callState = "idle";
    private volatile long lastFinalAt = 0;      // SystemClock at last final (turn start)
    private volatile boolean turnAudioReported = false;
    private String host = "127.0.0.1:8765";
    private String token = "";
    private String akashaSessionId = "";
    private String callId = UUID.randomUUID().toString();

    public void addStateListener(StateListener l) { listeners.add(l); }
    public void removeStateListener(StateListener l) { listeners.remove(l); }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "语音通话", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        CpLog.i(TAG, "onStartCommand action=" + action);
        if (ACTION_HANGUP.equals(action)) {
            hangUp("user");
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && intent != null) {
            host = intent.getStringExtra(EXTRA_HOST);
            if (host == null || host.isEmpty()) host = "127.0.0.1:8765";
            token = intent.getStringExtra(EXTRA_TOKEN);
            if (token == null) token = "";
            akashaSessionId = intent.getStringExtra(EXTRA_SESSION);
            if (akashaSessionId == null) akashaSessionId = "";
            startCall();
        }
        return START_NOT_STICKY;
    }

    private void startCall() {
        if (ws != null || tts != null) {
            CpLog.w(TAG, "startCall ignored: call already active");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            CpLog.e(TAG, "startCall: RECORD_AUDIO not granted, abort");
            for (StateListener l : listeners)
                l.onError("PERMISSION", "RECORD_AUDIO not granted");
            hangUp("permission");
            return;
        }
        hungUp = false;
        callId = UUID.randomUUID().toString();
        callState = "idle";
        boolean ttsOn = new Prefs(this).voiceTtsOn();
        tts = ttsOn ? new TtsPlayer() : null;
        if (tts != null) tts.init();
        lastFinalAt = 0;
        ws = new CallWsClient();
        ws.addListener(wsListener);
        startForegroundWithType();
        TaskBridge.get().attach(this);
        if (!startMic()) {
            hangUp("mic_start_failed");
            return;
        }
        ws.start(host, token);
        CpLog.i(TAG, "call started id=" + callId + " host=" + host);
    }

    private void startForegroundWithType() {
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Akasha")
                .setContentText("语音通话中")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            // FOREGROUND_SERVICE_TYPE_MICROPHONE (128) is an API-30 constant;
            // the android-29 stub lacks it, use the literal.
            startForeground(NOTIF_ID, n, 128 /* FOREGROUND_SERVICE_TYPE_MICROPHONE */);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    // ---------------- mic (uplink) ----------------

    private boolean startMic() {
        int minBuf = AudioRecord.getMinBufferSize(MIC_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            CpLog.e(TAG, "startMic: getMinBufferSize=" + minBuf);
            return false;
        }
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, MIC_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, MIC_FRAME_BYTES));
        } catch (Exception e) {
            CpLog.e(TAG, "startMic: AudioRecord ctor failed: " + e);
            return false;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            CpLog.e(TAG, "startMic: not initialized");
            releaseMic("ctor_failed");
            return false;
        }
        micRunning = true;
        try {
            record.startRecording();
        } catch (Exception e) {
            CpLog.e(TAG, "startMic: startRecording failed: " + e);
            micRunning = false;
            releaseMic("start_failed");
            return false;
        }
        micThread = new Thread(this::micLoop, "mic-capture");
        micThread.start();
        CpLog.i(TAG, "mic started rate=" + MIC_RATE + " buf=" + minBuf);
        return true;
    }

    private void micLoop() {
        byte[] frame = new byte[MIC_FRAME_BYTES];
        long t0 = SystemClockNow();
        int frames = 0;
        try {
            while (micRunning) {
                int n = record.read(frame, 0, frame.length);
                if (n < 0) throw new IllegalStateException("read err=" + n);
                if (hungUp) break;
                CallWsClient w = ws; // may be nulled by hangUp() concurrently
                if (w != null) w.sendBinary(frame);
                frames++;
                if (frames % LOG_EVERY_FRAMES == 0) {
                    long elapsed = SystemClockNow() - t0;
                    double avgMs = (double) elapsed / frames;
                    CpLog.i(TAG, "mic frame rate avg=" + String.format("%.1f", avgMs)
                            + "ms/frame (expect ~100) over " + frames + " frames");
                    frames = 0;
                    t0 = SystemClockNow();
                }
            }
        } catch (Exception e) {
            if (micRunning) {
                CpLog.e(TAG, "mic loop exception: " + e);
                // §22 stop condition: exception -> full release
                hangUp("mic_exception:" + e);
            }
        }
        if (micRunning) releaseMic("loop_exit");
    }

    /** §22: every path must stop()+release(). Never skip release. */
    private void releaseMic(String reason) {
        micRunning = false;
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
            r.release();
            CpLog.i(TAG, "AudioRecord released (reason=" + reason + ")");
        }
        Thread t = micThread;
        micThread = null;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(2000); } catch (InterruptedException ignored) {}
        }
    }

    // ---------------- ws (session) ----------------

    private final CallWsClient.Listener wsListener = new CallWsClient.Listener() {
        @Override
        public void onConnected() {
            sendStartFrame();
        }

        @Override
        public void onDisconnected(int code, String reason) {
            if (hungUp) return;
            // §54: connection layer reconnects (backoff handled by client);
            // mic stays up, a new start frame resumes the call.
            CpLog.i(TAG, "ws disconnected (reconnect in progress) code=" + code);
        }

        @Override
        public void onText(String text) {
            handleFrame(text);
        }

        @Override
        public void onBinary(byte[] data) {
            TtsPlayer t = tts;
            if (t != null) t.enqueue(data);
        }
    };

    private void sendStartFrame() {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "start");
            o.put("callId", callId);
            o.put("sampleRate", 16000);
            o.put("channels", 1);
            o.put("format", "pcm_s16le");
            if (!akashaSessionId.isEmpty()) o.put("akashaSessionId", akashaSessionId);
            ws.sendText(o.toString());
            CpLog.i(TAG, "start frame sent");
        } catch (Exception e) {
            CpLog.e(TAG, "start frame failed: " + e);
        }
    }

    private void handleFrame(String text) {
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (Exception e) {
            CpLog.w(TAG, "bad json frame: " + text);
            return;
        }
        String type = o.optString("type", "");
        switch (type) {
            case "session": {
                String state = o.optString("state", "");
                if (!state.equals(callState)) {
                    CpLog.i(TAG, "state " + callState + " -> " + state);
                    callState = state;
                }
                for (StateListener l : listeners) l.onCallState(state);
                break;
            }
            case "partial":
                for (StateListener l : listeners)
                    l.onPartial(o.optString("text", ""));
                break;
            case "final":
                lastFinalAt = android.os.SystemClock.elapsedRealtime();
                turnAudioReported = false;
                for (StateListener l : listeners)
                    l.onFinal(o.optString("text", ""));
                break;
            case "brain_text":
                for (StateListener l : listeners)
                    l.onBrainText(o.optString("text", ""));
                break;
            case "task_goal":
                for (StateListener l : listeners)
                    l.onTaskGoal(o.optString("taskId", ""),
                            o.optString("goal", ""),
                            o.optString("akashaSessionId", ""));
                break;
            case "audio_start": {
                // first TTS PCM of a turn -> report final->audio latency (Task 8 UI)
                if (lastFinalAt > 0 && !turnAudioReported) {
                    long ms = android.os.SystemClock.elapsedRealtime() - lastFinalAt;
                    turnAudioReported = true;
                    for (StateListener l : listeners) l.onFirstAudioLatency(ms);
                }
                break;
            }
            case "audio_end":
                // binary PCM between them goes straight to TtsPlayer
                break;
            case "error":
                for (StateListener l : listeners)
                    l.onError(o.optString("code", ""), o.optString("message", ""));
                break;
            case "end":
                CpLog.i(TAG, "server end frame");
                hangUp("server_end");
                break;
            default:
                CpLog.d(TAG, "unhandled frame type=" + type);
        }
    }

    // ---------------- teardown ----------------

    /**
     * Single teardown path. Covers: user hangup, server end, permission,
     * mic start failure, mic exception, onDestroy. Always:
     * AudioRecord stop()+release() + tts release + ws no-reconnect + stopSelf.
     */
    public synchronized void hangUp(String reason) {
        if (hungUp) {
            CpLog.d(TAG, "hangUp ignored reason=" + reason + " (already hung up)");
            return;
        }
        CpLog.i(TAG, "hangUp reason=" + reason);
        hungUp = true;
        TaskBridge.get().detach();
        if (ws != null) {
            ws.stopCall(); // sends stop + no auto reconnect (§54)
            ws = null;
        }
        releaseMic(reason);
        TtsPlayer t = tts;
        tts = null;
        if (t != null) {
            t.interrupt();
            t.release();
        }
        callState = "ended";
        for (StateListener l : listeners) l.onCallEnded();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        // safety net: if the system kills us mid-call, release everything
        if (!hungUp) {
            hangUp("onDestroy");
        } else {
            releaseMic("onDestroy_safety");
            TtsPlayer t = tts;
            tts = null;
            if (t != null) t.release();
            TaskBridge.get().detach();
            if (ws != null) {
                ws.shutdown();
                ws = null;
            }
        }
        if (inst == this) inst = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static long SystemClockNow() {
        return android.os.SystemClock.elapsedRealtime();
    }
}
