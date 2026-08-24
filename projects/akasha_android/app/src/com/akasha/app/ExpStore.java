package com.akasha.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.net.Uri;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Experience pool store on top of AppDb (SQLite). v2 扩展:
 *  - pools + experience_pool + agent_pool_access 三张新表
 *  - 旧 listAll() 等价于 listForPool(GLOBAL)，兼容已有 UI 与 Tool
 *  - 旧 record(String agentId,...) 等价于 recordForAgent(...)
 *
 * 接口保持: 旧 exp_* Tool 不需要带 pool_id。方案 §5 / §6 / §7。
 */
public class ExpStore {

    private final Context ctx;
    private final File mediaDir;

    public ExpStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.mediaDir = new File(ctx.getFilesDir(), "exps/media");
        mediaDir.mkdirs();
        ensureGlobalAccess();  // 方案 §19 步骤 6: 每个旧 Agent 补 GLOBAL access
    }

    // ------------- bootstrap: ensure every agent has GLOBAL access row -------------

    /**
     * 按 Prefs.models() 的 permExpRead / permExpWrite，给每个 Agent 生成
     * agent_pool_access(agent, global) 记录。只 insert，不会覆盖用户已改的权限。
     * 方案 §19: onUpgrade 步骤 6 放在这里，避免 AppDb 依赖 Prefs。
     */
    public void ensureGlobalAccess() {
        Prefs prefs;
        try { prefs = new Prefs(ctx); } catch (Throwable t) { return; }
        List<ModelInfo> all = prefs.models();
        if (all == null || all.isEmpty()) return;
        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();
        for (ModelInfo m : all) {
            // 如果此 Agent 已有对 GLOBAL 的 access，尊重现有值不覆盖
            Cursor c = db.query(AppDb.T_AGENT_POOL, new String[]{AppDb.CAP_FLAGS},
                    AppDb.CAP_AGENT + "=? AND " + AppDb.CAP_POOL + "=?",
                    new String[]{m.id, PoolInfo.GLOBAL_ID}, null, null, null);
            boolean exists = c.moveToFirst();
            c.close();
            if (exists) continue;
            int flags = 0;
            if (m.permExpRead)  flags |= PoolInfo.POOL_READ;
            if (m.permExpWrite) flags |= PoolInfo.POOL_WRITE;
            flags |= PoolInfo.POOL_DELETE;  // 旧行为: 允许删自己写的经验
            if (flags == 0) flags = PoolInfo.POOL_READ; // 至少可读
            ContentValues cv = new ContentValues();
            cv.put(AppDb.CAP_AGENT, m.id);
            cv.put(AppDb.CAP_POOL, PoolInfo.GLOBAL_ID);
            cv.put(AppDb.CAP_FLAGS, flags);
            db.insertWithOnConflict(AppDb.T_AGENT_POOL, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }

    // ========================================================================
    //  旧版"单全局池"兼容 API (直接代理到 GLOBAL 池)
    // ========================================================================

    public List<Experience> listAll() {
        return listForPool(PoolInfo.GLOBAL_ID);
    }

    public List<Experience> search(String query, int limit) {
        // 兼容旧行为: 搜索范围 = GLOBAL 池
        return searchInPool(PoolInfo.GLOBAL_ID, query, limit);
    }

    /** Agent records an experience with optional recent screenshots. */
    public Experience record(String agentId, String agentName, String title,
                             String content, List<Bitmap> shots) {
        return recordForAgent(agentId, agentName, title, content, shots);
    }

    /** User publish (朋友圈-style): text + optional images/video copied in. */
    public Experience publishUser(String title, String content, List<Uri> uris) {
        // 用户发布的经验进入 GLOBAL 池；未来如需多选池可在这里增强
        Experience e = buildUserExp(title, content, uris);
        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();
        db.beginTransaction();
        try {
            insertExp(db, e);
            linkExpToPool(db, e.id, PoolInfo.GLOBAL_ID);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        applyRetention();
        return e;
    }

    /** User (or any caller) deletes an experience by id. */
    public void remove(String id) {
        if (id == null) return;
        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();
        db.beginTransaction();
        try {
            // experience_pool 因 ON DELETE CASCADE 自动清理
            db.delete(AppDb.T_EXP, AppDb.C_ID + "=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        deleteMedia(id);
    }

    /** Agent deletes its OWN experiences: by exact id, else by exact title. */
    public int removeByAgent(String agentId, String idOrTitle) {
        if (agentId == null || idOrTitle == null || idOrTitle.isEmpty()) return 0;
        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        Cursor c = db.query(AppDb.T_EXP, new String[]{AppDb.C_ID},
                AppDb.C_ID + "=? AND " + AppDb.C_AGENT_ID + "=?",
                new String[]{idOrTitle, agentId}, null, null, null);
        boolean byId = c.moveToFirst();
        c.close();
        if (byId) {
            // 方案 §7: 还需校验 Agent 对该经验所在至少一个池有 DELETE 权限且池 enabled
            if (!canAgentDeleteExp(agentId, idOrTitle)) return 0;
            remove(idOrTitle);
            return 1;
        }
        Cursor c2 = db.query(AppDb.T_EXP, new String[]{AppDb.C_ID},
                AppDb.C_TITLE + "=? AND " + AppDb.C_AGENT_ID + "=?",
                new String[]{idOrTitle, agentId}, null, null, null);
        List<String> ids = new ArrayList<>();
        while (c2.moveToNext()) {
            String eid = c2.getString(0);
            if (canAgentDeleteExp(agentId, eid)) ids.add(eid);
        }
        c2.close();
        for (String id : ids) remove(id);
        return ids.size();
    }

    public int count() {
        return countInPool(PoolInfo.GLOBAL_ID);
    }

    /** Drop entries older than 保留时间 and/or overflow beyond 保留大小. */
    public void applyRetention() {
        Prefs p = new Prefs(ctx);
        long maxAge = p.expRetainDays() > 0 ? p.expRetainDays() * 86400000L : 0;
        int maxCount = p.expRetainMax();
        if (maxAge > 0) {
            long cutoff = System.currentTimeMillis() - maxAge;
            SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
            Cursor c = db.query(AppDb.T_EXP, new String[]{AppDb.C_ID},
                    AppDb.C_TIME + " < ?", new String[]{String.valueOf(cutoff)},
                    null, null, null);
            List<String> ids = new ArrayList<>();
            while (c.moveToNext()) ids.add(c.getString(0));
            c.close();
            for (String id : ids) remove(id);
        }
        if (maxCount > 0) {
            SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
            Cursor c = db.rawQuery("SELECT " + AppDb.C_ID + " FROM " + AppDb.T_EXP
                    + " ORDER BY " + AppDb.C_TIME + " DESC LIMIT -1 OFFSET " + maxCount, null);
            List<String> ids = new ArrayList<>();
            while (c.moveToNext()) ids.add(c.getString(0));
            c.close();
            for (String id : ids) remove(id);
        }
    }

    // ========================================================================
    //  v2 Pool CRUD (方案 §12)
    // ========================================================================

    /**
     * 列出所有池，含经验条数，按置顶 → pinned_at → 创建时间倒序。
     * 方案 §21: 直接 JOIN COUNT，不做缓存列。
     */
    public List<PoolInfo> listPools() {
        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        String sql = "SELECT p.*, COUNT(ep." + AppDb.CEP_EXP + ") AS exp_count" +
                " FROM " + AppDb.T_POOLS + " p" +
                " LEFT JOIN " + AppDb.T_EXP_POOL + " ep ON ep." + AppDb.CEP_POOL + "=p." + AppDb.C_ID +
                " GROUP BY p." + AppDb.C_ID +
                " ORDER BY p." + AppDb.CP_PINNED + " DESC, p." + AppDb.CP_PINNED_AT + " DESC,"
                + " p." + AppDb.CP_CREATED_AT + " DESC";
        List<PoolInfo> out = new ArrayList<>();
        Cursor c = db.rawQuery(sql, null);
        try {
            while (c.moveToNext()) out.add(poolFromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    public PoolInfo getPool(String poolId) {
        if (poolId == null) return null;
        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        Cursor c = db.query(AppDb.T_POOLS, null, AppDb.C_ID + "=?",
                new String[]{poolId}, null, null, null);
        try {
            if (c.moveToNext()) return poolFromCursor(c);
        } finally {
            c.close();
        }
        return null;
    }

    /** 创建 GROUP 池，成功返回新 pool id。空名直接拒绝。 */
    public String createPool(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return null;
        long now = System.currentTimeMillis();
        String id = "p" + now + (int) (Math.random() * 900 + 100);
        ContentValues cv = new ContentValues();
        cv.put(AppDb.C_ID, id);
        cv.put(AppDb.CP_NAME, n);
        cv.put(AppDb.CP_TYPE, PoolInfo.TYPE_GROUP);
        cv.put(AppDb.CP_ENABLED, 1);
        cv.put(AppDb.CP_PINNED, 0);
        cv.put(AppDb.CP_PINNED_AT, 0);
        cv.put(AppDb.CP_CREATED_AT, now);
        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();
        long rc = db.insertWithOnConflict(AppDb.T_POOLS, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        return rc < 0 ? null : id;
    }

    public boolean setPoolEnabled(String poolId, boolean enabled) {
        if (poolId == null) return false;
        ContentValues cv = new ContentValues();
        cv.put(AppDb.CP_ENABLED, enabled ? 1 : 0);
        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();
        return db.update(AppDb.T_POOLS, cv, AppDb.C_ID + "=?", new String[]{poolId}) > 0;
    }

    public boolean setPoolPinned(String poolId, boolean pinned) {
        if (poolId == null) return false;
        ContentValues cv = new ContentValues();
        cv.put(AppDb.CP_PINNED, pinned ? 1 : 0);
        cv.put(AppDb.CP_PINNED_AT, pinned ? System.currentTimeMillis() : 0);
        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();
        return db.update(AppDb.T_POOLS, cv, AppDb.C_ID + "=?", new String[]{poolId}) > 0;
    }

    /**
     * 删除 GROUP 池。GLOBAL 拒绝删除 (方案 §2.2 / §8)。
     * ON DELETE CASCADE 自动清 experience_pool + agent_pool_access。
     * 之后清 orphan experiences（不属于任何池的）。方案 §8。
     */
    public boolean deletePool(String poolId) {
        PoolInfo p = getPool(poolId);
        if (p == null) return false;
        if (p.isGlobal()) return false;  // GLOBAL 不可删
        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(AppDb.T_POOLS, AppDb.C_ID + "=?", new String[]{poolId});
            // 清 orphan experiences（不再挂在任何池下的经验）
            db.execSQL("DELETE FROM " + AppDb.T_EXP + " WHERE NOT EXISTS (" +
                    "SELECT 1 FROM " + AppDb.T_EXP_POOL + " ep WHERE ep." +
                    AppDb.CEP_EXP + "=" + AppDb.T_EXP + "." + AppDb.C_ID + ")");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        cleanupOrphanMedia();
        return true;
    }

    // ========================================================================
    //  v2: Pool-level listing / searchForAgent / recordForAgent / deleteForAgent
    // ========================================================================

    public List<Experience> listForPool(String poolId) {
        return searchInPool(poolId, null, 0);
    }

    /**
     * Agent 视角搜索（方案 §6）：搜所有 enabled + 自己有 READ 权限的池，
     * 多池共享的同一条 Experience 通过 DISTINCT 去重。
     */
    public List<Experience> searchForAgent(String agentId, String query) {
        if (agentId == null) return new ArrayList<>();
        String q = query == null ? "" : query.trim();
        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        String sql = "SELECT DISTINCT e.* FROM " + AppDb.T_EXP + " e" +
                " JOIN " + AppDb.T_EXP_POOL + " ep ON ep." + AppDb.CEP_EXP + "=e." + AppDb.C_ID +
                " JOIN " + AppDb.T_POOLS + " p ON p." + AppDb.C_ID + "=ep." + AppDb.CEP_POOL +
                " JOIN " + AppDb.T_AGENT_POOL + " a ON a." + AppDb.CAP_POOL + "=p." + AppDb.C_ID +
                " WHERE a." + AppDb.CAP_AGENT + "=?" +
                " AND (a." + AppDb.CAP_FLAGS + " & " + PoolInfo.POOL_READ + ") != 0" +
                " AND p." + AppDb.CP_ENABLED + "=1";
        List<String> args = new ArrayList<>();
        args.add(agentId);
        if (!q.isEmpty()) {
            sql += " AND (e." + AppDb.C_TITLE + " LIKE ? OR e." + AppDb.C_CONTENT + " LIKE ?"
                    + " OR e." + AppDb.C_AGENT_NAME + " LIKE ?)";
            String p = "%" + q + "%";
            args.add(p); args.add(p); args.add(p);
        }
        sql += " ORDER BY e." + AppDb.C_TIME + " DESC";
        Cursor c = db.rawQuery(sql, args.toArray(new String[0]));
        List<Experience> out = new ArrayList<>();
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Agent 写入经验（方案 §5）：把 Experience 本体 + 所有 enabled + WRITE 的池的
     * experience_pool 映射一次性事务写入。无任何可写池返回 null（上层抛 EXP_NO_WRITE_PERM）。
     */
    public Experience recordForAgent(String agentId, String agentName, String title,
                                     String content, List<Bitmap> shots) {
        if (agentId == null) return null;
        List<String> writable = listWritablePools(agentId);
        if (writable.isEmpty()) return null;
        Experience e = new Experience();
        e.id = newId();
        e.agentId = agentId;
        e.agentName = agentName == null ? "" : agentName;
        e.title = title;
        e.content = content;
        e.type = "agent";
        e.time = System.currentTimeMillis();
        if (shots != null) {
            int n = 0;
            for (Bitmap b : shots) {
                if (b == null) continue;
                String name = e.id + "_" + n + ".jpg";
                if (writeJpeg(new File(mediaDir, name), b)) {
                    e.media.add(name);
                    n++;
                }
            }
        }
        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();
        db.beginTransaction();
        try {
            insertExp(db, e);
            for (String pid : writable) linkExpToPool(db, e.id, pid);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        applyRetention();
        return e;
    }

    /**
     * Agent 删除自己的经验（方案 §7）：
     *   author=agentId 且 该经验至少在 1 个 池 enabled + Agent 有 DELETE 权限 的池里
     * 满足才删 Experience 本体，experience_pool 级联全清，媒体文件 best-effort 删除。
     */
    public boolean deleteForAgent(String agentId, String idOrTitle) {
        return removeByAgent(agentId, idOrTitle) > 0;
    }

    // ========================================================================
    //  Agent-Pool 权限（读/写/删 flags 管理）—— 由 Perms facade 统一调用
    // ========================================================================

    /** Agent 有哪些 enabled + READ 的池（用于 search 成员关系展示等）。 */
    public List<String> listReadablePools(String agentId) {
        return listAgentPoolsByFlag(agentId, PoolInfo.POOL_READ, true);
    }
    /** Agent 有哪些 enabled + WRITE 的池（recordForAgent 插入经验映射用）。 */
    public List<String> listWritablePools(String agentId) {
        return listAgentPoolsByFlag(agentId, PoolInfo.POOL_WRITE, true);
    }
    /** Agent 是否对某池具备某 flag（flag 位为 1）。 */
    public boolean hasPoolFlag(String agentId, String poolId, int flag) {
        if (agentId == null || poolId == null) return false;
        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        Cursor c = db.query(AppDb.T_AGENT_POOL, new String[]{AppDb.CAP_FLAGS},
                AppDb.CAP_AGENT + "=? AND " + AppDb.CAP_POOL + "=?",
                new String[]{agentId, poolId}, null, null, null);
        try {
            if (c.moveToFirst()) {
                int f = c.getInt(0);
                return (f & flag) == flag;
            }
        } finally {
            c.close();
        }
        return false;
    }
    /** 返回 (agent,pool) 的 flags 整数值，无记录返回 0。 */
    public int poolFlags(String agentId, String poolId) {
        if (agentId == null || poolId == null) return 0;
        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        Cursor c = db.query(AppDb.T_AGENT_POOL, new String[]{AppDb.CAP_FLAGS},
                AppDb.CAP_AGENT + "=? AND " + AppDb.CAP_POOL + "=?",
                new String[]{agentId, poolId}, null, null, null);
        try {
            if (c.moveToFirst()) return c.getInt(0);
        } finally {
            c.close();
        }
        return 0;
    }
    /** 写入 (agent,pool) 的 flags：flags==0 时删除行表示完全脱离。 */
    public void setPoolFlags(String agentId, String poolId, int flags) {
        if (agentId == null || poolId == null) return;
        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();
        if (flags == 0) {
            db.delete(AppDb.T_AGENT_POOL,
                    AppDb.CAP_AGENT + "=? AND " + AppDb.CAP_POOL + "=?",
                    new String[]{agentId, poolId});
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put(AppDb.CAP_AGENT, agentId);
        cv.put(AppDb.CAP_POOL, poolId);
        cv.put(AppDb.CAP_FLAGS, flags);
        db.insertWithOnConflict(AppDb.T_AGENT_POOL, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ========================================================================
    //  Media helpers (保留原签名，供 ExperiencePoolActivity 复用)
    // ========================================================================

    public File mediaFile(Experience e, int i) {
        if (e == null || i < 0 || i >= e.media.size()) return null;
        File f = new File(mediaDir, e.media.get(i));
        return f.isFile() ? f : null;
    }

    public boolean isVideo(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm")
                || n.endsWith(".3gp") || n.endsWith(".mov");
    }

    // ========================================================================
    //  Internals
    // ========================================================================

    private List<String> listAgentPoolsByFlag(String agentId, int flag, boolean enabledOnly) {
        if (agentId == null) return new ArrayList<>();
        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        String sql = "SELECT DISTINCT a." + AppDb.CAP_POOL +
                " FROM " + AppDb.T_AGENT_POOL + " a" +
                " JOIN " + AppDb.T_POOLS + " p ON p." + AppDb.C_ID + "=a." + AppDb.CAP_POOL +
                " WHERE a." + AppDb.CAP_AGENT + "=?" +
                " AND (a." + AppDb.CAP_FLAGS + " & " + flag + ") != 0";
        if (enabledOnly) sql += " AND p." + AppDb.CP_ENABLED + "=1";
        Cursor c = db.rawQuery(sql, new String[]{agentId});
        List<String> out = new ArrayList<>();
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    /** 方案 §7: Agent 能不能删除该经验。条件: author 匹配 且 至少 1 个所属池 enabled + DELETE。 */
    private boolean canAgentDeleteExp(String agentId, String expId) {
        if (agentId == null || expId == null) return false;
        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        // author match
        Cursor c0 = db.query(AppDb.T_EXP, new String[]{AppDb.C_AGENT_ID},
                AppDb.C_ID + "=?", new String[]{expId}, null, null, null);
        boolean self = false;
        try {
            if (c0.moveToFirst()) {
                self = agentId.equals(c0.getString(0));
            }
        } finally { c0.close(); }
        if (!self) return false;
        // 至少存在一个池: (exp 隶属) ∩ (agent 有 DELETE) ∩ (enabled)
        String sql = "SELECT 1 FROM " + AppDb.T_EXP_POOL + " ep" +
                " JOIN " + AppDb.T_AGENT_POOL + " a ON a." + AppDb.CAP_POOL + "=ep." + AppDb.CEP_POOL +
                " JOIN " + AppDb.T_POOLS + " p ON p." + AppDb.C_ID + "=ep." + AppDb.CEP_POOL +
                " WHERE ep." + AppDb.CEP_EXP + "=?" +
                " AND a." + AppDb.CAP_AGENT + "=?" +
                " AND (a." + AppDb.CAP_FLAGS + " & " + PoolInfo.POOL_DELETE + ") != 0" +
                " AND p." + AppDb.CP_ENABLED + "=1" +
                " LIMIT 1";
        Cursor c = db.rawQuery(sql, new String[]{expId, agentId});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    private List<Experience> searchInPool(String poolId, String like, int limit) {
        if (poolId == null) return new ArrayList<>();
        List<Experience> out = new ArrayList<>();
        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        String sql = "SELECT e.* FROM " + AppDb.T_EXP + " e" +
                " JOIN " + AppDb.T_EXP_POOL + " ep ON ep." + AppDb.CEP_EXP + "=e." + AppDb.C_ID +
                " WHERE ep." + AppDb.CEP_POOL + "=?";
        List<String> args = new ArrayList<>();
        args.add(poolId);
        if (like != null && !like.isEmpty()) {
            sql += " AND (e." + AppDb.C_TITLE + " LIKE ? OR e." + AppDb.C_AGENT_NAME
                    + " LIKE ? OR e." + AppDb.C_CONTENT + " LIKE ?)";
            String p = "%" + like + "%";
            args.add(p); args.add(p); args.add(p);
        }
        sql += " ORDER BY e." + AppDb.C_TIME + " DESC";
        if (limit > 0) sql += " LIMIT " + limit;
        Cursor c = db.rawQuery(sql, args.toArray(new String[0]));
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    private int countInPool(String poolId) {
        if (poolId == null) return 0;
        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + AppDb.T_EXP_POOL +
                " WHERE " + AppDb.CEP_POOL + "=?", new String[]{poolId});
        try {
            c.moveToFirst();
            return c.getInt(0);
        } finally {
            c.close();
        }
    }

    private PoolInfo poolFromCursor(Cursor c) {
        PoolInfo p = new PoolInfo();
        p.id = c.getString(c.getColumnIndexOrThrow(AppDb.C_ID));
        p.name = c.getString(c.getColumnIndexOrThrow(AppDb.CP_NAME));
        p.type = c.getInt(c.getColumnIndexOrThrow(AppDb.CP_TYPE));
        p.enabled = c.getInt(c.getColumnIndexOrThrow(AppDb.CP_ENABLED)) != 0;
        p.pinned = c.getInt(c.getColumnIndexOrThrow(AppDb.CP_PINNED)) != 0;
        p.pinnedAt = c.getLong(c.getColumnIndexOrThrow(AppDb.CP_PINNED_AT));
        p.createdAt = c.getLong(c.getColumnIndexOrThrow(AppDb.CP_CREATED_AT));
        try { p.expCount = c.getInt(c.getColumnIndexOrThrow("exp_count")); }
        catch (Exception ignored) {}
        return p;
    }

    private Experience fromCursor(Cursor c) {
        Experience e = new Experience();
        e.id = c.getString(c.getColumnIndexOrThrow(AppDb.C_ID));
        e.agentId = c.getString(c.getColumnIndexOrThrow(AppDb.C_AGENT_ID));
        e.agentName = c.getString(c.getColumnIndexOrThrow(AppDb.C_AGENT_NAME));
        e.title = c.getString(c.getColumnIndexOrThrow(AppDb.C_TITLE));
        e.content = c.getString(c.getColumnIndexOrThrow(AppDb.C_CONTENT));
        e.type = c.getString(c.getColumnIndexOrThrow(AppDb.C_TYPE));
        e.time = c.getLong(c.getColumnIndexOrThrow(AppDb.C_TIME));
        String media = c.getString(c.getColumnIndexOrThrow(AppDb.C_MEDIA));
        if (media != null) {
            try {
                JSONArray a = new JSONArray(media);
                for (int i = 0; i < a.length(); i++) e.media.add(a.getString(i));
            } catch (Exception ignored) {}
        }
        e.lastUsedTime = c.getLong(c.getColumnIndexOrThrow("last_used_time"));
        e.useCount = c.getInt(c.getColumnIndexOrThrow("use_count"));
        e.importance = c.getDouble(c.getColumnIndexOrThrow("importance"));
        e.sourceSessionId = c.getString(c.getColumnIndexOrThrow("source_session_id"));
        e.tags = c.getString(c.getColumnIndexOrThrow("tags"));
        e.userEdited = c.getInt(c.getColumnIndexOrThrow("user_edited")) != 0;
        return e;
    }

    private Experience buildUserExp(String title, String content, List<Uri> uris) {
        Experience e = new Experience();
        e.id = newId();
        e.agentId = "user";
        e.agentName = "用户";
        e.title = title;
        e.content = content;
        e.type = "user";
        e.time = System.currentTimeMillis();
        if (uris != null) {
            int n = 0;
            for (Uri u : uris) {
                if (u == null) continue;
                String ext = guessExt(u);
                String name = e.id + "_" + n + ext;
                if (copyToMedia(u, new File(mediaDir, name))) {
                    e.media.add(name);
                    n++;
                }
            }
        }
        return e;
    }

    private void insertExp(SQLiteDatabase db, Experience e) {
        ContentValues v = new ContentValues();
        v.put(AppDb.C_ID, e.id);
        v.put(AppDb.C_AGENT_ID, e.agentId == null ? "" : e.agentId);
        v.put(AppDb.C_AGENT_NAME, e.agentName == null ? "" : e.agentName);
        v.put(AppDb.C_TITLE, e.title == null ? "" : e.title);
        v.put(AppDb.C_CONTENT, e.content == null ? "" : e.content);
        v.put(AppDb.C_TYPE, e.type == null ? "agent" : e.type);
        v.put(AppDb.C_TIME, e.time);
        JSONArray a = new JSONArray();
        for (String s : e.media) a.put(s);
        v.put(AppDb.C_MEDIA, a.toString());
        v.put("last_used_time", e.lastUsedTime);
        v.put("use_count", e.useCount);
        v.put("importance", e.importance);
        v.put("source_session_id", e.sourceSessionId == null ? "" : e.sourceSessionId);
        v.put("tags", e.tags == null ? "" : e.tags);
        v.put("user_edited", e.userEdited ? 1 : 0);
        db.insertWithOnConflict(AppDb.T_EXP, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private void linkExpToPool(SQLiteDatabase db, String expId, String poolId) {
        ContentValues cv = new ContentValues();
        cv.put(AppDb.CEP_EXP, expId);
        cv.put(AppDb.CEP_POOL, poolId);
        db.insertWithOnConflict(AppDb.T_EXP_POOL, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** 把已存在的经验追加挂到一个额外池；用于用户从某组池页发布时，同步显示到 GLOBAL/其他池。 */
    public void linkExpToExtraPool(String expId, String extraPoolId) {
        if (expId == null || extraPoolId == null) return;
        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();
        linkExpToPool(db, expId, extraPoolId);
    }

    private void deleteMedia(String id) {
        if (id == null) return;
        File[] fs = mediaDir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().startsWith(id + "_")) f.delete();
        }
    }

    /** 删除没有任何 experience_pool 引用的媒体文件（best-effort）。 */
    private void cleanupOrphanMedia() {
        File[] fs = mediaDir.listFiles();
        if (fs == null) return;
        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        for (File f : fs) {
            String name = f.getName();
            int us = name.indexOf('_');
            if (us <= 0) continue;
            String eid = name.substring(0, us);
            Cursor c = db.rawQuery("SELECT 1 FROM " + AppDb.T_EXP + " WHERE "
                    + AppDb.C_ID + "=? LIMIT 1", new String[]{eid});
            boolean exists = c.moveToFirst();
            c.close();
            if (!exists) f.delete();
        }
    }

    private static String newId() {
        return "e" + System.currentTimeMillis() + (int) (Math.random() * 900 + 100);
    }

    private boolean writeJpeg(File f, Bitmap bmp) {
        try {
            OutputStream os = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, os);
            os.close();
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean copyToMedia(Uri uri, File dst) {
        try {
            InputStream is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) return false;
            OutputStream os = new FileOutputStream(dst);
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            is.close();
            os.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String guessExt(Uri uri) {
        String t = null;
        try {
            t = ctx.getContentResolver().getType(uri);
        } catch (Exception ignored) {}
        if (t != null) {
            if (t.startsWith("video/")) return ".mp4";
            if (t.startsWith("image/")) return ".jpg";
        }
        String p = uri.getPath();
        if (p != null) {
            int dot = p.lastIndexOf('.');
            if (dot >= 0 && p.length() - dot <= 5) return p.substring(dot);
        }
        return ".jpg";
    }

    // ========================================================================
    //  v3: Smart retrieval, usage tracking, retention scoring, auto-record
    // ========================================================================

    /**
     * Retrieve top-K relevant experiences for context building.
     * Scores: 0.4*textRelevance + 0.2*scopeMatch + 0.2*importance + 0.1*recency + 0.1*usageBoost
     * Returns formatted text (maxChars total) for injection into system prompt.
     * Also increments usage count for matched experiences.
     */
    public String retrieveRelevant(String goal, String foregroundPkg, String agentId, int k, int maxChars) {
        List<Experience> all = listAll();
        if (all.isEmpty()) return "";

        String lGoal = goal == null ? "" : goal.toLowerCase(java.util.Locale.ROOT);
        String lPkg = foregroundPkg == null ? "" : foregroundPkg.toLowerCase(java.util.Locale.ROOT);

        java.util.List<double[]> scored = new java.util.ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Experience e = all.get(i);
            double score = computeRetrievalScore(e, lGoal, lPkg);
            scored.add(new double[]{i, score});
        }

        java.util.Collections.sort(scored, new java.util.Comparator<double[]>() {
            @Override public int compare(double[] a, double[] b) {
                return Double.compare(b[1], a[1]);
            }
        });

        StringBuilder sb = new StringBuilder();
        int count = 0;
        int totalLen = 0;

        for (double[] pair : scored) {
            if (count >= k) break;
            Experience e = all.get((int) pair[0]);

            String header = String.format("[重要性:%.1f, 被引:%d次] %s",
                    e.importance, e.useCount, e.title);
            String body = e.content == null ? "" : e.content;
            if (body.length() > 200) body = body.substring(0, 200) + "…";
            String entry = header + "\n" + body + "\n";

            if (totalLen + entry.length() > maxChars) break;
            sb.append(entry);
            totalLen += entry.length();
            count++;

            incrementUsage(e.id, goal, agentId);
        }

        if (count == 0) return "";
        return "【相关经验】\n" + sb.toString();
    }

    private double computeRetrievalScore(Experience e, String lGoal, String lPkg) {
        String lTitle = e.title == null ? "" : e.title.toLowerCase(java.util.Locale.ROOT);
        String lContent = e.content == null ? "" : e.content.toLowerCase(java.util.Locale.ROOT);
        String lTags = e.tags == null ? "" : e.tags.toLowerCase(java.util.Locale.ROOT);

        double textScore = 0;
        if (!lGoal.isEmpty()) {
            String[] words = lGoal.split("[\\s,，。.?!？！；;:：()（）\\[\\]【】\"'']+");
            int hits = 0;
            int total = 0;
            for (String w : words) {
                if (w.length() < 2) continue;
                total++;
                if (lTitle.contains(w)) hits += 2;
                else if (lContent.contains(w) || lTags.contains(w)) hits += 1;
            }
            textScore = total > 0 ? (double) hits / (total * 2) : 0;
        }

        double scopeScore = 0;
        if (!lPkg.isEmpty() && !lTags.isEmpty()) {
            String[] tagArr = lTags.split(",");
            for (String t : tagArr) {
                if (t.trim().isEmpty()) continue;
                if (lPkg.contains(t.trim()) || t.trim().contains(lPkg)) {
                    scopeScore = 1.0;
                    break;
                }
            }
        }

        long now = System.currentTimeMillis();
        long refTime = e.lastUsedTime > 0 ? e.lastUsedTime : e.time;
        double ageDays = Math.max(0, (now - refTime) / 86400000.0);
        double recency = Math.exp(-ageDays / 30.0);

        double usageBoost = Math.min(1.0, e.useCount * 0.1);

        return 0.4 * textScore + 0.2 * scopeScore + 0.2 * e.importance + 0.1 * recency + 0.1 * usageBoost;
    }

    /** Increment usage count and update lastUsedTime for an experience. */
    public void incrementUsage(String expId, String query, String agentId) {
        if (expId == null) return;
        long now = System.currentTimeMillis();
        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();

        db.execSQL("UPDATE " + AppDb.T_EXP + " SET last_used_time=?, use_count=use_count+1 WHERE " + AppDb.C_ID + "=?",
                new Object[]{now, expId});

        try {
            ContentValues log = new ContentValues();
            log.put("id", "u" + now + "_" + (int)(Math.random()*9000+1000));
            log.put("experience_id", expId);
            log.put("query", query == null ? "" : query);
            log.put("used_at", now);
            log.put("used_by_agent", agentId == null ? "" : agentId);
            db.insert("experience_usage_log", null, log);
        } catch (Exception ignored) {}
    }

    /** LRU retention: delete low-score experiences when over limit. */
    public void applyRetentionWithScore() {
        Prefs p = new Prefs(ctx);
        int maxCount = p.expRetainMax();
        if (maxCount <= 0) maxCount = 500;

        long maxAge = p.expRetainDays() > 0 ? p.expRetainDays() * 86400000L : 0;
        long now = System.currentTimeMillis();

        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();

        if (maxAge > 0) {
            long cutoff = now - maxAge;
            Cursor c = db.rawQuery("SELECT " + AppDb.C_ID + " FROM " + AppDb.T_EXP +
                    " WHERE time < ? AND use_count = 0 AND importance < 0.5 AND (user_edited IS NULL OR user_edited = 0)",
                    new String[]{String.valueOf(cutoff)});
            List<String> expired = new ArrayList<>();
            while (c.moveToNext()) expired.add(c.getString(0));
            c.close();
            for (String id : expired) remove(id);
        }

        int total = count();
        if (total > maxCount) {
            int toRemove = total - maxCount;
            List<String> toDelete = new ArrayList<>();

            Cursor delCursor = db.rawQuery(
                    "SELECT " + AppDb.C_ID + " FROM " + AppDb.T_EXP +
                    " WHERE (user_edited IS NULL OR user_edited = 0) AND importance < 0.9" +
                    " ORDER BY importance ASC, use_count ASC, last_used_time ASC LIMIT ?",
                    new String[]{String.valueOf(toRemove)});
            while (delCursor.moveToNext()) {
                toDelete.add(delCursor.getString(0));
            }
            delCursor.close();

            for (String id : toDelete) remove(id);
        }
    }

    /** Auto-record an experience with deduplication and importance. */
    public Experience autoRecord(String agentId, String agentName, String title,
                                 String content, double importance, String sourceSessionId, String tags) {
        if (agentId == null || title == null || title.trim().isEmpty()) return null;

        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        Cursor c = db.rawQuery("SELECT " + AppDb.C_ID + ", " + AppDb.C_TIME + " FROM " + AppDb.T_EXP +
                " WHERE " + AppDb.C_AGENT_ID + "=? AND " + AppDb.C_TITLE + " LIKE ? AND type='auto'" +
                " ORDER BY " + AppDb.C_TIME + " DESC LIMIT 1",
                new String[]{agentId, title.trim()});
        try {
            if (c.moveToFirst()) {
                long existingTime = c.getLong(1);
                long ageMs = System.currentTimeMillis() - existingTime;
                if (ageMs < 86400000L) {
                    return null;
                }
            }
        } finally {
            c.close();
        }

        Experience e = new Experience();
        e.id = newId();
        e.agentId = agentId;
        e.agentName = agentName == null ? "" : agentName;
        e.title = title;
        e.content = content;
        e.type = "auto";
        e.time = System.currentTimeMillis();
        e.importance = importance;
        e.sourceSessionId = sourceSessionId == null ? "" : sourceSessionId;
        e.tags = tags == null ? "" : tags;
        e.lastUsedTime = e.time;
        e.useCount = 0;
        e.userEdited = false;

        List<String> writable = listWritablePools(agentId);
        if (writable.isEmpty()) {
            writable.add(PoolInfo.GLOBAL_ID);
        }

        SQLiteDatabase wdb = AppDb.get(ctx).getWritableDatabase();
        wdb.beginTransaction();
        try {
            insertExp(wdb, e);
            for (String pid : writable) linkExpToPool(wdb, e.id, pid);
            wdb.setTransactionSuccessful();
        } finally {
            wdb.endTransaction();
        }
        applyRetentionWithScore();
        return e;
    }

    /** Update an experience's title/content by user. Sets userEdited=true and boosts importance. */
    public boolean update(String id, String title, String content) {
        if (id == null) return false;
        ContentValues cv = new ContentValues();
        if (title != null) cv.put(AppDb.C_TITLE, title);
        if (content != null) cv.put(AppDb.C_CONTENT, content);
        cv.put("user_edited", 1);
        cv.put("importance", Math.min(1.0, getImportance(id) + 0.2));
        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();
        return db.update(AppDb.T_EXP, cv, AppDb.C_ID + "=?", new String[]{id}) > 0;
    }

    /** Get importance of an experience. Returns 0.5 default. */
    public double getImportance(String id) {
        if (id == null) return 0.5;
        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        Cursor c = db.query(AppDb.T_EXP, new String[]{"importance"},
                AppDb.C_ID + "=?", new String[]{id}, null, null, null);
        try {
            if (c.moveToFirst()) return c.getDouble(0);
        } finally {
            c.close();
        }
        return 0.5;
    }

    /** Set importance directly (for user marking). */
    public boolean setImportance(String id, double importance) {
        if (id == null) return false;
        ContentValues cv = new ContentValues();
        cv.put("importance", Math.max(0, Math.min(1.0, importance)));
        if (importance >= 0.9) cv.put("user_edited", 1);
        SQLiteDatabase db = AppDb.get(ctx).getWritableDatabase();
        return db.update(AppDb.T_EXP, cv, AppDb.C_ID + "=?", new String[]{id}) > 0;
    }

    /** Get a single experience by ID. */
    public Experience getById(String id) {
        if (id == null) return null;
        SQLiteDatabase db = AppDb.get(ctx).getReadableDatabase();
        Cursor c = db.query(AppDb.T_EXP, null, AppDb.C_ID + "=?",
                new String[]{id}, null, null, null);
        try {
            if (c.moveToFirst()) return fromCursor(c);
        } finally {
            c.close();
        }
        return null;
    }
}
