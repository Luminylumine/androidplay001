package com.akasha.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.List;

/**
 * App-internal SQLite database (req: 数据存放在数据库内, 应用内部数据, 非全局数据).
 *
 * Schema v1: experiences
 * Schema v2: + pools, experience_pool, agent_pool_access (多经验池 + 动态权限)
 *
 * onConfigure 启用 foreign_keys，保证 ON DELETE CASCADE 真正生效。
 * Future phases (timers, events, model-settings overrides) add tables/columns
 * here via onUpgrade - this is the single extension point for structured data.
 */
public class AppDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "akasha.db";
    private static final int DB_VERSION = 3;

    // ----- experiences (v1, untouched) -----
    public static final String T_EXP = "experiences";
    public static final String C_ID = "id";
    public static final String C_AGENT_ID = "agent_id";
    public static final String C_AGENT_NAME = "agent_name";
    public static final String C_TITLE = "title";
    public static final String C_CONTENT = "content";
    public static final String C_TYPE = "type";   // 'agent' | 'user'
    public static final String C_TIME = "time";
    public static final String C_MEDIA = "media"; // JSON array of file names

    // ----- pools (v2) -----
    public static final String T_POOLS = "pools";
    public static final String CP_NAME = "name";
    public static final String CP_TYPE = "type";          // 0=GLOBAL 1=GROUP
    public static final String CP_ENABLED = "enabled";    // 1 yes 0 no
    public static final String CP_PINNED = "pinned";      // 1 yes 0 no
    public static final String CP_PINNED_AT = "pinned_at";
    public static final String CP_CREATED_AT = "created_at";

    // ----- experience_pool (v2: many-to-many) -----
    public static final String T_EXP_POOL = "experience_pool";
    public static final String CEP_EXP = "experience_id";
    public static final String CEP_POOL = "pool_id";

    // ----- agent_pool_access (v2: membership + R/W/D flags) -----
    public static final String T_AGENT_POOL = "agent_pool_access";
    public static final String CAP_AGENT = "agent_id";
    public static final String CAP_POOL = "pool_id";
    public static final String CAP_FLAGS = "flags";   // 1=R 2=W 4=D, bit OR

    private static volatile AppDb inst;

    public static AppDb get(Context ctx) {
        if (inst == null) {
            synchronized (AppDb.class) {
                if (inst == null) inst = new AppDb(ctx.getApplicationContext());
            }
        }
        return inst;
    }

    private AppDb(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        // 方案 §19: 确保 ON DELETE CASCADE 真正生效
        try { db.setForeignKeyConstraintsEnabled(true); } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // v1 table
        db.execSQL("CREATE TABLE " + T_EXP + " (" +
                C_ID + " TEXT PRIMARY KEY," +
                C_AGENT_ID + " TEXT," +
                C_AGENT_NAME + " TEXT," +
                C_TITLE + " TEXT," +
                C_CONTENT + " TEXT," +
                C_TYPE + " TEXT DEFAULT 'agent'," +
                C_TIME + " INTEGER," +
                C_MEDIA + " TEXT)");
        createV2(db);
        seedGlobal(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.beginTransaction();
        try {
            if (oldV < 2) {
                createV2(db);
                seedGlobal(db);
                migrateV1toV2(db);
            }
            if (oldV < 3) {
                db.execSQL("ALTER TABLE " + T_EXP + " ADD COLUMN last_used_time INTEGER DEFAULT 0");
                db.execSQL("ALTER TABLE " + T_EXP + " ADD COLUMN use_count INTEGER DEFAULT 0");
                db.execSQL("ALTER TABLE " + T_EXP + " ADD COLUMN importance REAL DEFAULT 0.5");
                db.execSQL("ALTER TABLE " + T_EXP + " ADD COLUMN source_session_id TEXT DEFAULT ''");
                db.execSQL("ALTER TABLE " + T_EXP + " ADD COLUMN tags TEXT DEFAULT ''");
                db.execSQL("ALTER TABLE " + T_EXP + " ADD COLUMN user_edited INTEGER DEFAULT 0");
                // usage log table
                db.execSQL("CREATE TABLE IF NOT EXISTS experience_usage_log (" +
                        "id TEXT PRIMARY KEY," +
                        "experience_id TEXT NOT NULL," +
                        "query TEXT," +
                        "used_at INTEGER NOT NULL," +
                        "used_by_agent TEXT," +
                        "FOREIGN KEY (experience_id) REFERENCES " + T_EXP + "(" + C_ID + "))");
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_usage_log_exp ON experience_usage_log(experience_id)");
            }
            // future phases here
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ---------------- helpers ----------------

    private static void createV2(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_POOLS + " (" +
                C_ID + " TEXT PRIMARY KEY," +
                CP_NAME + " TEXT NOT NULL," +
                CP_TYPE + " INTEGER NOT NULL DEFAULT 1," +
                CP_ENABLED + " INTEGER NOT NULL DEFAULT 1," +
                CP_PINNED + " INTEGER NOT NULL DEFAULT 0," +
                CP_PINNED_AT + " INTEGER NOT NULL DEFAULT 0," +
                CP_CREATED_AT + " INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + T_EXP_POOL + " (" +
                CEP_EXP + " TEXT NOT NULL," +
                CEP_POOL + " TEXT NOT NULL," +
                "PRIMARY KEY (" + CEP_EXP + ", " + CEP_POOL + ")," +
                "FOREIGN KEY (" + CEP_EXP + ") REFERENCES " + T_EXP + "(" + C_ID + ") ON DELETE CASCADE," +
                "FOREIGN KEY (" + CEP_POOL + ") REFERENCES " + T_POOLS + "(" + C_ID + ") ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_exp_pool_pool ON " + T_EXP_POOL + "(" + CEP_POOL + ")");
        db.execSQL("CREATE INDEX idx_exp_pool_exp ON " + T_EXP_POOL + "(" + CEP_EXP + ")");

        db.execSQL("CREATE TABLE " + T_AGENT_POOL + " (" +
                CAP_AGENT + " TEXT NOT NULL," +
                CAP_POOL + " TEXT NOT NULL," +
                CAP_FLAGS + " INTEGER NOT NULL DEFAULT 1," +
                "PRIMARY KEY (" + CAP_AGENT + ", " + CAP_POOL + ")," +
                "FOREIGN KEY (" + CAP_POOL + ") REFERENCES " + T_POOLS + "(" + C_ID + ") ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_ap_pool ON " + T_AGENT_POOL + "(" + CAP_POOL + ")");
    }

    /** 插入全局池（每库必存在一条 id=global type=0）。方案 §2.2。 */
    private static void seedGlobal(SQLiteDatabase db) {
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put(C_ID, PoolInfo.GLOBAL_ID);
        v.put(CP_NAME, "全局经验池");
        v.put(CP_TYPE, PoolInfo.TYPE_GLOBAL);
        v.put(CP_ENABLED, 1);
        v.put(CP_PINNED, 1);
        v.put(CP_PINNED_AT, now);
        v.put(CP_CREATED_AT, now);
        db.insertWithOnConflict(T_POOLS, null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /**
     * v1→v2 迁移:
     *  1. 旧 experiences 全部挂到 global 池
     *  2. 旧 Agent: 以 Prefs.models() 为准，逐个给 global 池写默认权限
     *     - permExpRead=true  -> READ  (1)
     *     - permExpWrite=true -> WRITE (2)   (方案§5: 写所有 WRITE 池)
     *     - DELETE 给 4 (保持旧"删除自己的经验"语义简单: 对global有 DELETE 即可)
     */
    private static void migrateV1toV2(SQLiteDatabase db) {
        // 1. experiences -> experience_pool(global)
        Cursor c = db.rawQuery("SELECT " + C_ID + " FROM " + T_EXP, null);
        try {
            db.beginTransaction();
            try {
                while (c.moveToNext()) {
                    String eid = c.getString(0);
                    ContentValues cv = new ContentValues();
                    cv.put(CEP_EXP, eid);
                    cv.put(CEP_POOL, PoolInfo.GLOBAL_ID);
                    db.insertWithOnConflict(T_EXP_POOL, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            c.close();
        }
        // 2. Agent global 默认权限在启动时由 ExpStore.ensureGlobalAccess() 做，
        //    因为这里 AppDb 拿不到 Prefs（防止与 Prefs 环形依赖）。
    }
}
