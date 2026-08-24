# 上下文管理架构升级 实现计划

## Repository Research

- 项目类型: Android Java 原生 App, 最小 APK 体积, 无第三方库 (仅 HttpURLConnection)
- 当前 LLM 调用链路: `AgentService.startRunLoop()` → `buildSystemPrompt(goal, appList)` + `AGENT_HISTORY` (assistant/user 交替, 半满截断) + `[text observation + screenshot]` → `LlmClient.chat()` → `ActionParser.parse()` → tool dispatch → `pushHistory()`
- 持久化:
  - 会话元数据 & 聊天记录: `SessionStore` (SharedPreferences `sessions` + `files/sessions/{id}.json`)
  - Agent / 定时器配置: `Prefs` (SharedPreferences, key=`timer_<agentId>` / `timer_session_<sessionId>`)
  - 经验池: `Prefs` (SharedPreferences)
- 现有基础组件可直接复用: `chat_search` (ChatSearch.java, 支持 this_session / same_agent_all)、`exp_search`、手机控制工具(a11y/shell/files)、TimerEngine
- 当前已知 5 大问题(对应架构文档第 77-231 行):
  1. `AGENT_HISTORY` 同时承担短期+长期记忆, 半满截断会丢失关键约束
  2. 新 Agent 冷启动严重 (TimerEngine.fireForAgent 只传触发文字, 无上下文摘要)
  3. `chat_search` 是"模型主动搜", 模型不知道它不知道什么
  4. 手机状态 (前台 App / 最近动作 / UI 变化) 仅作为原始 observation, 不是一等上下文
  5. System Prompt 越来越肥 (goal + guide + base + toolDocs + appList), 每轮重复发 250+ app list

## Files and Modules

### V1 新增文件
- `ContextManager.java`: 统一上下文构建入口, 替代 `buildSystemPrompt()` + `AGENT_HISTORY` 的直接拼接
- `TaskState.java`: 结构化当前任务状态 (goal/status/constraints/completed/failed_attempts/current_step/pending/pending_action)
- `DeviceSnapshot.java`: 当前设备状态快照 (device/screen/foreground_app/ui_elements/last_action)
- `SessionCheckpoint.java`: 会话检查点摘要 (facts/constraints/decisions/failed_attempts/open_questions)

### V1 修改文件
- `AgentService.java`: 用 `ContextManager.build()` 替换 `buildSystemPrompt(...)` 和 `AGENT_HISTORY` 遍历; 在各 hook 点 (tool result / done/terminate / spawn sub-agent) 写入 `TaskState` 和触发 `SessionCheckpoint` 压缩; `AGENT_HISTORY` 改为最近 N 轮固定窗口
- `TimerEngine.java`: `fireForAgent()` 派生新会话时, 通过 `ContextManager.buildHandoffCapsule()` 生成摘要注入 GUIDE_NOTES, 不再空启动
- `AgentPrompts.java`: `defaultBase()` 精简 (移出 app list 相关说明, 改为只声明有 app_search 工具)
- `LlmClient.java` (可选): 增加简单 token 估算工具, 给 `ContextManager` 做预算裁剪

### V2 新增文件
- `MemoryStore.java`: SQLite 持久化存储 (tables: messages / tool_events / checkpoints / memories / handoffs)
- `MemoryRetriever.java`: SQLite + FTS 自动 Top-K 检索 (关键词命中 + recency + scope + importance 综合评分)
- `HandoffCapsule.java`: Agent 派生上下文胶囊结构化对象
- `memory_search` / `conversation_search` (扩展 ActionParser)

### V2 修改文件
- `AgentService.java`: 每轮前调用 `MemoryRetriever.retrieve()` 注入 L5 memory; `chat_search` 逐步迁移为 `conversation_search` 与 `memory_search` 双 API
- `ChatSearch.java`: 底层迁移到 MemoryStore SQLite 查询, 同时保留对外 API 兼容
- `SessionStore.java`: 聊天记录写入迁移到 SQLite messages 表, 旧 JSON 迁移脚本保持兼容
- `Prefs.java`: 经验池迁移到 SQLite memories 表

### V3 新增文件
- `UiDiffEngine.java`: Accessibility 树差分 (screen_id / added / removed, 语义压缩快照)
- `ToolResultRefStore.java`: 大工具结果落盘引用 (tool_event_id 只存摘要, 详情文件路径)
- `AutomaticMemoryExtractor.java`: 事件触发自动抽取结构化 Memory (失败尝试/设备事实/项目约束), 带去重和置信度

### V3 修改文件
- `ControlService.java` / 无障碍回调: 接入 `UiDiffEngine`, 不再 dump 完整 XML
- `AgentService.java` tool dispatch: 大结果 (>2KB) 走 `ToolResultRefStore` 落盘, 历史只留摘要+引用ID; 增加 `tool_result_read` 动作

## Implementation Steps

### Stage 0: 前置准备 (1-2 天工作量)
1. 在 AgentService 中补一套 debug 工具: 统计每轮 messages 各部分 token 估算 (System/History/Observation), CpLog 打点日志, 方便对比 V1 前后 token 变化
2. 验证 `AGENT_HISTORY` 与 `SessionStore.loadChat()` 数据重复情况, 确认哪些数据在内存里唯一来源 (AGENT_HISTORY 目前是 assistant/user action+obs 对, 非完整聊天文本)

### Stage V1.1: 引入 4 个基础对象类
3. 新增 `TaskState.java`: JSON 可序列化, 字段: goal/status/constraints/completed/failed_attempts/current_step/pending/pending_action(结构化, 含 tool/args/requires_confirmation)
4. 新增 `DeviceSnapshot.java`: JSON 可序列化, 字段: device/screen_on/locked/foreground_package/foreground_activity/orientation/window_title/ui_elements[] (id/text/clickable/bounds)/last_action(type/target/result)
5. 新增 `SessionCheckpoint.java`: JSON 可序列化, 字段: session_id/goal/facts/constraints/decisions/failed_attempts/open_questions/user_prefs/created_at_ts/last_message_id
6. 新增 `ContextManager.java` 空壳类, 持有 build() 接口和静态 helper

### Stage V1.2: ContextManager.build() 第一版替换 buildSystemPrompt
7. `ContextManager.build(agentId, sessionId, goal, observation, jpegB64, profile)` 返回 `List<LlmClient.Msg> msgs`。结构:
   - Msg#1 (role=system): L0 Agent Identity (AgentPrompts.defaultBase 精简版 + toolDocs) → 稳定层
   - Msg#2 (role=system): L1 TaskState (JSON 序列化, 带"当前任务进度"section头) → 结构化进度
   - Msg#3 (role=system): L2 DeviceSnapshot (JSON, 或压缩文本 Semantic UI) → 设备状态
   - Msg#4 (role=system, 若无 checkpoint 可省略): L4 SessionCheckpoint (summary) → 长期压缩摘要
   - Msg#5~N-1 (role=assistant/user): L3 最近 8 轮消息 (固定窗口, 不是半满截断) → 近期原文
   - Msg#N (role=user): observation + 截图 → 当前轮输入
8. 在 AgentService 的 LLM 请求构建处, 用 `ContextManager.build(...)` 替换 `msgs.add(system)` + `AGENT_HISTORY` 遍历; 保留 LlmClient 调用不变
9. `AGENT_HISTORY` 改为 `RecentMessagesWindow`: 固定保存最近 8 个 (assistant/user) 对, 超了直接丢最老的, 不再半满截断; 旧内容走 SessionCheckpoint

### Stage V1.3: TaskState / DeviceSnapshot 钩子写入
10. TaskState 写入点:
    - 启动: 从 goal 初始化 TaskState(status=running, goal=...)
    - 每个 tool 结果: 根据动作推断 completed[] (如 open_app 成功→加入 completed), 并更新 current_step
    - done/terminate 动作: 把 result message 加入 completed[], status=done/terminated
    - ask_user / pending_action: 保存到 TaskState.pending_action
    - 失败重试: 加到 failed_attempts[]
11. DeviceSnapshot 写入点:
    - 每轮 `ControlService.dumpText()` 后, 本地语义压缩为 Semantic UI Snapshot (编号文本节点, 只保留 clickable/editable/scrollable + 重要容器), 对应文档"第七节"
    - Tool dispatch 前 → last_action.preview; tool result 后 → last_action.result (success/fail + summary)
    - ControlService 前台窗口变化回调 → 更新 foreground_package/activity
12. 把 buildSystemPrompt 里的 GUIDE_NOTES / customPrompt 注入逻辑移到 ContextManager

### Stage V1.4: SessionCheckpoint 摘要 + HandoffCapsule 派生
13. SessionCheckpoint 触发条件:
    - 每 15 轮 (RecentMessagesWindow 超阈值溢出前)
    - done/terminate 任务阶段完成
    - Agent 即将派生子 Agent 前
14. 摘要策略 (方案 B 代码优先, 方案 A LLM 备用):
    - 从 TaskState 结构化抽取: completed/failed_attempts/constraints → checkpoint 直接填
    - 从溢出消息用规则抽用户偏好和明确结论 (不要重复尝试 X)
    - 可选: 当 token 预算充足时, 用当前 LLM 跑一次 summary_prompt 生成结构化 JSON, 但不作为依赖路径
15. TimerEngine.fireForAgent() 派生新会话时: `ContextManager.buildHandoffCapsule(parent_session_id, trigger_msg, goal)` → 生成 JSON 摘要 (文档第十八节 HandoffCapsule: from_agent/from_session/goal/reason/constraints/completed/current_state/open_questions/memory_refs), 作为 system message 注入新会话第一轮, 解决"新 Agent 冷启动"问题
16. SessionSettingsActivity 新增"查看会话摘要"入口, 便于调试

### Stage V1.5: App List 精简 + Token Budget 保护
17. AgentPrompts.defaultBase() 和 toolDocs 中: 移除 250 个 app 全量列表, 改为说明"可用 app_search(query) 查询指定 App 包名", app_list 动作保留
18. ContextManager.build() 末尾加 TokenBudget 保护: 简单按字符估算 (中文1字≈2token, 英文1词≈1.3token), 超出预算按优先级丢:
    - 永远不丢: system 安全规则 / user 当前输入 / task constraints / pending confirmation
    - 第二优先: DeviceSnapshot + last_action_result
    - 第三优先: Recent messages (先删最老的)
    - 第四优先: Checkpoint summary (先 trim history 列表)
    - 最先丢: 冗长 tool output (超 200 行截前 50 行 + 引用号)
    - 最先丢: jpegB64 (超预算就不发, 让模型下一轮明确 look 才带)

---

### Stage V2.1: SQLite MemoryStore (数据库层)
19. 新增 `MemoryStore.java`: android.database.sqlite.SQLiteOpenHelper, 初始表:
    - messages(id TEXT PK, session_id, agent_id, role, content, created_at, reply_to_id)
    - checkpoints(session_id PK, summary_json, task_state_json, last_message_id)
    - memories(id TEXT PK, scope, scope_id, type, subject, content, importance REAL, created_at, source_session_id, expires_at)
    - handoffs(id TEXT PK, parent_session_id, child_session_id, capsule_json, created_at)
    - tool_events(id TEXT PK, session_id, tool, args_json, result_summary, result_blob_path, success, created_at)
20. 迁移脚本: 首次创建 DB 时, 从 SessionStore JSON 文件批量 insert messages; 从 Prefs exp_ 项迁移到 memories; checkpoint 表为空, 后续按 V1 方式增量填充

### Stage V2.2: 自动 Top-K Memory 检索 (解决"模型不知道它不知道什么")
21. 新增 `MemoryRetriever.java`: 每轮在 ContextManager.build() 第 6 步自动调用
    - Query 来源: goal + current_step + DeviceSnapshot.foreground_package + last_action.target + user_message
    - SQLite FTS4 搜索: message.content / checkpoint.summary / memory.content / memory.subject / tool_events.result_summary
    - 综合评分 = 0.55 textMatch + 0.20 recency(days recency decay) + 0.15 scopeMatch (SESSION > AGENT > DEVICE > GLOBAL 匹配当前上下文) + 0.10 memory.importance
    - 返回 Top 5, 总长度 ≤ 2KB, 超出按评分裁剪
22. ContextManager.build() 中增加 L5 Retrieved Memories section: Msg (role=system) 输出 "【相关记忆】\n 1) [MEM] ...\n 2) [CHECKPOINT] ...\n 3) [MESSAGE] ..." 每条带时间戳+来源, 对应架构文档"第十三节"
23. chat_search 保留 (模型主动深挖), 但底层复用 MemoryStore 查询

### Stage V2.3: memory_search + conversation_search 双 API
24. ActionParser.java 新增 `memory_search` (搜 memories/checkpoints/tool_events 结构化结论) 和 `conversation_search` (搜原始聊天消息原文, 成本更高) 两个动作参数解析, 老 `chat_search` 转发到 `conversation_search` 兼容
25. AgentPrompts.toolDocs() 中同时列两个 API, 明确"优先用 memory_search, 需要用户原话才用 conversation_search"
26. AgentService.java 对应 case 分支: `memory_search` → MemoryRetriever.searchMemory, `conversation_search` → MemoryRetriever.searchConversation

### Stage V2.4: DEVICE MEMORY + MEMORY 分级 scope
27. Device Memory (架构文档第十七节): 每次 EPERM / sysfs 拒绝 / shell 失败 / build fingerprint 检测后, 自动写入 memories (scope=DEVICE, scope_id=设备序列号 Build.SERIAL)
28. Memory scope 枚举: MESSAGE / SESSION / AGENT / DEVICE / PROJECT / GLOBAL, MemoryRetriever 按 scope 过滤匹配当前 Agent
29. Memory 去重: 写入前按 scope + subject + content-normalized-hash 查存在, 已存在则 importance/last_verified_at/source_count 累加, 不插入新行 (架构文档第三十八节)
30. 手动写 memory 的 `memory_write` 动作: 仅 Agent 可写 AGENT 以下 scope; DEVICE 及以上只能自动抽取, 防止垃圾爆炸

---

### Stage V3.1: UI Diff Engine
31. UiDiffEngine: 上一轮 screen_id / element map 对比当前; 输出 `{screenChanged: bool, added:[], removed:[], unchangedCount: int}`
32. screen_hash 相同 → ContextBuilder 输出 "UI unchanged. Last screen: 微信/发现"; 否则输出 Changed section (added 优先, removed 省略短文本, 仅保留关键项) + full semantic list 兜底
33. DeviceSnapshot.ui_elements 存 screen_id 和 diff 摘要, 每轮不落完整树

### Stage V3.2: Tool Result 引用化 + Screenshot 引用化
34. ToolResultRefStore: tool output > 2KB (或 shell > 50 行) 时, 完整结果写入 `files/tool-results/{evt_id}.txt`, 上下文只给 capped 结果 + summary + `Full: tool_event://{id}`; 新增 `tool_result_read(id)` 动作让模型按需展开
35. Screenshot: 历史 AGENT_HISTORY / messages 表只存 screenshot_id + Vision Summary (文本摘要, 比如"微信聊天页,底部4个tab") + 文件路径 `files/session-assets/{shot_id}.webp`; 真实图只在当前轮 look 动作时才发给 LLM

### Stage V3.3: 自动 Memory 抽取
36. AutomaticMemoryExtractor 在以下事件触发:
    - shell/file 工具 EPERM/denied 连续失败 → DEVICE memory (scope=DEVICE)
    - Task done 前的 final status 总结 → AGENT memory (scope=AGENT)
    - ask_user 询问用户偏好时, 确认后写入 → AGENT memory
    - 完成复杂任务 (超过 30 轮) → AGENT memory 总结 + source_session_id
37. Memory confidence: Build.FINGERPRINT 改变时, scope=DEVICE 的 memories confidence 降级 0.3 (架构文档第三十九节)

## Dependencies and Considerations

- 不引入外部库: SQLite 用 android 自带 android.database.sqlite, FTS4 用 SQLite 内置; 禁用 Room/Chroma/FAISS/embedding 等任何需要依赖的方案
- JSON 序列化一律用 org.json 已内置库, 禁止 jackson/gson
- V1 阶段必须保持完全向后兼容: 旧 SessionStore JSON 继续可读, 数据库在 V2 才引入; V1 上线后 V2 可渐进迁移
- HandoffCapsule 必须控制在 500-1500 tokens, 超了就先截失败尝试再截 completed, 保留 constraints 和 open_questions 最高优先级
- RecentMessagesWindow 固定窗口大小需可在 Prefs 中调 (默认 8, 调试用, 上线不改)
- token 估算: MVP 用字符估算 (1 中文字符=1.8 token, 1 英文字母=0.3 token), 不做真实 tiktoken (省依赖)

## Validation

每阶段完成后:
1. `build_clawphone.ps1` 构建通过, `adb install -r` 无崩溃
2. Token 基准测试: 连续跑 3 轮已知任务 (打开微信搜朋友圈 3 屏滚动), 对比 V0→V1→V2 每轮 token 估算消耗 (CpLog 打点), 预期 V1 比 V0 降 15-25%
3. 冷启动测试: A Agent 完成 20 轮后 done, 触发 B Agent; 检查 B 第一轮 system prompt 中 HandoffCapsule 是否包含 completed[] 和 constraints[] (日志验证)
4. 历史约束持久性测试: 第 1 轮 ask_user "不要卸载微信" 用户确认 → 写入 TaskState.constraints + SessionCheckpoint → 第 50 轮后 (超过 recent window) 让模型执行 "卸载微信" 操作 → 验证 ask_user 保护仍然触发 (即约束没丢)
5. Top-K Memory 验证: 先跑一次 "GPU 温度 EPERM" 任务, 产生一条 DEVICE memory; 第二次打开新 Agent 跑 "检查 GPU" → 自动检索到 memory 并显示在 Retrieved Memory section
6. UI Diff 验证: 连续 2 轮屏幕完全相同 → 第二轮只出现 "UI unchanged" 不重复 dump 120 行文本

## Risks

1. **Risk: V1 ContextManager 替换 buildSystemPrompt 后某些特殊场景 (test 会话 / deepseek 结束动作) 行为变化**
   Handling: 用 feature flag 控制, 保留 buildSystemPrompt 原路径 fallback; 先默认启用并保留日志回退开关
2. **Risk: SQLite 在 Android 某些 ROM 下 FTS4 不可用**
   Handling: 检测到 FTS4 不支持时降级为 LIKE 前缀模糊搜索 + recency 排序, 仅降低召回不阻塞功能
3. **Risk: SessionCheckpoint 每 15 轮触发, 额外 LLM 调用增加 token/延迟**
   Handling: 默认用代码抽取 (无 LLM 开销), LLM 摘要仅在 Task done 时按需触发一次, 不在每 15 轮执行
4. **Risk: HandoffCapsule 摘要不完整, 子 Agent 仍会踩坑**
   Handling: HandoffCapsule 中带上 source_session_id, 并给模型 `context_fetch(session_id, before_msg_id, limit)` 工具 (V2 阶段), 让子 Agent 能查原文
5. **Risk: app_list 从 system prompt 移除, 模型在需要 open_app 时会忘记用 app_search 先查包名**
   Handling: 在 AgentPrompts.toolDocs() open_app 条目前补一句 "若不确认包名必须先 app_search 查询, 禁止猜包名直接 open"; 并在 FailedAttempt 中自动记录"X 包名不存在, 必须用 app_search"
