# AdbManager 安卓多开扩展屏：精炼架构与实现指南

> 项目：C# WinForms `AdbManager`
>
> 目标：通过 ADB + scrcpy，在 Windows 上同时打开、显示和控制同一台 Android 设备上的多个 App 窗口，效果接近“小米妙享桌面”的“独立 App 窗口”。
>
> 设计优先级：**实现简单 > 稳定不崩 > 低维护成本 > 扩展性 > 高性能**
>
> 推荐基线：**scrcpy 4.1**。`--new-display` 自 scrcpy 3.0 已引入；scrcpy 4.0 增加 `--flex-display`。

---

# 1. 最重要的结论

## 1.1 不要自己先造 VirtualDisplay，再交给 scrcpy

第一版最省事、最稳的做法不是：

```text
AdbManager
  ↓
ADB 创建 VirtualDisplay
  ↓
解析 displayId
  ↓
am start --display ...
  ↓
scrcpy --display-id ...
```

而应该直接：

```text
AdbManager
  ↓
启动一个 scrcpy 进程
  ↓
scrcpy --new-display --start-app=<package>
  ↓
scrcpy 自己创建 VirtualDisplay
  ↓
scrcpy 自己启动 App
  ↓
scrcpy 自己负责输入、编码、显示和清理
```

每个“扩展 App 窗口”就是一个独立 scrcpy session：

```text
WeChat session
  └─ scrcpy process
     └─ VirtualDisplay #17

Browser session
  └─ scrcpy process
     └─ VirtualDisplay #23
```

这样 AdbManager 不需要自己实现 Android 端 VirtualDisplay Server。

## 1.2 ADB 没有稳定通用的 create/delete VirtualDisplay 命令

以下能力是合理、稳定的：

```bash
adb shell dumpsys display
scrcpy --list-displays
adb shell am start --display <ID> ...
```

但是不要设计并不存在的通用生产接口：

```bash
adb shell display create ...
adb shell display delete ...
```

scrcpy 的 `--new-display` 是由 scrcpy server 运行在 shell 身份下调用 Android DisplayManager/内部能力创建，因此 VirtualDisplay 生命周期最好直接绑定 scrcpy process。

## 1.3 `--new-display` 不是 scrcpy 3.3 才支持

```text
scrcpy 3.0
  → 引入 new virtual display

scrcpy 3.1
  → --no-vd-destroy-content 等改进

scrcpy 3.3
  → Android 15 虚拟显示 UHID 鼠标等改进

scrcpy 4.0
  → --flex-display

scrcpy 4.1
  → 当前推荐基线
```

新项目应直接针对较新的 scrcpy 版本做能力探测与测试。

---

# 2. 推荐最终架构

```text
┌──────────────────────────────────────┐
│               WinForms UI            │
│                                      │
│ Device Page                          │
│   ├─ App List                        │
│   ├─ Open Extended Window            │
│   └─ Active Sessions                 │
└──────────────────┬───────────────────┘
                   │
                   ▼
┌──────────────────────────────────────┐
│       ExtendedDisplayManager         │
│                                      │
│ Dictionary<Guid, ScrcpySession>      │
└──────────────┬───────────────┬───────┘
               │               │
               ▼               ▼
       ScrcpySession      ScrcpySession
               │               │
               ▼               ▼
          scrcpy.exe        scrcpy.exe
               │               │
               ▼               ▼
        VirtualDisplay A  VirtualDisplay B
               │               │
               ▼               ▼
             App A            App B
```

另外单独保留：

```text
AdbService
```

用于：

- `adb devices`
- 设备连接/断开
- 安装 App
- 文件操作
- package 查询
- `dumpsys`
- 诊断
- 手工 `am start --display`

不要让每个 ScrcpySession 自己管理 `adb connect`。

---

# 3. 第一版功能边界

建议第一版只实现：

```text
选择设备
  ↓
读取 App 列表
  ↓
选择 App
  ↓
选择分辨率 / DPI
  ↓
Open in Extended Window
  ↓
启动一个 scrcpy --new-display --start-app
```

支持：

- 多个 App 同时打开；
- 每个 App 独立窗口；
- 单独关闭；
- 全部关闭；
- 保存窗口位置；
- 可选手机本体熄屏；
- 可选关闭扩展窗口后保留 App。

第一版不要做：

```text
把 scrcpy SDL 窗口 SetParent 到 WinForms Panel
```

先让 scrcpy 保持独立顶层窗口，AdbManager 只管理其生命周期与位置。

---

# 4. 推荐 scrcpy 命令

## 4.1 最简版本

```bash
scrcpy -s SERIAL \
  --new-display=1200x1920/320 \
  --start-app=com.example.app
```

## 4.2 更适合 AdbManager 的生产命令

```bash
scrcpy -s SERIAL \
  --new-display=1200x1920/320 \
  --start-app=com.example.app \
  --no-vd-system-decorations \
  --display-ime-policy=local \
  --window-title="AdbManager - Example" \
  --window-width=600 \
  --window-height=960 \
  --no-audio
```

### `--no-vd-system-decorations`

如果目标是“一 App 一显示器”，建议默认使用：

```text
--no-vd-system-decorations
--start-app=<package>
```

这样可减少：

- OEM secondary launcher 问题；
- 虚拟导航栏异常；
- SystemUI 装饰异常；
- 厂商桌面模式冲突。

### `--display-ime-policy=local`

尽量让 IME 与当前 VirtualDisplay 对应，避免软键盘跑到主屏 display 0。

### `--window-title`

强烈建议为每个 Session 设置唯一标题，例如：

```text
AdbManager | Mate50 | WeChat
AdbManager | Xiaomi15 | Chrome
```

后续 Win32 窗口管理、日志排查和用户识别都会更容易。

---

# 5. 动态缩放：scrcpy 4.0+

如果希望 Windows 窗口大小改变时，Android VirtualDisplay 的逻辑尺寸跟着变化：

```bash
scrcpy -s SERIAL \
  --new-display=1280x960/200 \
  --start-app=com.android.settings \
  --flex-display
```

短参数：

```bash
-x
```

建议第一版：

```text
默认关闭 flex-display
```

高级设置再提供：

```text
[ ] 窗口大小同步到 Android 显示尺寸
```

原因：

- 固定尺寸兼容性更好；
- Activity configuration change 更少；
- 游戏/视频重布局更少；
- 某些 ROM resize VD 会导致 Activity recreate。

---

# 6. 手机上熄屏

推荐：

```bash
scrcpy -s SERIAL \
  --new-display=1200x1920/320 \
  --start-app=com.example.app \
  --turn-screen-off \
  --stay-awake
```

短写：

```text
-S
-w
```

不要使用：

```bash
settings put system screen_off_timeout -1
```

作为主实现。`screen_off_timeout` 是超时配置，不是“关闭物理屏但保持 VirtualDisplay 正常工作”的专用接口，而且厂商对特殊值行为不统一。

Android 15+ 还可能支持：

```bash
adb shell cmd display power-off 0
adb shell cmd display power-on 0
```

但 Session 级功能仍优先用 scrcpy 自己的 `--turn-screen-off`，因为它会参与自身清理与恢复。

必须准备 fallback：某些 OEM 上物理屏熄灭或锁屏后，VirtualDisplay 会失去输入能力。因此产品文案应是：

```text
投屏时尝试关闭手机屏幕
```

而不是承诺所有设备都可完全后台工作。

---

# 7. 如何列出显示器

首选诊断命令：

```bash
scrcpy -s SERIAL --list-displays
```

输出类似：

```text
List of displays:
    --display-id=0    (1080x2400)
    --display-id=17   (1200x1920)
```

也可以：

```bash
adb -s SERIAL shell dumpsys display
```

但正常运行时不要频繁 `--list-displays` 或 `dumpsys display`。

AdbManager 自己创建了所有 Session，应维护：

```text
Session → DisplayId
```

当前 scrcpy server 创建成功时会记录类似：

```text
New display: 1200x1920/320 (id=17)
```

所以可以从**该 Session 自己的 stderr**提取 `displayId`，比“启动前 list + 启动后 list + 集合 diff”更可靠。

注意：日志格式不是永久 machine API。因此第一版建议固定支持经过测试的 scrcpy 版本（例如 4.1.x）。如果以后 DisplayId 成为关键协议字段，再 fork scrcpy 增加 JSON event 输出。

---

# 8. 手工启动 App 到指定 Display

如果已经知道 `displayId=17`：

```bash
adb -s SERIAL shell am start --display 17 \
  -n com.example.app/.MainActivity
```

AOSP 的 `am start --display` 最终会通过 `ActivityOptions.setLaunchDisplayId()` 指定目标 display。

不过正常 AdbManager 流程更推荐直接：

```bash
scrcpy --new-display --start-app=<package>
```

避免再额外解析 launcher Activity。

---

# 9. Secondary Display Capability

设备应支持：

```text
android.software.activities_on_secondary_displays
```

检查：

```bash
adb -s SERIAL shell pm list features
```

Windows 可：

```bat
adb -s SERIAL shell pm list features | findstr secondary_displays
```

如果没有：

```text
Extended Display = Unsupported
```

不要继续大量重试。

即使 feature 存在，也不保证每个 App 都能在 secondary display 正常运行。可能受以下因素影响：

- App 自身限制；
- Activity trampoline；
- DRM / secure content；
- OEM WindowManager 策略；
- resize 不兼容；
- 游戏输入限制；
- 多实例/Task 逻辑。

因此能力应分为：

```text
Device supports Extended Display
App works on Extended Display
```

---

# 10. “ADB 创建/删除 VirtualDisplay”正确处理

## 生产方案

创建：

```text
Start scrcpy --new-display
```

删除：

```text
关闭对应 scrcpy process
```

VirtualDisplay 随 scrcpy server/session 释放。

## 调试用 Overlay Display

某些 Android 构建可通过开发者“模拟辅助显示设备”机制：

```bash
adb shell settings put global overlay_display_devices 1920x1080/240
```

清除：

```bash
adb shell settings delete global overlay_display_devices
```

但这应只放在：

```text
Diagnostics → Simulate Secondary Display
```

不要做正式扩展屏后端，因为它和 developer option / SystemServer overlay display 强耦合，ROM 差异大，也不适合多 Session 生命周期。

---

# 11. Android / ROM 兼容策略

## Huawei Android 10 / EMUI

scrcpy 当前 server 会接受 Android 10 的 `--new-display`，但 Android 10 上存在“VirtualDisplay 能创建、普通 App 却无法可靠在该显示器启动”的实际兼容问题。

建议：

```text
Huawei Android 10
→ Experimental / Low confidence
```

失败时直接 fallback 到普通 scrcpy 主屏镜像，不要为 Android 10 写大量特殊分支。

## Huawei Android 12 / HarmonyOS

比 Android 10 更值得尝试，但必须 runtime probe：

```text
secondary display feature
↓
--new-display
↓
--start-app=com.android.settings
↓
首帧是否出现
↓
鼠标是否可点
↓
键盘是否可输入
↓
关闭是否正确清理
```

通过后才把该设备标记为 Supported。

## Xiaomi Android 16 / HyperOS

推荐直接使用 scrcpy 4.1。

Xiaomi 官方 Android 16 适配文档表明 HyperOS 自己的工作台/无极窗口覆盖并屏蔽 Google 原生 Desktop Windowing，因此：

```text
不要依赖 Android 16 原生 Desktop Windowing
```

AdbManager 仍采用自己的：

```text
scrcpy --new-display
--no-vd-system-decorations
--start-app
```

路线。

HyperOS 屏蔽 Google Desktop Windowing **不等于**屏蔽 VirtualDisplay API，所以仍应以 runtime probe 为准。

## 推荐兼容矩阵

| 环境 | `--new-display` | 推荐级别 |
|---|---|---|
| Android 9 及以下 | 不支持 | ❌ |
| Android 10 | 能尝试，但实际 App secondary-display 兼容差 | ⚠ |
| Android 11 | 可尝试，ROM 差异明显 | ⚠ |
| Android 12–14 | 推荐测试区间 | ✅/⚠ |
| Android 15 | 推荐新 scrcpy | ✅/⚠ |
| Android 16 | 用 scrcpy 4.x，必须 runtime probe | ✅/⚠ |
| Huawei EMUI/HarmonyOS | vendor policy 重 | runtime probe |
| Xiaomi HyperOS | vendor window manager 重 | runtime probe |

不需要 root；需要已授权的 ADB shell。

---

# 12. 推荐 Capability Probe

设备第一次开启扩展屏时执行一次：

```text
PROBE_START
   ↓
SDK >= 29 ?
   ├─ no → unsupported
   ↓
secondary display feature ?
   ├─ no → unsupported
   ↓
start scrcpy:
   --new-display=800x1280/240
   --start-app=com.android.settings
   --no-vd-system-decorations
   ↓
virtual display created ?
   ├─ no → unsupported/vendor blocked
   ↓
first frame / session alive ?
   ├─ no → capture problem
   ↓
SUPPORTED
```

缓存模型：

```csharp
public sealed class DeviceCapabilities
{
    public bool SecondaryDisplayFeature { get; init; }
    public bool ScrcpyVirtualDisplayWorks { get; init; }
    public bool TouchOnVirtualDisplayWorks { get; init; }
    public bool ScreenOffWhileVirtualDisplayWorks { get; init; }
}
```

缓存 key 建议：

```text
device serial
+ ro.build.fingerprint
+ scrcpy version
```

ROM 更新或 scrcpy 更新后重新 probe。

---

# 13. WinForms UI 建议

设备页面：

```text
┌─────────────────────────────────────┐
│ Device: HUAWEI xxx                  │
│ Android 12                          │
│ Extended Display: Supported         │
├─────────────────────────────────────┤
│ Apps                                │
│ [Search...]                         │
│                                     │
│ Chrome                   [Open ▸]   │
│ WeChat                   [Open ▸]   │
│ Settings                 [Open ▸]   │
├─────────────────────────────────────┤
│ Active Extended Windows             │
│                                     │
│ Chrome   1200×1920   Running [Close]│
│ WeChat   1200×1920   Running [Close]│
└─────────────────────────────────────┘
```

配置窗口只暴露高价值选项：

```text
App:
  com.example.app

Resolution:
  Auto
  720×1280
  1080×1920
  1200×1920
  Custom

DPI:
  Auto
  240
  320
  420
  Custom

[ ] 窗口大小动态改变 Android Display
[ ] 手机物理屏熄灭
[ ] 关闭扩展屏后将 App 保留到主屏
[ ] 启用声音

                    [Open]
```

Codec、Bitrate、FPS、Keyboard、Mouse、IME policy 放到“高级”。

---

# 14. C# 数据模型

```csharp
public sealed class ExtendedDisplayOptions
{
    public required string DeviceSerial { get; init; }
    public required string PackageName { get; init; }

    public int? Width { get; init; }
    public int? Height { get; init; }
    public int? Dpi { get; init; }

    public bool FlexDisplay { get; init; }
    public bool TurnPhysicalScreenOff { get; init; }
    public bool PreserveAppOnClose { get; init; }
    public bool Audio { get; init; }

    public string? WindowTitle { get; init; }

    public int? WindowX { get; init; }
    public int? WindowY { get; init; }
    public int? WindowWidth { get; init; }
    public int? WindowHeight { get; init; }
}
```

---

# 15. 不要手工拼未转义参数字符串

不推荐：

```csharp
var arguments = $"-s {deviceId} --start-app={package}";
```

现代 .NET 优先使用：

```csharp
ProcessStartInfo.ArgumentList
```

这样窗口标题、路径和特殊字符不会形成 quoting bug。

---

# 16. ScrcpyCommandBuilder

```csharp
public static class ScrcpyCommandBuilder
{
    public static ProcessStartInfo Build(
        string scrcpyPath,
        ExtendedDisplayOptions o)
    {
        var psi = new ProcessStartInfo
        {
            FileName = scrcpyPath,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            WorkingDirectory = Path.GetDirectoryName(scrcpyPath)!
        };

        psi.ArgumentList.Add("-s");
        psi.ArgumentList.Add(o.DeviceSerial);

        psi.ArgumentList.Add(BuildNewDisplayArgument(o));
        psi.ArgumentList.Add("--start-app=" + o.PackageName);

        psi.ArgumentList.Add("--no-vd-system-decorations");
        psi.ArgumentList.Add("--display-ime-policy=local");

        if (!o.Audio)
            psi.ArgumentList.Add("--no-audio");

        if (o.FlexDisplay)
            psi.ArgumentList.Add("--flex-display");

        if (o.TurnPhysicalScreenOff)
        {
            psi.ArgumentList.Add("--turn-screen-off");
            psi.ArgumentList.Add("--stay-awake");
        }

        if (o.PreserveAppOnClose)
            psi.ArgumentList.Add("--no-vd-destroy-content");

        if (!string.IsNullOrWhiteSpace(o.WindowTitle))
            psi.ArgumentList.Add("--window-title=" + o.WindowTitle);

        AddInt(psi, "--window-x=", o.WindowX);
        AddInt(psi, "--window-y=", o.WindowY);
        AddInt(psi, "--window-width=", o.WindowWidth);
        AddInt(psi, "--window-height=", o.WindowHeight);

        return psi;
    }

    private static string BuildNewDisplayArgument(
        ExtendedDisplayOptions o)
    {
        if (o.Width is null || o.Height is null)
        {
            return o.Dpi is null
                ? "--new-display"
                : $"--new-display=/{o.Dpi.Value}";
        }

        if (o.Dpi is null)
            return $"--new-display={o.Width.Value}x{o.Height.Value}";

        return $"--new-display={o.Width.Value}x{o.Height.Value}/{o.Dpi.Value}";
    }

    private static void AddInt(
        ProcessStartInfo psi,
        string prefix,
        int? value)
    {
        if (value.HasValue)
            psi.ArgumentList.Add(prefix + value.Value);
    }
}
```

如果项目还是传统 .NET Framework、没有 `ArgumentList`，再单独实现一个严格的 Windows command-line quote helper；不要把两套兼容代码混进核心 Session 类。

---

# 17. ScrcpySession

一个 Session 对应：

```text
一个 scrcpy.exe
+
一个 VirtualDisplay
+
一个 Android App Window
```

```csharp
public enum ScrcpySessionState
{
    Starting,
    Running,
    Stopping,
    Exited,
    Failed
}
```

实现：

```csharp
using System.Diagnostics;
using System.Text.RegularExpressions;

public sealed class ScrcpySession : IDisposable
{
    private static readonly Regex NewDisplayRegex =
        new(
            @"New display:\s+\d+x\d+/\d+\s+\(id=(\d+)\)",
            RegexOptions.Compiled);

    private readonly Process _process;

    private readonly TaskCompletionSource<int> _displayIdTcs =
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    public Guid Id { get; } = Guid.NewGuid();

    public string DeviceSerial { get; }
    public string PackageName { get; }

    public int? DisplayId { get; private set; }
    public ScrcpySessionState State { get; private set; }

    public int ProcessId => _process.Id;

    public event Action<string>? LogReceived;
    public event Action<ScrcpySession>? Exited;

    public ScrcpySession(
        ProcessStartInfo startInfo,
        string deviceSerial,
        string packageName)
    {
        DeviceSerial = deviceSerial;
        PackageName = packageName;

        _process = new Process
        {
            StartInfo = startInfo,
            EnableRaisingEvents = true
        };

        _process.OutputDataReceived += OnOutput;
        _process.ErrorDataReceived += OnOutput;

        _process.Exited += (_, _) =>
        {
            State = _process.ExitCode == 0
                ? ScrcpySessionState.Exited
                : ScrcpySessionState.Failed;

            if (!_displayIdTcs.Task.IsCompleted)
            {
                _displayIdTcs.TrySetException(
                    new InvalidOperationException(
                        "scrcpy exited before VirtualDisplay was created."));
            }

            Exited?.Invoke(this);
        };
    }

    public void Start()
    {
        State = ScrcpySessionState.Starting;

        if (!_process.Start())
            throw new InvalidOperationException("Could not start scrcpy.");

        _process.BeginOutputReadLine();
        _process.BeginErrorReadLine();
    }

    public async Task<int> WaitForDisplayIdAsync(
        CancellationToken cancellationToken)
    {
        return await _displayIdTcs.Task.WaitAsync(cancellationToken);
    }

    private void OnOutput(object sender, DataReceivedEventArgs e)
    {
        if (string.IsNullOrEmpty(e.Data))
            return;

        LogReceived?.Invoke(e.Data);

        var match = NewDisplayRegex.Match(e.Data);

        if (match.Success &&
            int.TryParse(match.Groups[1].Value, out int displayId))
        {
            DisplayId = displayId;
            State = ScrcpySessionState.Running;
            _displayIdTcs.TrySetResult(displayId);
        }
    }

    public async Task StopAsync()
    {
        if (_process.HasExited)
            return;

        State = ScrcpySessionState.Stopping;

        if (_process.CloseMainWindow())
        {
            try
            {
                using var timeout =
                    new CancellationTokenSource(TimeSpan.FromSeconds(2));

                await _process.WaitForExitAsync(timeout.Token);
                return;
            }
            catch (OperationCanceledException)
            {
                // fallback below
            }
        }

        if (!_process.HasExited)
        {
            _process.Kill(entireProcessTree: true);
            await _process.WaitForExitAsync();
        }
    }

    public void Dispose()
    {
        _process.Dispose();
    }
}
```

关闭策略：

```text
CloseMainWindow
↓
等待正常退出
↓
必要时 Kill process tree
```

这样正常情况下 scrcpy 可以执行自己的清理；异常时又不会留僵尸进程。

---

# 18. ExtendedDisplayManager

```csharp
using System.Collections.Concurrent;

public sealed class ExtendedDisplayManager
{
    private readonly ConcurrentDictionary<Guid, ScrcpySession>
        _sessions = new();

    private readonly string _scrcpyPath;

    public ExtendedDisplayManager(string scrcpyPath)
    {
        _scrcpyPath = scrcpyPath;
    }

    public IReadOnlyCollection<ScrcpySession> Sessions =>
        _sessions.Values.ToArray();

    public async Task<ScrcpySession> StartAsync(
        ExtendedDisplayOptions options,
        CancellationToken cancellationToken)
    {
        var psi = ScrcpyCommandBuilder.Build(_scrcpyPath, options);

        var session = new ScrcpySession(
            psi,
            options.DeviceSerial,
            options.PackageName);

        session.Exited += OnSessionExited;

        if (!_sessions.TryAdd(session.Id, session))
        {
            session.Dispose();
            throw new InvalidOperationException("Failed to register scrcpy session.");
        }

        try
        {
            session.Start();
            await session.WaitForDisplayIdAsync(cancellationToken);
            return session;
        }
        catch
        {
            _sessions.TryRemove(session.Id, out _);

            try
            {
                await session.StopAsync();
            }
            catch
            {
                // cleanup best-effort
            }

            session.Dispose();
            throw;
        }
    }

    public async Task StopAsync(Guid sessionId)
    {
        if (!_sessions.TryRemove(sessionId, out var session))
            return;

        try
        {
            await session.StopAsync();
        }
        finally
        {
            session.Dispose();
        }
    }

    public async Task StopAllAsync()
    {
        var sessions = _sessions.Values.ToArray();
        _sessions.Clear();

        await Task.WhenAll(
            sessions.Select(async session =>
            {
                try
                {
                    await session.StopAsync();
                }
                finally
                {
                    session.Dispose();
                }
            }));
    }

    private void OnSessionExited(ScrcpySession session)
    {
        _sessions.TryRemove(session.Id, out _);
    }
}
```

---

# 19. WinForms 调用

```csharp
private async void btnOpenExtended_Click(
    object sender,
    EventArgs e)
{
    btnOpenExtended.Enabled = false;

    try
    {
        using var timeout =
            new CancellationTokenSource(TimeSpan.FromSeconds(12));

        var session =
            await _extendedDisplayManager.StartAsync(
                new ExtendedDisplayOptions
                {
                    DeviceSerial = _selectedDevice.Serial,
                    PackageName = _selectedApp.PackageName,

                    Width = 1200,
                    Height = 1920,
                    Dpi = 320,

                    Audio = false,
                    FlexDisplay = false,
                    TurnPhysicalScreenOff = true,
                    PreserveAppOnClose = true,

                    WindowTitle = $"AdbManager | {_selectedApp.Name}",
                    WindowWidth = 600,
                    WindowHeight = 960
                },
                timeout.Token);

        AddSessionToUi(session);
    }
    catch (OperationCanceledException)
    {
        MessageBox.Show(
            "创建扩展显示超时。",
            "AdbManager",
            MessageBoxButtons.OK,
            MessageBoxIcon.Warning);
    }
    catch (Exception ex)
    {
        MessageBox.Show(
            ex.Message,
            "启动失败",
            MessageBoxButtons.OK,
            MessageBoxIcon.Error);
    }
    finally
    {
        btnOpenExtended.Enabled = true;
    }
}
```

`Process.ErrorDataReceived` 回调不保证处于 UI thread，所以日志更新要 `BeginInvoke()`：

```csharp
private void Session_LogReceived(string line)
{
    if (InvokeRequired)
    {
        BeginInvoke(new Action<string>(Session_LogReceived), line);
        return;
    }

    txtLog.AppendText(line + Environment.NewLine);
}
```

---

# 20. 同一 App 能否开多个扩展窗口

这由 App 的 Task/Activity 设计决定。

Android 可能：

- 移动已有 task；
- 新建 task；
- 拒绝第二实例；
- 把旧 task 从 display A 搬到 display B；
- 重新启动 Activity。

因此：

```text
一个 scrcpy session
```

不等于：

```text
一个独立 Android App process / 独立账号实例
```

功能名称应叫：

```text
独立应用窗口
扩展屏窗口
```

不要叫“应用分身”。

---

# 21. `--no-vd-destroy-content`

默认关闭 VirtualDisplay 时，其中运行的 App 内容会被销毁。

如果加入：

```bash
--no-vd-destroy-content
```

Android 会尝试把内容移回主 display。

建议 UI：

```text
关闭扩展窗口后：
( ) 结束该扩展窗口内容
(*) 将 App 保留到手机主屏
```

部分游戏会因 Display configuration 改变而重新启动，这是正常兼容问题。

---

# 22. 多 scrcpy 最重要的坑

不要给任何 Session 使用：

```bash
--kill-adb-on-close
```

否则：

```text
关闭一个 scrcpy
→ adb server 被杀
→ 同设备其它 scrcpy session 全部掉线
```

ADB server 生命周期必须由全局 `AdbService` 管理。

同时，不要让每个 Session 自己执行 `adb connect`。推荐：

```text
DeviceConnectionManager
      ↓
保证 SERIAL 已经出现在 adb devices
      ↓
ScrcpySession
      ↓
scrcpy -s SERIAL ...
```

连接层和显示 Session 层必须分开。

---

# 23. App 列表

如果只需要 packageName，可用：

```bash
adb shell pm list packages -3
```

也可以用：

```bash
scrcpy -s SERIAL --list-apps
```

但后者会启动 scrcpy server，不适合频繁刷新。

建议模型：

```csharp
public sealed class AndroidAppInfo
{
    public required string PackageName { get; init; }
    public string? Label { get; init; }
    public bool IsSystemApp { get; init; }
}
```

`--start-app=<package>` 不要求 AdbManager 必须先解析 launcher Activity，所以初版可以只维护 packageName + label。

---

# 24. Window 布局

scrcpy 原生支持：

```text
--window-x
--window-y
--window-width
--window-height
```

所以第一版可以直接做：

```text
Cascade
Tile Horizontal
Tile Vertical
2×2 Grid
Remember Position
```

而不需要 Win32 `SetParent()`。

---

# 25. 为什么第一版不要嵌进 WinForms Panel

把 scrcpy SDL top-level window 强行 `SetParent()` 到 WinForms Panel 会引入：

- SDL2 / SDL3 差异；
- DPI awareness；
- 焦点；
- IME；
- raw input；
- UHID mouse/keyboard；
- fullscreen；
- resize；
- GPU renderer；
- Alt+Tab；
- window destruction；
- Win32 child/top-level style 冲突。

scrcpy 4.0 已经迁移到 SDL3，这类私有嵌入 hack 的维护风险更高。

如果未来必须做“一个 WinForms 主窗口里多个真正嵌入式视频 Panel”，更合理的路线是：

```text
复用 scrcpy server/protocol
↓
C# 收视频流
↓
FFmpeg/MediaFoundation 解码
↓
Direct3D/WinForms 控件渲染
```

但这会显著增加开发量，不适合第一阶段。

---

# 26. 错误模型

```csharp
public enum ExtendedDisplayFailure
{
    None,

    ScrcpyNotFound,
    DeviceOffline,
    AndroidTooOld,
    SecondaryDisplayUnsupported,

    VirtualDisplayCreateFailed,
    AppLaunchFailed,

    EncoderFailed,
    InputInjectionFailed,

    AdbDisconnected,
    ScrcpyExited,

    Unknown
}
```

解析 scrcpy 日志时进行分类：

```text
Could not create display
→ VirtualDisplayCreateFailed

INJECT_EVENT permission
→ InputInjectionFailed

进程在 New display 之前退出
→ VirtualDisplayCreateFailed / StartupFailure
```

UI 应显示可理解的错误，而不是直接弹 stack trace。

---

# 27. 推荐 Session 状态机

```text
CREATED
   ↓
STARTING_PROCESS
   ↓
CREATING_DISPLAY
   ↓
STARTING_APP
   ↓
RUNNING
   ↓
STOPPING
   ↓
EXITED
```

异常：

```text
STARTING_PROCESS → FAILED
CREATING_DISPLAY → UNSUPPORTED
RUNNING → DISCONNECTED
```

不要把 `Process.HasExited` 直接当成全部业务状态。

ScrcpySession 只负责：

```text
Start scrcpy
Read log
Capture displayId
Track process
Stop scrcpy
```

不负责：

```text
adb connect
设备扫描
App 列表
文件管理
USB detection
Wi-Fi discovery
```

---

# 28. 小米“妙享桌面”是不是 VirtualDisplay

不能直接断言：

```text
妙享桌面 = AOSP VirtualDisplay API
```

公开资料可以确认：

- HyperOS 有自己的跨端协同服务；
- Windows/Mac/iPad 可以操作手机；
- 可独立打开最近 App；
- 可以同时打开多个手机 App 窗口；
- HyperOS 还有自己的工作台/无极窗口；
- Android 16 HyperOS 明确以自家 Windowing 功能覆盖/屏蔽 Google 原生 Desktop Windowing。

因此更合理的技术推断是：

```text
system privilege
+
WindowManager / ActivityTaskManager vendor extensions
+
display/task/window isolation
+
video encode
+
input forwarding
+
Xiaomi proprietary transport
+
cross-device authentication
```

VirtualDisplay 可能是同类底层机制之一，但没有公开资料能证明完整妙享桌面就是标准 VirtualDisplay 的简单封装。

---

# 29. 我们能否开源实现类似体验

可以实现：

```text
PC 上独立 App 窗口
多 App 同时打开
独立输入
物理屏关闭
剪贴板
文件拖放
音视频
窗口定位
```

当前最值得复用的是：

```text
AdbManager
+
scrcpy Multi-Session Manager
```

这已经能实现“核心体验”的大部分，而不需要复刻 OEM system service。

真正与小米系统方案的差距主要在：

- ADB 必须启用；
- OEM system privilege 更高；
- 锁屏/解锁体验；
- DRM；
- App 状态迁移；
- 系统 UI 集成；
- 设备互信与无感发现；
- 厂商 WindowManager 定制。

---

# 30. 开源项目参考

## Genymobile/scrcpy

最重要。重点阅读：

```text
server/.../video/NewDisplayCapture.java
server/.../wrappers/DisplayManager.java
server/.../control/Controller.java
doc/virtual-display.md
doc/device.md
doc/window.md
```

## QtScrcpy

适合参考：

- 多设备 Session；
- GUI 与投屏实例生命周期；
- async architecture；
- ADB/文件/投屏组合式桌面工具。

但 VirtualDisplay 的最终标准仍以 scrcpy upstream/AOSP 为准。

---

# 31. 建议开发顺序

## Phase 1：手工验证三台目标机

```bash
scrcpy -s SERIAL \
  --new-display=800x1280/240 \
  --start-app=com.android.settings \
  --no-vd-system-decorations \
  --no-audio
```

记录：

```text
Display 创建？
Settings 出现？
鼠标可点？
键盘可输入？
屏幕能旋转？
关闭是否清理？
```

## Phase 2：ScrcpySession

只做：

```text
Process start
stderr capture
displayId parsing
stop
```

## Phase 3：MultiSession

实现：

```text
Open A
Open B
Open C
Close B
Close All
```

## Phase 4：App Picker

加入：

```text
App icon
App label
Package
Open button
```

## Phase 5：Window Layout

实现：

```text
cascade
tile horizontal
tile vertical
2×2 grid
remember position
```

## Phase 6：Capability Probe

缓存设备兼容性，避免每次失败后重复重试。

## Phase 7：高级功能

再加入：

```text
--flex-display
--turn-screen-off
audio
IME policy
UHID
preserve on close
```

---

# 32. 推荐默认参数

普通 App 默认：

```bash
scrcpy -s SERIAL \
  --new-display=1080x1920/320 \
  --start-app=PACKAGE \
  --no-vd-system-decorations \
  --display-ime-policy=local \
  --no-audio \
  --no-vd-destroy-content \
  --window-title="AdbManager | APP"
```

用户选择物理屏关闭：

```text
+ --turn-screen-off
+ --stay-awake
```

用户选择动态显示尺寸：

```text
+ --flex-display
```

---

# 33. 最终原则

AdbManager 的“扩展屏”第一版应该本质上是：

```text
Multi Scrcpy Sessions
```

而不是：

```text
自己重写 Android VirtualDisplay Server
```

最终结构：

```text
DeviceConnectionManager
        │
        ├─ AdbService
        │
        └─ ExtendedDisplayManager
                 │
                 ├─ ScrcpySession A
                 │      └─ VD A → App A
                 │
                 ├─ ScrcpySession B
                 │      └─ VD B → App B
                 │
                 └─ ScrcpySession C
                        └─ VD C → App C
```

这是目前最符合：

- 代码量小；
- 稳定；
- 不需要 root；
- 多设备易扩展；
- Android 版本差异可控；
- WinForms 开发成本低；

的方案。

---

# 34. 参考资料

- scrcpy Virtual Display  
  https://github.com/Genymobile/scrcpy/blob/master/doc/virtual-display.md

- scrcpy Device Control  
  https://github.com/Genymobile/scrcpy/blob/master/doc/device.md

- scrcpy Window  
  https://github.com/Genymobile/scrcpy/blob/master/doc/window.md

- scrcpy Releases  
  https://github.com/Genymobile/scrcpy/releases

- scrcpy `NewDisplayCapture.java`  
  https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java

- Android `DisplayManager`  
  https://developer.android.com/reference/android/hardware/display/DisplayManager

- Android `VirtualDisplay`  
  https://developer.android.com/reference/android/hardware/display/VirtualDisplay

- Android `ActivityOptions.setLaunchDisplayId()`  
  https://developer.android.com/reference/android/app/ActivityOptions

- Android `FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS`  
  https://developer.android.com/reference/android/content/pm/PackageManager

- Xiaomi HyperOS 工作台模式适配  
  https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2034

- Xiaomi Android 16 适配指南  
  https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2035

- Xiaomi HyperConnect / 妙享桌面  
  https://os.mi.com/continuity/abilities/ab0019

- QtScrcpy  
  https://github.com/barry-ran/QtScrcpy
