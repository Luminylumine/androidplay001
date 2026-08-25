using System.Diagnostics;
using System.IO;

namespace AdbManager;

public class AdbHelper
{
    // Resolve repo root by walking up from AppContext.BaseDirectory looking for .git/
    private static readonly Lazy<string> _repoRoot = new(() =>
    {
        var d = AppContext.BaseDirectory;
        while (!string.IsNullOrEmpty(d))
        {
            if (Directory.Exists(Path.Combine(d, ".git"))) return d;
            d = Path.GetDirectoryName(d);
        }
        return null!;
    });

    private static string FindExecutable(string repoRelative, string fallbackCmd)
    {
        if (_repoRoot.Value != null)
        {
            var full = Path.Combine(_repoRoot.Value, repoRelative);
            if (File.Exists(full)) return full;
        }
        return fallbackCmd;
    }

    // Prefer repo-local tool copies; fall back to system PATH ("adb", "scrcpy").
    private static readonly string AdbPath    = FindExecutable(@"tools\android-sdk\platform-tools\adb.exe", "adb");
    private static readonly string ScrcpyPath = FindExecutable(@"common\sdks\scrcpy\scrcpy.exe",
                                FindExecutable(@"tools\scrcpy\scrcpy.exe", "scrcpy"));

    public static async Task<List<DeviceInfo>> GetDevicesAsync()
    {
        var devices = new List<DeviceInfo>();

        var output = await RunCommandAsync($"{AdbPath} devices -l");
        ParseDevicesOutput(output, devices);

        // Also scan mDNS services for Android 11+ Wireless Debugging
        try
        {
            var mdnsOutput = await RunCommandAsync($"{AdbPath} mdns services", timeoutMs: 5000);
            ParseMdnsOutput(mdnsOutput, devices);
        }
        catch
        {
            // mDNS might not be available, silently ignore
        }

        // USB 设备自动尝试 TCP 无线调试：获取 WiFi IP 并连接
        // 注意：保留原有 USB 设备，TCP 仅作为额外连接添加
        var usbDevices = devices.Where(d => d.IsUsb).ToList();
        var tcpConnected = new List<string>(); // 记录成功连接的 TCP ID
        foreach (var usb in usbDevices)
        {
            try
            {
                var tcpId = await TryAutoConnectUsbToTcpAsync(usb);
                if (!string.IsNullOrEmpty(tcpId))
                    tcpConnected.Add(tcpId);
            }
            catch
            {
                // 自动连接失败不影响其他设备
            }
        }

        // 拉取最新设备列表，但保留原有的 USB 设备条目
        if (tcpConnected.Count > 0 || usbDevices.Count > 0)
        {
            var newDevices = new List<DeviceInfo>();
            output = await RunCommandAsync($"{AdbPath} devices -l");
            ParseDevicesOutput(output, newDevices);

            // 合并：保留原有 USB 设备 + 新增的 TCP 设备
            // 如果重新枚举后 USB 设备还在，用新数据更新；如果不在，保留旧记录
            foreach (var existing in devices.ToList())
            {
                var updated = newDevices.FirstOrDefault(d => d.Id == existing.Id);
                if (updated != null)
                {
                    devices[devices.IndexOf(existing)] = updated;
                }
                // 如果重新枚举找不到了（可能 tcpip 导致切换），保留原有条目
            }

            // 添加新发现的 TCP 设备
            foreach (var nd in newDevices)
            {
                if (!devices.Any(d => d.Id == nd.Id))
                    devices.Add(nd);
            }

            // mDNS 重新扫描
            try
            {
                var mdnsOutput = await RunCommandAsync($"{AdbPath} mdns services", timeoutMs: 5000);
                ParseMdnsOutput(mdnsOutput, devices);
            }
            catch
            {
            }
        }

        // Get device models
        foreach (var device in devices)
        {
            try
            {
                var model = await RunCommandAsync($"{AdbPath} -s {device.Id} shell getprop ro.product.model", timeoutMs: 3000);
                device.Name = model.Trim();
            }
            catch
            {
            }
        }

        return devices;
    }

    /// <summary>
    /// 对 USB 设备：读取 WiFi IP，尝试直接 connect 到 TCP 调试端口。
    /// 与旧版不同：不执行 adb tcpip 5555（该命令可能断开 USB），
    /// 而是直接尝试 adb connect，因为用户通常已在手机上启用了无线调试。
    /// 如果未启用，则通过 adb shell 在不影响 USB 的前提下开启 TCP 调试。
    /// 返回成功连接的 TCP 设备 ID（如 "192.168.1.100:5555"），失败返回 null。
    /// </summary>
    private static async Task<string?> TryAutoConnectUsbToTcpAsync(DeviceInfo usb)
    {
        var ip = await GetWifiIpAsync(usb.Id);
        if (string.IsNullOrEmpty(ip))
            return null;

        var tcpTarget = $"{ip}:5555";

        // 1. 先直接尝试 connect（若手机已开启 TCP 调试，这是最快的路径）
        var connectResult = await RunCommandAsync($"{AdbPath} connect {tcpTarget}", timeoutMs: 5000);
        if (connectResult.Contains("connected") || connectResult.Contains("already connected"))
            return tcpTarget;

        // 2. 如果直接 connect 失败，可能 TCP 调试未开启。尝试用 shell 开启（不影响 USB）
        try
        {
            // 尝试通过 shell 开启 TCP 调试（Android 11+ 推荐方式，不影响 USB 连接）
            await RunCommandAsync($"{AdbPath} -s {usb.Id} shell settings put global adb_enabled 1", timeoutMs: 3000);
            await RunCommandAsync($"{AdbPath} -s {usb.Id} shell setprop service.adb.tcp.port 5555", timeoutMs: 3000);
            await Task.Delay(500);

            // 再次尝试连接
            connectResult = await RunCommandAsync($"{AdbPath} connect {tcpTarget}", timeoutMs: 5000);
            if (connectResult.Contains("connected") || connectResult.Contains("already connected"))
                return tcpTarget;
        }
        catch
        {
            // shell 方式失败，继续尝试其他方式
        }

        // 3. 最后兜底：使用传统的 adb tcpip（注意：这可能导致 USB 断开！）
        // 仅在上述方法都失败时使用
        try
        {
            var tcpipResult = await RunCommandAsync($"{AdbPath} -s {usb.Id} tcpip 5555", timeoutMs: 5000);
            if (tcpipResult.Contains("restarting") || tcpipResult.Contains("cannot run"))
            {
                await Task.Delay(500);
                connectResult = await RunCommandAsync($"{AdbPath} connect {tcpTarget}", timeoutMs: 5000);
                if (connectResult.Contains("connected") || connectResult.Contains("already connected"))
                    return tcpTarget;
            }
        }
        catch
        {
        }

        return null;
    }

    /// <summary>
    /// 通过 adb shell ip addr show wlan0 解析 WiFi IP。
    /// 部分厂商/旧系统可能把接口命名为 wlan0、eth0 或 rmnet_data，多接口按顺序兜底。
    /// </summary>
    public static async Task<string?> GetWifiIpAsync(string deviceId)
    {
        var output = await RunCommandAsync($"{AdbPath} -s {deviceId} shell ip addr show", timeoutMs: 5000);
        var lines = output.Split('\n');
        string? candidate = null;
        foreach (var line in lines)
        {
            var match = System.Text.RegularExpressions.Regex.Match(line, @"inet\s+(\d+\.\d+\.\d+\.\d+)/");
            if (!match.Success) continue;
            var ip = match.Groups[1].Value;
            if (ip.StartsWith("127.")) continue;
            // 优先取 wlan0 接口
            if (line.Contains("wlan0")) return ip;
            candidate = ip;
        }
        return candidate;
    }

    private static void ParseDevicesOutput(string output, List<DeviceInfo> devices)
    {
        foreach (var line in output.Split('\n').Select(l => l.Trim()).Where(l => !string.IsNullOrEmpty(l)))
        {
            if (line.StartsWith("List of devices")) continue;

            var parts = line.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length >= 2 && parts[1] == "device")
            {
                var id = parts[0];
                if (devices.Any(d => d.Id == id)) continue;

                var isUsb = !id.Contains(':');
                var device = new DeviceInfo
                {
                    Id = id,
                    IsUsb = isUsb
                };

                if (!isUsb)
                {
                    var ipParts = id.Split(':');
                    if (ipParts.Length == 2)
                    {
                        device.IpAddress = ipParts[0];
                        if (int.TryParse(ipParts[1], out var port))
                            device.Port = port;
                    }
                }

                // Try to extract model from -l output: product:xxx model:xxx device:xxx
                for (int i = 2; i < parts.Length; i++)
                {
                    if (parts[i].StartsWith("model:"))
                    {
                        device.Name = parts[i].Substring(6);
                    }
                }

                devices.Add(device);
            }
        }
    }

    private static void ParseMdnsOutput(string output, List<DeviceInfo> devices)
    {
        // Output format:
        // Service name: _adb-tls-connect._tcp.local.
        //   Host: ...
        //   Port: ...
        // or
        // Service found: ...

        var lines = output.Split('\n').Select(l => l.Trim()).ToList();

        for (int i = 0; i < lines.Count; i++)
        {
            var line = lines[i];

            // Look for service info patterns
            if (line.Contains("Service name:") || line.Contains("Host:"))
            {
                var device = new DeviceInfo { IsUsb = false };

                // Try to get host
                if (line.StartsWith("Host:"))
                {
                    device.IpAddress = line.Substring(5).Trim().TrimEnd('.');
                }

                // Look for port in next lines
                for (int j = i; j < Math.Min(i + 5, lines.Count); j++)
                {
                    if (lines[j].StartsWith("Port:"))
                    {
                        var portStr = lines[j].Substring(5).Trim();
                        if (int.TryParse(portStr, out var port))
                            device.Port = port;
                    }
                }

                // Construct ADB endpoint
                if (!string.IsNullOrEmpty(device.IpAddress) && device.Port.HasValue)
                {
                    var id = $"{device.IpAddress}:{device.Port}";
                    if (!devices.Any(d => d.Id == id))
                    {
                        device.Id = id;
                        devices.Add(device);
                    }
                }
            }
            else if (line.Contains("Service found:") || line.Contains("Device found:"))
            {
                // Alternative format
                var match = System.Text.RegularExpressions.Regex.Match(line, @"(\d+\.\d+\.\d+\.\d+):(\d+)");
                if (match.Success)
                {
                    var ip = match.Groups[1].Value;
                    var port = int.Parse(match.Groups[2].Value);
                    var id = $"{ip}:{port}";
                    if (!devices.Any(d => d.Id == id))
                    {
                        devices.Add(new DeviceInfo
                        {
                            Id = id,
                            IpAddress = ip,
                            Port = port,
                            IsUsb = false
                        });
                    }
                }
            }
        }
    }

    public static async Task<string> RunCommandAsync(string command, int timeoutMs = 10000)
    {
        // Parse command into fileName and arguments
        string fileName;
        string arguments;

        var spaceIndex = command.IndexOf(' ');
        if (spaceIndex >= 0)
        {
            fileName = command.Substring(0, spaceIndex);
            arguments = command.Substring(spaceIndex + 1);
        }
        else
        {
            fileName = command;
            arguments = string.Empty;
        }

        using var process = new Process();
        process.StartInfo = new ProcessStartInfo
        {
            FileName = fileName,
            Arguments = arguments,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true
        };

        using var cts = new CancellationTokenSource(timeoutMs);

        try
        {
            process.Start();
            var outputTask = process.StandardOutput.ReadToEndAsync();
            var errorTask = process.StandardError.ReadToEndAsync();

            await process.WaitForExitAsync(cts.Token);

            var output = await outputTask;
            var error = await errorTask;

            return string.IsNullOrEmpty(output) ? error : output;
        }
        catch (OperationCanceledException)
        {
            if (!process.HasExited)
                process.Kill();
            return "Command timed out";
        }
    }

    public static async Task<string> ShellExecAsync(string deviceId, string shellCommand, int timeoutMs = 10000)
    {
        return await RunCommandAsync($"{AdbPath} -s {deviceId} shell \"{shellCommand}\"", timeoutMs);
    }

    public static async Task<string> ListDirectoryAsync(string deviceId, string path)
    {
        return await RunCommandAsync($"{AdbPath} -s {deviceId} shell ls -la \"{path}\"");
    }

    public static async Task PullFileAsync(string deviceId, string remotePath, string localPath)
    {
        await RunCommandAsync($"{AdbPath} -s {deviceId} pull \"{remotePath}\" \"{localPath}\"", timeoutMs: 300000);
    }

    public static async Task PushFileAsync(string deviceId, string localPath, string remotePath)
    {
        await RunCommandAsync($"{AdbPath} -s {deviceId} push \"{localPath}\" \"{remotePath}\"", timeoutMs: 300000);
    }

    public static async Task<bool> ConnectAsync(string ipAddress, int port)
    {
        var output = await RunCommandAsync($"{AdbPath} connect {ipAddress}:{port}");
        return output.Contains("connected");
    }

    public static async Task<bool> PairAsync(string ipAddress, int port, string pairingCode)
    {
        var output = await RunCommandAsync($"{AdbPath} pair {ipAddress}:{port} {pairingCode}");
        return output.Contains("Successfully paired");
    }

    // 用于定位 scrcpy 窗口（点击唤醒用）的唯一标题前缀
    public const string ScrcpyWindowPrefix = "AdbManager_Mirror_";
    public static string ScrcpyWindowTitle(string deviceId) => ScrcpyWindowPrefix + deviceId;

    private static string? _cachedScrcpyVersion;

    public static async Task<string?> GetScrcpyVersionAsync()
    {
        if (_cachedScrcpyVersion != null)
            return _cachedScrcpyVersion;

        try
        {
            var output = await RunCommandAsync($"{ScrcpyPath} --version", timeoutMs: 3000);
            // 输出格式: scrcpy 3.3.4 <https://github.com/Genymobile/scrcpy>
            var match = System.Text.RegularExpressions.Regex.Match(output, @"scrcpy\s+(\d+\.\d+\.\d+)");
            if (match.Success)
            {
                _cachedScrcpyVersion = match.Groups[1].Value;
                return _cachedScrcpyVersion;
            }
        }
        catch { }
        return null;
    }

    // scrcpy 能力探测：直接解析 `scrcpy --help`，而不是写死版本表。
    // 关键点：scrcpy 3.3.4 就已支持 --prefer-text / --turn-screen-off / --screen-off-timeout，
    // 早期按“major>=4”判断是错误的。探测一次后缓存。
    private static ScrcpyCapabilities? _caps;

    /// <summary>返回已探测到的能力（未探测时返回 Unknown，即所有可选能力视为不可用，保持保守）。</summary>
    public static ScrcpyCapabilities GetCapabilities() => _caps ?? ScrcpyCapabilities.Unknown;

    public static async Task<ScrcpyCapabilities> DetectScrcpyCapabilitiesAsync()
    {
        if (_caps != null) return _caps;
        try
        {
            var text = await RunCommandAsync($"{ScrcpyPath} --help", timeoutMs: 3000);
            _caps = new ScrcpyCapabilities
            {
                PreferText = text.Contains("--prefer-text", StringComparison.Ordinal),
                TurnScreenOff = text.Contains("--turn-screen-off", StringComparison.Ordinal),
                ScreenOffTimeout = text.Contains("--screen-off-timeout", StringComparison.Ordinal),
                KeepActive = text.Contains("--keep-active", StringComparison.Ordinal),
                UhidKeyboard = text.Contains("--keyboard", StringComparison.Ordinal)
                    && text.Contains("uhid", StringComparison.OrdinalIgnoreCase)
            };
        }
        catch
        {
            // 探测失败保持 Unknown，后续不追加可选参数，仍可正常投屏
        }
        return _caps ?? ScrcpyCapabilities.Unknown;
    }

    public static Process StartScrcpy(string deviceId, ScrcpyOptions options)
    {
        // 参数按独立 token 组织，不拼接空格，避免被 QuoteIfNeeded 错误转义
        var args = new List<string>
        {
            "-s", deviceId,
            "--window-title", ScrcpyWindowTitle(deviceId)
        };

        var caps = GetCapabilities();

        // 屏幕模式
        switch (options.ScreenMode)
        {
            case ScreenMode.StayAwake:
                // 不黑屏：让屏幕常亮（USB/TCP 均有效）
                args.Add("--stay-awake");
                break;
            case ScreenMode.ClickToWake:
                // 黑屏可点亮：启动即熄屏，并设短超时（由 scrcpy 负责保存/恢复 screen_off_timeout，
                // 避免与 AdbManager 手动 Get/Put 竞争）。TCP 连接同样有效。
                if (caps.TurnScreenOff)
                    args.Add("--turn-screen-off");
                if (caps.ScreenOffTimeout)
                    args.Add("--screen-off-timeout=5");
                // 注意：ClickToWake 绝不传 --stay-awake / --keep-active，否则会阻止熄屏
                break;
        }

        // 键盘模式
        switch (options.KeyboardMode)
        {
            case KeyboardMode.Uhid:
                args.Add("--keyboard=uhid");
                break;
            case KeyboardMode.Sdk:
                args.Add("--keyboard=sdk");
                if (caps.PreferText)
                    args.Add("--prefer-text");
                break;
            case KeyboardMode.Disabled:
                args.Add("--keyboard=disabled");
                break;
        }

        if (options.NoAudio)
            args.Add("--no-audio");

        if (options.MaxFps.HasValue)
        {
            args.Add("--max-fps");
            args.Add(options.MaxFps.Value.ToString());
        }

        if (options.BitRate.HasValue)
            args.Add($"--video-bit-rate={options.BitRate.Value}M");

        if (!string.IsNullOrEmpty(options.MaxSize))
        {
            args.Add("--max-size");
            args.Add(options.MaxSize);
        }

        var arguments = string.Join(" ", args.Select(QuoteIfNeeded));

        var process = new Process();
        process.StartInfo = new ProcessStartInfo
        {
            FileName = ScrcpyPath,
            Arguments = arguments,
            UseShellExecute = false,
            CreateNoWindow = true
        };

        process.Start();
        return process;
    }

    private static string QuoteIfNeeded(string arg)
    {
        return arg.Contains(' ') ? "\"" + arg + "\"" : arg;
    }

    // ---- 设备设置读写（用于“不黑屏”/“隐藏软键盘”的保存与恢复）----

    public static async Task<string> GetSettingAsync(string deviceId, string ns, string key)
    {
        var output = await RunCommandAsync($"{AdbPath} -s {deviceId} shell settings get {ns} {key}", timeoutMs: 5000);
        return output.Trim();
    }

    public static async Task SetSettingAsync(string deviceId, string ns, string key, string value)
    {
        await RunCommandAsync($"{AdbPath} -s {deviceId} shell settings put {ns} {key} {value}", timeoutMs: 5000);
    }

    // 点亮屏幕：只发 KEYCODE_WAKEUP(224)。
    // 224 语义是“设备睡着→唤醒；已醒→无操作”，天然幂等，适合自动化，覆盖 Android 10+。
    // 不再并发 cmd display power-on 0（仅 Android 15+ 支持，且并发两条电源命令无意义）。
    public static async Task WakeDeviceAsync(string deviceId)
    {
        try
        {
            await RunCommandAsync($"{AdbPath} -s {deviceId} shell input keyevent 224", timeoutMs: 4000);
        }
        catch
        {
            // 唤醒失败忽略（例如设备临时离线）
        }
    }

    // 打开手机“物理键盘”设置页（用于一次性配置 UHID 键盘布局）
    public static async Task OpenPhysicalKeyboardSettingsAsync(string deviceId)
    {
        await RunCommandAsync($"{AdbPath} -s {deviceId} shell am start -a android.settings.HARD_KEYBOARD_SETTINGS", timeoutMs: 5000);
    }
}

public enum ScreenMode
{
    StayAwake,   // 不黑屏（屏幕常亮）
    ClickToWake  // 黑屏但可点亮（点击投屏窗口唤醒）
}

public enum KeyboardMode
{
    Sdk,         // 兼容模式（推荐，支持中文输入法）
    Uhid,        // 电脑键盘（UHID 物理键盘模拟，部分旧系统不支持）
    Disabled     // 禁用键盘转发
}

public class ScrcpyOptions
{
    public ScreenMode ScreenMode { get; set; } = ScreenMode.StayAwake;
    public KeyboardMode KeyboardMode { get; set; } = KeyboardMode.Sdk;
    public bool NoAudio { get; set; } = true;
    public int? MaxFps { get; set; } = 60;
    public int? BitRate { get; set; } = 8;
    public string? MaxSize { get; set; } = "1024";
}

/// <summary>
/// 通过 `scrcpy --help` 探测到的能力开关。按能力（而非版本号）决定是否追加可选参数。
/// </summary>
public sealed class ScrcpyCapabilities
{
    public bool PreferText { get; init; }       // --prefer-text（3.3.4 已支持）
    public bool TurnScreenOff { get; init; }    // --turn-screen-off（3.3.4 已支持）
    public bool ScreenOffTimeout { get; init; } // --screen-off-timeout=<秒>（3.3.4 已支持）
    public bool KeepActive { get; init; }       // --keep-active（仅 4.0+）
    public bool UhidKeyboard { get; init; }     // --keyboard=uhid

    /// <summary>未探测成功时的保守默认：全部视为不可用。</summary>
    public static readonly ScrcpyCapabilities Unknown = new ScrcpyCapabilities();
}
