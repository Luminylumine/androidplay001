你的方向是对的：**“仿微信的 Agent 对话 + 手机控制器”最需要解决的不是把更多聊天记录塞给模型，而是让模型在每一轮都拿到“恰好够用的上下文”。**

从你当前架构看，模型每轮收到的是：

```text
system prompt
+ AGENT_HISTORY
+ 当前屏幕文本 / 截图
```

历史会话另有落盘数据，并可通过 `chat_search` / `exp_search` 主动检索；新触发会话则基本从空历史开始。

这套设计能跑，但我建议做一次比较明确的上下文架构升级。

------

# 一、先理解：远端模型实际上“看不到你的 App”

无论 UI 看起来多像微信，远端 LLM 每一次请求本质上仍然是：

```text
HTTP Request
    ↓
System instructions
Messages
Tools / tool descriptions
Current observation
```

模型不会天然知道：

- 当前是哪台手机；
- 屏幕在哪个 App；
- 上一次点了什么；
- 这个 Agent 两小时前做过什么；
- 另一个 Agent 踩过什么坑；
- 某个聊天气泡对应哪个任务；
- 文件系统里有什么；
- 数据库里有什么。

除非你：

> **本轮主动塞给它，或者让它能检索。**

而且还有一个非常重要的概念：

### HTTP Header 不是模型上下文

你现在类似：

```http
Authorization: Bearer ...
Content-Type: application/json
```

这些是服务端协议。

即使以后增加：

```http
X-Agent-Id: xxx
X-Session-Id: xxx
```

通常也只是你自己的服务端 metadata，**模型本身并不会因此“知道” Agent ID**。

真正希望模型看到的信息，应进入：

```text
system/developer/user/tool message
```

或者相应 API 的结构化 context。

------

# 二、你当前架构最大的 5 个问题

## 1. `AGENT_HISTORY` 同时承担了“短期记忆”和“长期记忆”

这是最应该拆掉的地方。

当前：

```text
AGENT_HISTORY
  ↓
不断增长
  ↓
到一定程度截掉前半段
```

这种方式的问题是：

```text
前 30 轮：
用户说“绝对不要卸载微信”

第 100 轮：
历史截断

模型：
不知道这个约束了
```

关键约束和闲聊现在是同一级别。

这是危险的。

------

# 2. 新 Agent 冷启动严重

现在 A Agent 触发 B Agent：

```text
A:
已经排查：
- WiFi 正常
- Shizuku 正常
- 某节点 EPERM
- 不要再尝试方案 X

        ↓ 创建 B

B:
“你好，请问需要做什么？”
```

B 如果只拿触发文字，不知道 A 已经完成什么，就很容易：

> 第二个 Agent 再踩第一个 Agent 已经踩过的坑。

这正是多 Agent 系统中最常见的 token 和执行成本浪费。

------

# 3. `chat_search` 是“模型主动搜索”，但模型不知道它不知道什么

这是经典问题。

假设历史里有：

```text
三天前：
这台华为不允许 shell 读 /sys/class/thermal
```

今天模型正在调 GPU。

模型如果不知道三天前存在这条结论，它就未必会主动执行：

```text
chat_search("Huawei thermal permission")
```

所以：

> 仅提供搜索工具不够。

还需要：

> **Context Manager 在每轮请求前自动检索。**

------

# 4. 手机状态属于“一等上下文”，现在却比较像临时 Observation

手机 Agent 与纯聊天 Agent 最大区别就是：

> 世界状态一直在变化。

例如：

```text
上一秒：
微信聊天页

Agent:
点击“设置”

下一秒：
设置页
```

模型真正需要知道的是：

```text
我刚执行了什么
结果是什么
当前手机在哪
当前 UI 是什么
上一轮 UI 与现在变化了什么
```

而不是简单重新收到一大坨 Accessibility XML。

------

# 5. System Prompt 容易越来越肥

现在 System Prompt 里面已经混合：

```text
goal
guide notes
base prompt
tool docs
app list
```

以后再加：

```text
设备信息
权限
Agent memories
shared memories
session summary
```

很快会变成：

```text
System Prompt = 20K tokens
```

而且每轮重复发送。

这是最不经济的结构。

------

# 三、我建议你的最终架构：6 层上下文

对于你的项目，我会设计成：

```text
┌─────────────────────────────────────┐
│ L0 Agent Identity / Rules           │
├─────────────────────────────────────┤
│ L1 Current Task State               │
├─────────────────────────────────────┤
│ L2 Current Phone State              │
├─────────────────────────────────────┤
│ L3 Recent Conversation              │
├─────────────────────────────────────┤
│ L4 Rolling Summary / Checkpoint     │
├─────────────────────────────────────┤
│ L5 Retrieved Memory                 │
├─────────────────────────────────────┤
│           Current User Input        │
└─────────────────────────────────────┘
```

这是你整个 Agent 上下文系统的核心。

------

# 四、L0：Agent Identity——只放真正长期稳定的信息

例如：

```text
你是 PhoneController Agent。

职责：
- 通过手机控制工具完成用户任务
- 操作前确认当前手机状态
- 不重复执行已经确认失败的方案
- 高风险操作必须请求用户确认

工具规则：
...
```

这里可以有：

```text
Agent 名称
Agent 类型
核心行为规范
工具说明
安全策略
```

但不要放：

```text
当前微信在哪
当前任务完成了几步
昨天用户说了什么
```

这些都不属于 L0。

------

# 五、L1：Current Task State——这是最重要的新增层

不要只保存：

```text
goal = "帮我安装微信"
```

改成结构化：

```json
{
  "goal": "安装微信并登录",
  "status": "running",
  "constraints": [
    "不能清除用户数据",
    "不要切换 Google 账号"
  ],
  "completed": [
    "确认网络正常",
    "打开应用商店"
  ],
  "current_step": "搜索微信",
  "failed_attempts": [
    {
      "action": "使用华为应用市场 deep link",
      "reason": "activity not exported"
    }
  ],
  "pending": [
    "找到微信",
    "安装",
    "等待用户登录"
  ]
}
```

模型每轮都看到这一小块。

这样就不需要从 50 轮聊天中自己总结：

> “我现在到底做到哪了？”

------

# 六、L2：Current Phone State——手机 Agent 的关键

建议专门建立：

```java
DeviceContext
```

例如：

```json
{
  "device": "EVE-AL00",
  "screen_on": true,
  "locked": false,

  "foreground_package": "com.tencent.mm",
  "foreground_activity": ".ui.LauncherUI",

  "orientation": "portrait",

  "window": {
    "title": "微信"
  },

  "ui": {
    "screen_id": "sha256:abc...",
    "elements": [
      {
        "id": 12,
        "text": "通讯录",
        "clickable": true,
        "bounds": [0, 2100, 270, 2280]
      },
      {
        "id": 13,
        "text": "发现",
        "clickable": true
      }
    ]
  },

  "last_action": {
    "type": "tap",
    "target": "发现",
    "result": "success"
  }
}
```

模型看到的就非常清楚：

> 我在哪、刚干什么、现在能点什么。

------

# 七、不要每轮塞完整 Accessibility Tree

例如系统原始树可能是：

```text
500 个 node
每个 node：
class
resource-id
bounds
description
text
flags...
```

非常浪费。

建议先本地转换成：

## Semantic UI Snapshot

只保留：

```text
文本节点
可点击节点
可编辑节点
可滚动节点
重要容器
```

例如：

```text
SCREEN: 微信 / 发现

[1] 朋友圈             clickable
[2] 视频号             clickable
[3] 扫一扫             clickable
[4] 摇一摇             clickable
[5] 看一看             clickable
```

模型操作：

```text
tap_ui(3)
```

远比让模型自己解析 XML 好。

------

# 八、进一步做 UI Diff

这是手机 Agent 很值得做的优化。

上一轮：

```text
微信聊天列表
```

点击：

```text
设置
```

下一轮不要再次发送 10KB UI。

发送：

```text
UI_CHANGED

Previous:
微信聊天列表

Current:
设置

Added:
[1] 账号与安全
[2] 消息通知
[3] 通用
[4] 朋友权限

Removed:
聊天列表...
```

如果：

```text
screenHash 相同
```

甚至可以：

```text
UI unchanged.
```

### 这能显著降低手机连续操作的 token 消耗。

------

# 九、截图也不要进入历史

这是另一个重要设计。

如果每轮都：

```text
user:
 screenshot 1

assistant

user:
 screenshot 2

assistant

user:
 screenshot 3
```

上下文成本会很快爆炸。

推荐：

```text
历史：
只保存 screenshotId + vision summary

当前轮：
必要时才发真实图片
```

例如历史只记录：

```text
Screenshot #A813
Summary:
微信聊天页面，底部有“微信/通讯录/发现/我”
```

真实图片可以落盘：

```text
/files/session-assets/A813.webp
```

如果模型之后真的需要：

```text
image_get("A813")
```

再读取。

------

# 十、L3：Recent Conversation——只保留最近几轮原文

我建议：

```text
最近 6–12 个 message
```

不要“直到 token 满了再截一半”。

例如：

```text
Recent Window = 8 messages
```

这些用于理解：

```text
“继续”
“刚才那个”
“第二个”
“不是这个”
```

因为这些短距离代词很依赖原始对话。

------

# 十一、L4：Rolling Summary——把旧对话压缩成工作记忆

超过 recent window 的历史：

```text
不要直接删
```

而是定期变成：

```text
SessionCheckpoint
```

例如：

```text
【会话摘要】

用户目标：
修复 SysMon GPU 监控。

已确认：
- Huawei EVE-AL00
- HarmonyOS 2
- Mali-G51
- shell uid=2000
- /sys/class/thermal → EPERM
- cpufreq 多数节点 → EPERM
- /proc/stat 可读

已失败：
- /dev/mali0 ioctl，shell EPERM

重要结论：
- 不要再次尝试 mali ioctl
- 下一步检查 devfreq 和 dumpsys gpu

用户偏好：
- 小 APK
- 低运行开销
- Java
```

以后发送：

```text
summary + recent turns
```

而不是整个聊天。

------

# 十二、不要让模型每次自己写摘要

推荐在特定事件做 checkpoint：

```text
每 10～20 个 turn
任务阶段完成
上下文达到阈值
Agent 即将退出
Agent 即将派生子 Agent
```

可以：

### 方案 A：LLM summary

让当前模型生成：

```json
{
  "facts": [],
  "constraints": [],
  "decisions": [],
  "failed_attempts": [],
  "open_questions": []
}
```

### 方案 B：代码抽取

tool events、本地状态等直接结构化，不需要 LLM。

最佳方案：

> 两者结合。

------

# 十三、L5：Retrieved Memory——自动搜索，而不是全塞

这是我认为你多 Agent 架构最值得增加的部分。

目前：

```text
模型
  ↓
自己决定 chat_search()
```

建议变成：

```text
用户消息
+
当前任务
+
当前手机状态
        ↓
ContextRetriever
        ↓
自动搜索：
  当前 Session
  当前 Agent 历史
  当前项目
  shared experience
        ↓
Top K
        ↓
ContextBuilder
```

------

# 十四、不要一上来搞向量数据库

对于你的目标：

```text
APK 小
实现简单
速度快
稳定
```

第一版完全没必要：

```text
Chroma
FAISS
Milvus
embedding model
```

手机里跑这些反而复杂。

推荐：

```text
SQLite
+
FTS
+
metadata filter
+
recency
```

就够用了。

例如：

```text
query = "Kirin GPU permission"
```

搜索：

```text
message text
summary
experience title
experience body
failed attempt
```

综合排序：

[
score =
0.55\times textMatch
+
0.20\times recency
+
0.15\times scopeMatch
+
0.10\times importance
]

已经非常实用。

------

# 十五、Memory 不应该只有“一堆文本”

建议统一成：

```java
MemoryItem
{
  "id": "mem_123",

  "scope": "PROJECT",

  "type": "FAILED_ATTEMPT",

  "subject": "Huawei EVE-AL00 GPU",

  "content":
    "shell uid 2000 对 /dev/mali0 ioctl 返回 EPERM",

  "tags": [
    "Huawei",
    "Mali",
    "GPU",
    "SELinux"
  ],

  "source_session": "session_55",

  "importance": 0.91,

  "created_at": 1787570000,

  "expires_at": null
}
```

------

# 十六、Memory Scope 要分级

这非常重要。

建议：

```text
MESSAGE
SESSION
AGENT
DEVICE
PROJECT
GLOBAL
```

例如：

### SESSION

```text
“这一次任务先不要重启手机”
```

任务结束就没用了。

------

### DEVICE

```text
Huawei EVE-AL00：
shell 无法读取 thermal sysfs
```

以后所有 Agent 控这台设备都有用。

------

### PROJECT

```text
SysMon 规定：
GPU 找不到必须显示 N/A，
禁止用 FPS 冒充 GPU %
```

整个项目共享。

------

### GLOBAL

真正跨项目的经验。

非常少。

------

# 十七、我尤其推荐增加 DEVICE MEMORY

因为你这是：

> Agent + 手机控制器。

设备本身就是稳定实体。

例如：

```text
device:FEDBB23413006269
```

存：

```text
OEM = Huawei
Android = 10
Shizuku = available
root = no
thermal sysfs = denied
Mali ioctl = denied
screen resolution = ...
known quirks = ...
```

以后任何 Agent 接手这台手机：

```text
自动拿 Device Profile
```

立即少踩很多坑。

------

# 十八、多 Agent 最关键：Handoff Capsule

A Agent 触发 B Agent 时，绝对不要：

### 方案 1

什么都不给。

也不要：

### 方案 2

把 A 的全部 200 条聊天发给 B。

应该生成：

```text
Handoff Capsule
```

例如：

```json
{
  "from_agent": "agent_gpu_research",
  "from_session": "s123",

  "goal":
    "继续解决 Huawei GPU frequency",

  "reason":
    "需要专门分析 Huawei BSP",

  "constraints": [
    "无 root",
    "shell uid 2000",
    "不要使用 /dev/mali0 ioctl"
  ],

  "completed": [
    "/sys/class/thermal shell EPERM",
    "mali ioctl EPERM"
  ],

  "current_state": {
    "device": "EVE-AL00",
    "foreground": "SysMon"
  },

  "open_questions": [
    "GPU devfreq 是否存在",
    "Huawei 是否存在 vendor GPU service"
  ],

  "memory_refs": [
    "mem_108",
    "mem_109"
  ]
}
```

大小控制在：

```text
500～1500 tokens
```

足够。

------

# 十九、新 Agent 第一轮应该看到什么

推荐：

```text
SYSTEM
Agent rules

TASK
Handoff Capsule

DEVICE
Current Device State

MEMORY
Top relevant retrieved memories

USER
Trigger message
```

而不是：

```text
SYSTEM
...

USER
“继续处理一下”
```

------

# 二十、让子 Agent 还能“追溯原聊天”

Handoff 不够时，可以：

```text
handoff.source_session_id
```

然后给模型工具：

```text
context_fetch(
  session_id,
  before_message_id,
  limit
)
```

所以：

```text
默认只给摘要
需要细节时再查原文
```

这就是：

> **摘要优先 + 按需展开**

这比把全量上下文塞进去好很多。

------

# 二十一、建议将 `chat_search` 拆成两个层次

现在类似一个：

```text
chat_search
```

以后推荐：

```text
memory_search
conversation_search
```

------

## `memory_search`

搜索：

```text
结构化结论
经验
失败方案
设备事实
项目约束
```

返回内容短。

模型优先使用。

------

## `conversation_search`

只有想确认：

```text
“用户原话到底怎么说的？”
```

才搜 raw chat。

成本更高。

------

# 二十二、工具结果也要做摘要

例如：

```bash
find /sys -name '*gpu*'
```

返回 300 行。

不要把 300 行永久留在 conversation。

当前轮可以给模型：

```text
完整结果
```

或者 capped 结果。

然后持久化成：

```text
ToolEvent #873
```

并在上下文只保留：

```text
find GPU paths:
132 results.
Relevant:
- /sys/class/devfreq/...
- /sys/...
Full result: tool://873
```

以后需要：

```text
tool_result_read(873)
```

------

# 二十三、把手机工具调用也变成 Event Log

建议定义：

```text
AgentEvent
```

类型：

```text
USER_MESSAGE
MODEL_MESSAGE
TOOL_CALL
TOOL_RESULT
DEVICE_STATE
SCREENSHOT
TASK_UPDATE
MEMORY_WRITE
HANDOFF
ERROR
```

于是整个系统实际上是：

```text
Event Store
       ↓
Context Builder
       ↓
LLM
```

而不是：

```text
List<Message>
       ↓
LLM
```

这会让未来扩展非常舒服。

------

# 二十四、推荐 SQLite 结构

第一版已经足够：

```sql
sessions
messages
tool_events
device_snapshots
task_states
checkpoints
memories
handoffs
```

例如：

```text
messages
--------
id
session_id
role
content
created_at

tool_events
-----------
id
session_id
tool
args_json
result_summary
result_blob_path
success
created_at

memories
--------
id
scope
scope_id
type
subject
content
importance
created_at
source_session_id

checkpoints
-----------
session_id
summary
task_state_json
last_message_id

handoffs
--------
id
parent_session_id
child_session_id
capsule_json
```

------

# 二十五、原始大数据可以继续文件落盘

SQLite 只存索引。

例如：

```text
screenshots/
tool-results/
attachments/
accessibility-dumps/
```

这样：

```text
DB 小
查询快
附件管理简单
```

------

# 二十六、运行时内存里只保留 Working Set

不建议把整个 Session JSON 全加载。

内存：

```text
CurrentTaskState
CurrentDeviceState
RecentMessages
CurrentCheckpoint
TopRetrievedMemories
```

例如：

```text
< 100 KB
```

就足够。

其他全部按需 SQLite 查询。

------

# 二十七、ContextBuilder 是整个系统核心

我建议增加一个：

```java
ContextBuilder
```

接口大致：

```java
ContextBundle build(
    String agentId,
    String sessionId,
    UserInput input,
    DeviceSnapshot device,
    TokenBudget budget
);
```

内部：

```text
1. load AgentProfile
2. load TaskState
3. get DeviceContext
4. load recent messages
5. load latest checkpoint
6. auto retrieve memories
7. rank
8. trim by token budget
9. serialize messages
```

------

# 二十八、最终请求不要只是拼字符串

逻辑上应构造成：

```text
SYSTEM
──────────────────
Agent identity
Safety rules
Tool rules

CONTEXT
──────────────────
Task State

Device State

Session Summary

Relevant Memories

RECENT CHAT
──────────────────
User
Assistant
User
Assistant

CURRENT
──────────────────
Current observation
Current user request
```

即使底层 OpenAI-compatible API 最后仍是：

```json
messages[]
```

你本地也应该有这种清晰层次。

------

# 二十九、Token Budget 必须由程序控制

不要：

```text
一直添加
→ API 报 context too long
```

假设 context window：

```text
128K
```

也不要真用满。

建议例如：

```text
System/tool rules       5%
Task state              5%
Device state            8%
Session summary         8%
Retrieved memory       15%
Recent messages        25%
Current observation    15%
Reserved output        19%
```

不是要求严格按百分比，而是应该有：

```java
ContextBudget
```

------

# 三十、优先级必须明确

context 不够时：

### 永远不能删

```text
系统安全规则
当前用户指令
任务 constraints
pending confirmation
```

### 第二优先

```text
Current Device State
last action/result
```

### 第三优先

```text
Recent messages
```

### 第四优先

```text
relevant memories
```

### 最先丢

```text
旧聊天原文
旧截图
冗长 tool output
不相关 App list
```

------

# 三十一、`appList` 不应该每轮完整塞

你当前 System Prompt 每轮带 installed apps。

如果手机装了：

```text
250 apps
```

这其实很浪费。

建议 System 只说明：

```text
可使用 app_search / app_list 工具查询安装应用。
```

只有需要打开 App：

```text
“打开微信”
```

ContextBuilder 本地检索：

```text
微信
com.tencent.mm
```

然后注入：

```text
Relevant apps:
- 微信: com.tencent.mm
```

不用每轮发 250 个包名。

------

# 三十二、工具文档也可以按需注入

如果工具很多：

```text
click
swipe
shell
files
memory
calendar
network
adb
...
```

不一定所有工具每轮都给。

可以按 Agent 类型提供工具集：

```text
PhoneAgent
→ UI + Shell + Files

ResearchAgent
→ Search + Memory

SchedulerAgent
→ Alarm + Session
```

这样：

> tool schema 本身也省 token。

------

# 三十三、微信式 UI 可以天然帮助上下文管理

你的“仿微信”设计其实非常适合 Context Scope。

每一个聊天对象：

```text
一个 Agent
```

每个会话：

```text
一个 Session / Thread
```

然后允许用户：

```text
长按某条消息
→ “作为新任务发送给 Agent X”
```

此时：

```text
parent_message_id
```

天然成为 Handoff Anchor。

比如：

```text
用户长按：
“帮我继续调查 Huawei GPU”

→ 转发给 Hardware Agent
```

系统自动生成：

```text
消息本身
+ 当前任务摘要
+ 相关设备状态
+ Top Memories
```

这比单纯复制文字智能很多。

------

# 三十四、非常推荐增加“引用消息”

像微信引用：

```text
用户：
> Agent：这个节点 shell 不可读
那有没有别的方法？
```

数据库里不要只保存引用文字。

保存：

```text
reply_to_message_id = 8831
```

ContextBuilder 可以精准补原消息。

因此处理：

```text
“这个”
“它”
“刚才那个”
```

可靠性会明显提升。

------

# 三十五、用户发“继续”时怎么办

本地 Intent Resolver 看到：

```text
继续
```

不用做复杂 NLP。

ContextBuilder 自动提高：

```text
CurrentTaskState
last assistant message
last tool event
CurrentDeviceState
```

优先级。

于是模型就知道：

> 继续什么。

------

# 三十六、手机 Agent 还应该维护 `PendingAction`

很重要。

例如模型问：

```text
准备删除微信数据，是否确认？
```

你必须保存：

```json
{
  "pending_action": {
    "tool": "clear_app_data",
    "package": "com.tencent.mm",
    "requires_confirmation": true
  }
}
```

下一轮用户：

```text
可以
```

ContextBuilder 必须带上这个。

否则模型可能根本不知道：

> “可以”指什么。

------

# 三十七、共享经验池应该“写少读精”

不要每个 Agent 完成一步就：

```text
exp_write(...)
```

最终会垃圾爆炸。

建议只进入 shared memory 的信息：

```text
稳定事实
高价值 workaround
明确失败结论
用户长期偏好
设备特性
项目约束
```

例如值得：

```text
EVE-AL00:
shell /sys/class/thermal → EPERM
```

不值得：

```text
今天 14:03 点击了设置按钮
```

------

# 三十八、Memory 应该支持去重/合并

例如三个 Agent 都发现：

```text
Huawei thermal shell denied
```

不能产生三条重复记忆。

可按：

```text
scope + subject + normalized content hash
```

去重。

或者：

```text
existing:
confidence +=
last_verified_at =
source_count +=
```

------

# 三十九、Memory 最好允许“失效”

因为手机 ROM 会升级。

例如：

```json
{
  "fact":
    "gpuclk shell denied",

  "device":
    "EVE-AL00",

  "build_fingerprint":
    "Huawei/...",

  "verified_at":
    "...",

  "valid_until_build_change":
    true
}
```

检测到：

```text
Build.FINGERPRINT 改变
```

降低该 memory confidence。

这能避免旧经验害新系统。

------

# 四十、我推荐你的整体架构

最终：

```text
                  ┌────────────────┐
                  │    WeChat UI   │
                  └───────┬────────┘
                          │
                    User / Trigger
                          │
                          ▼
                 ┌─────────────────┐
                 │ Session Manager │
                 └────────┬────────┘
                          │
                          ▼
              ┌──────────────────────┐
              │    ContextManager    │
              │                      │
              │ Agent Profile        │
              │ Task State           │
              │ Device State         │
              │ Recent Messages      │
              │ Checkpoint           │
              │ Memory Retrieval     │
              │ Handoff              │
              └──────────┬───────────┘
                         │
                         ▼
                ┌──────────────────┐
                │  ContextBuilder  │
                │   Token Budget   │
                └─────────┬────────┘
                          │
                          ▼
                    Remote LLM
                          │
                          ▼
                     Tool Call
                          │
              ┌───────────┴────────────┐
              ▼                        ▼
        PhoneController           Local Tools
              │
              ▼
        Device Snapshot
              │
              └────→ Event Store
```

------

# 四十一、我最建议你现在先做的版本

不要一次重构太大。

### V1

保留：

```text
AgentService
LlmClient
SessionStore
```

新增四个类：

```text
ContextManager
TaskState
DeviceSnapshot
SessionCheckpoint
```

把当前：

```text
buildSystemPrompt(...)
+ AGENT_HISTORY
```

替换为：

```text
ContextManager.build(...)
```

------

### V2

增加：

```text
SQLite MemoryStore
MemoryRetriever
HandoffCapsule
```

解决：

> 多 Agent 重复踩坑。

------

### V3

再做：

```text
UI diff
tool output refs
screenshot refs
automatic memory extraction
```

做到这里已经是相当成熟的手机 Agent runtime。

------

# 四十二、如果只允许我改你当前架构的 5 件事

按收益排序，我会选：

1. **把 `AGENT_HISTORY` 改成 `recent messages + rolling summary`。**
2. **增加结构化 `TaskState`，每轮必传。**
3. **增加 `DeviceSnapshot`，把当前前台 App/UI/last action 变成一等上下文。**
4. **Agent 派生时生成 `HandoffCapsule`，不要让子 Agent 空启动。**
5. **ContextManager 自动检索 Top-K memory，`chat_search` 继续保留给模型深挖。**

这五项实现后，“模型看到上下文”的质量会比单纯扩大 history 高一个量级，而且 **token 反而会下降**。

你当前已经具备会话落盘、`AGENT_HISTORY`、`chat_search`、经验池和手机工具这些基础组件，所以不需要推翻重写；真正缺的是位于 `AgentService → LlmClient` 中间的 **ContextManager / ContextBuilder 层**。这应该成为下一次重构的核心。