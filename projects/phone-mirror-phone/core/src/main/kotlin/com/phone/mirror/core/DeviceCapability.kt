package com.phone.mirror.core

/**
 * ADB 设备能力枚举。
 *
 * 这些能力位来自 ADB 协议的 features 字符串，以及 scrcpy 探测得到的能力。
 */
enum class DeviceCapability(val flag: Int) {
    /** ADB shell v2 协议 (multi-line stdout + exit code) */
    ADB_SHELL_V2(1 shl 0),
    /** scrcpy 视频流支持 */
    SCRCPY_VIDEO(1 shl 1),
    /** scrcpy 仅音频（未启用视频） */
    SCRCPY_AUDIO_ONLY(1 shl 2),
    /** 剪贴板双向同步 */
    CLIPBOARD(1 shl 3),
    /** 通过 USB 传输 */
    USB_TRANSPORT(1 shl 4),
    /** TLS over ADB (Wireless Debugging 配对) */
    TLS_WIRELESS(1 shl 5),
    /** akasha RPC 扩展 */
    AKASHA_RPC(1 shl 6),
    /** scrcpy 控制流支持注入按键 */
    CONTROL_INJECT(1 shl 7),
    /** 陀螺仪/传感器转发 */
    SENSORS(1 shl 8),
    /** 虚拟显示 */
    VIRTUAL_DISPLAY(1 shl 9),
}

/** 将一组 [DeviceCapability] 折叠为一个 flag 整型 */
fun Collection<DeviceCapability>.toFlags(): Int =
    this.fold(0) { acc, cap -> acc or cap.flag }

/** 从 flags 整型反向提取能力集合 */
fun Int.toCapabilities(): Set<DeviceCapability> =
    DeviceCapability.values().filterTo(linkedSetOf()) { it.flag and this != 0 }
