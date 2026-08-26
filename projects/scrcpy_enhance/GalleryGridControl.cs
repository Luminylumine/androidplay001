using System.Drawing;
using System.Drawing.Drawing2D;

namespace AdbManager;

/// <summary>
/// 自绘虚拟网格：几千项也不创建几千个子控件，只绘制可视区附近的 tile。
/// 交互：默认 3 列；Ctrl+/Ctrl- 增减列数（并铺满视口）；Ctrl+滚轮缩放（列数粘性：
/// 放大溢出时被动减列，缩小绝不自动加列）；Ctrl+左键多选；双击激活（打开）；右键菜单。
/// </summary>
public sealed class GalleryGridControl : ScrollableControl
{
    private readonly List<GalleryItem> _items = new();
    private readonly HashSet<int> _selected = new();
    private readonly Dictionary<int, Image> _thumbMem = new(); // 内存缩略图（由 Form 填充并管理）

    private int _columns = 3;
    private int _thumbPx = 200;
    public int Gap { get; } = 8;
    public bool ShowNames { get; } = true;

    public int Columns => _columns;
    public int ThumbPx => _thumbPx;

    public event Action<int>? ItemActivated;   // 双击
    public event Action<int>? ItemRightClicked; // 右键
    public event Action? SelectionChanged;
    public event Action? RequestThumbnails;     // 可视区变化，通知 Form 拉取缩略图

    public GalleryGridControl()
    {
        SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.AllPaintingInWmPaint
                 | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
        AutoScroll = true;
        BackColor = Color.FromArgb(30, 30, 32);
    }

    public IReadOnlyList<GalleryItem> Items => _items;
    public IReadOnlyCollection<int> SelectedIndices => _selected;
    public GalleryItem? ItemAt(int index) => (index >= 0 && index < _items.Count) ? _items[index] : null;

    public void SetItems(IEnumerable<GalleryItem> items)
    {
        _items.Clear();
        _items.AddRange(items);
        _selected.Clear();
        ClearThumbMem();
        UpdateLayout();
        RequestThumbnails?.Invoke();
    }

    public void SetThumbnail(int index, Image img)
    {
        if (index < 0 || index >= _items.Count) return;
        if (_thumbMem.TryGetValue(index, out var old))
        {
            if (ReferenceEquals(old, img)) return; // 同一实例不得重复持有（Dispose 竞态）
            _thumbMem.Remove(index);
            old.Dispose();
        }
        _thumbMem[index] = img;
        Invalidate();
    }

    public void ClearSelection()
    {
        _selected.Clear();
        Invalidate();
        SelectionChanged?.Invoke();
    }

    public void SelectAll()
    {
        for (int i = 0; i < _items.Count; i++) _selected.Add(i);
        Invalidate();
        SelectionChanged?.Invoke();
    }

    private void ClearThumbMem()
    {
        foreach (var im in _thumbMem.Values) im.Dispose();
        _thumbMem.Clear();
    }

    private int TileW => _thumbPx + Gap;
    private int ViewportWidth => Math.Max(TileW, ClientSize.Width);

    private void UpdateLayout()
    {
        int rows = (int)Math.Ceiling((double)Math.Max(0, _items.Count) / Math.Max(1, _columns));
        var min = new Size(_columns * TileW, rows * TileW);
        // 值未变则不重设——重设 AutoScrollMinSize 会触发系统对滚动位置的 clamp（画面跳动的诱因之一）
        if (AutoScrollMinSize != min)
        {
            AutoScrollMinSize = min;
            DiagLog.Info($"grid layout: cols={_columns} thumbPx={_thumbPx} items={_items.Count} minSize={min} client={ClientSize} scroll={AutoScrollPosition}");
        }
        Invalidate();
    }

    /// <summary>Ctrl + / Ctrl -：列数 -1 / +1，并让缩略图重新铺满视口。</summary>
    public void ChangeColumns(int delta)
    {
        _columns = Math.Clamp(_columns + delta, 1, 8);
        int w = (ViewportWidth - Gap * (_columns + 1)) / Math.Max(1, _columns);
        _thumbPx = Math.Clamp(w, 96, 640);
        UpdateLayout();
    }

    /// <summary>Ctrl + 滚轮：只改缩略图尺寸；放大溢出时被动减列，缩小绝不自动加列。</summary>
    public void Zoom(int direction)
    {
        _thumbPx = Math.Clamp(_thumbPx + (direction > 0 ? 16 : -16), 96, 640);
        while (_columns > 1 && _columns * TileW > ViewportWidth) _columns--;
        UpdateLayout();
    }

    protected override void OnClientSizeChanged(EventArgs e)
    {
        base.OnClientSizeChanged(e);
        // 窗口变窄：被动减列；变宽：不自动加列（与滚轮同规则）
        while (_columns > 1 && _columns * TileW > ViewportWidth) _columns--;
        UpdateLayout();
    }

    private int IndexAt(Point clientPoint)
    {
        var p = new Point(clientPoint.X - AutoScrollPosition.X, clientPoint.Y - AutoScrollPosition.Y);
        if (p.X < 0 || p.Y < 0) return -1;
        int col = p.X / TileW;
        int row = p.Y / TileW;
        if (col >= _columns) return -1;
        int index = row * _columns + col;
        if (index < 0 || index >= _items.Count) return -1;
        int localX = p.X - col * TileW;
        int localY = p.Y - row * TileW;
        if (localX >= _thumbPx || localY >= _thumbPx) return -1;
        return index;
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        base.OnMouseDown(e);
        if (e.Button != MouseButtons.Left) return;
        int idx = IndexAt(e.Location);
        if (idx < 0) return;
        if (ModifierKeys.HasFlag(Keys.Control))
        {
            if (!_selected.Add(idx)) _selected.Remove(idx); // toggle
        }
        else
        {
            _selected.Clear();
            _selected.Add(idx);
        }
        Invalidate();
        SelectionChanged?.Invoke();
    }

    protected override void OnMouseUp(MouseEventArgs e)
    {
        base.OnMouseUp(e);
        if (e.Button != MouseButtons.Right) return;
        int idx = IndexAt(e.Location);
        if (idx < 0) return;
        if (!_selected.Contains(idx)) { _selected.Clear(); _selected.Add(idx); Invalidate(); }
        ItemRightClicked?.Invoke(idx);
    }

    protected override void OnMouseDoubleClick(MouseEventArgs e)
    {
        base.OnMouseDoubleClick(e);
        int idx = IndexAt(e.Location);
        if (idx < 0) return;
        if (!_selected.Contains(idx)) { _selected.Clear(); _selected.Add(idx); Invalidate(); }
        ItemActivated?.Invoke(idx);
    }

    protected override void OnMouseWheel(MouseEventArgs e)
    {
        if (ModifierKeys.HasFlag(Keys.Control))
        {
            Zoom(e.Delta > 0 ? 1 : -1); // 缩放，不滚动
        }
        else
        {
            base.OnMouseWheel(e);
        }
    }

    /// <summary>滚动到指定内容坐标（WinForms 限制：滚到 (0,0) 只能从其他位置触发）。</summary>
    public void ScrollTo(int x, int y)
    {
        var cur = AutoScrollPosition;
        if (cur == new Point(x, y)) return;
        if (x == 0 && y == 0 && (cur.X != 0 || cur.Y != 0))
        {
            AutoScrollPosition = new Point(0, 0);
            return;
        }
        // 非原点目标：先移出当前坐标再设目标，规避 WinForms 的 no-op 陷阱
        AutoScrollPosition = new Point(cur.X + 1, cur.Y + 1);
        AutoScrollPosition = new Point(x, y);
    }

    /// <summary>
    /// 当前可视区覆盖的 [first, last]（含前后余量，供预取）。
    /// 注意：WinForms <see cref="Control.AutoScrollPosition"/> 的 Y 为负值（向下滚动 2000px → -2000），
    /// 可见内容行 = -Y / TileW，符号弄反会导致滚动后绘制范围整体错位（视口空白）。
    /// </summary>
    public (int first, int last) VisibleRange()
    {
        if (_items.Count == 0) return (0, -1);
        int scrollY = Math.Max(0, -AutoScrollPosition.Y);
        int firstRow = Math.Max(0, scrollY / TileW - 1);
        int rowsVisible = ClientSize.Height / TileW + 2;
        int first = Math.Max(0, Math.Min(_items.Count - 1, firstRow * _columns));
        int last = Math.Min(_items.Count - 1, (firstRow + rowsVisible) * _columns + _columns - 1);
        return (first, last);
    }

    protected override void OnScroll(ScrollEventArgs se)
    {
        base.OnScroll(se);
        PruneThumbnails();
        RequestThumbnails?.Invoke();
    }

    /// <summary>
    /// 释放滚出可视区（含上下各 2 行余量）的内存缩略图——缩略图只活在内存里，
    /// 不占磁盘；释放后如需回看会重新拉取（限流低并发，不会堆积）。
    /// </summary>
    public void PruneThumbnails()
    {
        if (_thumbMem.Count == 0) return;
        int scrollY = Math.Max(0, -AutoScrollPosition.Y);
        int rowsVisible = ClientSize.Height / TileW;
        int topRow = Math.Max(0, scrollY / TileW - 2);
        int bottomRow = topRow + rowsVisible + 4;

        var dead = _thumbMem.Keys.Where(i =>
        {
            int row = i / _columns;
            return row < topRow || row > bottomRow;
        }).ToList();
        foreach (var i in dead)
        {
            if (_thumbMem.TryGetValue(i, out var img))
            {
                _thumbMem.Remove(i);
                img.Dispose();
            }
        }
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(BackColor);
        if (_items.Count == 0) return;

        // AutoScroll 只平移子控件，自绘必须自行把 Graphics 平移到内容坐标系
        // （漏掉这行会导致：滚动后画面与滚动位置失步、点击命中与画面不一致）
        g.TranslateTransform(-AutoScrollPosition.X, -AutoScrollPosition.Y);

        var (first, last) = VisibleRange();

        using var nameBrush = new SolidBrush(Color.White);
        using var nameBg = new SolidBrush(Color.FromArgb(170, 0, 0, 0));
        using var selPen = new Pen(Color.FromArgb(0, 120, 215), 3f);
        using var dimBrush = new SolidBrush(Color.FromArgb(90, 0, 0, 0));
        using var playBrush = new SolidBrush(Color.FromArgb(220, 255, 255, 255));
        using var phBrush = new SolidBrush(Color.FromArgb(54, 54, 60));
        using var phPen = new Pen(Color.FromArgb(92, 92, 100)); // 占位格边界：缩略图未到位也能立刻看到格子结构
        using var nameFont = new Font("Segoe UI", 8f);
        using var sf = new StringFormat
        {
            Trimming = StringTrimming.EllipsisCharacter,
            Alignment = StringAlignment.Near,
            LineAlignment = StringAlignment.Center
        };

        for (int index = first; index <= last && index < _items.Count; index++)
        {
            int row = index / _columns, col = index % _columns;
            var rect = new Rectangle(col * TileW, row * TileW, _thumbPx, _thumbPx);

            if (_thumbMem.TryGetValue(index, out var img))
                g.DrawImage(img, rect);
            else
            {
                g.FillRectangle(phBrush, rect);
                g.DrawRectangle(phPen, rect); // 未加载也立刻显示格子边界
            }

            if (_items[index].Kind == MediaKind.Video)
            {
                g.FillRectangle(dimBrush, rect);
                int cx = rect.X + rect.Width / 2, cy = rect.Y + rect.Height / 2;
                g.FillPolygon(playBrush, new[]
                {
                    new Point(cx - 10, cy - 14), new Point(cx + 15, cy), new Point(cx - 10, cy + 14)
                });
            }

            if (ShowNames)
            {
                int barH = 20;
                var bar = new Rectangle(rect.X, rect.Bottom - barH, rect.Width, barH);
                g.FillRectangle(nameBg, bar);
                g.DrawString(_items[index].DisplayName, nameFont, nameBrush,
                    new RectangleF(bar.X + 3, bar.Y + 1, bar.Width - 6, barH - 2), sf);
            }

            if (_selected.Contains(index))
                g.DrawRectangle(selPen, rect);
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing) ClearThumbMem();
        base.Dispose(disposing);
    }
}
