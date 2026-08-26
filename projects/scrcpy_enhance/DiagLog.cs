using System.IO;

namespace AdbManager;

/// <summary>
/// 临时诊断日志（定位问题后可删）。%TEMP%\AdbManager\diag\gallery_diag.log，带时间戳、限大小。
/// </summary>
public static class DiagLog
{
    private static readonly object _gate = new();
    private static readonly string LogFile =
        System.IO.Path.Combine(System.IO.Path.GetTempPath(), "AdbManager", "diag", "gallery_diag.log");
    private static bool _headerWritten;

    public static void Info(string msg) => Write(msg);

    private static void Write(string msg)
    {
        try
        {
            lock (_gate)
            {
                if (!_headerWritten)
                {
                    _headerWritten = true;
                    Directory.CreateDirectory(System.IO.Path.GetDirectoryName(LogFile)!);
                    File.WriteAllText(LogFile,
                        $"==== AdbManager diag session {System.DateTime.Now:yyyy-MM-dd HH:mm:ss} (pid {System.Diagnostics.Process.GetCurrentProcess().Id}) ====\n");
                }
                File.AppendAllText(LogFile, $"[{System.DateTime.Now:HH:mm:ss.fff}] {msg}\n");
            }
        }
        catch { }
    }
}
