package com.akasha.app;

import android.content.Context;

/**
 * 统一权限判断 facade (方案 §4.3)。
 *  - 静态能力（shell/a11y/file/...）继续读 ModelInfo 内建字段
 *  - Pool 成员/权限：读 agent_pool_access.flags 位
 *
 * 未来所有 Tool / Service / UI 一律问 Perms.canXxx()，
 * 不直接读 ModelInfo 或 agent_pool_access，方便后续扩展 ADMIN/SHARE 等。
 */
public final class Perms {

    private Perms() {}

    // ---------- static capability flags (in ModelInfo boolean fields) ----------

    public static boolean canShell(Context ctx, String agentId) {
        return modelFlag(ctx, agentId, 1);
    }
    public static boolean canA11y(Context ctx, String agentId) {
        return modelFlag(ctx, agentId, 2);
    }
    public static boolean canFile(Context ctx, String agentId) {
        return modelFlag(ctx, agentId, 3);
    }
    public static boolean canPhoto(Context ctx, String agentId) {
        return modelFlag(ctx, agentId, 4);
    }
    public static boolean canMedia(Context ctx, String agentId) {
        return modelFlag(ctx, agentId, 5);
    }
    public static boolean canMusic(Context ctx, String agentId) {
        return modelFlag(ctx, agentId, 6);
    }
    // 历史兼容名 (permExpRead/permExpWrite 在 Pool 级别，这里返回 true 仅表示 UI 默认允许)
    @Deprecated
    public static boolean canExpAny(Context ctx, String agentId) {
        return modelFlag(ctx, agentId, 7) || modelFlag(ctx, agentId, 8);
    }

    private static boolean modelFlag(Context ctx, String agentId, int which) {
        if (ctx == null || agentId == null) return false;
        ModelInfo m = new Prefs(ctx).effectiveModel(agentId);
        if (m == null) return false;
        switch (which) {
            case 1: return m.permShell;
            case 2: return m.permA11y;
            case 3: return m.permFile;
            case 4: return m.permPhoto;
            case 5: return m.permMedia;
            case 6: return m.permMusic;
            case 7: return m.permExpRead;
            case 8: return m.permExpWrite;
        }
        return false;
    }

    // ---------- Pool 动态权限 (agent_pool_access.flags 位) ----------

    /** Agent 对某池有 READ（= 所在组成员） 且 池 enabled。 */
    public static boolean canReadPool(Context ctx, String agentId, String poolId) {
        return hasPoolFlag(ctx, agentId, poolId, PoolInfo.POOL_READ, true);
    }
    /** Agent 对某池有 WRITE 且 池 enabled。(recordForAgent 用于决定经验进哪些池) */
    public static boolean canWritePool(Context ctx, String agentId, String poolId) {
        return hasPoolFlag(ctx, agentId, poolId, PoolInfo.POOL_WRITE, true);
    }
    /** Agent 对某池有 DELETE 自己经验的权限 且 池 enabled。 */
    public static boolean canDeletePool(Context ctx, String agentId, String poolId) {
        return hasPoolFlag(ctx, agentId, poolId, PoolInfo.POOL_DELETE, true);
    }

    /**
     * 返回 Agent 对某池的完整 flags 整数。若不存在 access 行或池 disabled 也仍可返回 0。
     * (ModelSettings 编辑权限时用)。
     */
    public static int poolFlags(Context ctx, String agentId, String poolId) {
        if (ctx == null) return 0;
        return new ExpStore(ctx).poolFlags(agentId, poolId);
    }

    /** 写回 Agent 对某池 flags。flags==0 时表示退出此组（失去 READ/WRITE/DELETE）。 */
    public static void setPoolFlags(Context ctx, String agentId, String poolId, int flags) {
        if (ctx == null) return;
        new ExpStore(ctx).setPoolFlags(agentId, poolId, flags);
    }

    private static boolean hasPoolFlag(Context ctx, String agentId, String poolId, int flag,
                                       boolean requireEnabled) {
        if (ctx == null || agentId == null || poolId == null) return false;
        ExpStore store = new ExpStore(ctx);
        if (!store.hasPoolFlag(agentId, poolId, flag)) return false;
        if (!requireEnabled) return true;
        PoolInfo p = store.getPool(poolId);
        return p != null && p.enabled;
    }
}
