package com.androidplay.mdclient;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.androidplay.mdclient.agent.AgentAction;
import com.androidplay.mdclient.agent.AgentResponse;
import com.androidplay.mdclient.agent.FakeAgentBackend;
import com.androidplay.mdclient.agent.FastAgentController;
import com.androidplay.mdclient.agent.FreshnessValidator;
import com.androidplay.mdclient.audio.LectureAudioService;
import com.androidplay.mdclient.core.DocumentBlock;
import com.androidplay.mdclient.material.PdfMaterialController;
import com.androidplay.mdclient.storage.DocumentService;
import com.androidplay.mdclient.storage.EventStore;
import com.androidplay.mdclient.storage.MdClientDatabase;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Small alpha/debug shell for the mdclient core services. */
public final class MdClientActivity extends Activity {
    private static final int AUDIO_PERMISSION = 10;
    private static final int NOTIFICATION_PERMISSION = 11;
    private static final int OPEN_PDF = 12;
    private static final String PREFS = "mdclient_session";
    private static final String SESSION_KEY = "session_id";

    private MdClientDatabase database;
    private EventStore eventStore;
    private DocumentService documents;
    private PdfMaterialController pdf;
    private FastAgentController agent;
    private FreshnessValidator freshness;
    private String sessionId;
    private String courseId;
    private boolean sessionRunning;
    private boolean loadingBlocks;
    private long lastAgentActionMs = -1L;
    private final List<EditText> blocks = new ArrayList<>();
    private final List<DocumentBlock> blockState = new ArrayList<>();
    private TextView status;
    private TextView transcript;
    private TextView pageInfo;
    private TextView ocrInfo;
    private ImageView pageImage;
    private EditText pageNumber;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        database = new MdClientDatabase(this);
        eventStore = null;
        documents = new DocumentService(database);
        pdf = new PdfMaterialController(this);
        agent = new FastAgentController(new FakeAgentBackend("Agent: 将这段内容整理为复习要点"), 0L);
        freshness = new FreshnessValidator(null);
        sessionId = getSharedPreferences(PREFS, MODE_PRIVATE).getString(SESSION_KEY, null);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
        LectureAudioService.setEventSink((type, json) -> {
            if (eventStore != null) eventStore.append("audio-service", type, json);
            runOnUiThread(() -> showStatus("audio " + type));
        });
        buildUi();
        restorePdf();
        if (sessionId != null) loadSession(sessionId);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(12, 8, 12, 8);
        status = label("mdclient | idle"); root.addView(status);
        LinearLayout columns = new LinearLayout(this); columns.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout material = column("MATERIAL");
        material.addView(button("Choose PDF", v -> choosePdf()));
        pageInfo = label("Page -/-"); material.addView(pageInfo);
        pageImage = new ImageView(this); pageImage.setAdjustViewBounds(true); pageImage.setBackgroundColor(0xffeeeeee);
        material.addView(pageImage, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout pages = new LinearLayout(this); pages.setOrientation(LinearLayout.HORIZONTAL);
        pages.addView(button("Previous", v -> changePage(-1)), new LinearLayout.LayoutParams(0, -2, 1));
        pages.addView(button("Next", v -> changePage(1)), new LinearLayout.LayoutParams(0, -2, 1));
        material.addView(pages);
        pageNumber = new EditText(this); pageNumber.setHint("page #"); pageNumber.setInputType(2); material.addView(pageNumber);
        material.addView(button("Jump", v -> jumpPage()));
        ocrInfo = label(""); material.addView(ocrInfo);
        columns.addView(material, new LinearLayout.LayoutParams(0, -1, .9f));

        LinearLayout document = column("KNOWLEDGE DOCUMENT | native blocks");
        for (int i = 0; i < 5; i++) addBlockEditor(document, i);
        columns.addView(document, new LinearLayout.LayoutParams(0, -1, 1.7f));

        LinearLayout agentPanel = column("AGENT | live debug state");
        transcript = label("Transcript: -"); agentPanel.addView(transcript);
        agentPanel.addView(button("Start Session", v -> startSession()));
        agentPanel.addView(button("Stop Session", v -> stopSession()));
        agentPanel.addView(button("Start Audio", v -> startAudio()));
        agentPanel.addView(button("Stop Audio", v -> stopAudio()));
        agentPanel.addView(button("Fake Transcript", v -> fakeTranscript()));
        agentPanel.addView(button("Fake Agent", v -> fakeAgent()));
        agentPanel.addView(button("Export Markdown", v -> exportMarkdown()));
        agentPanel.addView(button("Export JSONL", v -> exportJsonl()));
        columns.addView(agentPanel, new LinearLayout.LayoutParams(0, -1, 1));
        root.addView(label("Event timeline (SQLite EventStore)"));
        ScrollView log = new ScrollView(this); log.addView(label("Events are persisted in mdclient.db; count updates on each event."));
        root.addView(log, new LinearLayout.LayoutParams(-1, 100)); setContentView(root);
    }

    private void addBlockEditor(LinearLayout parent, final int index) {
        EditText edit = new EditText(this); edit.setHint("Block " + (index + 1)); edit.setGravity(Gravity.TOP); edit.setMinLines(2);
        edit.setOnFocusChangeListener((v, focused) -> { if (focused) append("ui", "HUMAN_ATTENTION_CHANGED", "block=" + index); });
        edit.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (!loadingBlocks && index < blockState.size()) updateHuman(index, s.toString());
            }
            public void afterTextChanged(android.text.Editable e) { }
        });
        blocks.add(edit); parent.addView(edit, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private LinearLayout column(String title) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(8, 4, 8, 4); c.addView(label(title)); return c; }
    private TextView label(String text) { TextView v = new TextView(this); v.setText(text); v.setTextSize(15); v.setPadding(8, 8, 8, 8); return v; }
    private Button button(String text, View.OnClickListener listener) { Button b = new Button(this); b.setText(text); b.setOnClickListener(listener); return b; }

    private void startSession() {
        if (sessionRunning) return;
        long now = System.currentTimeMillis(); sessionId = "session-" + UUID.randomUUID(); courseId = "course-mdclient";
        ContentValues course = new ContentValues(); course.put("id", courseId); course.put("title", "mdclient alpha"); course.put("description", "debug course"); course.put("created_wall_ms", now); course.put("updated_wall_ms", now);
        database.getWritableDatabase().insertWithOnConflict("courses", null, course, 5);
        ContentValues session = new ContentValues(); session.put("id", sessionId); session.put("course_id", courseId); session.put("started_elapsed_ns", SystemClock.elapsedRealtimeNanos()); session.put("started_wall_ms", now); session.put("status", "running");
        database.getWritableDatabase().insertOrThrow("sessions", null, session);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(SESSION_KEY, sessionId).apply();
        sessionRunning = true; eventStore = new EventStore(database, sessionId); append("ui", "SESSION_STARTED", sessionId); ensureBlocks(); showStatus("session running");
    }

    private void loadSession(String id) {
        courseId = "course-mdclient";
        eventStore = new EventStore(database, id);
        android.database.Cursor cursor = database.getReadableDatabase().query("sessions", new String[]{"status"}, "id=?", new String[]{id}, null, null, null);
        try { sessionRunning = cursor.moveToFirst() && "running".equals(cursor.getString(0)); }
        finally { cursor.close(); }
        ensureBlocks();
    }
    private void ensureBlocks() {
        if (sessionId == null) return;
        List<DocumentBlock> found = documents.list(null, sessionId);
        for (int i = found.size(); i < 5; i++) documents.create(courseId, sessionId, i, i == 0 ? "heading" : "paragraph", "");
        blockState.clear(); blockState.addAll(documents.list(null, sessionId));
        loadingBlocks = true; for (int i = 0; i < blocks.size(); i++) blocks.get(i).setText(i < blockState.size() ? blockState.get(i).content : ""); loadingBlocks = false;
    }
    private void updateHuman(int index, String content) {
        DocumentBlock old = blockState.get(index); DocumentBlock updated = documents.update(old.id, old.revision, old.kind, content, old.position);
        if (updated != null) { blockState.set(index, updated); append("human", "HUMAN_TEXT_EDIT", "block=" + old.id + ":revision=" + updated.revision); }
        else { blockState.set(index, documents.get(old.id)); append("human", "HUMAN_EDIT_CONFLICT", old.id); }
    }

    private void stopSession() {
        stopAudio(); if (sessionId == null) return;
        ContentValues v = new ContentValues(); v.put("ended_elapsed_ns", SystemClock.elapsedRealtimeNanos()); v.put("ended_wall_ms", System.currentTimeMillis()); v.put("status", "stopped");
        database.getWritableDatabase().update("sessions", v, "id=?", new String[]{sessionId}); append("ui", "SESSION_STOPPED", sessionId); exportJsonlFile(); sessionRunning = false; showStatus("session stopped");
    }
    private void startAudio() {
        if (sessionId == null) startSession();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { showStatus("microphone permission required"); return; }
        Intent intent = LectureAudioService.startIntent(this, sessionId);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent); showStatus("audio starting");
    }
    private void stopAudio() { stopService(LectureAudioService.stopIntent(this)); }

    private void fakeTranscript() {
        if (sessionId == null) startSession(); String partial = "今天我们建立课堂时间轴"; transcript.setText("Transcript: " + partial + " (partial)"); append("fake-asr", "TRANSCRIPT_PARTIAL", partial);
        String finalText = partial + "，并记录每次修改"; transcript.setText("Transcript: " + finalText); append("fake-asr", "TRANSCRIPT_FINAL", finalText);
        AgentResponse response = agent.onFinalTranscript(finalText, System.currentTimeMillis(), documents.exportMarkdown(null, sessionId), blockState.isEmpty() ? 0 : blockState.get(0).revision);
        if (response != null) { append("fake-agent", "AGENT_SUGGESTION", response.getText()); applyAgent(response.getText()); }
    }
    private void fakeAgent() { if (sessionId == null) startSession(); applyAgent("Agent suggestion: 将早期定义整理为复习要点"); }
    private void applyAgent(String text) {
        if (blockState.isEmpty()) return; DocumentBlock old = blockState.get(1 < blockState.size() ? 1 : 0);
        AgentAction action = new AgentAction(AgentAction.Type.REPLACE, old.id, text, old.revision, System.currentTimeMillis()); append("fake-agent", "AGENT_SUGGESTION", "targetBlock=" + old.id + ":" + text);
        if (!freshness.isFresh(action, System.currentTimeMillis(), old.revision, lastAgentActionMs)) { append("fake-agent", "AGENT_ACTION_DROPPED", "stale=" + old.id); return; }
        DocumentBlock updated = documents.update(old.id, old.revision, old.kind, text, old.position);
        if (updated != null) { int index = blockState.indexOf(old); blockState.set(index, updated); loadingBlocks = true; blocks.get(index).setText(text); loadingBlocks = false; append("fake-agent", "AGENT_ACTION", old.id + ":applied"); lastAgentActionMs = System.currentTimeMillis(); }
    }

    private void choosePdf() { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), OPEN_PDF); }
    private void restorePdf() { pdf.setPageChangedCallback((page, count) -> { append("material", "PAGE_CHANGED", "page=" + page + ",count=" + count); renderPdf(); }); try { if (pdf.openPersisted()) renderPdf(); } catch (Exception e) { append("material", "PDF_ERROR", e.toString()); } }
    @Override protected void onActivityResult(int request, int result, Intent data) { super.onActivityResult(request, result, data); if (request == OPEN_PDF && result == RESULT_OK && data != null) try { pdf.open(data.getData()); renderPdf(); } catch (Exception e) { append("material", "PDF_ERROR", e.toString()); } }
    private void changePage(int delta) { try { if (delta < 0) pdf.previous(); else pdf.next(); } catch (Exception e) { showStatus("open a PDF first"); } }
    private void jumpPage() { try { pdf.jump(Integer.parseInt(pageNumber.getText().toString()) - 1); } catch (Exception e) { showStatus("invalid page"); } }
    private void renderPdf() { if (!pdf.isOpen()) return; try { int page = pdf.currentPage(); pageInfo.setText("Page " + (page + 1) + "/" + pdf.pageCount()); ocrInfo.setText(pdf.extractPageText(page).trim().isEmpty() ? "needsOcr" : "text available"); pageImage.setImageBitmap(pdf.renderPageBitmap(900, 1200)); } catch (Exception e) { append("material", "PDF_RENDER_ERROR", e.toString()); } }

    private File sessionDir() { File dir = new File(new File(getFilesDir(), "sessions"), sessionId == null ? "unstarted" : sessionId); if (!dir.exists()) dir.mkdirs(); return dir; }
    private void exportMarkdown() { try { File out = new File(sessionDir(), "notes.md"); BufferedWriter w = new BufferedWriter(new FileWriter(out)); w.write(documents.exportMarkdown(null, sessionId)); w.close(); append("ui", "MARKDOWN_EXPORTED", out.getAbsolutePath()); Toast.makeText(this, out.getAbsolutePath(), Toast.LENGTH_SHORT).show(); } catch (Exception e) { append("ui", "EXPORT_ERROR", e.toString()); } }
    private void exportJsonl() { File out = exportJsonlFile(); if (out != null) Toast.makeText(this, out.getAbsolutePath(), Toast.LENGTH_SHORT).show(); }
    private File exportJsonlFile() { try { File out = new File(sessionDir(), "session-events.jsonl"); BufferedWriter w = new BufferedWriter(new FileWriter(out)); eventStore.exportJsonl(w); w.close(); return out; } catch (Exception e) { append("ui", "EXPORT_ERROR", e.toString()); return null; } }

    private void append(String source, String type, String payload) { if (eventStore != null) eventStore.append(source, type, payload); runOnUiThread(() -> showStatus(type)); }
    private void showStatus(String text) { if (status != null) status.setText("mdclient | audio/service | asr/fake | session=" + (sessionId == null ? "-" : sessionId) + " | " + text); }
    @Override protected void onDestroy() { LectureAudioService.setEventSink(null); eventStore = null; if (pdf != null) pdf.close(); if (database != null) database.close(); super.onDestroy(); }
}
