# 请求（第 2 轮）：为 ClawPhone 手机 Agent 的工具调用设计"错误码 + 提示语"映射表

## 背景

ClawPhone 是运行在安卓手机上的自主 Agent（LLM 每轮输出一行 JSON 动作，App 执行后把结果文本回传给模型）。上一轮你已给出"错误场景 → 提示语"映射表，质量很好。但对照我的系统**实际产生的报错字符串**，发现两处需要修正：

1. **缺少稳定 error_code**：你建议"错误码与提示语分离"，但表格没有给每个场景分配 error_code。我计划在 App 内实现 `error_code`（稳定、机器可读）+ 展示层映射提示语，需要你为每个场景分配唯一 error_code。
2. **部分场景与系统实际报错不对应**：你列了一些我系统当前**不会产生**的报错（如 file_read 文件过大/编码异常、shell 命令超时/非零退出码、tap_text 匹配项不可点击、open_app 应用未安装等）；同时漏了我系统**真实存在**的报错（如"本 Agent 无 X 权限"门禁、exp_record 缺 title/content、shell 通道为 app 级 uid 等）。

## 我系统当前的真实报错字符串（请逐条给出 error_code + 提示语）

### 文件类（App 进程内执行，绝不提 shell）
| 场景 | 系统当前返回 |
|---|---|
| path 缺失/为空 | `路径为空（file_ls 需要 path 参数）` |
| 路径越界 | `路径非法或越界: <path>` |
| 目录不存在 | `目录不存在: <path>（请先 file_ls 上级目录确认路径）` |
| 目录无读取权限 | `无权限读取该目录: <path>（存储权限未授予，可在设置页授权）` |
| 文件不存在 | `文件不存在: <path>（请先 file_ls 确认路径）` |
| 文件读取失败 | `读取失败: <异常>（文件可能被占用或无读取权限）` |
| 父目录不存在 | `父目录不存在: <path>（请先 file_ls 确认目录）` |
| 写入失败 | `写入失败: <异常>（只读/空间不足/无写入权限）` |
| 搜索 pattern 为空 | `pattern 为空（file_search 需要 pattern 参数）` |
| 存储权限未授予 | `无存储权限，无法搜索（设置页授权存储）` |
| 搜索无匹配 | `未找到匹配 "<pattern>" 的文件` |
| 相册权限关闭 | `本 Agent 无相册访问权限` |
| 媒体权限关闭 | `本 Agent 无媒体访问权限` |
| 音乐权限关闭 | `本 Agent 无音乐访问权限` |

### shell 类（需 Shizuku 通道）
| 场景 | 系统当前返回 |
|---|---|
| 本 Agent 未授予 shell 权限 | `本 Agent 无 Shizuku Shell 权限（通讯录→Agent 设置→权限管理）` |
| Shizuku 未运行/未授权 | `shell 通道不可用（Shizuku 未运行/未授权，可在设置页处理）` |
| 命令为空 | `cmd 为空` |
| 危险命令被拒绝 | `拒绝执行（危险命令：重启/卸载/擦除类）` |
| 通道断开 | `shell 执行失败（通道已断开？）` |
| 通道是 app 级 uid | `(注: 当前通道为 app 级 uid <uid>，非 shell，系统命令可能受限)` |

### 无障碍类
| 场景 | 系统当前返回 |
|---|---|
| 无障碍未启用 | `无障碍服务未启用` |
| tap_text 未找到 | `未找到文本: <query>`（近似） |
| tap_idx 越界 | `索引越界`（近似） |
| type 无聚焦框 | `type 失败(无聚焦输入框?)` |
| key 失败 | `key 失败` |
| tap/double_tap/swipe 失败 | `tap 失败` / `double_tap 失败` / `swipe 失败` |
| open_app 失败 | `无法打开: <pkg>` / `打开失败: <异常>` |

### 经验池类（App 内 SQLite）
| 场景 | 系统当前返回 |
|---|---|
| 无写入权限 | `本 Agent 无全局经验池写入权限（通讯录→Agent 设置→权限管理）` |
| 无读取权限 | `本 Agent 无全局经验池读取权限（通讯录→Agent 设置→权限管理）` |
| exp_record 缺 title/content | `exp_record 需要 title 或 content` |
| exp_search 缺 query | `exp_search 需要 query` |
| exp_search 无匹配 | `经验池中没有匹配「<query>」的经验` |
| exp_delete 缺 id/title | `exp_delete 需要 id 或 title（只能删除本 Agent 自己的经验）` |
| exp_delete 找不到自己的 | `未找到本 Agent 可删除的经验（id/title 不匹配或不属于本 Agent）` |

### 通用
| 场景 | 系统当前返回 |
|---|---|
| LLM 输出为空 | `模型输出为空` |
| LLM 输出不含 JSON | `输出不含 JSON 动作` |
| JSON 损坏 | `JSON 损坏或字段缺失` |
| 未知动作 | `未知动作: <type>` |
| 执行异常 | `执行异常: <异常>` |

## 请输出

一张 markdown 表格，列为：`error_code | 错误场景 | 给 LLM 的提示语（≤60字，发生了什么+下一步） | 给用户的友好提示语（可选，显示在对话 ⚠ 里）`。

要求：
1. **error_code 唯一且稳定**，风格如 `FILE_DIR_NOT_FOUND`、`SHELL_CHANNEL_UNAVAILABLE`、`A11Y_NOT_ENABLED`、`EXP_NO_WRITE_PERM`、`LLM_EMPTY_OUTPUT`。
2. **只针对我上面列出的真实场景**；我系统当前不产生的报错（文件过大/编码异常/命令超时/非零退出码/匹配项不可点击/应用未安装等）**不要**列入，除非你建议我新增检测——若建议新增，请单独标注"建议新增"。
3. 文件类错误**绝不提 shell/Shizuku**。
4. 提示语既给 LLM 判断下一步，也会显示给用户看，措辞要自然、不暴露内部实现。
5. 最后给出：`error_code → 提示语` 的 Java 常量/映射实现建议（我打算用 `Map<String,String>` 或 switch 实现）。
