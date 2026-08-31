# Phone Mirror Phone — Android 主控端方案调研 Prompt

> 给 ChatGPT 的完整调研请求，涵盖技术选型、架构设计、关键可行性分析。

---

## 背景与目标

### 项目仓库
- 仓库路径: `d:\study\androidplay\phone_mirror_phone`
- 现有分支:
  - `feature/scrcpy-enhance`: WinForms .NET 9.0 **PC 主控端** (AdbManager + 外部 scrcpy 进程, 已 v1.0.0 release)
  - `feature/akasha-android`: Android 端 **Shizuku/Dhizuku shell 服务端** (已 v1.0.0 release)
  - `feature/device-monitor-tools`: Android 端 **运行时监控** (SysMon, 已 v1.0.0 release)
  - 三个 release 分支都已发版
- 新分支名: **`feature/phone-mirror-phone`**
- 核心目标: 把"PC 主控端"的能力移植到 **Android 设备**上, 实现 Android-to-Android 的 ADB 远程控制

### 已有的公共资产 (可直接复用)
- `common/sdks/shizuku/{api,provider,aidl}/classes.jar` — Shizuku SDK
- `common/sdks/dhizuku/dhizuku-api/classes.jar` — Dhizuku SDK
- `common/scripts/setup_toolchain.ps1` — 工具链脚本
- 已有 PC 端 `AdbHelper.cs` / `DeviceIoScheduler.cs` / `GalleryRepository.cs` 的设计思路可参考

### 已实现的 PC 端核心功能 (Android 端需要对齐)
1. **设备发现**: adb devices -l + adb mdns services + WiFi TCP 自动连接
2. **屏幕镜像/控制**: 外部调用 scrcpy 进程 (Android 端不能这么做)
3. **文件传输**: adb push/pull + 内容提供者 (ContentProvider)
4. **相册管理**: 缩略图缓存 (DeviceIoScheduler) + 增量同步 (GalleryRepository) + Grid UI
5. **磁盘挂载**: 通过 MediaProvider DISK mount 读取
6. **文本桥接**: 向目标设备注入输入法文字

---

## 调研需求 (请完整覆盖以下五个部分)

### Part 1: 技术栈选型 — 原生 Android 还是跨端?

请评估并给出推荐:
- **Android 原生 (Kotlin + Jetpack Compose)**: 性能最好, 但开发成本高
- **跨端方案 (Flutter / React Native / .NET MAUI)**: 开发效率高, 但需要与原生 ADB/USB 层互操作
- **混合方案**: UI 跨端 + NDK/原生库做 ADB 层

特别需要回答: 在 Android 上调用 adb 二进制、操作 USB OTG、绑定 Shizuku/Dhizuku 服务时, 跨端框架能否顺畅桥接?

### Part 2: 屏幕镜像与控制 — Android 上如何实现 scrcpy 同等体验?

这是最核心也最困难的部分。请对以下路径逐一分析可行性:

#### 路径 A: 移植 scrcpy 核心 (libscrcpy) 到 Android
- scrcpy 的 `server` 端 Java 代码已经能在 Android 上跑 (Akasha/Dhizuku 就是这么做的)
- 但 **client 端** (视频解码 + 触摸注入 + UI) 原本是 C + SDL, 移植到 Android 需要:
  - C 层通过 NDK 引入 (libusb 替代 USB socket, libavcodec/MediaCodec 解码)
  - Java/Kotlin UI 层做 SurfaceView 渲染 + 手势事件到 scrcpy protocol 的转换
- **关键问题**: 有现成的开源项目已经做过这件事吗? 推荐优先找而非从零写。

**已知相关项目 (请评估)**:
- [scrcpy-client](https://github.com/scrcpy/scrcpy) — 官方 scrcpy client, 纯 C/SDL
- [QtScrcpy](https://github.com/barry-ran/QtScrcpy) — Qt 实现的 client, 可能更容易移植
- [android-client-scrcpy](https://github.com/NetrisTV/ws-scrcpy) — Web 版 scrcpy (ws-scrcpy), 有 Android client 吗?
- [guiscrcpy](https://github.com/srevinsaju/guiscrcpy) — Python UI, 但 client 核心可以参考

#### 路径 B: 目标设备侧推流 + 主控端拉流
- 在 **目标 Android** 上通过 Shizuku/Dhizuku 权限, 用 VirtualDisplay 或 MediaProjection 编码推流
- 主控端接收 H.264/H.265 流, 用 MediaCodec 解码渲染
- 触摸事件通过 adb shell input 或 Shizuku 的 input dispatch 反向注入
- **优点**: 不需要 scrcpy 协议适配, 协议可以自己定
- **缺点**: 目标设备侧需要安装/注入推流服务

#### 路径 C: 纯 adb fallback 方案 (最低优先级)
- `adb exec-out screencap -p` 持续截屏 + `adb shell input` 注入事件
- **优点**: 零依赖, 任何 Android 都能用
- **缺点**: 延迟高 (1-2 秒), CPU 占用大, 仅适合最低限度操作

### Part 3: ADB 设备发现与连接

需要覆盖三种连接模式:

1. **WiFi TCP**: 
   - adb connect <ip>:<port>
   - Android 11+ Wireless Debugging 的 adb pair 流程 (需要配对码)
   - mDNS 服务发现 (`adb mdns services`)
   - 如何在 Android 应用里跑 adb 二进制? 需要 root 吗? 还是可以用 Java 实现 ADB 协议?

2. **USB OTG**:
   - Android 作为 USB host 连接另一台 Android
   - 需要用 `android.hardware.usb.UsbManager` 发现设备
   - 然后在 USB 层跑 ADB 协议 (Android Open Accessory Protocol?)
   - 参考: 有现成的 Java ADB 协议实现吗?

3. **Shizuku/Dhizuku 辅助**:
   - 如果目标设备安装了 Shizuku/Dhizuku, 可以通过它触发 `adb tcpip 5555`
   - 也可以直接通过 Dhizuku shell 服务执行命令, 绕过 ADB 连接

**关键问题**: 在 Android 上跑 adb 二进制需要什么权限? 无 root 情况下可行吗?

### Part 4: 文件传输与相册管理

Android 端不能直接调 adb 二进制 (不像 PC 端), 替代方案:
- 用 **Java 实现的 ADB 客户端库** 跑 `adb pull`/`adb push`/`shell ls`/`content query` 的等价命令
- 有哪些成熟的 Java/Kotlin ADB 客户端库? (如 `com.android.tools.ddmlib`、`adb-java-client`)
- MediaProvider (DISK mount) 在 Android 13+ 的权限模型变化, Shizuku/Dhizuku 能否绕过?
- 缩略图生成: `ContentResolver.loadThumbnail()` vs 自己解码 `MediaStore.Images.Thumbnails`

### Part 5: 整体架构建议

请给出:
1. **推荐的技术栈** (原生/跨端/混合) 及理由
2. **项目模块划分** (UI 层 / ADB 协议层 / 屏幕流层 / 文件传输层 / 权限层)
3. **与现有仓库的集成方式** (如何复用 common/sdks、common/scripts、是否需要新建 common/sdks/adb-java 等)
4. **开发路线图** — 分阶段 MVP, 先做什么后做什么
5. **主要风险点** 及缓解策略

---

## 补充上下文 (来自现有代码)

### PC 端 AdbHelper.cs 设计思路
```
AdbHelper.RunCommandAsync() -> Process.Start("adb.exe", args)
AdbHelper.GetDevicesAsync() -> "adb devices -l" + "adb mdns services" + USB→TCP 自动转换
AdbHelper.ConnectTcp()      -> "adb tcpip 5555" + "adb connect"
```

### PC 端 DeviceIoScheduler.cs 设计思路
```
每设备限流:
  Metadata (ls/content query): SemaphoreSlim(2)
  Transfer (push/pull):        USB=2, TCP=1
  ThumbBatch (缩略图):         USB=2, TCP=1
```

### PC 端 GalleryRepository.cs 设计思路
```
1. ContentResolver.query(MediaStore.Images) 获取相册列表
2. DeviceIoScheduler.ThumbBatch 并发拉取缩略图
3. DeviceIoScheduler.Transfer 拉取原图 (增量同步)
4. Android 10+ 分区存储下用 MediaProvider DISK mount 绕过
```

### akasha-android (Android shell 服务) 能力
- AIDL 接口 `IAkashaShell`, 绑定 Dhizuku 权限
- 可以在目标设备侧执行任意 shell 命令, 包括 `input`、`screencap`、`dumpsys`
- 这个服务可以作为"路径 B 目标设备侧"的基础组件

---

## 输出格式要求

请以 Markdown 格式输出, 每个 Part 用 `## Part N` 标题, 推荐结论用 **加粗** 标出, 风险点用 ⚠️ 标记。每个技术方案给出:
- 可行性评级 (⭐⭐⭐⭐⭐ ~ ⭐)
- 优点 / 缺点
- 推荐程度
- 具体开源项目链接 (如果有的话)
