using System.Collections.Concurrent;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Security.Cryptography;
using System.Text.RegularExpressions;

namespace AdbManager;

/// <summary>
/// 相册缩略图流水线（会话级 TEMP 缓存，<b>关闭软件即删</b>）：
///   网格内存 → 会话磁盘(规范 512px JPEG) → 手机端现成小 JPEG(.thumbnails, 多源批量 adb pull)
///            → 原图 pull 兜底(限流) → 后台解码(EXIF 方向修正 + 缩 512 + 重编码)
/// 要点：
/// - 请求 100ms 去抖；同项 in-flight 合并（滚出再滚回不重复起 adb）；
/// - <c>_size</c> 与实盘不符仅作 warning（魔数合法 + 可解码即放行，MediaStore 元数据可能陈旧）；
/// - 拉取失败重试 1 次；HEIC/WebP 等 GDI+ 解不了的格式明确降级为占位，不无限重试；
/// - 解码（全尺寸 + 512）统一过 DecodeSem(2)，UI 线程只接收成品 Bitmap。
/// </summary>
public sealed class ThumbnailService : IAsyncDisposable
{
    private const int CanonicalMaxEdge = 512;   // 规范缩略图最大边
    private const int BatchSize = 12;           // 一次多源 adb pull 的文件数
    private const int DebounceMs = 100;
    private const int CacheSchema = 1;
    private const int RetryDelayMs = 250;

    /// <summary>全尺寸解码限流（8000x6000 解压 ~192MB，必须限制并发峰值）。</summary>
    private static readonly SemaphoreSlim DecodeSem = new(2, 2);
    private static readonly string ThumbsRoot = Path.Combine(Path.GetTempPath(), "AdbManager", "thumbs");

    private readonly DeviceInfo _device;
    private readonly DeviceIoScheduler _sched;
    private readonly string _sessionDir;
    private readonly string _stagingDir;
    private readonly CancellationTokenSource _cts = new();
    private readonly ConcurrentDictionary<string, Task<string>> _inFlight = new();
    private bool _disposed;

    // 手机端 modern 缩略图能力（每 session 探测一次，负缓存）
    private Task? _probeTask;
    private HashSet<long>? _imageThumbIds;
    private HashSet<long>? _videoThumbIds;
    private bool _probeFailed;

    // 去抖
    private readonly object _gate = new();
    private (int First, int Last, IReadOnlyList<GalleryItem> Items, Action<int, Bitmap, long> OnReady)? _pending;
    private Task? _pendingTask;

    public ThumbnailService(DeviceInfo device, DeviceIoScheduler sched)
    {
        _device = device;
        _sched = sched;
        CleanStaleSessions();
        _sessionDir = Path.Combine(ThumbsRoot, Guid.NewGuid().ToString("N"));
        _stagingDir = Path.Combine(_sessionDir, "staging");
        Directory.CreateDirectory(_sessionDir);
    }

    /// <summary>启动时清理确认是残留的旧会话目录（>24h；单实例场景不会误删在用的）。</summary>
    public static void CleanStaleSessions()
    {
        try
        {
            if (!Directory.Exists(ThumbsRoot)) return;
            var cutoff = DateTime.Now.AddHours(-24);
            foreach (var d in Directory.EnumerateDirectories(ThumbsRoot))
            {
                try { if (Directory.GetCreationTime(d) < cutoff) Directory.Delete(d, true); }
                catch { }
            }
        }
        catch { }
    }

    // ================= 公共 API =================

    /// <summary>
    /// 请求生成 [first,last] 的缩略图（100ms 去抖）。
    /// <paramref name="onReady"/> 在**后台线程**回调 (index, bitmap, itemId)，Form 负责回 UI 线程并按 itemId 校验。
    /// </summary>
    public void EnqueueRange(int first, int last, IReadOnlyList<GalleryItem> items, Action<int, Bitmap, long> onReady)
    {
        if (_disposed) return;
        lock (_gate)
        {
            _pending = (first, last, items, onReady);
            if (_pendingTask == null) _pendingTask = RunDebouncedAsync();
        }
    }

    /// <summary>某项缓存文件是否已就绪（增量删除后快速回填用）。</summary>
    public string? GetCachedPath(GalleryItem item)
    {
        var p = Path.Combine(_sessionDir, CacheKey(item) + ".jpg");
        return File.Exists(p) ? p : null;
    }

    /// <summary>解码已缓存的规范 JPEG（限流，返回的 Bitmap 由调用方持有）。</summary>
    public Task<Bitmap> DecodeCachedAsync(string cachedPath, CancellationToken ct = default)
        => DecodeSmallJpegAsync(File.ReadAllBytes(cachedPath), ct);

    /// <summary>删除项的会话缓存文件（增量删除时调用）。</summary>
    public void DeleteItemCaches(IEnumerable<GalleryItem> items)
    {
        foreach (var it in items)
            TryDelete(Path.Combine(_sessionDir, CacheKey(it) + ".jpg"));
    }

    /// <summary>退出清理：取消工作、删除本会话目录（用户要求：关软件就删）。</summary>
    public async Task ShutdownAsync()
    {
        if (_disposed) return;
        _disposed = true;
        try { _cts.Cancel(); } catch { }
        await Task.Delay(300); // 给在途工作一点时间自行退出（其产物随目录一起删）
        DeleteDirRetry(_sessionDir);
    }

    public async ValueTask DisposeAsync()
    {
        await ShutdownAsync();
        _cts.Dispose();
    }

    // ================= 去抖 + 范围处理 =================

    private async Task RunDebouncedAsync()
    {
        try { await Task.Delay(DebounceMs, _cts.Token); }
        catch (OperationCanceledException) { return; }

        (int, int, IReadOnlyList<GalleryItem>, Action<int, Bitmap, long>)? work;
        lock (_gate)
        {
            work = _pending;
            _pending = null;
            _pendingTask = null;
        }
        if (work == null || _disposed || _cts.IsCancellationRequested) return;

        DiagLog.Info($"ProcessRange start: range=[{work.Value.Item1},{work.Value.Item2}] itemsCount={work.Value.Item3.Count}");
        try { await ProcessRangeAsync(work.Value, _cts.Token); }
        catch (OperationCanceledException) { }
        catch (Exception ex) { DiagLog.Info($"ProcessRange EXCEPTION: {ex}"); }
    }

    private async Task ProcessRangeAsync((int First, int Last, IReadOnlyList<GalleryItem> Items, Action<int, Bitmap, long> OnReady) work, CancellationToken ct)
    {
        var (first, last, items, onReady) = work;
        if (first > last || items.Count == 0) return;
        first = Math.Max(0, first);
        last = Math.Min(last, items.Count - 1);

        await EnsureThumbProbeAsync();
        DiagLog.Info($"probe: imgThumbs={_imageThumbIds?.Count} vidThumbs={_videoThumbIds?.Count} probeFailed={_probeFailed}");

        var modern = new List<(int Index, GalleryItem Item, string RemotePath)>();
        var fallback = new List<(int Index, GalleryItem Item)>();

        for (int i = first; i <= last; i++)
        {
            var it = items[i];
            if (ct.IsCancellationRequested) break;

            if (GetCachedPath(it) != null)
            {
                _ = DeliverCachedAsync(it, i, onReady, ct);
                continue;
            }
            var key = CacheKey(it);
            if (_inFlight.ContainsKey(key))
            {
                // 在途：等它落盘后再交付（不重复起 adb）
                _ = AwaitInFlightAsync(key, it, i, onReady, ct);
                continue;
            }

            var idset = it.Kind == MediaKind.Image ? _imageThumbIds : _videoThumbIds;
            if (idset != null && idset.Contains(it.Id))
            {
                modern.Add((i, it, it.Kind == MediaKind.Image
                    ? $"/storage/emulated/0/Pictures/.thumbnails/{it.Id}.jpg"
                    : $"/storage/emulated/0/Movies/.thumbnails/{it.Id}.jpg"));
            }
            else if (it.Kind == MediaKind.Image)
            {
                fallback.Add((i, it)); // 视频无小图 → 保持占位，不拉整段视频
            }
        }

        // 1) 手机端小图：8-16 张一个 batch，一次 adb 进程
        foreach (var batch in Chunk(modern, BatchSize))
        {
            if (ct.IsCancellationRequested) break;
            await _sched.ThumbBatch.WaitAsync(ct);
            var misses = new List<(int Index, GalleryItem Item, string RemotePath)>();
            try
            {
                var batchDir = Path.Combine(_stagingDir, Guid.NewGuid().ToString("N"));
                try
                {
                    var ok = await AdbHelper.PullFilesAsync(_device.Id, batch.Select(b => b.RemotePath).ToArray(), batchDir, 60000, ct);
                    foreach (var (idx, item, _) in batch)
                    {
                        var f = Path.Combine(batchDir, item.Id + ".jpg");
                        byte[] bytes = Array.Empty<byte>();
                        if (File.Exists(f))
                        {
                            try { bytes = File.ReadAllBytes(f); } catch { }
                        }
                        if (bytes.Length > 0 && GalleryCache.LooksLikeImageBytes(bytes))
                        {
                            DiagLog.Info($"batch hit: id={item.Id} size={bytes.Length}");
                            _ = DeliverPrefetchedAsync(item, idx, bytes, onReady, ct);
                        }
                        else
                        {
                            DiagLog.Info($"batch miss: id={item.Id} exists={File.Exists(f)} size={bytes.Length} exit={ok}");
                            misses.Add((idx, item, $"/storage/emulated/0/{(item.Kind == MediaKind.Image ? "Pictures" : "Movies")}/.thumbnails/{item.Id}.jpg"));
                        }
                    }
                }
                finally { DeleteDirRetry(batchDir); }
            }
            catch (OperationCanceledException) { throw; }
            catch { misses.AddRange(batch); }
            finally { _sched.ThumbBatch.Release(); }

            // 单张 miss → 原图兜底（只收图片；远端小图不存在说明路径规则不适用）
            foreach (var m in misses)
                if (m.Item.Kind == MediaKind.Image && fallback.All(f => f.Item.Id != m.Item.Id))
                    fallback.Add((m.Index, m.Item));
        }

        // 2) 原图兜底：逐个拉（限流 Transfer），拉回后统一规范化
        foreach (var (idx, item) in fallback)
        {
            if (ct.IsCancellationRequested) break;
            _ = DeliverOriginalAsync(item, idx, onReady, ct);
        }
    }

    // ================= 交付路径 =================

    private async Task DeliverCachedAsync(GalleryItem item, int index, Action<int, Bitmap, long> onReady, CancellationToken ct)
    {
        try
        {
            var p = GetCachedPath(item);
            if (p == null) return;
            var bmp = await DecodeCachedAsync(p, ct);
            if (_disposed) { bmp.Dispose(); return; }
            onReady(index, bmp, item.Id);
        }
        catch (Exception ex) { DiagLog.Info($"deliverCached EXC: id={item.Id} idx={index}: {ex.Message}"); }
    }

    private async Task AwaitInFlightAsync(string key, GalleryItem item, int index, Action<int, Bitmap, long> onReady, CancellationToken ct)
    {
        try
        {
            if (_inFlight.TryGetValue(key, out var t)) await t;
            await DeliverCachedAsync(item, index, onReady, ct);
        }
        catch { }
    }

    /// <summary>已拿到字节（现代小图批量）→ 规范化落缓存 → 解码交付。</summary>
    private async Task DeliverPrefetchedAsync(GalleryItem item, int index, byte[] raw, Action<int, Bitmap, long> onReady, CancellationToken ct)
    {
        string? path = null;
        try
        {
            path = await EnsureCanonicalAsync(item, raw, ct);
            if (path == null) return;
            var bmp = await DecodeSmallJpegAsync(File.ReadAllBytes(path), ct);
            if (_disposed) { bmp.Dispose(); return; }
            onReady(index, bmp, item.Id);
            DiagLog.Info($"deliver OK(prefetched): id={item.Id} idx={index}");
        }
        catch (Exception ex) { DiagLog.Info($"deliverPrefetched EXC: id={item.Id} idx={index}: {ex}"); }
    }

    /// <summary>原图兜底：拉取（1 次重试）→ 规范化落缓存 → 解码交付。</summary>
    private async Task DeliverOriginalAsync(GalleryItem item, int index, Action<int, Bitmap, long> onReady, CancellationToken ct)
    {
        string? path = null;
        try
        {
            path = await EnsureCanonicalAsync(item, null, ct);
            if (path == null) return;
            var bmp = await DecodeSmallJpegAsync(File.ReadAllBytes(path), ct);
            if (_disposed) { bmp.Dispose(); return; }
            onReady(index, bmp, item.Id);
            DiagLog.Info($"deliver OK(original): id={item.Id} idx={index}");
        }
        catch (Exception ex) { DiagLog.Info($"deliverOriginal EXC: id={item.Id} idx={index}: {ex}"); }
    }

    // ================= 规范化核心 =================

    /// <summary>
    /// 保证 item 的规范 512 JPEG 已落会话缓存，返回其路径；不可读/不可解码返回 null。
    /// in-flight 合并：同 key 只有一份拉取+规范化任务。
    /// </summary>
    private async Task<string?> EnsureCanonicalAsync(GalleryItem item, byte[]? prefetched, CancellationToken ct)
    {
        string cached = Path.Combine(_sessionDir, CacheKey(item) + ".jpg");
        if (File.Exists(cached)) return cached;

        var key = CacheKey(item);
        if (_inFlight.TryGetValue(key, out var existing))
        {
            try { return await existing; } catch { return null; }
        }

        var task = CreateCanonicalAsync(item, prefetched, ct);
        _inFlight[key] = task;
        try
        {
            return await task;
        }
        catch
        {
            _inFlight.TryRemove(key, out _); // 失败清掉，允许后续重试
            return null;
        }
    }

    private async Task<string> CreateCanonicalAsync(GalleryItem item, byte[]? prefetched, CancellationToken ct)
    {
        string cached = Path.Combine(_sessionDir, CacheKey(item) + ".jpg");

        byte[] raw = prefetched ?? await FetchOriginalBytesAsync(item, ct);
        if (raw == null || raw.Length == 0)
            throw new IOException("媒体文件不可读（不存在或无权限）");

        DiagLog.Info($"canonical start: id={item.Id} raw={raw.Length} dataPath={item.DataPath}");
        // _size 不符仅作 warning：能解码的实际文件优先于可能陈旧的 MediaStore 记录
        byte[] canonical = await CanonicalizeAsync(raw, ct); // HEIC/WebP 等解不了 → 抛 → 占位

        var partial = cached + ".partial";
        await File.WriteAllBytesAsync(partial, canonical, ct);
        File.Move(partial, cached, overwrite: true);
        DiagLog.Info($"canonical done: id={item.Id} out={canonical.Length}");
        return cached;
    }

    /// <summary>拉取原图字节（_data 优先 pull；无 _data 时 content read 且受负缓存约束）。失败重试 1 次。</summary>
    private async Task<byte[]?> FetchOriginalBytesAsync(GalleryItem item, CancellationToken ct)
    {
        await _sched.Transfer.WaitAsync(ct);
        try
        {
            for (int attempt = 0; attempt <= 1; attempt++)
            {
                if (attempt > 0) await Task.Delay(RetryDelayMs, ct);
                string partial = Path.Combine(_sessionDir, Guid.NewGuid().ToString("N") + ".partial");
                try
                {
                    if (item.DataPath.Length > 0)
                    {
                        await AdbHelper.PullFileAsync(_device.Id, item.DataPath, partial, 180000, ct);
                        var bytes = File.ReadAllBytes(partial);
                        if (bytes.Length == 0)
                        {
                            DiagLog.Info($"fetch skip: id={item.Id} attempt={attempt} empty file");
                            continue;
                        }
                        if (!GalleryCache.LooksLikeImageBytes(bytes))
                        {
                            DiagLog.Info($"fetch skip: id={item.Id} attempt={attempt} bad magic size={bytes.Length} head={(bytes.Length >= 4 ? BitConverter.ToString(bytes, 0, 4) : "")}");
                            continue;
                        }
                        return bytes;
                    }
                    if (AdbHelper.IsContentReadBroken(_device.Id))
                    {
                        DiagLog.Info($"fetch skip: id={item.Id} no dataPath and contentRead broken");
                        return null;
                    }
                    using var ms = new MemoryStream();
                    await AdbHelper.ReadContentUriToStreamAsync(_device.Id, item.ContentUri, ms, 120000, ct);
                    if (ms.Length == 0)
                    {
                        DiagLog.Info($"fetch skip: id={item.Id} attempt={attempt} contentRead empty");
                        continue;
                    }
                    var b = ms.ToArray();
                    if (!GalleryCache.LooksLikeImageBytes(b))
                    {
                        DiagLog.Info($"fetch skip: id={item.Id} attempt={attempt} contentRead bad magic size={b.Length}");
                        AdbHelper.MarkContentReadBroken(_device.Id);
                        continue;
                    }
                    return b;
                }
                catch (OperationCanceledException) { throw; }
                catch (Exception ex) { DiagLog.Info($"fetch EXC: id={item.Id} attempt={attempt}: {ex.Message}"); }
                finally { TryDelete(partial); }
            }
            DiagLog.Info($"fetch give up: id={item.Id}");
            return null;
        }
        finally { _sched.Transfer.Release(); }
    }

    /// <summary>EXIF 方向修正 + 缩到最大边 512 + 重编码 JPEG(85)；线程池执行；GDI+ 不支持的格式抛异常。</summary>
    private static async Task<byte[]> CanonicalizeAsync(byte[] raw, CancellationToken ct)
    {
        await DecodeSem.WaitAsync(ct);
        try
        {
            try
            {
                return await Task.Run(() => CanonicalizeCore(raw), ct);
            }
            catch (Exception ex)
            {
                DiagLog.Info($"canonicalize EXCEPTION (raw={raw.Length}, head={(raw.Length >= 4 ? BitConverter.ToString(raw, 0, 4) : "")}): {ex}");
                throw;
            }
        }
        finally { DecodeSem.Release(); }
    }

    private static byte[] CanonicalizeCore(byte[] raw)
    {
        {
            using var ms = new MemoryStream(raw);
            using var src = Image.FromStream(ms);

            int orient = 1;
            if (src.PropertyIdList.Contains(0x0112))
                orient = src.GetPropertyItem(0x0112).Value[0];

            using var fixedImg = new Bitmap(src.Width, src.Height, PixelFormat.Format32bppArgb);
            using (var g0 = Graphics.FromImage(fixedImg))
                g0.DrawImage(src, 0, 0, src.Width, src.Height);
            if (orient is >= 2 and <= 8)
                fixedImg.RotateFlip(orient switch
                {
                    2 => RotateFlipType.RotateNoneFlipX,
                    3 => RotateFlipType.Rotate180FlipNone,
                    4 => RotateFlipType.RotateNoneFlipY,
                    5 => RotateFlipType.Rotate90FlipX,
                    6 => RotateFlipType.Rotate90FlipNone,
                    7 => RotateFlipType.Rotate270FlipX,
                    _ => RotateFlipType.Rotate270FlipNone
                });

            float scale = Math.Min(1f, CanonicalMaxEdge / (float)Math.Max(fixedImg.Width, fixedImg.Height));
            int dw = Math.Max(1, (int)(fixedImg.Width * scale));
            int dh = Math.Max(1, (int)(fixedImg.Height * scale));
            using var dest = new Bitmap(dw, dh, PixelFormat.Format32bppArgb);
            using (var g = Graphics.FromImage(dest))
            {
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                g.CompositingQuality = CompositingQuality.HighQuality;
                g.DrawImage(fixedImg, 0, 0, dw, dh);
            }

            // 注意：.NET 9 下 EncoderParameters(Quality) 会令 Save 抛 ArgumentException("Parameter is not valid")，
            // 故用默认质量（75）。512px 缩略图观感无损。
            using var outMs = new MemoryStream();
            dest.Save(outMs, ImageFormat.Jpeg);
            return outMs.ToArray();
        }
    }

    /// <summary>解码规范 512 JPEG（限流）。结果 Bitmap 所有权移交调用方。</summary>
    private static async Task<Bitmap> DecodeSmallJpegAsync(byte[] bytes, CancellationToken ct)
    {
        await DecodeSem.WaitAsync(ct);
        try
        {
            using var ms = new MemoryStream(bytes);
            using var img = Image.FromStream(ms);
            var bmp = new Bitmap(Math.Max(1, img.Width), Math.Max(1, img.Height), PixelFormat.Format32bppArgb);
            using var g = Graphics.FromImage(bmp);
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.DrawImage(img, 0, 0, bmp.Width, bmp.Height);
            return bmp;
        }
        finally { DecodeSem.Release(); }
    }

    // ================= 手机端 .thumbnails 能力探测 =================

    private async Task EnsureThumbProbeAsync()
    {
        if (_imageThumbIds != null || _probeFailed) return;
        if (_probeTask == null) _probeTask = ProbeAsync();
        await _probeTask;
    }

    private async Task ProbeAsync()
    {
        try
        {
            var outp = await AdbHelper.ShellExecAsync(_device.Id,
                "ls -1 /storage/emulated/0/Pictures/.thumbnails /storage/emulated/0/Movies/.thumbnails 2>&1", 15000);

            var img = new HashSet<long>();
            var vid = new HashSet<long>();
            string? cur = null;
            bool anyError = false;
            foreach (var rawLine in outp.Split('\n'))
            {
                var line = rawLine.Trim();
                if (line.Length == 0) continue;
                if (line.Contains("No such file or directory")) { anyError = true; continue; }
                if (line.EndsWith(':')) { cur = line; continue; }
                var m = Regex.Match(line, @"^(\d+)\.jpg$");
                if (!m.Success) continue;
                long id = long.Parse(m.Groups[1].Value);
                if (cur != null && cur.Contains("/Pictures/")) img.Add(id);
                else vid.Add(id);
            }

            if (img.Count == 0 && vid.Count == 0)
            {
                // 目录不存在/被拒 → 本 session 关闭 modern 快路径
                if (anyError) { _probeFailed = true; return; }
                // 目录存在但为空：也视为暂不可用（避免每批都白跑一次 pull）
                _probeFailed = true;
                return;
            }
            _imageThumbIds = img;
            _videoThumbIds = vid;
            DiagLog.Info($"probe OK: img={img.Count} vid={vid.Count} anyError={anyError} rawHead={outp.Substring(0, Math.Min(120, outp.Length)).Replace('\n', '|')}");
        }
        catch (Exception ex)
        {
            _probeFailed = true;
            DiagLog.Info($"probe EXCEPTION: {ex}");
        }
    }

    // ================= 工具 =================

    /// <summary>
    /// 缓存键：schema | 设备 | 类型 | _id | _size | date_modified。
    /// （size+修改时间足够会话级失效；Android 11+ 的 generation_modified 暂不引入，避免三档 projection 分叉）
    /// </summary>
    public string CacheKey(GalleryItem it)
    {
        var input = $"{CacheSchema}|{_device.Id}|{(it.Kind == MediaKind.Image ? "i" : "v")}|{it.Id}|{it.Size}|{it.DateModifiedSec}";
        return Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(input))).ToLowerInvariant();
    }

    private static IEnumerable<List<T>> Chunk<T>(List<T> source, int size)
    {
        for (int i = 0; i < source.Count; i += size)
            yield return source.GetRange(i, Math.Min(size, source.Count - i));
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    private static void DeleteDirRetry(string dir)
    {
        for (int i = 0; i < 2; i++)
        {
            try
            {
                if (Directory.Exists(dir)) Directory.Delete(dir, true);
                return;
            }
            catch
            {
                if (i == 0) Thread.Sleep(100);
            }
        }
    }
}
