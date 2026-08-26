namespace AdbManager;

/// <summary>
/// 每设备 IO 限流。避免同时起过多 adb 进程——TCP 设备尤其怕并发（队头阻塞、CPU/IO 抖动），
/// 相册与磁盘挂载共享这一层，保证 Explorer/网格不会积压几十个 adb 回调。
/// </summary>
public sealed class DeviceIoScheduler
{
    /// <summary>元数据 shell（ls / content query）并发上限。</summary>
    public SemaphoreSlim Metadata { get; } = new(2, 2);

    /// <summary>重型文件传输（原图 pull / push / content read）并发上限：USB 2 / TCP 1。</summary>
    public SemaphoreSlim Transfer { get; }

    /// <summary>小缩略图批次（多源 adb pull）并发上限：USB 2 / TCP 1。</summary>
    public SemaphoreSlim ThumbBatch { get; }

    public DeviceIoScheduler(bool isUsb = false)
    {
        Transfer = new SemaphoreSlim(isUsb ? 2 : 1, isUsb ? 2 : 1);
        ThumbBatch = new SemaphoreSlim(isUsb ? 2 : 1, isUsb ? 2 : 1);
    }
}
