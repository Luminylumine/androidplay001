# AdbManager 键盘输入与黑屏/休眠问题调研及实现方案

> 项目：AdbManager  
> 技术栈：.NET 9.0 / WinForms / adb / scrcpy  
> 当前 scrcpy：3.3.4  
> 目标设备：Huawei Android 10、Huawei Android 12、Xiaomi/Redmi Android 16  
> 连接：ADB TCP  
> 调研日期：2026-08-24  
> 设计目标：**实现简单、稳定、不污染用户系统设置、无线可用、跨 Android 10–16**

---

# 0. 先给结论

1. **`--prefer-text` 并不是 scrcpy 4.0+ 才支持。scrcpy 3.3.4 已经正式支持。**
2. **`--turn-screen-off` 在 scrcpy 3.3.4 已经支持，并且可用于 TCP/无线连接。**
3. `--stay-awake` 才有“只在设备处于 plugged 状态时生效”的限制；如果只是 TCP/IP 连接且没有充电，它没有效果。
4. scrcpy 3.3.4 的 `--keyboard=sdk` **不能可靠注入任意 Unicode/CJK 字符**。服务端最终依赖 Android `KeyCharacterMap.getEvents()` 将字符转换成按键事件，中文字符经常失败并输出 `Could not inject char u+XXXX`。
5. `--keyboard=uhid` 适合“物理键盘 + Android 端 IME”。它不会把 Windows IME 的候选文本作为 Unicode 字符串直接传入 Android。
6. 想要“Windows 中文输入法选好 `你好` 后直接输入 Android”，最低成本且最稳定的路径是：
   ```text
   Windows IME → Windows Clipboard → scrcpy Ctrl+V → Android Clipboard/PASTE
   ```
7. “黑屏可点亮”当前不工作的首要原因不是 WakeLock，而是 `ClickToWake` 分支**没有执行任何熄屏动作**。
8. 推荐 ClickToWake：
   ```text
   scrcpy --turn-screen-off --screen-off-timeout=5
   ```
   不传 `--stay-awake`；升级 4.x 后也不要传 `--keep-active`。
9. 自动化电源控制优先：
   ```text
   Sleep  = KEYCODE_SLEEP 223
   Wake   = KEYCODE_WAKEUP 224
   Power  = KEYCODE_POWER 26（toggle，仅 fallback）
   ```
10. 不要为了“黑屏”使用 `svc power forcesuspend`。它会忽略 wakelock 强制整机 suspend，ADB TCP/scrcpy 可能一起失去响应。

---

# 1. 先修正 `SupportsPreferText()`

scrcpy v3.3.4 官方文档已经包含：

```bash
scrcpy --prefer-text
```

所以如果你现在写的是：

```csharp
return version >= new Version(4, 0);
```

这是错误的。

更好的做法不是继续记版本表，而是启动 AdbManager 时执行一次：

```bash
scrcpy --help
```

直接检测 capability。

```csharp
public sealed class ScrcpyCapabilities
{
    public bool PreferText { get; init; }
    public bool TurnScreenOff { get; init; }
    public bool ScreenOffTimeout { get; init; }
    public bool KeepActive { get; init; }
    public bool UhidKeyboard { get; init; }
}
```

```csharp
public static async Task<ScrcpyCapabilities> DetectScrcpyCapabilitiesAsync(
    string scrcpyPath)
{
    var psi = new ProcessStartInfo
    {
        FileName = scrcpyPath,
        UseShellExecute = false,
        RedirectStandardOutput = true,
        RedirectStandardError = true,
        CreateNoWindow = true
    };

    psi.ArgumentList.Add("--help");

    using var process = Process.Start(psi)
        ?? throw new InvalidOperationException("Unable to start scrcpy.");

    string stdout = await process.StandardOutput.ReadToEndAsync();
    string stderr = await process.StandardError.ReadToEndAsync();
    await process.WaitForExitAsync();

    string text = stdout + "\n" + stderr;

    return new ScrcpyCapabilities
    {
        PreferText = text.Contains("--prefer-text", StringComparison.Ordinal),
        TurnScreenOff = text.Contains("--turn-screen-off", StringComparison.Ordinal),
        ScreenOffTimeout = text.Contains("--screen-off-timeout", StringComparison.Ordinal),
        KeepActive = text.Contains("--keep-active", StringComparison.Ordinal),
        UhidKeyboard =
            text.Contains("--keyboard", StringComparison.Ordinal) &&
            text.Contains("uhid", StringComparison.OrdinalIgnoreCase)
    };
}
```

只在启动或 scrcpy 路径改变时探测一次。

---

# 2. 问题 1：SDK 键盘为什么中文失败

scrcpy SDK 模式的实际链路可以简化为：

```text
Windows keyboard / SDL
        ↓
key event / text event
        ↓
scrcpy control protocol
        ↓
Android scrcpy-server
        ↓
Controller.injectText()
        ↓
KeyCharacterMap.getEvents(chars)
        ↓
InputManager.injectInputEvent()
```

关键点：scrcpy server 的 text injection **不是** `InputConnection.commitText()`。

它会尝试把字符变成 Android `KeyEvent`。如果：

```java
charMap.getEvents(chars)
```

返回 null，就会出现：

```text
Could not inject char u+597d
```

因此：

```text
--keyboard=sdk
```

只适合 ASCII 和有限特殊字符，不应作为“任意 Unicode 文本通道”。

Huawei Android 10 / Harmony 场景已有 scrcpy 3.3.4 issue 出现大量 `Could not inject char u+XXXX`，与你当前现象一致。

---

# 3. `--prefer-text` 到底解决什么

SDK 模式同时有：

```text
key events
text events
```

默认字母通常偏向 key event，以保证游戏中的 WASD 行为正常。

启用：

```bash
--prefer-text
```

后，字母和空格也更偏向 text event。

它对：

- dead key；
- 某些组合字符；
- 某些桌面 IME/键盘布局；

有帮助。

但它**没有改变 Android `KeyCharacterMap` 的 Unicode 能力**。

因此：

```text
Windows IME 组成 “你好”
→ scrcpy 获得文本
→ Android KeyCharacterMap 无法映射 “你”“好”
→ 仍可能失败
```

## AdbHelper.cs 立即修改

```csharp
case KeyboardMode.Sdk:
{
    args.Add("--keyboard=sdk");

    if (_scrcpyCapabilities.PreferText)
    {
        args.Add("--prefer-text");
    }

    break;
}
```

UI 文案建议从：

```text
SDK（兼容模式，支持中文输入法）
```

改为：

```text
SDK（兼容模式 / 有限文本注入）
```

---

# 4. UHID 模式与中文

UHID 路线是：

```text
PC 键盘按键
↓
UHID HID reports
↓
Android 看见“物理键盘”
↓
Android IME
↓
中文组合/候选
```

所以 UHID 可以很好地输入中文，但**中文是由 Android 端 IME 处理的**。

它不是：

```text
Windows 微软拼音选出“你好”
↓
UHID 发送 Unicode “你好”
```

UHID HID report 不承载任意 Unicode 文本。

推荐：

```bash
scrcpy --keyboard=uhid
```

并打开 Android 物理键盘设置：

```bash
adb shell am start -a android.settings.HARD_KEYBOARD_SETTINGS
```

`show_ime_with_hard_keyboard=1` 时可以让 Android 软键盘/候选继续出现。

---

# 5. Windows IME 能否直接在 scrcpy HWND 中工作

stock scrcpy 不适合把它当可靠能力。

即使 Windows IME 最终产生 SDL text input，后续仍然会走 scrcpy SDK 的字符注入链，最终受到 `KeyCharacterMap` 限制。

所以单独通过：

```text
WM_CHAR
WM_IME_COMPOSITION
```

注入 scrcpy 窗口，并没有解决 Android 端 Unicode commit 的根本问题。

而且直接操作 scrcpy SDL 窗口会带来：

- SDL2/SDL3 差异；
- IMM32/TSF 差异；
- composition state；
- HWND 焦点；
- scrcpy 4.x SDL3 迁移；

维护成本很高。

**不推荐 WM_CHAR 作为主方案。**

---

# 6. 最推荐中文方案：Clipboard Paste

scrcpy 3.3.4 已经支持 PC ↔ Android clipboard synchronization。

正常 `Ctrl+V`：

```text
Windows Clipboard
↓
scrcpy
↓
Android Clipboard
↓
PASTE key/action
```

它传输的是字符串/剪贴板，而不是逐字符 KeyEvent，因此非常适合 Unicode/CJK。

注意区分：

```text
Ctrl+V / MOD+V
→ clipboard sync + paste
```

与：

```text
MOD+Shift+V
→ 把 clipboard 变成 key-event 序列
```

后者官方明确说明可能破坏 non-ASCII，所以中文不要用。

也不要默认启用：

```bash
--legacy-paste
```

因为它会退回 key-event paste。

---

# 7. 最好的 AdbManager UX：增加“PC 输入法”输入框

例如：

```text
┌──────────────────────────────┐
│ PC 输入法发送                │
│ [你好世界________________]   │
│                    [发送]    │
└──────────────────────────────┘
```

WinForms TextBox 正常接收 Windows IME。

用户按 Enter：

```text
Unicode TextBox
↓
Clipboard.SetText()
↓
聚焦 scrcpy HWND
↓
SendInput Ctrl+V
↓
scrcpy clipboard sync + paste
```

这不需要安装手机 App。

建议新增 `ScrcpyTextBridge.cs`。

```csharp
using System.Runtime.InteropServices;
using System.Windows.Forms;

public static class ScrcpyTextBridge
{
    [DllImport("user32.dll")]
    private static extern bool SetForegroundWindow(IntPtr hWnd);

    [DllImport("user32.dll")]
    private static extern uint SendInput(
        uint nInputs,
        INPUT[] pInputs,
        int cbSize);

    private const ushort VK_CONTROL = 0x11;
    private const ushort VK_V = 0x56;
    private const uint INPUT_KEYBOARD = 1;
    private const uint KEYEVENTF_KEYUP = 0x0002;

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public InputUnion U;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)]
        public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public UIntPtr dwExtraInfo;
    }

    public static async Task SendTextAsync(
        IntPtr scrcpyHwnd,
        string text)
    {
        if (scrcpyHwnd == IntPtr.Zero)
            throw new ArgumentException("Invalid scrcpy window handle.");

        if (string.IsNullOrEmpty(text))
            return;

        Clipboard.SetText(text);
        SetForegroundWindow(scrcpyHwnd);
        await Task.Delay(50);

        var inputs = new[]
        {
            Key(VK_CONTROL, false),
            Key(VK_V, false),
            Key(VK_V, true),
            Key(VK_CONTROL, true)
        };

        uint sent = SendInput(
            (uint)inputs.Length,
            inputs,
            Marshal.SizeOf<INPUT>());

        if (sent != inputs.Length)
            throw new InvalidOperationException("Failed to send Ctrl+V.");
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
```

注意 Clipboard API 应在 STA/UI thread 使用。

安全提示：PC → Android paste 会把文本写到手机剪贴板，不要无提示发送密码/API Token。

---

# 8. 第三方/高级 Unicode：ADBKeyBoard 或自研 IME companion

`adb shell input text` 不是可靠 Unicode 文本接口。

ADBKeyBoard 的思路是：

```text
AdbManager
↓
adb broadcast Base64 UTF-8
↓
Android InputMethodService
↓
InputConnection.commitText()
```

示例：

```bash
adb install keyboardservice-debug.apk
adb shell ime enable com.android.adbkeyboard/.AdbIME
adb shell ime set com.android.adbkeyboard/.AdbIME
```

发送：

```bash
adb shell am broadcast -a ADB_INPUT_B64 --es msg <BASE64>
```

恢复：

```bash
adb shell ime reset
```

或者先保存：

```bash
adb shell settings get secure default_input_method
```

再：

```bash
adb shell ime set <OLD_IME>
```

C#：

```csharp
public static async Task SendUnicodeViaAdbKeyboardAsync(
    string deviceId,
    string text)
{
    string base64 = Convert.ToBase64String(
        Encoding.UTF8.GetBytes(text));

    await AdbHelper.RunAdbAsync(
        deviceId,
        "shell",
        "am",
        "broadcast",
        "-a",
        "ADB_INPUT_B64",
        "--es",
        "msg",
        base64);
}
```

缺点：

- 要安装 IME APK；
- 会改变当前输入法；
- IME 是敏感组件；
- OEM 可能限制 `ime set`；
- 如果分发第三方 GPL 实现，要注意许可证。

长期产品化更建议自己写一个很小的 `AdbManagerIME.apk`。

---

# 9. 输入方案优先级

| 优先级 | 方案 | 中文 | 手机改动 | 推荐 |
|---|---|---:|---:|---|
| P0 | scrcpy Clipboard Ctrl+V | ✅ | 否 | ⭐⭐⭐⭐⭐ |
| P1 | UHID + Android 中文 IME | ✅ | 否 | ⭐⭐⭐⭐⭐ |
| P2 | WinForms PC 输入法框 → Clipboard → Ctrl+V | ✅ | 否 | ⭐⭐⭐⭐⭐ |
| P3 | SDK + `--prefer-text` | ⚠ | 否 | ⭐⭐⭐ |
| P4 | 自研 IME companion / ADBKeyBoard | ✅ | 是 | ⭐⭐⭐⭐ |
| 不推荐 | `adb shell input text 中文` | ❌/不稳定 | 否 | ⭐ |
| 不推荐 | 给 SDL HWND 手发 WM_CHAR | ⚠ | 否 | ⭐ |

建议保留：

```csharp
public enum KeyboardMode
{
    Sdk,
    Uhid,
    Disabled
}
```

另外增加：

```csharp
public enum PcTextInputMode
{
    ClipboardPaste,
    AndroidIme,
    Disabled
}
```

`KeyboardMode` 决定“按键怎么传”；`PcTextInputMode` 决定“已经组成好的 Unicode 文本怎么传”。

---

# 10. 问题 2：ClickToWake 为什么一直亮

你当前代码：

```csharp
if (_options.ScreenMode == ScreenMode.ClickToWake)
{
    _wakeHelper = new ScreenWakeHelper(windowTitle, deviceId);
    _wakeHelper.Start();
}
```

只实现：

```text
点击 → WAKEUP
```

没有：

```text
启动 → SCREEN OFF
```

所以手机一直亮是当前逻辑的必然结果。

---

# 11. scrcpy 3.3.4 的正确黑屏接口

官方 v3.3.4 已支持：

```bash
scrcpy --turn-screen-off
scrcpy -S
```

运行时也可以：

```text
MOD+o       → turn screen off
MOD+Shift+o → turn screen on
```

关键：`--turn-screen-off` 没有“仅 USB”限制。它通过 scrcpy 的 control channel 调用设备显示电源控制，TCP ADB 一样可用。

官方反而特意说明：

```text
--stay-awake
```

如果设备没有处于 plugged 状态、只是 TCP/IP 连接，则没有效果。

---

# 12. `AdbHelper.StartScrcpy()` 立即修改

```csharp
if (options.ScreenMode == ScreenMode.ClickToWake)
{
    if (capabilities.TurnScreenOff)
        args.Add("--turn-screen-off");

    if (capabilities.ScreenOffTimeout)
        args.Add("--screen-off-timeout=5");
}
```

注意：

```text
--screen-off-timeout
```

在 scrcpy 3.3.4 也已经支持。

单位：

```text
scrcpy 参数 = 秒
Android settings system screen_off_timeout = 毫秒
```

ClickToWake 不要传：

```bash
--stay-awake
```

升级 4.x 后也不要传：

```bash
--keep-active
```

因为这些都与“无操作后自动黑屏”冲突。

推荐：

```bash
scrcpy -s DEVICE \
  --turn-screen-off \
  --screen-off-timeout=5 \
  --keyboard=uhid
```

SDK：

```bash
scrcpy -s DEVICE \
  --turn-screen-off \
  --screen-off-timeout=5 \
  --keyboard=sdk \
  --prefer-text
```

---

# 13. 不要手工改 `screen_off_timeout`，优先交给 scrcpy

可以：

```bash
settings put system screen_off_timeout 5000
```

但 ClickToWake 没必要自己管理，因为 v3.3.4：

```bash
--screen-off-timeout=5
```

会由 scrcpy：

```text
保存旧值
→ 修改
→ 退出时恢复
```

比 C# 自己 Get/Put/finally 更简单。

同一个 setting 不要同时被 scrcpy 和 AdbManager 修改，否则退出恢复顺序容易打架。

---

# 14. 检查 Developer Options “Stay awake”

即使你没传 `--stay-awake`，用户可能手动开启：

```text
充电时屏幕不休眠
```

检查：

```bash
adb shell settings get global stay_on_while_plugged_in
```

`0` 表示关闭。

非 0 表示某些供电类型下保持唤醒。

如果 ClickToWake 的产品语义要求“即使充电也必须自动灭屏”，可以：

```text
session start：save → set 0
session end：restore
```

```csharp
private string? _savedStayOnWhilePluggedIn;
```

启动：

```csharp
_savedStayOnWhilePluggedIn =
    await AdbHelper.GetSettingAsync(
        _deviceId,
        "global",
        "stay_on_while_plugged_in");

await AdbHelper.SetSettingAsync(
    _deviceId,
    "global",
    "stay_on_while_plugged_in",
    "0");
```

退出恢复。

---

# 15. KEYCODE_SLEEP / WAKEUP / POWER

Android 定义：

```text
KEYCODE_POWER  = 26
KEYCODE_SLEEP  = 223
KEYCODE_WAKEUP = 224
```

## 223 Sleep

语义：

```text
设备已醒 → sleep
设备已 asleep → no-op
```

适合自动化。

## 224 Wakeup

语义：

```text
设备 asleep → wake
设备已 awake → no-op
```

这是 `ScreenWakeHelper` 最适合的命令。

## 26 Power

是 toggle。

```text
亮 → 灭
灭 → 亮
```

自动化中容易因为“当前状态判断错”做反效果。

普通：

```bash
adb shell input keyevent 26
```

通常不会直接关机；主要风险是 toggle 状态不可预测。长按/厂商 power gesture 才有更大副作用。

---

# 16. `WakeDeviceAsync()` 不要并发两个 power command

你现在：

```csharp
await Task.WhenAll(
    input keyevent 224,
    cmd display power-on 0);
```

不推荐。

Android 10/12 没有 `cmd display power-on 0`，并发执行也没有意义。

建议：

```csharp
public static async Task WakeDeviceAsync(
    string deviceId,
    int androidSdk)
{
    var wake = await RunAdbShellAsync(
        deviceId,
        "input keyevent 224");

    if (wake.Success)
        return;

    if (androidSdk >= 35)
    {
        await RunAdbShellAsync(
            deviceId,
            "cmd display power-on 0");
    }
}
```

你目标设备全部 Android 10+，224 已存在。

---

# 17. 点击唤醒要 debounce

每次鼠标点击都启动：

```text
adb.exe → shell → keyevent
```

会浪费进程和 TCP 往返。

建议最多 1 秒发一次：

```csharp
private long _lastWakeTick;

private async void OnScrcpyClicked()
{
    long now = Environment.TickCount64;

    if (now - Interlocked.Read(ref _lastWakeTick) < 1000)
        return;

    Interlocked.Exchange(ref _lastWakeTick, now);

    await AdbHelper.WakeDeviceAsync(
        _deviceId,
        _androidSdk);
}
```

224 在设备已醒时 no-op，所以这个策略很安全。

---

# 18. `screen_off_timeout` 与 `sleep_timeout`

## `system screen_off_timeout`

公开 `Settings.System.SCREEN_OFF_TIMEOUT`。

含义：无 user activity 后多久进入 screen off/dream 流程。

单位 ms。

## `secure sleep_timeout`

AOSP 隐藏 setting：

```text
Settings.Secure.SLEEP_TIMEOUT
```

含义：设备多久后“fully sleep”的上界。

AOSP 说明它通常应该比 `SCREEN_OFF_TIMEOUT` 更长。

`-1` 表示禁用该 timeout。

## ClickToWake 不要修改 `sleep_timeout`

你要的是：

```text
屏幕灭
scrcpy 继续
ADB TCP 继续
点击立即唤醒
```

把 fully-sleep timeout 调得很短，可能导致 Wi-Fi/CPU/ADB 进入更深睡眠，反而破坏投屏。

所以：

```text
只控制 display / screen_off_timeout
```

---

# 19. 问题 3：scrcpy 默认会不会阻止休眠

scrcpy 3.3.4 没有证据表明默认通过一个 FULL_WAKE_LOCK 永久保持屏幕亮。

其文档化的 `--stay-awake` 实现是修改：

```text
stay_on_while_plugged_in
```

不是默认 `WakeLock.acquire()`。

并且该选项默认不开。

scrcpy 4.0 才新增：

```bash
--keep-active
```

周期性模拟 user activity。

因此 3.3.4 不传 `--stay-awake` 时，不应该先假设“scrcpy 自己持有 WakeLock 阻止 screen timeout”。

更常见原因：

1. ClickToWake 根本没执行 screen off；
2. Developer Options stay awake 开着；
3. 用户一直通过 scrcpy 操作，真实输入不断刷新 user activity；
4. OEM power policy；
5. 其它 App/system service WakeLock。

---

# 20. `mHoldingDisplaySuspendBlocker` 不是 scrcpy WakeLock

AOSP PowerManagerService 内：

```text
mDisplaySuspendBlocker
mHoldingDisplaySuspendBlocker
```

用于 display 亮着、display 状态切换、user activity 等过程中阻止 CPU suspend。

这是 `system_server` 内部 suspend blocker。

看到：

```text
mHoldingDisplaySuspendBlocker=true
```

不能推出：

```text
scrcpy 持有 WakeLock
```

---

# 21. 如何检查 power / wakelock

完整：

```bash
adb shell dumpsys power
```

关注：

```text
mWakefulness
mHoldingWakeLockSuspendBlocker
mHoldingDisplaySuspendBlocker
Wake Locks
Display Power
```

Windows：

```bat
adb -s DEVICE shell dumpsys power > power.txt
```

然后搜索：

```text
Wake Locks: size=
PARTIAL_WAKE_LOCK
FULL_WAKE_LOCK
SCREEN_BRIGHT_WAKE_LOCK
SCREEN_DIM_WAKE_LOCK
```

不要只搜 `scrcpy`，很多 lock 属于 SystemUI、media、charging、vendor service。

---

# 22. 不要尝试“释放 scrcpy WakeLock”

首先 stock scrcpy 3.3.4 普通投屏没有一个文档化的 scrcpy WakeLock 需要你释放。

其次 Android 没有适合产品使用的通用：

```text
release arbitrary process wakelock
```

不要：

- kill system power service；
- 修改不相关 device_config；
- 手工释放系统 suspend blocker。

---

# 23. `svc power forcesuspend` 为什么不能用

AOSP `svc power forcesuspend` 的定义就是：

```text
Force the system into suspend, ignoring all wakelocks.
```

PowerManagerService 会：

```text
goToSleep
→ disable partial wake locks
→ nativeForceSuspend
```

无线 ADB 的：

```text
Wi-Fi
adbd
scrcpy server
```

可能全部暂停，直到有外部 wake source。

它适合 power debugging，不适合你的“黑屏可点亮”。

---

# 24. `dumpsys window policy` 不能强制 sleep

`dumpsys` 是 diagnostic。

```bash
adb shell dumpsys window policy
```

可以查看 interactive/keyguard/window policy，但不能作为 `sleep now` API。

---

# 25. 建议重命名 ScreenMode

```csharp
public enum ScreenMode
{
    KeepPhysicalScreenOn,

    // 启动后立即关闭物理屏；
    // 点击 scrcpy 窗口后 WAKEUP；
    // 无操作按 timeout 再灭屏。
    ScreenOffClickToWake
}
```

比：

```text
StayAwake / ClickToWake
```

语义更准确。

---

# 26. 推荐 `ScrcpySession.StartAsync()`

```csharp
public async Task StartAsync()
{
    try
    {
        await PrepareDeviceSettingsAsync();

        StartScrcpyProcess();
        await WaitForScrcpyWindowAsync();

        if (_options.ScreenMode ==
            ScreenMode.ScreenOffClickToWake)
        {
            _wakeHelper = new ScreenWakeHelper(
                _windowTitle,
                _deviceId,
                _androidSdk);

            _wakeHelper.Start();
        }
    }
    catch
    {
        await RestoreSettingsSafeAsync();
        throw;
    }
}
```

---

# 27. 推荐 StartScrcpy 参数构建

```csharp
switch (options.ScreenMode)
{
    case ScreenMode.KeepPhysicalScreenOn:
    {
        // scrcpy 3.3.4 没有 --keep-active。
        // 可继续使用原有长 screen_off_timeout lease。
        break;
    }

    case ScreenMode.ScreenOffClickToWake:
    {
        if (caps.TurnScreenOff)
            args.Add("--turn-screen-off");

        if (caps.ScreenOffTimeout)
            args.Add("--screen-off-timeout=5");

        // 不添加 --stay-awake。
        // 4.x 也不添加 --keep-active。
        break;
    }
}
```

---

# 28. 避免 setting restore race

不要同时：

```text
AdbManager 手动 screen_off_timeout=5000
+
scrcpy --screen-off-timeout=5
```

因为退出时两个组件都会恢复旧值，容易出现顺序竞争。

建议：

```text
ClickToWake:
  screen_off_timeout 完全交给 scrcpy

KeepScreenOn + scrcpy 3.3.4:
  AdbManager 自己管理长 timeout

KeepScreenOn + scrcpy 4.x:
  优先 --keep-active
```

---

# 29. scrcpy 4.0/4.1 是否值得升级

截至 2026-08-24，最新正式版本：

```text
scrcpy 4.1
```

发布日期：

```text
2026-07-12
```

## 不是为了 `--prefer-text`

4.0 release notes 是 “Changes since v3.3.4”，主要包括：

- SDL2 → SDL3；
- flex display；
- `--keep-active`；
- camera torch/zoom；
- window aspect-ratio；
- 其它修复。

没有“新增 `--prefer-text`”，因为 3.3.4 已经有。

所以：

```text
升级 4.x ≠ 获得任意 Unicode SDK 输入
```

---

# 30. 4.x 对你真正有价值的是 `--keep-active`

4.0 新增：

```bash
scrcpy --keep-active
```

它周期性模拟 user activity。

这对 TCP 模式的：

```text
KeepPhysicalScreenOn
```

比 `--stay-awake` 更合理。

因为 `--stay-awake` 依赖 plugged 状态，而 `--keep-active` 是主动 user-activity keepalive。

所以升级 4.1 后可以把当前：

```text
screen_off_timeout = int.MaxValue
```

逐步替换成：

```text
--keep-active
```

这会减少系统 setting 修改。

---

# 31. 为什么仍建议保留 3.3.4 fallback

4.0 开始 SDL2 → SDL3。

你的程序依赖：

- WinForms；
- scrcpy HWND；
- 窗口标题；
- 全局鼠标 hook；
- 多 Session；
- 可能的窗口尺寸/定位；

所以应先并存：

```text
3.3.4
4.1
```

在 Huawei A10 / Huawei A12 / Xiaomi A16 三台设备回归测试后，再把 4.1 设默认。

建议保留：

```text
Advanced → Scrcpy executable
```

允许覆盖版本。

---

# 32. 推荐 `ScrcpyAdapter`

```csharp
public sealed class ScrcpyAdapter
{
    public Version Version { get; }
    public ScrcpyCapabilities Capabilities { get; }

    public IReadOnlyList<string> BuildArguments(
        ScrcpyOptions options)
    {
        // 根据 capability 而不是到处 if version
        ...
    }
}
```

业务层只表达：

```text
I want keep screen on
I want click-to-wake
I want SDK/UHID
I want Unicode text
```

Adapter 决定具体参数。

---

# 33. 推荐默认配置

普通办公/中文：

```text
Keyboard = UHID
PC Text Input = ClipboardPaste
Screen = ScreenOffClickToWake
```

scrcpy 3.3.4：

```bash
scrcpy -s DEVICE \
  --keyboard=uhid \
  --turn-screen-off \
  --screen-off-timeout=10
```

如果用户需要 Android 中文候选：

```bash
settings put secure show_ime_with_hard_keyboard 1
```

SDK 兼容：

```bash
scrcpy -s DEVICE \
  --keyboard=sdk \
  --prefer-text \
  --turn-screen-off \
  --screen-off-timeout=10
```

但 UI 不要承诺“Windows 中文直接输入”。

---

# 34. 最终开发优先级

## P0：立即修复

### AdbHelper.cs

- 修复 `SupportsPreferText()`；最好改成 `--help` capability probe。
- ClickToWake 加：
  ```text
  --turn-screen-off
  --screen-off-timeout=5~10
  ```
- ClickToWake 不加 `--stay-awake`。
- `WakeDeviceAsync()` 优先只用 `KEYCODE_WAKEUP 224`，不要并发两个 power command。

## P1：中文体验

增加：

```text
PC 输入法发送 TextBox
```

走：

```text
Windows IME → Clipboard → scrcpy Ctrl+V → Android
```

这是投入产出最高的中文方案。

## P2：系统设置保护

ClickToWake 启动时检查：

```bash
settings get global stay_on_while_plugged_in
```

如果产品要求“充电也自动灭”，临时 set 0 并恢复。

## P3：升级 4.1

收益主要是：

```text
--keep-active
Android 16 当前维护
SDL3 / 新修复
```

不是 Unicode SDK 注入。

## P4：高级 Unicode

Clipboard 不能覆盖的 App，再考虑：

```text
自研 Android IME companion
```

---

# 35. 测试矩阵

| 测试 | Huawei A10 | Huawei A12 | Xiaomi A16 |
|---|---|---|---|
| SDK ASCII |  |  |  |
| SDK `--prefer-text` |  |  |  |
| SDK Windows IME 中文 |  |  |  |
| UHID English |  |  |  |
| UHID + Android 中文 IME |  |  |  |
| Clipboard 中文 Ctrl+V |  |  |  |
| `--turn-screen-off` |  |  |  |
| 点击 WAKEUP 224 |  |  |  |
| 5/10s timeout 再灭屏 |  |  |  |
| 充电状态 timeout |  |  |  |
| session exit 恢复 settings |  |  |  |

---

# 36. 调试命令

## scrcpy capability

```bat
scrcpy --version
scrcpy --help | findstr /i "prefer-text"
scrcpy --help | findstr /i "turn-screen-off"
scrcpy --help | findstr /i "screen-off-timeout"
scrcpy --help | findstr /i "keep-active"
```

## Android power settings

```bash
adb shell settings get system screen_off_timeout
adb shell settings get secure sleep_timeout
adb shell settings get global stay_on_while_plugged_in
```

## Power state

```bash
adb shell dumpsys power
```

## 单向 sleep/wake

```bash
adb shell input keyevent 223
adb shell input keyevent 224
```

## Android 15+

```bash
adb shell cmd display power-off 0
adb shell cmd display power-on 0
```

## 3.3.4 TCP 黑屏测试

```bash
scrcpy -s 192.168.43.1:5555 \
  --turn-screen-off \
  --screen-off-timeout=5
```

## SDK prefer text

```bash
scrcpy -s DEVICE \
  --keyboard=sdk \
  --prefer-text
```

## UHID

```bash
scrcpy -s DEVICE \
  --keyboard=uhid
```

```bash
adb shell am start -a android.settings.HARD_KEYBOARD_SETTINGS
```

---

# 37. 最终推荐架构

```text
ScrcpySession
│
├─ ScrcpyAdapter
│   ├─ Capability Detection
│   └─ Argument Builder
│
├─ DeviceSettingLease
│   ├─ screen_off_timeout
│   ├─ stay_on_while_plugged_in
│   └─ show_ime_with_hard_keyboard
│
├─ ScreenWakeHelper
│   └─ KEYCODE_WAKEUP 224
│
└─ TextInputBridge
    ├─ Keyboard: SDK / UHID
    ├─ Unicode: ClipboardPaste
    └─ Optional: AndroidImeCompanion
```

最重要的两个拆分：

```text
实时键盘按键 ≠ Unicode 文本提交
```

以及：

```text
Physical display off ≠ Whole-device suspend
```

---

# 38. 参考资料

## scrcpy 3.3.4 Keyboard

https://github.com/Genymobile/scrcpy/blob/v3.3.4/doc/keyboard.md

确认：

- SDK limited to ASCII and some other chars；
- `--prefer-text` 已存在；
- UHID 支持 physical keyboard + Android IME；
- UHID 可工作在 TCP/IP。

## scrcpy 3.3.4 Device

https://github.com/Genymobile/scrcpy/blob/v3.3.4/doc/device.md

确认：

- `--stay-awake`；
- TCP-only/no-plugged 场景 stay-awake 无效果；
- `--screen-off-timeout`；
- `--turn-screen-off`；
- Android 15 `cmd display power-off/on`。

## scrcpy 3.3.4 Control / Clipboard

https://github.com/Genymobile/scrcpy/blob/v3.3.4/doc/control.md

确认：

- Ctrl+V 同步 PC→Android clipboard 再 paste；
- `MOD+Shift+V` 是 key-event 注入，可能破坏 non-ASCII；
- clipboard 有敏感数据风险。

## scrcpy text injection implementation

https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/control/Controller.java

`injectText()` 最终依赖 `KeyCharacterMap.getEvents()`。

## Huawei Android 10 中文失败案例

https://github.com/Genymobile/scrcpy/issues/6653

## scrcpy Releases

https://github.com/Genymobile/scrcpy/releases

截至 2026-08-24 最新正式版：scrcpy 4.1（2026-07-12）。

## Android KeyEvent

https://developer.android.com/reference/android/view/KeyEvent

定义：

```text
KEYCODE_POWER 26
KEYCODE_SLEEP 223
KEYCODE_WAKEUP 224
```

## AOSP Settings / sleep_timeout

https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/provider/Settings.java

## AOSP PowerManager suspend blocker

https://android.googlesource.com/platform/frameworks/base/+/master/core/proto/android/server/powermanagerservice.proto

## AOSP force suspend

https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/power/PowerManagerService.java

## `svc power forcesuspend`

https://android.googlesource.com/platform/frameworks/base/+/8aeade4/cmds/svc/src/com/android/commands/svc/PowerCommand.java

## ADBKeyBoard

https://github.com/senzhk/ADBKeyBoard

提供 Unicode/Base64 broadcast/InputMethodService 路线，并有 Android 16 修复版本。
