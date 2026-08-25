using System.Threading;

namespace AdbManager;

static class Program
{
    /// <summary>
    /// The main entry point for the application.
    /// </summary>
    [STAThread]
    static void Main()
    {
        // 全局异常处理：弹窗提示
        Application.ThreadException += (sender, e) =>
        {
            try
            {
                MessageBox.Show(
                    $"发生未处理的异常：\n\n{e.Exception}",
                    "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            catch { }
        };

        AppDomain.CurrentDomain.UnhandledException += (sender, e) =>
        {
            try
            {
                MessageBox.Show(
                    $"发生严重错误：\n\n{e.ExceptionObject}\n\n程序即将退出。",
                    "严重错误", MessageBoxButtons.OK, MessageBoxIcon.Stop);
            }
            catch { }
        };

        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}
