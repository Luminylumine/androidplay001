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
                    _status.Text = $"检测到 {added} 项新媒体";
                    ApplyFilter(); // 会话缩略图缓存命中，回填很快
                    if (_currentItems.Count > 0)
                        _grid.ScrollTo(0, 0); // 新条目在最前（按时间倒序），回到顶部
                    UpdateStatus();
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

    private void ApplyFilter()
    {
        var items = _repo.ItemsInAlbum(CurrentAlbumKey());
        int fi = _cmbFilter?.SelectedIndex ?? 0; // 0 全部, 1 照片, 2 视频
        if (fi == 1) items = items.Where(i => i.Kind == MediaKind.Image).ToList();
        else if (fi == 2) items = items.Where(i => i.Kind == MediaKind.Video).ToList();

        _currentItems = items;
        _grid.SetItems(_currentItems); // 内部会清内存缩略图并触发 RequestThumbnails（会话磁盘缓存快速回填）
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
        var (first, last) = _grid.VisibleRange();
        _thumbs.EnqueueRange(first, last, _currentItems, OnThumbReady);
    }

    /// <summary>后台线程回调：回 UI 线程按 itemId 校验后交给网格（网格拥有该 Bitmap 的生命周期）。</summary>
    private void OnThumbReady(int index, Bitmap bmp, long itemId)
    {
        if (_disposed) { bmp.Dispose(); return; }
        try
        {
            BeginInvoke(() =>
            {
                if (_disposed || index < 0 || index >= _currentItems.Count || _currentItems[index].Id != itemId)
                {
                    DiagLog.Info($"onReady DROP: idx={index} itemId={itemId} count={_currentItems.Count} disposed={_disposed}");
                    bmp.Dispose(); // 期间发生过删除/切图册，索引已漂移
                    return;
                }
                _grid.SetThumbnail(index, bmp);
                DiagLog.Info($"onReady SET: idx={index} itemId={itemId} bmp={bmp.Width}x{bmp.Height}");
            });
        }
        catch (ObjectDisposedException) { bmp.Dispose(); }
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
                        var testOut = await AdbHelper.ShellExecAsync(_device.Id,
                            "ls " + AdbHelper.ShellQuote(it.DataPath), 10000);
                        if (testOut.Contains("No such file or directory"))
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
        _cts.Cancel();
        _disposed = true;
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
