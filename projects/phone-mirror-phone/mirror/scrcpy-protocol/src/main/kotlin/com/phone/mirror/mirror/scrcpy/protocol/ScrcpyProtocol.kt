package com.phone.mirror.mirror.scrcpy.protocol

/**
 * scrcpy 协议常量 —— 对齐官方 scrcpy wire protocol（scrcpy 4.x）。
 *
 * 依据：IMPLEMENTATION_PLAN.md Phase 2/3 常量表 + scrcpy 官方 develop.md。
 *
 * 连接建立（forward tunnel 场景）：
 *  1. video socket（第 1 个 localabstract socket）：
 *     device 发 1 字节 dummy byte（forward 模式连接探测）→ codec id (4 bytes) →
 *     device name (64 bytes) → 循环 12 字节 packet header（session / media）
 *  2. control socket（第 2 个）：client 发 1 字节 dummy byte（forward 模式探测），
 *     之后双向：client 写 ControlMessage，device 回 DeviceMessage
 *
 * 所有多字节字段均为 **big-endian**。
 */
object ScrcpyProtocol {

    // —— ADB 服务名 ——

    /** scrcpy server 监听的 localabstract socket 前缀 */
    const val LOCAL_ABSTRACT_PREFIX = "localabstract:scrcpy_"

    /** 默认 socket 名（一个 client 会话一个 scid，避免多会话冲突） */
    const val DEFAULT_SOCKET_NAME = "phone-mirror"

    /** 完整 localabstract 地址 */
    fun localAbstract(socket: String = DEFAULT_SOCKET_NAME): String =
        "$LOCAL_ABSTRACT_PREFIX$socket"

    // —— 连接元数据 ——

    /** device name 固定长度（\0 padding） */
    const val DEVICE_NAME_LENGTH = 64

    /** codec id 字段长度（"h264" / "h265" 四字符，u32 BE） */
    const val CODEC_ID_LENGTH = 4

    // —— 视频 codec id（u32 BE，即四字符 ASCII 的 BE 读法）——

    /** "h264" */
    const val CODEC_ID_H264 = 0x68323634

    /** "h265" */
    const val CODEC_ID_H265 = 0x68323635

    // —— 控制包类型 (ControlMessageType, 1 byte) —— 官方值 ——

    object ControlType {
        const val INJECT_KEYCODE = 0
        const val INJECT_TEXT = 1
        const val INJECT_TOUCH_EVENT = 2
        const val INJECT_SCROLL_EVENT = 3
        const val BACK_OR_SCREEN_ON = 4
        const val EXPAND_NOTIFICATION_PANEL = 5
        const val EXPAND_SETTINGS_PANEL = 6
        const val COLLAPSE_PANELS = 7
        const val GET_CLIPBOARD = 8
        const val SET_CLIPBOARD = 9
        const val SET_DISPLAY_POWER = 10
        const val ROTATE_DEVICE = 11
        const val UHID_CREATE = 12
        const val UHID_INPUT = 13
        const val UHID_DESTROY = 14
        const val OPEN_HARD_KEYBOARD_SETTINGS = 15
        const val START_APP = 16
    }

    // —— 控制包动作常量 ——

    /** keycode / touch 的 action（与 Android KeyEvent/MotionEvent 一致） */
    object Action {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
    }

    // —— 视频包 header（12 bytes，BE）——

    /** session / media packet 公共 header 长度 */
    const val PACKET_HEADER_SIZE = 12

    // byte 0 位标志
    const val FLAG_SESSION = 0x80   // bit 7: 1=session packet, 0=media packet
    const val FLAG_CONFIG = 0x40    // bit 6: config packet (SPS/PPS)
    const val FLAG_KEY_FRAME = 0x20 // bit 5: key frame

    // —— H.264 ——

    /** H.264 NALU 起始码 */
    val H264_NALU_STARTCODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    // —— 剪贴板 ——

    /** SET_CLIPBOARD 文本上限（协议级防溢出） */
    const val MAX_CLIPBOARD_LENGTH = (1 shl 18) - 14

    // —— device → client 消息类型 (DeviceMessageType, 1 byte) —— 官方值 ——

    object DeviceMessageType {
        const val CLIPBOARD = 0
        const val ACK_CLIPBOARD = 1
        const val UHID_OUTPUT = 2
    }
}
