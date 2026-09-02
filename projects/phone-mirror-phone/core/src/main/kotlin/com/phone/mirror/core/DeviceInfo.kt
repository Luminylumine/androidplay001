package com.phone.mirror.core

/**
 * ADB 目标设备的基本信息。完全 1:1 移植自 Windows C# DeviceInfo。
 */
data class DeviceInfo(
    /** ADB 序列号；USB 下形如 `0123456789ABCDEF`，TCP 下形如 `192.168.1.23:37921` */
    val id: String,
    /** 厂商 + 型号，例 `"Google Pixel 7 Pro"` */
    val model: String,
    /** TCP/WiFi 下的 IP 地址；USB 下为 null */
    val ipAddress: String? = null,
    /** TCP/WiFi 下的端口；USB 下为 0 */
    val port: Int = 0,
    /** true 表示通过 USB OTG 连接 */
    val isUsb: Boolean = false,
    /** 设备支持的 ADB 能力集 */
    val capabilities: Set<DeviceCapability> = emptySet(),
) {
    /** 是否支持 scrcpy 视频流 */
    val supportsScrcpy: Boolean get() = DeviceCapability.SCRCPY_VIDEO in capabilities

    /** 是否支持 shell v2 协议 */
    val supportsShellV2: Boolean get() = DeviceCapability.ADB_SHELL_V2 in capabilities

    /** 是否支持 TLS wireless (Wireless Debugging) */
    val supportsTlsWireless: Boolean get() = DeviceCapability.TLS_WIRELESS in capabilities
}
