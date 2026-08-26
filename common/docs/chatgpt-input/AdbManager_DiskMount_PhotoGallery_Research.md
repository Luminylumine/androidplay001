# AdbManager：虚拟磁盘挂载 + 相册预览 —— 架构调研请求

## 0. 背景与目标
- **项目**：AdbManager，C# WinForms，.NET 9（`net9.0-windows`），Windows 桌面工具。
- **用途**：通过 adb / scrcpy 管理 Android 设备（USB + TCP 无线调试），已实现设备枚举、屏幕共享（多设备并行）、键盘/黑屏、PC 输入法注入等。
- **仓库**：`github.com/Luminylumine/androidplay001`，工作分支 `feature/scrcpy-enhance`。
- **重要约束（本次强调）**：仓库**现在有远程、要求按"可开源"标准开发**：
  - 第三方依赖（NuGet / 原生库 / 运行时）必须开源许可兼容，GPL 传染性需特别说明；
  - 不提交：工具链二进制（`tools/`）、adb 私钥/签名 keystore、编译产物、个人隐私数据（见 `.gitignore`：`dcim_latest/`、`personal_surface/`、`photos_extracted/`、`adbkey*`、`*.keystore`、`exploits/lpe_*` 等）；
  - adb / scrcpy 由**用户自行安装**，程序通过 `FindExecutable` 优先找仓库内 `tools/...`，否则回退系统 `PATH`。

本次要新增/改造**两个功能**：
1. **挂载为磁盘**：把设备存储虚拟成 Windows 资源管理器里可读写的光盘/磁盘。
2. **相册预览**：在 Windows 端**预览照片**（而不是只看文件名），带缩放/列数/多选/图册切换/右键操作。

---

## 1. 现有代码能力（已实现，可直接复用）
`AdbHelper`（全部 `static`）：
- `RunCommandAsync(cmd, timeoutMs)`：按**首个空格**拆成 `fileName + args`，起子进程，返回 stdout（若 stdout 空则返回 stderr）。
- `ShellExecAsync(deviceId, shellCmd, timeoutMs)` = `adb -s <id> shell "<cmd>"`。
- `ListDirectoryAsync(deviceId, path)` = `adb -s <id> shell ls -la "<path>"`。
- `PullFileAsync(deviceId, remote, local)` = `adb pull`（默认 5min 超时）。
- `PushFileAsync(deviceId, local, remote)` = `adb push`（默认 5min 超时）。
- `GetDevicesAsync()`：枚举 USB + TCP 设备（保留 USB、自动补 TCP），返回 `List<DeviceInfo>`。
- `ConnectAsync(ip, port)` / `PairAsync(ip, port, code)`：无线调试连接/配对。
- 其它：`settings get/put`、scrcpy 启动（含能力探测）、`WakeDeviceAsync`（keyevent 224）等。

`DeviceInfo`：`Id`（USB=序列号如 `FEDBB...`；TCP=`ip:port` 如 `192.168.43.1:5555`）、`Name`、`IsUsb`、`DisplayName`。

`FileTransferForm`（当前实现）：基础 `ls -la` 文件浏览器——路径栏 + 上/刷新/上传/下载/删除按钮 + `ListView`（名称/大小/日期），双击进目录，`FileEntry { Name, Path, IsDirectory, Size }`。**无预览、无多选、无相册**。

`MainForm`：设备列表 + 右键菜单（`传输文件` / `访问相册` / `屏幕共享` / `连接TCP` 等）。已支持**多设备并行会话**（`Dictionary<deviceId, ScrcpySession>` 管理）。

---

## 2. 目标环境
- **3 台真机**：华为 Mate 30（Android 10）、华为 P 系（Android 12）、小米 Redmi（Android 16）。
- **连接方式**：USB 与 TCP（adb over wifi）**都要支持**。
- 平台：Windows + .NET 9 WinForms；不引入重量级框架。

---

## 3. 功能一：挂载为磁盘（核心难点，需架构决策 + 许可分析）

### 需求
- 右键设备 → 将"文件传输"**改为/新增**"挂载为磁盘"。
- 点击后，Windows 资源管理器"此电脑"里出现一个**可读写磁盘**，映射到设备存储（以 `/sdcard` 为主，或 shell 可访问范围）。
- 同时支持 **USB 与 TCP** 设备（因此不能只靠 USB MTP）。
- 退出软件 / 设备断开时**自动卸载**，不留残留。

### 候选方案（请逐一评估并给明确推荐）
1. **WinFsp（Windows 用户态文件系统代理）**：实现 C# 文件系统回调（`FindFirstFile/ReadFile/WriteFile/CreateFile/DeleteFile/RenameFile/GetFileInformationByHandle` 等），每个操作映射到 adb shell（`ls`/`stat`/`cat`/`push`/`pull`/`mkdir`/`rm`/`mv`）。出现为**本地盘符**。**必须确认：WinFsp 的开源许可证（GPL/LGPL？对本项目的传染性影响）；是否有官方 C# 支持，还是只能 C++ 实现后 P/Invoke。**
2. **本地 WebDAV 服务 + `net use` 映射网络驱动器**：PC 用 `HttpListener` 起 WebDAV 服务器（实现 `PROPFIND/GET/PUT/DELETE/MKCOL/MOVE/COPY`），后端走 adb；`net use Z: http://127.0.0.1:<port> /user:...`。无内核驱动、无需安装，出现为"网络位置"。**请评估：.NET 手写 WebDAV server 的复杂度；Windows WebDAV 客户端的兼容性坑（长文件名/权限/超时）。**
3. **本地 SMB(Samba2) 服务器 + `net use \\127.0.0.1\share`**：Explorer 集成最好，但 .NET 生态里 SMB **server** 较少（多为 client 库）。**请评估：是否存在可用且许可兼容的 .NET SMB server 库。**
4. **USB MTP**：USB 原生、但基本只读、仅 USB、非盘符。仅作对照，不满足"统一 + 可写"。

### 请重点回答
- 在五点权衡下给**明确推荐 + 理由**：
  (a) 真正"资源管理器里的磁盘"（本地盘 vs 网络位置）
  (b) USB + TCP 统一
  (c) 无需/轻量安装（用户体验）
  (d) 可开源许可
  (e) adb 往返延迟下的元数据查询性能
- **性能**：资源管理器会发起大量小元数据查询，而每次 adb 调用都是**进程启动（约 50–200ms）**。需要怎样的缓存（目录项缓存、inode 缓存、TTL/失效策略、写回策略）才可用？
- **具体选型**：给出第三方库 / 运行时 + 用户侧**最小安装步骤**。
- **读写映射**：写文件如何映射（先写本地暂存再 `adb push`？还是流式）？删除 / 改名 / 新建目录如何映射？
- 是否建议**保留原内建"文件传输"浏览器作为兜底**（挂载失败 / 未装运行时时）？

---

## 4. 功能二：相册预览（UI 交互 + 缩略图策略）

### 需求（界面与交互）
右键设备 → "访问相册" → 弹出窗口：
- 照片网格，**默认 3 列**。
- `Ctrl +` / `Ctrl -`：列数**增 / 减**（即缩略图放大 / 缩小）。
- `Ctrl` + 鼠标**滚轮**：缩放。规则——放大到一行放不下时**减少列数**；缩放到能放下更多时**不自动增加**列数（列数只由 `Ctrl ±` 手动控制）。
  > 说明：以上对"谁改列数"的表述可能有出入。请按"**列数仅由 `Ctrl +` / `Ctrl -` 手动改变；滚轮只改变缩略图尺寸，并据此被动减少列数**"来设计；若你认为更合理的交互不同，请指出。
- 窗口**底部：图册选择**。默认"全部"；并扫描系统内置图册（截屏 `Pictures/Screenshots`、录屏 `Pictures/VideoRecorder`、相机 `DCIM/Camera` 等）与用户自建文件夹，作为**下拉子菜单**。

操作：
- **双击** = Windows 双击 = 用默认程序打开（无默认程序则弹"选择程序"对话框）。→ 需先把原图下载到本地缓存（带正确扩展名），再 `ShellExecute`。
- **右键菜单**：下载 / 删除 / 复制 / 剪切 / 全选（多选）等基础操作。
- `Ctrl` + **左键** = 多选（切换选中）。

### 请重点回答
- **缩略图方案（关键，避免大量下载）**：
  - 方案 A：`adb pull` 全图到本地缓存 → 客户端 `System.Drawing` / WIC 生成缩略图。缺点：首次打开相册会下载大量 3–15MB 原图，重。
  - 方案 B：先用 `adb shell content query`（MediaStore，如 `content://media/external/images/media`）拉取**元数据**（路径/尺寸/日期/大小/bucket）快速建网格；**懒加载**：仅对可见项下载原图生成缩略图 + **磁盘缓存**；双击时才下全图。
  - **推荐哪种？给出懒加载 + 缓存目录设计**（缓存放哪、命名、容量上限、何时清理）。
- **图册枚举**：用 MediaStore **BUCKET**（`BUCKET_ID` / `BUCKET_DISPLAY_NAME`）还是直接扫 `DCIM/*`、`Pictures/*`？在 Android 10 / 12 / 16（11+ scoped storage，`adb shell` 为 `shell` 用户）下哪个更稳？请给出**具体的 `content://` URI 与 projection 字段**（跨这三个版本可用的写法）。
- **视频**：相册里混有视频时是否显示？视频缩略图如何在**不完全下载**的情况下获得（MediaStore 是否有缩略图列？是否需要装额外工具？）
- **双击打开**：无默认程序时弹"选择程序"是 Windows `ShellExecute` 的天然行为吗？确认 `Process.Start(new ProcessStartInfo { UseShellExecute = true })` 对**无关联扩展名**文件的弹窗行为。
- 建议新建 `PhotoGalleryForm`（复用 `AdbHelper`），与"文件传输"并列保留。

---

## 5. 交付要求（给 GPT 的产出格式）
1. **功能一**：明确推荐的磁盘挂载架构 + 许可分析 + 关键代码骨架（C# / .NET 9）+ 用户安装步骤 + 性能缓存策略。
2. **功能二**：缩略图 / 懒加载 / 缓存方案 + 图册枚举方案（含各安卓版本的 URI / projection）+ 完整交互实现要点（列数/缩放/多选/右键/双击打开）。
3. **分阶段落地顺序**：先做哪个、各自的可独立验收点；并标注**哪些我可以直接在现有 WinForms 里实现、哪些依赖你给的第三方选型**。
4. 所有第三方依赖必须**给出开源许可证**，并说明与"可开源项目"是否兼容（GPL 传染性单独说明）。

## 6. 约束
- 平台：Windows + .NET 9 WinForms；不引入重量级框架。
- 开源：第三方依赖许可需兼容；不提交工具链 / 密钥 / 二进制 / 隐私数据。
- adb 每次调用都是进程启动，**严格控制调用次数并做缓存**。
