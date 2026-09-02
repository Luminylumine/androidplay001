using System.IO;
using System.Threading.Channels;

namespace AdbManager;

/// <summary>
/// 异步诊断日志（定位问题后可删）。%TEMP%\AdbManager\diag\gallery_diag.log。
/// 后台线程写盘：调用方（UI 线程每缩略图/每帧都会调用）绝不阻塞。
/// 有界队列：日志风暴时丢最旧，避免内存堆积。
/// </summary>
public static class DiagLog
{
    private static readonly Channel<string> Queue = Channel.CreateBounded<string>(
        new BoundedChannelOptions(4000)
        {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true,
            SingleWriter = false
        });
    private static readonly string LogFile =
        Path.Combine(Path.GetTempPath(), "AdbManager", "diag", "gallery_diag.log");
    private static long _dropped;

    /// <summary>网格调试叠加层（tile 左上角画 #index / I:itemId）：验证"画面 ID == 双击 ID"用，验证完置 false。</summary>
    public static bool ShowGridOverlay = true;

    static DiagLog()
    {
        _ = WriterLoopAsync();
    }

    public static void Info(string msg)
    {
        try
        {
            if (!Queue.Writer.TryWrite($"[{DateTime.Now:HH:mm:ss.fff}] {msg}"))
                System.Threading.Interlocked.Increment(ref _dropped);
        }
        catch { }
    }

    private static async Task WriterLoopAsync()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(LogFile)!);
            await File.WriteAllTextAsync(LogFile,
                $"==== AdbManager diag session {DateTime.Now:yyyy-MM-dd HH:mm:ss} (pid {System.Diagnostics.Process.GetCurrentProcess().Id}) ====\n");
            long written = 0;
            await foreach (var line in Queue.Reader.ReadAllAsync())
            {
                await File.AppendAllTextAsync(LogFile, line + "\n");
                if (++written % 200 == 0)
                    Info($"diag: written={written} dropped={_dropped}");
            }
        }
        catch { }
    }
}
