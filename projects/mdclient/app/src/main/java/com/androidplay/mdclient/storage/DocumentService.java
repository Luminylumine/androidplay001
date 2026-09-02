package com.androidplay.mdclient.storage;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.androidplay.mdclient.core.DocumentBlock;
import com.androidplay.mdclient.core.SearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** CRUD and deliberately small Markdown projection for native document blocks. */
public final class DocumentService {
    private final MdClientDatabase helper;

    public DocumentService(MdClientDatabase helper) { this.helper = helper; }

    public DocumentBlock create(String courseId, String sessionId, int position, String kind, String content) {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        ContentValues v = values(id, courseId, sessionId, position, kind, content, 0, now);
        helper.getWritableDatabase().insertOrThrow("document_blocks", null, v);
        index(id, content);
        return new DocumentBlock(id, courseId, sessionId, position, kind, content, 0);
    }

    public DocumentBlock get(String id) {
        Cursor c = helper.getReadableDatabase().query("document_blocks", null, "id=?", new String[]{id}, null, null, null);
        try { return c.moveToFirst() ? read(c) : null; } finally { c.close(); }
    }

    public List<DocumentBlock> list(String courseId, String sessionId) {
        String where = courseId == null ? "session_id=?" : "course_id=?";
        String arg = courseId == null ? sessionId : courseId;
        Cursor c = helper.getReadableDatabase().query("document_blocks", null, where, new String[]{arg}, null, null, "position ASC");
        List<DocumentBlock> result = new ArrayList<>();
        try { while (c.moveToNext()) result.add(read(c)); } finally { c.close(); }
        return result;
    }

    /** Returns the updated block, or null when expectedRevision is stale/missing. */
    public DocumentBlock update(String id, int expectedRevision, String kind, String content, int position) {
        ContentValues v = new ContentValues(); v.put("kind", kind); v.put("content", content);
        v.put("position", position); v.put("revision", expectedRevision + 1); v.put("updated_wall_ms", System.currentTimeMillis());
        SQLiteDatabase db = helper.getWritableDatabase();
        int changed = db.update("document_blocks", v, "id=? AND revision=?", new String[]{id, String.valueOf(expectedRevision)});
        if (changed != 1) return null;
        try { db.delete("document_blocks_fts", "block_id=?", new String[]{id}); index(id, content); } catch (Exception ignored) {}
        return get(id);
    }

    public boolean delete(String id, int expectedRevision) {
        SQLiteDatabase db = helper.getWritableDatabase();
        int changed = db.delete("document_blocks", "id=? AND revision=?", new String[]{id, String.valueOf(expectedRevision)});
        if (changed == 1) try { db.delete("document_blocks_fts", "block_id=?", new String[]{id}); } catch (Exception ignored) {}
        return changed == 1;
    }

    public List<DocumentBlock> outline(String courseId, String sessionId) {
        List<DocumentBlock> all = list(courseId, sessionId); List<DocumentBlock> result = new ArrayList<>();
        for (DocumentBlock b : all) if ("heading".equals(b.kind)) result.add(b);
        return result;
    }

    public List<SearchResult> search(String query, String courseId, String sessionId, int limit) {
        List<SearchResult> result = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        try {
            String sql = "SELECT b.id,b.course_id,b.session_id,b.position,b.kind,b.content,b.revision "
                    + "FROM document_blocks_fts f JOIN document_blocks b ON b.id=f.block_id WHERE f MATCH ?";
            List<String> args = new ArrayList<>(); args.add(query);
            if (courseId != null) { sql += " AND b.course_id=?"; args.add(courseId); }
            if (sessionId != null) { sql += " AND b.session_id=?"; args.add(sessionId); }
            Cursor c = db.rawQuery(sql + " LIMIT " + Math.max(1, limit), args.toArray(new String[0]));
            try { while (c.moveToNext()) addResult(result, c); } finally { c.close(); }
        } catch (Exception unavailableOrInvalidQuery) {
            String like = "%" + query + "%";
            String where = "content LIKE ?"; List<String> args = new ArrayList<>(); args.add(like);
            if (courseId != null) { where += " AND course_id=?"; args.add(courseId); }
            if (sessionId != null) { where += " AND session_id=?"; args.add(sessionId); }
            Cursor c = db.query("document_blocks", null, where, args.toArray(new String[0]), null, null, "position ASC", String.valueOf(Math.max(1, limit)));
            try { while (c.moveToNext()) addResult(result, c); } finally { c.close(); }
        }
        return result;
    }

    public List<DocumentBlock> readAround(String blockId, int radius) {
        DocumentBlock target = get(blockId); if (target == null) return new ArrayList<>();
        String owner = target.courseId != null ? "course_id=?" : "session_id=?";
        String ownerId = target.courseId != null ? target.courseId : target.sessionId;
        Cursor c = helper.getReadableDatabase().query("document_blocks", null,
                owner + " AND position BETWEEN ? AND ?", new String[]{ownerId, String.valueOf(target.position - radius), String.valueOf(target.position + radius)}, null, null, "position ASC");
        List<DocumentBlock> result = new ArrayList<>();
        try { while (c.moveToNext()) result.add(read(c)); } finally { c.close(); }
        return result;
    }

    public String exportMarkdown(String courseId, String sessionId) {
        StringBuilder out = new StringBuilder();
        for (DocumentBlock b : list(courseId, sessionId)) {
            if ("heading".equals(b.kind)) out.append("## ").append(b.content).append('\n');
            else if ("bullet".equals(b.kind)) out.append("- ").append(b.content).append('\n');
            else if ("quote".equals(b.kind)) out.append("> ").append(b.content).append('\n');
            else if ("formula".equals(b.kind)) out.append("$$\n").append(b.content).append("\n$$\n");
            else if ("code".equals(b.kind)) out.append("```\n").append(b.content).append("\n```\n");
            else out.append(b.content).append("\n");
            out.append('\n');
        }
        return out.toString();
    }

    public List<DocumentBlock> importMarkdown(String courseId, String sessionId, String markdown) {
        List<DocumentBlock> added = new ArrayList<>(); int position = list(courseId, sessionId).size();
        boolean code = false, formula = false; StringBuilder fenced = new StringBuilder();
        for (String line : markdown.replace("\r", "").split("\n", -1)) {
            if (line.trim().startsWith("```")) { code = !code; if (!code) { added.add(create(courseId, sessionId, position++, "code", fenced.toString().trim())); fenced.setLength(0); } continue; }
            if (line.trim().equals("$$")) { formula = !formula; if (!formula) { added.add(create(courseId, sessionId, position++, "formula", fenced.toString().trim())); fenced.setLength(0); } continue; }
            if (code || formula) { fenced.append(line).append('\n'); continue; }
            String kind = "paragraph", text = line;
            if (line.startsWith("#")) { kind = "heading"; text = line.replaceFirst("^#+\\s*", ""); }
            else if (line.startsWith("- ") || line.startsWith("* ")) { kind = "bullet"; text = line.substring(2); }
            else if (line.startsWith("> ")) { kind = "quote"; text = line.substring(2); }
            if (!text.trim().isEmpty()) added.add(create(courseId, sessionId, position++, kind, text));
        }
        return added;
    }

    private ContentValues values(String id, String course, String session, int pos, String kind, String content, int rev, long now) {
        ContentValues v = new ContentValues(); v.put("id", id); v.put("course_id", course); v.put("session_id", session); v.put("position", pos);
        v.put("kind", kind == null ? "paragraph" : kind); v.put("content", content == null ? "" : content); v.put("revision", rev); v.put("created_wall_ms", now); v.put("updated_wall_ms", now); return v;
    }
    private void index(String id, String content) { try { ContentValues v = new ContentValues(); v.put("block_id", id); v.put("content", content); helper.getWritableDatabase().insert("document_blocks_fts", null, v); } catch (Exception ignored) {} }
    private void addResult(List<SearchResult> out, Cursor c) { DocumentBlock b = read(c); out.add(new SearchResult(b, b.content)); }
    private DocumentBlock read(Cursor c) { return new DocumentBlock(c.getString(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("course_id")), c.getString(c.getColumnIndexOrThrow("session_id")), c.getInt(c.getColumnIndexOrThrow("position")), c.getString(c.getColumnIndexOrThrow("kind")), c.getString(c.getColumnIndexOrThrow("content")), c.getInt(c.getColumnIndexOrThrow("revision"))); }
}
