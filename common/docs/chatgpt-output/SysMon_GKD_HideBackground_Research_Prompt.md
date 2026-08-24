# 调研任务：GKD 如何实现"隐藏后台"功能

## 背景

我正在开发一个 Android 系统监控应用（SysMon），使用悬浮窗（TYPE_APPLICATION_OVERLAY）常驻显示 CPU/GPU/电池等实时数据。应用需要常驻后台运行，用户希望增加一个"隐藏后台"开关，让应用在后台运行时尽量"隐形"（不引起注意、不占最近任务列表、截屏/录屏不暴露内容）。

我了解到 GKD（搞快点，github.com/gkd-kit/gkd）是一个开源的 Android 无障碍自动化应用，它实现了类似的"隐藏后台"能力。请帮我调研 GKD 是如何实现的。

## 目标设备环境

- 华为 Enjoy 50Z（EVE-AL00），HarmonyOS 2.0（AOSP 10 基础，API 29）
- SELinux enforcing，无 root
- 应用通过 Shizuku/Dhizuku 获取 shell 权限，有悬浮窗权限
- 兼容 Android 10–16

## 需要调研的具体问题

1. **GKD 的"隐藏后台"具体指什么？** 是隐藏最近任务列表（recents）中的卡片？还是隐藏应用图标？还是让应用在后台不显示任何通知/痕迹？请给出 GKD 源码中对应的实现位置和代码。

2. **隐藏最近任务列表的实现方式**：
   - `FLAG_SECURE` 窗口标志的作用和限制（是否只隐藏截图预览？）
   - `Activity.setRecentsScreenshotEnabled(false)`（API 28+）的作用
   - `android:excludeFromRecents="true"` 清单属性的作用
   - 是否有办法让应用完全不出现在最近任务列表，同时还能正常返回应用？
   - GKD 用的是哪种组合方案？

3. **隐藏后台后如何保持悬浮窗/服务运行**：
   - 前台服务（Foreground Service）是否必须保留通知？如何最小化通知的存在感（低优先级、隐藏图标）？
   - 隐藏后台后应用进程被杀的风险，如何保活（START_STICKY、WorkManager、AlarmManager 等）？
   - GKD 在隐藏后台时如何维持无障碍服务和悬浮窗？

4. **截屏/录屏保护**：
   - `FLAG_SECURE` 对悬浮窗和 Activity 分别如何设置？
   - 悬浮窗加 FLAG_SECURE 后是否影响正常使用？
   - 华为/HarmonyOS 上 FLAG_SECURE 是否有特殊行为？

5. **Android 10–16 的兼容性差异**：
   - 各版本对 `setRecentsScreenshotEnabled`、`excludeFromRecents`、后台启动限制（background activity start restrictions）的差异
   - Android 10 上隐藏后台的可行方案

## 输出要求

- 给出 GKD 源码中隐藏后台功能的具体文件路径和关键代码片段
- 给出一个针对 Android 10–16 的"隐藏后台"推荐实现方案（代码级）
- 说明各方案的优缺点和兼容性风险
- 如果 GKD 的实现不适用于我的场景，请给出替代方案
