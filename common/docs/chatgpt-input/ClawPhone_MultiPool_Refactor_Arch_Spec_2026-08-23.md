# ClawPhone 架构确认文档（等待 ChatGPT 输出推荐架构）

生成时间：2026-08-23
目的：用户提出 5 项跨模块改动需求，其中多项涉及当前架构不兼容处。本文件把 **现状 / 不兼容点 / 需求拆解 / 实体关系不确定点** 列清楚，让 ChatGPT 给出**以扩展性、代码规范性、简洁性**为目标的最终架构方案。

本阶段开发语言：Java（Android 10 / API 29 / minSdk 29）。无 Room / ORM，只有手写 `SQLiteOpenHelper`。不引入三方依赖。

---

## 0. 当前状态快照

### 0.1 经验池数量
当前**只有 1 个内置的"全局经验池"**，没有"组经验池"概念。池本身不是实体，只是 ExperiencePoolActivity 把 `ExpStore` 表的全部记录按倒序列出。

### 0.2 数据库表（`AppDb.java` extends SQLiteOpenHelper）
仅有 `experiences` 一张表：
```sql
CREATE TABLE experiences (
    _id INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT UNIQUE NOT NULL,            -- 业务ID, 形如 exp_<time>_<rand>
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    author_name TEXT NOT NULL,          -- 作者显示名, 也用于"模型名(黑体大字)"渲染
    author_model_id TEXT NOT NULL,      -- 作者模型ID, 用于"Agent只能删除自己的"判定
    media_json TEXT NOT NULL DEFAULT '[]',  -- JSON 字符串数组: ["exp123_0.jpg","exp123_1.mp4"]
    created_at INTEGER NOT NULL,        -- 毫秒
    pinned INTEGER NOT NULL DEFAULT 0   -- 0/1 置顶标记
)
```
媒体二进制文件单独存 `ctx.getFilesDir()/files/exps/media/`，表中只放文件名。**无法表达"一条经验属于多个池，存储不重复"**。

### 0.3 Agent / 模型配置存储
`Prefs.java` 把 `List<ModelInfo>` 序列化成 JSON，放在 SharedPreferences 的 `models` key：
```java
public class ModelInfo {
    public String id;                   // 主键
    public String name;                 // 显示名
    public boolean vision;
    public int ctxIn;
    public int maxOut;
    // per-agent 字符串 KV（空值=继承全局/系统默认）
    public String apiBaseUrl;           // 空 = 走 Prefs.baseUrl()
    public String apiKey;               // 空 = 走 Prefs.apiKey()
    public String customSystemPrompt;   // 空 = 恢复默认
    // 权限 8 项固定布尔:
    public boolean pShell, pA11y, pFile, pAlbum, pMedia, pMusic,
                   pExpWrite, pExpDelete;
    // 是否写入/读取全局经验池两个布尔也在权限里
}
```
**不兼容点**：权限是固定布尔数组，无法表达"第 N 个组经验池写入授权 / 删除授权"。

### 0.4 当前顶栏（activity_home.xml）
同一套标题栏在四个 Tab 共享：
- 标题 TextView tvTitle：永远显示 "ClawPhone"
- 右上角 btnNewChatTop "＋"：点击永远触发 `newChat()`（新增会话）

用户 1+2 号需求要求：**四个 Tab 的「标题 + ＋ 行为」各自不同**。

### 0.5 底部四键与 Tab 内容之间的 UI（activity_home.xml 第 39-45 行）
```xml
<!-- 顶栏（52dp） -->
<!-- flContent FrameLayout weight=1 填满 -->
<!-- 底栏（56dp，内含 1dp 分划线） -->
```
用户说"删除最底端四个选项上方无用的 UI"——从 XML 看中间没有冗余。**需要澄清**：用户可能指的是 ①flContent 的 padding/margin，或 ②flContent 与底栏之间被某个 tab 内容自带了 bottom padding？

---

## 1. 用户需求逐条拆解（保留用户原文）

### 1.1 顶部 + 号差异化（§1）
- 聊天 tab：保留 "ClawPhone 标题 + ＋ 新增会话"，**不动**。
- 通讯录 tab：标题 = "通讯录"，＋ = 新增 Agent（配置新 Agent）。后期扩展为"新增人类会话"。
  - 通讯录长按某 Agent 3 秒 → 弹 置顶 / 删除 两键，配色**对齐左滑聊天三键**（置顶=白字蓝底 #1677ff，删除=白字红底 #F44336，设为未读=黑字黄底 #FFEB3B——此处通讯录功能无"未读"，省）。
- 发现 tab：标题 = "发现"，＋ = 新增经验池（多个经验池：1 个全局默认 + N 个组经验池）。
- 设置 tab：标题 = **"全局"**（原先叫"设置"），**去掉＋**（不需要）。
- 顶部抬头"预留抬头/置顶接口"继续保留。

### 1.2 多经验池（§2 + §3）
原：1 个全局经验池。
新：1 个默认全局池（GLOBAL）+ 用户创建的**多个组经验池**（GROUP_xxx）。每条经验可以**归属**到**一个或多个**池。**数据库存储不重复**（= 一条经验记录 + 多个归属映射。不能每条经验复制多份）。

发现页 = 所有**非禁用**经验池的**列表入口**（每行一项：名 + 条数，颜色/状态标记），用户点某项进入对应经验池详情（复用现 `ExperiencePoolActivity` 逻辑，按池过滤）。

**模型设置页**：
- 选择"该模型所在的组"：多选？单选？= **不确定点 A**（模型与组的关系）
- **权限管理**：对**每个**已创建的组经验池，**独立**提供两项开关：
  - 写入（对应 `exp_record` 动作能否写进该组）
  - 删除（对应 `exp_delete` 能否删该组里自己的经验）
- 原有的"读取/写入全局经验池"两个权限变成"全局池的写+读"开关（并入按组权限）。
- **关键语义**："模型不知道自己写了哪些经验池，接口不变"—— 也就是说 `exp_record` 这个动作**不新增 `pool_id` 参数**。那么：Agent 调用 `exp_record(title, content, images)` 时，系统该把经验**默认写到哪个池？**= **不确定点 B（默认写入池策略）**
- `exp_search(keyword)` 接口同样不变（不带 pool_id 参数）。那搜索范围？= **不确定点 C（搜索范围）**
- 经验的删除（Agent 侧 `exp_delete`）：原接口是 `{action:"exp_delete", "id_or_title":"xxx"}`，没有 pool_id。"只删除自己的经验"这条规则还保留。但若一条经验在池 A 和池 B 里都有，Agent 删时是**从全部池中摘除并删除本体**，还是**仅从某个池里摘除？**= **不确定点 D（级联删除策略）**

**发现页长按某经验池 3 秒**（§3）：弹出横向三键对话框：
| 按钮 | 样式 | 作用 |
|---|---|---|
| 置顶 | 白字蓝底 (#1677ff) | 把该经验池排到发现页列表最前（与经验置顶同理，多池都置顶的按时间排）|
| 暂时禁用 | 黑字黄底 (#FFEB3B) | 禁用后 Agent 侧所有接口不能读/写该池（搜索不到、写入直接被拒返回错误码），发现页列表该项名字**前面加红色 ❌ 字符**（不换背景色），用户点击仍可进入看但显示"已禁用 仅可浏览"条幅 |
| 删除 | 白字红底 (#F44336) | 确认弹框后**完全删除**该池本体 + 清除池中所有映射（经验本体是否一并 cascade 删除？= **不确定点 E**） |

### 1.3 设置页重构（§4）
原："设置"Tab 直接显示 SettingsPanel（所有全局设置堆在一起）。
新：设置 Tab 抬头重命名为 **"全局"**（对应 §1）。它变成一个**总控面板列表**，包含：
- 🔹"设置"子菜单：点进去才是当前 SettingsPanel 的内容（**应用级权限/Shizuku/Dhizuku/截屏/无障碍/电池/日志路径/保存按钮**——这些仍在全局层级，不拆分）
- 🔹开机自启（**总门控**开关）：Boolean，"全局允许各 Agent 在设备启动后被唤醒"。关闭时，所有 Agent 的"我自己要开机自启"都被门控挡住，不生效。
- 🔹全局经验池保留时间/保留大小（从 SettingsPanel 里的"── 全局经验池 ──"区移上来，或保留不动）
- 🔹（预留）经验池管理入口（后期放"全局/禁用的池/池排序"等）

**API 配置迁移（重大）**：
- 从全局 SettingsPanel 里**移除**：etGoal / etUrl / etKey / spModel（默认模型下拉）/ btnTest / btnAddModel / etMax / etInterval / etHistory
- 上面这些**全部进入单个 Agent 的 ModelSettingsActivity**（变成每个 Agent 独立配置，不共享）。
- ModelSettingsActivity 里新增**「开机自启」开关（Agent 级，空实现即可，占位后期走唤醒门控路径）**。
- 全局设置里的"添加模型"按钮保留或迁到通讯录 + 号（§1 说通讯录 "+= 新增 Agent"，所以 btnAddModel 在全局层留冗余入口也行）。
- **兼容性问题**：当前启动默认新会话用的是全局 `prefs.model()` / `prefs.baseUrl()` / `prefs.apiKey()` 回退。迁移后每个 Agent 有自己的 URL/Key，当 Agent 级 URL/Key 为空时**是否回退到全局？**= **不确定点 F（空值回退策略）**。若不回退，则 Agent 级 URL/Key 必填。

### 1.4 底部四键上方无用 UI（§5）
从 activity_home.xml 无法确定具体哪块。**初步怀疑是各 tab 内容的 padding 或 flContent 与 底栏之间 1dp 分划线**。当前 UI 请先让用户圈出再精删。

---

## 2. 不兼容点汇总（当前实现无法不动架构满足）

| # | 冲突 | 说明 |
|---|---|---|
| C1 | 权限模型从固定 8 布尔 → **动态生成的 (N_pool × 2) + 8 静态** 布尔 | `ModelInfo` 目前是强类型字段。如果采用 `Map<String, Pair<Boolean,Boolean>>` per pool 表达会破坏 SharedPreferences JSON 序列化稳定性。推荐改为**权限独立表**。 |
| C2 | 经验与池 = 多对多，当前只有 experiences 单表 | 需要至少新增 `pools` 表 + `pool_membership` 关联表（pool_id × exp_id）。 |
| C3 | API 配置从全局共享 → 每 Agent 独立 | 当前 `Prefs.baseUrl()` / `Prefs.apiKey()` / `Prefs.model()` 被大量代码直接调用（AgentService/LlmClient 等）。需要加一层 "Agent 级 → 全局级 → 系统默认" 三级回退的 facade。 |
| C4 | 多池场景下 exp_record / exp_search / exp_delete 的默认行为均无 pool_id 参数但语义不明 | 对应不确定点 B/C/D。 |

---

## 3. 给 ChatGPT 的指令

请产出一份符合下列要求的**最终架构文档**：
1. **ER 图**（纯文本表格即可）：新增哪些表、每表字段、主键外键、索引、级联删除策略（回答不确定点 D/E）。
2. **权限模型设计**：
   - ModelInfo 中权限如何存储（继续 JSON 还是独立表 `agent_permissions`？动态 pool 权限如何与 8 个静态权限统一？）
   - 权限检查 facade：提供给调用方的签名类似 `Perms.canWritePool(agentId, poolId)` / `Perms.canDeletePool(...)` / `Perms.canShell(agentId)`。
3. **API 配置三层回退方案**（解决 C3 + 不确定点 F）：Agent 级空值如何回退到全局级；全局级空值回退到硬编码默认；对 `AgentService` 调用链给出 2-3 个关键签名。
4. **多池下三个经验动作的默认语义**（解决 B/C/D）：
   - `exp_record(title, content, images)` 默认写到哪些池？（候选：只写「模型所在组」+ 全局池？按组独立开关门控？）
   - `exp_search(keyword)` 默认搜哪些池？（候选：搜「模型所在组」+ 全局池 + 所有授权「读取」的组？= 但用户原只提"写入/删除"两权限，没给「读取」权限——要补上吗？）
   - `exp_delete(id_or_title)` 级联策略。
5. **发现页长按的三键对话框 UI 调用链**（轻量说明即可）。
6. **顶栏 + 号按 Tab 差异化**的最小侵入式 Java 代码组织（在 `MainActivity.switchTab()` 里更新标题 + 更新 btnNewChatTop clickListener？还是其他方式）。
7. **"全局" Tab 内部的"设置子菜单"导航方案**（是新建 GlobalPanel.java + 点击跳转 SettingsActivity（把 SettingsPanel 嵌入）？还是在 MainActivity Tab 里用 ViewStub 切换？= 保持简洁）。
8. **总门控开机自启与 Agent 级开机自启门控的接口签名**（给后期定时器 / 唤醒机制预留）。

约束：
- 不引入三方库（无 Room / Gson / 协程）
- 代码量增加尽量少，**复用现有类**（保留 ExpStore / Prefs / AppDb 前缀命名）
- 保持扩展性：后期若加"人类会话"类型、"事件通知类型"、"定时器类型"不得推翻本架构
- 以可维护性、代码规范性、简洁性为第一优先级（不要过度设计）
