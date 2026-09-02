package com.akasha.app.voice;

import android.os.Handler;
import android.os.Looper;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import com.akasha.app.CpLog;

/**
 * WS client for /ws/call (PROTOCOL.md §3). Connection layer only:
 * - token goes in the URL query (?token=...)
 * - unexpected close -> exponential backoff 1/2/4/8s, capped 10s (voice-bridge §54)
 * - user hangup -> shutdown(): NO auto reconnect (call must be re-dialed)
 * Listener callbacks arrive on the main thread.
 */
public class CallWsClient {

    public interface Listener {
        void onConnected();
        void onDisconnected(int code, String reason);
        void onText(String text);
        void onBinary(byte[] data);
    }

    private static final String TAG = "CallWs";
    private static final int BACKOFF_MIN_MS = 1000;
    private static final int BACKOFF_MAX_MS = 10000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    private String url;
    private WebSocketClient client;
    private int backoffMs = BACKOFF_MIN_MS;
    private volatile boolean shutdown = false;
    private volatile boolean started = false;
    private volatile boolean connected = false;

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (shutdown || connected) return;
            CpLog.i(TAG, "reconnect attempt backoffMs=" + backoffMs);
            open();
        }
    };

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    public boolean isConnected() { return connected; }

    /** Begin the connection lifecycle. Safe to call only once per client. */
    public void start(String host, String token) {
        url = "ws://" + host + "/ws/call?token=" + token;
        started = true;
        shutdown = false;
        open();
    }

    private void open() {
        synchronized (lock) {
            if (shutdown) return;
            try {
                if (client != null && client.isOpen()) return;
                client = new WebSocketClient(new URI(url)) {
                    @Override
                    public void onOpen(ServerHandshake handshake) {
                        connected = true;
                        backoffMs = BACKOFF_MIN_MS;
                        CpLog.i(TAG, "ws open handshake=" + (handshake != null));
                        post(CallWsClient.this::fireConnected);
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        connected = false;
                        CpLog.i(TAG, "ws close code=" + code + " reason=" + reason
                                + " remote=" + remote + " shutdown=" + shutdown);
                        post(() -> fireDisconnected(code, reason == null ? "" : reason));
                        scheduleRetryIfNeeded();
                    }

                    @Override
                    public void onError(Exception ex) {
                        CpLog.e(TAG, "ws error: " + ex);
                    }

                    @Override
                    public void onMessage(String message) {
                        post(() -> fireText(message));
                    }

                    @Override
                    public void onMessage(ByteBuffer bytes) {
                        byte[] data = new byte[bytes.remaining()];
                        bytes.get(data);
                        post(() -> fireBinary(data));
                    }
                };
                client.setConnectionLostTimeout(0);
                client.connect();
            } catch (Exception e) {
                CpLog.e(TAG, "ws open failed: " + e);
                connected = false;
                scheduleRetryIfNeeded();
            }
        }
    }

    private void scheduleRetryIfNeeded() {
        if (shutdown || !started) return;
        main.removeCallbacks(retryRunnable);
        int wait = backoffMs;
        backoffMs = Math.min(backoffMs * 2, BACKOFF_MAX_MS);
        main.postDelayed(retryRunnable, wait);
    }

    /** Send raw bytes (mic PCM). Drops silently when not connected. */
    public void sendBinary(byte[] data) {
        WebSocketClient c = client;
        if (c != null && c.isOpen()) c.send(data);
    }

    /** Send a JSON control frame. Drops silently when not connected. */
    public void sendText(String json) {
        WebSocketClient c = client;
        if (c != null && c.isOpen()) c.send(json);
    }

    /** Clean stop: send stop, close, no reconnect. For user hangup. */
    public void stopCall() {
        WebSocketClient c = client;
        if (c != null && c.isOpen()) {
            c.send("{\"type\":\"stop\"}");
            c.close(1000, "user hangup");
        }
        shutdown = true;
        started = false;
        main.removeCallbacks(retryRunnable);
    }

    /** Hard teardown without sending stop (server already ended / abort). */
    public void shutdown() {
        shutdown = true;
        started = false;
        main.removeCallbacks(retryRunnable);
        WebSocketClient c = client;
        if (c != null) {
            try { c.close(1000, "shutdown"); } catch (Exception ignored) {}
        }
    }

    private void post(Runnable r) { main.post(r); }

    private void fireConnected() {
        for (Listener l : listeners) l.onConnected();
    }

    private void fireDisconnected(int code, String reason) {
        for (Listener l : listeners) l.onDisconnected(code, reason);
    }

    private void fireText(String text) {
        for (Listener l : listeners) l.onText(text);
    }

    private void fireBinary(byte[] data) {
        for (Listener l : listeners) l.onBinary(data);
    }
}
