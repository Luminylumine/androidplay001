using System.Diagnostics;
using System.IO;
using System.Text;

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
    private static readonly string LogDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AdbManager", "Logs");

    public static string GetScrcpyLogPath(string deviceId)
    {
        try
        {
            Directory.CreateDirectory(LogDir);
            var safeId = SanitizeFileName(deviceId);
            if (string.IsNullOrEmpty(safeId)) safeId = "device";
            return Path.Combine(LogDir, $"scrcpy_{safeId}_{DateTime.Now:yyyyMMdd_HHmmss_fff}.log");
        }
        catch
        {
            // 如果默认日志目录不可用，降级到临时目录
            var fallbackDir = Path.GetTempPath();
            var safeId = SanitizeFileName(deviceId) ?? "device";
            return Path.Combine(fallbackDir, $"scrcpy_{safeId}_{DateTime.Now:yyyyMMdd_HHmmss_fff}.log");
        }
    }

    public static string SanitizeFileName(string name)
    {
        return string.Join("_", name.Split(Path.GetInvalidFileNameChars(), StringSplitOptions.RemoveEmptyEntries));
    }

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

        // 取型号（并行，避免多台设备串行拖慢刷新）
        await Task.WhenAll(devices.Select(async d =>
        {
            try
            {
                var model = await RunCommandAsync($"{AdbPath} -s {d.Id} shell getprop ro.product.model", timeoutMs: 3000);
                d.Name = model.Trim();
            }
            catch
            {
            }
        }));

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
        // 只做“安全的直接连接”，绝不调用 `adb tcpip`：
        // `adb tcpip 5555` 会重启 adbd 从而断开 USB，违反“保留 USB、TCP 仅作额外连接”的要求。
        // 因此仅当手机已开启无线调试(端口 5555)时才会连上；否则保持 USB 不动、快速返回。
        var ip = await GetWifiIpAsync(usb.Id);
        if (string.IsNullOrEmpty(ip))
            return null;

        var tcpTarget = $"{ip}:5555";
        try
        {
            var connectResult = await RunCommandAsync($"{AdbPath} connect {tcpTarget}", timeoutMs: 2000);
            return (connectResult.Contains("connected") || connectResult.Contains("already connected"))
                ? tcpTarget
                : null;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// 解析设备的 WiFi IP。只认 wlan* 接口的 inet 地址——绝不用蜂窝(rmnet)/USB-ethernet 地址，
    /// 否则会对一个不可达地址做 adb connect 白等超时。没有 WiFi 连接则返回 null。
    /// </summary>
    public static async Task<string?> GetWifiIpAsync(string deviceId)
    {
        var output = await RunCommandAsync($"{AdbPath} -s {deviceId} shell ip addr show", timeoutMs: 5000);
        string? currentIface = null;
        foreach (var rawLine in output.Split('\n'))
        {
            var line = rawLine.Trim();
            // 接口定义行形如 "2: wlan0: <...>"
            var ifaceMatch = System.Text.RegularExpressions.Regex.Match(line, @"^\d+:\s+([a-zA-Z0-9_]+):");
            if (ifaceMatch.Success)
            {
                currentIface = ifaceMatch.Groups[1].Value;
                continue;
            }
            if (currentIface == null || !currentIface.StartsWith("wlan")) continue;
            var m = System.Text.RegularExpressions.Regex.Match(line, @"inet\s+(\d+\.\d+\.\d+\.\d+)/");
            if (m.Success && !m.Groups[1].Value.StartsWith("127."))
                return m.Groups[1].Value;
        }
        return null;
    }

    /// <summary>
    /// 手动把 USB 设备切换到 TCP 模式（adb tcpip 5555），随后连上 ip:5555。
    /// 注意：会重启 adbd、断开 USB（adb 固有限制），完成后设备仅经 TCP 可达；
    /// 恢复 USB 需重新插拔或执行 `adb usb`。由用户显式触发（“启用TCP”菜单），不自动执行。
    /// 返回新的 TCP 设备 ID（ip:5555）；无 WiFi 或失败返回 null。
    /// </summary>
    public static async Task<string?> EnableTcpOverUsbAsync(DeviceInfo usb)
    {
        var ip = await GetWifiIpAsync(usb.Id);
        if (string.IsNullOrEmpty(ip))
            return null; // 没有 WiFi，无法走 TCP

        var tcpipResult = await RunCommandAsync($"{AdbPath} -s {usb.Id} tcpip 5555", timeoutMs: 10000);
        if (!tcpipResult.Contains("restarting") && !tcpipResult.Contains("cannot run"))
            return null; // tcpip 未生效

        await Task.Delay(1200); // adbd 重启需要片刻

        var tcpTarget = $"{ip}:5555";
        var connectResult = await RunCommandAsync($"{AdbPath} connect {tcpTarget}", timeoutMs: 5000);
        return (connectResult.Contains("connected") || connectResult.Contains("already connected"))
            ? tcpTarget
            : null;
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
            CreateNoWindow = true,
            // adb 输出为 UTF-8；不强制则中文 Windows 默认按 GBK 解码 → 中文文件名乱码（pull stat 失败）
            StandardOutputEncoding = System.Text.Encoding.UTF8,
            StandardErrorEncoding = System.Text.Encoding.UTF8
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
                process.Kill(entireProcessTree: true);
            return "Command timed out";
        }
    }

    public static async Task<string> ShellExecAsync(string deviceId, string shellCommand, int timeoutMs = 10000)
    {
        var result = await ShellCommandResultAsync(deviceId, shellCommand, timeoutMs);
        return result.Output;
    }

    public static async Task<string> ListDirectoryAsync(string deviceId, string path)
    {
        var result = await ListDirectoryResultAsync(deviceId, path);
        return result.Output;
    }

    /// <summary>
    /// 拉取远端文件（SYNC 协议，不经远端 shell 解析，路径含空格/中文/引号安全）。
    /// <paramref name="diagnostics"/> 回填 adb 的 stdout/stderr 仅用于诊断；
    /// 文件内容只认本地落盘结果 + 调用方校验，pull 的 host exit code 可在此通道信。
    /// </summary>
    public static async Task PullFileAsync(string deviceId, string remotePath, string localPath, int timeoutMs = 300000, CancellationToken ct = default, StringBuilder? diagnostics = null)
    {
        var psi = new ProcessStartInfo
        {
            FileName = AdbPath,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8,
            StandardErrorEncoding = System.Text.Encoding.UTF8
        };
        psi.ArgumentList.Add("-s");
        psi.ArgumentList.Add(deviceId);
        psi.ArgumentList.Add("pull");
        psi.ArgumentList.Add(remotePath);
        psi.ArgumentList.Add(localPath);

        using var process = new Process { StartInfo = psi };
        process.Start();
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
        linked.CancelAfter(timeoutMs);

        var outTask = process.StandardOutput.ReadToEndAsync(ct);
        var errTask = process.StandardError.ReadToEndAsync(ct);
        try
        {
            await process.WaitForExitAsync(linked.Token);
        }
        catch (OperationCanceledException)
        {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch { }
            throw new TimeoutException("adb pull 超时");
        }

        var outp = await outTask;
        var errp = await errTask;
        if (diagnostics != null)
        {
            diagnostics.Append(outp);
            if (errp.Length > 0) diagnostics.Append(" | ").Append(errp);
        }
        if (process.ExitCode != 0)
            throw new IOException($"adb pull 失败 (exit {process.ExitCode}): {(errp + outp).Trim()}");
    }

    /// <summary>
    /// 推送本地文件到远端（SYNC 协议，ArgumentList 结构化传参：本地/远端路径含空格/中文/引号均安全）。
    /// 同名文件直接覆盖（与资源管理器"复制粘贴"的替换语义一致）。
    /// </summary>
    public static async Task PushFileAsync(string deviceId, string localPath, string remotePath, int timeoutMs = 300000, CancellationToken ct = default)
    {
        var psi = new ProcessStartInfo
        {
            FileName = AdbPath,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8,
            StandardErrorEncoding = System.Text.Encoding.UTF8
        };
        psi.ArgumentList.Add("-s");
        psi.ArgumentList.Add(deviceId);
        psi.ArgumentList.Add("push");
        psi.ArgumentList.Add(localPath);
        psi.ArgumentList.Add(remotePath);

        using var process = new Process { StartInfo = psi };
        process.Start();
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
        linked.CancelAfter(timeoutMs);

        var outTask = process.StandardOutput.ReadToEndAsync(ct);
        var errTask = process.StandardError.ReadToEndAsync(ct);
        try
        {
            await process.WaitForExitAsync(linked.Token);
        }
        catch (OperationCanceledException)
        {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch { }
            throw new TimeoutException("adb push 超时");
        }

        var outp = await outTask;
        var errp = await errTask;
        if (process.ExitCode != 0)
            throw new IOException($"adb push 失败 (exit {process.ExitCode}): {(errp + outp).Trim()}");
    }

    /// <summary>
    /// 多源批量拉取（<c>adb pull REMOTE... LOCAL-DIR</c>，SYNC 协议，一次进程拉多个小文件）。
    /// 注意：某个远端文件失败不会中止其余文件，但整体 exit 会变非 0 ——
    /// 因此本方法**不抛异常**，只返回 exit code，调用方必须逐个检查 staging 目录里的实际文件。
    /// </summary>
    public static async Task<bool> PullFilesAsync(string deviceId, string[] remotePaths, string localDir, int timeoutMs = 60000, CancellationToken ct = default)
    {
        Directory.CreateDirectory(localDir);
        var psi = new ProcessStartInfo
        {
            FileName = AdbPath,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8,
            StandardErrorEncoding = System.Text.Encoding.UTF8
        };
        psi.ArgumentList.Add("-s");
        psi.ArgumentList.Add(deviceId);
        psi.ArgumentList.Add("pull");
        foreach (var p in remotePaths) psi.ArgumentList.Add(p);
        psi.ArgumentList.Add(localDir);

        using var process = new Process { StartInfo = psi };
        process.Start();
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
        linked.CancelAfter(timeoutMs);

        var outTask = process.StandardOutput.ReadToEndAsync(ct);
        var errTask = process.StandardError.ReadToEndAsync(ct);
        try
        {
            await process.WaitForExitAsync(linked.Token);
        }
        catch (OperationCanceledException)
        {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch { }
            return false;
        }
        await outTask; await errTask;
        return process.ExitCode == 0;
    }

    // ===== 结构化 ADB 进程执行（新代码统一走这里，避免按空格拼接/引号问题） =====

    /// <summary>POSIX shell 严格引号：把任意字符串安全地作为一个 shell 参数（防注入 / 含特殊字符文件名）。</summary>
    public static string ShellQuote(string value)
        => "'" + value.Replace("'", "'\"'\"'") + "'";

    /// <summary>
    /// 用 <see cref="ProcessStartInfo.ArgumentList"/> 结构化启动进程（文件名 + 参数数组），
    /// 不再按空格拼字符串，避免复杂文件名/引号把 host 参数拆坏。带超时 + 整进程树 kill。
    /// 返回 stdout（若为空则返回 stderr）。
    /// </summary>
    public static async Task<AdbCommandResult> RunArgsResultAsync(string fileName, IEnumerable<string> args, int timeoutMs = 10000, CancellationToken ct = default)
    {
        var psi = new ProcessStartInfo
        {
            FileName = fileName,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            // content query 等输出 UTF-8（中文文件名/图册名），不强制 GBK 解码必乱码
            StandardOutputEncoding = System.Text.Encoding.UTF8,
            StandardErrorEncoding = System.Text.Encoding.UTF8
        };
        foreach (var a in args) psi.ArgumentList.Add(a);

        using var process = new Process { StartInfo = psi };
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
        linked.CancelAfter(timeoutMs);

        try
        {
            process.Start();
            var outTask = process.StandardOutput.ReadToEndAsync(linked.Token);
            var errTask = process.StandardError.ReadToEndAsync(linked.Token);
            try
            {
                await process.WaitForExitAsync(linked.Token);
            }
            catch (OperationCanceledException)
            {
                try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch { }
                throw new TimeoutException("adb 命令超时");
            }
            var output = await outTask;
            var error = await errTask;
            return new AdbCommandResult(process.ExitCode, output, error);
        }
        finally
        {
            process.Dispose();
        }
    }

    /// <summary>结构化 ADB 命令的兼容字符串返回值。需要判断成功/失败时使用 RunArgsResultAsync。</summary>
    public static async Task<string> RunArgsAsync(string fileName, IEnumerable<string> args, int timeoutMs = 10000, CancellationToken ct = default)
    {
        var result = await RunArgsResultAsync(fileName, args, timeoutMs, ct);
        return string.IsNullOrEmpty(result.Stdout) ? result.Stderr : result.Stdout;
    }

    /// <summary>
    /// 执行 <c>adb -s <id> shell <shellArgs...></c>。host 侧结构化传参；
    /// shellArgs 里不要放含空格的裸值，需要时用 <see cref="ShellQuote"/>。
    /// </summary>
    public static Task<string> ShellCommandAsync(string deviceId, string[] shellArgs, int timeoutMs = 20000, CancellationToken ct = default)
    {
        var args = new List<string> { "-s", deviceId, "shell" };
        args.AddRange(shellArgs);
        return RunArgsAsync(AdbPath, args, timeoutMs, ct);
    }

    /// <summary>执行需要可靠退出码的设备 shell 脚本。脚本中的动态值必须通过 ShellQuote 包裹。</summary>
    public static Task<AdbCommandResult> ShellCommandResultAsync(string deviceId, string script, int timeoutMs = 20000, CancellationToken ct = default)
    {
        var args = new[] { "-s", deviceId, "shell", "sh", "-c", script };
        return RunArgsResultAsync(AdbPath, args, timeoutMs, ct);
    }

    public static Task<AdbCommandResult> ListDirectoryResultAsync(string deviceId, string path, CancellationToken ct = default)
        => ShellCommandResultAsync(deviceId, $"ls -laA {ShellQuote(path)}", 20000, ct);

    public static Task<AdbCommandResult> DeleteRemoteAsync(string deviceId, string path, bool directory, CancellationToken ct = default)
        => ShellCommandResultAsync(deviceId, $"rm {(directory ? "-rf" : "-f")} -- {ShellQuote(path)}", 120000, ct);

    public static Task<AdbCommandResult> RenameRemoteAsync(string deviceId, string source, string target, CancellationToken ct = default)
        => ShellCommandResultAsync(deviceId, $"mv -- {ShellQuote(source)} {ShellQuote(target)}", 20000, ct);

    public static Task<AdbCommandResult> CreateRemoteDirectoryAsync(string deviceId, string path, CancellationToken ct = default)
        => ShellCommandResultAsync(deviceId, $"mkdir -p -- {ShellQuote(path)}", 20000, ct);

    // ===== content read 能力负缓存（同一设备 session 内一次失败即停用，避免每张图都吃一次 SecurityException） =====
    private static readonly HashSet<string> _contentReadBroken = new(StringComparer.Ordinal);

    public static bool IsContentReadBroken(string deviceId) => _contentReadBroken.Contains(deviceId);

    public static void MarkContentReadBroken(string deviceId) => _contentReadBroken.Add(deviceId);

    /// <summary>
    /// 读取 content URI 的二进制内容到内存流（provider fallback 用，不落盘）。host 侧结构化传参。
    /// 注意：content CLI 会吞异常且 exit 可能为 0，stdout 可能是一段 Java 异常文本 —— 调用方必须内容校验。
    /// </summary>
    public static async Task ReadContentUriToStreamAsync(string deviceId, string contentUri, Stream dest, int timeoutMs = 120000, CancellationToken ct = default)
    {
        var psi = new ProcessStartInfo
        {
            FileName = AdbPath,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            // stdout 走 BaseStream 二进制透传（编码无关）；stderr 是文本，强制 UTF-8
            StandardErrorEncoding = System.Text.Encoding.UTF8
        };
        psi.ArgumentList.Add("-s");
        psi.ArgumentList.Add(deviceId);
        psi.ArgumentList.Add("exec-out");
        psi.ArgumentList.Add("content");
        psi.ArgumentList.Add("read");
        psi.ArgumentList.Add("--uri");
        psi.ArgumentList.Add(contentUri);

        using var process = new Process { StartInfo = psi };
        process.Start();
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
        linked.CancelAfter(timeoutMs);

        var errTask = process.StandardError.ReadToEndAsync(ct);
        try
        {
            await process.StandardOutput.BaseStream.CopyToAsync(dest, ct);
            await process.WaitForExitAsync(linked.Token);
        }
        catch (OperationCanceledException)
        {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch { }
            throw new TimeoutException("adb content read 超时");
        }

        var error = await errTask;
        if (process.ExitCode != 0)
            throw new IOException($"adb content read 失败 (exit {process.ExitCode}): {error}".Trim());
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

    /// <summary>最近一次启动的 scrcpy 日志路径（多会话下仅代表"最近启动"的那个，崩溃提示辅助用）。</summary>
    public static string? LastScrcpyLogPath { get; private set; }

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
    // 关键点：scrcpy 3.3.4 就已支持 --prefer-text / --keyboard=uhid，早期按“major>=4”判断是错误的。
    // 探测一次后缓存。
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

    // ---- 设备显示电源能力探测（每台设备一次，按 deviceId 缓存）----
    // 物理息屏走“Extinguish 方案”：系统屏幕状态保持 ON，在 SurfaceFlinger/系统层关掉物理面板。
    //   1) scrcpy --turn-screen-off                —— scrcpy 原生 SurfaceControl 路径
    //   2) cmd display power-off / power-on         —— Android 15+ 才有（真断电）
    //   3) 内置 MiniDisplay / set-brightness 0      —— ADB 回退路径
    private static readonly Dictionary<string, DeviceDisplayCaps> _deviceDisplayCaps = new();
    private static readonly object _deviceDisplayCapsLock = new();

    public static DeviceDisplayCaps GetDeviceDisplayCaps(string deviceId)
    {
        lock (_deviceDisplayCapsLock)
            return _deviceDisplayCaps.TryGetValue(deviceId, out var c) ? c : new DeviceDisplayCaps();
    }

    public static async Task<DeviceDisplayCaps> DetectDeviceDisplayCapsAsync(string deviceId)
    {
        var caps = new DeviceDisplayCaps();
        try
        {
            var help = await RunCommandAsync($"{AdbPath} -s {deviceId} shell cmd display help", timeoutMs: 5000);
            caps.DisplayPowerCmd = help.Contains("power-off", StringComparison.Ordinal)
                && help.Contains("power-on", StringComparison.Ordinal);
            caps.DisplaySetBrightnessCmd = help.Contains("set-brightness", StringComparison.Ordinal);
        }
        catch
        {
            // 探测失败按无能力处理，走回退路径
        }

        lock (_deviceDisplayCapsLock)
            _deviceDisplayCaps[deviceId] = caps;
        return caps;
    }

    /// <param name="logPath">可选：日志文件路径。多会话并存时由调用方各自传入，
    /// 避免用 static 字段导致不同会话的日志互相串台。不传则按设备自动生成。</param>
    public static Process StartScrcpy(string deviceId, ScrcpyOptions options, string? logPath = null)
    {
        // 参数按独立 token 组织，不拼接空格，避免被 QuoteIfNeeded 错误转义
        var args = new List<string>
        {
            "-s", deviceId,
            "--window-title", ScrcpyWindowTitle(deviceId)
        };

        var caps = GetCapabilities();

        // scrcpy 的 --turn-screen-off 使用与 Extinguish 相同的
        // SurfaceControl.setDisplayPowerMode(POWER_MODE_OFF) 路径。
        // --stay-awake 只负责防止电源管理策略随后进入睡眠，两者可以同时使用。
        if (options.PhysicalScreenOff && options.OffScheme == PhysicalOffScheme.PowerOff
            && caps.TurnScreenOff)
            args.Add("--turn-screen-off");
        args.Add("--stay-awake");

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

        // 生成/采用日志文件路径（容错：路径生成失败则不记录日志）。
        // 用局部变量 logPath 贯穿整个方法，多会话各自独立，互不串台。
        try
        {
            logPath ??= GetScrcpyLogPath(deviceId);
            LastScrcpyLogPath = logPath;

            System.Diagnostics.Debug.WriteLine($"[Scrcpy] 启动 scrcpy: {ScrcpyPath} {arguments}");
            System.Diagnostics.Debug.WriteLine($"[Scrcpy] 日志文件: {logPath}");

            // 记录启动参数到日志
            File.AppendAllText(logPath, $"=== scrcpy 启动 {DateTime.Now:yyyy-MM-dd HH:mm:ss} ===\n");
            File.AppendAllText(logPath, $"命令行: {ScrcpyPath} {arguments}\n\n");
        }
        catch
        {
            // 日志初始化失败不阻断主流程
            logPath = null;
            LastScrcpyLogPath = null;
            System.Diagnostics.Debug.WriteLine("[Scrcpy] 日志文件初始化失败，继续启动");
        }

        var process = new Process();
        process.StartInfo = new ProcessStartInfo
        {
            FileName = ScrcpyPath,
            Arguments = arguments,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8,
            StandardErrorEncoding = System.Text.Encoding.UTF8
        };

        try
        {
            process.Start();

            // 安全的日志写入辅助方法（捕获局部 logPath，本会话独立）
            void SafeWrite(string prefix, string? data)
            {
                if (data == null || logPath == null) return;
                try
                {
                    File.AppendAllText(logPath, $"{prefix} {data}\n");
                }
                catch
                {
                    // 日志写入失败静默忽略，不影响主流程
                }
            }

            // 异步读取 stdout/stderr 写入日志（带异常保护）
            process.OutputDataReceived += (sender, e) => SafeWrite("[OUT]", e.Data);
            process.ErrorDataReceived += (sender, e) => SafeWrite("[ERR]", e.Data);
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"[Scrcpy] 启动失败: {ex.Message}");
            if (logPath != null)
            {
                try { File.AppendAllText(logPath, $"启动失败: {ex}\n"); } catch { }
            }
            throw;
        }

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

    // ---- 物理息屏 / 点亮（纯 ADB 实现，无需 Extinguish / Shizuku）----
    // 原理：系统屏幕状态保持 ON（--stay-awake + stay_on_while_plugged_in + screen_off_timeout），
    // 在 SurfaceFlinger 层关掉物理面板（POWER_MODE_OFF），投屏与应用运行不受影响。
    //
    // 完全断电需要调隐藏 API SurfaceControl.setDisplayPowerMode（纯 shell 命令做不到），
    // 这里用一个 MiniDisplay dex，经 app_process 以 shell 身份运行（与
    // shizuku_server 完全相同的运行方式，但无需 Shizuku/任何 App）：
    //   CLASSPATH=/data/local/tmp/mini_display.dex app_process /data/local/tmp MiniDisplay start|stop|off|on
    // 主模式 start = 15s 周期巡检守护进程：MIUI 会在系统点亮屏幕时强制把面板拉回 ON，
    // 一次性断电扛不住，守护进程检测到面板非 OFF 就重新断电（stop 杀进程并恢复面板）。
    // display token 按版本反射获取（A14+: services.jar→DisplayControl；A10-13: getInternalDisplayToken；
    // 更早: getBuiltInDisplay）。源码见 MiniDisplay/MiniDisplay.java。

    private static readonly string MiniDisplayDex = FindExecutable(
        @"projects\scrcpy_enhance\MiniDisplay\dex\classes.dex", "");
    private const string MiniDisplayRemoteDex = "/data/local/tmp/mini_display.dex";
    private const string MiniDisplayRemoteLog = "/data/local/tmp/mini_display.log";

    /// <summary>本地 dex 是否存在（不存在则跳过 MiniDisplay 路径，直接走关闭背光）。</summary>
    public static bool MiniDisplayAvailable => File.Exists(MiniDisplayDex);

    /// <summary>确保 MiniDisplay dex 已部署到设备且与本地一致（按 md5 比对，不一致则重新 push）。</summary>
    public static async Task<bool> EnsureMiniDisplayToolAsync(string deviceId)
    {
        if (!MiniDisplayAvailable) return false;
        try
        {
            var localMd5 = ComputeFileMd5(MiniDisplayDex);
            var remote = await RunCommandAsync(
                $"{AdbPath} -s {deviceId} shell md5sum {MiniDisplayRemoteDex}", timeoutMs: 5000);
            var firstLine = remote.Split('\n')[0].Trim();
            if (firstLine.StartsWith(localMd5, StringComparison.OrdinalIgnoreCase))
                return true; // 已部署且与本地一致
            await RunCommandAsync(
                $"{AdbPath} -s {deviceId} push \"{MiniDisplayDex}\" {MiniDisplayRemoteDex}", timeoutMs: 30000);
            return true;
        }
        catch { return false; }
    }

    private static string ComputeFileMd5(string path)
    {
        using var stream = File.OpenRead(path);
        return Convert.ToHexString(System.Security.Cryptography.MD5.HashData(stream)).ToLowerInvariant();
    }

    /// <summary>
    /// 启动 MiniDisplay 15s 周期巡检守护进程（后台运行，立即返回；守护输出重定向到设备日志文件）。
    /// 守护进程先断电一次，之后每 15s 检测面板电源状态，非 OFF 则重新断电
    ///（对抗 MIUI 在系统点亮屏幕时强制把面板拉回 ON 的安全机制）。
    /// 返回 (是否成功, 守护进程启动日志)。
    /// </summary>
    public static async Task<(bool Ok, string Detail)> MiniDisplayStartAsync(string deviceId)
    {
        try
        {
            const string launch =
                $"CLASSPATH={MiniDisplayRemoteDex} app_process /data/local/tmp MiniDisplay start" +
                $" >{MiniDisplayRemoteLog} 2>&1 </dev/null &";
            await RunCommandAsync($"{AdbPath} -s {deviceId} shell \"{launch}\"", timeoutMs: 15000);
            await Task.Delay(1500); // 等守护进程启动并写日志
            var log = await RunCommandAsync(
                $"{AdbPath} -s {deviceId} shell tail -n 1 {MiniDisplayRemoteLog}", timeoutMs: 5000);
            var last = log.Trim();
            if (last.StartsWith("OK", StringComparison.Ordinal))
                return (true, last);
            return (false, string.IsNullOrEmpty(last) ? "守护进程无日志输出" : last);
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    /// <summary>
    /// 停止 MiniDisplay 巡检守护进程并恢复面板正常供电
    ///（守护进程不存在时也执行面板恢复，可安全重复调用，如会话清理路径）。
    /// 返回 (是否成功, 工具输出)。
    /// </summary>
    public static async Task<(bool Ok, string Detail)> MiniDisplayStopAsync(string deviceId)
    {
        try
        {
            var cmd = $"CLASSPATH={MiniDisplayRemoteDex} app_process /data/local/tmp MiniDisplay stop";
            var outp = await RunCommandAsync($"{AdbPath} -s {deviceId} shell \"{cmd}\"", timeoutMs: 20000);
            foreach (var line in outp.Split('\n'))
            {
                var t = line.TrimStart();
                if (t.StartsWith("OK", StringComparison.Ordinal)) return (true, outp.Trim());
                if (t.StartsWith("FAIL", StringComparison.Ordinal)) return (false, outp.Trim());
            }
            return (false, outp.Trim());
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    /// <summary>
    /// 一次性运行 MiniDisplay 对主显示完全断电（off）/恢复供电（on），立即退出
    ///（用于 Doze 恢复等补救场景；常规息屏请走 MiniDisplayStartAsync 守护模式）。
    /// 返回 (是否成功, 工具输出)。工具首行输出 "OK ..." 或 "FAIL ..."；
    /// 注意进程退出时可能 segfault（退出码不可靠），以输出文本为准。
    /// </summary>
    public static async Task<(bool Ok, string Detail)> MiniDisplayPowerAsync(string deviceId, bool off)
    {
        try
        {
            var cmd = string.Format(
                "CLASSPATH={0} app_process /data/local/tmp MiniDisplay {1}",
                MiniDisplayRemoteDex, off ? "off" : "on");
            var outp = await RunCommandAsync($"{AdbPath} -s {deviceId} shell \"{cmd}\"", timeoutMs: 15000);
            foreach (var line in outp.Split('\n'))
            {
                var t = line.TrimStart();
                if (t.StartsWith("OK", StringComparison.Ordinal)) return (true, outp.Trim());
                if (t.StartsWith("FAIL", StringComparison.Ordinal)) return (false, outp.Trim());
            }
            return (false, outp.Trim());
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
    }

    /// <summary>
    /// 读取主显示当前物理状态（用于验证息屏/点亮是否真正生效）：
    /// powerMode —— SurfaceFlinger 显示电源模式（Off = 面板完全断电）；
    /// nits      —— 当前背光亮度（接近 0 = 背光已关）。
    /// 解析不到时对应项返回 null（部分 ROM 输出格式不同），由调用方决定是否信任命令。
    /// </summary>
    public static async Task<(bool? PowerOff, float? Nits)> GetDisplayStateAsync(string deviceId)
    {
        try
        {
            var outp = await RunCommandAsync(
                $"{AdbPath} -s {deviceId} shell dumpsys SurfaceFlinger | grep -oE 'powerMode=[A-Za-z]+|displayBrightnessNits=[0-9.]+' | head -4",
                timeoutMs: 5000);
            bool? powerOff = null;
            float? nits = null;
            foreach (var line in outp.Split('\n'))
            {
                var t = line.Trim();
                if (powerOff == null && t.StartsWith("powerMode=", StringComparison.Ordinal))
                    powerOff = t.Length > "powerMode=".Length
                        && string.Equals(t.Substring("powerMode=".Length), "Off", StringComparison.OrdinalIgnoreCase);
                else if (t.StartsWith("displayBrightnessNits=", StringComparison.Ordinal)
                         && float.TryParse(t.Substring("displayBrightnessNits=".Length),
                              System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var v))
                    nits = v;
                if (powerOff != null && nits != null) break;
            }
            return (powerOff, nits);
        }
        catch { return (null, null); }
    }

    /// <summary>判定物理面板当前是否处于"熄灭"状态（完全断电 或 背光接近 0）；无法判定时返回 null。</summary>
    public static async Task<bool?> IsDisplayPhysicallyOffAsync(string deviceId)
    {
        var (powerOff, nits) = await GetDisplayStateAsync(deviceId);
        if (powerOff == true) return true;                    // 面板已完全断电
        if (nits != null && nits < 10f) return true;          // 背光已归零（实测 MIUI A14 约 2 nits）
        if (powerOff != null || nits != null) return false;   // 状态可读且未满足任一条 → 未熄灭
        return null;                                          // 无法判定
    }

    /// <summary>读取系统唤醒状态（Awake/Dozing/Asleep 等）。无法解析返回 null。</summary>
    public static async Task<string?> GetWakefulnessAsync(string deviceId)
    {
        try
        {
            var outp = await RunCommandAsync(
                $"{AdbPath} -s {deviceId} shell dumpsys power | grep 'mWakefulness=' | head -1", timeoutMs: 5000);
            var idx = outp.IndexOf("mWakefulness=", StringComparison.Ordinal);
            if (idx < 0) return null;
            var parts = outp.Substring(idx + "mWakefulness=".Length)
                .Split(new[] { ' ', '\t', '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
            return parts.Length > 0 ? parts[0] : null;
        }
        catch { return null; }
    }

    /// <summary>
    /// SF 层背光（0~1）。实测 A14 会拒绝 -1（"brightness should be a number between 0 and 1"），
    /// 故统一用 0 表示关闭背光（MIUI A14 实测 nits→2，等效熄灭，且持久不被 DPC 覆盖）。
    /// </summary>
    public static async Task SetDisplayBrightnessSfAsync(string deviceId, float brightness)
    {
        var v = Math.Clamp(brightness, 0f, 1f).ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
        await RunCommandAsync($"{AdbPath} -s {deviceId} shell cmd display set-brightness {v}", timeoutMs: 5000);
    }

    /// <summary>Android 15+：系统层关闭主显示（display 0）。</summary>
    public static async Task DisplayPowerOffAsync(string deviceId)
    {
        await RunCommandAsync($"{AdbPath} -s {deviceId} shell cmd display power-off 0", timeoutMs: 5000);
    }

    /// <summary>Android 15+：系统层点亮主显示（display 0）。</summary>
    public static async Task DisplayPowerOnAsync(string deviceId)
    {
        await RunCommandAsync($"{AdbPath} -s {deviceId} shell cmd display power-on 0", timeoutMs: 5000);
    }

    // 打开手机“物理键盘”设置页（用于一次性配置 UHID 键盘布局）
    public static async Task OpenPhysicalKeyboardSettingsAsync(string deviceId)
    {
        await RunCommandAsync($"{AdbPath} -s {deviceId} shell am start -a android.settings.HARD_KEYBOARD_SETTINGS", timeoutMs: 5000);
    }
}

public sealed record AdbCommandResult(int ExitCode, string Stdout, string Stderr)
{
    public bool Succeeded => ExitCode == 0;
    public string Output => string.IsNullOrEmpty(Stdout) ? Stderr : Stdout;
}

/// <summary>
/// 物理息屏方案（对齐 Extinguish 的两种方案）：
/// PowerOff      —— 完全断电（SurfaceControl POWER_MODE_OFF / cmd display power-off，最省电）
/// BacklightOff  —— 关闭背光（SF 亮度 0，屏幕状态保持 ON，任意版本可用）
/// </summary>
public enum PhysicalOffScheme
{
    PowerOff,
    BacklightOff
}

/// <summary>物理息屏在运行时按设备能力实际采用的执行路径。</summary>
public enum PhysicalOffPath
{
    None,            // 未息屏（未启用或执行失败，屏幕保持常亮）
    Scrcpy,          // scrcpy --turn-screen-off（使用其内置 SurfaceControl 实现）
    CmdDisplayPower, // cmd display power-off（Android 15+）
    MiniDisplay,     // 内置 MiniDisplay 工具经 app_process 执行（纯 ADB，无需额外软件）
    Backlight        // SF 层关闭背光（cmd display set-brightness 0）
}

public enum KeyboardMode
{
    Sdk,         // 兼容模式（推荐，支持中文输入法）
    Uhid,        // 电脑键盘（UHID 物理键盘模拟，部分旧系统不支持）
    Disabled     // 禁用键盘转发
}

public class ScrcpyOptions
{
    public bool PhysicalScreenOff { get; set; } = true;               // 物理息屏（默认开）
    public PhysicalOffScheme OffScheme { get; set; } = PhysicalOffScheme.PowerOff;
    public bool ClickToWake { get; set; } = true;                     // 点击投屏窗口点亮屏幕
    public KeyboardMode KeyboardMode { get; set; } = KeyboardMode.Sdk;
    public bool NoAudio { get; set; } = true;
    public int? MaxFps { get; set; } = 60;
    public int? BitRate { get; set; } = 8;
    public string? MaxSize { get; set; } = "1024";

    /// <summary>物理息屏流程的状态行回调（非阻塞状态窗），在 UI 线程调用。可为 null。</summary>
    public Action<string>? ScreenOffStatus;
}

/// <summary>
/// 通过 `scrcpy --help` 探测到的能力开关。按能力（而非版本号）决定是否追加可选参数。
/// </summary>
public sealed class ScrcpyCapabilities
{
    public bool PreferText { get; init; }   // --prefer-text（3.3.4 已支持）
    public bool TurnScreenOff { get; init; } // --turn-screen-off（scrcpy 原生 SurfaceControl 路径）
    public bool UhidKeyboard { get; init; } // --keyboard=uhid

    /// <summary>未探测成功时的保守默认：全部视为不可用。</summary>
    public static readonly ScrcpyCapabilities Unknown = new ScrcpyCapabilities();
}

/// <summary>
/// 单台设备的显示电源能力（`cmd display help` 探测，按 deviceId 缓存）。
/// </summary>
public sealed class DeviceDisplayCaps
{
    public bool DisplayPowerCmd { get; set; }         // cmd display power-off / power-on（Android 15+）
    public bool DisplaySetBrightnessCmd { get; set; } // cmd display set-brightness（Android 10+ 实测可用）
}
