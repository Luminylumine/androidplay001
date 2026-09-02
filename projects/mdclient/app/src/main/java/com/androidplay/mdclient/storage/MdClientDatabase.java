package com.androidplay.mdclient.storage;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Owns the local, disposable mdclient store. All DDL is idempotent. */
public final class MdClientDatabase extends SQLiteOpenHelper {
    public static final String NAME = "mdclient.db";
    private static final int VERSION = 2;
    private static final String[] TABLES = {
        "courses", "sessions", "events", "document_blocks", "transcript_segments",
        "agent_actions", "agent_suggestions", "lecture_logic_snapshots", "course_materials",
        "session_diagnostics", "unresolved_items", "evidence_links"
    };

    public MdClientDatabase(Context context) { super(context.getApplicationContext(), NAME, null, VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS courses (id TEXT PRIMARY KEY, title TEXT NOT NULL, description TEXT, created_wall_ms INTEGER NOT NULL, updated_wall_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, course_id TEXT, started_elapsed_ns INTEGER NOT NULL, ended_elapsed_ns INTEGER, started_wall_ms INTEGER NOT NULL, ended_wall_ms INTEGER, status TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, source TEXT NOT NULL, type TEXT NOT NULL, payload TEXT, event_time_ns INTEGER NOT NULL, arrival_time_ns INTEGER NOT NULL, wall_time_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS document_blocks (id TEXT PRIMARY KEY, course_id TEXT, session_id TEXT, position INTEGER NOT NULL, kind TEXT NOT NULL, content TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0, created_wall_ms INTEGER NOT NULL, updated_wall_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS transcript_segments (id TEXT PRIMARY KEY, session_id TEXT, start_ns INTEGER, end_ns INTEGER, text TEXT NOT NULL, revision INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_actions (id TEXT PRIMARY KEY, session_id TEXT, action_type TEXT NOT NULL, payload TEXT, created_wall_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS agent_suggestions (id TEXT PRIMARY KEY, session_id TEXT, target_block_id TEXT, text TEXT NOT NULL, status TEXT NOT NULL, created_wall_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS lecture_logic_snapshots (id TEXT PRIMARY KEY, session_id TEXT, payload TEXT NOT NULL, created_wall_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS course_materials (id TEXT PRIMARY KEY, course_id TEXT, name TEXT NOT NULL, uri TEXT, created_wall_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS session_diagnostics (id TEXT PRIMARY KEY, session_id TEXT, key TEXT NOT NULL, value TEXT, created_wall_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS unresolved_items (id TEXT PRIMARY KEY, session_id TEXT, text TEXT NOT NULL, status TEXT NOT NULL, created_wall_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS evidence_links (id TEXT PRIMARY KEY, source_type TEXT NOT NULL, source_id TEXT NOT NULL, target_type TEXT NOT NULL, target_id TEXT NOT NULL, created_wall_ms INTEGER NOT NULL)");
        try {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS document_blocks_fts USING fts5(block_id UNINDEXED, content)");
            db.execSQL("CREATE INDEX IF NOT EXISTS document_blocks_position ON document_blocks(course_id, session_id, position)");
        } catch (Exception ignored) { /* Some vendor SQLite builds omit FTS5. */ }
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE events ADD COLUMN session_id TEXT"); } catch (Exception ignored) { }
        }
        onCreate(db);
    }
}
