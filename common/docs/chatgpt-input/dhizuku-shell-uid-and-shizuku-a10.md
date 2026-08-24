# 问题：Dhizuku 的进程/服务 UID + Android 10 上 Shizuku 的保活方案

## 背景

设备：华为畅享 50z，Android 10（API 29），无 root。
目标：一个普通 App（uid 10201）需要一个 **uid 2000（shell）身份** 的命令执行通道，长期运行。

当前状态（已用 adb 实测确认）：
- Dhizuku v2.12.0 已激活为 **Device Owner**（`dpm list-owners` 确认），其 app 进程 uid = 10203。
- Shizuku（moe.shizuku.privileged.api，manager uid 10200）已安装，且 **shizuku_server 进程当前正以 `shell`（uid 2000）运行**。
- 我方 App 已集成 Shizuku-API 13.1.5 + `ShizukuProvider` + `moe.shizuku.manager.permission.API_V23`，准备用 `Shizuku.bindUserService(UserServiceArgs(我方Service), conn)` 让 Service 在 shizuku_server 进程内运行。

## 具体问题

### Q1（最关键）：Dhizuku 能否提供 shell（uid 2000）执行？
- Dhizuku 的 `Dhizuku.newProcess(String[] cmd, String[] env, File dir)`（`IDhizuku.remoteProcess`）启动的进程，**以哪个 UID 运行**？是 Dhizuku app 自己的 uid（10203），还是 shell（2000）或 root（0）？
- Dhizuku 的 `bindUserService(DhizukuUserServiceArgs, ServiceConnection)` 把客户端声明的 Service 加载到哪个进程、以哪个 UID 运行？
- 结论确认：Dhizuku 作为 Device Owner，是否**不可能**让第三方 App 获得 adb shell 等价能力？（我参考的文档结论是：Dhizuku 只共享 DevicePolicyManager 能力，不是 shell。）
- 如果 Dhizuku 有"delegated scopes"机制（v2.4+ 的 getDelegatedScopes/setDelegatedScopes），它能代理哪些系统服务 binder？能否用于 `grantRuntimePermission` 这类 DPM 调用，给第三方 App 授予危险权限（如存储权限）？

### Q2：Android 10 无 root 上，Shizuku（ADB 模式）的长期可用性
- A10 没有 Android 11+ 的"无线调试"配对 UI，只有 legacy `adb tcpip 5555` + `adb connect <ip>:5555`（网络 adb，需要某个 adb client 发起）。
- Shizuku server 重启后需要重新启动。A10 上可行的启动方式有哪些？
  1. USB 连电脑：`adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`
  2. 网络 adb：`adb tcpip 5555`（需一次 USB/已有 adb）→ `adb connect <手机IP>:5555` → 同上 start.sh
  3. 手机本地 adb client（如 Termux 装 adb 后 `adb connect 127.0.0.1:5555`）——**在 A10 上是否可行**？本地 client 的 RSA 授权问题如何解决（/data/misc/adb/adb_keys 的 key 能否被本地 client 使用）？
  4. 有没有其他"无电脑、无 root"的 A10 启动 Shizuku 方案？
- Shizuku v13.x（2025/2026 版本）在 Android 10 上 start.sh 的路径是否仍是 `/sdcard/Android/data/moe.shizuku.privileged.api/start.sh`？（我实测该目录当前不存在，但 shizuku_server 已在运行，说明可能是旧版本启动的或路径有变。）

### Q3：Shizuku-API 13.1.5 + targetSdk 29 的组合
- 在 API 29 设备、App targetSdk 29、Shizuku server 由 ADB 启动的场景下，客户端流程 `Shizuku.pingBinder()` → `checkSelfPermission()` → `requestPermission(1001)`（用户点允许）→ `bindUserService` 是否是官方推荐且可靠的完整流程？
- A10 上 Shizuku manager 的授权弹窗（requestPermission）是否正常工作？有没有 A10 特有的坑？

## 期望输出
- 对每个问题给出明确结论 + 依据（官方仓库/源码/文档链接）。
- Q1 若确认 Dhizuku 不能给 shell，请明确说明 Dhizuku 在"给第三方 App 授权"场景下的正确用途（比如 DPM 代授权）。
- 给出 A10 场景下"shell 通道"的推荐架构（启动、保活、重启后恢复的具体步骤）。
