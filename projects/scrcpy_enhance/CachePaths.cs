using System.Security.Cryptography;
using System.Text;

namespace AdbManager;

/// <summary>
/// 本机缓存目录规划。全部放在用户目录，不进仓库。
/// 相册与磁盘挂载共用 <c>deviceHash</c> 做设备隔离。
/// </summary>
public static class CachePaths
{
    /// <summary>缓存根目录：%LocalAppData%\AdbManager\Cache</summary>
    public static string Root => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "AdbManager", "Cache");

    /// <summary>设备隔离哈希（SHA256 前 16 位 hex），避免把 <c>192.168.x.x:5555</c> 直接当目录名。</summary>
    public static string DeviceHash(string deviceId)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(deviceId));
        return Convert.ToHexString(bytes)[..16].ToLowerInvariant();
    }

    // ---- 相册缓存 ----
    public static string GalleryDir(string deviceId) => Path.Combine(Root, "gallery", DeviceHash(deviceId));
    public static string ThumbsDir(string deviceId, bool video) =>
        Path.Combine(GalleryDir(deviceId), "thumbs", video ? "video" : "image");
    public static string OriginalsDir(string deviceId) => Path.Combine(GalleryDir(deviceId), "originals");
    public static string TransientDir(string deviceId) => Path.Combine(GalleryDir(deviceId), "transient");

    // ---- 磁盘挂载缓存（Phase 3/4 用）----
    public static string MountReadDir(string deviceId) => Path.Combine(Root, "mount", DeviceHash(deviceId), "read");
    public static string MountStagingDir(string deviceId) => Path.Combine(Root, "mount", DeviceHash(deviceId), "staging");
    public static string MountRecoveryDir(string deviceId) => Path.Combine(Root, "mount", DeviceHash(deviceId), "recovery");

    public static void Ensure(string dir) => Directory.CreateDirectory(dir);
}
