package com.phone.mirror.mirror.scrcpy.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * scrcpy 协议编解码 —— 控制包序列化 + video 包 header / DeviceMessage 反序列化。
 *
 * 所有布局对齐官方 scrcpy wire protocol（big-endian）：
 *
 *  - INJECT_KEYCODE:      type(1) + action(1) + keycode(4) + repeat(4) + metaState(4) = 14 bytes
 *  - INJECT_TEXT:         type(1) + len(4) + utf8(N)
 *  - INJECT_TOUCH_EVENT:  type(1) + action(1) + pointerId(8) + x(4) + y(4) +
 *                         screenWidth(2) + screenHeight(2) + pressure(2) +
 *                         actionButton(4) + buttons(4) = 32 bytes
 *  - INJECT_SCROLL_EVENT: type(1) + x(4) + y(4) + hScroll(2) + vScroll(2) = 13 bytes
 *  - SET_CLIPBOARD:       type(1) + copy(1) + len(4) + utf8(N)
 */
object ScrcpyCodec {

    // ---------- 控制包编码（client → device，BE） ----------

    /**
     * INJECT_KEYCODE（14 bytes）。
     * @param action [ScrcpyProtocol.Action] DOWN / UP
     */
    fun encodeKeycode(
        action: Int,
        keycode: Int,
        repeat: Int = 0,
        metaState: Int = 0,
    ): ByteArray {
        val buf = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
        buf.put(ScrcpyProtocol.ControlType.INJECT_KEYCODE.toByte())
        buf.put(action.toByte())
        buf.putInt(keycode)
        buf.putInt(repeat)
        buf.putInt(metaState)
        return buf.array()
    }

    /**
     * INJECT_TEXT：type(1) + length(4, 字节数) + utf8。
     */
    fun encodeText(text: String): ByteArray {
        val content = text.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(5 + content.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(ScrcpyProtocol.ControlType.INJECT_TEXT.toByte())
        buf.putInt(content.size)
        buf.put(content)
        return buf.array()
    }

    /**
     * INJECT_TOUCH_EVENT（32 bytes）。
     * @param action [ScrcpyProtocol.Action] DOWN / UP / MOVE
     * @param pointerId 指针 id（u64 BE；多指时每指独立 id）
     * @param x, y 触点坐标（i32 BE，主屏坐标系）
     * @param screenWidth, screenHeight 触点所在屏幕尺寸（u16 BE，用于坐标归一化）
     * @param pressure 压力 [0,1]，u16 定点
     */
    fun encodeTouch(
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        pressure: Float = 1f,
        actionButton: Int = 0,
        buttons: Int = 0,
    ): ByteArray {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buf.put(ScrcpyProtocol.ControlType.INJECT_TOUCH_EVENT.toByte())
        buf.put(action.toByte())
        buf.putLong(pointerId)
        buf.putInt(x)
        buf.putInt(y)
        buf.putShort(screenWidth.toShort())
        buf.putShort(screenHeight.toShort())
        buf.putShort((pressure.coerceIn(0f, 1f) * 0xFFFF).toInt().toShort())
        buf.putInt(actionButton)
        buf.putInt(buttons)
        return buf.array()
    }

    /**
     * INJECT_SCROLL_EVENT（13 bytes）：x/y i32 BE，hScroll/vScroll i16 定点（8 位小数）。
     */
    fun encodeScroll(x: Int, y: Int, hScroll: Float, vScroll: Float): ByteArray {
        fun fp16(v: Float): Short = (v.coerceIn(-127.99f, 127.99f) * 0x100).toInt().toShort()
        val buf = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
        buf.put(ScrcpyProtocol.ControlType.INJECT_SCROLL_EVENT.toByte())
        buf.putInt(x)
        buf.putInt(y)
        buf.putShort(fp16(hScroll))
        buf.putShort(fp16(vScroll))
        return buf.array()
    }

    /**
     * BACK_OR_SCREEN_ON：type(1) + action(1)。action=DOWN 唤醒屏幕，UP 触发 back。
     */
    fun encodeBackOrScreenOn(action: Int = ScrcpyProtocol.Action.DOWN): ByteArray =
        byteArrayOf(
            ScrcpyProtocol.ControlType.BACK_OR_SCREEN_ON.toByte(),
            action.toByte(),
        )

    /** SET_CLIPBOARD：type(1) + copy(1) + length(4) + utf8。文本超限抛 [IllegalArgumentException]。 */
    fun encodeSetClipboard(text: String, copy: Boolean = false): ByteArray {
        val content = text.toByteArray(Charsets.UTF_8)
        require(content.size <= ScrcpyProtocol.MAX_CLIPBOARD_LENGTH) {
            "clipboard text too long: ${content.size} > ${ScrcpyProtocol.MAX_CLIPBOARD_LENGTH}"
        }
        val buf = ByteBuffer.allocate(6 + content.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(ScrcpyProtocol.ControlType.SET_CLIPBOARD.toByte())
        buf.put(if (copy) 1 else 0)
        buf.putInt(content.size)
        buf.put(content)
        return buf.array()
    }

    /** GET_CLIPBOARD：type(1) + copyKey(1)。copyKey=1 时设备同时把内容写入自身剪贴板。 */
    fun encodeGetClipboard(copyKey: Boolean = false): ByteArray =
        byteArrayOf(
            ScrcpyProtocol.ControlType.GET_CLIPBOARD.toByte(),
            if (copyKey) 1 else 0,
        )

    /** SET_DISPLAY_POWER：type(1) + on(1)。 */
    fun encodeSetDisplayPower(on: Boolean): ByteArray =
        byteArrayOf(
            ScrcpyProtocol.ControlType.SET_DISPLAY_POWER.toByte(),
            if (on) 1 else 0,
        )

    /** ROTATE_DEVICE：1 byte。 */
    fun encodeRotateDevice(): ByteArray =
        byteArrayOf(ScrcpyProtocol.ControlType.ROTATE_DEVICE.toByte())

    /** EXPAND_NOTIFICATION_PANEL：1 byte。 */
    fun encodeExpandNotificationPanel(): ByteArray =
        byteArrayOf(ScrcpyProtocol.ControlType.EXPAND_NOTIFICATION_PANEL.toByte())

    /** EXPAND_SETTINGS_PANEL：1 byte。 */
    fun encodeExpandSettingsPanel(): ByteArray =
        byteArrayOf(ScrcpyProtocol.ControlType.EXPAND_SETTINGS_PANEL.toByte())

    /** COLLAPSE_PANELS：1 byte。 */
    fun encodeCollapsePanels(): ByteArray =
        byteArrayOf(ScrcpyProtocol.ControlType.COLLAPSE_PANELS.toByte())

    // ---------- video 包 header 解析（device → client，12 bytes BE） ----------

    /** session packet：屏幕尺寸变化通知（rotation / 分辨率切换） */
    data class SessionPacket(
        val clientResized: Boolean,
        val width: Int,
        val height: Int,
    )

    /** media packet header：一帧视频数据的元信息 */
    data class MediaPacketHeader(
        val pts: Long,        // u61 BE（byte0 低 5 位 + bytes 1..7）
        val config: Boolean,  // bit 6: SPS/PPS 等配置包
        val keyFrame: Boolean,// bit 5: 关键帧
        val size: Int,        // u32 BE：后续 payload 字节数
    )

    /**
     * 解析 12 字节 packet header 的首字节，判断 session / media。
     * [header] 至少 1 字节（通常传 12 字节）。
     */
    fun isSessionPacket(header: ByteArray): Boolean {
        require(header.isNotEmpty()) { "empty header" }
        return (header[0].toInt() and ScrcpyProtocol.FLAG_SESSION) != 0
    }

    /**
     * 解析 SessionPacket（12 bytes）：
     * byte0 bit7=1；byte3 bit0=clientResized；bytes4..7 width；bytes8..11 height（BE）。
     */
    fun decodeSessionPacket(header: ByteArray): SessionPacket {
        require(header.size >= ScrcpyProtocol.PACKET_HEADER_SIZE) {
            "session header must be ${ScrcpyProtocol.PACKET_HEADER_SIZE} bytes, got ${header.size}"
        }
        val buf = ByteBuffer.wrap(header, 0, ScrcpyProtocol.PACKET_HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        buf.int // byte 0..3: flags（byte3 低 1 位有效）
        val flags = header[3].toInt()
        val width = buf.int
        val height = buf.int
        return SessionPacket(
            clientResized = (flags and 0x01) != 0,
            width = width,
            height = height,
        )
    }

    /**
     * 解析 MediaPacket header（12 bytes）：
     * byte0 bit7=0, bit6=config, bit5=keyFrame, bits0..4=PTS 高 5 位；
     * bytes1..7=PTS 低 56 位；bytes8..11=size（全部 BE）。
     */
    fun decodeMediaPacketHeader(header: ByteArray): MediaPacketHeader {
        require(header.size >= ScrcpyProtocol.PACKET_HEADER_SIZE) {
            "media header must be ${ScrcpyProtocol.PACKET_HEADER_SIZE} bytes, got ${header.size}"
        }
        val b0 = header[0].toInt()
        // PTS (u61) = byte0 低 5 位 (bits 60..56) || bytes 1..7 (bits 55..0, BE)
        var pts = (b0 and 0x1F).toLong()
        for (i in 1..7) {
            pts = (pts shl 8) or (header[i].toLong() and 0xFF)
        }
        val size = readU32Be(header, 8)
        return MediaPacketHeader(
            pts = pts,
            config = (b0 and ScrcpyProtocol.FLAG_CONFIG) != 0,
            keyFrame = (b0 and ScrcpyProtocol.FLAG_KEY_FRAME) != 0,
            size = size,
        )
    }

    // ---------- DeviceMessage 解析（device → client） ----------

    /** device → client 消息 */
    sealed interface DeviceMessage {
        /** 设备剪贴板内容变化 */
        data class Clipboard(val text: String) : DeviceMessage

        /** 确认 client 的 SET_CLIPBOARD 已应用（携带 client 序列号） */
        data class AckClipboard(val sequence: Long) : DeviceMessage

        /** UHID 输出数据（HID 设备回传） */
        data class UhidOutput(val data: ByteArray) : DeviceMessage
    }

    /**
     * 从 control socket 的接收数据解析一条 DeviceMessage。
     * @return 解析出的消息及消费的字节数；数据不足返回 null。
     */
    fun decodeDeviceMessage(bytes: ByteArray): Pair<DeviceMessage, Int>? {
        if (bytes.isEmpty()) return null
        return when (bytes[0].toInt()) {
            ScrcpyProtocol.DeviceMessageType.CLIPBOARD -> {
                if (bytes.size < 5) return null
                val len = readU32Be(bytes, 1)
                val end = 5 + len
                if (bytes.size < end) return null
                val text = String(bytes, 5, len, Charsets.UTF_8)
                DeviceMessage.Clipboard(text) to end
            }
            ScrcpyProtocol.DeviceMessageType.ACK_CLIPBOARD -> {
                if (bytes.size < 9) return null
                var seq = 0L
                for (i in 1..8) seq = (seq shl 8) or (bytes[i].toLong() and 0xFF)
                DeviceMessage.AckClipboard(seq) to 9
            }
            ScrcpyProtocol.DeviceMessageType.UHID_OUTPUT -> {
                if (bytes.size < 5) return null
                val len = readU32Be(bytes, 1)
                val end = 5 + len
                if (bytes.size < end) return null
                DeviceMessage.UhidOutput(bytes.copyOfRange(5, end)) to end
            }
            else -> null
        }
    }

    private fun readU32Be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toLong() and 0xFF) shl 24 or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)).toInt()
}
