package com.phone.mirror.mirror.scrcpy.protocol

/**
 * scrcpy 协议常量与控制包类型。
 *
 * scrcpy 走 ADB 的 localabstract socket：
 *   `localabstract:scrcpy_<socket_name>`
 *
 * 连接建立后，scrcpy server 先发送 device name (64 bytes)，再发送 video stream。
 * 客户端通过 "control" 通道发送控制包。
 */
object ScrcpyProtocol {

    // —— ADB 服务名 ——

    /** scrcpy server 启动的 localabstract 前缀 */
    const val LOCAL_ABSTRACT_PREFIX = "localabstract:scrcpy_"

    /** 默认 socket 名 */
    const val DEFAULT_SOCKET_NAME = "phone-mirror"

    /** 完整 localabstract 地址 */
    fun localAbstract(socket: String = DEFAULT_SOCKET_NAME): String =
        "$LOCAL_ABSTRACT_PREFIX$socket"

    // —— 设备名称 ——
    const val DEVICE_NAME_LENGTH = 64

    // —— 控制包类型 (1 byte) ——
    object ControlType {
        const val ACK = 0
        const val BACK_OR_SCREEN_ON = 1
        const val INPUT_TOUCH_EVENT = 2
        const val INPUT_KEYCODE = 3
        const val INPUT_TEXT = 4
        const val INPUT_SCROLL = 5
        const val CLIPBOARD = 6
        const val SET_DISPLAY_POWER = 7
        const val ROTATE_DEVICE = 8
        const val PING = 9
    }

    // —— 视频流相关 ——

    /** H.264 起始序列头：0x00 0x00 0x00 0x01 */
    val H264_NALU_STARTCODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    /** 视频流 header 结构（8 bytes）: PTS(8) 接 H.264 裸码流 */
    const val VIDEO_HEADER_SIZE = 8

    // —— 可选能力位 ——
    object Capability {
        const val DEVICE_NAME = 1
        const val DYNAMIC_MAX_FPS = 2
        const val LINK_TEST = 4
        const val BATTERY = 8
    }
}
