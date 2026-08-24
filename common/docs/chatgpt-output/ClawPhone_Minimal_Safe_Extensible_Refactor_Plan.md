# ClawPhone 最小化、安全、可扩展重构方案

> 目标优先级：**程序小 / 实现简单 / 安全不崩溃 / 扩展性强**。  
> 环境：Java，Android 10 / API 29，`SQLiteOpenHelper`，不引入第三方库。  
> 核心原则：**只把“会动态增长的关系”放进 SQLite，其余继续沿用现有简单结构。**

## 1. 总体结论

本次只新增 3 张表：

```text
pools
experience_pool
agent_pool_access
```

保留现有：

```text
experiences
ModelInfo
Prefs
ExpStore
AppDb
```

不要新增 Group / GroupMember / Repository / UseCase / EventBus / DI 等额外层。

核心关系：

```text
Agent(ModelInfo)
      |
      | agent_pool_access
      v
    Pool
      ^
      | experience_pool
      |
 Experience
```

`agent_pool_access` 同时表达“Agent 属于哪个池”以及 READ / WRITE / DELETE 权限，因此不需要再建组成员表。

---

## 2. 数据库设计

### 2.1 experiences

保持现有结构不动。经验本体只保存一次，不增加 `pool_id`。

### 2.2 pools

```sql
CREATE TABLE pools (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    type INTEGER NOT NULL DEFAULT 1,
    enabled INTEGER NOT NULL DEFAULT 1,
    pinned INTEGER NOT NULL DEFAULT 0,
    pinned_at INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);
```

约定：

```text
type=0 GLOBAL
type=1 GROUP
```

固定存在：

```text
id="global"
name="全局经验池"
type=0
```

GLOBAL 可禁用，但不允许删除，也不允许创建第二个 GLOBAL。

### 2.3 experience_pool

```sql
CREATE TABLE experience_pool (
    experience_id TEXT NOT NULL,
    pool_id TEXT NOT NULL,
    PRIMARY KEY (experience_id, pool_id),
    FOREIGN KEY (experience_id)
        REFERENCES experiences(id)
        ON DELETE CASCADE,
    FOREIGN KEY (pool_id)
        REFERENCES pools(id)
        ON DELETE CASCADE
);

CREATE INDEX idx_exp_pool_pool
ON experience_pool(pool_id);
```

这张表只表达“经验属于哪些池”，不复制正文或媒体。

### 2.4 agent_pool_access

```sql
CREATE TABLE agent_pool_access (
    agent_id TEXT NOT NULL,
    pool_id TEXT NOT NULL,
    flags INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (agent_id, pool_id),
    FOREIGN KEY (pool_id)
        REFERENCES pools(id)
        ON DELETE CASCADE
);
```

权限位：

```java
public static final int POOL_READ   = 1; // 001
public static final int POOL_WRITE  = 2; // 010
public static final int POOL_DELETE = 4; // 100
```

例如：

```text
1 = READ
3 = READ + WRITE
5 = READ + DELETE
7 = READ + WRITE + DELETE
```

后期如果增加 ADMIN / SHARE，只增加 bit，不改表结构。

---

## 3. Agent 与组的关系

采用**多选**。

用户在 Agent 设置里勾选某经验池，就创建：

```text
(agent_id, pool_id, READ)
```

因此：

```text
所在组 = 有 READ 权限
```

取消所在组，直接删除对应 `agent_pool_access` 行，同时自然失去该池的读/写/删能力。

这样不会出现“组成员关系”和“读取权限”两套状态不一致。

---

## 4. 权限模型

### 4.1 静态权限继续放 ModelInfo

继续保留：

```text
pShell
pA11y
pFile
pAlbum
pMedia
pMusic
```

这些数量固定、数据量小，没有必要数据库化。

### 4.2 动态 Pool 权限全部放 SQLite

不要继续给 `ModelInfo` 增加 `pPool1Write` 之类字段。

统一由：

```text
agent_pool_access.flags
```

表达。

### 4.3 新增极薄 facade：Perms.java

```java
public final class Perms {

    private Perms() {}

    public static boolean canShell(Context ctx, String agentId) {
        ModelInfo m = Prefs.findModel(ctx, agentId);
        return m != null && m.pShell;
    }

    public static boolean canReadPool(Context ctx, String agentId, String poolId) {
        return hasPoolFlag(ctx, agentId, poolId, POOL_READ);
    }

    public static boolean canWritePool(Context ctx, String agentId, String poolId) {
        return hasPoolFlag(ctx, agentId, poolId, POOL_WRITE);
    }

    public static boolean canDeletePool(Context ctx, String agentId, String poolId) {
        return hasPoolFlag(ctx, agentId, poolId, POOL_DELETE);
    }
}
```

以后所有 Tool 都只通过 `Perms.canXxx()` 判断，不直接读表或解析 ModelInfo。

---

## 5. exp_record 最终语义

接口保持不变，不增加 `pool_id`。

执行逻辑：

```text
查询该 Agent 所有 enabled + WRITE 的 Pool
        ↓
创建 1 条 Experience 本体
        ↓
向每个可写 Pool 插入 1 条 experience_pool 映射
```

例如：

```text
global   READ+WRITE
project1 READ+WRITE
project2 READ
```

一次 `exp_record()` 的结果：

```text
Experience #123
 ├─ global
 └─ project1
```

不进入 project2。

优点：不需要默认池、当前池、primary pool，也不需要 Agent 理解 `pool_id`。

若没有任何可写池，返回现有 `EXP_NO_WRITE_PERM` 即可；未来需要更精细时再新增 `EXP_NO_WRITABLE_POOL`。

---

## 6. exp_search 最终语义

接口保持不变。

搜索范围：

```text
所有 enabled + READ 的 Pool
```

SQL 需要 `DISTINCT`，因为同一经验可能同时属于多个可读池。

```sql
SELECT DISTINCT e.*
FROM experiences e
JOIN experience_pool ep ON ep.experience_id=e.id
JOIN pools p ON p.id=ep.pool_id
JOIN agent_pool_access a ON a.pool_id=p.id
WHERE a.agent_id=?
  AND (a.flags & 1) != 0
  AND p.enabled=1
  AND (e.title LIKE ? OR e.content LIKE ?)
ORDER BY e.pinned DESC, e.created_at DESC;
```

UI 不需要额外显示“读取”开关：

```text
Agent 所在组 = READ
写入 = WRITE
删除自己的经验 = DELETE
```

---

## 7. exp_delete 最终语义

由于接口没有 `pool_id`，最简单且最一致的定义是：

> `exp_delete` 删除 Experience 本体，而不是“从某一个 Pool 摘除”。

条件：

1. `author_model_id == 当前 Agent`；
2. 该经验至少位于一个当前 Agent 有 DELETE 权限且启用的池中。

删除：

```sql
DELETE FROM experiences
WHERE id=? AND author_model_id=?;
```

`experience_pool` 依靠 `ON DELETE CASCADE` 自动清理，因此经验会从所有池同时消失。

媒体文件：数据库事务成功后再 best-effort 删除。文件删失败只记日志，不回滚数据库。

---

## 8. 删除 Pool 的策略

删除 GROUP Pool：

```text
DELETE pools
```

数据库自动级联删除：

```text
experience_pool
agent_pool_access
```

Experience 本体策略：

```text
仍属于其他池 → 保留
已经不属于任何池 → 删除
```

删除 Pool 后执行：

```sql
DELETE FROM experiences
WHERE NOT EXISTS (
    SELECT 1
    FROM experience_pool ep
    WHERE ep.experience_id=experiences.id
);
```

这样不会误删多个池共享的经验，也不会积累不可见孤儿数据。

GLOBAL 不允许删除。

---

## 9. 禁用 Pool

只更新：

```sql
UPDATE pools SET enabled=0 WHERE id=?;
```

Agent 侧读/写/删全部不可用；用户 UI 仍可进入浏览。

发现页建议**不要隐藏禁用池**，直接显示：

```text
❌ 池名称
```

否则禁用后还需要额外造“禁用池管理页”才能恢复，反而更复杂。

---

## 10. Pool 置顶

```sql
UPDATE pools
SET pinned=1, pinned_at=?
WHERE id=?;
```

排序：

```sql
ORDER BY pinned DESC, pinned_at DESC, created_at DESC;
```

不新增手工排序字段。

---

## 11. ExperiencePoolActivity 复用

不要复制页面。

```java
String poolId = getIntent().getStringExtra("pool_id");
```

然后：

```java
ExpStore.listForPool(poolId)
```

发现页每一项只是跳入同一个 Activity，并传不同 `pool_id`。

---

## 12. ExpStore 最小扩展

继续保留 `ExpStore`，只新增这些方法：

```java
List<PoolInfo> listPools();
String createPool(String name);
boolean setPoolEnabled(String poolId, boolean enabled);
boolean setPoolPinned(String poolId, boolean pinned);
boolean deletePool(String poolId);

List<Experience> listForPool(String poolId);
List<Experience> searchForAgent(String agentId, String query);
String recordForAgent(String agentId, String title, String content, ...);
boolean deleteForAgent(String agentId, String idOrTitle);
```

调用方不要自己拼多池 SQL。

---

## 13. API 配置迁移

### 13.1 不新增 agent_config 表

继续使用：

```text
ModelInfo + Prefs.models JSON
```

ModelInfo 增加：

```java
public String apiBaseUrl;
public String apiKey;
public String remoteModel;
public String goal;
public int maxOut;
public int intervalMs;
public int historyLimit;
public boolean bootEnabled;
```

解析旧 JSON 必须用：

```text
optString / optInt / optBoolean
```

缺字段时给默认值，避免旧用户升级崩溃。

### 13.2 新增极薄 facade：AgentConfig.java

以后 AgentService / LlmClient 不再到处直接读 `Prefs.baseUrl()` 等。

统一：

```java
AgentConfig cfg = AgentConfig.resolve(ctx, agentId);
```

三层解析：

```text
Agent 自己的值
    ↓ 空
旧版 global Prefs（仅兼容旧数据）
    ↓ 空
硬编码安全默认
```

API Key 特例：

```text
Agent apiKey
↓
legacy global apiKey
↓
null
```

不能硬编码 Key；最终仍为空时，直接判定 Agent 配置不完整，不发送网络请求。

关键签名：

```java
public final class AgentConfig {
    public static AgentConfig resolve(Context ctx, String agentId);
    public boolean isRunnable();
    public String validationError();
}
```

调用：

```java
AgentConfig cfg = AgentConfig.resolve(this, agentId);
if (!cfg.isRunnable()) return;
LlmClient client = new LlmClient(cfg);
```

`LlmClient` 不再自己读取 Prefs。

---

## 14. 开机自启门控

全局：

```java
boolean Prefs.globalBootEnabled(Context ctx)
```

Agent：

```java
ModelInfo.bootEnabled
```

唯一入口：

```java
public final class BootPolicy {
    public static boolean canAutoStart(Context ctx, String agentId) {
        if (!Prefs.globalBootEnabled(ctx)) return false;
        ModelInfo m = Prefs.findModel(ctx, agentId);
        return m != null && m.bootEnabled;
    }
}
```

未来 BootReceiver / Alarm / Job / Timer / Push 全部只问这个方法。

---

## 15. 顶栏最小侵入方案

不要造 ToolbarController。

MainActivity 增加一个方法：

```java
private void updateTopBar(int tab) {
    switch (tab) {
        case TAB_CHAT:
            tvTitle.setText("ClawPhone");
            btnNewChatTop.setVisibility(View.VISIBLE);
            btnNewChatTop.setOnClickListener(v -> newChat());
            break;

        case TAB_CONTACTS:
            tvTitle.setText("通讯录");
            btnNewChatTop.setVisibility(View.VISIBLE);
            btnNewChatTop.setOnClickListener(v -> openAddAgent());
            break;

        case TAB_DISCOVER:
            tvTitle.setText("发现");
            btnNewChatTop.setVisibility(View.VISIBLE);
            btnNewChatTop.setOnClickListener(v -> createPool());
            break;

        case TAB_GLOBAL:
            tvTitle.setText("全局");
            btnNewChatTop.setVisibility(View.GONE);
            btnNewChatTop.setOnClickListener(null);
            break;
    }
}
```

在 `switchTab(tab)` 最后调用 `updateTopBar(tab)`。

以后通讯录 `+` 需要支持人类联系人时，只把 `openAddAgent()` 改成一个两项 Dialog，不需要推翻 MainActivity。

---

## 16. “全局” Tab

不要在 MainActivity 里做 ViewStub 状态机。

新增一个非常小的 `GlobalPanel`：

```text
设置 >
开机自启      [开关]
经验池保留时间 >
经验池保留大小 >
经验池管理 >   （预留）
```

点击“设置”：

```text
GlobalPanel
↓
SettingsActivity
↓
复用现有 SettingsPanel
```

`SettingsActivity` 只做容器，不复制原设置逻辑。

---

## 17. ModelSettingsActivity

集中放：

```text
Agent 名称
模型/API URL/API Key/remoteModel
goal/maxOut/interval/history/vision
静态权限
开机自启
所在经验池（多选）
  ├─ 写入
  └─ 删除自己的经验
```

GLOBAL 也作为一个池显示。

---

## 18. Pool/Agent 长按 UI

复用一个简单 Dialog 方法即可。

Pool：

```text
置顶
暂时禁用 / 恢复启用
删除
```

GLOBAL 不显示删除。

Agent：

```text
置顶
删除
```

### 不建议实现“精确长按 3 秒”

优先使用 Android 标准 `setOnLongClickListener()`。

精确 3 秒需要自己处理 ACTION_DOWN / ACTION_UP / CANCEL / RecyclerView 滑动冲突，代码更多且更容易误触。既然最高准则是简单和稳定，建议把产品语义改成“长按”而不是“必须 3 秒”。

---

## 19. AppDb 升级

例如 `DB_VERSION 1 -> 2`。

`onUpgrade()` 中：

1. 创建 `pools`
2. 创建 `experience_pool`
3. 创建 `agent_pool_access`
4. 插入 GLOBAL
5. 将所有旧 Experience 关联到 GLOBAL
6. 按旧版现有经验权限，为每个 Agent 创建 GLOBAL access

整个迁移必须包 transaction。

```java
db.beginTransaction();
try {
    ...
    db.setTransactionSuccessful();
} finally {
    db.endTransaction();
}
```

在 `onConfigure()` 中：

```java
db.setForeignKeyConstraintsEnabled(true);
```

确保 `ON DELETE CASCADE` 真正生效。

---

## 20. 媒体删除策略

数据库永远是 source of truth。

顺序：

```text
1. DB transaction 成功
2. commit
3. best-effort 删除媒体文件
```

文件删除失败只记日志，不回滚数据库。

后期如有需要，再加：

```java
ExpStore.cleanupOrphanMedia();
```

无需现在做复杂任务系统。

---

## 21. 发现页数量

不要新增 `experience_count` 缓存列。

直接：

```sql
SELECT p.*, COUNT(ep.experience_id) AS exp_count
FROM pools p
LEFT JOIN experience_pool ep ON ep.pool_id=p.id
GROUP BY p.id
ORDER BY p.pinned DESC, p.pinned_at DESC, p.created_at DESC;
```

当前规模实时 COUNT 足够，而且不会出现计数不同步。

---

## 22. 底部四键上方“无用 UI”

当前文档无法确定具体 View，因此本次不要猜测删除。

原则：

```text
未定位具体 View → 不改
```

等确认具体控件后做单点修改。

---

## 23. 建议新增文件

控制在大约 6 个：

```text
Perms.java
AgentConfig.java
BootPolicy.java
GlobalPanel.java
SettingsActivity.java
PoolInfo.java
```

其余直接扩展现有类。

不要为每张表继续拆 DAO / Repository / Service / UseCase / Mapper / DTO。

---

## 24. 最终调用结构

```text
AgentService
    |
    +---- AgentConfig.resolve(agentId)
    |
    +---- Perms.canXxx(agentId)
    |
    +---- exp_* -> ExpStore
    |
    +---- LLM -> LlmClient(AgentConfig)
```

UI：

```text
MainActivity
  ├─ Contacts
  ├─ Discover -> ExpStore -> SQLite
  └─ GlobalPanel -> SettingsActivity -> SettingsPanel
```

---

## 25. 六个不确定点的最终答案

| 不确定点 | 最终方案 |
|---|---|
| A：Agent 所在组单选/多选 | **多选**；`agent_pool_access` 本身就是成员关系 |
| B：exp_record 写哪个池 | 所有 `enabled + WRITE` 的池 |
| C：exp_search 搜哪个池 | 所有 `enabled + READ` 的池 |
| D：Agent exp_delete | 删除自己 Experience 本体，全部 membership 自动消失 |
| E：删除 Pool 是否删 Experience | 共享 Experience 保留；删除失去最后 membership 的 orphan Experience |
| F：Agent API 空值回退 | Agent → legacy global → hardcoded default；API Key 最终可为 null 并阻止请求 |

---

## 26. 推荐实施顺序

### Phase 1：数据库

```text
pools
experience_pool
agent_pool_access
GLOBAL migration
```

先手测：

```text
创建池
经验加入两个池
删除一个池
共享经验仍存在
删除最后一个池
孤儿经验消失
```

### Phase 2：ExpStore

实现多池的 list / record / search / delete。

### Phase 3：Perms

所有 Tool 权限统一收口。

### Phase 4：发现页

池列表、创建、置顶、禁用、删除、进入详情。

### Phase 5：ModelSettings

Agent API 配置、bootEnabled、池成员与写/删权限。

### Phase 6：AgentConfig

替换 AgentService/LlmClient 中直接读全局 Prefs 的代码。

### Phase 7：MainActivity/UI

最后改顶栏和 GlobalPanel。

业务和迁移先稳定，UI 最后做，最不容易把数据层一起搞坏。

---

## 27. 最重要的“不要做”

```text
不要复制 Experience 到多个 Pool
不要给 ModelInfo 动态加 pool 权限字段
不要单独再造 Group / GroupMember
不要给 exp_record / search / delete 增加 pool_id
不要把固定小配置全部数据库化
不要为了几个页面造 Router/Repository/UseCase 框架
不要为“3 秒长按”重写触摸状态机
```

## 28. 一句话架构原则

> **动态关系进 SQLite，固定小配置留在 Prefs；Experience 只存一次，权限/配置/启动各用一个极薄 facade 收口。**

这套结构的重点不是“架构漂亮”，而是让本次需求只增加少量代码，同时未来增加更多经验池、权限位、Agent、联系人、定时器和事件唤醒时不需要推翻数据模型。
