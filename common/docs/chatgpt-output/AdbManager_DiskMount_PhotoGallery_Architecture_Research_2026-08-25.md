# AdbManager：虚拟磁盘挂载 + 相册预览架构调研与落地方案

> 调研日期：2026-08-25  
> 目标项目：AdbManager / C# WinForms / .NET 9 (`net9.0-windows`)  
> 目标设备：Android 10 / 12 / 16；USB + ADB over TCP  
> 目标原则：**程序小、实现简单、安全不崩溃、可扩展、可开源、尽量减少 adb 往返次数**  
> 本文基于你提供的 `AdbManager_DiskMount_PhotoGallery_Research.md` 需求，并结合 WinFsp、Microsoft、Android/AOSP、SMBLibrary、NuGet 等官方/上游资料核验。  
>
> **许可说明**：本文是工程选型分析，不构成法律意见。正式发布前仍建议对最终 LICENSE/NOTICE 和依赖分发方式做一次人工合规复核。

---

## 1. 结论先行

### 1.1 磁盘挂载：明确推荐 WinFsp

**推荐架构：**

```text
Windows Explorer
    │
    ▼
WinFsp（本地盘符）
    │  C# FileSystemBase 回调
    ▼
AdbVirtualFileSystem
    │
    ├── DirectorySnapshotCache   目录快照 / inode 元数据缓存
    ├── LocalReadCache           首次读时整文件拉取，本地随机读
    ├── LocalWriteStaging        随机写先落本地，Flush/Close 再提交
    └── PerDeviceIoScheduler     每设备限并发
             │
             ▼
        adb / adb server
             │
      USB 或 TCP，完全统一
             ▼
         /sdcard
```

四个候选方案的优先级：

| 方案 | Explorer 形态 | USB+TCP | 用户额外安装 | 实现复杂度 | 许可/运维 | 结论 |
|---|---|---:|---:|---:|---|---|
| **WinFsp + C#** | **真正本地盘符** | ✅ | 需装 WinFsp runtime | 中 | GPLv3 + FLOSS 特例 | **首选** |
| 本地 WebDAV | 网络驱动器 | ✅ | 通常无需第三方驱动，但依赖 Windows WebClient 配置 | 中高 | 自写 server 无第三方许可问题 | 仅备选/原型 |
| 本地 SMB server | 网络驱动器 | ✅ | 无驱动，但 445 端口/系统 SMB 服务冲突 | 高 | SMBLibrary LGPL-3.0 | **不推荐** |
| USB MTP | “便携设备”，通常非盘符 | ❌ TCP | 无 | 低 | OS 自带 | 仅对照，不满足统一目标 |

**为什么不是 WebDAV：** Windows WebDAV Redirector 本身就有 `WebClient` 服务、Basic Auth/HTTP 限制、默认 50 MB 单文件上限、集合属性大小上限、超时与大小写语义等坑。为了“不安装驱动”而手写一个兼容 Explorer 的 WebDAV server，最终复杂度并不比 WinFsp 小，而且得到的仍只是网络盘。

**为什么不是 SMB：** SMBLibrary 确实存在，而且是 C# 的 SMB1/2/3 server + client，但 Windows 本机已经占用/管理 SMB 的 445 端口；本地 SMB server 往往需要停用或绕开系统 Server/LanmanServer，部署体验和权限要求都比 WinFsp 更差。

### 1.2 相册：明确推荐 MediaStore 元数据 + 可见范围懒加载 + 磁盘缩略图缓存

推荐：

```text
打开 PhotoGalleryForm
    │
    ├─ 一次 MediaStore images query
    ├─ 一次 MediaStore videos query（可配置是否显示）
    │
    ▼
快速得到几百/几千条元数据
    │
    ├─ BUCKET_ID / BUCKET_DISPLAY_NAME 构建图册
    ├─ 客户端按日期排序
    └─ 立即显示占位网格
           │
           ▼
可见项 + 前后 1 屏预取
           │
           ├─ 命中缩略图磁盘缓存 → 立即显示
           └─ 未命中 → 低并发读取媒体内容 → PC 端生成 thumbnail
                                    │
                                    └─ 只保留小缩略图；临时原图可删除

双击
    └─ 确保完整原图缓存 → Windows Shell 打开
```

**重要限制：**“方案 B”并不意味着现代 Android 可以只通过 `adb shell content query` 直接拿到缩略图字节。Android 10（API 29）开始，旧 `MediaStore.*.Thumbnails` 路径已经弃用，官方推荐 Android App 调用 `ContentResolver.loadThumbnail()`。纯 PC + adb、且不安装辅助 APK 时，没有一个跨 Android 10/12/16 都可靠的“只取小缩略图”官方 ADB 接口。

因此第一版应把目标定义为：

> **不下载整个相册，只下载当前可见的、且尚未缓存缩略图的媒体。**

这已经比“首次全量拉取 3–15 MB 原图 × N”轻得多。

---

# 2. 与现有 AdbManager 的衔接

你当前已有的能力非常适合复用：

- `GetDevicesAsync()`：USB/TCP 都归一成 `DeviceInfo.Id`。
- `PullFileAsync` / `PushFileAsync`：可直接用于挂载层的缓存/提交。
- `ShellExecAsync`：可用于目录操作原型。
- `FileTransferForm`：应继续保留，作为 WinFsp 未安装、挂载失败、设备行为异常时的兜底。
- `MainForm`：在设备右键菜单新增“挂载为磁盘 / 卸载磁盘”，并保留“文件传输”。
- 相册建议新建独立 `PhotoGalleryForm`，不要把现有 `FileTransferForm` 强行改成两套模式。

但在做这两个功能前，**建议先补一层新的 ADB 进程执行 API**，原因有两个：

1. 你现有 `RunCommandAsync(cmd)` 按首个空格拆 `fileName + args`，复杂文件名/引号非常容易出错。
2. 相册的 `content read` 是**二进制 stdout**，不能走“读成字符串再返回”的接口。

建议保留旧接口兼容已有代码，新增结构化参数接口；不要一次性大改所有旧代码。

---

# 3. 功能一：WinFsp 磁盘挂载

## 3.1 WinFsp 许可到底是什么

### 结论

WinFsp 的主许可证是 **GPLv3**，但上游专门提供了一个 **Free/Libre and Open Source Software special exception**。

这个特例明确允许满足 FLOSS 条件的软件：

- 链接 WinFsp 平台 DLL；
- 使用 WinFsp 的 .NET 层；
- 分发**官方未修改**的 WinFsp installer；
- **而不因为这种链接本身被强制要求将你的整个项目改成 GPLv3**。

但它不是“等于 MIT/LGPL”。使用该特例仍有条件，核心包括：

- 你的软件本身必须符合 Free Software Definition 或 Open Source Definition；
- 按 WinFsp 特例要求，在用户可见 UI/文档中保留其版权说明和项目链接；
- 不应把这条 FLOSS 特例错误地用于和闭源专有组件组合的发行物；
- 如果将来项目闭源/商业专有化，应重新审查是否需要 WinFsp commercial license；
- 若直接修改/再分发 WinFsp 本体，义务与仅链接官方 DLL 的情形不同，需要按 GPLv3/特例逐条复核。

### 对你的项目的含义

只要 AdbManager **确实按开源许可证发布并持续满足 FLOSS 定义**，WinFsp 是可用的。

建议在：

```text
帮助 → 关于
```

或“第三方软件”窗口加入：

```text
WinFsp - Windows File System Proxy
Copyright (C) Bill Zissimopoulos
https://github.com/winfsp/winfsp
License: GPLv3 with FLOSS exception
```

并在仓库增加：

```text
THIRD_PARTY_NOTICES.md
```

记录版本、许可、链接、是否随包分发。

---

## 3.2 WinFsp 有官方 C#/.NET 支持，不需要自己写 C++

WinFsp 官方资料明确列出 C / C++ / **.NET** 为内建语言支持。

当前 NuGet：

```xml
<PackageReference Include="winfsp.net" Version="2.2.26215" />
```

该包包含 `.NET Standard 2.0` 目标，对 `net9.0-windows` 可兼容使用。

因此架构应是：

```text
AdbManager.exe (.NET 9)
   │
   └─ winfsp.net
         │
         └─ WinFsp native/runtime + driver
```

而不是：

```text
C# → 自己写 C++ bridge → P/Invoke → WinFsp
```

后者没有必要。

---

## 3.3 版本建议：当前有一个“稳定版 vs 安全补丁”取舍

截至 **2026-08-25**：

- 上游 “WinFsp 2025” `2.1.25156` 是 stable；
- “WinFsp 2026 Beta4” `2.2.26215` 于 2026-08-03 发布；
- 2026 Beta 分支的 release notes 明确包含对 **CVE-2026-3006**、**CVE-2026-7162** 等问题的修复；
- `winfsp.net 2.2.26215` 已同步发布到 NuGet。

因此不建议为了“stable 标签”简单固定在旧 2.1，然后无视已公开的驱动层安全修复。

### 推荐发布策略

开发/内测：

```text
WinFsp runtime >= 2.2.26215
winfsp.net = 2.2.26215
```

公开发行：

- 如果届时 WinFsp 2026 stable 已发布：切到包含上述修复的 stable；
- 如果仍只有 Beta：明确标注“WinFsp 2.2 当前为 prerelease”，在三台目标真机 + Win10/Win11 上做完整回归后再决定；
- **不要静默回退到存在已知已修复漏洞的旧版本。**

同时不要把 MSI 提交进仓库；程序检测未安装时引导用户前往 WinFsp 官方下载页。

---

## 3.4 用户最小安装步骤

推荐用户流程：

1. 用户自行安装 `adb`（你的项目已有这个约束）。
2. 首次点击“挂载为磁盘”：
   - 程序检测 WinFsp runtime；
   - 未安装：弹出说明和“打开 WinFsp 官方下载页”；
   - 安装官方 signed MSI；
   - 返回 AdbManager 后再次挂载。
3. 不需要单独安装 C++ bridge，不需要 Samba，不需要 WebDAV server。
4. AdbManager 退出或设备断开时调用 `Unmount()`。

UI 建议：

```text
右键设备
├─ 挂载为磁盘
├─ 卸载磁盘        ← 只有挂载状态显示/启用
├─ 文件传输        ← 永久保留兜底
├─ 访问相册
└─ 屏幕共享
```

---

# 4. WinFsp 文件系统的关键设计：绝不能“一次回调 = 一次 adb”

Explorer 会做大量重复操作：

```text
枚举目录
GetFileInfo
打开
再次 GetFileInfo
图标/缩略图 handler
属性读取
关闭
重新枚举
...
```

若每个回调都起一个 `adb.exe`：

```text
50~200 ms × 数十/数百次
```

体验一定不可用。

正确设计是“**目录快照是元数据获取单位**”。

---

## 4.1 推荐缓存层

### A. DirectorySnapshotCache

Key：

```text
(deviceId, normalizedRemoteDirectory)
```

Value：

```csharp
sealed record DirectorySnapshot(
    IReadOnlyDictionary<string, RemoteNode> Children,
    DateTimeOffset LoadedAt);
```

`RemoteNode` 至少有：

```csharp
sealed record RemoteNode(
    string Name,
    string RemotePath,
    bool IsDirectory,
    long Size,
    DateTimeOffset? ModifiedUtc);
```

### TTL

建议初值：

| 缓存 | TTL | 原因 |
|---|---:|---|
| 当前/最近目录快照 | **3 s** | Explorer 重复查询很多；又不能太旧 |
| 一般 inode/path metadata | 跟随父目录快照 | 不再单独 adb |
| negative lookup | **1 s** | 避免 Explorer 重复探测不存在文件 |
| 磁盘容量/free space | **15–30 s** | 没必要频繁 `df` |
| 手动刷新 | 立即失效 | 用户有明确意图 |

不要对每个文件单独执行 `stat`。

一次目录刷新应尽量在**一个 adb shell 进程**内把该目录所有条目和必要 metadata 批量拿回来。

---

## 4.2 缓存失效策略

本程序自己做的写操作可以精确失效：

| 操作 | 成功后 |
|---|---|
| Create / mkdir | invalidate(parent) |
| Delete | invalidate(parent), remove(node) |
| Rename A→B | invalidate(parentA), invalidate(parentB) |
| Push/overwrite | invalidate(parent), remove file read cache |
| 手机端外部 App 改文件 | 靠 3 s TTL / 手动刷新发现 |

这样不需要复杂 watcher。

ADB 本身没有适合这里的低成本、跨 ROM 文件系统 change notification，因此不要第一版上 inotify 长连接。

---

## 4.3 每设备限流

推荐：

```text
metadata shell concurrency: 1~2
file transfer concurrency: 1（TCP）/ 最多 2（USB）
```

不要为了“更快”同时起 10 个 `adb.exe`；TCP 设备会发生队头阻塞、CPU/IO 抖动，Explorer 也会更容易积压回调。

可实现：

```csharp
sealed class DeviceIoScheduler
{
    public SemaphoreSlim Metadata { get; } = new(2, 2);
    public SemaphoreSlim Transfer { get; } = new(1, 1);
}
```

后续再根据 USB/TCP 调不同上限。

---

# 5. 读路径：第一版建议“首次读取整文件到本地缓存”

WinFsp `ReadFile(offset, length)` 是随机读语义。

最糟糕的映射是：

```text
每次 64 KB ReadFile
→ adb shell dd skip=...
→ 起一个 adb.exe
```

这会灾难性地慢。

### 推荐

首次真正发生 Read 时：

```text
remote /sdcard/xxx
        │
        └─ adb pull（一次）
              ▼
%LocalAppData%\AdbManager\Cache\mount\<deviceHash>\read\...
              │
              └─ 之后所有 offset read 都是本地 FileStream/RandomAccess
```

优点：

- WinFsp 随机读变成本地磁盘随机读；
- Explorer 对同一文件做多次读不会重复走 adb；
- 实现简单；
- 与 USB/TCP 一致。

缺点：

- 首次打开大视频要等整文件；
- 读一个 4 GB 文件仅取头部时不划算。

这仍然是第一版最稳的方案。等 profiling 明确证明“大文件首读”是主要瓶颈后，再做分块/持久 ADB 通道。

---

# 6. 写路径：必须 local staging，绝不能每个 WriteFile 都 adb push

正确流程：

```text
Create/Open for write
      │
      ▼
本地 staging file
      │
      ├─ WinFsp Write(offset,data) → 本地随机写
      ├─ SetLength → 本地
      └─ 标 dirty
                │
        Flush / Cleanup / Close
                ▼
adb push staging → /sdcard/path/.adbmanager-upload-<guid>.tmp
                │
                ▼
adb shell mv temp target
                │
                ▼
invalidate parent + 清 read cache
```

### 为什么要先 push 到远端临时文件再 mv

直接：

```text
adb push local target
```

若 TCP 中途断开，目标文件可能处于半写状态。

使用同目录临时文件：

```text
.push-temp → mv -f → target
```

可把“提交窗口”缩到最后一步。它不是完整数据库事务，但比直接覆盖可靠得多。

### Flush 语义

- staging 没有 dirty → 不做 adb。
- dirty → 执行一次提交，成功后清 dirty。
- 后续又写入 → 再变 dirty。
- Close 时仍 dirty → 最后提交。

若提交失败：

- 返回 WinFsp 对应 IO error；
- **不要删除 staging**；
- 把它保存在 recovery 区，用户可在下次启动看到“有 1 个未同步文件”。

这比失败时静默丢数据安全。

---

# 7. 文件操作映射

| Windows 语义 | Android/ADB |
|---|---|
| Create directory | `mkdir` |
| Delete file | `rm -f` |
| Delete empty directory | `rmdir` 优先 |
| Rename/move same volume | `mv` |
| Read | 本地 read cache，首次 `adb pull` |
| Write | 本地 staging，Flush/Close push + mv |
| Truncate | 本地 staging `SetLength(0)`，提交 |
| File time | 可选映射 `touch`；第一版可只可靠返回 mtime |
| ACL | 不尝试映射 Android SELinux/Unix ACL；对 `/sdcard` 暴露统一 Windows 语义 |
| symlink | 第一版可不支持/当普通受限项处理 |

### 关于删除目录

不要把 Windows `DeleteDirectory` 默认映射成：

```sh
rm -rf
```

Windows 文件系统语义通常要求“非空目录删除失败”。第一版建议：

1. 先根据目录快照确认为空；
2. 用 `rmdir`；
3. 只有你自己的“强制递归删除”UI 命令才允许 `rm -rf`。

这样不容易因 Explorer 一个动作误删一棵目录。

---

# 8. 一个非常重要的安全/正确性问题：不能继续拼 shell 文件名

挂载后，文件名来自 Android 设备，是**不可信输入**。

例如设备存在：

```text
a"; rm -rf ...
```

如果继续：

```csharp
$"adb shell rm \"{path}\""
```

既有 shell injection 风险，也会在引号、`$`、反引号、换行等文件名上出错。

### 新代码原则

**Host 侧参数：用 `ProcessStartInfo.ArgumentList`。**

```csharp
var psi = new ProcessStartInfo
{
    FileName = adbPath,
    UseShellExecute = false,
    RedirectStandardOutput = true,
    RedirectStandardError = true,
    CreateNoWindow = true
};

psi.ArgumentList.Add("-s");
psi.ArgumentList.Add(deviceId);
psi.ArgumentList.Add("pull");
psi.ArgumentList.Add(remotePath);
psi.ArgumentList.Add(localPath);
```

**必须进入 Android shell 的参数：严格 POSIX shell quote。**

```csharp
static string ShQuote(string value)
    => "'" + value.Replace("'", "'\"'\"'") + "'";
```

然后：

```csharp
var command =
    $"mv -- {ShQuote(tempRemote)} {ShQuote(targetRemote)}";
```

同时做：

- 根路径 canonicalize；
- 禁止 `..` 逃逸 `/sdcard`；
- 不允许虚拟路径绕到 `/data` 等未授权范围。

---

# 9. WinFsp C# 骨架

> 下列代码是“接口分层骨架”，重点在架构，不假装是一份复制后零修改即可编译的完整文件系统实现。WinFsp 的具体 callback 签名应以你固定版本 `winfsp.net` 的 `FileSystemBase` API 为准。

## 9.1 项目依赖

```xml
<ItemGroup>
  <PackageReference Include="winfsp.net" Version="2.2.26215" />
</ItemGroup>
```

## 9.2 结构

```csharp
interface IAndroidStorageBackend
{
    RemoteNode GetNode(string remotePath);
    IReadOnlyList<RemoteNode> ListDirectory(string remotePath);

    string EnsureLocalReadCopy(string remotePath);
    string OpenWriteStage(string remotePath, bool preserveExisting);
    void CommitStage(string stagePath, string remotePath);

    void CreateDirectory(string remotePath);
    void DeleteFile(string remotePath);
    void DeleteEmptyDirectory(string remotePath);
    void Rename(string from, string to);
}

sealed class AdbStorageBackend : IAndroidStorageBackend
{
    // AdbHelper + cache + DeviceIoScheduler
}

sealed class AndroidFileSystem /* : FileSystemBase */
{
    private readonly IAndroidStorageBackend _backend;

    // GetSecurityByName/Open/Create/Read/Write/Flush/Cleanup/Close/
    // ReadDirectory/Rename/... callbacks
}

sealed class MountSession : IDisposable
{
    public string DeviceId { get; }
    public object Host { get; }  // 实际用 FileSystemHost

    public void Dispose()
    {
        // host.Unmount();
        // dispose backend / cancellation
    }
}

sealed class MountManager
{
    private readonly Dictionary<string, MountSession> _sessions = new();

    public void Mount(DeviceInfo device) { /* ... */ }
    public void Unmount(string deviceId) { /* ... */ }
    public void UnmountAll() { /* ... */ }
}
```

## 9.3 挂载/卸载生命周期

WinFsp 官方 .NET host 支持 `Mount(...)` / `Unmount()`。

建议：

```text
MainForm.FormClosing
    └─ MountManager.UnmountAll()

设备轮询确认 device offline
    └─ MountManager.Unmount(deviceId)

用户右键“卸载磁盘”
    └─ MountManager.Unmount(deviceId)
```

对多设备：

```text
device A → M:
device B → N:
device C → O:
```

盘符可以让 WinFsp/你的选择逻辑从高位空闲盘符中分配；在 session 中保存实际盘符，避免重新扫描猜测。

---

# 10. Windows 与 Android 文件名语义不完全相同

这是虚拟磁盘必须处理的边界：

- Windows 常见文件系统是大小写不敏感；
- Android `/sdcard` 语义可能允许与 Windows 不完全相同的名称；
- Windows 禁止 `:*?"<>|`、尾部点/空格、保留名等；
- Android 可能存在两个只大小写不同的名字。

### 建议第一版

1. 正常 Android 相册/下载目录几乎不会触发这些极端情况；
2. 对无法合法暴露的名字使用**可逆转义**，不要简单删字符；
3. 转义后必须检测碰撞；
4. case-only collision 必须显式处理，不能让两个远端文件映射到同一路径；
5. 日志记录原始路径和映射路径，但不要把隐私文件名上传/提交。

---

# 11. WebDAV 方案为何只建议作为备选

如果你坚持“完全不安装驱动”，WebDAV 是比 SMB 更合理的 Plan B，但不是首选。

Explorer 至少会涉及：

```text
OPTIONS
PROPFIND
GET
PUT
DELETE
MKCOL
MOVE
COPY
```

为了更好兼容，还会遇到：

```text
LOCK / UNLOCK
ETag / If-* 条件头
Range
Depth
207 Multi-Status XML
路径 URL 编码
大小写
超时
```

Microsoft 自己的 WebDAV Redirector 还有额外系统限制：

- `BasicAuthLevel` 默认只允许 Basic Auth over SSL；
- `FileAttributesLimitInBytes` 默认约 1 MB；
- `FileSizeLimitInBytes` 默认约 **50,000,000 bytes**；
- 依赖 `WebClient` service；
- 有各种 timeout/registry 配置；
- 映射结果是网络驱动器，不是真正本地磁盘。

因此它并不符合“实现简单 + 安全不崩溃”的最高原则。

---

# 12. SMB 方案评估

可用库：

```text
SMBLibrary
https://github.com/TalAloni/SMBLibrary
```

它确实支持：

- SMB 1.0/CIFS
- SMB 2.0
- SMB 2.1
- SMB 3.0
- server + client
- virtual filesystem

当前上游 release 页面显示 1.5.7（2026-04-17）。

### 许可

**LGPL-3.0**，不是 MIT/Apache。

对开源 AdbManager 并非绝对不能用，但要满足 LGPL 的库替换/修改、notice/license 等要求。它不像纯 GPL 那样通常要求整个独立应用按 GPL 发布，但合规负担明显高于 Apache/MIT。

### 更大的问题不是许可，是 Windows 运维

SMB 客户端默认用 445，而 Windows 自身已经运行 SMB server/相关服务。你不能像 HTTP 一样简单：

```text
\\127.0.0.1:12345\share
```

给 Explorer 指定任意端口。

结果往往会演变成：

- 停系统 Server 服务；
- 改绑定；
- 要管理员权限；
- 与用户已有 Windows 文件共享功能冲突。

**因此不推荐。**

---

# 13. MTP 对照

需要纠正一个常见说法：

> MTP 不是“天然只读”。设备和 Windows 实现允许时，它可以支持写入/删除。

但它仍然不满足你的核心需求，因为：

- 只解决 USB；
- TCP ADB 不能统一复用；
- Explorer 中通常体现为 Portable Device/object store，而非标准本地盘符；
- 文件系统 API/随机访问/rename 等语义与普通磁盘不同。

所以仍然不选。

---

# 14. 是否保留 FileTransferForm

**强烈建议保留。**

理由：

1. WinFsp 未安装时仍可使用；
2. WinFsp runtime/driver 被企业策略拦截时仍可使用；
3. 某设备文件名/ROM 行为触发挂载层 bug 时还能救急；
4. 内建浏览器更适合显示明确的 adb 错误、进度和手动刷新；
5. 它的维护成本已经很低。

UI 不要把“文件传输”删除，只新增：

```text
挂载为磁盘
```

---

# 15. 功能二：PhotoGalleryForm 总体设计

建议类：

```text
PhotoGalleryForm
├─ GalleryRepository          MediaStore query / item fetch
├─ GalleryCache               thumbnails / originals
├─ ThumbnailScheduler         可见区任务队列
├─ GalleryGridPanel           自绘/虚拟化网格
└─ AlbumSelector              bottom dropdown
```

不建议给每张照片创建一个复杂 WinForms `UserControl`；几千张照片时控件数会爆炸。

优先方案：

- 一个自绘 `ScrollableControl` / `Panel`；
- 只 paint 当前 viewport 附近的 tile；
- 保存 `List<GalleryItem>`；
- 命中测试计算 index；
- 图片对象只缓存当前附近。

如果想最快落地，也可以先用 `ListView` LargeIcon + virtual mode 验证数据链路，再升级自绘网格。

---

# 16. MediaStore：图册枚举优先 BUCKET，不硬编码文件夹

你的三代目标系统：

- Android 10 = API 29
- Android 12 > API 29
- Android 16 > API 29

`BUCKET_ID`、`BUCKET_DISPLAY_NAME`、`RELATIVE_PATH` 都在 API 29 加入，因此版本覆盖是合适的。

Android 官方对 BUCKET 的定位就是“用于对媒体进行一级聚类”的只读字段。

### 推荐

**主路径：MediaStore BUCKET。**

不要把：

```text
DCIM/Camera
Pictures/Screenshots
Pictures/VideoRecorder
```

写死成唯一图册集合。

这些可以作为：

- 已知图册显示名优化；
- 排序/图标优化；
- MediaStore 失败时的 fallback scan root；

但不是 source of truth。

### 图册 key

不要只用：

```text
BUCKET_DISPLAY_NAME = "Camera"
```

因为不同路径可能同名。

至少使用：

```text
(mediaType, bucketId, relativePath)
```

或合并 image/video 后使用稳定的逻辑 album key。

---

# 17. 推荐 MediaStore URI / projection

## 图片

URI：

```text
content://media/external/images/media
```

建议 projection：

```text
_id:
_display_name:
mime_type:
_size:
width:
height:
date_added:
date_modified:
datetaken:
bucket_id:
bucket_display_name:
relative_path
```

命令示意：

```bash
adb -s <device> shell content query \
  --uri content://media/external/images/media \
  --projection _id:_display_name:mime_type:_size:width:height:date_added:date_modified:datetaken:bucket_id:bucket_display_name:relative_path
```

## 视频

URI：

```text
content://media/external/video/media
```

projection：

```text
_id:
_display_name:
mime_type:
_size:
width:
height:
duration:
date_added:
date_modified:
datetaken:
bucket_id:
bucket_display_name:
relative_path
```

### 排序

为了降低 OEM MediaProvider 对复杂 SQL sort 的兼容风险，建议：

1. query 时不依赖复杂 `--sort`；
2. PC 端统一：

```text
dateTaken != null ? dateTaken : dateModified * 1000
```

倒序。

一次把元数据取回来再排序的成本很低。

---

# 18. 为什么不应该把 `_data` 当主接口

Android 10 开始，`DATA/_data` 已经 deprecated；官方对 `RELATIVE_PATH` 也明确强调它用于组织，不应该拿来自己拼 raw filesystem path。

你当前是 adb shell，不是普通三方 Android App，很多设备上 `/sdcard/...` 当然仍可由 `shell` 用户访问；但为了跨 ROM 稳健，**相册读取最好以 MediaStore item URI 为主键，而不是依赖 `_data`。**

例如图片 `_id=1234`：

```text
content://media/external/images/media/1234
```

视频同理：

```text
content://media/external/video/media/5678
```

然后用 Android `content read` 读取媒体内容。

---

# 19. 给 AdbHelper 新增“二进制 stdout → 文件”接口

这是相册最值得先做的基础设施。

```csharp
public static async Task ReadContentUriToFileAsync(
    string deviceId,
    string contentUri,
    string localPath,
    CancellationToken cancellationToken = default)
{
    Directory.CreateDirectory(Path.GetDirectoryName(localPath)!);

    var psi = new ProcessStartInfo
    {
        FileName = AdbPath,
        UseShellExecute = false,
        RedirectStandardOutput = true,
        RedirectStandardError = true,
        CreateNoWindow = true
    };

    psi.ArgumentList.Add("-s");
    psi.ArgumentList.Add(deviceId);
    psi.ArgumentList.Add("exec-out");
    psi.ArgumentList.Add("content");
    psi.ArgumentList.Add("read");
    psi.ArgumentList.Add("--uri");
    psi.ArgumentList.Add(contentUri);

    using var process = new Process { StartInfo = psi };
    process.Start();

    await using var fs = new FileStream(
        localPath,
        FileMode.Create,
        FileAccess.Write,
        FileShare.None,
        bufferSize: 1024 * 128,
        useAsync: true);

    var stderrTask = process.StandardError.ReadToEndAsync(cancellationToken);
    await process.StandardOutput.BaseStream.CopyToAsync(fs, cancellationToken);
    await process.WaitForExitAsync(cancellationToken);

    var stderr = await stderrTask;

    if (process.ExitCode != 0)
    {
        try { File.Delete(localPath); } catch { }
        throw new IOException(
            $"adb content read failed ({process.ExitCode}): {stderr}");
    }
}
```

> 实际实现里再加 timeout + kill process tree，复用你现有的超时模式。

这样：

- 二进制不会经过字符串编码破坏；
- 不依赖 raw `_data`；
- host 参数全部通过 `ArgumentList`。

---

# 20. `content query` 输出解析注意事项

不要直接：

```csharp
line.Split(", ")
```

因为 `_display_name` 本身可以含逗号，甚至 `=`。

如果固定 projection，应根据已知字段标记解析，例如寻找：

```text
, mime_type=
, _size=
, width=
...
```

即“下一字段名”为边界，而不是任意逗号。

更稳的抽象：

```csharp
GalleryRowParser.ParseKnownProjection(line, orderedColumns)
```

并建立单元测试覆盖：

```text
IMG, holiday=1.jpg
中文,空格.jpg
a=b=c.png
```

---

# 21. Android 10 / 12 / 16 与 scoped storage

### 我建议的兼容策略

```text
Tier 1:
MediaStore query + content URI read

Tier 2:
如果某 OEM 的 shell user 对 query/read 异常
→ fallback 到 /sdcard/DCIM、/sdcard/Pictures、/sdcard/Movies 的目录扫描

Tier 3:
保留 FileTransferForm 让用户手工处理
```

原因：

- API 29+ 字段本身在 Android API 上是成立的；
- 但 `adb shell` 是 shell user，不是一个通过普通 app permission 流程授权的 Android App；
- 华为/小米 ROM 可能对 shell/provider 有额外策略；
- 因此“API 字段跨版本存在”不能替代三台真机实测。

你应该把以下测试做成一次 capability probe：

```text
content query images 是否成功
content query videos 是否成功
content read 一个 image item 是否成功
content read 一个 video item 是否成功
```

然后记录：

```csharp
GalleryCapabilities
{
    CanQueryMediaStore,
    CanReadContentUri,
    CanUseLegacyThumbnailTable
}
```

不要按“品牌字符串”硬编码行为。

---

# 22. 缩略图策略：B，但要明确真实数据流

## 22.1 图片

首次打开：

```text
只 query metadata
→ 网格立即出现 placeholder
```

需要加载当前可见图片时：

```text
检查 persistent thumbnail cache
  │
  ├─ hit → decode 小图
  │
  └─ miss
       │
       ├─ content read 原图到 transient 文件
       ├─ 生成 256/384 px thumbnail
       ├─ 写入 thumbnail cache
       └─ 若不是双击打开需求，则删除 transient 原图
```

也就是说：**一张第一次可见的、未缓存的照片，仍可能需要传一次完整原图。**

但总量从：

```text
整个图库 N 张
```

变成：

```text
viewport + 1 屏预取，通常十几张
```

这正是性能收益。

---

# 23. 可否直接用 MediaStore 旧 thumbnail 表

可以做 **opportunistic fast path**，但绝不能作为 Android 10/12/16 的核心保证。

旧 URI：

```text
content://media/external/images/thumbnails
content://media/external/video/thumbnails
```

Android 官方已经把老的 `getThumbnail()` 等 API 在 API 29 弃用，并推荐 App 侧使用：

```text
ContentResolver.loadThumbnail(...)
```

问题是你的 PC 端纯 adb CLI 没有一个等价的标准命令来传目标尺寸并调用 `loadThumbnail()`。

因此建议：

```text
如果 legacy thumbnail query 恰好返回可读缓存
    → 用
否则
    → 回到 visible original fetch
```

不要为它写一堆 ROM-specific hack。

---

# 24. 是否为了缩略图安装 helper APK

第一版：**不建议。**

helper APK 的优势：

```text
ContentResolver.loadThumbnail(uri, Size(...))
→ 真正只返回小图
```

但代价：

- 又多一个 Android APK 工程；
- 需要签名；
- 多设备安装/升级；
- 公开仓库不能提交私有 keystore；
- 安装 APK 的用户信任成本；
- 增加协议/进程生命周期。

如果以后发现 TCP 相册的原图 lazy fetch 仍太慢，再把它作为**可选高性能模式**研究，而不是首版前置依赖。

---

# 25. 缩略图缓存目录设计

全部放在用户本机：

```text
%LocalAppData%\AdbManager\Cache\
```

不要放仓库。

推荐：

```text
Cache\
├─ gallery\
│  └─ <deviceHash>\
│     ├─ thumbs\
│     │  ├─ image\
│     │  └─ video\
│     ├─ originals\
│     └─ transient\
│
└─ mount\
   └─ <deviceHash>\
      ├─ read\
      ├─ staging\
      └─ recovery\
```

`deviceHash`：

```text
SHA256(deviceId) 取前 16~24 hex
```

这样不会把 `192.168.x.x:5555` 等直接放目录名里。

## Thumbnail cache key

至少：

```text
kind + mediaId + dateModified + requestedThumbClass
```

例如：

```text
image_1234_1787541412_384.jpg
```

`date_modified` 变化，自然产生新 key，旧 key 之后由 LRU 清理。

---

# 26. 缓存容量建议

默认：

| 类型 | 建议 |
|---|---:|
| thumbnails | 512 MB |
| opened originals | 2 GB |
| transient | 任务结束即删 |
| mount read cache | 2–5 GB，可配置 |
| staging/recovery | **不按普通 LRU 静默删** |

清理策略：

```text
启动后异步扫描
超过上限 → 按 last access 从旧到新删到 80%
```

不要在 UI 打开相册时同步扫描整个 cache 目录。

`recovery` 中的未提交写入属于用户数据，必须单独提示，不能当 cache 自动清。

---

# 27. 内存缓存与懒加载调度

建议：

```text
当前 viewport
+ 前 1 屏
+ 后 1 屏
```

才进入加载队列。

当用户快速滚动：

- 远离 viewport 的未开始任务直接取消；
- 已经进行到 content read 的任务可视情况完成并落盘；
- 同一 media key 去重；
- 每设备 1~2 个 thumbnail transfer。

内存图片 LRU：

```text
64~128 MB
```

并及时 `Dispose()` `Bitmap/Image`，WinForms/GDI handle 泄漏比 managed heap 更容易先把界面拖死。

---

# 28. 图片格式：System.Drawing 不够覆盖所有现代手机格式

JPEG/PNG 可直接用 `System.Drawing`/GDI+。

但 Android 手机可能有：

- HEIC/HEIF
- WEBP
- AVIF（部分新设备）
- 特殊 HDR/编码格式

建议优先顺序：

1. 普通 JPEG/PNG：`System.Drawing`；
2. 本地已有 Windows codec 的格式：尝试 Windows Shell thumbnail / WIC；
3. 无 codec：显示通用图片 placeholder，不让整个相册崩溃。

**不要为了一个格式首版引入 FFmpeg/ImageMagick 级重量依赖。**

---

# 29. 视频是否显示

建议默认：

```text
“照片与视频”一起显示
```

并提供筛选：

```text
全部 / 照片 / 视频
```

视频 tile 显示：

- 播放三角 overlay；
- `duration`；
- 文件名/日期按你现有 UI 风格决定是否显示。

---

# 30. 视频缩略图能否“不完整下载”

### 可靠答案：纯 adb + 现代 MediaStore，不能保证

你有三种层级：

### A. 最轻量、最稳

若 legacy `Video.Thumbnails` 缓存恰好存在且可读取：

```text
用旧缓存
```

否则：

```text
先显示视频 placeholder
```

双击时才下载完整视频。

这是我最推荐的第一版行为，因为不会为了展示一张 256 px 封面去下载 2 GB 视频。

### B. 完整下载后用 Windows 生成

用户双击/主动请求缩略图时：

```text
完整视频已在 local cache
→ Windows Shell / Media Foundation 抽帧
```

无需 FFmpeg，但实现比图片复杂。

### C. helper APK

设备端 `loadThumbnail()` 最优，但多 APK/签名/安装复杂度。

**不要宣称 MediaStore 有一个跨 Android 10/12/16 可直接 adb pull 的现代“thumbnail 列”。**

---

# 31. 双击打开：`UseShellExecute=true` 不是“保证弹选择程序”

正确第一步：

```csharp
Process.Start(new ProcessStartInfo
{
    FileName = localPath,
    UseShellExecute = true
});
```

这会让 Windows graphical shell 使用文件关联来打开已注册文档。

但对**没有关联**的文件，Shell API 可以返回 no-association error；`.NET Process.Start` 可能抛 `Win32Exception`。不要把“自动弹 Open With”当成契约。

### 稳妥实现

```csharp
try
{
    Process.Start(new ProcessStartInfo
    {
        FileName = localPath,
        UseShellExecute = true
    });
}
catch (Win32Exception)
{
    OpenWithDialog(localPath);
}
```

Windows 有明确的：

```text
SHOpenWithDialog
```

它就是“显示 Open With 对话框”的 API。

C# P/Invoke 骨架：

```csharp
[StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
private struct OPENASINFO
{
    public string pcszFile;
    public string? pcszClass;
    public uint oaifInFlags;
}

private const uint OAIF_EXEC = 0x00000004;

[DllImport("shell32.dll", CharSet = CharSet.Unicode)]
private static extern int SHOpenWithDialog(
    IntPtr hwndParent,
    ref OPENASINFO poainfo);

private static void OpenWithDialog(string path)
{
    var info = new OPENASINFO
    {
        pcszFile = path,
        pcszClass = null,
        oaifInFlags = OAIF_EXEC
    };

    int hr = SHOpenWithDialog(IntPtr.Zero, ref info);
    Marshal.ThrowExceptionForHR(hr);
}
```

Windows 10+ 对“修改默认程序”的行为已经收紧，但它仍可用于让用户选择应用打开**单个文件**。

---

# 32. PhotoGalleryForm 交互设计

## 32.1 关于 `Ctrl +` / `Ctrl -` 的语义冲突

原需求里同时出现：

```text
Ctrl +：列数增
```

和：

```text
Ctrl +：缩略图放大
```

二者在固定窗口宽度下是相反的。

### 我建议采用符合常规缩放直觉的版本

```text
Ctrl +  = 放大缩略图 = 减少列数
Ctrl -  = 缩小缩略图 = 增加列数
```

如果你坚持“Ctrl+ 必须增加列数”，只要交换两个 handler，其他架构不用改。

---

## 32.2 滚轮的“只被动减少、不自动增加”

你的澄清要求可以精确实现为**sticky column count**：

状态：

```csharp
int _columns = 3;
int _thumbPx = 240;
```

Ctrl +/-：

```text
显式改变 _columns
并同步调整 thumb size/布局
```

Ctrl+Wheel：

```text
只改变 _thumbPx

如果当前 _columns 已放不下：
    while (_columns > 1 && 不够宽)
        _columns--

如果变小以后能放更多：
    不增加 _columns
```

伪代码：

```csharp
void OnCtrlWheel(int delta)
{
    _thumbPx = Math.Clamp(
        _thumbPx + (delta > 0 ? 16 : -16),
        96,
        640);

    while (_columns > 1 &&
           RequiredWidth(_columns, _thumbPx) > GalleryViewportWidth)
    {
        _columns--;   // 只向下被动调整
    }

    Relayout();
}
```

这样严格满足：

- wheel 可放大/缩小；
- 放大导致溢出时减少列；
- 缩小后绝不自动加列；
- 增加列只有 `Ctrl -` 这个显式用户动作。

窗口 resize 也建议遵循同规则：变窄可被动减列；重新变宽不自动加回。

---

# 33. 多选

建议保存：

```csharp
HashSet<GalleryItemKey> _selected;
int? _selectionAnchor;
```

行为：

- 普通左键：只选当前；
- `Ctrl + 左键`：toggle；
- `Ctrl + A`：当前图册/当前过滤结果全选；
- 右键一个未选项：先把它设成单选，再弹菜单；
- 右键已选项：保留多选集合。

如果以后需要 Windows 风格，可再加 Shift 范围选择。

---

# 34. 右键菜单语义

建议：

```text
打开
下载到...
复制
删除
--------
全选
```

### “复制”

Windows Clipboard 的 `FileDrop` 需要**本地路径**。

所以点击复制时：

1. 确保选中原图/视频已经下载到 local originals cache；
2. `Clipboard.SetFileDropList(...)`；
3. 用户即可粘贴到 Explorer。

这会产生网络传输，应显示进度/取消。

### “剪切”的问题

如果你把缓存文件放进 Windows Clipboard 并标成 cut：

```text
Explorer 粘贴只会移动本地缓存
```

它不会自动删除 Android 源文件，这会产生非常误导的语义。

因此建议把“剪切”改成：

```text
移动到图册...
```

由 AdbManager 自己执行远端 move。

若需求必须保留“剪切”，就做**应用内部 cut buffer + 粘贴**，而不是伪装成普通 Windows FileDrop cut。

---

# 35. 删除

相册删除后不仅是文件系统问题，还有 MediaStore 索引一致性。

建议第一版封装：

```text
GalleryRepository.Delete(item)
```

内部优先尝试 provider-aware 的删除路径；若 OEM/shell 权限不允许，再 fallback 到文件系统删除并触发刷新/重查。

无论哪种：

```text
成功后立即从本地 model 移除
→ invalidate thumbnail/original cache
→ 后台重新 query 验证
```

不要让 UI 继续显示一个已被删除的 stale MediaStore row。

三台 ROM 对 `shell` user 的 provider 删除权限需要实测；这里不应凭 AOSP 理论写死品牌分支。

---

# 36. “下载”

用户明确选择本地保存位置后：

- 已有 `originals` cache：本地 Copy；
- 没有：`content read` 直接输出到目标/临时文件，再 atomic rename；
- 保留 `_display_name` 的正确扩展名；
- 文件名无扩展名时，可按 MIME 推断一个常见扩展名，但不要擅自覆盖已有扩展名。

下载和双击打开应共用：

```csharp
EnsureOriginalLocalAsync(item)
```

避免两套缓存逻辑。

---

# 37. 建议的 Gallery 数据模型

```csharp
enum MediaKind
{
    Image,
    Video
}

sealed record GalleryItem(
    MediaKind Kind,
    long Id,
    string DisplayName,
    string MimeType,
    long Size,
    int? Width,
    int? Height,
    long? DurationMs,
    long? DateTakenMs,
    long DateModifiedSec,
    string? BucketId,
    string? BucketDisplayName,
    string? RelativePath)
{
    public string ContentUri =>
        Kind == MediaKind.Image
            ? $"content://media/external/images/media/{Id}"
            : $"content://media/external/video/media/{Id}";
}
```

不要把 local cache path 放进 immutable MediaStore metadata；缓存状态交给 `GalleryCache`，避免 model 混杂生命周期状态。

---

# 38. 第三方依赖与开源许可表

| 依赖 | 用途 | 当前核验版本/状态 | 许可 | 对 AdbManager |
|---|---|---|---|---|
| **WinFsp runtime + winfsp.net** | 本地盘符/用户态 FS | 2.2.26215 / 2026 Beta4 | **GPLv3 + FLOSS special exception** | **可用于真正 FLOSS 项目，但必须满足特例条件与 notice；不是普通 permissive license** |
| **SMBLibrary** | SMB server 候选 | 1.5.7 | **LGPL-3.0** | 可合规使用但义务较多；且 445 运维问题严重，不选 |
| **AdvancedSharpAdbClient**（可选） | 减少反复启动 adb client、直接使用 ADB protocol/client API | 3.6.16 | **Apache-2.0** | 许可宽松兼容；首版不必引入 |
| WinForms / .NET | UI/runtime | .NET 9 | .NET OSS 组件以各自 Microsoft 开源许可为准 | 项目现有基础 |
| adb | Android Debug Bridge | 用户自行安装 | AOSP/Apache 2.0 生态 | 不随仓库提交 |
| scrcpy | 屏幕共享 | 用户自行安装 | Apache-2.0 | 本功能不新增分发 |

### 关于 GPL “传染性”

最需要防止的误解：

```text
“WinFsp 是 GPLv3，所以我的 C# 项目必定 GPLv3”
```

**不准确。**

WinFsp 明确给 FLOSS 软件附加链接/分发例外，因此满足例外条件时，不会仅因链接官方 WinFsp DLL 就自动要求整个 AdbManager 变 GPLv3。

但另一个误解也要避免：

```text
“有例外，所以它和 MIT 一样随便”
```

也不准确。

你必须：

- 保持项目真正 FLOSS；
- 满足其版权/链接等特例条件；
- 如果发行模式改变，重新审查。

---

# 39. AdvancedSharpAdbClient：是否现在就引入

我的建议：

## 第一版：不引入

原因：

- 现有代码已经稳定依赖 adb CLI；
- 相册通过“2 次 metadata query + 低并发 lazy transfer”已经把进程数大幅压低；
- WinFsp 通过目录快照也可把 metadata process 数压低；
- 少一个依赖，迁移风险最低。

## 什么时候值得引入

实测如果出现：

```text
缓存已经正确
但目录冷启动仍被 adb.exe process startup 主导
```

再考虑 `AdvancedSharpAdbClient 3.6.16`：

- Apache-2.0；
- 活跃维护；
- 可以把 ADB 通讯放进同进程 client 层；
- 但会改动你现有 `AdbHelper` 的核心通信路径。

把它作为 Phase 5 性能优化，而不是 Phase 0 前置条件。

---

# 40. 建议的落地顺序

我建议**先做相册，再做 WinFsp**。

不是因为挂载不重要，而是相册：

- 不新增驱动依赖；
- 能先验证 MediaStore 在三台 ROM 的真实行为；
- 能顺便补齐二进制 ADB 输出、缓存、限流这些公共基础设施；
- 验收边界清晰；
- 即便磁盘挂载后续延期，相册仍是独立完整功能。

---

## Phase 0：公共 ADB 基础设施

**直接在现有 WinForms 中实现，无第三方依赖。**

新增：

```text
ProcessStartInfo.ArgumentList
binary stdout → Stream/File
timeout + Kill(entireProcessTree)
PerDeviceIoScheduler
CachePaths
ShellQuote / path canonicalization
```

### 验收

- 中文/空格/单双引号文件名不会把 host 参数拆坏；
- `content read` JPEG 与原文件 hash/尺寸一致；
- 取消时 adb 子进程能退出；
- TCP/USB 都通过。

---

## Phase 1：相册 metadata + 图册 + 虚拟网格

**直接实现，无第三方依赖。**

功能：

- `PhotoGalleryForm`
- image/video MediaStore query
- BUCKET 图册
- 默认 3 列
- Ctrl +/- + Ctrl wheel
- 多选
- placeholder

### 验收

三台手机：

- 进入相册数秒内先看到结构，不等待全图库下载；
- Camera/Screenshots/用户文件夹可被正确归组；
- 1000+ item 不创建 1000+ WinForms 子控件导致卡顿；
- USB/TCP 一致。

---

## Phase 2：相册 lazy thumbnails + original open

**直接实现，无第三方依赖。**

功能：

- visible-range scheduler
- persistent thumbnail cache
- original cache
- 双击 ShellExecute + `SHOpenWithDialog` fallback
- download / copy / delete
- 视频 placeholder + duration

### 验收

- 冷启动只下载可见范围；
- 第二次打开大部分首屏零网络传输；
- 快速滚动不会堆积几十个 adb；
- 缓存超过上限自动 LRU；
- 无关联扩展名能显式出现 Open With；
- 删除后重查 MediaStore 不再出现已删项。

---

## Phase 3：WinFsp 只读盘

**依赖 WinFsp。**

先做：

```text
ListDirectory
GetFileInfo
Open
Read
Close
```

不要一开始就写。

### 验收

- Explorer “此电脑”出现真正盘符；
- `/sdcard` 可浏览；
- 目录一次冷刷新最多 1 个 metadata shell；
- 同目录连续刷新/Explorer 属性探测在 TTL 内不重复 adb；
- 打开文件只 pull 一次，后续随机读走 local cache；
- 断开设备后盘符被卸载；
- App 正常退出不残留盘符。

---

## Phase 4：WinFsp 可写盘

**依赖 WinFsp。**

加入：

```text
Create
Write local staging
Flush/Close commit
mkdir
rename
delete
truncate
recovery
```

### 验收

- 复制 PC 文件到盘符后 Android 端 hash 一致；
- 覆盖时 TCP 中途拔线不会把旧 target 静默变成“成功”；
- 提交失败 staging 保留；
- rename/delete/mkdir 能立即更新 snapshot；
- 设备断开不会造成 AdbManager 崩溃。

---

## Phase 5：性能与边界硬化

候选：

- capability probe；
- cache metrics；
- 大文件 read 策略；
- 可选 AdvancedSharpAdbClient；
- 特殊文件名 reversible escaping；
- case collision；
- WinFsp 版本检查；
- crash recovery / stale session cleanup。

---

# 41. 建议新增的文件

```text
AdbManager/
├─ AndroidStorage/
│  ├─ AdbProcessRunner.cs
│  ├─ AdbStorageBackend.cs
│  ├─ DeviceIoScheduler.cs
│  ├─ RemoteNode.cs
│  ├─ DirectorySnapshotCache.cs
│  └─ CachePaths.cs
│
├─ Mounting/
│  ├─ MountManager.cs
│  ├─ MountSession.cs
│  └─ AndroidFileSystem.cs
│
├─ Gallery/
│  ├─ PhotoGalleryForm.cs
│  ├─ GalleryRepository.cs
│  ├─ GalleryItem.cs
│  ├─ GalleryCache.cs
│  ├─ ThumbnailScheduler.cs
│  └─ GalleryGridControl.cs
│
└─ THIRD_PARTY_NOTICES.md
```

对于现在规模的项目，不要上 DI framework、ORM、消息总线等重型架构。

直接构造少量 service 对象就够：

```csharp
var scheduler = new DeviceIoScheduler();
var repository = new GalleryRepository(device.Id, scheduler);
var cache = new GalleryCache(device.Id);
new PhotoGalleryForm(device, repository, cache).Show();
```

---

# 42. 我认为最值得优先改的三个现有设计点

## 42.1 `RunCommandAsync(string cmd)` 不再用于新复杂路径

它可以继续服务已有简单命令，但新功能统一走：

```text
FileName + ArgumentList
```

避免 quoting bug。

## 42.2 新增 binary stream API

这同时解决：

- MediaStore content read；
- 后续可能的流式 ADB；
- 不把二进制塞进 `string`。

## 42.3 把“缓存”设计成正式组件，不散落在 Form 里

PhotoGallery 和 WinFsp 都需要：

- device key；
- cache directory；
- LRU；
- invalidation；
- cancellation；
- concurrency limit。

把这些放在 Form event handler 里，后期很快失控。

---

# 43. 最终决策表

| 问题 | 最终建议 |
|---|---|
| 真正 Explorer 本地盘 | **WinFsp** |
| WinFsp 是否必须 C++ | **否，官方有 .NET/C# 支持** |
| WinFsp 许可 | **GPLv3 + FLOSS 特例**；满足特例的开源项目可用，不等于项目必须 GPLv3 |
| 当前 WinFsp 版本 | 开发核验 **2.2.26215 / 2026 Beta4**；发布时优先切到包含安全修复的 stable |
| 用户安装 | 仅额外安装 WinFsp runtime MSI；adb/scrcpy继续用户自备 |
| WebDAV | 只做无驱动 Plan B，不做主架构 |
| SMB | 库存在但 LGPL + 445 冲突，**不选** |
| MTP | 可写与否非核心；USB-only + 非标准盘符，**不选** |
| 元数据性能 | **目录快照 3 s TTL**，一次目录一次 shell，绝不 per-file stat |
| 读文件 | 首读 `adb pull` 到 local read cache |
| 写文件 | local staging → remote temp → `mv` commit |
| 内建 FileTransferForm | **保留兜底** |
| 相册数据源 | **MediaStore BUCKET + metadata** |
| 图片 URI | `content://media/external/images/media` |
| 视频 URI | `content://media/external/video/media` |
| scoped storage | Content URI 为主；目录扫描 fallback；三台 ROM capability probe |
| 缩略图 | **visible-only lazy fetch + persistent thumb cache** |
| 旧 MediaStore thumbnail | 仅 opportunistic，不依赖 |
| helper APK | 首版不引入 |
| 视频 thumbnail | 无可靠纯 ADB 小图接口时显示 placeholder；不要为缩略图全下巨大视频 |
| 双击打开 | `UseShellExecute=true`，失败显式 `SHOpenWithDialog` |
| Ctrl+/- | 建议 `+` 放大/减列，`-` 缩小/加列 |
| Ctrl+Wheel | 改 thumb px；仅溢出时被动减列，绝不自动加列 |
| copy/cut | Copy 可 materialize local files；Cut 建议改“移动到图册”，避免假 Windows cut |
| 开发顺序 | **公共 ADB → 相册 → WinFsp 只读 → WinFsp 可写 → 性能硬化** |

---

# 44. 参考资料

以下链接均为本次调研使用的上游/官方来源，建议在真正编码前固定依赖版本后再核一遍。

## WinFsp

1. WinFsp Source / Licensing / .NET support  
   https://winfsp.dev/src/

2. WinFsp main site  
   https://winfsp.dev/

3. WinFsp GitHub releases  
   https://github.com/winfsp/winfsp/releases

4. WinFsp license  
   https://github.com/winfsp/winfsp/blob/master/License.txt

5. `winfsp.net` NuGet 2.2.26215  
   https://www.nuget.org/packages/winfsp.net/2.2.26215

6. WinFsp ARM64 / installer notes  
   https://winfsp.dev/doc/WinFsp-on-ARM64/

## Windows / Microsoft

7. Using the WebDAV Redirector  
   https://learn.microsoft.com/en-us/iis/publish/using-webdav/using-the-webdav-redirector

8. `ProcessStartInfo.UseShellExecute`  
   https://learn.microsoft.com/en-us/dotnet/api/system.diagnostics.processstartinfo.useshellexecute

9. `SHOpenWithDialog`  
   https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/nf-shlobj_core-shopenwithdialog

10. `OPENASINFO`  
    https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/ns-shlobj_core-openasinfo

## Android / MediaStore

11. `MediaStore.MediaColumns`  
    https://developer.android.com/reference/android/provider/MediaStore.MediaColumns

12. API 29 diff for MediaColumns  
    https://developer.android.com/sdk/api_diff/29/changes/android.provider.MediaStore.MediaColumns

13. `MediaStore.Images.Thumbnails`  
    https://developer.android.com/reference/android/provider/MediaStore.Images.Thumbnails

14. `MediaStore.Video.Thumbnails`  
    https://developer.android.com/reference/android/provider/MediaStore.Video.Thumbnails

15. AOSP `content` shell command source/usage（可从 AOSP frameworks/base cmd content 核验）  
    https://android.googlesource.com/platform/frameworks/base/+/master/cmds/content/

## SMB / optional ADB library

16. SMBLibrary  
    https://github.com/TalAloni/SMBLibrary

17. AdvancedSharpAdbClient 3.6.16  
    https://www.nuget.org/packages/AdvancedSharpAdbClient

---

# 45. 一句话建议

如果现在就开始改代码：

> **先给 `AdbHelper` 补“结构化 ArgumentList + binary stdout + 每设备限流”，随后直接做 `PhotoGalleryForm`；相册验收后再引入 WinFsp，以“只读盘 → 本地 staging 可写盘”两步推进。不要用 WebDAV/SMB 绕开一个 WinFsp runtime 安装，因为那会用更高的软件复杂度换来更差的 Explorer 语义。**
