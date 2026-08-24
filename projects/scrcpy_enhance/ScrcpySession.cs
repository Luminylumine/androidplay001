using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace AdbManager;

/// <summary>
/// 全局鼠标钩子：监听对 scrcpy 投屏窗口的点击，点击时向手机发送 WAKEUP 唤醒键。
/// 采用“套壳”思路——不修改 scrcpy 源码，而是在外部捕获点击指令。
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
    private readonly string _deviceId;
    private readonly MouseProc _proc; // 保持引用，防止被 GC 回收导致钩子崩溃
    private IntPtr _hook = IntPtr.Zero;
    private IntPtr _target = IntPtr.Zero;
    private System.Threading.Timer? _finder;
    private DateTime _lastWake = DateTime.MinValue;
    private bool _disposed;

    public ScreenWakeHelper(string windowTitle, string deviceId)
    {
        _windowTitle = windowTitle;
        _deviceId = deviceId;
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
        _ = Task.Run(() => AdbHelper.WakeDeviceAsync(_deviceId));
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
    private string? _savedShowImeWithHardKeyboard;
    private string? _savedStayOnWhilePluggedIn;
    private bool _cleanedUp;
    private readonly TaskCompletionSource<bool> _exitedTcs =
        new(TaskCreationOptions.RunContinuationsAsynchronously);

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

    /// <summary>在 UI 线程调用。启动前应用设置、拉起 scrcpy、挂载唤醒钩子。</summary>
    public async Task StartAsync()
    {
        // 0. 确保 scrcpy 能力已探测（StartScrcpy 会据此生成正确的命令行参数，
        //    例如 ClickToWake 需要 --turn-screen-off）。
        await AdbHelper.DetectScrcpyCapabilitiesAsync();

        // 1. “不黑屏”：把 screen_off_timeout 调成极大值（USB/TCP 均有效）
        if (_options.ScreenMode == ScreenMode.StayAwake)
        {
            try
            {
                _savedScreenOffTimeout = await AdbHelper.GetSettingAsync(_deviceId, "system", "screen_off_timeout");
                await AdbHelper.SetSettingAsync(_deviceId, "system", "screen_off_timeout", HugeScreenOffTimeout);
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

        // 3. 启动 scrcpy（本会话独立日志路径，多会话并存互不串台）
        LogPath = AdbHelper.GetScrcpyLogPath(_deviceId);
        _process = AdbHelper.StartScrcpy(_deviceId, _options, LogPath);
        _process.Exited += OnProcessExited;

        // 4. “黑屏可点亮”：
        //    a) 关闭开发者选项“充电时保持唤醒”（stay_on_while_plugged_in=0），
        //       否则充电状态下屏幕不会自动熄灭——这是“手机不休眠”的常见根因之一。
        //    b) 挂载点击唤醒钩子（必须在 UI 线程）。
        if (_options.ScreenMode == ScreenMode.ClickToWake)
        {
            try
            {
                _savedStayOnWhilePluggedIn = await AdbHelper.GetSettingAsync(_deviceId, "global", "stay_on_while_plugged_in");
                await AdbHelper.SetSettingAsync(_deviceId, "global", "stay_on_while_plugged_in", "0");
            }
            catch { }

            _wakeHelper = new ScreenWakeHelper(AdbHelper.ScrcpyWindowTitle(_deviceId), _deviceId);
            _wakeHelper.Start();
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
            if (!string.IsNullOrEmpty(_savedScreenOffTimeout) && _savedScreenOffTimeout != "null")
                await AdbHelper.SetSettingAsync(_deviceId, "system", "screen_off_timeout", _savedScreenOffTimeout);

            if (!string.IsNullOrEmpty(_savedShowImeWithHardKeyboard) && _savedShowImeWithHardKeyboard != "null")
                await AdbHelper.SetSettingAsync(_deviceId, "secure", "show_ime_with_hard_keyboard", _savedShowImeWithHardKeyboard);

            if (!string.IsNullOrEmpty(_savedStayOnWhilePluggedIn) && _savedStayOnWhilePluggedIn != "null")
                await AdbHelper.SetSettingAsync(_deviceId, "global", "stay_on_while_plugged_in", _savedStayOnWhilePluggedIn);
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
