package com.androidplay.mdclient.storage;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.androidplay.mdclient.core.SessionClock;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import org.json.JSONObject;

/** Append-only event log. The synchronized method is the sole sequence allocator. */
public final class EventStore {
    private final MdClientDatabase helper;
    private final String sessionId;
    public EventStore(MdClientDatabase helper) { this(helper, null); }
    public EventStore(MdClientDatabase helper, String sessionId) { this.helper = helper; this.sessionId = sessionId; }

    public synchronized long append(String source, String type, String payload) {
        return append(source, type, payload, SessionClock.elapsedRealtimeNanos());
    }

    public synchronized long append(String source, String type, String payload, long eventTimeNs) {
        SQLiteDatabase db = helper.getWritableDatabase();
        long arrival = SessionClock.elapsedRealtimeNanos();
        long wall = System.currentTimeMillis();
        long seq = 0;
        Cursor c = db.rawQuery("SELECT COALESCE(MAX(seq), 0) + 1 FROM events", null);
        try { if (c.moveToFirst()) seq = c.getLong(0); } finally { c.close(); }
        ContentValues v = new ContentValues();
        v.put("seq", seq); if (sessionId != null) v.put("session_id", sessionId);
        v.put("source", source == null ? "unknown" : source);
        v.put("type", type == null ? "unknown" : type); v.put("payload", payload);
        v.put("event_time_ns", eventTimeNs); v.put("arrival_time_ns", arrival); v.put("wall_time_ms", wall);
        db.insertOrThrow("events", null, v);
        return seq;
    }

    public synchronized int size() { Cursor c = sessionId == null
            ? helper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events", null)
            : helper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events WHERE session_id=?", new String[]{sessionId}); try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); } }

    public synchronized void exportJsonl(Writer out) throws IOException {
        BufferedWriter writer = out instanceof BufferedWriter ? (BufferedWriter) out : new BufferedWriter(out);
        String query = "SELECT seq,source,type,payload,event_time_ns,arrival_time_ns,wall_time_ms FROM events";
        Cursor c = sessionId == null
                ? helper.getReadableDatabase().rawQuery(query + " ORDER BY seq", null)
                : helper.getReadableDatabase().rawQuery(query + " WHERE session_id=? ORDER BY seq", new String[]{sessionId});
        try {
            while (c.moveToNext()) {
                try {
                    JSONObject o = new JSONObject();
                    o.put("seq", c.getLong(0)); o.put("source", c.getString(1)); o.put("type", c.getString(2));
                    o.put("payload", c.isNull(3) ? JSONObject.NULL : c.getString(3)); o.put("eventTimeNs", c.getLong(4));
                    o.put("arrivalTimeNs", c.getLong(5)); o.put("wallTimeMs", c.getLong(6));
                    writer.write(o.toString()); writer.newLine();
                } catch (Exception e) { throw new IOException("event serialization failed", e); }
            }
            writer.flush();
        } finally { c.close(); }
    }
}
