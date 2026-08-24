# ClawPhone Agent/会话/定时器 2026-08-24 重构 - Implementation Plan

## Task 1: 数据模型切层 — ChatSession.displayName 独立 + 版本迁移
- **Status**: `pending`
- **Priority**: high
- **Depends On**: None
- **Description**:
  - 在 [ChatSession.java](file:///d:/study/androidplay/huawei_phone/clawphone/src/com/clawphone/app/ChatSession.java) 新增长期真实字段 `displayName`；JSON 序列化含 displayName；保留 title 字段做向后兼容（不再 UI 展示）。
  - 在 [SessionStore.java](file:///d:/study/androidplay/huawei_phone/clawphone/src/com/clawphone/app/SessionStore.java)：
    - `list()`/`save()` 读/写 displayName；当读到旧会话 `displayName == null` 时，回退到旧 `title`（若 title 为空或等于 `agentId` 则按"模型名"重算默认值），并对全部会话做一次 displayName 去重（`(1)(2)` 规则）后落盘。
    - `uniqueTitle()` 保留做内部兼容；对外的默认创建/改名判重统一走新增 `uniqueDisplayName(base)`，维度 = 现存 `ChatSession.displayName` 集合。
  - [MainActivity.java](file:///d:/study/androidplay/huawei_phone/clawphone/src/com/clawphone/app/MainActivity.java) / [AgentService.java](file:///d:/study/androidplay/huawei_phone/clawphone/src/com/clawphone/app/AgentService.java) / [TimerEngine.java](file:///d:/study/androidplay/huawei_phone/clawphone/src/com/clawphone/app/TimerEngine.java) 新创建会话时，用 `store.uniqueDisplayName(agentName)` 填 `displayName`。
- **Acceptance Criteria Addressed**: AC-1, AC-7, NFR-1/3
- **Test Requirements**:
  - `rule` TR-1.1: 把旧 sessions（无 displayName）用 adb push 模拟，启动后 3 个会话自动 displayName 赋值成 `a/a(1)/a(2)`、保存后重启不重复、不丢失。Evidence: pull sessions 后 JSON 检查。
  - `rule` TR-1.2: 通讯录允许创建同名 Agent（展示名由 Task 2 `uniqueAgentName` 判重，本 Task 不做）。
  - `rubric` TR-1.3: 字段真源清晰度；scale 1-5；1=仍有 title vs displayName 互相读；3=基本使用 displayName 但有 1~2 处遗留；5=全项目对外展示统一走 displayName（聊天 Tab / ChatActivity 顶栏 / 定时器）。Pass >= 4。Evidence: grep `\.title\b` 非 SessionStore 内部位置。

## Task 2: 通讯录/Agent 展示名在新建 / 改名时去重
- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 在 [Prefs.java](file:///d:/study/androidplay/huawei_phone/clawphone/src/com/clawphone/app/Prefs.java) 新增 `uniqueAgentName(base)` 工具：以现存 `ModelInfo.name` 集合判重、生成 `(1)(2)`。
  - [ModelSettingsActivity.java](file:///d:/study/androidplay/huawei_phone/clawphone/src/com/clawphone/app/ModelSettingsActivity.java)：
    - 保存基本信息时，最终 name 经 `uniqueAgentName` 落盘；不允许空名。
    - 改 Agent 展示名时**不修改**该 Agent 已有对话的 displayName（FR-2.4）。
  - 通讯录 Tab 行（item_agent.xml + MainActivity.refreshAgents）保持只显示展示名 + 运行状态（无需改，核对即可）。
- **Acceptance Criteria Addressed**: AC-1, FR-1.1
- **Test Requirements**:
  - `rule` TR-2.1: 新建 3 个名字默认= `qwen-max` 的 Agent → 落盘名 `qwen-max / qwen-max(1) / qwen-max(2)`。Evidence: `prefs.models()` 序列化。
  - `rule` TR-2.2: 对话归属到 Agent-A、Agent-A 改名"新名" → 该对话 displayName 仍保持原值。Evidence: 对话设置页 / ChatActivity 顶栏截图。

## Task 3: Agent 级上下文共享 + 半满批删
- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 在 [AgentService.java](file:///d:/study/androidplay/huawei_phone/clawphone/src/com/clawphone/app/AgentService.java)：
    - 将静态 `HISTORY` 改为 `Map<String, LinkedList<String[]>> AGENT_HISTORY`（key=agentId），提供 `agentHistory(agentId)` 统一取。
    - `startTask()`：`keepCtx = keepHistory && agentId.equals(currentAgentId)`（同 Agent 即保留，不必同会话）；不 keep 时清该 agent 对应 HISTORY；每轮 LLM 请求从对应 agent 的 HISTORY 遍历塞入 msgs。
    - 将 `while (size >= maxRounds) removeFirst()` 改成**半满批删**：`if (size >= maxRounds) { remove oldest floor(size/2) }`。
    - 切到不同对话但同 agentId 时，先从"该 agent 所有对话聊天文件合并"还原 HISTORY 窗口（按 time 排序取最近 maxRounds 轮；单 agent 累计聊天 >2MB 丢弃最老对话文件再合并）。
  - 日志打点：`CpLog.d("HISTORY", "agent=<id> size=<n> truncated?=<y/n>")`。
- **Acceptance Criteria Addressed**: AC-2, FR-3
- **Test Requirements**:
  - `rule` TR-3.1: maxRounds=10, 连续 10 轮；第 10 轮末 size=10；第 11 轮追加前批删 → size=5。Evidence: HISTORY 日志。
  - `rule` TR-3.2: A agent 对话 X 累积 8 轮 → 切 Y 做新任务 → Y 第一轮 msgs 含 8 轮 (assistant,user)；Y 的 JSON 聊天文件里只落盘 Y 自己的气泡。Evidence: LLM 请求日志 + adb pull sessions/Y.json。

## Task 4: Agent Skill 新增 chat_search action（含时间/范围/发送者过滤）
- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 3
- **Description**:
  - 新建 `ChatSearch.java` 提供：
    - `query(query, scope, fromTs, toTs, role, senderAgentIds, currentAgentId, currentSessionId, ctx)` → 返回多行字符串 + 截断提示；每行 `[yyyy-MM-dd HH:mm:ss] [对话:displayName] [发送者: role或Agent名(id)]: text`；结果条数上限 40，超出时首行 `hit=<n> truncated to 40, refine query`。
    - scope: `this_session` / `same_agent_all`；sender_agent_ids 只在 `role=agent|any` 时生效；该 agent 对话总聊天文件 >2MB 时先丢弃老的。
  - AgentService.execute 中新增 `chat_search` 分支（无权限门控，永久可用）。
- **Acceptance Criteria Addressed**: AC-3, FR-4
- **Test Requirements**:
  - `rule` TR-4.1: 构造两条用户消息分属同 agent 不同对话；`scope=this_session in Y` → 只出 Y；`same_agent_all` → 都出现。Evidence: CpLog `chat_search result:` 截断日志。
  - `rule` TR-4.2: `role=agent, sender_agent_ids=[id]` → 用户行不出；`from_ts/to_ts 1h` 窗内命中。Evidence: 构造时间戳 + 搜索。
  - `rubric` TR-4.3: 结果格式；scale 1-5；1=时间格式错；3=偶尔空行；5=每行符合格式。Pass >= 4。

## Task 5: Prompt 引导 + chat_search 文档补齐
- **Status**: `pending`
- **Priority**: medium
- **Depends On**: Task 4
- **Description**:
  - 修改 [AgentPrompts.java](file:///d:/study/androidplay/huawei_phone/clawphone/src/com/clawphone/app/AgentPrompts.java) `defaultBase()` 新增两段引导：
    - 需要了解用户长期偏好/跨对话信息时 → `chat_search scope=same_agent_all`
    - 需要了解当前对话任务目标/临时规则 → `chat_search scope=this_session`
    - 明确告知返回行都带时间戳 + 所属对话 + 发送者（精确到 Agent 名称）。
  - `toolDocs()` 在零成本工具段落追加 chat_search 完整 JSON 参数与返回样例。
- **Acceptance Criteria Addressed**: FR-5, AC-3 引导质量（软）
- **Test Requirements**:
  - `rule` TR-5.1: 构建无错；启动 Agent 第一轮 system prompt grep `chat_search` 与 `长期偏好` 字样存在。Evidence: CpLog 新增一次截断日志打印 `sys prompt start:` 打前 2000 字符；

## Task 6: 独立对话设置 SessionSettingsActivity + ChatActivity 齿轮跳转重写
- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1, Task 2
- **Description**:
  - 新建 `SessionSettingsActivity.java` + 布局 `activity_session_settings.xml`：
    - 顶栏：返回（左上红取消/不保存）+ 保存（右上蓝）；标题 = 对话 `displayName`。
    - body 三行：① `⏰ 定时器与唤醒` → 启动 TimerWakeupActivity（保持 per-agent 存储；需 agentId）；② `对话名` → 弹窗改名，写入 `ChatSession.displayName` 且再经 `uniqueDisplayName` 校验；③ 删除本对话（红色按钮）。
    - AndroidManifest 注册 `exported=false`。
  - 自启开关按 FR-6.2 注释放入 TimerWakeupActivity（见 Task 8）。
  - 修改 [ChatActivity.java](file:///d:/study/androidplay/huawei_phone/clawphone/src/com/clawphone/app/ChatActivity.java) `btnSettingsTop`：跳转改为 `SessionSettingsActivity`（传 `sessionId`）。
- **Acceptance Criteria Addressed**: AC-4, FR-6
- **Test Requirements**:
  - `rule` TR-6.1: 齿轮打开后页面不包含 API / Key / Model ID / 权限 / 经验池；改名保存后聊天 Tab 行标题 + ChatActivity 顶栏立刻变化。Evidence: 截图 + adb pull sessions JSON。
  - `rule` TR-6.2: 删除对话后 SessionStore.remove 成功、聊天 Tab 行从列表消失。Evidence: MainActivity.refreshSessions 后列表 0 行。

## Task 7: 定时器「任务完成」触发源双区域 + 新建 AgentDialogsPickerActivity（逐对话勾选、保存/取消返回）
- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `AgentDialogsPickerActivity.java`（独立选择页，FR-7.2）+ 布局 `activity_agent_dialogs_picker.xml`（可复用 item_trigger_source）：
    - 顶栏：左上 `btnAdpCancel`（红色白字取消，返回不保存）；中上标题；右上 `btnAdpSave`（蓝色白字保存，返回提交）。
    - body：仅列 `<agentId>` 该 Agent 下全部对话（按 pinned→最近活动排序），每行 CheckBox + displayName；**无子页递归（单层）**。
    - extras：`EXTRA_AGENT_ID`、`EXTRA_ENTER_SELECTED`（入参：当前已经勾选过的对话 id 列表）；返回 intent `EXTRA_RESULT` = ArrayList<String>（新的被勾选的对话 id 集合；若用户点取消 → RESULT_CANCELED，上一页不做任何合并）。
    - AndroidManifest 注册。
  - 修改 [TriggerSourcePickerActivity.java](file:///d:/study/androidplay/huawei_phone/clawphone/src/com/clawphone/app/TriggerSourcePickerActivity.java)：
    - `rowSource` 交互拆分：只 `cbSource` toggle；`tvSourceTitle+tvSourceSub` 整行点击 → `startActivityForResult(..., REQ_AGENT_DIALOGS=3)`，传 `EXTRA_AGENT_ID`（从所属会话 agentId / AGENT_PREFIX id 解析取）。
    - `onActivityResult`：REQ_AGENT_DIALOGS 返回 RESULT_OK 时，**合并**（原已勾选 ∪ 返回集合）；RESULT_CANCELED 不变。
    - "具体对话"列表按 FR-1.3 展示名/黑体大；ALL_SESSIONS 行点击行体不进入子页（仅 CheckBox 可切）。
- **Acceptance Criteria Addressed**: AC-5（第 1、2、3 步）, FR-7
- **Test Requirements**:
  - `rule` TR-7.1: ① 点复选框 → √；② 点行体 → AgentDialogsPickerActivity 打开；勾选 2 → 保存 → 返回，原勾选 √ 仍然保留 + taskDoneSessions 追加 2。Evidence: `timer_<agentId>` JSON 前后对比。
  - `rule` TR-7.2: 再次进子页 → 这 2 条默认勾选；直接点取消返回 → taskDoneSessions **不变**。Evidence: 相同 JSON。
  - `rule` TR-7.3: 全不勾直接点保存返回 → 原行仍保持不勾选（不会被自动选中）。Evidence: JSON 与 UI 复选框。

## Task 8: TimerWakeupActivity 摘要前 4 个对话名 + 更多(N) + SearchPickerActivity（三路独立排序 + 通讯录黑体大字+灰色小字模型名 + 单对象点开展示该对象所有会话）
- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 7
- **Description**:
  - 新建 `SearchPickerActivity.java` + 布局 `activity_search_picker.xml`，注册到 Manifest：
    - 顶：`etSearch` + 右上蓝色白字 `btnSearch`。
    - 下方 3 个切换：`btnChat / btnAgents / btnSortOrder`（`btnSortOrder` 点击弹 4 选菜单：字母序正/倒、时间正/倒）。
    - body：ListView 行（复用 item_trigger_source 布局，勾选框 vs 行体区分点击，同 Task 7 双区拆分）。
    - 通讯录 Tab 每行**强制两行**：标题 `ModelInfo.name`（黑体大字 16sp）、副标题 `ModelInfo.id`（灰色小字 13sp）。
    - 聊天 Tab 每行：标题 `ChatSession.displayName`、副标题 "所属：<Agent展示名>"。
    - 三路独立排序实现（sort() 阶段写 comparator）：
      1. 分组键 `0=已勾选, 1=pinned, 2=其他`（三路独立）；
      2. 各组内部再按用户在「排序」中选择的规则（时间/字母、正/倒）。
    - 点击**任意行（对话或 Agent）的行体** → 进入 Task 7 同款 AgentDialogsPickerActivity（对话行：仅展示该对话单独勾选；Agent 行：展示其下全部对话、逐对话勾选，见 FR-8.2）。
    - 已勾选行 vs 未勾选行：两种进入子页的返回后，按 FR-8.3：
      - 未勾选的即使子页勾选并保存 → 上一页合并；
      - 未勾选但子页空选+保存 → 不写入该 Agent 的任何对话；
      - 结果通过 `onActivityResult RESULT_OK` 合并写入 taskDoneSessions。
    - 搜索过滤：同时 match「对话 displayName / Agent 展示名 (name) / Agent 模型名 (id)」。
  - [TimerWakeupActivity.java](file:///d:/study/androidplay/huawei_phone/clawphone/src/com/clawphone/app/TimerWakeupActivity.java) 调整：
    - 「任务完成」摘要列**前 4 个实际对话 displayName**，剩余数 ≥1 时替换第 5 位成 `更多(N)`（`N = taskDoneSessions.size() - 4`，只对实际具体会话 id 做计数，ALL_SESSIONS / AGENT_PREFIX 不计入该数量，FR-8.1）。
    - 定时器页**顶部**新增一行 Switch：「本 Agent 自启动」 → 写对应 `ModelInfo.autoStart`；和 Agent 设置页保持双向一致。
- **Acceptance Criteria Addressed**: AC-6, FR-8, FR-9
- **Test Requirements**:
  - `rule` TR-8.1: taskDoneSessions = 8 个会话 id → 摘要 4 个 displayName + `更多(4)`。Evidence: UI 截图。
  - `rule` TR-8.2: 通讯录 Tab 每行第一行 bold 16 name / 第二行灰色 13 id。Evidence: UI 截图；点某 Agent（行体非勾选）→ AgentDialogsPickerActivity 列出该 Agent 全部对话，可逐对话勾选+保存/取消。Evidence: 操作+JSON。
  - `rule` TR-8.3: 聊天 Tab 三路独立排序（勾选置顶 → pinned → 其余；每路内部遵守时间倒序/字母正序切换）。Evidence: 构造 10 条对话（3 勾选，1 pinned，6 其他），切排序查看。
  - `rule` TR-8.4: 自启 Switch 与通讯录 Agent 设置同步。Evidence: Prefs.models JSON 双向比对。

## Task 9: 交叉检查 + 构建 + 冒烟
- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1..8
- **Description**:
  - 3 个新 Activity（SessionSettingsActivity / SearchPickerActivity / AgentDialogsPickerActivity）都注册到 AndroidManifest `exported=false`。
  - 代码中对外名字展示一律走 displayName / ModelInfo.name；`ChatSession.title` 仅出现在 SessionStore 内部向后兼容。
  - 修未使用 import；跑 build_clawphone.ps1；安装 → 启动 → 无崩溃；齿轮进入 SessionSettingsActivity，进入对话设置改名生效。
- **Acceptance Criteria Addressed**: AC-9, FR-10
- **Test Requirements**:
  - `rule` TR-9.1: 构建退出码 0，安装成功；`adb logcat | findstr AndroidRuntime` 无 FATAL。
  - `rule` TR-9.2: 齿轮进入 SessionSettingsActivity、改名生效、打开定时器/更多不崩溃。Evidence: 操作 + 截图。
  - `rubric` TR-9.3: 代码整洁度 AC-9；scale 1-5；Pass >= 4。
