using System.Runtime.InteropServices;
using System.Text;

namespace AdbManager;

/// <summary>
/// PC 输入法文本桥：把 Windows 端输入法（如微软拼音）已组成好的 Unicode 文本，
/// 通过「剪贴板 + Ctrl+V」注入 scrcpy 窗口。scrcpy 3.3.4 会做 PC↔Android 剪贴板同步
/// 并触发粘贴，从而支持中文等任意 Unicode（这是投入产出比最高的中文输入方案）。
///
/// 原理链路：
///   Windows IME 组成"你好" → Clipboard.SetText → 聚焦 scrcpy HWND → SendInput(Ctrl+V)
///   → scrcpy 剪贴板同步 + 粘贴 → 手机端目标输入框。
///
/// 注意：
///  · Clipboard 与 SendInput 必须在 UI(STA) 线程调用（本方法由 UI 线程调用）。
///  · 文本会先写入手机剪贴板，请勿无提示发送密码 / Token 等敏感内容。
/// </summary>
public static class ScrcpyTextBridge
{
    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern IntPtr GetAncestor(IntPtr hwnd, uint gaFlags);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    private const uint GA_ROOT = 2;
    private const int SW_RESTORE = 9;
    private const ushort VK_CONTROL = 0x11;
    private const ushort VK_V = 0x56;
    private const uint INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_KEYUP = 0x0002;

    // 完整 INPUT 联合体（mi/ki/hi 三者都要），保证 64 位下 sizeof(INPUT)==40，
    // 否则 SendInput 的 cbSize 与真实结构不符会直接返回 0。
    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public InputUnion U;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
        [FieldOffset(0)] public HARDWAREINPUT hi;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx, dy;
        public uint mouseData, dwFlags, time;
        public UIntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk, wScan;
        public uint dwFlags, time;
        public UIntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct HARDWAREINPUT
    {
        public uint uMsg;
        public ushort wParamL, wParamH;
    }

    /// <summary>按标题前缀查找 scrcpy 顶层窗口句柄（找不到返回 IntPtr.Zero）。</summary>
    public static IntPtr FindScrcpyWindow(string titlePrefix)
    {
        IntPtr found = IntPtr.Zero;
        EnumWindows((hwnd, _) =>
        {
            var sb = new StringBuilder(256);
            GetWindowText(hwnd, sb, sb.Capacity);
            var title = sb.ToString();
            if (title.StartsWith(titlePrefix, StringComparison.Ordinal))
            {
                found = GetAncestor(hwnd, GA_ROOT);
                return false; // 停止枚举
            }
            return true;
        }, IntPtr.Zero);
        return found;
    }

    /// <summary>
    /// 把 text 通过剪贴板 + Ctrl+V 发送到 scrcpy 窗口。必须在 UI 线程调用。
    /// 成功返回 true；未找到窗口或 SendInput 失败返回 false。
    /// </summary>
    public static async Task<bool> SendTextToScrcpyAsync(string titlePrefix, string text)
    {
        if (string.IsNullOrEmpty(text)) return false;

        var hwnd = FindScrcpyWindow(titlePrefix);
        if (hwnd == IntPtr.Zero || !IsWindow(hwnd))
            throw new InvalidOperationException("未找到 scrcpy 投屏窗口，请先启动屏幕共享。");

        // 写入 Windows 剪贴板（STA 线程）
        Clipboard.SetText(text);

        // 让 scrcpy 窗口获得前台焦点（若被最小化则先恢复）
        ShowWindow(hwnd, SW_RESTORE);
        SetForegroundWindow(hwnd);
        await Task.Delay(80); // 留出焦点切换时间

        var inputs = new[]
        {
            Key(VK_CONTROL, false),
            Key(VK_V, false),
            Key(VK_V, true),
            Key(VK_CONTROL, true)
        };

        var sent = SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
        return sent == (uint)inputs.Length;
    }

    private static INPUT Key(ushort vk, bool up)
    {
        return new INPUT
        {
            type = INPUT_KEYBOARD,
            U = new InputUnion
            {
                ki = new KEYBDINPUT
                {
                    wVk = vk,
                    dwFlags = up ? KEYEVENTF_KEYUP : 0
                }
            }
        };
    }
}
