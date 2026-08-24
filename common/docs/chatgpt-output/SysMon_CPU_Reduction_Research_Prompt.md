# 调研任务：悬浮窗模式下如何将应用 CPU 占用降到最低

## 背景

我正在开发一个 Android 系统监控应用（SysMon），使用悬浮窗（TYPE_APPLICATION_OVERLAY）常驻显示 CPU/GPU/电池等实时数据。当前实现存在 CPU 占用偏高的问题，需要调研如何将悬浮窗模式下的 CPU 占用降到最低。

## 当前实现（需要优化的部分）

1. **采样引擎**：单线程 `MonitorEngine`，每 2 秒采样一次，通过 Shizuku/Dhizuku/ADB 通道执行 shell 命令（`cat /proc/stat`、`dumpsys battery`、`dumpsys thermalservice` 等）或使用无权限 API（`TrafficStats`、`BatteryManager`、`/proc/meminfo`）。
2. **悬浮窗更新**：`OverlayService` 用 `Handler.postDelayed` 每 1 秒刷新一次 TextView 内容。
3. **主界面**：`MainActivity` 每 1 秒刷新一次 UI（仅前台可见时）。
4. **无障碍服务**：`SysAccessibilityService` 常驻，用于检测全屏状态。

## 目标设备环境

- 华为 Enjoy 50Z（EVE-AL00），HarmonyOS 2.0（AOSP 10 基础，API 29）
- SELinux enforcing，无 root
- 应用通过 Shizuku/Dhizuku 获取 shell 权限
- 兼容 Android 10–16

## 需要调研的具体问题

1. **悬浮窗 TextView 高频 setText 的 CPU 开销**：
   - 每 1 秒 setText 一次 vs 每 2 秒 vs 每 5 秒，CPU 差异有多大？
   - 是否应该只在内容变化时才 setText？
   - 使用 `TextView` vs 自定义 `View.onDraw()`（Canvas 直接绘制）的 CPU 差异？
   - 悬浮窗窗口本身（透明、无动画）的渲染开销如何最小化？

2. **采样频率与 CPU 占用的权衡**：
   - 系统监控类应用（如 htop、CPU 监控悬浮窗）推荐的采样间隔是多少？
   - 每 2 秒 vs 每 5 秒 vs 每 10 秒采样，CPU 差异？
   - 哪些数据源开销最大（`dumpsys` 系列 vs `/proc` 文件 vs `BatteryManager` API）？
   - 是否应该用"慢数据低频采样 + 快数据高频采样"的分频策略？

3. **shell 命令执行的开销**：
   - 通过 Shizuku 执行 `cat /proc/stat` 等命令的进程创建开销
   - 是否应该用"一次 exec 批量读取多个文件"替代"多次 exec"？
   - `dumpsys battery` vs 直接读 `/sys/class/power_supply` 的开销对比
   - 是否有办法避免每次采样都 spawn 进程（如 Shizuku 的 `readFiles` binder 批量读）？

4. **Handler/线程模型优化**：
   - `Handler.postDelayed` vs `ScheduledExecutorService` vs `HandlerThread` 的 CPU 差异
   - 采样线程的优先级设置（`Process.setThreadPriority`）
   - 是否应该让采样线程在无监听者时休眠（idle）？
   - 前台服务 + 悬浮窗的常驻开销如何最小化？

5. **无障碍服务的开销**：
   - 无障碍服务常驻的 CPU 开销
   - 是否可以在不需要全屏检测时禁用无障碍服务？
   - 无障碍服务的事件监听范围如何最小化（`eventTypes`、`feedbackType`）？

6. **Android 10–16 的省电机制**：
   - Doze 模式、App Standby 对后台采样线程的影响
   - 前台服务在 Doze 下的行为
   - 如何避免被系统判定为"耗电大户"？

## 输出要求

- 给出一个"悬浮窗模式最低 CPU 占用"的完整优化方案（代码级）
- 明确采样频率、更新频率、数据源选择的推荐配置
- 给出优化前后的预期 CPU 占用对比
- 说明各优化点的兼容性风险
