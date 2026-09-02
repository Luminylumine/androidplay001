package com.androidplay.phase0probe;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.Editable;
import android.text.TextWatcher;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity {
    private final AtomicLong seq = new AtomicLong();
    private TextView logView;
    private AudioRecord audio;
    private volatile boolean recording;
    private Thread audioThread;
    private int page = 1;
    private FileOutputStream eventFile;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
        }
        try { eventFile = openFileOutput("events.jsonl", MODE_APPEND); } catch (Exception ignored) {}
        buildUi();
        event("APP", "START", "sdk=" + android.os.Build.VERSION.SDK_INT);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("CapabilityProbe | MatePad Phase 0");
        title.setTextSize(20);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView caps = new TextView(this);
        caps.setText(capabilities());
        root.addView(caps, new LinearLayout.LayoutParams(-1, -2));

        EditText nativeEdit = new EditText(this);
        nativeEdit.setHint("Native EditText: 傅里叶变换");
        nativeEdit.setSingleLine(false);
        root.addView(nativeEdit, new LinearLayout.LayoutParams(-1, 100));
        nativeEdit.setOnFocusChangeListener((v, has) -> event("human", "FOCUS_CHANGE", "native=" + has));
        nativeEdit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            public void onTextChanged(CharSequence s, int st, int before, int count) { event("human", "TEXT_INPUT", "nativeLength=" + s.length()); }
            public void afterTextChanged(Editable e) {}
        });

        WebView web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Probe");
        web.setWebViewClient(new android.webkit.WebViewClient());
        web.loadDataWithBaseURL(null, html(), "text/html", "UTF-8", null);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout buttons = new LinearLayout(this);
        addButton(buttons, "Audio start", v -> startAudio());
        addButton(buttons, "Audio stop", v -> stopAudio());
        addButton(buttons, "Page next", v -> { page++; event("human", "PAGE_CHANGE", "page=" + page); });
        addButton(buttons, "Fake agent", v -> fakeAgent());
        addButton(buttons, "Append block", v -> web.evaluateJavascript("document.getElementById('other').textContent += ' [agent append]';", null));
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        logView = new TextView(this);
        logView.setTextSize(11);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 170));
        setContentView(root);
    }

    private void addButton(LinearLayout row, String text, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(text); b.setOnClickListener(listener);
        row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private String capabilities() {
        StringBuilder out = new StringBuilder();
        out.append("SDK_INT=").append(android.os.Build.VERSION.SDK_INT)
                .append(" ABI=").append(android.os.Build.SUPPORTED_ABIS[0]).append('\n');
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.webkit.WebView.getCurrentWebViewPackage();
            android.content.pm.PackageInfo p = android.webkit.WebView.getCurrentWebViewPackage();
            out.append("WebView=").append(p == null ? "UNKNOWN" : p.packageName + " " + p.versionName + " (" + p.getLongVersionCode() + ")").append('\n');
        }
        out.append("Speech available=").append(SpeechRecognizer.isRecognitionAvailable(this));
        if (android.os.Build.VERSION.SDK_INT >= 31) out.append(" on-device=").append(onDeviceRecognitionAvailable());
        out.append('\n');
        Intent i = new Intent(RecognitionService.SERVICE_INTERFACE);
        List<ResolveInfo> services = getPackageManager().queryIntentServices(i, PackageManager.MATCH_ALL);
        for (ResolveInfo r : services) {
            ComponentName c = new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
            out.append("RecognitionService=").append(c.flattenToShortString()).append('\n');
        }
        return out.toString();
    }

    private boolean onDeviceRecognitionAvailable() {
        try {
            return (Boolean) SpeechRecognizer.class
                    .getMethod("isOnDeviceRecognitionAvailable", android.content.Context.class)
                    .invoke(null, this);
        } catch (Exception e) {
            return false;
        }
    }

    private String html() {
        return "<html><body><h2>WebView IME Probe</h2>"
                + "<div id='editor' contenteditable='true' style='min-height:100px;border:1px solid blue'>这里可以输入中文 English 123 $x^2$</div>"
                + "<div id='other'>Page 1</div><p>输入后点击 Append block，观察 composition/cursor。</p>"
                + "<script>['beforeinput','input','compositionstart','compositionupdate','compositionend','selectionchange','keydown','keyup','focus','blur'].forEach(function(n){document.addEventListener(n,function(e){Probe.event(n,(e.data||'').toString())},true)});</script>"
                + "</body></html>";
    }

    private void startAudio() {
        if (recording) return;
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { event("audio", "FAILED", "permission"); return; }
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try { audio = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, 4096)); audio.startRecording(); } catch (Exception e) { event("audio", "FAILED", e.toString()); return; }
        recording = true; event("audio", "AUDIO_START", "state=" + audio.getState() + " recordingState=" + audio.getRecordingState());
        audioThread = new Thread(() -> { byte[] buf = new byte[4096]; AudioTimestamp ts = new AudioTimestamp(); long frames = 0; long start = SystemClock.elapsedRealtimeNanos(); while (recording) { int n = audio.read(buf, 0, buf.length); if (n > 0) { frames += n / 2; if (frames % 16000 < n / 2) { int rc = audio.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC); event("audio", "AUDIO_TIMESTAMP", "frames=" + frames + " rc=" + rc + " framePosition=" + ts.framePosition + " audioNs=" + ts.nanoTime + " elapsedNs=" + SystemClock.elapsedRealtimeNanos() + " elapsed=" + (SystemClock.elapsedRealtimeNanos()-start)); } } else event("audio", "AUDIO_READ_ERROR", "code=" + n); } }); audioThread.start();
    }

    private void stopAudio() { if (!recording) return; recording = false; try { audio.stop(); audio.release(); } catch (Exception ignored) {} event("audio", "AUDIO_STOP", "timeNs=" + SystemClock.elapsedRealtimeNanos()); }

    private void fakeAgent() { long based = seq.get(); event("agent", "FAKE_AGENT_ACTION", "basedOnSeq=" + based + " basedOnEventTime=" + SystemClock.elapsedRealtimeNanos() + " targetRevision=" + (based + 1)); }

    private void event(String source, String type, String payload) {
        long n = SystemClock.elapsedRealtimeNanos(); long s = seq.incrementAndGet(); String line = "{\"seq\":" + s + ",\"source\":\"" + source + "\",\"type\":\"" + type + "\",\"eventTimeNs\":" + n + ",\"payload\":\"" + payload.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}\n";
        try { if (eventFile != null) { eventFile.write(line.getBytes(StandardCharsets.UTF_8)); eventFile.flush(); } } catch (Exception ignored) {}
        runOnUiThread(() -> { if (logView != null) { logView.append(line); } });
    }

    public final class Bridge {
        @JavascriptInterface public long nowNs() { return SystemClock.elapsedRealtimeNanos(); }
        @JavascriptInterface public void event(String type, String data) { MainActivity.this.event("webview", type, data); }
    }
    @Override protected void onDestroy() { stopAudio(); try { if (eventFile != null) eventFile.close(); } catch (Exception ignored) {} super.onDestroy(); }
}
