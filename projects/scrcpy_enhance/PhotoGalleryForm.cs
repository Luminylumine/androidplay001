using System.Collections.Specialized;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace AdbManager;

/// <summary>
/// 相册预览窗口：MediaStore 图册枚举 + 自绘虚拟网格 + 可见范围懒加载缩略图。
/// 双击=用默认程序打开（无关联则弹 Open With）；右键=打开/下载/复制/删除/全选；
/// Ctrl+左键=多选；Ctrl+/- 与 Ctrl+滚轮=缩放（列数粘性：只被动减列不自动加列）。
/// </summary>
public sealed class PhotoGalleryForm : Form
{
    private readonly DeviceInfo _device;
    private readonly DeviceIoScheduler _sched;
    private readonly GalleryRepository _repo;
    private readonly GalleryCache _cache;
    private readonly ThumbnailService _thumbs;
    private readonly CancellationTokenSource _cts = new();
    private readonly System.Windows.Forms.Timer _pollTimer = new() { Interval = 15000 };
    private bool _pollRunning;
    // 缩略图批量提交（§14）：后台只入队，UI 每 25ms commit 一批 + 一次 Invalidate
    private readonly System.Collections.Concurrent.ConcurrentQueue<(GalleryItem Item, Bitmap Bmp)> _thumbQueue = new();
    private readonly System.Windows.Forms.Timer _commitTimer = new() { Interval = 25 };

    private GalleryGridControl _grid = null!;
    private ComboBox _cmbAlbum = null!;
    private ComboBox _cmbFilter = null!;
    private Button _btnRefresh = null!;
    private Button _btnColMinus = null!;
    private Button _btnColPlus = null!;
    private Label _status = null!;
    private ContextMenuStrip _ctx = null!;

    private IReadOnlyList<GalleryItem> _currentItems = Array.Empty<GalleryItem>();
    private bool _disposed;

    public PhotoGalleryForm(DeviceInfo device)
    {
        _device = device;
        _sched = new DeviceIoScheduler(device.IsUsb); // USB/TCP 分档限流
        _repo = new GalleryRepository(device.Id, _sched);
        _cache = new GalleryCache(device.Id);
        _thumbs = new ThumbnailService(device, _sched); // 会话级 TEMP 缩略图缓存（关窗即删）

        Text = $"相册 - {device.DisplayName}";
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(1000, 700);
        KeyPreview = true;

        BuildUi();

        _commitTimer.Tick += OnCommitTick;

        Load += OnLoadAsync;
        FormClosing += OnFormClosing;
        KeyDown += OnKeyDown;
    }

    private void BuildUi()
    {
        var top = new Panel { Dock = DockStyle.Top, Height = 44 };

        var lblAlbum = new Label { Text = "图册:", Location = new Point(8, 13), AutoSize = true };
        _cmbAlbum = new ComboBox { Location = new Point(48, 8), Width = 240, DropDownStyle = ComboBoxStyle.DropDownList };
        _cmbFilter = new ComboBox { Location = new Point(296, 8), Width = 90, DropDownStyle = ComboBoxStyle.DropDownList };
        _cmbFilter.Items.AddRange(new object[] { "全部", "照片", "视频" });
        _cmbFilter.SelectedIndex = 0;
        _btnRefresh = new Button { Text = "刷新", Location = new Point(394, 7), Size = new Size(44, 27) };
        var lblCols = new Label { Text = "列:", Location = new Point(446, 13), AutoSize = true };
        _btnColMinus = new Button { Text = "－", Location = new Point(470, 7), Size = new Size(32, 27) };
        _btnColPlus = new Button { Text = "＋", Location = new Point(504, 7), Size = new Size(32, 27) };
        _status = new Label
        {
            Text = "正在加载相册...",
            Location = new Point(548, 13),
            AutoSize = false,
            Height = 20,
            Width = 420,
            Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
        };

        _cmbAlbum.SelectedIndexChanged += (s, e) => ApplyFilter();
        _cmbFilter.SelectedIndexChanged += (s, e) => ApplyFilter();
        _btnRefresh.Click += (s, e) => _ = ReloadAsync();
        _btnColMinus.Click += (s, e) => _grid.ChangeColumns(-1); // 放大 / 减列
        _btnColPlus.Click += (s, e) => _grid.ChangeColumns(+1);  // 缩小 / 加列

        top.Controls.AddRange(new Control[] { lblAlbum, _cmbAlbum, _cmbFilter, _btnRefresh, lblCols, _btnColMinus, _btnColPlus, _status });

        _grid = new GalleryGridControl { Dock = DockStyle.Fill };
        _grid.ItemActivated += idx => _ = OpenSelected();
        _grid.ItemRightClicked += OnItemRightClicked;
        _grid.RequestThumbnails += OnRequestThumbnails;
        _grid.SelectionChanged += () => UpdateStatus();
        _grid.FilesDroppedToGrid += (files, dropIndex) => _ = PushLocalFilesToAlbumAsync(files, dropIndex);
        _grid.DragSelectionRequested += OnDragSelectionRequested;

        // 增量轮询：15s 查一次新条目（date_added 基线），新拍的照片/视频自动出现
        _pollTimer.Tick += async (s, e) =>
        {
            if (_disposed || _pollRunning) return;
            _pollRunning = true;
            try
            {
                int added = await _repo.PollNewAsync(_cts.Token);
                if (added > 0 && !_disposed)
                {
                    // 增量并入：保留已加载缩略图（只补新增项的）
                    ApplyFilter(keepThumbs: true);
                    // 用户已在顶部附近 → 滚到顶看新条目；否则只提示，不打断浏览
                    if (-_grid.AutoScrollPosition.Y <= _grid.Height * 1.5)
                        _grid.ScrollTo(0, 0);
                    UpdateStatus();
                    _status.Text = $"新增 {added} 项新媒体（{_currentItems.Count} 项）";
                }
            }
            catch { /* 轮询失败静默 */ }
            finally { _pollRunning = false; }
        };

        Controls.Add(_grid);
        Controls.Add(top);

        BuildContextMenu();
    }

    private void BuildContextMenu()
    {
        _ctx = new ContextMenuStrip();
        _ctx.Items.Add("打开", null, (s, e) => _ = OpenSelected());
        _ctx.Items.Add("下载到...", null, (s, e) => _ = DownloadSelected());
        _ctx.Items.Add("复制", null, (s, e) => _ = CopySelected());
        _ctx.Items.Add("删除", null, (s, e) => _ = DeleteSelected());
        _ctx.Items.Add(new ToolStripSeparator());
        _ctx.Items.Add("全选", null, (s, e) => { _grid.SelectAll(); UpdateStatus(); });
    }

    private string CurrentAlbumKey()
        => _cmbAlbum.SelectedItem is AlbumEntry ae ? ae.Key : GalleryRepository.AllKey;

    private async void OnLoadAsync(object? sender, EventArgs e)
    {
        Task.Run(GalleryCache.CleanupStaleTempFiles); // 清 24h 前的打开/复制临时文件
        await ReloadAsync();
        _pollTimer.Start(); // 首载完成后再起轮询
    }

    private async Task ReloadAsync()
    {
        try
        {
            _status.Text = "正在加载相册...";
            _cmbAlbum.Enabled = false;
            int count = await _repo.LoadAsync(_cts.Token);

            _cmbAlbum.Items.Clear();
            foreach (var (key, display) in _repo.Albums)
                _cmbAlbum.Items.Add(new AlbumEntry(key, display));
            if (_cmbAlbum.Items.Count > 0) _cmbAlbum.SelectedIndex = 0;
            _cmbAlbum.Enabled = true;

            if (count == 0)
                _status.Text = string.IsNullOrEmpty(_repo.LastError)
                    ? "未找到媒体（MediaStore 无结果）"
                    : "读取失败: " + _repo.LastError;

            ApplyFilter();
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            _cmbAlbum.Enabled = true;
            _status.Text = "加载失败: " + ex.Message;
        }
    }

    private void ApplyFilter(bool keepThumbs = false)
    {
        var items = _repo.ItemsInAlbum(CurrentAlbumKey());
        int fi = _cmbFilter?.SelectedIndex ?? 0; // 0 全部, 1 照片, 2 视频
        if (fi == 1) items = items.Where(i => i.Kind == MediaKind.Image).ToList();
        else if (fi == 2) items = items.Where(i => i.Kind == MediaKind.Video).ToList();

        _currentItems = items;
        // keepThumbs：增量轮询路径保留已加载缩略图；手动刷新/切图册走全量重建
        _grid.SetItems(_currentItems, keepThumbs);
        UpdateStatus();
    }

    private void UpdateStatus()
    {
        int sel = _grid.SelectedIndices.Count;
        _status.Text = sel > 0 ? $"{_currentItems.Count} 项，已选 {sel}" : $"{_currentItems.Count} 项";
    }

    // ---- 懒加载缩略图（ThumbnailService：会话TEMP缓存 / 手机侧小图批量 / 原图兜底）----
    private void OnRequestThumbnails()
    {
        if (_disposed) return;
        var (first, last) = _grid.VisibleRange();   // 预取范围（视口上下各 2 屏）
        if (first > last || _currentItems.Count == 0) return;
        var (vf, vl) = _grid.ViewportRange();       // 真正可见：最先拉取
        // 网格内存已持有的 key：跳过，不重复解码/回调（长期功耗）
        var skip = new HashSet<GalleryKey>();
        for (int i = first; i <= last; i++)
        {
            var it = _currentItems[i];
            if (_grid.HasThumb(it.Key)) skip.Add(it.Key);
        }
        // 显示尺寸 = 当前网格 thumbPx：后台一次缩到位，Paint 只 blit（§12）
        _thumbs.EnqueueRange(first, last, vf, vl, _currentItems, OnThumbReady, _grid.ThumbPx, skip);
    }

    /// <summary>
    /// 后台线程回调：只入队，不 BeginInvoke（§14：20 张短时完成 = 20 个 UI transaction 是"一顿一顿"的根因）。
    /// UI 线程由 <see cref="_commitTimer"/> 每 25ms 批量 commit 一批 + 一次 Invalidate。
    /// </summary>
    private void OnThumbReady(GalleryItem item, Bitmap bmp)
    {
        if (_disposed) { bmp.Dispose(); return; }
        _thumbQueue.Enqueue((item, bmp));
        try { BeginInvoke(new System.Windows.Forms.MethodInvoker(StartCommitTimer)); }
        catch (ObjectDisposedException) { bmp.Dispose(); }
    }

    private bool _commitTimerArmed;

    private void StartCommitTimer()
    {
        if (_disposed || _commitTimerArmed) return;
        _commitTimerArmed = true;
        _commitTimer.Start();
    }

    /// <summary>UI 线程每帧最多 16 张批量提交 + 一次整窗失效（§14：每帧最多一次 thumbnail commit）。</summary>
    private void OnCommitTick(object? s, EventArgs e)
    {
        _commitTimer.Stop();
        _commitTimerArmed = false;
        int n = 0;
        while (n < 16 && _thumbQueue.TryDequeue(out var pair))
        {
            var (item, bmp) = pair;
            n++;
            // 身份按稳定键（类型+_id）校验：列表重排/删除/切图册后 key 不在网格 → 丢弃
            if (!_grid.SetThumbnail(item.Key, bmp)) bmp.Dispose();
        }
        if (n > 0)
        {
            _grid.Invalidate(); // 一批一次失效，替代逐张 Invalidate
            DiagLog.Info($"commit: n={n} queueLeft={_thumbQueue.Count}");
        }
        if (!_thumbQueue.IsEmpty) StartCommitTimer();
    }

    private void DrainThumbQueue()
    {
        while (_thumbQueue.TryDequeue(out var pair)) pair.Bmp.Dispose();
    }

    // ---- 选中项 ----
    private List<GalleryItem> SelectedItems()
    {
        var list = new List<GalleryItem>();
        foreach (var idx in _grid.SelectedIndices)
            if (idx >= 0 && idx < _currentItems.Count) list.Add(_currentItems[idx]);
        return list;
    }

    // ---- 打开（双击 / 菜单）：唯一临时副本 + 按内容嗅探格式，源文件/缓存绝不直接打开 ----
    private async Task OpenSelected()
    {
        var item = SelectedItems().FirstOrDefault();
        if (item == null) return;
        _status.Text = "正在准备打开...";
        try
        {
            var path = await _cache.ReadToTempFileAsync(item, formatCorrect: true, _sched, _cts.Token);
            try
            {
                var fi = new FileInfo(path);
                string head = "?";
                if (fi.Exists)
                {
                    using var rs = new FileStream(path, FileMode.Open, FileAccess.Read);
                    var buf = new byte[4];
                    if (rs.Read(buf, 0, 4) == 4) head = Convert.ToHexString(buf);
                }
                DiagLog.Info($"open: id={item.Id} kind={item.Kind} path={path} size={(fi.Exists ? fi.Length : -1)} head={head}");
            }
            catch { }
            OpenWithShell(path);
            UpdateStatus();
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            MessageBox.Show(this, "打开失败: " + ex.Message, "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
            UpdateStatus();
        }
    }

    private static void OpenWithShell(string path)
    {
        try
        {
            Process.Start(new ProcessStartInfo { FileName = path, UseShellExecute = true });
        }
        catch (Win32Exception)
        {
            TryOpenWithDialog(path); // 无文件关联时显式弹出 Open With
        }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct OPENASINFO
    {
        public string pcszFile;
        public IntPtr pcszClass;
        public uint oaifInFlags;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern int SHOpenWithDialog(IntPtr hwndOwner, ref OPENASINFO poainfo);

    private static void TryOpenWithDialog(string path)
    {
        try
        {
            var info = new OPENASINFO { pcszFile = path, pcszClass = IntPtr.Zero, oaifInFlags = 0x4 /* OAIF_EXEC */ };
            SHOpenWithDialog(IntPtr.Zero, ref info);
        }
        catch { /* 忽略：回退失败不影响主流程 */ }
    }

    // ---- 右键菜单 ----
    private void OnItemRightClicked(int index)
    {
        var p = _grid.PointToClient(Cursor.Position);
        _ctx.Show(_grid, p);
    }

    // ---- 下载（唯一落盘场景之一：读 TEMP 临时副本 → 用户选的目标路径）----
    private async Task DownloadSelected()
    {
        var items = SelectedItems();
        if (items.Count == 0) return;
        try
        {
            if (items.Count == 1)
            {
                var item = items[0];
                using var sfd = new SaveFileDialog { FileName = item.DisplayName };
                if (sfd.ShowDialog(this) != DialogResult.OK) return;
                var tmp = await _cache.ReadToTempFileAsync(item, formatCorrect: false, _sched, _cts.Token);
                File.Copy(tmp, sfd.FileName, true);
            }
            else
            {
                using var fbd = new FolderBrowserDialog { Description = "选择保存目录" };
                if (fbd.ShowDialog(this) != DialogResult.OK) return;
                for (int i = 0; i < items.Count; i++)
                {
                    _status.Text = $"下载 {i + 1}/{items.Count}...";
                    var tmp = await _cache.ReadToTempFileAsync(items[i], formatCorrect: false, _sched, _cts.Token);
                    File.Copy(tmp, Path.Combine(fbd.SelectedPath, items[i].DisplayName), true);
                }
            }
            UpdateStatus();
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            MessageBox.Show(this, "下载失败: " + ex.Message, "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    // ---- 复制（Windows FileDrop 必须持本地路径 → 用 TEMP 唯一临时副本，24h 自动清理）----
    private async Task CopySelected()
    {
        var items = SelectedItems();
        if (items.Count == 0) return;
        try
        {
            var paths = new List<string>(items.Count);
            for (int i = 0; i < items.Count; i++)
            {
                _status.Text = $"复制 {i + 1}/{items.Count}（下载中）...";
                var tmp = await _cache.ReadToTempFileAsync(items[i], formatCorrect: false, _sched, _cts.Token);
                paths.Add(tmp);
            }
            var fileDrop = new StringCollection();
            foreach (var p in paths) fileDrop.Add(p);
            Clipboard.SetFileDropList(fileDrop);
            _status.Text = $"已复制 {paths.Count} 个文件到剪贴板";
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            MessageBox.Show(this, "复制失败: " + ex.Message, "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    // ---- 删除（provider 感知：content delete，保持 MediaStore 索引一致）----
    private async Task DeleteSelected()
    {
        var items = SelectedItems();
        if (items.Count == 0) return;
        if (MessageBox.Show(this, $"确定删除选中的 {items.Count} 个项目？此操作不可撤销。",
                "确认删除", MessageBoxButtons.YesNo, MessageBoxIcon.Warning) != DialogResult.Yes)
            return;

        try
        {
            int ok = 0, fail = 0;
            var deleted = new List<GalleryItem>();
            for (int i = 0; i < items.Count; i++)
            {
                var it = items[i];
                _status.Text = $"删除 {i + 1}/{items.Count}...";
                try
                {
                    // 1) provider 删除（保持 MediaStore 索引一致）；部分 ROM 对 shell 拒绝（exit 仍可能为 0）
                    var uri = it.Kind == MediaKind.Image ? GalleryRowParser.ImagesUri : GalleryRowParser.VideosUri;
                    var where = AdbHelper.ShellQuote($"_id={it.Id}");
                    await AdbHelper.ShellCommandAsync(_device.Id,
                        new[] { "content", "delete", "--uri", uri, "--where", where }, 20000, _cts.Token);

                    // 2) 用原始路径验证是否真删掉；没删掉则 rm -f 兜底（content CLI 会吞异常，exit 0 不可全信）
                    if (it.DataPath.Length > 0)
                    {
                        var testResult = await AdbHelper.ShellCommandResultAsync(_device.Id,
                            "ls " + AdbHelper.ShellQuote(it.DataPath), 10000);
                        if (testResult.Succeeded)
                        {
                            await AdbHelper.ShellExecAsync(_device.Id,
                                "rm -f " + AdbHelper.ShellQuote(it.DataPath), 20000);
                        }
                    }
                    ok++;
                    deleted.Add(it);
                    DeletionTombstones.Add(it.Kind, it.Id); // 防 stale row 在刷新时复活
                }
                catch { fail++; }
            }

            // 增量移除：不重跑 content query、不全量重建 UI（缩略图走会话磁盘缓存快速回填）
            if (deleted.Count > 0)
            {
                _thumbs.DeleteItemCaches(deleted);
                _repo.RemoveItems(deleted.Select(i => (i.Kind, i.Id)));
                ApplyFilter();
            }
            _status.Text = $"删除完成：成功 {ok}，失败 {fail}" + (fail > 0 ? "（失败项请手动刷新）" : "");
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            MessageBox.Show(this, "删除失败: " + ex.Message, "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    // ---- 拖入：Windows 本地文件 → 手机"该相册目录"（复制粘贴语义，同名覆盖）----

    private async Task PushLocalFilesToAlbumAsync(IReadOnlyList<string> localFiles, int dropIndex)
    {
        // 目标目录：丢在图片上 = 该图所在目录；丢在空白 = 当前图册第一项所在目录（"全部"时即最新媒体的目录）
        string dir = (dropIndex >= 0 && dropIndex < _currentItems.Count)
            ? AlbumDirOf(_currentItems[dropIndex])
            : CurrentAlbumDir();

        if (_disposed) return;
        try
        {
            _status.Text = $"正在准备传输 {localFiles.Count} 个文件到 {dir}...";
            await AdbHelper.ShellExecAsync(_device.Id, "mkdir -p " + AdbHelper.ShellQuote(dir), 15000);

            int ok = 0, fail = 0;
            for (int i = 0; i < localFiles.Count; i++)
            {
                _status.Text = $"传输 {i + 1}/{localFiles.Count} → {dir}...";
                try
                {
                    await _sched.Transfer.WaitAsync(_cts.Token);
                    try
                    {
                        await AdbHelper.PushFileAsync(_device.Id, localFiles[i],
                            dir + "/" + Path.GetFileName(localFiles[i]), 300000, _cts.Token);
                    }
                    finally { _sched.Transfer.Release(); }
                    ok++;
                }
                catch { fail++; }
            }

            // 提示系统索引（content call scan_file，非所有 ROM 支持，失败静默——15s 增量轮询兜底）
            for (int i = 0; i < localFiles.Count; i++)
            {
                try
                {
                    await AdbHelper.ShellCommandAsync(_device.Id,
                        new[] { "content", "call", "--uri", GalleryRowParser.ImagesUri,
                                "--method", "scan_file", "--arg", dir + "/" + Path.GetFileName(localFiles[i]) },
                        10000, _cts.Token);
                }
                catch { /* best-effort */ }
            }

            int added = await _repo.PollNewAsync(_cts.Token);
            if (added > 0 && !_disposed) ApplyFilter(keepThumbs: true);

            if (!_disposed)
                _status.Text = $"已传输 {ok}/{localFiles.Count} 到 {dir}" +
                               (fail > 0 ? $"（{fail} 个失败）" : "") +
                               (added > 0 ? $"，相册已更新 +{added}" : "（相册稍后自动出现）");
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            if (!_disposed)
                MessageBox.Show(this, "传输失败: " + ex.Message, "错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    /// <summary>媒体项所在的手机文件系统目录（_data 优先，relative_path 兜底）。</summary>
    private static string AlbumDirOf(GalleryItem it)
    {
        if (it.DataPath.Length > 0)
        {
            int slash = it.DataPath.LastIndexOf('/');
            if (slash > 0) return it.DataPath[..slash];
        }
        var rp = (it.RelativePath ?? "").TrimEnd('/');
        return rp.Length > 0 ? "/storage/emulated/0/" + rp : "/storage/emulated/0/DCIM/Camera";
    }

    private string CurrentAlbumDir()
    {
        var first = _currentItems.FirstOrDefault();
        return first != null ? AlbumDirOf(first) : "/storage/emulated/0/DCIM/Camera";
    }

    // ---- 长按 2s 拖出：准备本地临时副本 → OLE 拖拽（CF_HDROP）----
    // 丢进文件夹 = 资源管理器复制；丢进微信发送框 = 微信按文件格式自行发送
    // （图片扩展名经魔数校验后是正确的，微信按扩展名识别图片/文件，无需我们额外处理）。

    private bool _dragPrepBusy;

    private async void OnDragSelectionRequested(IReadOnlyList<int> indices)
    {
        if (_dragPrepBusy || _disposed) return;
        _dragPrepBusy = true;
        var items = indices
            .Where(i => i >= 0 && i < _currentItems.Count)
            .Select(i => _currentItems[i])
            .ToList();
        try
        {
            if (items.Count == 0) return;
            _status.Text = $"准备 {items.Count} 个文件用于拖拽...";
            // 并行准备（Transfer 信号量限真实并发）；formatCorrect=true 保证扩展名与内容一致；
            // 单项失败不拖垮整批（跳过失败项）
            var tasks = items.Select(async it =>
            {
                try { return await _cache.ReadToTempFileAsync(it, formatCorrect: true, _sched, _cts.Token); }
                catch { return ""; }
            })
                .ToList();
            var results = await Task.WhenAll(tasks);
            // 按 (item, path) 配对改名
            var paths = new List<string>(results.Length);
            for (int i = 0; i < items.Count && i < results.Length; i++)
            {
                if (string.IsNullOrEmpty(results[i])) continue;
                paths.Add(FriendlyTempName(items[i], results[i]));
            }
            if (paths.Count == 0)
            {
                _status.Text = "拖拽准备失败：文件读取失败";
                return;
            }
            _status.Text = $"拖拽 {paths.Count} 个文件（丢进文件夹=复制）";
            int r = _grid.BeginSelectionDrag(paths);
            _status.Text = r == 1 ? $"已释放 {paths.Count} 个文件到目标位置"
                           : r == 0 ? "拖拽已取消"
                           : "拖拽已取消（准备期间松手）";
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            if (!_disposed) _status.Text = "拖拽准备失败: " + ex.Message;
        }
        finally { _dragPrepBusy = false; }
    }

    /// <summary>临时副本改回展示文件名（GUID → 原名），让"复制粘贴/发送"落地的文件名字可读；扩展名以内容魔数探测结果为准。</summary>
    private static string FriendlyTempName(GalleryItem item, string cachedPath)
    {
        try
        {
            var ext = Path.GetExtension(cachedPath); // 魔数校验后的真实扩展名
            var baseName = Path.GetExtension(item.DisplayName).Length > 0
                ? Path.GetFileNameWithoutExtension(item.DisplayName)
                : item.DisplayName;
            baseName = AdbHelper.SanitizeFileName(baseName);
            if (baseName.Length == 0) return cachedPath;
            string target = Path.Combine(GalleryCache.TempRoot, baseName + ext);
            if (File.Exists(target))
                target = Path.Combine(GalleryCache.TempRoot,
                    baseName + "_" + Guid.NewGuid().ToString("N")[..6] + ext);
            if (target != cachedPath)
            {
                File.Move(cachedPath, target);
                return target;
            }
        }
        catch { /* 改名失败不影响拖拽（GUID 名也能用） */ }
        return cachedPath;
    }

    // ---- 键盘：Ctrl+/- 改列数，Ctrl+A 全选 ----
    private void OnKeyDown(object? sender, KeyEventArgs e)
    {
        if (!ModifierKeys.HasFlag(Keys.Control)) return;
        switch (e.KeyCode)
        {
            case Keys.Add:      // 小键盘 +
            case Keys.Oemplus:  // 主键盘 +（= / +）
                _grid.ChangeColumns(-1); e.Handled = true; break; // 放大 / 减列
            case Keys.Subtract: // 小键盘 -
            case Keys.OemMinus: // 主键盘 -
                _grid.ChangeColumns(+1); e.Handled = true; break; // 缩小 / 加列
            case Keys.A:
                _grid.SelectAll(); UpdateStatus(); e.Handled = true; break;
        }
    }

    private void OnFormClosing(object? sender, FormClosingEventArgs e)
    {
        _pollTimer.Stop();
        _pollTimer.Dispose();
        _commitTimer.Stop();
        _commitTimer.Dispose();
        _cts.Cancel();
        _disposed = true;
        DrainThumbQueue(); // 未提交的显示位图释放（网格自己的 _thumbMem 随控件 Dispose 释放）
        _ = _thumbs.ShutdownAsync(); // 后台清理：取消在途工作 + 删除本会话缩略图临时目录
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) _cts.Dispose();
        base.Dispose(disposing);
    }

    /// <summary>图册下拉项。</summary>
    private sealed class AlbumEntry
    {
        public string Key { get; }
        public string Display { get; }
        public AlbumEntry(string key, string display) { Key = key; Display = display; }
        public override string ToString() => Display;
    }
}
