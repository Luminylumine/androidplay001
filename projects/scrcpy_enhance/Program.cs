using System.Threading;

namespace AdbManager;

static class Program
{
    private static readonly string CrashLogDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AdbManager", "CrashLogs");

    /// <summary>
    /// The main entry point for the application.
    /// </summary>
    [STAThread]
    static void Main()
    {
        // 全局异常处理：将异常落盘 + 弹窗，方便排查
        Application.ThreadException += (sender, e) =>
        {
            SaveCrashLog(e.Exception, "ThreadException");
            try
            {
                MessageBox.Show(
                    $"发生未处理的异常：\n\n{e.Exception}\n\n详细信息已保存到：\n{CrashLogDir}",
                    "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            catch { }
        };

        AppDomain.CurrentDomain.UnhandledException += (sender, e) =>
        {
            SaveCrashLog(e.ExceptionObject as Exception ?? new Exception(e.ExceptionObject?.ToString() ?? "Unknown"), "UnhandledException");
            try
            {
                MessageBox.Show(
                    $"发生严重错误：\n\n{e.ExceptionObject}\n\n详细信息已保存到：\n{CrashLogDir}\n\n程序即将退出。",
                    "严重错误", MessageBoxButtons.OK, MessageBoxIcon.Stop);
            }
            catch { }
        };

        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }

    private static void SaveCrashLog(Exception ex, string source)
    {
        try
        {
            Directory.CreateDirectory(CrashLogDir);
            var path = Path.Combine(CrashLogDir, $"crash_{DateTime.Now:yyyyMMdd_HHmmss_fff}.log");
            var content = $"=== AdbManager 崩溃日志 ===\n" +
                          $"时间: {DateTime.Now:yyyy-MM-dd HH:mm:ss}\n" +
                          $"来源: {source}\n\n" +
                          $"异常信息:\n{ex}\n\n" +
                          $"堆栈跟踪:\n{ex.StackTrace}\n";
            File.WriteAllText(path, content);
        }
        catch { }
    }
}