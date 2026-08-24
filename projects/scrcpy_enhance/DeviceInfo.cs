namespace AdbManager;

public class DeviceInfo
{
    public string Id { get; set; } = string.Empty;
    public string Name { get; set; } = "Unknown";
    public bool IsUsb { get; set; }
    public string? IpAddress { get; set; }
    public int? Port { get; set; }

    // 显示名称：USB 设备显示序号后4位，TCP 设备显示 IP，方便区分同型号设备
    public string DisplayName
    {
        get
        {
            if (IsUsb)
            {
                // USB 设备 ID 通常是序列号，取后 4 位区分
                var suffix = Id.Length > 4 ? Id[^4..] : Id;
                return $"{Name} (USB:{suffix})";
            }
            else
            {
                return $"{Name} ({IpAddress}:{Port})";
            }
        }
    }
}
