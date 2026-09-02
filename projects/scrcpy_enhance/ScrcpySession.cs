using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace AdbManager;

/// <summary>
/// 全局鼠标钩子：监听对 scrcpy 投屏窗口的点击，点击时执行“点亮手机屏幕”动作。
/// 采用“套壳”思路——不修改 scrcpy 源码，而是在外部捕获点击指令。
/// 点亮动作由调用方注入（物理息屏时是 SF 层恢复/系统点亮，普通睡眠时是 KEYCODE_WAKEUP）。
/// </summary>
public sealed class ScreenWakeHelper : IDisposable
{
    private const int WH_MOUSE_LL = 14;
    private const int WM_LBUTTONDOWN = 0x0201;
    private const int WM_RBUTTONDOWN = 0x0204;
    private const int WM_MBUTTONDOWN = 0x0207;
    private const uint GA_ROOT = 2;

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int X; public int Y; }

    private delegate IntPtr MouseProc(int nCode, IntPtr wParam, IntPtr lParam);
    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")]
    private static extern IntPtr SetWindowsHookEx(int idHook, MouseProc lpfn, IntPtr hMod, uint dwThreadId);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UnhookWindowsHookEx(IntPtr hhk);

    [DllImport("user32.dll")]
    private static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetModuleHandle(string? lpModuleName);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorPos(out POINT lpPoint);

    [DllImport("user32.dll")]
    private static extern IntPtr WindowFromPoint(POINT p);

    [DllImport("user32.dll")]
    private static extern IntPtr GetAncestor(IntPtr hwnd, uint gaFlags);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindow(IntPtr hWnd);

    private readonly string _windowTitle;
    private readonly Func<Task> _wakeAction;
    private readonly MouseProc _proc; // 保持引用，防止被 GC 回收导致钩子崩溃
    private IntPtr _hook = IntPtr.Zero;
    private IntPtr _target = IntPtr.Zero;
    private System.Threading.Timer? _finder;
    private DateTime _lastWake = DateTime.MinValue;
    private bool _disposed;

    public ScreenWakeHelper(string windowTitle, Func<Task> wakeAction)
    {
        _windowTitle = windowTitle;
        _wakeAction = wakeAction;
        _proc = HookCallback;
    }

    /// <summary>必须在 UI 线程调用（钩子回调依赖 UI 线程的消息循环）。</summary>
    public void Start()
    {
        if (_disposed) return;
        _hook = SetWindowsHookEx(WH_MOUSE_LL, _proc, GetModuleHandle(null), 0);
        // scrcpy 窗口在进程启动后才出现，用定时器轮询查找
        _finder = new System.Threading.Timer(_ => TryFindTarget(), null, 0, 500);
    }

    private void TryFindTarget()
    {
        if (_disposed) return;
        if (_target != IntPtr.Zero && IsWindow(_target)) return; // 已找到且仍有效

        IntPtr found = IntPtr.Zero;
        EnumWindows((hwnd, _) =>
        {
            var sb = new StringBuilder(256);
            GetWindowText(hwnd, sb, sb.Capacity);
            var title = sb.ToString();
            // 标题以完整标题开头（兼容 scrcpy 追加分辨率等信息的情况）
            if (title.StartsWith(_windowTitle, StringComparison.Ordinal))
            {
                found = GetAncestor(hwnd, GA_ROOT);
                return false; // 停止枚举
            }
            return true;
        }, IntPtr.Zero);

        if (found != IntPtr.Zero)
            _target = found;
    }

    private IntPtr HookCallback(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0)
        {
            int msg = wParam.ToInt32();
            if (msg == WM_LBUTTONDOWN || msg == WM_RBUTTONDOWN || msg == WM_MBUTTONDOWN)
            {
                var target = _target;
                if (target != IntPtr.Zero && GetCursorPos(out POINT pt))
                {
                    IntPtr top = GetAncestor(WindowFromPoint(pt), GA_ROOT);
                    if (top == target)
                        TryWake();
                }
            }
        }
        // 必须调用 CallNextHookEx，否则会影响系统其它程序的鼠标事件
        return CallNextHookEx(_hook, nCode, wParam, lParam);
    }

    private void TryWake()
    {
        if (_disposed) return;
        var now = DateTime.UtcNow;
        if ((now - _lastWake).TotalMilliseconds < 800) return; // 防抖，避免频繁拉起 adb
        _lastWake = now;
        _ = Task.Run(async () =>
        {
            try { await _wakeAction(); } catch { } // 点亮失败忽略（例如设备临时离线）
        });
    }

    /// <summary>必须在 UI 线程调用。</summary>
    public void Stop()
    {
        _finder?.Dispose();
        _finder = null;
        if (_hook != IntPtr.Zero)
        {
            UnhookWindowsHookEx(_hook);
            _hook = IntPtr.Zero;
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Stop();
    }
}

/// <summary>
/// 管理一次 scrcpy 会话：启动前保存并修改设备设置（常亮 / 隐藏软键盘），
/// 启动 scrcpy，按需挂载“点击唤醒”钩子，退出时恢复设置并清理。
/// </summary>
public sealed class ScrcpySession : IDisposable
{
    // screen_off_timeout 设为极大值（毫秒）≈ 24.8 天，等效“永不黑屏”
    private const string HugeScreenOffTimeout = "2147483647";

    private readonly string _deviceId;
    private readonly ScrcpyOptions _options;
    private readonly SynchronizationContext? _uiContext;

    private Process? _process;
    private ScreenWakeHelper? _wakeHelper;
    private string? _savedScreenOffTimeout;
    private string? _savedStayOn; // 完全断电时钉住的 stay_on_while_plugged_in 原值
    private string? _savedShowImeWithHardKeyboard;
    // 物理息屏（关闭背光路径）需要保存/恢复的亮度设置
    private string? _savedBrightness;
    private string? _savedBrightnessMode;
    private float _restoreSfBrightness = 0.5f; // 点亮时 SF 层恢复到的亮度（按原设置换算）
    private bool _cleanedUp;
    private readonly TaskCompletionSource<bool> _exitedTcs =
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    /// <summary>运行时实际采用的物理息屏路径（StartAsync 完成后可读，用于状态提示与恢复）。</summary>
    public PhysicalOffPath OffPath { get; private set; } = PhysicalOffPath.None;

    public event EventHandler? Exited;

    /// <summary>会话结束（清理完成）时完成，用于在重启前等待旧会话彻底收尾。</summary>
    public Task ExitedAsync => _exitedTcs.Task;

    public bool IsRunning => _process != null && !_process.HasExited;

    /// <summary>本会话对应的设备 ID（用于定位 scrcpy 窗口 / 发送输入等）。</summary>
    public string DeviceId => _deviceId;

    /// <summary>本会话独立的 scrcpy 日志路径（多会话并存时互不串台）。</summary>
    public string? LogPath { get; private set; }

    public ScrcpySession(string deviceId, ScrcpyOptions options)
    {
        _deviceId = deviceId;
        _options = options;
        _uiContext = SynchronizationContext.Current; // 在 UI 线程创建，用于回调切回 UI 线程
    }

    /// <summary>在 UI 线程调用。启动前应用设置、拉起 scrcpy、执行物理息屏、挂载点亮钩子。</summary>
    public async Task StartAsync()
    {
        // 0. 确保 scrcpy 能力与设备显示电源能力已探测（StartScrcpy 与息屏路径据此生成命令）
        var scrcpyCaps = await AdbHelper.DetectScrcpyCapabilitiesAsync();
        var devCaps = await AdbHelper.DetectDeviceDisplayCapsAsync(_deviceId);

        // 1. 钉住系统“亮屏状态”（Extinguish 方案的核心前提，等价于其 keep-screen-on 悬浮窗）：
        //    - scrcpy --stay-awake：充电时防息屏（scrcpy 自动保存/恢复）
        //    - screen_off_timeout 调成极大值：未充电（TCP）时防息屏兜底（USB/TCP 均有效）
        try
        {
            _savedScreenOffTimeout = await AdbHelper.GetSettingAsync(_deviceId, "system", "screen_off_timeout");
            await AdbHelper.SetSettingAsync(_deviceId, "system", "screen_off_timeout", HugeScreenOffTimeout);
        }
        catch { }

        // 1b. 请求完全断电时额外钉住"充电常亮"：
        //     实测面板被外部断电后，系统会在 ~3 秒内进入 Doze（screen_off_timeout 拦不住），
        //     stay_on_while_plugged_in=7 是充电时的 keep-screen-on 机制（等效 Extinguish 悬浮窗的作用）。
        if (_options.PhysicalScreenOff && _options.OffScheme == PhysicalOffScheme.PowerOff)
        {
            try
            {
                _savedStayOn = await AdbHelper.GetSettingAsync(_deviceId, "global", "stay_on_while_plugged_in");
                await AdbHelper.SetSettingAsync(_deviceId, "global", "stay_on_while_plugged_in", "7");
            }
            catch { }
        }

        // 2. 键盘相关设置：
        //    - UHID：隐藏手机软键盘，让电脑键盘成为唯一输入源
        //    - SDK：允许软键盘弹出（用户用电脑输入拼音，手机弹出中文输入法选字）
        if (_options.KeyboardMode == KeyboardMode.Uhid)
        {
            try
            {
                _savedShowImeWithHardKeyboard = await AdbHelper.GetSettingAsync(_deviceId, "secure", "show_ime_with_hard_keyboard");
                await AdbHelper.SetSettingAsync(_deviceId, "secure", "show_ime_with_hard_keyboard", "0");
            }
            catch { }
        }
        else if (_options.KeyboardMode == KeyboardMode.Sdk)
        {
            try
            {
                _savedShowImeWithHardKeyboard = await AdbHelper.GetSettingAsync(_deviceId, "secure", "show_ime_with_hard_keyboard");
                await AdbHelper.SetSettingAsync(_deviceId, "secure", "show_ime_with_hard_keyboard", "1");
            }
            catch { }
        }

        // 3. 启动 scrcpy（本会话独立日志路径，多会话并存互不串台；scrcpy 默认启动时会点亮设备，
        //    所以物理息屏必须在其启动之后执行，顺序不能颠倒）
        LogPath = AdbHelper.GetScrcpyLogPath(_deviceId);
        _process = AdbHelper.StartScrcpy(_deviceId, _options, LogPath);
        _process.Exited += OnProcessExited;

        // 4. 物理息屏：系统状态保持 ON，只在 SF/系统层关物理面板，投屏与应用运行不受影响
        if (_options.PhysicalScreenOff)
            await ApplyPhysicalOffAsync(devCaps, scrcpyCaps);

        // 5. 点击投屏窗口点亮（必须在 UI 线程挂载钩子；未实际息屏时无需挂载）
        if (_options.ClickToWake && OffPath != PhysicalOffPath.None)
        {
            _wakeHelper = new ScreenWakeHelper(AdbHelper.ScrcpyWindowTitle(_deviceId), WakeActionAsync);
            _wakeHelper.Start();
        }
    }

    /// <summary>
    /// 按设备能力选择物理息屏路径：
    /// 完全断电：优先使用 scrcpy 原生 --turn-screen-off；随后尝试 cmd display power-off
    /// 和内置 MiniDisplay 工具；全部失败则降级为关闭背光。
    /// 关闭背光：SF 层亮度 0（任意版本）。
    /// 每条路径都会验证物理面板确实熄灭，未生效则降级到下一条路径；
    /// 全部失败不阻断投屏（屏幕保持常亮，用户可手动处理）。
    /// </summary>
    private async Task ApplyPhysicalOffAsync(DeviceDisplayCaps devCaps, ScrcpyCapabilities scrcpyCaps)
    {
        try
        {
            bool wantPowerOff = _options.OffScheme == PhysicalOffScheme.PowerOff;

            // 优先使用 scrcpy 自己的实现。它会在 server 已建立连接后发送关屏请求，
            // 避免 PC 端与 scrcpy server 同时操作显示电源，也覆盖 Xiaomi Android 14
            // 这类没有 cmd display power-off 的设备。
            if (wantPowerOff && scrcpyCaps.TurnScreenOff)
            {
                Status("完全断电：scrcpy --turn-screen-off（SurfaceControl）");
                if (await WaitDisplayOffAsync(4000))
                {
                    OffPath = PhysicalOffPath.Scrcpy;
                    Status("✓ 完全断电生效（scrcpy 原生路径）");
                    return;
                }
                Status("scrcpy 原生关屏未生效，尝试下一条路径");
            }

            if (wantPowerOff && devCaps.DisplayPowerCmd)
            {
                Status("完全断电：cmd display power-off（Android 15+ 原生）");
                await AdbHelper.DisplayPowerOffAsync(_deviceId);
                if (await WaitDisplayOffAsync(3000))
                {
                    if (!await RecoverDozeAsync(() => AdbHelper.DisplayPowerOffAsync(_deviceId)))
                        Status("警告：系统未回到 Awake，投屏性能可能下降");
                    OffPath = PhysicalOffPath.CmdDisplayPower;
                    Status("✓ 完全断电生效（原生命令）");
                    return;
                }
                Status("原生命令未生效，尝试下一条路径");
            }
            if (wantPowerOff && AdbHelper.MiniDisplayAvailable)
            {
                Status("完全断电：启动内置 MiniDisplay 15s 巡检守护（纯 ADB；MIUI 强制开屏时自动重新断电）");
                if (await AdbHelper.EnsureMiniDisplayToolAsync(_deviceId))
                {
                    var (ok, detail) = await AdbHelper.MiniDisplayStartAsync(_deviceId);
                    if (ok && await WaitDisplayOffAsync(4000))
                    {
                        if (!await RecoverDozeAsync(() => AdbHelper.MiniDisplayPowerAsync(_deviceId, off: true)))
                            Status("警告：系统未回到 Awake，投屏性能可能下降");
                        OffPath = PhysicalOffPath.MiniDisplay;
                        Status("✓ 完全断电生效（MiniDisplay 15s 巡检守护）");
                        return;
                    }
                    // 守护未生效：先停掉残留守护（避免它与降级路径互抢面板），再降级
                    try { await AdbHelper.MiniDisplayStopAsync(_deviceId); } catch { }
                    Status($"守护未生效：{FirstLine(detail)}");
                }
                else
                {
                    Status("工具部署失败");
                }
                Status("降级为关闭背光");
            }
            await ApplyBacklightOffAsync(devCaps);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[Scrcpy] 物理息屏失败（屏幕保持常亮）: {ex.Message}");
            OffPath = PhysicalOffPath.None;
            Status($"物理息屏失败：{ex.Message}");
        }
    }

    /// <summary>向非阻塞状态窗追加一行（未挂窗口时为空操作）。</summary>
    private void Status(string msg)
    {
        try { _options.ScreenOffStatus?.Invoke(msg); } catch { }
    }

    private static string FirstLine(string s)
    {
        s = s.Trim();
        var idx = s.IndexOf('\n');
        return idx > 0 ? s[..idx] : s;
    }

    /// <summary>轮询验证物理面板是否已熄灭。返回 true = 已熄灭，或无法判定时信任命令。</summary>
    private async Task<bool> WaitDisplayOffAsync(int maxMs, int intervalMs = 600)
    {
        var deadline = Environment.TickCount + maxMs;
        while (true)
        {
            try
            {
                if (await AdbHelper.IsDisplayPhysicallyOffAsync(_deviceId) != false)
                    return true; // 已熄灭，或状态无法解析（信任命令）
            }
            catch { return true; }
            if (Environment.TickCount >= deadline) return false;
            await Task.Delay(intervalMs);
        }
    }

    /// <summary>
    /// 物理断电后系统可能进入 Doze（DPC 检测到面板被外部断电即走熄屏流程；
    /// 实测 MIUI A14 在 ~3 秒内发生，stay_on_while_plugged_in 充电时可避免，未充电时会触发）。
    /// Doze 会降 scrcpy 帧率/断 TCP。恢复：先唤醒系统（唤醒序列会把面板重新点亮），
    /// 再重新断电（reapplyOff）。返回 true = 系统处于 Awake（或无法判定时信任）。
    /// </summary>
    private async Task<bool> RecoverDozeAsync(Func<Task> reapplyOff)
    {
        try
        {
            var wf = await AdbHelper.GetWakefulnessAsync(_deviceId);
            if (wf == null || wf.StartsWith("Awake", StringComparison.Ordinal))
                return true;
            await AdbHelper.WakeDeviceAsync(_deviceId);
            await Task.Delay(1200);
            await reapplyOff();
            return await WaitDisplayOffAsync(4000);
        }
        catch { return true; }
    }

    /// <summary>关闭背光路径：SF 层亮度 0 + 亮度设置切手动防 DPC 回拉（纯 ADB，任意版本可用）。</summary>
    private async Task ApplyBacklightOffAsync(DeviceDisplayCaps devCaps)
    {
        Status("关闭背光：SF 亮度置 0（纯 ADB）");
        // 保存原亮度设置；亮度=auto 时 get 可能返回 "null"，恢复时跳过
        try
        {
            _savedBrightness = await AdbHelper.GetSettingAsync(_deviceId, "system", "screen_brightness");
            _savedBrightnessMode = await AdbHelper.GetSettingAsync(_deviceId, "system", "screen_brightness_mode");
            if (int.TryParse(_savedBrightness, out var b) && b > 0)
                _restoreSfBrightness = Math.Min(1f, b / 255f);
        }
        catch { }

        // 切手动 + 亮度 0：防止自动亮度/DPC 按原设置值把 SF 亮度拉回去
        try
        {
            await AdbHelper.SetSettingAsync(_deviceId, "system", "screen_brightness_mode", "0");
            await AdbHelper.SetSettingAsync(_deviceId, "system", "screen_brightness", "0");
        }
        catch { }

        // SF 层直接置 0（实测 MIUI A14 立即生效，nits→2；比走设置管道更直接、更即时）
        if (devCaps.DisplaySetBrightnessCmd)
            await AdbHelper.SetDisplayBrightnessSfAsync(_deviceId, 0f);

        // 验证背光确实归零；无法判定时信任命令
        OffPath = await WaitDisplayOffAsync(2000) ? PhysicalOffPath.Backlight : PhysicalOffPath.None;
        Status(OffPath == PhysicalOffPath.Backlight ? "✓ 关闭背光生效" : "背光关闭未生效（屏幕保持常亮）");
    }

    /// <summary>点亮手机屏幕（点击投屏窗口触发；会话结束时也用它恢复）。动作取决于实际息屏路径。</summary>
    private async Task WakeActionAsync()
    {
        switch (OffPath)
        {
            case PhysicalOffPath.Scrcpy:
                // scrcpy 的关屏和 Extinguish 使用同一个 SurfaceControl API。
                // 先尝试内置工具恢复供电；失败时再发送通用唤醒事件。
                if (AdbHelper.MiniDisplayAvailable
                    && await AdbHelper.EnsureMiniDisplayToolAsync(_deviceId))
                {
                    var result = await AdbHelper.MiniDisplayPowerAsync(_deviceId, off: false);
                    if (result.Ok) break;
                }
                await AdbHelper.WakeDeviceAsync(_deviceId);
                break;
            case PhysicalOffPath.CmdDisplayPower:
                await AdbHelper.DisplayPowerOnAsync(_deviceId);
                break;
            case PhysicalOffPath.MiniDisplay:
                // 停止 15s 巡检守护并恢复面板供电（守护不存在时也执行恢复，可安全重复调用）
                await AdbHelper.MiniDisplayStopAsync(_deviceId);
                // 实测：系统处于 Doze 时仅设 SF powerMode=NORMAL 点不亮面板，需补系统唤醒（已亮时为空操作）
                await AdbHelper.WakeDeviceAsync(_deviceId);
                break;
            case PhysicalOffPath.Backlight:
                // 先通用唤醒（屏幕已亮时为空操作），覆盖用户手动锁屏的情况
                await AdbHelper.WakeDeviceAsync(_deviceId);
                if (AdbHelper.GetDeviceDisplayCaps(_deviceId).DisplaySetBrightnessCmd)
                    await AdbHelper.SetDisplayBrightnessSfAsync(_deviceId, _restoreSfBrightness);
                if (!string.IsNullOrEmpty(_savedBrightness) && _savedBrightness != "null")
                    await AdbHelper.SetSettingAsync(_deviceId, "system", "screen_brightness", _savedBrightness);
                break;
            default:
                // 未执行物理息屏（例如用户按电源键手动锁屏）：按普通睡眠唤醒
                await AdbHelper.WakeDeviceAsync(_deviceId);
                break;
        }
    }

    private void OnProcessExited(object? sender, EventArgs e)
    {
        // 进程退出事件可能在线程池线程触发，切回 UI 线程做清理（卸载钩子）
        if (_uiContext != null)
            _uiContext.Post(_ => CleanupAndNotify(), null);
        else
            CleanupAndNotify();
    }

    private void CleanupAndNotify()
    {
        if (_cleanedUp) return;
        _cleanedUp = true;

        _wakeHelper?.Stop();
        _wakeHelper?.Dispose();
        _wakeHelper = null;

        _ = RestoreSettingsAsync();

        _exitedTcs.TrySetResult(true);
        Exited?.Invoke(this, EventArgs.Empty);
    }

    private async Task RestoreSettingsAsync()
    {
        try
        {
            // 1. 物理点亮屏幕（best effort，避免把用户手机面板留在黑屏状态）
            if (OffPath != PhysicalOffPath.None)
            {
                try { await WakeActionAsync(); } catch { }
            }

            // 2. 恢复亮度设置（关闭背光路径保存过）
            if (!string.IsNullOrEmpty(_savedBrightness) && _savedBrightness != "null")
                await AdbHelper.SetSettingAsync(_deviceId, "system", "screen_brightness", _savedBrightness);
            if (!string.IsNullOrEmpty(_savedBrightnessMode) && _savedBrightnessMode != "null")
                await AdbHelper.SetSettingAsync(_deviceId, "system", "screen_brightness_mode", _savedBrightnessMode);

            // 3. 恢复息屏超时（stay_on_while_plugged_in 由 scrcpy --stay-awake 自行恢复）
            if (!string.IsNullOrEmpty(_savedScreenOffTimeout) && _savedScreenOffTimeout != "null")
                await AdbHelper.SetSettingAsync(_deviceId, "system", "screen_off_timeout", _savedScreenOffTimeout);

            // 3b. 恢复"充电常亮"（完全断电路径钉住过 stay_on_while_plugged_in=7）
            if (!string.IsNullOrEmpty(_savedStayOn) && _savedStayOn != "null")
                await AdbHelper.SetSettingAsync(_deviceId, "global", "stay_on_while_plugged_in", _savedStayOn);

            if (!string.IsNullOrEmpty(_savedShowImeWithHardKeyboard) && _savedShowImeWithHardKeyboard != "null")
                await AdbHelper.SetSettingAsync(_deviceId, "secure", "show_ime_with_hard_keyboard", _savedShowImeWithHardKeyboard);
        }
        catch
        {
            // 恢复失败忽略
        }
    }

    /// <summary>停止会话（关闭 scrcpy 进程，触发清理）。</summary>
    public void Stop()
    {
        try
        {
            if (_process != null && !_process.HasExited)
                _process.Kill();
        }
        catch { }
    }

    public void Dispose()
    {
        Stop();
        if (!_cleanedUp)
            CleanupAndNotify();
    }
}
