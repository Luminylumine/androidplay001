# ClawPhone Agent/会话/定时器 2026-08-24 重构 - Product Requirements Document

## Overview

* **Summary**: 修正 Agent（通讯录对象）与 对话（会话）的两类名字/对象关系，按"Agent×API 满射 → 对话一一绑定单个对象"的新数据模型重切 UI、上下文共享、定时器触发源、对话设置独立页面、以及通用搜索页（聊天+通讯录）。

* **Purpose**: 修复当前对话显示名与通讯录展示名混淆、对话设置改到模型名、上下文会话间割裂、定时器触发源层级并列、超过 5 个对话无分页入口、LLM 无法查询项目背景/用户长期偏好等问题。

* **Target Users**: 单用户 ClawPhone App 使用者（含开发者）。

## Goals

1. **对象边界清晰**：通讯录显示名（模型名/备注）与对话显示名独立创建、独立改名，互不影响。
2. **上下文共享 + 可搜索**：同一 Agent 下所有对话共享同一 LLM 上下文；上下文以半满截断机制保活；Agent 可用新 `chat_search` action 搜索当前对话 / 该 Agent 全部对话的历史消息。
3. **对话设置独立**：对话页齿轮进入「对话设置」而非「Agent 设置」；对话设置暴露对话名、自启（仅切换开关，随定时器与唤醒同放），不暴露 API 配置等 Agent 级设置；Agent 设置入口保留在通讯录。
4. **定时器对话框层级合理**：对话行分为两个互斥交互区域——勾选区域（复选框，toggle 选中）与 对话内容区域（点击后进入"该 Agent 下的所有对话"选择页）；超过 5 个对话时末位显示"更多"，进入统一的"聊天/通讯录/排序"搜索页。
5. **通用搜索页（更多页）**：顶端搜索栏 + 右侧蓝色"搜索"；下方 3 个 Tab（聊天 / 通讯录 / 排序）；排序 = 字母序 & 时间顺序/倒序；列表复用聊天与通讯录页面样式。

## Non-Goals

* **不改动经验池、权限体系**：用户已明确经验池作用对象保持为单会话，不改动。

* **不实现新的存储层**：用户说明"后续会修改此机制、新增存储方式"——本次上下文与搜索只在现有 JSON 会话文件/内存上做，不在 SQLite 表中重构聊天记录。

* **不新增 LLM 外部工具依赖**：只加内部 action（`chat_search`），无额外网络库。

* **不修改全局 Tab 底部导航架构**。

* **不改变定时器其他两类唤醒源（闹钟、倒计时、打开软件）的结构与行为**，只调整「任务完成」内部的触发源 UI/数据。

## Background & Context

### 当前代码观察

* `ModelInfo.name`：通讯录显示名（展示名/备注），与 `id/baseUrl/apiKey/perm*/vision/...` 绑定为一个 Agent 对象；同一 API 可对应多个对象（满射）。

* `ChatSession.title`：当前代码里被强制当作 `模型名+(N)` 用，同时既作聊天列表标题，又在定时器触发源中当"对话名"显示；在 ChatActivity 顶栏点击齿轮时直接跳到 `ModelSettingsActivity`（改的是 Agent 级设置），导致"对话里改模型名"的 bug。

* 上下文：AgentService 在 `startTask()` 中仅 `keepHistory && same(currentSessionId)` 时保留 `HISTORY` 内存上下文，跨对话完全割裂；持久化的聊天记录存在 `files/sessions/<sessionId>.json`（每对话一个，互不共享）。

* 定时器「任务完成」当前结构：把"同型 Agent 行"和"对话行"平级并列放在 TriggerSourcePickerActivity；行内 CheckBox 与文本区还未做分离交互（用户要求：点文本区 = 选择该 Agent 的所有对话；点复选框 = 勾选该单个对话/单条目）。

* 全局搜索入口：当前无；触发源页 > 5 时无分页。

### 历史约束

* 方案①：APatch 11224 + ZygiskNext v1.4.5 + Shamiko v1.2.5（根方案不变）。

* 严格开源组件；项目内存敏感（华为，尽量避免复杂 IPC）。

* **用户强调"遵循 PLAN1.md、不自创新；不一致时停下"**。

## Functional Requirements

### FR-1. Agent（通讯录对象）命名与展示

* **FR-1.1**：新建 Agent 时，展示名默认取用户给的或模型名；若与现有 Agent 展示名重复，系统在保存时自动追加 `(1)(2)…`（以展示名维度判重，不考虑是否同 API）。

* **FR-1.2**：通讯录页每行展示 = 展示名（`ModelInfo.name`，用户可在 Agent 设置中改）+ 运行状态。严禁展示名与对话名共用字段。

* **FR-1.3**：API、权限、经验池、Prompt 继续挂在 Agent 对象（`ModelInfo`），不迁移到对话。

### FR-2. 对话（会话）命名与单射关系

* **FR-2.1**：每个对话唯一对应一个 Agent 对象（`ChatSession.agentId → ModelInfo.id`），不再出现"对话设置改到 Agent 名"的路径。

* **FR-2.2**：对话自己的显示名用**独立语义**的 `ChatSession.displayName`（可与同 Agent 的其它对话重名）；用户未设置时，创建时默认取其对应 Agent 的展示名并自动 `(1)(2)…` 保证全局对话名不重名。

* **FR-2.3**：聊天 Tab 行的大标题、ChatActivity 顶栏、定时器触发源都显示 `ChatSession.displayName`。

* **FR-2.4**：对话改名、聊天列表/顶栏改名、定时器摘要必须同步立刻更新；Agent 改名不会反向修改已存在对话的 `displayName`（只影响新对话默认值）。

* **FR-2.5**：历史升级：升级后第一次启动，如果 `ChatSession.displayName == null`，回退到原 `ChatSession.title` 且确保不重名（原 `title` 继续保留作为内部兼容字段，不再在 UI 出现）。

### FR-3. 同一 Agent 跨对话共享上下文（半满截断）

* **FR-3.1**：HISTORY 上下文作用域改为 `agentId` 级别：同一 Agent 下的所有对话，无论切换到哪个，只要 `keepHistory=true` 都共享同一份**合并后的上下文**。

* **FR-3.2**：上下文以"**半满截断**"保存：以 Prefs 中 `historyRounds`（即 `maxRounds`，用户已有设置）为上限；当 `HISTORY.size() == maxRounds` 时，删除最早 `floor(maxRounds/2)` 条，保留后半部。旧代码的 "while size>=maxRounds removeFirst" 逐条移除模式改为该批删模式。

* **FR-3.3**：上下文的加载/写入位置：任务开始（`startTask`）时按 `agentId` 从**该 Agent 最近 N 轮（所有对话合并）** 还原 HISTORY；任务结束 done/terminate 时把本轮产生的 `(assistant,user)` 对**落盘到该对话自己的聊天文件**（保留当前持久化行为），同时更新 agent 级上下文窗口缓存。

* **FR-3.4**：GUIDE\_NOTES 仍为"当前运行任务临时引导"，跨任务/跨对话不保留（保持不变）。

### FR-4. Agent 搜索动作 `chat_search`（Agent Skill 内新增）

* **FR-4.1**：新增 action `chat_search`。参数：

  ```json
  {"action":"chat_search","query":"关键词",
   "scope":"this_session | same_agent_all",
   "from_ts":0,"to_ts":9999999999999,
   "role":"user | agent | any",
   "sender_agent_ids":["agentId1","agentId2"]}
  ```

  所有参数除 `query` 外可缺省。`to_ts` 缺省=当下；`role=any`；`sender_agent_ids` 缺省=全部。

* **FR-4.2**：返回格式（作为下一轮的 llmHint）每条必带时间、所属对话、发送者（精确到 Agent id/名）：

  ```
  [2026-08-24 10:27:39] [对话: 第二个] [qwen-agent1] user: 请...
  [2026-08-24 10:27:40] [对话: 第二个] [qwen-agent1] agent: 好的，我来...
  ```

  结果条数上限 `max 40`，超过时首行提示 `hit=138 truncated to 40, refine query`。

* **FR-4.3**：scope 语义：

  * `this_session` → 只在当前 `currentSessionId` 对话文件内搜。

  * `same_agent_all` → 在同一 `currentAgentId` 下的**所有对话**聊天文件中搜。

* **FR-4.4**：`sender_agent_ids` 仅在聊天记录里 `type="agent"` 的行上命中（命中行的 agentId 必须在该列表中）；`role=user|any` 时不检查。

* **FR-4.5**：权限门控：`chat_search` 永远对 Agent 可用（读自己对话），不需要额外开关（不挂在 perm 位图上）。

### FR-5. Prompt 引导（项目理解 & 搜索策略）

在 [AgentPrompts.defaultBase](file:///d:/study/androidplay/huawei_phone/clawphone/src/com/clawphone/app/AgentPrompts.java) / `toolDocs` 中明确加入两段引导：

* **FR-5.1**：当 LLM 想了解"用户个人长期偏好、历史设定、长期习惯"等跨对话信息 → 用 `chat_search scope=same_agent_all` 在同一 Agent 所有对话中搜。

* **FR-5.2**：当 LLM 想了解"当前对话用户给的任务目标、当前上下文约束、本任务临时规则"等 → 用 `chat_search scope=this_session` 只在本对话搜。

* **FR-5.3**：告知"搜索返回一定带时间戳 + 所属对话 + 发送者（精确到 Agent 名称），请在需要区分来源的地方引用对话名。"

### FR-6. 对话设置独立页面

* **FR-6.1**：ChatActivity 齿轮按钮进入新建的 **SessionSettingsActivity**（不是 ModelSettingsActivity）。

* **FR-6.2**：SessionSettingsActivity 索引页（极简）：

  * ⏰ 定时器与唤醒（直接启动 TimerWakeupActivity，传 `sessionId` + `agentId`；TimerWakeupActivity 原本按 agent 存 → 现在**依然按 agent 存**不变，因为定时器是 per-agent 设置；用户需求"和定时器与唤醒放一起，无需单独开关了"）。

  * 对话名（行：当前 `displayName` + 摘要；点击进入弹输入框改名）。

  * 自启（行：Switch，直接写入 `ModelInfo.autoStart` 该对话对应 Agent 的开关；需求"无需单独开关了，和定时器与唤醒放一起"。此条保留为只读镜像也行——按需求放定时器页同页面即达成，不新增独立开关）。

  * **不出现 API BaseURL / Key / 经验池权限 / Prompt** 等 Agent 级设置入口。

  * 底部：删除本对话（红色按钮）。

  * （额外要求用户原话："隐藏api相关设置的入口（只保留自启动，和定时器与唤醒放一起，无需单独开关了）" → 因此 SessionSettingsActivity = ⏰定时器与唤醒 一项 + 对话名 一项 + 删除。自启的开关 UI 并入 定时器与唤醒 页顶部一行即可；若已存在 Agent 级自启入口保持兼容）。

* **FR-6.3**：Agent 设置入口保留在通讯录（通讯录 → 长按/点击齿轮/条目 → ModelSettingsActivity），结构不变。

* **FR-6.4**：修复 ChatActivity 齿轮跳转：不再进入 ModelSettingsActivity，改为 SessionSettingsActivity；修复在对话设置改名时不写 `ModelInfo.name`，只写 `ChatSession.displayName`。

### FR-7. 定时器「任务完成」触发源：行内两个交互区域

* **FR-7.1**：TriggerSourcePickerActivity 每行重新分区：

  * 左侧：CheckBox 区域（`cbSource`）——点击 = toggle 勾选（当前对话/单条目级选中/取消）。**勾选保留（不删）**（保持需求原设计）。

  * 右侧的文字/整个 `rowSource` 的其余区域 —— 点击 = **进入独立的「选择页」（AgentDialogsPickerActivity，新建）**，展示"该 Agent 下所有对话"的勾选页面（替代当前把"同型 Agent"和"具体对话"并列的错误结构）。

* **FR-7.2**：AgentDialogsPickerActivity（选择页）结构：

  * 顶栏：左上角红色白字「取消」→ 返回且**不保存**；右上角蓝色白字「保存」→ 返回且**提交**（默认按「保存」返回时才写入）。

  * body：列出该 Agent 下**所有对话**，每行仅勾选框 + 对话 displayName（无行体子页递归，不超过 5 → 不做"更多"）。

  * 返回行为：

    * 若进入时**已有被勾选的对话** → 页面上这些项**默认勾选**；用户改完后点「保存」才把新的集合带回来；点「取消」原集合不变。

    * 若进入时**无已勾选** → 全默认不勾；用户返回后不自动勾选任何项（只有明确点「保存」且确有勾选时才写入）。

  * 返回写入 target：写的是具体对话 id（不引入新的 `AGENT_PREFIX:*` 标记）。

### FR-8. 触发源已选 > 4 条 → 更多页（通用搜索页，三路独立排序）

* **FR-8.1**：TimerWakeupActivity 的「任务完成」摘要行（显示前 4 个对话名 + 第 5 位）：

  * ≤4 条：全部列名；

  * 5 条：列 4 个对话名 + `更多(1)`；

  * ≥6：列 4 个对话名 + `更多(N-4)`。

  * 触发"更多"阈值=已选对话数 > 4（不是总数）。

* **FR-8.2**：点"更多" → 进入 **SearchPickerActivity**（新建）。顶栏：

  * 搜索框（输入任意关键字）+ 右侧蓝色白字"搜索"按钮；

  * 搜索框下方 3 个切换键：「聊天」「通讯录」「排序」；

    * 「聊天」激活：列表 = 所有对话（ChatSession.displayName 大标题 + 所属 Agent 名小字），样式同聊天 Tab；

    * 「通讯录」激活：列表 = **所有 Agent**，每行**格式统一改为「黑体大字 = 展示名 (ModelInfo.name) / 灰色小字 = 模型名 (ModelInfo.id)」**（和原通讯录 Tab 一致但追加模型名字段）；点击**单个对象（Agent）** → 进入 FR-7.2 的 AgentDialogsPickerActivity 选择页：列出该 Agent 所有对话，可**单个逐对话勾选**，保存/取消返回。

    * 「排序」键：点击弹出 4 选菜单：

      * 字母序（正序）

      * 字母序（倒序）

      * 时间顺序（按创建或最后消息时间，新旧）

      * 时间倒序（默认）

  * **三路独立排序**：当已勾选对话数 > 4 时，整个列表按以下优先级/分组排序（每路内部再应用当前"排序"规则）：① 已勾选的对话/Agent 强制置顶；② 对话中 `pinned=true` 者（聊天范围）/ Agent 中 `pinned=true` 者（通讯录范围）居其次；③ 剩余的对话/Agent 最后。

* **FR-8.3**：SearchPickerActivity 每行交互（严格区分已勾选/未勾选）：

  * 对**已勾选的对话或 Agent 行**：点击行体（非勾选框区域）→ 进入 FR-7.2 同款选择页 AgentDialogsPickerActivity（Agents = 展开其全部对话；对话行 = 展开自己即单勾选择页；两种进入后的页面顶栏都符合「左上取消 / 右上保存，返回默认保存」）。

  * 对**未勾选的行**：点击行体也进入选择页；从选择页返回时**不自动勾任何项**；只有用户在选择页里显式勾选、点"保存"时才会写入。

  * 左侧勾选框永远只做 toggle（当前行级选中/取消；对 Agent 行勾选 = `AGENT_PREFIX:<id>` 特殊标记保留，不展开，不替用户勾选其对话）。

* **FR-8.4**：搜索过滤同时作用于**对话显示名**与**通讯录 Agent 展示名/模型名**。

* **FR-8.5**：搜索页通过「保存」返回时，写入 `TimerWakeupActivity.taskDoneSessions`。

### FR-9. 定时器摘要「任务完成」行显示名字 = 对话显示名

* **FR-9.1**：TimerWakeupActivity 的任务完成行摘要（前 4 个对话名）显示的是每个 `sessionId` 对应的 `ChatSession.displayName`（不是旧 `title`、不是 Agent 名）。

* **FR-9.2**：命中 `ALL_SESSIONS`（任意会话）或 `AGENT_PREFIX:<id>`（同型 Agent）时，摘要文案明确写成"任意会话"或"Agent:<展示名>的全部对话"，不误导成具体对话名。

### FR-10. 其他已确认不变

* 经验池与权限作用对象仍为**单个会话**（用户明确），不随 FR-3 上下文共享改变。

* 计时器的闹钟式、倒计时、打开软件唤醒结构不变，只改「任务完成」UI 与命中逻辑。

* `ModelSettingsActivity`（Agent 设置）保持索引页结构，不与对话设置混合。

## Non-Functional Requirements

* **NFR-1（向后兼容）**：升级时不破坏旧 `sessions` 列表与旧 `ChatSession.title`；旧 title 自动迁入 displayName 并做去重。

* **NFR-2（简单/可编译）**：仅用现有依赖（Android 原生 + JSON + SharedPreferences + 文件系统），不新增 jar/库。

* **NFR-3（命名单一真源）**：UI 中关于"名字"的展示统一走 2 条路径：通讯录展示名 ← `ModelInfo.name`；对话显示名 ← `ChatSession.displayName`；严禁出现第三字段（如旧 `ChatSession.title` 继续对外展示）。

* **NFR-4（性能）**：`chat_search same_agent_all` 若该 Agent 对话文件总和 > 2MB，主动丢弃最老对话后再搜索（避免阻塞 Agent 循环 >1s）。

* **NFR-5（稳定返回）**：任何时候 TimerEngine 触发结果回调中 `sessionId` 必须是现有对话，否则走 `resolveSession` 逻辑（已存在，验证覆盖）。

## Constraints

* **技术**：aapt2 + javac 手动构建（build\_clawphone.ps1），SDK 安卓 10 兼容 minSdkVersion 29；必须开源组件。

* **业务**：定时器 `TimerEngine` 依然按 **agentId** 维度持久化（不是 sessionId 维度），用户需求"每 Agent 设置内"隐含此约束。

* **依赖**：`SessionStore` 仍使用 SharedPreferences + JSON 文件（不迁到 SQLite，与"后续改存储方式"的路线一致）。

## Assumptions

* 需求 2 中"同一个对象中的不同对话之间共享上下文"的"对象"= `ModelInfo`（通讯录单个条目）。"单射关系"=每个对话唯一属于一个 Agent（反方向则一对多，即一个 Agent 下多对话）。

* 需求 3 中"更多页"的「排序」为全局状态切换（选中后应用于当前列表；下次进入默认为时间倒序）。

* 需求 4"点击对话区域（非勾选）可以选择该通讯录对象的所有对话" —— 这里"选择"语义是"进入选择子页，复选后等价于把该 Agent 下这些对话批量纳入监听集合"。勾选该对话行不等于选择"全部对话"（两者独立）。

* 对话设置页"只保留自启动，和定时器与唤醒放一起，无需单独开关了"——解释为：自启开关 UI 放到定时器页顶栏的一行（和唤醒功能同屏），不单独做"自启动"一项；Agent 设置中若已存在自启动 UI 保持不变（两边同步写同一字段 `ModelInfo.autoStart`）。

## Open Questions

* [x] （解答：按用户原话不做 API 预检）需求中不涉及 ChatActivity API 预检开关的恢复或修改，保持"直接调用、报错系统提示"。

* [x] （解答：保持不变）需求 3 中「排序」是否为全局设置？答：非持久化，当前 SearchPickerActivity 会话内生效、默认时间倒序。

* [x] （2026-08-24 回答：**逐对话勾选，保存/取消返回**）点击行体 → 进入 AgentDialogsPickerActivity（独立页、左上取消/右上保存）；逐对话勾选后保存返回；已勾选的进入时默认勾；未勾选的出来后不自动勾。详见 FR-7.2。

## Acceptance Criteria

### AC-1: 通讯录展示名与对话显示名独立（不互相覆盖）

* **Type**: `rule`

* **Given**: 设备上装了新版 App，有两个 Agent：qwen（展示名"qwen"）、deepseek（展示名"deepseek"）。

* **When**: 在聊天 Tab 新建两个 qwen 对话 → 分别进入对话设置 → 将它们改名成"工作对话"、"个人对话"；然后去通讯录把第一个 qwen 改名成"千问工作"。

* **Then**: 回到聊天 Tab，两行对话名仍显示"工作对话""个人对话"；通讯录显示"千问工作"；两者互相不覆盖。

* **Pass Condition**: 4 处名字展示分别符合"工作/个人/千问"；Prefs 中 ModelInfo.name 与两个 ChatSession.displayName 分别独立；运行 `grep/日志` 可验证。

* **Evidence**: 构建安装 + 手动改名操作截图 + `adb pull sessions/` 验证。

### AC-2: 同 Agent 多对话共享上下文；上下文半满批删

* **Type**: `rule`

* **Given**: 同一个 Agent A 下有对话 X、对话 Y；`historyRounds=10`。

* **When**: 在 X 中跑 9 轮任务（HISTORY=9）、停掉；切到 Y 对话、跑 keepHistory=true 的任务（打断/追加模式）。

* **Then**: Y 第一轮 LLM 请求里 msgs 应包含 X 任务那 9 轮 (assistant,user) 对。之后连续再进 1 轮 → HISTORY 达到 maxRounds=10 → 触发半满截断，最旧 5 条被批删，保留 5 条。

* **Pass Condition**: `adb logcat -d | findstr "ClawPhone"` 中 `llm:` 前置 msgs 数量/内容与预期一致；半满截断后 size=5（`CpLog` 可打点验证）。

* **Evidence**: 日志 + HISTORY 状态。

### AC-3: chat\_search scope=this\_session & same\_agent\_all 命中正确

* **Type**: `rule`

* **Given**: Agent A 对话 X 含用户消息"长期偏好: 红色"；对话 Y 含用户消息"当前任务: 帮我下订单买笔"。

* **When**: 在 Y 中 Agent 输出 `{"action":"chat_search","query":"红色","scope":"this_session"}`。

* **Then**: llmHint 返回 0 条。换成 `scope=same_agent_all` → 返回 `[时间] [对话:X] [user] 长期偏好: 红色`，时间戳精确到秒。

* **Pass Condition**: 两条分别命中/不命中；返回行都带时间与对话名。

* **Evidence**: 人工构造对话后运行一次，抓 `ClawPhone` 日志。

### AC-4: ChatActivity 齿轮进入独立对话设置（不再显示 API 配置）

* **Type**: `rule`

* **Given**: 任意对话打开 ChatActivity。

* **When**: 点顶栏齿轮。

* **Then**: 打开 SessionSettingsActivity；页面仅包含：⏰定时器与唤醒、对话名改名、删除本对话。**不出现任何 BaseURL/API Key/Model ID/权限/经验池**。

* **Pass Condition**: 截图 + 布局树 dump 没这些条目；点返回后定时器/改名生效；删除即会话消失。

* **Evidence**: 截图 + 构建日志无编译错误。

### AC-5: 定时器「任务完成」触发源行有"勾选 vs 点击行体→进入AgentDialogsPickerActivity"双区域

* **Type**: `rule`

* **Given**: Agent qwen 有 3 个对话（无已勾选）、进入 TriggerSourcePickerActivity。

* **When**: ① 点击 qwen 某对话行**左侧 CheckBox** → √ 勾选；② 点击该行**行体（非勾选区）**。

* **Then**: 勾选状态（√）保持不变；打开 AgentDialogsPickerActivity（该 Agent 下所有 3 个对话）。在子页勾选 2 个对话后点**右上保存** → 返回主选源页后 taskDoneSessions = {原先勾选的 1 个 + 子页 2 个}；再回到子页 → 这次那 2 个**默认勾选**；再点**左上取消**返回 → 不新增任何。随后进入时**完全不勾选**直接保存 → 返回后该对话行保持不勾选。

* **Pass Condition**: 四种路径（勾选 toggle/保存返回并合并/取消返回不变/空选返回不加）行为都符合；日志 `TimerEngine hitTaskDone` 命中保存的对话 id。

* **Evidence**: 手点 + Prefs 里 `timer_<id>` JSON 每步比对。

### AC-6: 已选 > 4 条 → 第 5 位显示更多(N)、搜索页通讯录行"黑体大字展示名/灰色小字模型名"+ 三路独立排序

* **Type**: `rule`

* **Given**: 全局共 10 个对话；taskDoneSessions 目前已勾选 8 条（其中 1 条 pinned=true）；还有 5 个 Agent（其中 1 条 pinned=true）。

* **When**: 看 TimerWakeupActivity 的任务完成摘要 → 点「更多(N-4)」→ 打开 SearchPickerActivity → 切「通讯录」Tab。

* **Then**:

  1. 摘要行显示前 4 个 displayName + `更多(4)`（因为已勾选 8，取后 4 个在更多里）。
  2. 通讯录 Tab 每行展示 = 第一行**黑体大字 ModelInfo.name**、第二行**灰色小字 ModelInfo.id**。
  3. 聊天 Tab 三路独立排序成立：勾选过的对话整体置顶（且内部按时间倒序）、其次是 pinned=true 的对话（内部按时间倒序）、最后是剩余对话（时间倒序）。
  4. 通讯录 Tab 点击某个 Agent（行体非勾选）→ 进入 AgentDialogsPickerActivity，列出该 Agent 全部对话，可逐对话勾选+保存/取消返回。
  5. 排序菜单 4 项可切；"字母序正序"切完三路中每路内部遵守字母序正序（不改组间顺序）。

* **Pass Condition**: 5 小项都成立；搜索框输入同时过滤展示名/模型名。

* **Evidence**: 截图 + taskDoneSessions JSON + 排序后逐行比对。

### AC-7: 同名对话创建自动 (1)(2)；改名也不影响原默认生成规则

* **Type**: `rule`

* **Given**: 刚装完、还没有 qwen 的对话。

* **When**: 连续用通讯录 qwen 新建 3 个对话（不改名）。

* **Then**: 三个对话 displayName 分别是 `qwen` / `qwen(1)` / `qwen(2)`。再把第一个 qwen 改名为 `首对话`；再新建 1 个 → 新名字为 `qwen(3)`（不因为改名占用 `qwen`）。

* **Pass Condition**: 全局对话名不重；4 个对话名字符合预期。

* **Evidence**: 创建 4 次，截图或 adb pull sessions/ 验证。

### AC-8: action terminal/complete 同义词归一化；归一化触发 TimerEngine 回调

* **Type**: `rule`

* **Given**: 定时器启用了「任意会话（ALL\_SESSIONS）」任务完成触发，触发结果=发消息"继续"。

* **When**: Agent 输出 `{"action":"terminal","message":"end"}`。

* **Then**: AgentService 视为 terminate，跳出循环；TimerEngine.onTaskDone 日志中出现 `任务终止 触发 agent=`；最终向 Agent 通过排队/打断等方式发"继续"。

* **Pass Condition**: 循环结束、TimerEngine 日志两行、有触发结果日志。

* **Evidence**: adb logcat -d | findstr TimerEngine。

### AC-9: 代码复杂度与整洁度

* **Type**: `rubric`

* **Dimension**: 改动局部性、命名一致、真源清晰。

* **Scale**: 1-5

* **Anchors**: 1 = 全局散落、仍有 title/displayName 混用；3 = 改动集中但有 2-3 处边缘 UI 命名不一致需要补改；5 = 所有 UI 通过 `ChatSession.displayName` / `ModelInfo.name` 单一真源，没有混用，Session/Model 两类设置边界清晰，新增的 action 与半满批删都有独立清晰方法。

* **Pass Threshold**: >= 4

* **Evidence**: 代码审阅（Review 阶段）。

