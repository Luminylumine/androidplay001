package com.akasha.app;

/**
 * Experience pool metadata row (matches table `pools`).
 *  - type=0 GLOBAL (fixed id="global", undeletable)
 *  - type=1 GROUP  (user-created via 发现页 +)
 *
 * 经验本体只保存 1 次；多池通过 experience_pool 关联；
 * Agent 与池的成员/权限关系通过 agent_pool_access 表达。
 */
public class PoolInfo {
    public static final int TYPE_GLOBAL = 0;
    public static final int TYPE_GROUP = 1;

    public static final int POOL_READ   = 1; // 001
    public static final int POOL_WRITE  = 2; // 010
    public static final int POOL_DELETE = 4; // 100
    public static final int POOL_RWD    = 7;

    public static final String GLOBAL_ID = "global";

    public String id;
    public String name;
    public int type = TYPE_GROUP;
    public boolean enabled = true;
    public boolean pinned = false;
    public long pinnedAt = 0;
    public long createdAt = 0;

    /** 列表页显示用: 该池内经验条数 (只读, 每次查询时实时 COUNT) */
    public int expCount = 0;

    public boolean isGlobal() {
        return type == TYPE_GLOBAL || GLOBAL_ID.equals(id);
    }
}
