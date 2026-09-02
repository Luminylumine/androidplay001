using System.Windows.Forms;

namespace AdbManager;

/// <summary>
/// 物理息屏状态窗（非阻塞）：scrcpy 启动时弹出，实时显示息屏流程
/// （能力探测 → 工具部署 → 断电执行 → 生效验证 → 实际路径）。
/// 可随时关闭，不影响投屏；会话结束时由 MainForm 一并关闭。
/// </summary>
public sealed class ScreenOffStatusForm : Form
{
    private readonly TextBox _log;

    public ScreenOffStatusForm(string title)
    {
        Text = title;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterScreen;
        MaximizeBox = false;
        MinimizeBox = false;
        ShowInTaskbar = false;
        ClientSize = new Size(480, 250);

        _log = new TextBox
        {
            Dock = DockStyle.Fill,
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Vertical,
            WordWrap = false,
            Font = new Font("Consolas", 9F),
        };

        var btnClose = new Button
        {
            Text = "关 闭",
            Dock = DockStyle.Fill,
        };
        btnClose.Click += (_, _) => Close();

        var panel = new Panel
        {
            Dock = DockStyle.Bottom,
            Height = 46,
            Padding = new Padding(10, 6, 10, 10),
        };
        panel.Controls.Add(btnClose);

        Controls.Add(_log);
        Controls.Add(panel);
    }

    /// <summary>追加一行状态（线程安全）。</summary>
    public void AppendLine(string line)
    {
        if (IsDisposed) return;
        if (InvokeRequired)
        {
            try { BeginInvoke(() => AppendLine(line)); } catch { }
            return;
        }
        _log.AppendText($"[{DateTime.Now:HH:mm:ss}] {line}{Environment.NewLine}");
        _log.ScrollToCaret();
    }
}
