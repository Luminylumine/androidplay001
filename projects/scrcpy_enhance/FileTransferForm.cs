using System.Diagnostics;
using System.IO.Compression;
using System.Text.RegularExpressions;

namespace AdbManager;

/// <summary>
/// ADB 文件资源管理器：左侧是本地目录，右侧是手机目录，所有手机文件访问均通过 adb shell/sync 完成。
/// </summary>
public sealed class FileTransferForm : Form
{
    private readonly DeviceInfo _device;
    private readonly DeviceIoScheduler _sched;
    private readonly CancellationTokenSource _cts = new();
    private readonly SemaphoreSlim _operationGate = new(1, 1);
    private string _currentPath = "/sdcard";
    private string _currentLocalPath = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
    private bool _busy;

    private ListView _listViewLocal = null!;
    private ListView _listViewRemote = null!;
    private TextBox _txtLocalPath = null!;
    private TextBox _txtRemotePath = null!;
    private Button _btnLocalUp = null!;
    private Button _btnLocalBrowse = null!;
    private Button _btnGoUp = null!;
    private Button _btnRemoteGo = null!;
    private Button _btnRefresh = null!;
    private Button _btnUpload = null!;
    private Button _btnDownload = null!;
    private Button _btnDelete = null!;
    private Button _btnRename = null!;
    private Button _btnNewFolder = null!;
    private Button _btnZip = null!;
    private Button _btnUnzip = null!;
    private ToolStripStatusLabel _statusLabel = null!;
    private OpenFileDialog _openFileDialog = null!;
    private ContextMenuStrip _remoteMenu = null!;

    public FileTransferForm(DeviceInfo device)
    {
        _device = device;
        _sched = new DeviceIoScheduler(device.IsUsb);
        if (!Directory.Exists(_currentLocalPath))
            _currentLocalPath = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);

        Text = $"文件传输 - {device.DisplayName}";
        StartPosition = FormStartPosition.CenterParent;
        MinimumSize = new Size(900, 560);
        ClientSize = new Size(1180, 700);
        BuildUi();
        Load += FileTransferForm_Load;
        FormClosing += (_, _) => _cts.Cancel();
    }

    private void BuildUi()
    {
        var actions = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            Height = 42,
            Padding = new Padding(6, 6, 6, 4),
            WrapContents = false,
            AutoScroll = true
        };
        _btnGoUp = ActionButton("手机上级");
        _btnRefresh = ActionButton("刷新");
        _btnUpload = ActionButton("上传");
        _btnDownload = ActionButton("下载");
        _btnDelete = ActionButton("删除");
        _btnRename = ActionButton("重命名");
        _btnNewFolder = ActionButton("新建文件夹");
        _btnZip = ActionButton("压缩 ZIP");
        _btnUnzip = ActionButton("解压 ZIP");
        actions.Controls.AddRange(new Control[]
        {
            _btnGoUp, _btnRefresh, _btnUpload, _btnDownload, _btnDelete,
            _btnRename, _btnNewFolder, _btnZip, _btnUnzip
        });

        _btnGoUp.Click += async (_, _) => await GoRemoteUpAsync();
        _btnRefresh.Click += async (_, _) => await LoadRemoteDirectoryAsync(_currentPath);
        _btnUpload.Click += async (_, _) => await UploadSelectedAsync();
        _btnDownload.Click += async (_, _) => await DownloadSelectedAsync();
        _btnDelete.Click += async (_, _) => await DeleteSelectedAsync();
        _btnRename.Click += async (_, _) => await RenameSelectedAsync();
        _btnNewFolder.Click += async (_, _) => await CreateFolderAsync();
        _btnZip.Click += async (_, _) => await ZipSelectedAsync();
        _btnUnzip.Click += async (_, _) => await UnzipSelectedAsync();

        var split = new SplitContainer
        {
            Dock = DockStyle.Fill,
            Orientation = Orientation.Vertical,
            SplitterDistance = 520,
            BorderStyle = BorderStyle.FixedSingle
        };
        split.Panel1.Controls.Add(BuildLocalPane());
        split.Panel2.Controls.Add(BuildRemotePane());

        var status = new StatusStrip();
        _statusLabel = new ToolStripStatusLabel("就绪") { Spring = true, TextAlign = ContentAlignment.MiddleLeft };
        status.Items.Add(_statusLabel);

        Controls.Add(split);
        Controls.Add(actions);
        Controls.Add(status);
        MainMenuStrip = null;
    }

    private Control BuildLocalPane()
    {
        var pane = new Panel { Dock = DockStyle.Fill };
        var header = new Panel { Dock = DockStyle.Top, Height = 58 };
        header.Controls.Add(new Label { Text = "本地文件", Location = new Point(8, 6), AutoSize = true });
        _txtLocalPath = new TextBox { Location = new Point(8, 27), Width = 380, Anchor = AnchorStyles.Left | AnchorStyles.Top | AnchorStyles.Right };
        _btnLocalUp = new Button { Text = "上级", Location = new Point(394, 25), Size = new Size(54, 26), Anchor = AnchorStyles.Top | AnchorStyles.Right };
        _btnLocalBrowse = new Button { Text = "浏览", Location = new Point(452, 25), Size = new Size(54, 26), Anchor = AnchorStyles.Top | AnchorStyles.Right };
        _txtLocalPath.KeyDown += async (_, e) =>
        {
            if (e.KeyCode == Keys.Enter)
            {
                e.SuppressKeyPress = true;
                await LoadLocalDirectoryAsync(_txtLocalPath.Text);
            }
        };
        _btnLocalUp.Click += async (_, _) =>
        {
            try
            {
                var parent = Directory.GetParent(_currentLocalPath)?.FullName;
                if (parent != null) await LoadLocalDirectoryAsync(parent);
            }
            catch { }
        };
        _btnLocalBrowse.Click += async (_, _) =>
        {
            using var dialog = new FolderBrowserDialog { SelectedPath = _currentLocalPath, Description = "选择本地目录" };
            if (dialog.ShowDialog(this) == DialogResult.OK)
                await LoadLocalDirectoryAsync(dialog.SelectedPath);
        };
        header.Controls.AddRange(new Control[] { _txtLocalPath, _btnLocalUp, _btnLocalBrowse });

        _listViewLocal = CreateListView();
        _listViewLocal.DoubleClick += async (_, _) => await LocalDoubleClickAsync();
        _listViewLocal.KeyDown += async (_, e) =>
        {
            if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; await LocalDoubleClickAsync(); }
        };
        _listViewLocal.DragEnter += ListViewLocal_DragEnter;
        _listViewLocal.DragDrop += async (_, e) => await UploadDroppedAsync(e);
        AddColumns(_listViewLocal, "名称", "类型", "大小", "修改日期");
        pane.Controls.Add(_listViewLocal);
        pane.Controls.Add(header);
        return pane;
    }

    private Control BuildRemotePane()
    {
        var pane = new Panel { Dock = DockStyle.Fill };
        var header = new Panel { Dock = DockStyle.Top, Height = 58 };
        header.Controls.Add(new Label { Text = "手机文件系统（ADB）", Location = new Point(8, 6), AutoSize = true });
        _txtRemotePath = new TextBox { Location = new Point(8, 27), Width = 410, Anchor = AnchorStyles.Left | AnchorStyles.Top | AnchorStyles.Right };
        _btnRemoteGo = new Button { Text = "转到", Location = new Point(418, 25), Size = new Size(54, 26), Anchor = AnchorStyles.Top | AnchorStyles.Right };
        _btnRemoteGo.Click += async (_, _) => await LoadRemoteDirectoryAsync(_txtRemotePath.Text);
        _txtRemotePath.KeyDown += async (_, e) =>
        {
            if (e.KeyCode == Keys.Enter)
            {
                e.SuppressKeyPress = true;
                await LoadRemoteDirectoryAsync(_txtRemotePath.Text);
            }
        };
        header.Controls.AddRange(new Control[] { _txtRemotePath, _btnRemoteGo });

        _listViewRemote = CreateListView();
        _listViewRemote.DoubleClick += async (_, _) => await RemoteDoubleClickAsync();
        _listViewRemote.KeyDown += async (_, e) =>
        {
            if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; await RemoteDoubleClickAsync(); }
            else if (e.KeyCode == Keys.Delete) { e.SuppressKeyPress = true; await DeleteSelectedAsync(); }
        };
        _listViewRemote.DragEnter += ListViewRemote_DragEnter;
        _listViewRemote.DragDrop += async (_, e) => await UploadDroppedAsync(e);
        AddColumns(_listViewRemote, "名称", "类型", "大小", "修改日期");
        _remoteMenu = new ContextMenuStrip();
        _remoteMenu.Items.Add("下载", null, async (_, _) => await DownloadSelectedAsync());
        _remoteMenu.Items.Add("重命名", null, async (_, _) => await RenameSelectedAsync());
        _remoteMenu.Items.Add("删除", null, async (_, _) => await DeleteSelectedAsync());
        _remoteMenu.Items.Add(new ToolStripSeparator());
        _remoteMenu.Items.Add("压缩为 ZIP", null, async (_, _) => await ZipSelectedAsync());
        _remoteMenu.Items.Add("解压 ZIP", null, async (_, _) => await UnzipSelectedAsync());
        _listViewRemote.ContextMenuStrip = _remoteMenu;

        pane.Controls.Add(_listViewRemote);
        pane.Controls.Add(header);
        return pane;
    }

    private static Button ActionButton(string text) => new()
    {
        Text = text,
        AutoSize = true,
        Height = 28,
        Margin = new Padding(2, 0, 2, 0)
    };

    private static ListView CreateListView() => new()
    {
        Dock = DockStyle.Fill,
        View = View.Details,
        FullRowSelect = true,
        GridLines = true,
        HideSelection = false,
        MultiSelect = true,
        AllowDrop = true,
        UseCompatibleStateImageBehavior = false
    };

    private static void AddColumns(ListView list, params string[] names)
    {
        foreach (var name in names)
            list.Columns.Add(name);
        list.Columns[0].Width = 270;
        for (var i = 1; i < list.Columns.Count; i++) list.Columns[i].Width = 105;
    }

    private async void FileTransferForm_Load(object? sender, EventArgs e)
    {
        await LoadLocalDirectoryAsync(_currentLocalPath);
        await LoadRemoteDirectoryAsync(_currentPath);
    }

    private async Task LoadRemoteDirectoryAsync(string path, bool showError = true)
    {
        string normalized;
        try { normalized = NormalizeRemotePath(path); }
        catch (Exception ex)
        {
            if (showError) MessageBox.Show(this, ex.Message, "路径错误", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        try
        {
            SetStatus($"正在加载 {normalized}...");
            await _sched.Metadata.WaitAsync(_cts.Token);
            AdbCommandResult result;
            try { result = await AdbHelper.ListDirectoryResultAsync(_device.Id, normalized, _cts.Token); }
            finally { _sched.Metadata.Release(); }
            if (!result.Succeeded)
                throw new IOException(string.IsNullOrWhiteSpace(result.Stderr) ? "无法读取远端目录" : result.Stderr.Trim());

            _currentPath = normalized;
            _txtRemotePath.Text = normalized;
            ParseAndDisplayFiles(result.Stdout, normalized);
            _btnGoUp.Enabled = normalized != "/";
            SetStatus($"手机：{normalized}，共 {_listViewRemote.Items.Count} 个项目");
        }
        catch (OperationCanceledException) when (_cts.IsCancellationRequested) { }
        catch (Exception ex)
        {
            SetStatus($"加载失败：{ex.Message}");
            if (showError && !IsDisposed)
                MessageBox.Show(this, $"加载目录失败：{ex.Message}", "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private void ParseAndDisplayFiles(string output, string parentPath)
    {
        _listViewRemote.BeginUpdate();
        try
        {
            _listViewRemote.Items.Clear();
            foreach (var raw in output.Split('\n'))
            {
                var line = raw.TrimEnd('\r');
                if (string.IsNullOrWhiteSpace(line) || line.StartsWith("total", StringComparison.OrdinalIgnoreCase)) continue;
                var fields = SplitLsLine(line, 7, out var name);
                if (fields == null || string.IsNullOrWhiteSpace(name) || name is "." or "..") continue;

                var permissions = fields[0];
                var isDirectory = permissions.StartsWith('d');
                if (permissions.StartsWith('l'))
                {
                    var arrow = name.IndexOf(" -> ", StringComparison.Ordinal);
                    if (arrow >= 0) name = name[..arrow];
                }
                if (!IsSafeLeafName(name)) continue;

                var entry = new FileEntry
                {
                    Name = name,
                    Path = CombineRemotePath(parentPath, name),
                    IsDirectory = isDirectory,
                    Size = fields[4]
                };
                var item = new ListViewItem(name) { Tag = entry };
                item.SubItems.Add(isDirectory ? "文件夹" : "文件");
                item.SubItems.Add(isDirectory ? "" : fields[4]);
                item.SubItems.Add($"{fields[5]} {fields[6]}");
                _listViewRemote.Items.Add(item);
            }
        }
        finally { _listViewRemote.EndUpdate(); }
    }

    // 解析 ls -l 的前 7 个字段后保留原始文件名，避免文件名中的空格被破坏。
    private static string[]? SplitLsLine(string line, int fieldCount, out string name)
    {
        name = string.Empty;
        var fields = new List<string>(fieldCount);
        var position = 0;
        for (var i = 0; i < fieldCount; i++)
        {
            while (position < line.Length && char.IsWhiteSpace(line[position])) position++;
            if (position >= line.Length) return null;
            var start = position;
            while (position < line.Length && !char.IsWhiteSpace(line[position])) position++;
            fields.Add(line[start..position]);
        }
        while (position < line.Length && char.IsWhiteSpace(line[position])) position++;
        if (position >= line.Length) return null;
        name = line[position..];
        return fields.ToArray();
    }

    private async Task LoadLocalDirectoryAsync(string path)
    {
        try
        {
            var fullPath = Path.GetFullPath(path.Trim());
            if (!Directory.Exists(fullPath)) throw new DirectoryNotFoundException($"本地目录不存在：{fullPath}");
            var directory = new DirectoryInfo(fullPath);
            var directories = directory.EnumerateDirectories().OrderBy(d => d.Name, StringComparer.CurrentCultureIgnoreCase);
            var files = directory.EnumerateFiles().OrderBy(f => f.Name, StringComparer.CurrentCultureIgnoreCase);
            _listViewLocal.BeginUpdate();
            try
            {
                _listViewLocal.Items.Clear();
                foreach (var child in directories)
                    AddLocalItem(child.Name, true, "", child.LastWriteTime, child.FullName);
                foreach (var file in files)
                    AddLocalItem(file.Name, false, FormatSize(file.Length), file.LastWriteTime, file.FullName);
            }
            finally { _listViewLocal.EndUpdate(); }
            _currentLocalPath = fullPath;
            _txtLocalPath.Text = fullPath;
            _btnLocalUp.Enabled = directory.Parent != null;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException)
        {
            SetStatus($"本地目录加载失败：{ex.Message}");
        }
        await Task.CompletedTask;
    }

    private void AddLocalItem(string name, bool directory, string size, DateTime modified, string path)
    {
        var item = new ListViewItem(name) { Tag = new LocalEntry(name, path, directory) };
        item.SubItems.Add(directory ? "文件夹" : "文件");
        item.SubItems.Add(size);
        item.SubItems.Add(modified.ToString("yyyy-MM-dd HH:mm"));
        _listViewLocal.Items.Add(item);
    }

    private async Task LocalDoubleClickAsync()
    {
        if (_listViewLocal.SelectedItems.Count != 1) return;
        if (_listViewLocal.SelectedItems[0].Tag is LocalEntry entry && entry.IsDirectory)
            await LoadLocalDirectoryAsync(entry.Path);
    }

    private async Task RemoteDoubleClickAsync()
    {
        if (_listViewRemote.SelectedItems.Count != 1) return;
        if (_listViewRemote.SelectedItems[0].Tag is FileEntry entry)
        {
            if (entry.IsDirectory) await LoadRemoteDirectoryAsync(entry.Path);
            else await DownloadSelectedAsync();
        }
    }

    private async Task GoRemoteUpAsync()
    {
        if (_currentPath == "/") return;
        var slash = _currentPath.LastIndexOf('/');
        await LoadRemoteDirectoryAsync(slash <= 0 ? "/" : _currentPath[..slash]);
    }

    private async Task UploadSelectedAsync()
    {
        var paths = _listViewLocal.SelectedItems.Cast<ListViewItem>()
            .Select(i => (i.Tag as LocalEntry)?.Path)
            .Where(p => p != null).Cast<string>().ToList();
        if (paths.Count == 0)
        {
            _openFileDialog ??= new OpenFileDialog();
            _openFileDialog.Multiselect = true;
            _openFileDialog.Filter = "所有文件|*.*";
            _openFileDialog.Title = "选择要上传的文件";
            if (_openFileDialog.ShowDialog(this) != DialogResult.OK) return;
            paths = _openFileDialog.FileNames.ToList();
        }
        await UploadPathsAsync(paths);
    }

    private async Task UploadPathsAsync(IReadOnlyList<string> paths)
    {
        await RunOperationAsync(async () =>
        {
            for (var i = 0; i < paths.Count; i++)
            {
                var local = paths[i];
                var name = Path.GetFileName(local.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
                ValidateLeafName(name);
                var remote = CombineRemotePath(_currentPath, name);
                SetStatus($"上传 {i + 1}/{paths.Count}：{name}");
                await _sched.Transfer.WaitAsync(_cts.Token);
                try { await AdbHelper.PushFileAsync(_device.Id, local, remote, ct: _cts.Token); }
                finally { _sched.Transfer.Release(); }
            }
            await LoadRemoteDirectoryAsync(_currentPath, false);
        });
    }

    private async Task DownloadSelectedAsync()
    {
        var entries = SelectedRemoteEntries();
        if (entries.Count == 0)
        {
            MessageBox.Show(this, "请先选择要下载的文件或文件夹", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        using var dialog = new FolderBrowserDialog { Description = "选择下载到的本地文件夹" };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;

        await RunOperationAsync(async () =>
        {
            for (var i = 0; i < entries.Count; i++)
            {
                var entry = entries[i];
                var local = Path.Combine(dialog.SelectedPath, SafeLocalName(entry.Name));
                SetStatus($"下载 {i + 1}/{entries.Count}：{entry.Name}");
                await _sched.Transfer.WaitAsync(_cts.Token);
                try { await AdbHelper.PullFileAsync(_device.Id, entry.Path, local, ct: _cts.Token); }
                finally { _sched.Transfer.Release(); }
            }
            SetStatus($"下载完成：{entries.Count} 个项目");
        });
    }

    private async Task DeleteSelectedAsync()
    {
        var entries = SelectedRemoteEntries();
        if (entries.Count == 0)
        {
            MessageBox.Show(this, "请先选择要删除的项目", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if (MessageBox.Show(this, $"确定删除选中的 {entries.Count} 个项目？此操作不可撤销。", "确认删除",
            MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes) return;

        await RunOperationAsync(async () =>
        {
            foreach (var entry in entries)
            {
                SetStatus($"删除：{entry.Name}");
                var result = await AdbHelper.DeleteRemoteAsync(_device.Id, entry.Path, entry.IsDirectory, _cts.Token);
                if (!result.Succeeded) throw new IOException(result.Output.Trim());
            }
            await LoadRemoteDirectoryAsync(_currentPath, false);
        });
    }

    private async Task RenameSelectedAsync()
    {
        var entries = SelectedRemoteEntries();
        if (entries.Count != 1)
        {
            MessageBox.Show(this, "重命名时请选择一个项目", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        var newName = PromptText(this, "重命名", "新名称：", entries[0].Name);
        if (string.IsNullOrWhiteSpace(newName) || newName == entries[0].Name) return;
        try { ValidateLeafName(newName); }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "名称错误", MessageBoxButtons.OK, MessageBoxIcon.Warning); return; }

        await RunOperationAsync(async () =>
        {
            var result = await AdbHelper.RenameRemoteAsync(_device.Id, entries[0].Path,
                CombineRemotePath(_currentPath, newName), _cts.Token);
            if (!result.Succeeded) throw new IOException(result.Output.Trim());
            await LoadRemoteDirectoryAsync(_currentPath, false);
        });
    }

    private async Task CreateFolderAsync()
    {
        var name = PromptText(this, "新建文件夹", "文件夹名称：", "新建文件夹");
        if (string.IsNullOrWhiteSpace(name)) return;
        try { ValidateLeafName(name); }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "名称错误", MessageBoxButtons.OK, MessageBoxIcon.Warning); return; }

        await RunOperationAsync(async () =>
        {
            var result = await AdbHelper.CreateRemoteDirectoryAsync(_device.Id, CombineRemotePath(_currentPath, name), _cts.Token);
            if (!result.Succeeded) throw new IOException(result.Output.Trim());
            await LoadRemoteDirectoryAsync(_currentPath, false);
        });
    }

    private async Task ZipSelectedAsync()
    {
        var entries = SelectedRemoteEntries();
        if (entries.Count == 0)
        {
            MessageBox.Show(this, "请先选择要压缩的手机文件或文件夹", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        var defaultName = entries.Count == 1 ? $"{entries[0].Name}.zip" : "archive.zip";
        var zipName = PromptText(this, "压缩为 ZIP", "手机中的 ZIP 文件名：", defaultName);
        if (string.IsNullOrWhiteSpace(zipName)) return;
        if (!zipName.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)) zipName += ".zip";
        try { ValidateLeafName(zipName); }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "名称错误", MessageBoxButtons.OK, MessageBoxIcon.Warning); return; }

        await RunOperationAsync(async () =>
        {
            var tempRoot = Path.Combine(Path.GetTempPath(), "AdbManager", "zip_" + Guid.NewGuid().ToString("N"));
            var zipPath = tempRoot + ".zip";
            Directory.CreateDirectory(tempRoot);
            try
            {
                foreach (var entry in entries)
                {
                    var local = Path.Combine(tempRoot, SafeLocalName(entry.Name));
                    SetStatus($"为 ZIP 拉取：{entry.Name}");
                    await _sched.Transfer.WaitAsync(_cts.Token);
                    try { await AdbHelper.PullFileAsync(_device.Id, entry.Path, local, ct: _cts.Token); }
                    finally { _sched.Transfer.Release(); }
                }
                ZipFile.CreateFromDirectory(tempRoot, zipPath, CompressionLevel.Fastest, false);
                SetStatus($"上传 ZIP：{zipName}");
                await _sched.Transfer.WaitAsync(_cts.Token);
                try { await AdbHelper.PushFileAsync(_device.Id, zipPath, CombineRemotePath(_currentPath, zipName), ct: _cts.Token); }
                finally { _sched.Transfer.Release(); }
                await LoadRemoteDirectoryAsync(_currentPath, false);
            }
            finally
            {
                TryDeleteDirectory(tempRoot);
                try { if (File.Exists(zipPath)) File.Delete(zipPath); } catch { }
            }
        });
    }

    private async Task UnzipSelectedAsync()
    {
        var entries = SelectedRemoteEntries();
        if (entries.Count != 1 || entries[0].IsDirectory || !entries[0].Name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
        {
            MessageBox.Show(this, "请选择一个 ZIP 文件", "提示", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        await RunOperationAsync(async () =>
        {
            var tempRoot = Path.Combine(Path.GetTempPath(), "AdbManager", "unzip_" + Guid.NewGuid().ToString("N"));
            var zipPath = Path.Combine(tempRoot, SafeLocalName(entries[0].Name));
            var extractRoot = Path.Combine(tempRoot, "content");
            Directory.CreateDirectory(tempRoot);
            try
            {
                SetStatus($"下载 ZIP：{entries[0].Name}");
                await _sched.Transfer.WaitAsync(_cts.Token);
                try { await AdbHelper.PullFileAsync(_device.Id, entries[0].Path, zipPath, ct: _cts.Token); }
                finally { _sched.Transfer.Release(); }
                ValidateZipEntries(zipPath, extractRoot);
                ZipFile.ExtractToDirectory(zipPath, extractRoot);
                foreach (var path in Directory.EnumerateFileSystemEntries(extractRoot))
                {
                    var name = Path.GetFileName(path);
                    SetStatus($"上传解压内容：{name}");
                    await _sched.Transfer.WaitAsync(_cts.Token);
                    try { await AdbHelper.PushFileAsync(_device.Id, path, CombineRemotePath(_currentPath, name), ct: _cts.Token); }
                    finally { _sched.Transfer.Release(); }
                }
                await LoadRemoteDirectoryAsync(_currentPath, false);
            }
            finally { TryDeleteDirectory(tempRoot); }
        });
    }

    private async Task RunOperationAsync(Func<Task> operation)
    {
        if (!await _operationGate.WaitAsync(0))
        {
            SetStatus("已有传输操作正在进行，请稍候...");
            return;
        }
        SetBusy(true);
        try
        {
            await operation();
        }
        catch (OperationCanceledException) when (_cts.IsCancellationRequested) { }
        catch (Exception ex)
        {
            SetStatus($"操作失败：{ex.Message}");
            if (!IsDisposed) MessageBox.Show(this, ex.Message, "文件操作失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
        finally
        {
            SetBusy(false);
            _operationGate.Release();
        }
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        foreach (var button in new[] { _btnGoUp, _btnRemoteGo, _btnRefresh, _btnUpload, _btnDownload,
            _btnDelete, _btnRename, _btnNewFolder, _btnZip, _btnUnzip, _btnLocalUp, _btnLocalBrowse })
            button.Enabled = !busy;
        _btnGoUp.Enabled = !busy && _currentPath != "/";
    }

    private void SetStatus(string text)
    {
        if (!IsDisposed && IsHandleCreated) _statusLabel.Text = text;
    }

    private List<FileEntry> SelectedRemoteEntries() => _listViewRemote.SelectedItems.Cast<ListViewItem>()
        .Select(item => item.Tag as FileEntry).Where(entry => entry != null).Cast<FileEntry>().ToList();

    private void ListViewLocal_DragEnter(object? sender, DragEventArgs e)
    {
        e.Effect = e.Data?.GetDataPresent(DataFormats.FileDrop) == true ? DragDropEffects.Copy : DragDropEffects.None;
    }

    private void ListViewRemote_DragEnter(object? sender, DragEventArgs e)
    {
        e.Effect = e.Data?.GetDataPresent(DataFormats.FileDrop) == true ? DragDropEffects.Copy : DragDropEffects.None;
    }

    private async Task UploadDroppedAsync(DragEventArgs e)
    {
        if (_busy || e.Data?.GetData(DataFormats.FileDrop) is not string[] paths || paths.Length == 0) return;
        await UploadPathsAsync(paths);
    }

    private static string NormalizeRemotePath(string path)
    {
        path = path.Trim().Replace('\\', '/');
        if (string.IsNullOrWhiteSpace(path) || !path.StartsWith('/') || path.Any(char.IsControl))
            throw new ArgumentException("手机路径必须是以 / 开头的有效路径。");
        var parts = path.Split('/', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Any(p => p is "." or "..")) throw new ArgumentException("手机路径不允许包含 . 或 ..。");
        return parts.Length == 0 ? "/" : "/" + string.Join('/', parts);
    }

    private static string CombineRemotePath(string parent, string leaf)
    {
        ValidateLeafName(leaf);
        return NormalizeRemotePath(parent.TrimEnd('/') + "/" + leaf);
    }

    private static bool IsSafeLeafName(string name)
        => !string.IsNullOrWhiteSpace(name) && name is not "." and not ".."
           && !name.Contains('/') && !name.Contains('\\') && !name.Any(char.IsControl);

    private static void ValidateLeafName(string name)
    {
        if (!IsSafeLeafName(name)) throw new ArgumentException("名称不能为空，且不能包含路径分隔符、控制字符或 . / ..。");
    }

    private static string SafeLocalName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var safe = new string(name.Select(c => invalid.Contains(c) ? '_' : c).ToArray()).Trim();
        return string.IsNullOrEmpty(safe) ? "unnamed" : safe;
    }

    private static string FormatSize(long size)
    {
        string[] units = { "B", "KB", "MB", "GB", "TB" };
        var value = (double)size;
        var unit = 0;
        while (value >= 1024 && unit < units.Length - 1) { value /= 1024; unit++; }
        return unit == 0 ? $"{size} B" : $"{value:0.##} {units[unit]}";
    }

    private static void ValidateZipEntries(string zipPath, string destination)
    {
        var root = Path.GetFullPath(destination) + Path.DirectorySeparatorChar;
        using var archive = ZipFile.OpenRead(zipPath);
        foreach (var entry in archive.Entries)
        {
            var candidate = Path.GetFullPath(Path.Combine(destination, entry.FullName.Replace('/', Path.DirectorySeparatorChar)));
            if (!candidate.StartsWith(root, StringComparison.OrdinalIgnoreCase))
                throw new IOException($"ZIP 包含越界路径：{entry.FullName}");
        }
    }

    private static void TryDeleteDirectory(string path)
    {
        try { if (Directory.Exists(path)) Directory.Delete(path, true); } catch { }
    }

    private static string? PromptText(IWin32Window owner, string title, string label, string initial)
    {
        using var form = new Form { Text = title, ClientSize = new Size(440, 125), StartPosition = FormStartPosition.CenterParent, MinimizeBox = false, MaximizeBox = false };
        var text = new TextBox { Text = initial, Location = new Point(12, 35), Width = 416 };
        var ok = new Button { Text = "确定", DialogResult = DialogResult.OK, Location = new Point(272, 78), Width = 75 };
        var cancel = new Button { Text = "取消", DialogResult = DialogResult.Cancel, Location = new Point(353, 78), Width = 75 };
        form.Controls.AddRange(new Control[] { new Label { Text = label, Location = new Point(12, 12), AutoSize = true }, text, ok, cancel });
        form.AcceptButton = ok;
        form.CancelButton = cancel;
        form.Shown += (_, _) => { text.SelectAll(); text.Focus(); };
        return form.ShowDialog(owner) == DialogResult.OK ? text.Text.Trim() : null;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _cts.Cancel();
            _cts.Dispose();
            _operationGate.Dispose();
            _sched.Metadata.Dispose();
            _sched.Transfer.Dispose();
            _sched.ThumbBatch.Dispose();
        }
        base.Dispose(disposing);
    }
}

public sealed class FileEntry
{
    public string Name { get; set; } = string.Empty;
    public string Path { get; set; } = string.Empty;
    public bool IsDirectory { get; set; }
    public string Size { get; set; } = string.Empty;
}

internal sealed record LocalEntry(string Name, string Path, bool IsDirectory);
