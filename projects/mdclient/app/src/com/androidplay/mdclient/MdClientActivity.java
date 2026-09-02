package com.androidplay.mdclient;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.AudioTimestamp;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/** mdclient's deliberately small first shell. It has no dependency on Akasha. */
public final class MdClientActivity extends Activity {
    private final List<String> events = new ArrayList<>();
    private final List<EditText> blocks = new ArrayList<>();
    private TextView status;
    private TextView transcript;
    private AudioRecord recorder;
    private Thread audioThread;
    private volatile boolean recording;
    private long seq;
    private long sessionStartNs;
    private BufferedWriter eventWriter;
    private boolean applyingAgent;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 8, 12, 8);
        status = label("mdclient  |  idle");
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout material = column("MATERIAL\n\nPDF viewer placeholder\nPage 1");
        material.addView(button("Previous page", v -> append("PAGE_CHANGED", "page=1")));
        material.addView(button("Next page", v -> append("PAGE_CHANGED", "page=2")));
        columns.addView(material, new LinearLayout.LayoutParams(0, -1, 0.9f));

        LinearLayout document = column("KNOWLEDGE DOCUMENT\nNative blocks are authoritative");
        for (int i = 0; i < 5; i++) {
            final int index = i;
            EditText edit = new EditText(this);
            edit.setHint("Block " + (i + 1));
            edit.setGravity(Gravity.TOP);
            edit.setMinLines(2);
            edit.setOnFocusChangeListener((v, focused) -> {
                if (focused) append("HUMAN_ATTENTION_CHANGED", "block=" + index);
            });
            edit.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                    if (!applyingAgent) append("HUMAN_TEXT_EDIT", "block=" + index + ":" + s);
                }
                public void afterTextChanged(android.text.Editable e) {}
            });
            blocks.add(edit);
            document.addView(edit, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        columns.addView(document, new LinearLayout.LayoutParams(0, -1, 1.7f));

        LinearLayout agent = column("AGENT\nLive state and side suggestions");
        transcript = label("Transcript: -");
        agent.addView(transcript);
        agent.addView(button("Start session", v -> startSession()));
        agent.addView(button("Stop session", v -> stopSession()));
        agent.addView(button("Start audio", v -> startAudio()));
        agent.addView(button("Stop audio", v -> stopAudio()));
        agent.addView(button("Fake transcript", v -> fakeTranscript()));
        agent.addView(button("Fake delayed agent", v -> fakeAgent()));
        agent.addView(button("Export Markdown", v -> exportMarkdown()));
        columns.addView(agent, new LinearLayout.LayoutParams(0, -1, 1.0f));

        root.addView(label("Event timeline"), new LinearLayout.LayoutParams(-1, -2));
        ScrollView log = new ScrollView(this);
        TextView logText = label("");
        log.addView(logText);
        root.addView(log, new LinearLayout.LayoutParams(-1, 150));
        setContentView(root);
    }

    private LinearLayout column(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(8, 4, 8, 4);
        c.addView(label(title));
        return c;
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(15);
        v.setPadding(8, 8, 8, 8);
        return v;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        return b;
    }

    private void startSession() {
        if (sessionStartNs != 0) return;
        try {
            File dir = new File(new File(getFilesDir(), "sessions"), "mdclient-" + System.currentTimeMillis());
            if (!dir.mkdirs() && !dir.isDirectory()) throw new java.io.IOException("cannot create session directory");
            eventWriter = new BufferedWriter(new FileWriter(new File(dir, "events.jsonl"), true));
        } catch (Exception e) {
            status.setText("mdclient  |  session error");
            return;
        }
        sessionStartNs = SystemClock.elapsedRealtimeNanos();
        append("SESSION_STARTED", "");
        status.setText("mdclient  |  session running");
    }

    private void stopSession() {
        stopAudio();
        if (sessionStartNs == 0) return;
        append("SESSION_STOPPED", "");
        closeEventWriter();
        sessionStartNs = 0;
        status.setText("mdclient  |  session stopped");
    }

    private void startAudio() {
        if (sessionStartNs == 0) startSession();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("mdclient  |  microphone permission required");
            return;
        }
        if (recording) return;
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) { append("AUDIO_ERROR", "invalid buffer"); return; }
        try {
            final AudioRecord current = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 3200));
            current.startRecording();
            recorder = current;
            recording = true;
            append("AUDIO_STARTED", "16k mono PCM");
            audioThread = new Thread(() -> readAudio(current), "mdclient-audio");
            audioThread.start();
        } catch (Exception e) { append("AUDIO_ERROR", e.toString()); }
    }

    private void readAudio(AudioRecord current) {
        short[] buffer = new short[1600];
        AudioTimestamp timestamp = new AudioTimestamp();
        long frames = 0;
        long nextSample = 32000;
        while (recording && current == recorder) {
            int count = current.read(buffer, 0, buffer.length);
            if (count < 0) { append("AUDIO_ERROR", "read=" + count); break; }
            if (count == 0) continue;
            frames += count;
            if (frames >= nextSample) {
                nextSample += 32000;
                int rc = current.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC);
                long elapsed = SystemClock.elapsedRealtimeNanos();
                append("AUDIO_TIMESTAMP", "frames=" + frames + ",rc=" + rc + ",audioNs=" + timestamp.nanoTime + ",elapsedNs=" + elapsed + ",offsetNs=" + (timestamp.nanoTime - elapsed));
            }
        }
    }

    private void stopAudio() {
        if (!recording) return;
        recording = false;
        AudioRecord current = recorder;
        if (current != null) { try { current.stop(); } catch (Exception ignored) {} }
        Thread t = audioThread;
        if (t != null && t != Thread.currentThread()) { try { t.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        if (current != null) current.release();
        recorder = null;
        audioThread = null;
        append("AUDIO_STOPPED", "");
    }

    private void fakeTranscript() {
        if (sessionStartNs == 0) startSession();
        transcript.setText("Transcript: 今天我们建立 mdclient 的课堂时间轴");
        append("TRANSCRIPT_FINAL", "今天我们建立 mdclient 的课堂时间轴");
    }

    private void fakeAgent() {
        if (sessionStartNs == 0) startSession();
        final int target = 0;
        final String value = "Agent suggestion: 将早期定义整理为复习要点";
        status.setText("mdclient  |  agent suggestion ready");
        append("AGENT_SUGGESTION", "targetBlock=" + target + ":" + value);
        getWindow().getDecorView().postDelayed(() -> {
            boolean focused = blocks.get(target).hasFocus();
            if (!focused) {
                applyingAgent = true;
                blocks.get(target).setText(value);
                applyingAgent = false;
                append("AGENT_ACTION_APPLIED", "targetBlock=" + target);
            } else {
                append("AGENT_ACTION_DROPPED", "targetBlock=" + target + ":human-focused");
            }
        }, 2000);
    }

    private void exportMarkdown() {
        try {
            File dir = new File(getFilesDir(), "sessions");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "notes.md");
            BufferedWriter writer = new BufferedWriter(new FileWriter(out));
            for (int i = 0; i < blocks.size(); i++) {
                writer.write("## Block " + (i + 1) + "\n\n" + blocks.get(i).getText() + "\n\n");
            }
            writer.close();
            append("MARKDOWN_EXPORTED", out.getAbsolutePath());
            Toast.makeText(this, "Markdown exported", Toast.LENGTH_SHORT).show();
        } catch (Exception e) { append("EXPORT_ERROR", e.toString()); }
    }

    private synchronized void append(String type, String payload) {
        long now = SystemClock.elapsedRealtimeNanos();
        String line = (++seq) + " " + now + " " + type + " " + (payload == null ? "" : payload);
        events.add(line);
        if (eventWriter != null) {
            try {
                eventWriter.write("{\"seq\":" + seq + ",\"eventTimeNs\":" + now + ",\"arrivalTimeNs\":" + SystemClock.elapsedRealtimeNanos() + ",\"type\":\"" + type + "\",\"payload\":\"" + jsonEscape(payload) + "\"}");
                eventWriter.newLine();
                eventWriter.flush();
            } catch (Exception ignored) {}
        }
        runOnUiThread(() -> status.setText("mdclient  |  " + type));
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private void closeEventWriter() {
        if (eventWriter == null) return;
        try { eventWriter.close(); } catch (Exception ignored) {}
        eventWriter = null;
    }

    @Override protected void onDestroy() { stopAudio(); closeEventWriter(); super.onDestroy(); }
}
