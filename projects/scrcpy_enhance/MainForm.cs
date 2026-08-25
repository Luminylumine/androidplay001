using System.IO;

namespace AdbManager;

partial class MainForm : Form
{
    private List<DeviceInfo> _devices = new List<DeviceInfo>();
    private DeviceInfo? _selectedDevice;
    // 支持多手机并行：按 deviceId 管理多个互不干扰的 scrcpy 会话
    private readonly Dictionary<string, ScrcpySession> _sessions = new();
    // PC 输入法目标下拉框对应的 deviceId 列表（与 cmbPcTarget.Items 顺序对齐）
    private readonly List<string> _pcTargets = new();

    public MainForm()
    {
        InitializeComponent();
        Load += MainForm_Load;
        FormClosed += MainForm_FormClosed;
    }

    private void MainForm_FormClosed(object? sender, FormClosedEventArgs e)
    {
        StopScrcpySession();
    }

    private async void MainForm_Load(object? sender, EventArgs e)
    {
        // 预热 scrcpy 版本缓存，供启动时判断参数兼容性
        try
        {
            var ver = await AdbHelper.GetScrcpyVersionAsync();
            if (ver != null)
                statusLabel.Text = $"scrcpy {ver} 已就绪";
        }
        catch { }

        await RefreshDevicesAsync();
    }

    private async Task RefreshDevicesAsync()
    {
        btnRefresh.Enabled = false;
        statusLabel.Text = "正在扫描设备...";

        try
        {
            _devices = await AdbHelper.GetDevicesAsync();
            UpdateDeviceList();
            statusLabel.Text = $"已发现 {_devices.Count} 台设备";
        }
        catch (Exception ex)
        {
            statusLabel.Text = $"扫描失败: {ex.Message}";
        }
        finally
        {
            btnRefresh.Enabled = true;
        }
    }

    private void UpdateDeviceList()
    {
        listViewDevices.Items.Clear();
        foreach (var device in _devices)
        {
            var item = new ListViewItem(device.DisplayName)
            {
                Tag = device
            };
            item.SubItems.Add(device.IsUsb ? "USB" : "TCP");
            listViewDevices.Items.Add(item);
        }
    }

    private void btnRefresh_Click(object? sender, EventArgs e)
    {
        _ = RefreshDevicesAsync();
    }

    private void listViewDevices_SelectedIndexChanged(object? sender, EventArgs e)
    {
        _selectedDevice = listViewDevices.SelectedItems.Count > 0
            ? (DeviceInfo?)listViewDevices.SelectedItems[0].Tag
            : null;
    }

    private void listViewDevices_MouseDown(object? sender, MouseEventArgs e)
    {
        if (e.Button == MouseButtons.Right)
        {
            var hitTest = listViewDevices.HitTest(e.Location);
            if (hitTest.Item != null)
            {
                listViewDevices.SelectedItems.Clear();
                hitTest.Item.Selected = true;
                _selectedDevice = (DeviceInfo?)hitTest.Item.Tag;
                contextMenuStrip.Show(listViewDevices, e.Location);
            }
        }
    }

    private void 传输文件ToolStripMenuItem_Click(object? sender, EventArgs e)
    {
        if (_selectedDevice == null) return;
        using var form = new FileTransferForm(_selectedDevice);
        form.ShowDialog(this);
    }

    private async void 屏幕共享ToolStripMenuItem_Click(object? sender, EventArgs e)
    {
        if (_selectedDevice == null) return;
        var deviceId = _selectedDevice.Id;

        // 仅当“同一台设备”已有会话时才先收尾（避免同设备两个 scrcpy 抢同一窗口标题/设置）。
        // 不同设备的会话互不干扰，可同时运行——这就是多手机并行的核心。
        if (_sessions.TryGetValue(deviceId, out var existing))
        {
            _sessions.Remove(deviceId);
            existing.Exited -= OnScrcpySessionExited;
            existing.Stop();
            try { await existing.ExitedAsync.WaitAsync(TimeSpan.FromSeconds(3)); }
            catch (TimeoutException) { }
            existing.Dispose();
            RefreshPcTargets();
        }

        using var dialog = new ScrcpySettingsForm(deviceId);
        if (dialog.ShowDialog(this) != DialogResult.OK)
            return;

        var options = new ScrcpyOptions
        {
            ScreenMode = dialog.ScreenMode,
            KeyboardMode = dialog.KeyboardMode,
            NoAudio = dialog.NoAudio,
            MaxFps = dialog.MaxFps,
            BitRate = dialog.BitRate,
            MaxSize = dialog.MaxSize
        };

        var session = new ScrcpySession(deviceId, options);
        session.Exited += OnScrcpySessionExited;
        _sessions[deviceId] = session;

        try
        {
            statusLabel.Text = $"正在启动 scrcpy，连接 {_selectedDevice.DisplayName}...";
            await session.StartAsync();

            // 等待片刻，检测 scrcpy 是否立即退出（启动失败），给出可操作的提示
            await Task.Delay(1500);
            if (!session.IsRunning)
            {
                statusLabel.Text = "scrcpy 启动失败（进程已退出）";

                var msg = "scrcpy 启动后立即退出。\n\n常见原因与对策：\n" +
                       "· 键盘模式不兼容：较旧安卓(如 10)可能不支持 UHID，请改选“兼容模式”后重试\n" +
                       "· 设备未授权：请在手机上确认已允许 USB 调试\n" +
                       "· adb / scrcpy 未加入系统 PATH\n" +
                       "· TCP 连接失败：请确认设备 IP 可达且无线调试已开启";

                MessageBox.Show(msg, "scrcpy 启动失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                _sessions.Remove(deviceId);
                session.Exited -= OnScrcpySessionExited;
                session.Dispose();
                RefreshPcTargets();
                UpdatePcInputState();
                return;
            }

            var tip = $"scrcpy 已启动：{_selectedDevice.DisplayName}";
            if (options.ScreenMode == ScreenMode.ClickToWake)
                tip += "｜黑屏模式：点击投屏窗口可唤醒";
            if (options.KeyboardMode == KeyboardMode.Sdk)
                tip += "｜电脑键盘(兼容模式)已启用";
            statusLabel.Text = tip;
            RefreshPcTargets(deviceId); // 让 PC 输入法默认指向刚启动的设备
            UpdatePcInputState();
        }
        catch (Exception ex)
        {
            MessageBox.Show($"启动 scrcpy 失败: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            statusLabel.Text = "屏幕共享启动失败";
            session.Exited -= OnScrcpySessionExited;
            _sessions.Remove(deviceId);
            session.Dispose();
            RefreshPcTargets();
            UpdatePcInputState();
        }
    }

    private void OnScrcpySessionExited(object? sender, EventArgs e)
    {
        if (sender is not ScrcpySession s) return;
        s.Exited -= OnScrcpySessionExited;
        _sessions.Remove(s.DeviceId);
        RefreshPcTargets();
        UpdatePcInputState();
        try { statusLabel.Text = $"屏幕共享已结束：{DisplayNameOf(s.DeviceId)}"; } catch { }
    }

    /// <summary>关闭全部会话（程序退出时调用）。</summary>
    private void StopScrcpySession()
    {
        if (_sessions.Count == 0) return;
        var list = _sessions.Values.ToList();
        _sessions.Clear();
        foreach (var s in list)
        {
            s.Exited -= OnScrcpySessionExited;
            s.Stop();
            s.Dispose();
        }
        RefreshPcTargets();
        UpdatePcInputState();
    }

    /// <summary>取设备友好名称（找不到时退回 deviceId）。</summary>
    private string DisplayNameOf(string deviceId)
    {
        return _devices.FirstOrDefault(d => d.Id == deviceId)?.DisplayName ?? deviceId;
    }

    /// <summary>
    /// 刷新 PC 输入法目标下拉框（列出所有运行中的会话），并尽量选中 prefer 指定的设备。
    /// </summary>
    private void RefreshPcTargets(string? prefer = null)
    {
        var prev = _pcTargets.Count > 0 && cmbPcTarget.SelectedIndex >= 0 && cmbPcTarget.SelectedIndex < _pcTargets.Count
            ? _pcTargets[cmbPcTarget.SelectedIndex]
            : prefer;

        cmbPcTarget.Items.Clear();
        _pcTargets.Clear();
        foreach (var kv in _sessions)
        {
            if (!kv.Value.IsRunning) continue;
            _pcTargets.Add(kv.Key);
            cmbPcTarget.Items.Add(DisplayNameOf(kv.Key));
        }

        var idx = -1;
        if (prefer != null)
            idx = _pcTargets.IndexOf(prefer);
        else if (prev != null)
            idx = _pcTargets.IndexOf(prev);
        cmbPcTarget.SelectedIndex = idx >= 0 ? idx : (_pcTargets.Count > 0 ? 0 : -1);
    }

    // ---- PC 输入法：把 Windows 输入法组成好的文本，经剪贴板 + Ctrl+V 发到手机 ----

    /// <summary>按“是否有运行中的会话”启用/禁用“PC输入法”控件。</summary>
    private void UpdatePcInputState()
    {
        var any = _sessions.Values.Any(s => s.IsRunning);
        pcInputBox.Enabled = any;
        cmbPcTarget.Enabled = any;
        pcSendBtn.Enabled = any;
    }

    private async void pcSendBtn_Click(object? sender, EventArgs e)
    {
        await SendPcTextToPhoneAsync();
    }

    private async void pcInputBox_KeyDown(object? sender, KeyEventArgs e)
    {
        if (e.KeyCode == Keys.Enter)
        {
            e.SuppressKeyPress = true; // 阻止换行/提示音
            await SendPcTextToPhoneAsync();
        }
    }

    private async Task SendPcTextToPhoneAsync()
    {
        var text = pcInputBox.Text;
        if (string.IsNullOrWhiteSpace(text)) return;

        // 从下拉框选定目标设备（多手机并行时，明确文本发往哪一台）
        var idx = cmbPcTarget.SelectedIndex;
        if (idx < 0 || idx >= _pcTargets.Count
            || !_sessions.TryGetValue(_pcTargets[idx], out var session)
            || !session.IsRunning)
        {
            MessageBox.Show("请先启动屏幕共享，并在下拉框选择要接收文本的手机。", "提示",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        var deviceId = _pcTargets[idx];
        pcSendBtn.Enabled = false;
        try
        {
            // scrcpy 窗口标题 = ScrcpyWindowPrefix + deviceId（每台设备窗口标题唯一，互不干扰）
            var ok = await ScrcpyTextBridge.SendTextToScrcpyAsync(
                AdbHelper.ScrcpyWindowTitle(deviceId), text);
            if (ok)
            {
                pcInputBox.Clear();
                statusLabel.Text = "已发送到手机（剪贴板 + Ctrl+V）";
            }
            else
            {
                MessageBox.Show("发送失败：请确认 scrcpy 投屏窗口存在且未最小化，再重试。",
                    "发送失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show($"发送失败: {ex.Message}", "发送失败",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally
        {
            UpdatePcInputState();
        }
    }

    private void 访问相册ToolStripMenuItem_Click(object? sender, EventArgs e)
    {
        if (_selectedDevice == null) return;
        using var form = new FileTransferForm(_selectedDevice);
        form.Text = "相册访问";
        form.ShowDialog(this);
    }

    private void 扩展屏ToolStripMenuItem_Click(object? sender, EventArgs e)
    {
        if (_selectedDevice == null) return;
        MessageBox.Show("扩展屏功能开发中...\n\n将使用 scrcpy 的 --new-display 或类似参数来实现扩展屏功能。", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    private async void 连接TCP设备ToolStripMenuItem_Click(object? sender, EventArgs e)
    {
        using var dialog = new ConnectTcpForm();
        if (dialog.ShowDialog(this) == DialogResult.OK)
        {
            try
            {
                if (dialog.PairingCode != null)
                {
                    statusLabel.Text = $"正在配对 {dialog.IpAddress}:{dialog.Port}...";
                    var paired = await AdbHelper.PairAsync(dialog.IpAddress, dialog.Port, dialog.PairingCode);
                    if (!paired)
                    {
                        MessageBox.Show("配对失败，请检查配对码是否正确。", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                        statusLabel.Text = "配对失败";
                        return;
                    }
                    statusLabel.Text = "配对成功，正在连接...";
                }

                statusLabel.Text = $"正在连接 {dialog.IpAddress}:{dialog.Port}...";
                var success = await AdbHelper.ConnectAsync(dialog.IpAddress, dialog.Port);
                if (success)
                {
                    statusLabel.Text = $"已连接 {dialog.IpAddress}:{dialog.Port}";
                    await RefreshDevicesAsync();
                }
                else
                {
                    MessageBox.Show("连接失败，请检查设备是否开启无线调试。", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    statusLabel.Text = "连接失败";
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show($"连接异常: {ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                statusLabel.Text = "连接异常";
            }
        }
    }
}
