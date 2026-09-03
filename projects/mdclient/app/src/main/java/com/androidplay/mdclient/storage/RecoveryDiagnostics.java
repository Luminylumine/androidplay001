package com.androidplay.mdclient.storage;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Detects sessions left with an unfinalized PCM file after process death. */
public final class RecoveryDiagnostics {
    public static final class InterruptedSession {
        public final String sessionId;
        public final File pcmPart;
        public InterruptedSession(String sessionId, File pcmPart) { this.sessionId = sessionId; this.pcmPart = pcmPart; }
    }

    private RecoveryDiagnostics() { }

    public static List<InterruptedSession> scan(File sessionsRoot) {
        List<InterruptedSession> result = new ArrayList<>();
        File[] children = sessionsRoot == null ? null : sessionsRoot.listFiles();
        if (children == null) return result;
        for (File child : children) {
            File part = new File(child, "audio.pcm.part");
            if (child.isDirectory() && part.isFile() && part.length() >= 44)
                result.add(new InterruptedSession(child.getName(), part));
        }
        return result;
    }

    public static int markInterrupted(SQLiteDatabase database, File sessionsRoot) {
        int count = 0;
        for (InterruptedSession item : scan(sessionsRoot)) {
            ContentValues values = new ContentValues();
            values.put("status", "interrupted");
            values.put("ended_elapsed_ns", SystemClock.elapsedRealtimeNanos());
            values.put("ended_wall_ms", System.currentTimeMillis());
            count += database.update("sessions", values, "id=? AND status=?", new String[]{item.sessionId, "running"});
        }
        return count;
    }
}
