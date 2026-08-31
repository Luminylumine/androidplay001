package com.phone.mirror.mirror.scrcpy.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * scrcpy 协议编解码 —— 控制包的序列化与反序列化。
 */
object ScrcpyCodec {

    /**
     * 打包 INPUT_TOUCH_EVENT 控制包。
     * 结构: type(1) → action(1) → pointerId(4) → x(4) → y(4) → pressure(2) → buttons(2)
     */
    fun encodeTouch(
        action: Int,
        pointerId: Long,
        x: Float,
        y: Float,
        pressure: Float = 1f,
        buttons: Int = 0,
    ): ByteArray {
        val buf = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(ScrcpyProtocol.ControlType.INPUT_TOUCH_EVENT.toByte())
        buf.put(action.toByte())
        buf.putInt((pointerId and 0xFFFFFFFF).toInt())
        buf.putFloat(x)
        buf.putFloat(y)
        buf.putShort((pressure * 0xFFFF).toShort())
        buf.putShort(buttons.toShort())
        return buf.array()
    }

    /**
     * 打包 INPUT_KEYCODE 控制包。
     * 结构: type(1) → action(1) → keycode(4) → scanCode(4) → metaState(4) → repeat(2) → extKeycode(2)
     */
    fun encodeKeycode(
        action: Int,
        keycode: Int,
        scanCode: Int = 0,
        metaState: Int = 0,
    ): ByteArray {
        val buf = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(ScrcpyProtocol.ControlType.INPUT_KEYCODE.toByte())
        buf.put(action.toByte())
        buf.putInt(keycode)
        buf.putInt(scanCode)
        buf.putInt(metaState)
        buf.putShort(0)  // repeat count
        buf.putShort(0)  // ext keycode
        return buf.array()
    }

    /**
     * 打包 BACK_OR_SCREEN_ON 控制包（屏幕唤醒 / 返回键）
     */
    fun encodeBackOrScreenOn(): ByteArray =
        byteArrayOf(ScrcpyProtocol.ControlType.BACK_OR_SCREEN_ON.toByte())

    /**
     * 打包 CLIPBOARD 控制包
     * 结构: type(1) → length(4) → content(utf8 bytes)
     */
    fun encodeClipboard(text: String): ByteArray {
        val content = text.toByteArray()
        val buf = ByteBuffer.allocate(5 + content.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(ScrcpyProtocol.ControlType.CLIPBOARD.toByte())
        buf.putInt(content.size)
        buf.put(content)
        return buf.array()
    }

    /**
     * 打包 INPUT_SCROLL 控制包
     * 结构: type(1) → x(4) → y(4) → hScroll(2) → vScroll(2)
     */
    fun encodeScroll(x: Int, y: Int, hScroll: Float, vScroll: Float): ByteArray {
        val buf = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(ScrcpyProtocol.ControlType.INPUT_SCROLL.toByte())
        buf.putInt(x)
        buf.putInt(y)
        buf.putShort((hScroll * 0xFFFF).toShort())
        buf.putShort((vScroll * 0xFFFF).toShort())
        return buf.array()
    }
}
