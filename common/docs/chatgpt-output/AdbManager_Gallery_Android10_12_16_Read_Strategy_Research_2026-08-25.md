# AdbManager 相册跨 Android 10 / 12 / 16 读取策略调研与修复建议

> 项目：`projects/scrcpy_enhance/`（.NET 9 / WinForms / adb / scrcpy）  
> 约束：纯 adb、无 root、无 APK、无新增 NuGet 依赖  
> 调研日期：2026-08-25

## 0. 结论先行

你已经定位出的根因成立，而且需要再补一层：**问题不仅是 `content read` 的 provider 权限/调用方身份失败；`adb exec-out` 本身也不能用作“远端命令退出码 + 独立 stderr”的可靠执行通道。**

在当前 ADB 源码中，`exec-out` 连接的是 raw `exec:` service：

- host 端只要成功连接并把远端 FD 复制到本地 stdout，最终就返回 `0`；它**不会把远端子进程 exit code 透传回来**；
- raw 子进程协议没有 stdout/stderr 分帧能力，因此远端 stderr 会被合并进同一数据流；
- 这正好解释了为什么 `content read` 抛出的 `SecurityException` 栈被写成“868 字节图片”，而 host 端 adb 仍然 `exit=0`。

因此推荐最终架构不是简单地把：

```text
exec-out content read
```

替换为：

```text
exec-out cat
```

而是：

> **文件落盘场景：优先 `adb pull <_data> <local.partial>`。**  
> **内存流场景：若必须直接流式读取，可用 `exec-out cat <_data>`，但必须把它视为“无可信远端 exit code、stderr 可能污染 stdout”的二进制通道，并进行严格长度/格式校验。**

`adb pull` 比 `cat` 更适合你当前的“打开 / 下载 / 复制”链路，因为它走 ADB 的 **SYNC 文件传输协议**，不经过远端 shell 的字符串解析，也不会把 `cat:` 或 Java 异常栈写进目标文件；文件名包含空格、中文、`&`、`'` 等字符时也更稳。

### 推荐优先级

```text
MediaStore query（加入 _data）
        │
        ├─ _data 非空
        │    └─ adb pull _data -> local.partial
        │          ├─ 成功 + _size/签名校验通过 -> 原子 rename -> 使用
        │          └─ 失败 -> provider fallback（仅设备 capability 已验证可用时）
        │
        └─ _data 空 / 路径不可访问
             ├─ content-read capability == GOOD
             │    └─ adb shell -T content read --uri ...
             │          + stderr 检查 + 内容校验
             └─ capability == BAD/UNKNOWN 且尝试失败
                  └─ 在“无 root、无 APK”约束下判定该条目不可读取
```

---

# 1. 对现有根因的确认

## 1.1 `content query` 成功、`content read` 失败并不矛盾

AOSP 历史版本的 `content` CLI 对 query 和 read 的调用参数本来就存在差异。

历史 `Content.java` 中：

- `query()` 使用 `resolveCallingPackage()`，shell uid 会映射为 `com.android.shell`；
- 但 `ReadCommand` 的 `provider.openFile(...)` 曾直接把 calling package 传成 `null`；
- 外层 `Command.execute()` 捕获所有异常，只把错误和 stack trace 打到 `System.err`，**没有重新抛出，也没有显式设置非 0 退出码**。

这非常符合你在 Android 10 华为机上观察到的：

```text
content query -> 正常
content read  -> Package ... does not belong to 2000
```

也就是说，你的现象并不能简单归纳成“shell uid 2000 完全没有媒体读取权限”。更准确的描述是：

> **该设备的 `content read` + MediaProvider 组合在调用方 package/attribution 校验阶段失败，而 content CLI 又吞掉了异常。**

较新的 AOSP `content` 实现已经出现使用 `AttributionSource(uid, com.android.shell, ...)` 调用 `openFile()` 的版本，因此 Android 12/16 上的实际表现可能比 Android 10 好；但 OEM 分支、MediaProvider 模块版本和 ROM 改动会导致差异，所以仍然不应把 `content read` 当跨 ROM 的稳定主链路。

## 1.2 `--user 0` 不是权限提升

`content ... --user <id>` 只决定：

- 到哪个 Android user/profile 下取得对应 ContentProvider；
- 它**不会改变执行命令的 Linux uid**；
- 不会把 uid 2000 变成 system/root；
- 也不会修复 calling-package / attribution 不匹配。

因此：

```bash
adb shell content read --user 0 --uri ...
```

不应被当成你当前 SecurityException 的修复方式。

如果设备当前用户不是 0，还应注意：硬编码 `--user 0` 反而可能读错用户的数据。需要 user 语义时，应先获取当前 user，并保证 query/read 使用同一 user。

## 1.3 `pm grant` 也不是合适的产品级修法

不建议把修复建立在：

```text
pm grant ...
appops set ...
```

上。

理由：

1. 真正的调用方是 shell / `com.android.shell`，不是 `com.android.providers.media`；给 provider 包授权方向就错了。
2. AOSP Android 12、Android 16 的 Shell manifest 本身仍声明了 `READ_EXTERNAL_STORAGE`、`WRITE_MEDIA_STORAGE`，并包含 `MANAGE_EXTERNAL_STORAGE`；shell 本来就是平台特权身份之一。
3. 你 Android 10 上的异常更像 `content read` 调用身份/attribution 与 MediaProvider 的兼容问题，不是单纯缺一个 runtime permission。
4. signature/privileged 级权限也不能靠普通 `pm grant` 稳定补齐。
5. 修改系统 appops/permission 会增加副作用、ROM 差异和维护成本，与“程序小、稳定、安全”的目标相反。

所以：**不要尝试把 `content read` 修成主路径；把它降级成 capability-probed fallback。**

---

# 2. `_data` 在 Android 10 / 12 / 16 是否能依赖？

## 2.1 官方语义：Android 11+ 并没有删除 `_data`

Android 当前官方 `MediaStore.MediaColumns.DATA` 文档仍明确将 `_data` 定义为：

> media item on disk 的绝对文件系统路径。

官方同时明确：

- 对**已有媒体**，可以在逻辑中使用 `DATA`；
- 但不能假设文件永远可用，必须处理 I/O 失败；
- Android 11 起，针对 target R+ 的 app，`DATA` 对“创建/修改路径”是 read-only；这并不等于查询结果必然为空；
- Android 11 还明确恢复/支持通过直接文件路径、`File`、`fopen()` 访问共享媒体。

因此针对你查询的经典：

```text
content://media/external/images/media
content://media/external/video/media
```

在本地共享存储上的正常图片/视频，**Android 10 / 12 / 16 上 `_data` 仍然是非常实用的首选读取线索**。

但工程上必须把它定义成：

```text
“通常有效、可直接尝试，但不是永久可达性保证”
```

而不是：

```text
“只要有 _data 就一定能打开”
```

## 2.2 Android 12 / 16 的 shell 文件系统访问为什么通常能工作

AOSP Android 12 和 Android 16 的 `com.android.shell` manifest 都仍包含外部存储相关权限；两者还都显式包含 `MANAGE_EXTERNAL_STORAGE`（注释用于存储测试）。

这与实际开发中：

```bash
adb shell cat /storage/emulated/0/DCIM/...
adb pull /storage/emulated/0/DCIM/... host-file
```

通常可行是一致的。

不过这是 AOSP 基准，不是对所有 OEM ROM 的无条件保证。以下情况仍要按失败处理：

- OEM 改了 SELinux / FUSE / MediaProvider 策略；
- 工作资料/多用户/受管设备限制；
- USB file transfer 被 DevicePolicy/UserRestriction 禁止；
- 文件所在卷已卸载；
- 数据库 `_data` 已陈旧；
- 文件正在移动/删除；
- 云媒体、合成/虚拟条目；
- 真正的 app 私有目录。

## 2.3 `_data` 为空时不要用 `relative_path + display_name` 硬拼

不建议把：

```text
relative_path = DCIM/Camera/
display_name  = IMG_xxx.jpg
```

直接拼成：

```text
/storage/emulated/0/DCIM/Camera/IMG_xxx.jpg
```

作为通用 fallback。

原因包括：

- 可能是 secondary volume / SD 卡；
- `external` 是合并视图；
- 多用户路径可能不是 `/storage/emulated/0`；
- OEM 挂载点可以不同；
- 官方文档也明确不建议用 `RELATIVE_PATH` 自行推导 raw filesystem path。

**正确做法是：查询 `_data`，然后尝试它；为空就进入 fallback，而不是猜路径。**

## 2.4 “应用私有目录媒体”如何处理

需要区分三类：

### A. 正常共享媒体目录

例如：

```text
/storage/emulated/0/DCIM/...
/storage/emulated/0/Pictures/...
/storage/emulated/0/Movies/...
```

这是你的主目标。shell + `adb pull` 通常能读。

### B. `/storage/emulated/.../Android/data/<package>/...`

Android 11+ 对普通 app 的跨 app 访问被严格限制；shell 在 AOSP 上有额外特权，但 OEM/FUSE/设备策略仍可能限制。**不能把这种目录当跨 ROM 保证。**

### C. `/data/user/0/<package>` / `/data/data/<package>`

在 production user build、无 root 条件下，shell 一般不能直接读。

如果某个条目真的只存在于私有目录，而且 provider 又不允许 shell 打开，则在你的约束下没有通用绕过方案。只有特例：

- app 本身 debuggable，可用 `run-as <package>`；或
- root；或
- 安装一个拥有合法 URI 权限/MediaStore API 的 helper APK。

这些都不应进入当前通用相册实现。

---

# 3. Android 10 / 12 / 16 读取策略决策表

| 场景 | Android 10（华为 Mate 30 实测重点） | Android 12 | Android 16 | 推荐动作 |
|---|---|---|---|---|
| MediaStore query | 已实测正常 | 通常正常 | 通常正常 | projection 加 `_data`，保留 `_size` / MIME |
| `_data` 非空且 `/storage/...` 可访问 | 已实测 `exec-out cat` 字节正确 | AOSP shell 通常可读 | AOSP shell 通常可读 | **首选 `adb pull`** |
| `_data` 包含空格/中文/引号等 | `adb pull` 不经远端 shell | 同左 | 同左 | `ProcessStartInfo.ArgumentList` 逐参数传入；不要手工加引号 |
| `_data` 为空 | 可能发生 | 可能发生 | 云/虚拟媒体场景相对更值得防御 | 进入 provider fallback；失败则标记不可读取 |
| `_data` 非空但 pull 权限失败 | OEM/SELinux/私有路径 | OEM/策略/私有路径 | OEM/策略/云/虚拟条目 | 不猜路径；provider fallback 仅 capability 可用时尝试 |
| `content read` 原图 | **本机已证实不可用** | 可能可用，ROM 相关 | 可能可用，ROM 相关 | 只做 fallback；按设备/boot/build 缓存 capability |
| `content read --user 0` | 不提升 uid，不能修根因 | 同左 | 同左 | 仅为选择正确 Android user，不用于提权 |
| `exec-out cat` | 已实测原始路径可读 | 通常可用 | 通常可用 | 适合直接流入内存；**不能信 exit code**；严格校验 |
| `adb shell -T cat ...` | shell_v2 通常存在 | 通常存在 | 通常存在 | 可获得更好的 stderr/远端 exit 语义，但远端 shell quoting 更复杂；非首选文件落盘通道 |
| 图片缩略图 | legacy thumbnail 兼容性最好但仍需探测 | deprecated 但 AOSP 仍兼容 | deprecated，AOSP 仍保留兼容入口 | 优先查询已有 thumbnail `_data` 并 pull；否则原图 fallback |
| 视频封面 | legacy video thumbnails 可尝试 | 同左 | 同左 | 小图存在则 pull；否则默认占位，**不要为封面传完整视频** |

### 最关键的设计原则

不要按：

```text
if AndroidVersion >= 11 ...
```

硬编码能力。

应该按设备做 runtime capability：

```text
CanPullDataPath
CanContentRead
CanReadLegacyImageThumbnail
CanReadLegacyVideoThumbnail
SupportsShellV2（如果你确实要使用 shell -T）
```

因为同一 Android 大版本的 AOSP、EMUI/HarmonyOS、MIUI/HyperOS 对 provider / SELinux / MediaProvider module 的实现可能不同。

---

# 4. 为什么 `adb pull` 比 `exec-out cat` 更适合作为首选

## 4.1 `adb pull` 走的是 SYNC 协议

`adb pull` 属于 ADB File Sync，而不是：

```text
shell -> sh -> cat -> stdout
```

因此它有几个非常适合你项目的特性：

1. **目标数据不会和远端 stderr 混在一起。**
2. remote filename 作为文件同步协议里的路径传递，不靠 shell tokenization。
3. 空格、中文和 shell metacharacter 不需要自己写 POSIX quoting。
4. host adb 能明确知道同步传输失败。
5. 你“打开 / 下载 / 复制”本来就需要一个本地文件，因此不会额外增加架构复杂度。

推荐命令：

```bash
adb -s <serial> pull <remote-_data-path> <local-guid>.partial
```

.NET：

```csharp
psi.ArgumentList.Add("-s");
psi.ArgumentList.Add(deviceId);
psi.ArgumentList.Add("pull");
psi.ArgumentList.Add(item.DataPath);     // 不要自己套引号
psi.ArgumentList.Add(localPartialPath);  // 不要自己套引号
```

传完后：

```text
.partial
  -> size 校验
  -> image/container signature 校验
  -> 可选 decode 校验
  -> 原子 rename 为最终扩展名
```

失败时直接删除 `.partial`。

## 4.2 `exec-out cat` 仍有价值，但定位应改变

`exec-out cat` 的优点：

- 二进制 stdout 不经过文本编码；
- 不落设备临时文件；
- 可直接流进 `MemoryStream`；
- 在 ADB 当前实现中，`exec-out` 对**第 2 个及以后的 remote argv** 会调用 `escape_arg()`；因此：

```text
exec-out cat <path>
```

里的 `<path>` 会由 adb 自己做 POSIX shell-safe escaping。

所以如果你用：

```csharp
ArgumentList.Add("exec-out");
ArgumentList.Add("cat");
ArgumentList.Add(dataPath);
```

**不要再人为在 `dataPath` 两边加 `'` 或 `"`**。否则容易变成双重 quoting 或把引号当成路径的一部分。

对于：

- 空格；
- 中文；
- `&`、`;`、`$`；
- 单引号；

这比自己拼 remote shell string 更安全。

但是它有一个结构性缺点：

> **`exec-out` host 返回码不能代表 `cat` 的远端退出码，并且 raw mode 把远端 stderr 合并到 stdout。**

因此，如果：

```text
cat: /path: Permission denied
```

发生，理论上你仍可能把这段文本收到“图片 byte stream”里。

所以 `exec-out cat` 只适合在你同时有强校验条件时使用。

## 4.3 如果你特别需要“stderr 分离 + 远端 exit code”

现代 ADB 的 shell protocol v2 支持：

- raw/non-PTY binary output；
- stdout/stderr 分离；
- 远端 exit code 传回 host。

命令形式：

```bash
adb -s <serial> shell -T <remote-command>
```

但注意一个容易踩的新坑：**`adb shell` 与 `exec-out` 的参数 escaping 行为不同。**

ADB 源码明确写着 `adb shell` 会像 ssh 一样，把后续 host argv 直接用空格 join 成一个 remote command，并不会自动为每个参数做 shell escaping。

因此，若要写通用：

```text
adb shell -T cat <arbitrary path>
```

你必须自己实现严格的 POSIX shell quote。

这反而比 `adb pull` 复杂。

所以本项目建议：

- 文件输出：`adb pull`；
- 直接内存流：保留 `exec-out cat` + 强校验；
- `shell -T` 更适合 provider fallback / 调试，而不是成为主文件传输层。

---

# 5. 建议的实际读取状态机

## 5.1 元数据 projection

建议至少改为：

```csharp
private static readonly string[] ImageCols = {
    "_id", "_display_name", "mime_type", "_size", "width", "height",
    "date_added", "date_modified", "datetaken",
    "bucket_id", "bucket_display_name", "relative_path",
    "_data"
};
```

视频同理加入 `_data`。

不必为了读取主链路一次加入太多高级列，以免 OEM projection 兼容性下降。

如果以后需要多卷诊断，可以把 `volume_name` 作为第二阶段可选信息；但主读取使用完整 `_data` 即可，不要自行根据 volume 拼路径。

## 5.2 `ReadToTempFileAsync()`

推荐逻辑：

```text
1. 创建 GUID.partial
2. item.DataPath 非空：
      try adb pull
      if pull exit == 0 && Validate == true:
          return finalized file
3. 如果本设备 ContentReadCapability == Unknown：
      对当前条目做一次 capability probe
4. 如果 ContentReadCapability == Good：
      尝试 provider read
      同样严格 Validate
5. 全失败：抛“媒体文件不可读取”，而不是返回任何 byte[] / 文件
```

### provider fallback 建议

与其继续：

```text
exec-out content read
```

更推荐在设备支持 shell_v2 时使用非 PTY shell：

```bash
adb -s <serial> shell -T content read --uri content://media/external/images/media/173
```

原因：

- remote stderr 可以由 shell protocol 与 stdout 分开；
- host 能获得 remote shell exit code；
- 即使 `content` 自己吞异常、仍返回 0，你至少能检测到它打印出的 `Error while accessing provider`/stack trace 在 stderr。

但**仍必须进行内容校验**，因为 `content` 自己捕获异常意味着 exit code 依然可能是 0。

对于普通媒体 URI（不含 `&` 等 shell 运算符）命令较简单；若以后 thumbnail URI 带 query string，则必须正确 quote remote URI。

## 5.3 缩略图内存流

可以有两条路径：

### 路径 A：小 thumbnail 文件 `_data` -> pull/stream

最省 ADB 带宽。

### 路径 B：原始 `_data` -> `exec-out cat` -> MemoryStream

简单但要传完整原图。

如果为了代码统一，也可以所有读取都 `pull` 到 PC cache，再从本地解码；这样架构最简单，但滚动大量照片时磁盘 I/O/临时文件较多。

一个折中方案：

```text
thumbnail: 优先 legacy thumbnail；否则 exec-out cat 原图到内存（有尺寸/签名校验）
open/download/copy: 一律 adb pull 到 partial
```

---

# 6. 结果校验：不要再信任何单一 exit code

## 6.1 强烈推荐的校验层级

### Level 1：传输通道自身结果

对于 `adb pull`：

- host adb ExitCode 必须为 0；
- 本地 `.partial` 必须存在；
- 长度必须 > 0。

对于 `exec-out`：

- host exit code 只能说明 adb transport 大体成功，**不能证明 remote command 成功**；
- 不要把它当强校验。

### Level 2：MediaStore `_size`

如果 `_size > 0`：

```text
localLength == mediaStoreSize
```

应作为非常强的成功条件。

但要处理文件在传输期间被拍照 app / gallery app 修改、移动、删除的竞态。

推荐 mismatch 时：

```text
重新 query 当前 item 的 _data + _size 一次
    ├─ 元数据变了 -> 用新值重试一次
    └─ 元数据没变 -> 判传输失败
```

不要无限重试。

### Level 3：文件签名 / container magic

图片应继续保留你现在的 magic sniffing。

至少支持：

- JPEG
- PNG
- GIF
- BMP
- TIFF
- WebP
- HEIF / HEIC

注意 HEIF/HEIC/AVIF 都属于 ISO-BMFF 家族；不要只认单一 `heic` brand。

### Level 4：解码验证（只在合适场景使用）

缩略图：

```text
Bitmap decode 失败 -> thumbnail 失败
```

非常合理。

但“下载/复制原文件”不能把 `System.Drawing.Bitmap` 能否解码当最终真值，因为：

- Windows 可能没有 HEIC codec；
- 某些合法格式 System.Drawing 不支持；
- 视频更不适用。

所以下载/复制更适合：

```text
_size equality + container signature
```

而不是强制 Bitmap decode。

## 6.2 错误文本检测应作为诊断，不应作为主校验

可以快速识别：

```text
Error while accessing provider:
java.lang.SecurityException
java.io.FileNotFoundException
cat:
Permission denied
No such file or directory
```

但是不要把：

```text
“小于 1 KB”
```

本身定义为失败，因为合法的 tiny GIF/PNG 确实存在。

正确顺序是：

```text
_size / signature / decode 是真值
known error text 只是帮助生成更好的错误原因
```

---

# 7. `exec-out` 的通用陷阱：为什么本例 stderr 似乎“消失”

这是本次最值得写进 AdbHelper 通用层的结论。

ADB raw subprocess 的实现中：

```text
Raw + no shell protocol:
stdout ----------┐
                 ├─ same fd -> host stdout
stderr ----------┘
remote exit code ---- X（不经 shell protocol 回传）
```

而 `adb exec-out` 正是这种 raw `exec:` 路径。

因此：

- Java 的 `System.err` 并不一定会进入你 C# 的 `RedirectStandardError`；
- 它可能已经在设备侧被合并进 exec-out 的 stdout binary stream；
- host `adb` 自己的错误（比如设备断线）才可能出现在 host stderr；
- `Process.ExitCode == 0` 也不能证明 `content` / `cat` 成功。

### 建议在 AdbHelper 明确区分三种 API

```text
RunShellCommandAsync()
    目标：文本命令
    通道：adb shell -T / shell protocol
    返回：stdout + stderr + exit code

StreamExecOutAsync()
    目标：二进制、低开销 streaming
    通道：adb exec-out
    契约：远端 stderr 可能污染 stdout；远端 exit code 不可信

PullFileAsync()
    目标：远端文件 -> 本地文件
    通道：adb pull / SYNC
    契约：文件内容与诊断分离；完成后必须做文件级校验
```

**不要再让一个“RunAdbAsync”抽象掩盖这三者完全不同的语义。**

---

# 8. 图片缩略图：纯 adb 下的最优方案

## 8.1 官方现代 API 很好，但纯 shell 无法干净调用

Android API 29 起，`MediaStore.Images.Thumbnails` / `Video.Thumbnails` 已 deprecated；官方推荐：

```java
ContentResolver.loadThumbnail(uri, size, signal)
```

`loadThumbnail()` 底层会通过 typed asset + `EXTRA_SIZE` 让 MediaProvider 生成合适尺寸缩略图。

问题是：标准 `content` CLI 的 `read` 只做普通 `openFile()`，没有一个稳定 CLI 参数可以等价表达：

```text
openTypedAssetFile(image/*, Bundle{ EXTRA_SIZE = ... })
```

因此，在“不安装 helper APK”的约束下，没有官方、稳定、可指定尺寸的 shell `loadThumbnail()` 前端。

## 8.2 AOSP 仍保留 legacy thumbnail 兼容入口

当前 AOSP MediaProvider 仍匹配：

```text
*/images/media/#/thumbnail
*/images/thumbnails
*/images/thumbnails/#
*/video/media/#/thumbnail
*/video/thumbnails
*/video/thumbnails/#
```

而且 `openFile()` 遇到：

```text
images/media/<id>/thumbnail
video/media/<id>/thumbnail
```

会转到 `ensureThumbnail()`。

更重要的是，当前 AOSP 对 legacy thumbnails table 的：

```text
WHERE image_id=<id>
WHERE video_id=<id>
```

仍有兼容逻辑，可返回 thumbnail 对应 `_data` 路径。

这给纯 adb 留了一个**非常值得尝试的可选优化**。

## 8.3 推荐图片 thumbnail 流程

### Step 1：查询 legacy thumbnail `_data`

概念命令：

```bash
adb shell content query \
  --uri content://media/external/images/thumbnails \
  --projection _id:_data:image_id:kind \
  --where "image_id=173"
```

如果返回 `_data`：

```text
thumbnailPath = ...
```

则：

```bash
adb pull <thumbnailPath> <cache>.partial
```

然后按 JPEG/图片签名和 decode 校验。

### Step 2：如果没有现成 thumbnail

可以把：

```text
content://media/external/images/media/<id>/thumbnail
```

作为**capability probe**尝试读取；当前 AOSP 支持这个 URI 并可触发 `ensureThumbnail()`。

但你的 Mate 30 已证实 `content read` 的 provider openFile 有 calling identity 问题，所以在该机上它大概率仍会遭遇同类失败。

因此此入口应满足：

```text
仅当 ContentThumbnailCapability == Good 时启用
```

不要每张照片都失败一次。

### Step 3：最后 fallback

拉原图，再在 Windows 端缩放：

```text
_data -> exec-out cat / pull -> Bitmap -> resize
```

优先选择哪个取决于你的 cache 策略：

- 想避免磁盘临时文件：`exec-out cat` + `_size` + image signature；
- 想统一可靠性：`adb pull` 到 PC cache。

## 8.4 不建议依赖固定 `.thumbnails` 路径

不要硬编码：

```text
/storage/emulated/0/.../.thumbnails/<id>.jpg
```

AOSP/OEM/MediaProvider 版本都可能改变 cache 实际位置。

**查询 thumbnail table 返回的 `_data`，再 pull 它**，比猜路径可靠得多。

---

# 9. 视频首帧封面：最省带宽的实际选择

纯 adb、无 APK、无 root、无 ffmpeg/第三方库时，建议优先级：

```text
1. query Video.Thumbnails / video thumbnail _data
2. pull 小 JPEG
3. 可选尝试 content://media/.../video/media/<id>/thumbnail（仅 capability 通过）
4. 仍失败 -> 显示视频占位图
```

**不建议仅为了列表封面把整个视频传回 PC。**

原因很直接：

- 一张 100~300 KB thumbnail 与数百 MB / 数 GB 视频不是一个量级；
- Android shell 没有稳定、跨 OEM、标准化的“MediaMetadataRetriever 输出一帧到 stdout”CLI；
- `screencap` 与媒体文件本身无关；
- `dumpsys` 不提供媒体帧数据；
- `toybox` 也不是视频解码器。

如果用户已经因为“打开/下载”把视频完整传到 PC，本地以后可以另做 Windows Shell thumbnail / decoder；但那不属于“列表滚动时省 adb 带宽”的方案。

所以在当前约束下：

> **视频没有可读取的小 thumbnail 时，使用占位图是正确的产品选择，不是技术退化。**

---

# 10. `_data` 为空或 pull 失败时的最终处理

建议区分错误原因，不要统一显示“格式不支持”。

例如：

```text
MediaReadError.ProviderDenied
MediaReadError.FilePathMissing
MediaReadError.FilePermissionDenied
MediaReadError.TransferTruncated
MediaReadError.MetadataSizeMismatch
MediaReadError.InvalidImageSignature
MediaReadError.DecodeFailed
MediaReadError.DeviceDisconnected
```

用户可见消息可以简化为：

```text
无法从设备读取此照片。
设备返回的媒体记录存在，但原始文件路径不可访问。
```

对于你当前 Mate 30 的 provider case：

```text
无法通过 Android MediaProvider 读取；已尝试直接文件路径。
```

这样以后排障不会再次把权限错误误判成“图片格式坏了”。

如果：

```text
_data == null/empty
AND content-read capability == Bad
```

则在“无 root / 无 APK”约束下应直接结束：

```text
metadata-only item / unavailable item
```

不要继续构造猜测路径，也不要无限尝试不同 shell 命令。

---

# 11. 建议的 capability cache

每台设备首次打开相册时不需要执行大量探测；可以 lazy probe。

建议缓存键至少包含：

```text
adb serial
ro.build.fingerprint
当前 boot id（可选）
Android user id（若支持多用户）
```

能力：

```csharp
enum CapabilityState { Unknown, Good, Bad }

ContentReadOriginal
ContentReadThumbnail
LegacyImageThumbnailPath
LegacyVideoThumbnailPath
```

### 负缓存非常重要

Mate 30 第一次：

```text
content read -> SecurityException
```

后立即：

```text
ContentReadOriginal = Bad
```

本 session 里剩下 3000 张照片都不再走 `content read`。

否则你会：

- 浪费大量 adb round-trip；
- 每张图都生成 Java stack trace；
- 增加滚动卡顿；
- 日志噪声极大。

---

# 12. 对你原始三点修法的评估

你的方案：

1. projection 加 `_data`；
2. 首选 `exec-out cat <_data>`；
3. `_size` + magic/decode 校验。

结论：**方向正确，但第 2 点建议升级。**

### 推荐改成

1. **projection 加 `_data`：同意。**
2. **打开/下载/复制：首选 `adb pull <_data>`；缩略图需要纯内存时才用 `exec-out cat <_data>`。**
3. **强制校验：同意，而且 `_size` equality 应优先于“小文件/错误文本 heuristic”。**
4. **`content read` 降级为 capability-probed fallback，不要作为稳定第二优先级无条件执行。**
5. **thumbnail 单独走 legacy thumbnail optimization，避免正常滚动时全量读取原图。**
6. **视频 thumbnail 不可用时用占位图，不传完整视频。**

---

# 13. 最小改造版本建议

如果当前阶段以“程序小 + 改动少 + 先修 bug”为最高目标，可以只做下面 6 件事：

### A. `GalleryRowParser`

加入：

```text
_data
```

并保存到 GalleryItem。

### B. 新增 `AdbHelper.PullFileAsync`

唯一职责：

```text
adb -s serial pull remote local.partial
```

捕获 host stdout/stderr，仅用于日志；文件内容永远只从 `local.partial` 读取。

### C. `ReadToTempFileAsync`

改成：

```text
_data != empty
    -> PullFileAsync
    -> ValidateExactFile
    -> 成功返回

否则/失败
    -> ContentReadFallback（可选）
```

### D. `.partial` 原子提交

```text
GUID.partial
   -> validate
   -> GUID.jpg / .png / .heic ...
```

校验不通过立即删除。

### E. 当前 Mate 30 禁止再次使用 content-read

只要第一次看到：

```text
Error while accessing provider:media
SecurityException
```

本 session 直接 negative-cache。

### F. 缩略图先不做复杂 provider optimization 也可以

第一阶段甚至可以：

```text
thumbnail = _data -> exec-out cat -> Bitmap
```

先把正确性修好。

第二阶段再加入：

```text
Images.Thumbnails / Video.Thumbnails -> _data -> pull
```

这样风险最小。

---

# 14. 三台真机建议测试矩阵

| 测试 | Android 10 Mate 30 | Android 12 华为 P | Android 16 Redmi | 通过条件 |
|---|---:|---:|---:|---|
| query `_data` 普通相机 JPEG | 必测 | 必测 | 必测 | 非空或进入明确 fallback |
| `adb pull _data` | 必测 | 必测 | 必测 | host size == `_size` |
| 路径含空格 | 必测 | 必测 | 必测 | pull 完整 |
| 路径含中文 | 必测 | 必测 | 必测 | pull 完整 |
| 路径含 `'` / `&` | 建议 | 建议 | 建议 | pull 完整；exec-out cat 也不注入/截断 |
| 10 KB 以下真实小 PNG/GIF | 建议 | 建议 | 建议 | 不因“小文件”误判 |
| 文件传输期间删除 | 建议 | 建议 | 建议 | 不产出最终文件；错误明确 |
| MediaStore `_size` 陈旧/变化 | 建议 | 建议 | 建议 | re-query + 最多一次 retry |
| `content read` capability | 已知 Bad | 探测 | 探测 | Good/Bad 都能正确缓存，不污染文件 |
| image legacy thumbnail query | 探测 | 探测 | 探测 | 有小图则 pull，没有则 fallback |
| video legacy thumbnail query | 探测 | 探测 | 探测 | 有小图则展示，没有则占位 |
| USB adb | 必测 | 必测 | 必测 | 行为一致 |
| TCP adb | 建议 | 建议 | 建议 | 断连时不留下“成功文件” |

---

# 15. 推荐的最终优先级（可直接交给开发 Agent）

## 图片原文件：打开 / 下载 / 复制

```text
1. MediaStore _data
2. adb pull _data -> .partial
3. ExitCode + file exists
4. _size equality
5. magic/container validation
6. atomic rename
7. pull 失败且 content-read capability Good -> provider fallback
8. provider fallback 仍失败 -> 显示“设备媒体文件不可读取”
```

## 图片缩略图

```text
1. 查 legacy Images.Thumbnails 的 image_id=<id> -> _data
2. thumbnail _data 可 pull -> pull 小文件 + decode
3. 可选：provider /images/media/<id>/thumbnail（仅 capability Good）
4. fallback：原始 _data -> exec-out cat / pull -> PC decode/resize
```

## 视频封面

```text
1. 查 legacy Video.Thumbnails 的 video_id=<id> -> _data
2. pull 小 JPEG
3. 可选：provider /video/media/<id>/thumbnail（仅 capability Good）
4. 失败 -> 占位图
5. 不为列表封面传完整视频
```

---

# 16. 最终判断

### 你的根因判断

**确认。** 不是图片格式问题，而是错误文本被当二进制文件保存。

### `_data` 是否值得加入

**非常值得，而且是当前约束下最重要的修复点。** Android 11+ 并未禁止读取已有媒体的 `_data`；官方仍明确说可使用其有效文件路径，但要处理 I/O 失败。

### `exec-out cat` 是否可做首选

**可用，但建议只做“内存 streaming 首选”，不要做“所有场景首选”。**

对已有本地临时文件需求的打开/下载/复制，**`adb pull` 更优**：SYNC 协议、无 shell quoting、无 stderr 污染目标文件、失败语义更清晰。

### Android 11+ `content read` 有没有标准修复

**没有一个值得依赖的 `--user` / `pm grant` 通用修复。**

不同 Android / OEM 的 `content` CLI 调用身份实现有变化。它可以在某些 Android 12/16 ROM 上正常工作，但正确的产品设计是 runtime probe，而不是强行授权或假定它可用。

### 缩略图最推荐办法

纯 adb 下：

> **legacy thumbnail table / thumbnail `_data` 是最有价值的 best-effort 优化；拿不到时才拉原图。**

现代 `loadThumbnail()` 很好，但没有一个同等稳定的标准 shell CLI。

### 视频封面

> **有系统 thumbnail 就取小 JPEG；没有就占位。**

这是当前“无 APK、无 root、无视频解码依赖”条件下的最佳带宽策略。

---

# 参考资料

1. Android Developers — `MediaStore.MediaColumns.DATA`：绝对文件路径；已有媒体可进行文件操作，但必须处理 I/O 失败；Android 11+ 对 target R+ 的写入变为 read-only。  
   https://developer.android.com/reference/kotlin/android/provider/MediaStore.MediaColumns

2. Android Developers — Access media files from shared storage：已有媒体可使用 `DATA`；direct sequential file access 与 MediaStore 性能可比。  
   https://developer.android.com/training/data-storage/shared/media

3. Android Developers — Android 11 storage updates：允许通过 direct file paths / `File` / `fopen()` 访问 shared media；同时加强 app-private directory 隔离。  
   https://developer.android.com/about/versions/11/privacy/storage

4. AOSP `cmds/content/Content.java`（历史实现）：`Command.execute()` 捕获异常仅打印 stderr；`ReadCommand` 曾向 `openFile()` 传 null calling package，而 query 使用 `resolveCallingPackage()`。  
   https://android.googlesource.com/platform/frameworks/base/+/792926a/cmds/content/src/com/android/commands/content/Content.java

5. AOSP 较新 `Content.java`（AttributionSource 实现）：read 路径已经存在用 shell uid / calling package attribution 调用 provider 的版本，说明行为会随平台/ROM 演化。  
   https://android.googlesource.com/platform/frameworks/base/+/84d7b63b6672/cmds/content/src/com/android/commands/content/Content.java

6. AOSP ADB `client/commandline.cpp`：`exec-out` 构造 `exec:`，后续参数经 `escape_arg()`；连接成功后复制 FD 到 stdout 并返回 0。  
   https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/commandline.cpp

7. AOSP ADB shell service：raw/no-protocol 无 stdout/stderr 分离和远端 exit code；shell protocol 可以分离 stderr 并返回 exit code。  
   https://android.googlesource.com/platform/system/core/+/5f8790f8ab861d06b26f5906dd70f40d294a9a13/adb/daemon/shell_service.cpp

8. AOSP ADB Internals：ADB file sync / service 架构。  
   https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/internals.md

9. AOSP Android 12 Shell manifest：包含 `READ_EXTERNAL_STORAGE` / `WRITE_MEDIA_STORAGE` / `MANAGE_EXTERNAL_STORAGE`。  
   https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-mainline-12.0.0_r6/packages/Shell/AndroidManifest.xml

10. AOSP Android 16 Shell manifest：仍包含上述 shell storage 权限。  
    https://android.googlesource.com/platform/frameworks/base/+/android16-release/packages/Shell/AndroidManifest.xml

11. Android Developers — `MediaStore.Images.Thumbnails`（API 29 deprecated，推荐 `ContentResolver.loadThumbnail()`）。  
    https://developer.android.com/reference/android/provider/MediaStore.Images.Thumbnails

12. Android Developers — `MediaStore.Video.Thumbnails`（API 29 deprecated）。  
    https://developer.android.com/reference/android/provider/MediaStore.Video.Thumbnails.html

13. 当前 AOSP MediaProvider：仍保留 image/video legacy thumbnail URI、thumbnail table，并对 `/media/<id>/thumbnail` 调用 `ensureThumbnail()`；legacy table 对 `image_id=<id>` / `video_id=<id>` 仍有兼容路径。  
    https://android.googlesource.com/platform/packages/providers/MediaProvider/+/cc72a7edde71d1dae05aafb31dc922cbfa1b6c82/src/com/android/providers/media/MediaProvider.java

---

## 附：一句话实现建议

如果只允许选一个最稳、最小改动的修复：

> **给 MediaStore projection 加 `_data`；打开/下载/复制统一改成 `adb pull <_data> <GUID.partial>`，随后做 `_size` + magic 校验并原子提交；`content read` 仅做按设备探测的 fallback；缩略图先保证正确，再用 legacy thumbnail `_data` 做带宽优化。**
