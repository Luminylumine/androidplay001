using System.Drawing;
using System.Drawing.Drawing2D;

namespace AdbManager;

/// <summary>
/// 自绘虚拟网格：几千项也不创建几千个子控件，只绘制可视区 tile。
/// 交互：默认 3 列；Ctrl+/Ctrl- 增减列数（并铺满视口）；Ctrl+滚轮缩放（列数粘性：
/// 放大溢出时被动减列，缩小绝不自动加列）；Ctrl+左键多选；双击激活（打开）；右键菜单。
///
/// 拖拽交互（状态机 <see cref="PressState"/>）：
/// - 左键按住后**移动**（>6px）= 框选：锚点 tile → 光标 tile 的矩形内全部 tile，
///   按住期间终点可连续修改（每帧实时重算选中 + 重绘虚线框）；松手保留选中。
/// - 左键**按住不动 2s** = "选择完并拖拽"：触发 <see cref="DragSelectionRequested"/>，
///   Form 准备本地临时副本后回调 <see cref="BeginSelectionDrag"/> 进入 OLE 拖拽
///   （CF_HDROP → 丢进文件夹=复制、丢进微信发送框=发送，均由接收方按文件格式自行处理）；
///   准备期间松手 = 取消拖拽，退回普通点击选中。
/// - 外部本地文件（资源管理器）拖入 = <see cref="FilesDroppedToGrid"/>（payload: 文件列表 + 命中项）。
///
/// 坐标模型（定稿于调研 AdbManager_GalleryGrid_Scroll_Overlap_RootCause_Architecture_2026-08-26）：
///   ScrollOffset = max(0, -AutoScrollPosition) —— 恒正，"视口顶在内容坐标中的偏移"
///   client = content - ScrollOffset ；content = client + ScrollOffset
///   Paint / HitTest / Invalidate 全部走同一组 helper，不使用 Graphics transform（杜绝取负符号错误）。
/// 缩略图：内存按 <see cref="GalleryKey"/> 稳定键缓存（列表重排不漂移），字节预算 LRU（§13），
/// 释放只发生在插入超预算 / 滚动静止后，绝不在滚动热路径（§8）。
/// </summary>
public sealed class GalleryGridControl : ScrollableControl
{
    /// <summary>显示位图内存预算（§13：按字节而非张数；200px≈156KB/张 → 约 800 张）。</summary>
    private const long ThumbMemBudgetBytes = 128L * 1024 * 1024;

    private readonly List<GalleryItem> _items = new();
    private readonly HashSet<int> _selected = new();

    private readonly Dictionary<GalleryKey, Image> _thumbMem = new();  // 稳定键（类型+_id）
    private readonly Dictionary<GalleryKey, int> _indexByKey = new();
    private readonly List<GalleryKey> _thumbOrder = new();             // FIFO 淘汰顺序
    private long _thumbMemBytes;

    // GDI+ 资源缓存：逐帧创建 Font/Brush 是滚动卡顿源之一，改为字段级复用
    private SolidBrush? _nameBrush, _nameBg, _dimBrush, _playBrush, _phBrush, _marqueeFill;
    private Pen? _selPen, _phPen, _marqueePen;
    private Font? _nameFont, _dbgFont;
    private StringFormat? _nameSf;
    private long _lastPaintLog, _lastScrollLog;

    private int _columns = 3;
    private int _thumbPx = 200;
    private long _layoutGen; // 列数+尺寸代际：变化 → 显示位图作废（磁盘规范缓存保留）
    public int Gap { get; } = 8;
    public bool ShowNames { get; } = true;

    public int Columns => _columns;
    public int ThumbPx => _thumbPx;

    public event Action<int>? ItemActivated;   // 双击
    public event Action<int>? ItemRightClicked; // 右键
    public event Action? SelectionChanged;
    public event Action? RequestThumbnails;     // 可视区变化，通知 Form 预取缩略图
    /// <summary>长按 2s 触发：请求拖出当前选中项。Form 准备本地临时副本后回调 <see cref="BeginSelectionDrag"/>。</summary>
    public event Action<IReadOnlyList<int>>? DragSelectionRequested;
    /// <summary>外部本地文件拖入网格（payload: 文件路径列表 + 命中项索引，-1 = 空白处）。</summary>
    public event Action<IReadOnlyList<string>, int>? FilesDroppedToGrid;

    /// <summary>左键按住的状态机（交互见类头注释）。</summary>
    private enum PressState { None, Pending, Marquee, DragPreparing }

    private const int MarqueeThresholdPx = 6;   // 移动超过该距离 → 框选（取消长按）
    private const int LongPressMs = 2000;       // 按住不动 2s → 选择完并拖拽

    private PressState _press = PressState.None;
    private int _anchorIndex = -1;              // 按下时的 tile
    private Point _pressStart;                  // 按下点（client）
    private Point _marqueeCursor;               // 框选终点（client，实时）
    private long _longPressAt;                  // 按下时刻（TickCount64）
    private readonly System.Windows.Forms.Timer _longPressTimer = new() { Interval = 100 };

    // 滚动静止定时器（§13/§15）：GDI Dispose 只在这里/插入超预算时发生，绝不在 OnScroll 热路径
    private readonly System.Windows.Forms.Timer _idleTimer = new() { Interval = 400 };

    public GalleryGridControl()
    {
        SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.AllPaintingInWmPaint
                 | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
        AutoScroll = true;
        AllowDrop = true; // 接收资源管理器拖入的本地文件（复制粘贴到手机相册目录）
        BackColor = Color.FromArgb(30, 30, 32);
        _idleTimer.Tick += (s, e) => { _idleTimer.Stop(); EvictThumbnailsByBudget(); };
        _longPressTimer.Tick += (s, e) =>
        {
            // 看门狗：在控件外松开时收不到 MouseUp → 按"鼠标已松开"收尾（真实输入状态判定）
            if ((Control.MouseButtons & MouseButtons.Left) == 0 && _press == PressState.Pending)
            {
                _longPressTimer.Stop();
                _selected.Clear();
                _selected.Add(_anchorIndex);
                _press = PressState.None;
                _anchorIndex = -1;
                Invalidate();
                SelectionChanged?.Invoke();
                return;
            }
            // 用 TickCount64 判 2s（不累加 Interval，避免定时器漂移）
            if (_press == PressState.Pending && Environment.TickCount64 - _longPressAt >= LongPressMs)
                EnterDragPreparing();
        };
    }

    public IReadOnlyList<GalleryItem> Items => _items;
    public IReadOnlyCollection<int> SelectedIndices => _selected;
    public GalleryItem? ItemAt(int index) => (index >= 0 && index < _items.Count) ? _items[index] : null;

    // ================= 坐标 helper（滚动坐标唯一入口，Paint/Hit/Invalidate 共用）=================

    private int TileW => _thumbPx + Gap;
    private int TileH => _thumbPx + Gap; // 文件名条覆盖在图片内部，不占额外高度（§22）

    /// <summary>视口偏移（恒正）：AutoScrollPosition getter 已滚动时为负，取负即得。</summary>
    private Point ScrollOffset
    {
        get
        {
            var p = AutoScrollPosition;
            return new Point(Math.Max(0, -p.X), Math.Max(0, -p.Y));
        }
    }

    private Rectangle TileContentRect(int index)
    {
        int row = index / _columns, col = index % _columns;
        return new Rectangle(col * TileW, row * TileH, _thumbPx, _thumbPx);
    }

    private static Rectangle ContentToClient(Rectangle r, Point off)
    {
        r.Offset(-off.X, -off.Y);
        return r;
    }

    private static Point ClientToContent(Point p, Point off)
        => new(p.X + off.X, p.Y + off.Y);

    /// <summary>Paint 范围：仅真正可见行（预取另算，§5）。</summary>
    private (int first, int last) GetPaintRange(Point off)
    {
        if (_items.Count == 0) return (0, -1);
        int rowCount = (_items.Count + _columns - 1) / _columns;
        int firstRow = Math.Clamp(off.Y / TileH, 0, Math.Max(0, rowCount - 1));
        int lastY = off.Y + Math.Max(0, ClientSize.Height - 1);
        int lastRow = Math.Clamp(lastY / TileH, firstRow, Math.Max(firstRow, rowCount - 1));
        int first = firstRow * _columns;
        int last = Math.Min(_items.Count - 1, (lastRow + 1) * _columns - 1);
        return (first, last);
    }

    /// <summary>
    /// 预取范围：可视区上/下各预留 2 屏（用户要求：当前位置优先，上下提前渲染两屏缓冲）。
    /// Form 用它请求缩略图。
    /// </summary>
    public (int first, int last) VisibleRange()
    {
        if (_items.Count == 0) return (0, -1);
        var off = ScrollOffset;
        int rowsVisible = Math.Max(1, (ClientSize.Height + TileH - 1) / TileH);
        int maxRow = (_items.Count - 1) / _columns;
        int topRow = off.Y / TileH;
        int firstRow = Math.Clamp(topRow - 2 * rowsVisible, 0, maxRow);
        int lastRow = Math.Clamp(topRow + rowsVisible + 2 * rowsVisible, firstRow, maxRow);
        int first = firstRow * _columns;
        int last = Math.Min(_items.Count - 1, (lastRow + 1) * _columns - 1);
        return (first, last);
    }

    /// <summary>真正可见范围（Form 用它做缩略图优先级排序：可见区最先拉取）。</summary>
    public (int first, int last) ViewportRange() => GetPaintRange(ScrollOffset);

    /// <summary>网格内存是否已持有该 key 的显示位图（Form 据此跳过重复请求，省解码/回调功耗）。</summary>
    public bool HasThumb(GalleryKey key) => _thumbMem.ContainsKey(key);

    private int IndexAt(Point clientPoint)
    {
        if (_items.Count == 0) return -1;
        var p = ClientToContent(clientPoint, ScrollOffset);
        if (p.X < 0 || p.Y < 0) return -1;
        int col = p.X / TileW, row = p.Y / TileH;
        if (col >= _columns) return -1;
        int index = row * _columns + col;
        if (index < 0 || index >= _items.Count) return -1;
        int localX = p.X - col * TileW, localY = p.Y - row * TileH;
        if (localX >= _thumbPx || localY >= _thumbPx) return -1;
        return index;
    }

    // ================= 数据 / 缩略图 =================

    /// <summary>
    /// 重建列表。缩略图按 <see cref="GalleryKey"/> 稳定键持有，重排/增量刷新天然不丢；
    /// keepThumbs=false（手动刷新/切图册）才整体清空内存（磁盘规范缓存仍在，回填很快）。
    /// </summary>
    public void SetItems(IEnumerable<GalleryItem> items, bool keepThumbs = false)
    {
        var newItems = items.ToList();

        // 重复项检测（同一 类型+_id 出现多次 = 列表被污染，画面"重叠"的候选根因）
        {
            var seen = new HashSet<GalleryKey>();
            int dups = 0;
            foreach (var it in newItems)
                if (!seen.Add(it.Key)) dups++;
            if (dups > 0) DiagLog.Info($"SETITEMS DUPES: {dups}/{newItems.Count}");
        }

        _indexByKey.Clear();
        for (int i = 0; i < newItems.Count; i++)
            _indexByKey[newItems[i].Key] = i;

        if (!keepThumbs)
        {
            ClearThumbMem();
        }
        else
        {
            // 仅释放已不在列表中的 key
            var dead = _thumbMem.Keys.Where(k => !_indexByKey.ContainsKey(k)).ToList();
            foreach (var k in dead)
                RemoveThumb(k);
        }

        _items.Clear();
        _items.AddRange(newItems);
        _selected.Clear();
        UpdateLayout();
        RequestThumbnails?.Invoke();
    }

    /// <summary>
    /// 提交一张显示位图（UI 线程批量 commit 调用）。返回 false = key 已不在列表，调用方自行 Dispose。
    /// 本方法不 Invalidate——由 Form 每帧批量提交后统一一次失效（§14：合并 UI callback）。
    /// </summary>
    public bool SetThumbnail(GalleryKey key, Image img)
    {
        if (!_indexByKey.ContainsKey(key)) return false;
        if (_thumbMem.TryGetValue(key, out var old))
        {
            if (ReferenceEquals(old, img)) return true;
            _thumbMemBytes -= (long)old.Width * old.Height * 4;
            old.Dispose();
        }
        _thumbMem[key] = img;
        _thumbOrder.Add(key);
        _thumbMemBytes += (long)img.Width * img.Height * 4;
        EvictThumbnailsByBudget();
        return true;
    }

    private void RemoveThumb(GalleryKey key)
    {
        if (_thumbMem.TryGetValue(key, out var im))
        {
            _thumbMem.Remove(key);
            _thumbOrder.Remove(key);
            _thumbMemBytes -= (long)im.Width * im.Height * 4;
            im.Dispose();
        }
    }

    /// <summary>字节预算 LRU：FIFO 头开始淘汰（跳过当前可见区），直到预算内（§13）。</summary>
    private void EvictThumbnailsByBudget()
    {
        if (_thumbOrder.Count == 0 || _thumbMemBytes <= ThumbMemBudgetBytes) return;
        var (pf, pl) = VisibleRange();
        for (int i = 0; i < _thumbOrder.Count && _thumbMemBytes > ThumbMemBudgetBytes; i++)
        {
            var k = _thumbOrder[i];
            if (!_thumbMem.ContainsKey(k)) { _thumbOrder.RemoveAt(i); i--; continue; } // 残留
            if (_indexByKey.TryGetValue(k, out var idx) && idx >= pf && idx <= pl) continue; // 可见不淘汰
            RemoveThumb(k);
            _thumbOrder.RemoveAt(i);
            i--;
        }
    }

    private void ClearThumbMem()
    {
        foreach (var im in _thumbMem.Values) im.Dispose();
        _thumbMem.Clear();
        _thumbOrder.Clear();
        _thumbMemBytes = 0;
    }

    private void UpdateLayout()
    {
        int rows = (int)Math.Ceiling((double)Math.Max(0, _items.Count) / Math.Max(1, _columns));
        var min = new Size(_columns * TileW, rows * TileH);
        if (AutoScrollMinSize != min)
        {
            AutoScrollMinSize = min;
            DiagLog.Info($"grid layout: cols={_columns} thumbPx={_thumbPx} items={_items.Count} minSize={min} client={ClientSize} scroll={AutoScrollPosition}");
        }
        Invalidate();
    }

    /// <summary>列数/尺寸变化：布局代际前进，显示位图全部作废（磁盘 512 规范缓存保留，本地解码回填快）。</summary>
    private void InvalidateThumbsOnLayoutChange()
    {
        var gen = (long)_columns * 100000 + _thumbPx;
        if (gen == _layoutGen) return;
        _layoutGen = gen;
        ClearThumbMem();
    }

    /// <summary>Ctrl + / Ctrl -：列数 -1 / +1，并让缩略图重新铺满视口。</summary>
    public void ChangeColumns(int delta)
    {
        _columns = Math.Clamp(_columns + delta, 1, 8);
        int w = (Math.Max(TileW, ClientSize.Width) - Gap * (_columns + 1)) / Math.Max(1, _columns);
        _thumbPx = Math.Clamp(w, 96, 640);
        InvalidateThumbsOnLayoutChange();
        UpdateLayout();
    }

    /// <summary>Ctrl + 滚轮：只改缩略图尺寸；放大溢出时被动减列，缩小绝不自动加列。</summary>
    public void Zoom(int direction)
    {
        _thumbPx = Math.Clamp(_thumbPx + (direction > 0 ? 16 : -16), 96, 640);
        while (_columns > 1 && _columns * TileW > Math.Max(TileW, ClientSize.Width)) _columns--;
        InvalidateThumbsOnLayoutChange();
        UpdateLayout();
    }

    protected override void OnClientSizeChanged(EventArgs e)
    {
        base.OnClientSizeChanged(e);
        while (_columns > 1 && _columns * TileW > Math.Max(TileW, ClientSize.Width)) _columns--;
        InvalidateThumbsOnLayoutChange();
        UpdateLayout();
    }

    // ================= 交互 =================

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

    protected override void OnMouseDown(MouseEventArgs e)
    {
        base.OnMouseDown(e);
        if (e.Button != MouseButtons.Left) return;
        int idx = IndexAt(e.Location);
        if (idx < 0) return;
        if (ModifierKeys.HasFlag(Keys.Control))
        {
            if (!_selected.Add(idx)) _selected.Remove(idx); // toggle（Ctrl 点按不进拖拽状态机）
            Invalidate();
            SelectionChanged?.Invoke();
            return;
        }
        // 进入 Pending：等待"移动=框选"或"不动 2s=选择完并拖拽"
        _press = PressState.Pending;
        _anchorIndex = idx;
        _pressStart = e.Location;
        _longPressAt = Environment.TickCount64;
        _longPressTimer.Start();
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        base.OnMouseMove(e);
        if (e.Button != MouseButtons.Left) return;

        if (_press == PressState.Pending)
        {
            int dx = e.X - _pressStart.X, dy = e.Y - _pressStart.Y;
            if (dx * dx + dy * dy > MarqueeThresholdPx * MarqueeThresholdPx)
            {
                // 移动超过阈值 → 放弃长按，进入框选
                _longPressTimer.Stop();
                _press = PressState.Marquee;
            }
            else
            {
                return; // 抖动容忍内：既不是框选也不重置长按计时
            }
        }
        if (_press != PressState.Marquee) return;

        _marqueeCursor = e.Location;
        UpdateMarqueeSelection(e.Location);
    }

    protected override void OnMouseUp(MouseEventArgs e)
    {
        base.OnMouseUp(e);

        if (e.Button == MouseButtons.Left)
        {
            _longPressTimer.Stop();
            switch (_press)
            {
                case PressState.Pending:
                    // 未移动、不足 2s = 普通点击：只选中锚点
                    _selected.Clear();
                    _selected.Add(_anchorIndex);
                    Invalidate();
                    SelectionChanged?.Invoke();
                    break;
                case PressState.Marquee:
                    Invalidate(); // 选中已实时生效，松手仅收尾
                    SelectionChanged?.Invoke();
                    break;
                case PressState.DragPreparing:
                    // 准备期间松手 = 取消拖拽（Form 侧 BeginSelectionDrag 会因鼠标已松开而返回取消），
                    // 退回普通点击选中
                    _selected.Clear();
                    _selected.Add(_anchorIndex);
                    Invalidate();
                    SelectionChanged?.Invoke();
                    break;
            }
            _press = PressState.None;
            _anchorIndex = -1;
            return;
        }

        if (e.Button == MouseButtons.Right)
        {
            int idx = IndexAt(e.Location);
            if (idx < 0) return;
            if (!_selected.Contains(idx)) { _selected.Clear(); _selected.Add(idx); Invalidate(); }
            ItemRightClicked?.Invoke(idx);
        }
    }

    /// <summary>长按 2s 命中：锚点确保在选中集内，通知 Form 准备本地副本（准备完回调 <see cref="BeginSelectionDrag"/>）。</summary>
    private void EnterDragPreparing()
    {
        _longPressTimer.Stop();
        _press = PressState.DragPreparing;
        if (!_selected.Contains(_anchorIndex))
        {
            _selected.Clear();
            _selected.Add(_anchorIndex);
            Invalidate();
            SelectionChanged?.Invoke();
        }
        DragSelectionRequested?.Invoke(_selected.OrderBy(i => i).ToList());
    }

    /// <summary>
    /// 用真实本地文件路径启动 OLE 拖拽（Form 备妥临时副本后调用，UI 线程）。
    /// 返回 1=拖拽结束且发生投放；0=拖拽被取消；-1=鼠标已松开未进入拖拽（准备期间松手）。
    /// </summary>
    public int BeginSelectionDrag(IReadOnlyList<string> localPaths)
    {
        if (_press != PressState.DragPreparing || (Control.MouseButtons & MouseButtons.Left) == 0)
            return -1;
        var tracker = new DropTrackingDataObject(DataFormats.FileDrop, localPaths.ToArray());
        try
        {
            DoDragDrop(tracker, DragDropEffects.Copy);
        }
        catch { /* 拖拽异常按取消处理 */ }
        return tracker.Dropped ? 1 : 0;
    }

    /// <summary>框选实时重算：锚点 tile 与光标 tile 的矩形内全部 tile（按网格顺序）。仅遍历边界框内行/列，大列表也廉价。</summary>
    private void UpdateMarqueeSelection(Point cursorClient)
    {
        var off = ScrollOffset;
        var anchor = TileContentRect(_anchorIndex);
        int anchorRow = anchor.Top / TileH, anchorCol = anchor.Left / TileW;
        var cur = ClientToContent(cursorClient, off);
        int curRow = Math.Clamp(cur.Y / TileH, 0, (_items.Count - 1) / _columns);
        int curCol = Math.Clamp(cur.X / TileW, 0, _columns - 1);

        _selected.Clear();
        for (int r = Math.Min(anchorRow, curRow); r <= Math.Max(anchorRow, curRow); r++)
            for (int c = Math.Min(anchorCol, curCol); c <= Math.Max(anchorCol, curCol); c++)
            {
                int idx = r * _columns + c;
                if (idx < _items.Count) _selected.Add(idx);
            }
        Invalidate();
        SelectionChanged?.Invoke();
    }

    // ================= 外部文件拖入（资源管理器 → 手机相册目录）=================

    protected override void OnDragEnter(DragEventArgs e)
    {
        base.OnDragEnter(e);
        e.Effect = e.Data?.GetDataPresent(DataFormats.FileDrop) == true ? DragDropEffects.Copy : DragDropEffects.None;
    }

    protected override void OnDragOver(DragEventArgs e)
    {
        base.OnDragOver(e);
        e.Effect = e.Data?.GetDataPresent(DataFormats.FileDrop) == true ? DragDropEffects.Copy : DragDropEffects.None;
    }

    protected override void OnDragDrop(DragEventArgs e)
    {
        base.OnDragDrop(e);
        var data = e.Data;
        if (data?.GetDataPresent(DataFormats.FileDrop) != true) return;
        var files = (string[])data.GetData(DataFormats.FileDrop);
        if (files.Length == 0) return;
        int idx = IndexAt(PointToClient(Control.MousePosition)); // drop 瞬间鼠标即位于投放点（屏幕坐标）
        FilesDroppedToGrid?.Invoke(files, idx);
    }

    /// <summary>继承 DataObject：记录是否发生过"按 FileDrop 取数"（=投放到接受文件的接收方，用于结束后提示）。</summary>
    private sealed class DropTrackingDataObject : DataObject
    {
        public DropTrackingDataObject(string format, object data) : base(format, data) { }
        public bool Dropped { get; private set; }
        public override object? GetData(string format)
        {
            if (string.Equals(format, DataFormats.FileDrop, StringComparison.OrdinalIgnoreCase)) Dropped = true;
            return base.GetData(format);
        }
        public override object? GetData(string format, bool translation)
        {
            if (string.Equals(format, DataFormats.FileDrop, StringComparison.OrdinalIgnoreCase)) Dropped = true;
            return base.GetData(format, translation);
        }
    }

    protected override void OnMouseDoubleClick(MouseEventArgs e)
    {
        base.OnMouseDoubleClick(e);
        int idx = IndexAt(e.Location);
        if (idx < 0) return;
        if (!_selected.Contains(idx)) { _selected.Clear(); _selected.Add(idx); Invalidate(); }
        // 命中诊断（§18）：双击时记录 客户区坐标/偏移/命中 index/key，与画面叠加层对照
        DiagLog.Info($"hit: client={e.Location} off={ScrollOffset} index={idx} key={_items[idx].Key}");
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

    // ================= 滚动 / 绘制 =================

    /// <summary>
    /// 热路径（§8：p95 < 1ms）——禁止 prune/Dispose/I/O/同步日志。
    /// 只做：请求预取 + 全量失效（最终位置完整重画）+ 重启静止定时器。
    /// </summary>
    protected override void OnScroll(ScrollEventArgs se)
    {
        base.OnScroll(se);
        RequestThumbnails?.Invoke();
        Invalidate();
        _idleTimer.Stop();
        _idleTimer.Start();
        var now = Environment.TickCount64;
        if (now - _lastScrollLog > 500)
        {
            _lastScrollLog = now;
            DiagLog.Info($"scroll: pos={AutoScrollPosition} off={ScrollOffset} min={AutoScrollMinSize} client={ClientSize}");
        }
    }

    private void EnsureGdiResources()
    {
        if (_nameBrush != null) return;
        _nameBrush = new SolidBrush(Color.White);
        _nameBg = new SolidBrush(Color.FromArgb(170, 0, 0, 0));
        _selPen = new Pen(Color.FromArgb(0, 120, 215), 3f);
        _dimBrush = new SolidBrush(Color.FromArgb(90, 0, 0, 0));
        _playBrush = new SolidBrush(Color.FromArgb(220, 255, 255, 255));
        _phBrush = new SolidBrush(Color.FromArgb(54, 54, 60));
        _phPen = new Pen(Color.FromArgb(92, 92, 100)); // 占位格边界：缩略图未到位也能立刻看到格子结构
        _marqueeFill = new SolidBrush(Color.FromArgb(50, 0, 120, 215));
        _marqueePen = new Pen(Color.FromArgb(0, 120, 215), 1.5f) { DashStyle = DashStyle.Dash };
        _nameFont = new Font("Segoe UI", 8f);
        _dbgFont = new Font("Consolas", 7.5f);
        _nameSf = new StringFormat
        {
            Trimming = StringTrimming.EllipsisCharacter,
            Alignment = StringAlignment.Near,
            LineAlignment = StringAlignment.Center
        };
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(BackColor);
        if (_items.Count == 0) return;

        // 一次 snapshot（§4：Paint 开头只读一次滚动状态；client = content - off，不做 transform）
        var off = ScrollOffset;
        var (first, last) = GetPaintRange(off);

        EnsureGdiResources();
        var nameBrush = _nameBrush!;
        var nameBg = _nameBg!;
        var selPen = _selPen!;
        var dimBrush = _dimBrush!;
        var playBrush = _playBrush!;
        var phBrush = _phBrush!;
        var phPen = _phPen!;
        var nameFont = _nameFont!;
        var sf = _nameSf!;

        // 帧诊断（节流 500ms，§18 不变量：首可见 tile 的 client.Y 应落在 0..TileH）
        var now = Environment.TickCount64;
        if (now - _lastPaintLog > 500)
        {
            _lastPaintLog = now;
            var fc = first >= 0 ? ContentToClient(TileContentRect(first), off) : Rectangle.Empty;
            DiagLog.Info($"paint: rawAuto={AutoScrollPosition} off={off} range=[{first},{last}] firstClient={fc} clip={e.ClipRectangle} items={_items.Count} thumbs={_thumbMem.Count} client={ClientSize}");
        }

        for (int index = first; index <= last && index < _items.Count; index++)
        {
            var r = ContentToClient(TileContentRect(index), off);
            if (!r.IntersectsWith(e.ClipRectangle)) continue; // 只画与 clip 相交的 tile（§4.5）

            var item = _items[index];

            if (_thumbMem.TryGetValue(item.Key, out var img))
            {
                // 底衬铺满（含留白区），图按实际尺寸居中（显示位图已后台缩到 thumbPx 内）
                g.FillRectangle(phBrush, r);
                if (img.Width <= r.Width && img.Height <= r.Height)
                    g.DrawImageUnscaled(img, r.X + (r.Width - img.Width) / 2, r.Y + (r.Height - img.Height) / 2);
                else
                {
                    // 尺寸不匹配（如 zoom 代际切换瞬间）：等比缩放进 tile
                    float s = Math.Min((float)r.Width / img.Width, (float)r.Height / img.Height);
                    int dw = Math.Max(1, (int)(img.Width * s)), dh = Math.Max(1, (int)(img.Height * s));
                    g.DrawImage(img, r.X + (r.Width - dw) / 2, r.Y + (r.Height - dh) / 2, dw, dh);
                }
            }
            else
            {
                g.FillRectangle(phBrush, r);
                g.DrawRectangle(phPen, r); // 未加载也立刻显示格子边界
            }

            if (item.Kind == MediaKind.Video)
            {
                g.FillRectangle(dimBrush, r);
                int cx = r.X + r.Width / 2, cy = r.Y + r.Height / 2;
                g.FillPolygon(playBrush, new[]
                {
                    new Point(cx - 10, cy - 14), new Point(cx + 15, cy), new Point(cx - 10, cy + 14)
                });
            }

            if (ShowNames)
            {
                int barH = 20;
                var bar = new Rectangle(r.X, r.Bottom - barH, r.Width, barH);
                g.FillRectangle(nameBg, bar);
                g.DrawString(item.DisplayName, nameFont, nameBrush,
                    new RectangleF(bar.X + 3, bar.Y + 1, bar.Width - 6, barH - 2), sf);
            }

            // 调试叠加层（§18）：验证"画面 ID == 双击 ID"，同图两次时按 ID 判定 cache 错配 / 列表重复
            if (DiagLog.ShowGridOverlay)
            {
                var dbg = $"#{index} {item.Key}";
                var sz = g.MeasureString(dbg, _dbgFont!);
                g.FillRectangle(nameBg, new Rectangle(r.X + 1, r.Y + 1, (int)sz.Width + 4, (int)sz.Height + 2));
                g.DrawString(dbg, _dbgFont!, nameBrush, r.X + 3, r.Y + 2);
            }

            if (_selected.Contains(index))
                g.DrawRectangle(selPen, r);
        }

        // 框选虚线框：锚点 tile 与光标位置的并集矩形（client 坐标）
        if (_press == PressState.Marquee && _anchorIndex >= 0)
        {
            var r0 = ContentToClient(TileContentRect(_anchorIndex), off);
            var rect = Rectangle.Union(r0, new Rectangle(_marqueeCursor, Size.Empty));
            g.FillRectangle(_marqueeFill!, rect);
            g.DrawRectangle(_marqueePen!, rect);
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _idleTimer.Stop();
            _idleTimer.Dispose();
            _longPressTimer.Stop();
            _longPressTimer.Dispose();
            ClearThumbMem();
            _nameBrush?.Dispose(); _nameBg?.Dispose(); _selPen?.Dispose();
            _dimBrush?.Dispose(); _playBrush?.Dispose(); _phBrush?.Dispose();
            _phPen?.Dispose(); _marqueeFill?.Dispose(); _marqueePen?.Dispose();
            _nameFont?.Dispose(); _dbgFont?.Dispose(); _nameSf?.Dispose();
        }
        base.Dispose(disposing);
    }
}
