namespace AdbManager;

/// <summary>媒体类型。</summary>
public enum MediaKind
{
    Image,
    Video
}

/// <summary>
/// 相册中的一项（来自 Android MediaStore）。以 content URI 为主键，不依赖 <c>_data</c> 原始路径读取；
/// 仅在需要删除/移动等文件系统操作时，用 <see cref="RawPath"/> 做 best-effort 兜底。
/// </summary>
public sealed class GalleryItem
{
    public MediaKind Kind;
    public long Id;
    public string DisplayName = "";
    public string MimeType = "";
    public long Size;
    public int? Width;
    public int? Height;
    public long? DurationMs;
    public long? DateTakenMs;
    public long DateAddedSec;
    public long DateModifiedSec;
    public string? BucketId;
    public string? BucketDisplayName;
    public string? RelativePath;

    /// <summary>MediaStore <c>_data</c> 原始文件系统路径（首选读取入口；可能为空，需走 provider fallback）。</summary>
    public string DataPath = "";

    /// <summary>MediaStore content URI（跨 scoped storage 的稳健读取入口）。</summary>
    public string ContentUri => Kind == MediaKind.Image
        ? $"content://media/external/images/media/{Id}"
        : $"content://media/external/video/media/{Id}";

    /// <summary>
    /// best-effort 原始文件系统路径（删除/移动时用）：优先 MediaStore 给出的 <c>_data</c>（权威值）；
    /// 为空才用 relative_path+display_name 拼（仅常见主存储布局，可能不准）。
    /// </summary>
    public string RawPath => DataPath.Length > 0
        ? DataPath
        : "/storage/emulated/0/" + (RelativePath ?? "") + DisplayName;

    /// <summary>排序键：优先拍摄时间，其次修改时间（均为毫秒）。</summary>
    public long SortKey => (DateTakenMs is > 0 ? DateTakenMs.Value : (long)DateModifiedSec * 1000L);

    /// <summary>稳定的逻辑图册 key（跨 image/video 合并同名）。</summary>
    public string AlbumKey => $"{(Kind == MediaKind.Image ? "I" : "V")}|{BucketId}|{RelativePath}";

    /// <summary>展示用图册名。</summary>
    public string AlbumName
    {
        get
        {
            if (!string.IsNullOrWhiteSpace(BucketDisplayName)) return BucketDisplayName;
            var rp = RelativePath ?? "";
            rp = rp.TrimEnd('/');
            var last = rp.Contains('/') ? rp[(rp.LastIndexOf('/') + 1)..] : rp;
            return string.IsNullOrWhiteSpace(last) ? "未分组" : last;
        }
    }
}

/// <summary>
/// 解析 <c>adb shell content query</c> 的单行输出。
/// 行形如：<c>Row: 0 _id=1, _display_name=a,b.jpg, mime_type=image/jpeg, ...</c>
/// 因为 <c>_display_name</c> 可含逗号/等号，绝不能用 <c>Split(", ")</c>，
/// 而是按"下一列名="作为边界切分（固定 projection 顺序已知）。
/// </summary>
public static class GalleryRowParser
{
    // 注意：_data 放最后一列 —— 原图文件名可含空格/逗号/等号，放最后就不用担心它污染"下一列名="切分边界
    private static readonly string[] ImageCols =
    {
        "_id", "_display_name", "mime_type", "_size", "width", "height",
        "date_added", "date_modified", "datetaken", "bucket_id", "bucket_display_name", "relative_path",
        "_data"
    };

    private static readonly string[] VideoCols =
    {
        "_id", "_display_name", "mime_type", "_size", "width", "height", "duration",
        "date_added", "date_modified", "datetaken", "bucket_id", "bucket_display_name", "relative_path",
        "_data"
    };

    public static string ImageProjection => string.Join(":", ImageCols);
    public static string VideoProjection => string.Join(":", VideoCols);

    public const string ImagesUri = "content://media/external/images/media";
    public const string VideosUri = "content://media/external/video/media";

    public static GalleryItem? TryParse(string line, MediaKind kind)
    {
        if (string.IsNullOrWhiteSpace(line)) return null;
        line = line.Trim();
        const string prefix = "Row:";
        if (!line.StartsWith(prefix, StringComparison.Ordinal)) return null;

        int sp = line.IndexOf(' ', prefix.Length);
        if (sp < 0) return null;
        string body = line[(sp + 1)..].TrimStart();
        if (body.Length == 0) return null;

        string[] cols = kind == MediaKind.Image ? ImageCols : VideoCols;
        var map = new Dictionary<string, string>(cols.Length, StringComparer.Ordinal);
        int offset = 0;
        for (int i = 0; i < cols.Length; i++)
        {
            string marker = cols[i] + "=";
            int m = body.IndexOf(marker, offset, StringComparison.Ordinal);
            if (m < 0) return null; // 该列缺失 → 认为行不完整
            int vStart = m + marker.Length;
            int vEnd = (i < cols.Length - 1)
                ? body.IndexOf(", " + cols[i + 1] + "=", vStart, StringComparison.Ordinal)
                : body.Length;
            if (vEnd < 0) vEnd = body.Length;
            var val = body.Substring(vStart, vEnd - vStart);
            if (i == cols.Length - 1) val = val.TrimEnd(); // 末列（_data）去掉行尾 \r 等
            map[cols[i]] = val;
            offset = vStart;
        }

        var item = new GalleryItem { Kind = kind };
        item.Id = GetLong(map, "_id");
        if (item.Id <= 0) return null;
        item.DisplayName = GetString(map, "_display_name");
        item.MimeType = GetString(map, "mime_type");
        item.Size = GetLong(map, "_size");
        item.Width = GetInt(map, "width");
        item.Height = GetInt(map, "height");
        item.DurationMs = GetLongOrNull(map, "duration");
        item.DateTakenMs = GetLongOrNull(map, "datetaken");
        item.DateAddedSec = GetLong(map, "date_added");
        item.DateModifiedSec = GetLong(map, "date_modified");
        item.BucketId = GetString(map, "bucket_id");
        item.BucketDisplayName = GetString(map, "bucket_display_name");
        item.RelativePath = GetString(map, "relative_path");
        item.DataPath = GetString(map, "_data");

        return string.IsNullOrEmpty(item.DisplayName) ? null : item;
    }

    private static string GetString(Dictionary<string, string> m, string k)
        => m.TryGetValue(k, out var v) ? (v == "null" ? "" : v) : "";

    private static long GetLong(Dictionary<string, string> m, string k)
        => m.TryGetValue(k, out var v) && long.TryParse(v, out var l) ? l : 0;

    private static int? GetInt(Dictionary<string, string> m, string k)
        => m.TryGetValue(k, out var v) && int.TryParse(v, out var i) ? i : null;

    private static long? GetLongOrNull(Dictionary<string, string> m, string k)
        => m.TryGetValue(k, out var v) && long.TryParse(v, out var l) ? l : null;
}
