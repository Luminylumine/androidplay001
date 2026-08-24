# ClawPhone Agent 错误码与提示语规范

> 适用范围：ClawPhone 当前真实存在的工具调用错误。  
> 设计目标：`error_code` 稳定、机器可读；提示语帮助 LLM 选择下一步，同时对用户自然可读。  
> 约束：文件类错误不提 shell / Shizuku；主表不包含当前系统不会产生的错误。

## 1. 错误码映射表

| error_code | 错误场景 | 给 LLM 的提示语（≤60字，发生了什么+下一步） | 给用户的友好提示语（可选） |
|---|---|---|---|
| `FILE_PATH_EMPTY` | file_ls / file_read / file_write 的 path 缺失或为空 | 未提供有效路径。请补充明确的文件或目录路径后重试。 | 未提供有效路径，请检查后重试。 |
| `FILE_PATH_OUT_OF_SCOPE` | 文件路径非法或越界 | 该路径不在允许访问范围内。请改用允许范围内的路径。 | 该路径当前不可访问，请选择其他位置。 |
| `FILE_DIR_NOT_FOUND` | file_ls 目录不存在 | 指定目录不存在。请先查看上级目录并确认实际路径。 | 找不到该目录，请检查路径。 |
| `FILE_DIR_READ_DENIED` | file_ls 目录无读取权限 | 当前无法读取该目录。请重新授权存储访问或更换目录。 | 当前没有权限读取该目录。 |
| `FILE_NOT_FOUND` | file_read 文件不存在 | 指定文件不存在。请先列出所在目录并确认文件名。 | 找不到该文件，请检查路径。 |
| `FILE_READ_FAILED` | file_read 文件读取失败 | 文件读取失败。请检查访问权限或文件状态后再试。 | 文件读取失败，请稍后重试。 |
| `FILE_PARENT_NOT_FOUND` | file_write 父目录不存在 | 目标父目录不存在。请先确认或创建父目录后再写入。 | 目标文件夹不存在，请先确认路径。 |
| `FILE_WRITE_FAILED` | file_write 写入失败 | 文件写入失败。请检查目标位置、空间和写入权限后重试。 | 文件写入失败，请检查存储空间或权限。 |
| `FILE_SEARCH_PATTERN_EMPTY` | file_search 的 pattern 为空 | 未提供搜索关键词。请补充 pattern 后重新搜索。 | 请输入要搜索的文件名或关键词。 |
| `FILE_STORAGE_PERMISSION_DENIED` | file_search 无存储权限 | 当前没有所需存储访问权限。请先授权后再搜索。 | 尚未获得存储访问权限，请先授权。 |
| `FILE_SEARCH_NO_MATCH` | file_search 无匹配结果 | 未找到匹配内容。请调整关键词或搜索范围后再试。 | 没有找到匹配的文件。 |
| `FILE_CATEGORY_PHOTO_DENIED` | 相册权限关闭 | 当前 Agent 无法访问相册。请先开启相册访问权限。 | 请先开启相册访问权限。 |
| `FILE_CATEGORY_MEDIA_DENIED` | 媒体权限关闭 | 当前 Agent 无法访问媒体内容。请先开启媒体访问权限。 | 请先开启媒体访问权限。 |
| `FILE_CATEGORY_MUSIC_DENIED` | 音乐权限关闭 | 当前 Agent 无法访问音乐内容。请先开启音乐访问权限。 | 请先开启音乐访问权限。 |
| `SHELL_AGENT_PERMISSION_DENIED` | 本 Agent 未授予 shell 权限 | 当前 Agent 没有 Shell 使用权限。请先在权限管理中授权。 | 当前 Agent 尚未获得 Shell 权限。 |
| `SHELL_CHANNEL_UNAVAILABLE` | Shizuku 未运行或未授权 | Shell 通道当前不可用。请先在设置中完成服务启动或授权。 | Shell 服务不可用，请先检查授权状态。 |
| `SHELL_CMD_EMPTY` | shell 命令为空 | 未提供要执行的命令。请补充有效 cmd 后重试。 | 没有提供可执行命令。 |
| `SHELL_DANGEROUS_COMMAND_REJECTED` | 危险命令被拒绝 | 该操作属于高风险命令，已被拒绝。请改用安全方案。 | 为保护设备，该高风险操作已被拒绝。 |
| `SHELL_CHANNEL_DISCONNECTED` | shell 执行时通道断开 | Shell 通道已断开，命令未完成。请恢复通道后重试。 | Shell 连接已断开，请重新连接。 |
| `SHELL_UID_NOT_SHELL` | 通道是 app 级 UID，非 shell | 当前通道不是 Shell 身份。请避免执行依赖系统权限的命令。 | 当前 Shell 能力受限，部分系统命令可能不可用。 |
| `A11Y_NOT_ENABLED` | 无障碍服务未启用 | 无障碍服务未启用。请先开启服务再执行界面操作。 | 请先开启无障碍服务。 |
| `A11Y_TEXT_NOT_FOUND` | tap_text 未找到目标文本 | 当前页面未找到目标文本。请重新读取页面或调整查询文本。 | 当前页面没有找到目标内容。 |
| `A11Y_INDEX_OUT_OF_RANGE` | tap_idx 索引越界 | 指定控件索引无效。请重新读取页面并使用有效索引。 | 控件索引已失效，请刷新页面信息。 |
| `A11Y_NO_FOCUSED_INPUT` | type 时无聚焦输入框 | 当前没有聚焦的输入框。请先点击目标输入框再输入。 | 请先点选一个输入框。 |
| `A11Y_KEY_FAILED` | key 操作失败 | 按键操作未成功。请确认当前页面状态后再试。 | 按键操作失败，请重试。 |
| `A11Y_TAP_FAILED` | tap 操作失败 | 点击操作未成功。请确认页面稳定并调整坐标后重试。 | 点击失败，请重试。 |
| `A11Y_DOUBLE_TAP_FAILED` | double_tap 操作失败 | 双击操作未成功。请确认页面稳定并调整坐标后重试。 | 双击失败，请重试。 |
| `A11Y_SWIPE_FAILED` | swipe 操作失败 | 滑动操作未成功。请调整起止位置后重新尝试。 | 滑动失败，请重试。 |
| `A11Y_OPEN_APP_FAILED` | open_app 无法打开目标应用 | 目标应用未能打开。请检查包名或改用其他入口。 | 无法打开目标应用。 |
| `A11Y_OPEN_APP_EXCEPTION` | open_app 执行异常 | 打开应用时发生异常。请检查目标应用状态后重新尝试。 | 打开应用失败，请稍后重试。 |
| `EXP_NO_WRITE_PERM` | 经验池无写入权限 | 当前 Agent 无经验池写入权限。请先授权或跳过记录。 | 当前 Agent 没有经验池写入权限。 |
| `EXP_NO_READ_PERM` | 经验池无读取权限 | 当前 Agent 无经验池读取权限。请先授权或继续当前任务。 | 当前 Agent 没有经验池读取权限。 |
| `EXP_RECORD_MISSING_CONTENT` | exp_record 缺 title 或 content | 经验记录缺少 title 或 content。请补充必要内容后重试。 | 经验记录内容不完整。 |
| `EXP_SEARCH_QUERY_EMPTY` | exp_search 缺 query | 未提供经验搜索条件。请补充 query 后重新搜索。 | 请输入经验搜索关键词。 |
| `EXP_SEARCH_NO_MATCH` | exp_search 无匹配 | 经验池中没有匹配记录。请调整关键词或继续当前任务。 | 没有找到匹配的经验。 |
| `EXP_DELETE_TARGET_EMPTY` | exp_delete 缺 id 或 title | 未指定要删除的经验。请提供 id 或 title 后重试。 | 请指定要删除的经验。 |
| `EXP_DELETE_NOT_OWNED_OR_NOT_FOUND` | exp_delete 找不到本 Agent 可删经验 | 未找到可删除的经验。请确认标识且该记录属于当前 Agent。 | 未找到可删除的对应经验。 |
| `LLM_EMPTY_OUTPUT` | LLM 输出为空 | 模型没有返回动作。请重新生成一条完整 JSON 动作。 | 模型未返回有效操作。 |
| `LLM_NO_JSON_ACTION` | LLM 输出不含 JSON | 输出中没有 JSON 动作。请只返回一条合法 JSON 动作。 | 模型返回格式不正确。 |
| `LLM_JSON_INVALID` | JSON 损坏或字段缺失 | JSON 无法解析或字段不完整。请修正格式和必填字段。 | 模型返回的操作格式有误。 |
| `ACTION_UNKNOWN` | 未知动作类型 | 当前动作类型不受支持。请改用已定义的工具动作。 | 收到不支持的操作类型。 |
| `ACTION_EXECUTION_EXCEPTION` | 工具执行异常 | 动作执行时发生异常。请检查当前条件并换用更稳妥的操作。 | 操作执行失败，请稍后重试。 |

## 2. 推荐返回结构

不要只把错误文本直接拼给模型。建议固定返回结构：

```json
{
  "ok": false,
  "error_code": "FILE_DIR_NOT_FOUND",
  "message": "指定目录不存在。请先查看上级目录并确认实际路径。",
  "detail": "/sdcard/example"
}
```

字段建议：

- `ok`：布尔值，供 Agent 快速分支。
- `error_code`：稳定协议字段；升级提示语时不要改它。
- `message`：给 LLM 的短提示。
- `detail`：可选，仅放动态信息，例如路径、包名、异常摘要。
- UI 如需不同文案，可再按 `error_code` 映射 `user_message`。

不要让 `error_code` 包含动态内容，例如：

```text
FILE_NOT_FOUND_/sdcard/a.txt   // 不推荐
```

应始终保持：

```text
FILE_NOT_FOUND
```

动态路径放入 `detail`。

## 3. Java 实现建议

### 3.1 error_code 使用常量

```java
public final class AgentErrorCodes {

    private AgentErrorCodes() {}

    // File
    public static final String FILE_PATH_EMPTY = "FILE_PATH_EMPTY";
    public static final String FILE_PATH_OUT_OF_SCOPE = "FILE_PATH_OUT_OF_SCOPE";
    public static final String FILE_DIR_NOT_FOUND = "FILE_DIR_NOT_FOUND";
    public static final String FILE_DIR_READ_DENIED = "FILE_DIR_READ_DENIED";
    public static final String FILE_NOT_FOUND = "FILE_NOT_FOUND";
    public static final String FILE_READ_FAILED = "FILE_READ_FAILED";
    public static final String FILE_PARENT_NOT_FOUND = "FILE_PARENT_NOT_FOUND";
    public static final String FILE_WRITE_FAILED = "FILE_WRITE_FAILED";
    public static final String FILE_SEARCH_PATTERN_EMPTY = "FILE_SEARCH_PATTERN_EMPTY";
    public static final String FILE_STORAGE_PERMISSION_DENIED = "FILE_STORAGE_PERMISSION_DENIED";
    public static final String FILE_SEARCH_NO_MATCH = "FILE_SEARCH_NO_MATCH";
    public static final String FILE_CATEGORY_PHOTO_DENIED = "FILE_CATEGORY_PHOTO_DENIED";
    public static final String FILE_CATEGORY_MEDIA_DENIED = "FILE_CATEGORY_MEDIA_DENIED";
    public static final String FILE_CATEGORY_MUSIC_DENIED = "FILE_CATEGORY_MUSIC_DENIED";

    // Shell
    public static final String SHELL_AGENT_PERMISSION_DENIED = "SHELL_AGENT_PERMISSION_DENIED";
    public static final String SHELL_CHANNEL_UNAVAILABLE = "SHELL_CHANNEL_UNAVAILABLE";
    public static final String SHELL_CMD_EMPTY = "SHELL_CMD_EMPTY";
    public static final String SHELL_DANGEROUS_COMMAND_REJECTED = "SHELL_DANGEROUS_COMMAND_REJECTED";
    public static final String SHELL_CHANNEL_DISCONNECTED = "SHELL_CHANNEL_DISCONNECTED";
    public static final String SHELL_UID_NOT_SHELL = "SHELL_UID_NOT_SHELL";

    // Accessibility
    public static final String A11Y_NOT_ENABLED = "A11Y_NOT_ENABLED";
    public static final String A11Y_TEXT_NOT_FOUND = "A11Y_TEXT_NOT_FOUND";
    public static final String A11Y_INDEX_OUT_OF_RANGE = "A11Y_INDEX_OUT_OF_RANGE";
    public static final String A11Y_NO_FOCUSED_INPUT = "A11Y_NO_FOCUSED_INPUT";
    public static final String A11Y_KEY_FAILED = "A11Y_KEY_FAILED";
    public static final String A11Y_TAP_FAILED = "A11Y_TAP_FAILED";
    public static final String A11Y_DOUBLE_TAP_FAILED = "A11Y_DOUBLE_TAP_FAILED";
    public static final String A11Y_SWIPE_FAILED = "A11Y_SWIPE_FAILED";
    public static final String A11Y_OPEN_APP_FAILED = "A11Y_OPEN_APP_FAILED";
    public static final String A11Y_OPEN_APP_EXCEPTION = "A11Y_OPEN_APP_EXCEPTION";

    // Experience pool
    public static final String EXP_NO_WRITE_PERM = "EXP_NO_WRITE_PERM";
    public static final String EXP_NO_READ_PERM = "EXP_NO_READ_PERM";
    public static final String EXP_RECORD_MISSING_CONTENT = "EXP_RECORD_MISSING_CONTENT";
    public static final String EXP_SEARCH_QUERY_EMPTY = "EXP_SEARCH_QUERY_EMPTY";
    public static final String EXP_SEARCH_NO_MATCH = "EXP_SEARCH_NO_MATCH";
    public static final String EXP_DELETE_TARGET_EMPTY = "EXP_DELETE_TARGET_EMPTY";
    public static final String EXP_DELETE_NOT_OWNED_OR_NOT_FOUND =
            "EXP_DELETE_NOT_OWNED_OR_NOT_FOUND";

    // LLM / Action
    public static final String LLM_EMPTY_OUTPUT = "LLM_EMPTY_OUTPUT";
    public static final String LLM_NO_JSON_ACTION = "LLM_NO_JSON_ACTION";
    public static final String LLM_JSON_INVALID = "LLM_JSON_INVALID";
    public static final String ACTION_UNKNOWN = "ACTION_UNKNOWN";
    public static final String ACTION_EXECUTION_EXCEPTION = "ACTION_EXECUTION_EXCEPTION";
}
```

### 3.2 用 Map 管理给 LLM 的提示语

如果提示语主要是静态文本，推荐 `Map<String, String>`：

```java
public final class AgentErrorMessages {

    private AgentErrorMessages() {}

    public static final Map<String, String> LLM_MESSAGES = Map.ofEntries(
        Map.entry(AgentErrorCodes.FILE_PATH_EMPTY,
            "未提供有效路径。请补充明确的文件或目录路径后重试。"),
        Map.entry(AgentErrorCodes.FILE_PATH_OUT_OF_SCOPE,
            "该路径不在允许访问范围内。请改用允许范围内的路径。"),
        Map.entry(AgentErrorCodes.FILE_DIR_NOT_FOUND,
            "指定目录不存在。请先查看上级目录并确认实际路径。"),
        Map.entry(AgentErrorCodes.FILE_DIR_READ_DENIED,
            "当前无法读取该目录。请重新授权存储访问或更换目录。"),
        Map.entry(AgentErrorCodes.FILE_NOT_FOUND,
            "指定文件不存在。请先列出所在目录并确认文件名。"),
        Map.entry(AgentErrorCodes.FILE_READ_FAILED,
            "文件读取失败。请检查访问权限或文件状态后再试。"),
        Map.entry(AgentErrorCodes.FILE_PARENT_NOT_FOUND,
            "目标父目录不存在。请先确认或创建父目录后再写入。"),
        Map.entry(AgentErrorCodes.FILE_WRITE_FAILED,
            "文件写入失败。请检查目标位置、空间和写入权限后重试。"),
        Map.entry(AgentErrorCodes.FILE_SEARCH_PATTERN_EMPTY,
            "未提供搜索关键词。请补充 pattern 后重新搜索。"),
        Map.entry(AgentErrorCodes.FILE_STORAGE_PERMISSION_DENIED,
            "当前没有所需存储访问权限。请先授权后再搜索。"),
        Map.entry(AgentErrorCodes.FILE_SEARCH_NO_MATCH,
            "未找到匹配内容。请调整关键词或搜索范围后再试。"),
        Map.entry(AgentErrorCodes.FILE_CATEGORY_PHOTO_DENIED,
            "当前 Agent 无法访问相册。请先开启相册访问权限。"),
        Map.entry(AgentErrorCodes.FILE_CATEGORY_MEDIA_DENIED,
            "当前 Agent 无法访问媒体内容。请先开启媒体访问权限。"),
        Map.entry(AgentErrorCodes.FILE_CATEGORY_MUSIC_DENIED,
            "当前 Agent 无法访问音乐内容。请先开启音乐访问权限。"),

        Map.entry(AgentErrorCodes.SHELL_AGENT_PERMISSION_DENIED,
            "当前 Agent 没有 Shell 使用权限。请先在权限管理中授权。"),
        Map.entry(AgentErrorCodes.SHELL_CHANNEL_UNAVAILABLE,
            "Shell 通道当前不可用。请先在设置中完成服务启动或授权。"),
        Map.entry(AgentErrorCodes.SHELL_CMD_EMPTY,
            "未提供要执行的命令。请补充有效 cmd 后重试。"),
        Map.entry(AgentErrorCodes.SHELL_DANGEROUS_COMMAND_REJECTED,
            "该操作属于高风险命令，已被拒绝。请改用安全方案。"),
        Map.entry(AgentErrorCodes.SHELL_CHANNEL_DISCONNECTED,
            "Shell 通道已断开，命令未完成。请恢复通道后重试。"),
        Map.entry(AgentErrorCodes.SHELL_UID_NOT_SHELL,
            "当前通道不是 Shell 身份。请避免执行依赖系统权限的命令。"),

        Map.entry(AgentErrorCodes.A11Y_NOT_ENABLED,
            "无障碍服务未启用。请先开启服务再执行界面操作。"),
        Map.entry(AgentErrorCodes.A11Y_TEXT_NOT_FOUND,
            "当前页面未找到目标文本。请重新读取页面或调整查询文本。"),
        Map.entry(AgentErrorCodes.A11Y_INDEX_OUT_OF_RANGE,
            "指定控件索引无效。请重新读取页面并使用有效索引。"),
        Map.entry(AgentErrorCodes.A11Y_NO_FOCUSED_INPUT,
            "当前没有聚焦的输入框。请先点击目标输入框再输入。"),
        Map.entry(AgentErrorCodes.A11Y_KEY_FAILED,
            "按键操作未成功。请确认当前页面状态后再试。"),
        Map.entry(AgentErrorCodes.A11Y_TAP_FAILED,
            "点击操作未成功。请确认页面稳定并调整坐标后重试。"),
        Map.entry(AgentErrorCodes.A11Y_DOUBLE_TAP_FAILED,
            "双击操作未成功。请确认页面稳定并调整坐标后重试。"),
        Map.entry(AgentErrorCodes.A11Y_SWIPE_FAILED,
            "滑动操作未成功。请调整起止位置后重新尝试。"),
        Map.entry(AgentErrorCodes.A11Y_OPEN_APP_FAILED,
            "目标应用未能打开。请检查包名或改用其他入口。"),
        Map.entry(AgentErrorCodes.A11Y_OPEN_APP_EXCEPTION,
            "打开应用时发生异常。请检查目标应用状态后重新尝试。"),

        Map.entry(AgentErrorCodes.EXP_NO_WRITE_PERM,
            "当前 Agent 无经验池写入权限。请先授权或跳过记录。"),
        Map.entry(AgentErrorCodes.EXP_NO_READ_PERM,
            "当前 Agent 无经验池读取权限。请先授权或继续当前任务。"),
        Map.entry(AgentErrorCodes.EXP_RECORD_MISSING_CONTENT,
            "经验记录缺少 title 或 content。请补充必要内容后重试。"),
        Map.entry(AgentErrorCodes.EXP_SEARCH_QUERY_EMPTY,
            "未提供经验搜索条件。请补充 query 后重新搜索。"),
        Map.entry(AgentErrorCodes.EXP_SEARCH_NO_MATCH,
            "经验池中没有匹配记录。请调整关键词或继续当前任务。"),
        Map.entry(AgentErrorCodes.EXP_DELETE_TARGET_EMPTY,
            "未指定要删除的经验。请提供 id 或 title 后重试。"),
        Map.entry(AgentErrorCodes.EXP_DELETE_NOT_OWNED_OR_NOT_FOUND,
            "未找到可删除的经验。请确认标识且该记录属于当前 Agent。"),

        Map.entry(AgentErrorCodes.LLM_EMPTY_OUTPUT,
            "模型没有返回动作。请重新生成一条完整 JSON 动作。"),
        Map.entry(AgentErrorCodes.LLM_NO_JSON_ACTION,
            "输出中没有 JSON 动作。请只返回一条合法 JSON 动作。"),
        Map.entry(AgentErrorCodes.LLM_JSON_INVALID,
            "JSON 无法解析或字段不完整。请修正格式和必填字段。"),
        Map.entry(AgentErrorCodes.ACTION_UNKNOWN,
            "当前动作类型不受支持。请改用已定义的工具动作。"),
        Map.entry(AgentErrorCodes.ACTION_EXECUTION_EXCEPTION,
            "动作执行时发生异常。请检查当前条件并换用更稳妥的操作。")
    );

    public static String forLlm(String errorCode) {
        return LLM_MESSAGES.getOrDefault(
            errorCode,
            "操作失败。请检查当前条件并选择其他可行步骤。"
        );
    }
}
```

### 3.3 动态信息不要拼进固定提示模板

例如原始错误：

```text
目录不存在: /sdcard/foo
```

建议内部构造为：

```java
return AgentToolResult.error(
    AgentErrorCodes.FILE_DIR_NOT_FOUND,
    AgentErrorMessages.forLlm(AgentErrorCodes.FILE_DIR_NOT_FOUND),
    "/sdcard/foo"
);
```

这样模型既获得稳定语义，也仍能看到实际路径：

```json
{
  "ok": false,
  "error_code": "FILE_DIR_NOT_FOUND",
  "message": "指定目录不存在。请先查看上级目录并确认实际路径。",
  "detail": "/sdcard/foo"
}
```

## 4. Map 还是 switch

当前场景推荐：

```text
error_code 常量
+
Map<String, String> 文案
```

原因：

- 文案属于数据，不属于控制流。
- 后续增加多语言时可以换成 Android `strings.xml`。
- 修改提示语不会影响业务分支。
- LLM 可直接依据 `error_code` 做确定性策略。

真正需要行为分支时，再使用 `switch`：

```java
switch (errorCode) {
    case AgentErrorCodes.FILE_DIR_NOT_FOUND:
        // Agent 策略：允许建议 file_ls 上级目录
        break;

    case AgentErrorCodes.SHELL_CHANNEL_UNAVAILABLE:
        // Agent 策略：不要继续重试 shell 命令
        break;

    case AgentErrorCodes.EXP_SEARCH_NO_MATCH:
        // 不是系统故障，可继续正常工作
        break;
}
```

不要使用提示语文本做程序判断：

```java
if (message.contains("无权限")) { ... } // 不推荐
```

应始终判断：

```java
if (AgentErrorCodes.FILE_DIR_READ_DENIED.equals(errorCode)) { ... }
```

## 5. 建议新增（不属于当前主表）

以下场景不加入当前协议，只建议以后实现显式检测时再增加：

| 建议 error_code | 建议检测 |
|---|---|
| `FILE_TARGET_NOT_DIRECTORY` | file_ls 指向普通文件时单独区分 |
| `FILE_TARGET_NOT_FILE` | file_read 指向目录时单独区分 |
| `ACTION_PARAM_TYPE_INVALID` | JSON 字段存在但类型错误，如 `x="abc"` |
| `ACTION_PARAM_MISSING` | 通用必填参数缺失，与 JSON 损坏分离 |
| `A11Y_SERVICE_DISCONNECTED` | 无障碍开关已开但服务 Binder/连接实际失效 |
| `EXP_DB_ERROR` | SQLite 自身异常，与权限/无匹配分离 |

这些只有在执行层可以稳定识别时才值得加入。不要为了“错误码齐全”而通过异常字符串猜测分类。

## 6. 设计原则摘要

1. `error_code` 是稳定协议，提示语是可修改展示层。
2. LLM 优先依据 `error_code` 决策，不依赖中文字符串解析。
3. 动态信息放 `detail`，不要改变 `error_code`。
4. “无匹配”与“系统异常”要区分，例如 `EXP_SEARCH_NO_MATCH` 不应触发故障恢复。
5. 文件错误保持文件语义，不引导 Agent 转向 Shell。
6. 只有执行层能可靠判断的情况才分配独立错误码。
