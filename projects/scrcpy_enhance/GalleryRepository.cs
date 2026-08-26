namespace AdbManager;

/// <summary>
/// 相册数据仓库：一次 MediaStore 查询拿到图片 + 视频元数据，构建图册，供网格使用。
/// 网络操作经 <see cref="DeviceIoScheduler"/> 限流。首版以"展示名"聚合图册（同名不同路径会合并，已知取舍）。
/// </summary>
public sealed class GalleryRepository
{
    public const string AllKey = "ALL";

    private readonly string _deviceId;
    private readonly DeviceIoScheduler _sched;
    private readonly List<GalleryItem> _items = new();

    public GalleryRepository(string deviceId, DeviceIoScheduler sched)
    {
        _deviceId = deviceId;
        _sched = sched;
    }

    public IReadOnlyList<GalleryItem> Items => _items;

    /// <summary>图册列表：[(key, 展示名)]，首项为"全部"。</summary>
    public List<(string Key, string Display)> Albums { get; } = new();

    public bool HasMedia => _items.Count > 0;
    public string? LastError { get; private set; }

    /// <summary>当前列表中最大的 date_added（秒），增量轮询的基线。</summary>
    public long NewestDateAdded { get; private set; }

    /// <summary>查询 image + video 元数据并排序、建图册。返回条目数。失败不抛，写 <see cref="LastError"/>。</summary>
    public async Task<int> LoadAsync(CancellationToken ct = default)
    {
        _items.Clear();
        Albums.Clear();
        LastError = null;

        await _sched.Metadata.WaitAsync(ct);
        string imgOut = "", vidOut = "";
        try
        {
            imgOut = await AdbHelper.ShellCommandAsync(_deviceId,
                new[] { "content", "query", "--uri", GalleryRowParser.ImagesUri, "--projection", GalleryRowParser.ImageProjection },
                30000, ct);
        }
        catch (Exception ex) { LastError = "图片查询失败: " + ex.Message; }

        try
        {
            vidOut = await AdbHelper.ShellCommandAsync(_deviceId,
                new[] { "content", "query", "--uri", GalleryRowParser.VideosUri, "--projection", GalleryRowParser.VideoProjection },
                30000, ct);
        }
        catch (Exception ex) { if (LastError == null) LastError = "视频查询失败: " + ex.Message; }
        finally
        {
            _sched.Metadata.Release();
        }

        ParseBlock(imgOut, MediaKind.Image);
        ParseBlock(vidOut, MediaKind.Video);

        RemoveTombstoned(); // MediaStore stale row 不得把本 session 已删除的项重新冒出来
        _items.Sort((a, b) => b.SortKey.CompareTo(a.SortKey));
        BuildAlbums();
        NewestDateAdded = _items.Count > 0 ? _items.Max(i => i.DateAddedSec) : 0;
        return _items.Count;
    }

    /// <summary>
    /// 增量轮询：只查 date_added >= (最新 - 60s 余量) 的新条目，去重后并入列表。
    /// 返回新增条目数（0 = 无变化，UI 无需动）。删除不在轮询范围（靠手动刷新）。
    /// </summary>
    public async Task<int> PollNewAsync(CancellationToken ct = default)
    {
        long baseline = Math.Max(0, NewestDateAdded - 60);

        await _sched.Metadata.WaitAsync(ct);
        string imgOut = "", vidOut = "";
        try
        {
            var where = AdbHelper.ShellQuote($"date_added >= {baseline}");
            imgOut = await AdbHelper.ShellCommandAsync(_deviceId,
                new[] { "content", "query", "--uri", GalleryRowParser.ImagesUri, "--projection", GalleryRowParser.ImageProjection, "--where", where },
                20000, ct);
            vidOut = await AdbHelper.ShellCommandAsync(_deviceId,
                new[] { "content", "query", "--uri", GalleryRowParser.VideosUri, "--projection", GalleryRowParser.VideoProjection, "--where", where },
                20000, ct);
        }
        catch (Exception)
        {
            return 0; // 轮询失败静默（下次再试；网络抖动不应打断浏览）
        }
        finally
        {
            _sched.Metadata.Release();
        }

        var known = _items.Select(i => (i.Kind, i.Id)).ToHashSet();
        var added = 0;
        foreach (var kind in new[] { MediaKind.Image, MediaKind.Video })
        {
            var outp = kind == MediaKind.Image ? imgOut : vidOut;
            if (string.IsNullOrWhiteSpace(outp)) continue;
            foreach (var raw in outp.Split('\n'))
            {
                var item = GalleryRowParser.TryParse(raw, kind);
                if (item == null) continue;
                if (DeletionTombstones.Contains(item.Kind, item.Id)) continue;
                if (!known.Add((item.Kind, item.Id))) continue;
                _items.Add(item);
                if (item.DateAddedSec > NewestDateAdded) NewestDateAdded = item.DateAddedSec;
                added++;
            }
        }

        if (added > 0)
        {
            _items.Sort((a, b) => b.SortKey.CompareTo(a.SortKey));
            BuildAlbums();
        }
        return added;
    }

    /// <summary>
    /// 增量移除（软件内删除成功后调用，不重跑 content query）：
    /// 从 _items 移除 → 重建图册计数。网格侧由 Form 重新 SetItems（缩略图走会话磁盘缓存快速回填）。
    /// </summary>
    public void RemoveItems(IEnumerable<(MediaKind Kind, long Id)> keys)
    {
        var set = keys.ToHashSet();
        _items.RemoveAll(i => set.Contains((i.Kind, i.Id)));
        BuildAlbums();
    }

    /// <summary>全量查询后过滤掉本 session 已删除的项（墓碑覆盖 stale provider row）。</summary>
    public void RemoveTombstoned()
    {
        _items.RemoveAll(i => DeletionTombstones.Contains(i.Kind, i.Id));
    }

    private void ParseBlock(string output, MediaKind kind)
    {
        if (string.IsNullOrWhiteSpace(output)) return;
        foreach (var raw in output.Split('\n'))
        {
            var item = GalleryRowParser.TryParse(raw, kind);
            if (item != null) _items.Add(item);
        }
    }

    private void BuildAlbums()
    {
        var byName = new SortedDictionary<string, int>(StringComparer.Ordinal);
        foreach (var it in _items)
        {
            byName.TryGetValue(it.AlbumName, out var c);
            byName[it.AlbumName] = c + 1;
        }

        Albums.Add((AllKey, $"全部 ({_items.Count})"));
        foreach (var kv in byName)
            Albums.Add(("name:" + kv.Key, $"{kv.Key} ({kv.Value})"));
    }

    /// <summary>按图册 key 过滤（<see cref="AllKey"/> 返回全部）。始终返回独立副本，调用方持有的列表不会因仓库变更而漂移。</summary>
    public IReadOnlyList<GalleryItem> ItemsInAlbum(string albumKey)
    {
        if (albumKey == AllKey) return _items.ToList();
        var name = albumKey.StartsWith("name:", StringComparison.Ordinal) ? albumKey["name:".Length..] : albumKey;
        return _items.Where(i => i.AlbumName == name).ToList();
    }
}

/// <summary>
/// 本 session 删除墓碑（进程级，退出即失效）：
/// 软件内已确认删除的 (类型,_id)，防止 MediaStore stale row 在刷新时把坏条目重新加回 UI。
/// </summary>
public static class DeletionTombstones
{
    private static readonly HashSet<(MediaKind Kind, long Id)> _set = new();

    public static void Add(MediaKind kind, long id)
    {
        lock (_set) _set.Add((kind, id));
    }

    public static bool Contains(MediaKind kind, long id)
    {
        lock (_set) return _set.Contains((kind, id));
    }
}
