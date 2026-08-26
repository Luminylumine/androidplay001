using System.IO;
using System.Text;

namespace AdbManager;

/// <summary>
/// 相册读取层（跨 Android 10/12/16，纯 adb / 无 root / 无 APK）：
/// - 打开/下载/复制：<c>adb pull <_data> → .partial → 强校验 → 原子改名</c>；
/// - 缩略图：<c>exec-out cat <_data> → 内存 byte[]</c>（不落盘，同样强校验）；
/// - <c>content read</c> 仅作按设备探测的 provider fallback（content CLI 会吞异常且 exit 可能为 0，
///   其 stdout 可能是 Java 异常文本；同设备一次失败即负缓存，本 session 不再重试）。
/// 校验真值：_size 相等 + 图片魔数签名 + （缩略图）Bitmap 可解码。绝不信 exec-out 的 exit code。
/// </summary>
public sealed class GalleryCache
{
    private readonly string _deviceId;

    public GalleryCache(string deviceId) => _deviceId = deviceId;

    /// <summary>临时文件根目录（系统 TEMP，非 LocalAppData）。</summary>
    public static string TempRoot => Path.Combine(Path.GetTempPath(), "AdbManager");

    // ---- 打开/下载/复制：落盘唯一通道 ----

    /// <summary>
    /// 读取到 TEMP 下唯一临时文件（GUID 命名，永不冲突 → 同一张图可反复打开）。
    /// <paramref name="formatCorrect"/> 为 true 时按内容魔数修正扩展名（用于"打开"，
    /// 手机里常见"显示名 .png 实际是 JPEG/HEIF"）。
    /// </summary>
    public async Task<string> ReadToTempFileAsync(GalleryItem item, bool formatCorrect, DeviceIoScheduler sched, CancellationToken ct = default)
    {
        Directory.CreateDirectory(TempRoot);
        await sched.Transfer.WaitAsync(ct);
        try
        {
            string partial = Path.Combine(TempRoot, Guid.NewGuid().ToString("N") + ".partial");
            var diags = new StringBuilder();
            Exception? pullError = null;
            bool finalized = false;

            // 1) 首选：_data 原始路径 + adb pull（SYNC 协议，无 shell 解析、无 stderr 污染）
            if (item.DataPath.Length > 0)
            {
                try
                {
                    await AdbHelper.PullFileAsync(_deviceId, item.DataPath, partial, 300000, ct, diags);
                    if (ValidateReadResult(item, partial, item.Size, isImage: item.Kind == MediaKind.Image))
                    {
                        var result = FinalizePartial(partial, item, formatCorrect);
                        finalized = true; // 已改名为最终文件，finally 不得再删 partial 路径
                        return result;
                    }
                }
                catch (Exception ex)
                {
                    pullError = ex;
                }
            }
            if (!finalized) TryDelete(partial);

            // 2) fallback：content read（仅该设备未被负缓存时）
            if (!AdbHelper.IsContentReadBroken(_deviceId))
            {
                try
                {
                    await AdbHelper.ReadContentUriToStreamAsync(_deviceId, item.ContentUri,
                        File.Create(partial), 120000, ct);
                    if (ValidateReadResult(item, partial, item.Size, isImage: item.Kind == MediaKind.Image))
                    {
                        var result = FinalizePartial(partial, item, formatCorrect);
                        finalized = true;
                        return result;
                    }
                    AdbHelper.MarkContentReadBroken(_deviceId); // 校验失败 → 大概率是异常文本
                }
                catch (Exception ex)
                {
                    pullError = ex;
                    AdbHelper.MarkContentReadBroken(_deviceId);
                }
            }
            if (!finalized) TryDelete(partial);

            throw new IOException(BuildUnavailableMessage(item, pullError, diags));
        }
        finally
        {
            sched.Transfer.Release();
        }
    }

    // ---- 校验（真值层级：_size 相等 → 魔数签名 → 错误文本仅作诊断） ----

    private static bool ValidateReadResult(GalleryItem item, string path, long expectedSize, bool isImage)
    {
        if (!File.Exists(path) || new FileInfo(path).Length == 0) return false;

        if (isImage)
        {
            // 魔数合法即放行。不做尾部 EOI/IEND 校验：
            // ① adb pull 是完整 SYNC 传输（成功即完整，失败即报错），不存在"半截文件"；
            // ② 实测华为 BURST JPEG 在 FFD9 之后还追加 ~1.2KB 尾部数据，尾部校验会误杀合法照片。
            return DetectExtByContent(path) != null;
        }

        // 视频等二进制：_size 相等为强一致条件
        return expectedSize <= 0 || new FileInfo(path).Length == expectedSize;
    }

    /// <summary>
    /// 校验通过后原子提交：.partial → 最终文件；<paramref name="formatCorrect"/> 时按魔数改真实扩展名。
    /// </summary>
    private static string FinalizePartial(string partial, GalleryItem item, bool formatCorrect)
    {
        string final = partial;
        if (formatCorrect)
        {
            var detected = DetectExtByContent(partial);
            if (detected != null) final = partial + detected;
            else final = partial + (Path.GetExtension(item.DisplayName) ?? ".bin");
        }
        if (final != partial)
        {
            if (File.Exists(final)) File.Delete(final);
            File.Move(partial, final);
        }
        return final;
    }

    private static string BuildUnavailableMessage(GalleryItem item, Exception? error, StringBuilder? diags)
    {
        var reason = "原始文件路径不可访问";
        if (item.DataPath.Length == 0) reason = "媒体记录缺少原始路径(_data)";
        if (error != null) reason += $"（{error.Message}）";
        if (diags != null && diags.Length > 0 && diags.Length <= 200) reason += $" [adb: {diags}]";
        return $"无法从设备读取此照片：{reason}";
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    // ---- 魔数嗅探（图片容器签名） ----

    public static bool LooksLikeImageBytes(byte[] b)
    {
        int n = b.Length;
        if (n >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF) return true;                      // JPEG
        if (n >= 8 && b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47 && b[4] == 0x0D &&
            b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return true;                                 // PNG
        if (n >= 4 && b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38) return true;       // GIF
        if (n >= 2 && b[0] == 0x42 && b[1] == 0x4D) return true;                                       // BMP
        if (n >= 4 && ((b[0] == 0x49 && b[1] == 0x49 && b[2] == 0x2A) || (b[0] == 0x4D && b[1] == 0x4D && b[3] == 0x2A))) return true; // TIFF
        if (n >= 12 && b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 &&
            b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) return true;               // WebP
        if (n >= 12 && b[4] == 0x66 && b[5] == 0x74 && b[6] == 0x79 && b[7] == 0x70)
        {
            var brand = Encoding.ASCII.GetString(b, 8, 4);                                             // ISO-BMFF
            return brand.StartsWith("heic") || brand.StartsWith("heix") || brand == "mif1" ||
                   brand.StartsWith("hevc") || brand == "msf1" || brand.StartsWith("avif") || brand.StartsWith("avis"); // HEIF/HEIC/AVIF
        }
        return false;
    }

    /// <summary>
    /// 按<b>文件头魔数</b>探测图片真实格式并返回扩展名；无法识别（或视频/未知）返回 null。
    /// </summary>
    public static string? DetectExtByContent(string path)
    {
        try
        {
            byte[] h;
            using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                h = new byte[Math.Min(16, fs.Length)];
                int read = 0;
                while (read < h.Length)
                {
                    int r = fs.Read(h, read, h.Length - read);
                    if (r <= 0) break;
                    read += r;
                }
                Array.Resize(ref h, read);
            }
            return LooksLikeImageBytes(h) ? ToExt(h) : null;
        }
        catch
        {
            return null;
        }
    }

    private static string? ToExt(byte[] h)
    {
        int n = h.Length;
        if (n >= 3 && h[0] == 0xFF && h[1] == 0xD8 && h[2] == 0xFF) return ".jpg";
        if (n >= 8 && h[0] == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47) return ".png";
        if (n >= 4 && h[0] == 0x47 && h[1] == 0x49 && h[2] == 0x46 && h[3] == 0x38) return ".gif";
        if (n >= 2 && h[0] == 0x42 && h[1] == 0x4D) return ".bmp";
        if (n >= 4 && ((h[0] == 0x49 && h[1] == 0x49 && h[2] == 0x2A) || (h[0] == 0x4D && h[1] == 0x4D && h[3] == 0x2A))) return ".tif";
        if (n >= 12 && h[0] == 0x52 && h[1] == 0x49 && h[2] == 0x46 && h[3] == 0x46) return ".webp";
        if (n >= 12 && h[4] == 0x66 && h[5] == 0x74 && h[6] == 0x79 && h[7] == 0x70)
        {
            var brand = Encoding.ASCII.GetString(h, 8, 4);
            if (brand.StartsWith("avif") || brand.StartsWith("avis")) return ".avif";
            return ".heic";
        }
        return null;
    }

    /// <summary>清理 TEMP 下超过 24 小时的临时文件（打开/复制产生的）。</summary>
    public static void CleanupStaleTempFiles()
    {
        try
        {
            if (!Directory.Exists(TempRoot)) return;
            var cutoff = DateTime.Now.AddDays(-1);
            foreach (var f in Directory.EnumerateFiles(TempRoot))
            {
                try
                {
                    if (File.GetLastWriteTime(f) < cutoff) File.Delete(f);
                }
                catch { }
            }
        }
        catch { }
    }
}
