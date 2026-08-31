package com.phone.mirror.data.remote.files

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB SYNC 协议帧常量与类型定义。
 *
 * ADB SYNC 走 `sync:` service，帧结构：
 *
 * | Field       | Size | Note                                   |
 * |-------------|------|----------------------------------------|
 * | id          | 4    | 命令字 (SEND/RECV/STAT/LIST/DONE/DATA/OKAY/FALI) |
 * | length      | 4    | 后续 payload 长度                       |
 * | payload     | N    | 命令字不同 payload 结构不同              |
 *
 * 目录 stat 结构: mode(4) + size(4) + mtime(4) + path(null-term string)
 */
object AdbSync {

    // —— 帧 ID (4 chars as big-endian uint32) ——
    const val ID_STAT = 0x53544154  // "STAT"
    const val ID_LIST = 0x4C495354  // "LIST"
    const val ID_SEND = 0x53454E44  // "SEND"
    const val ID_RECV = 0x52454356  // "RECV"
    const val ID_DONE = 0x444F4E45  // "DONE"
    const val ID_DATA = 0x44415441  // "DATA"
    const val ID_OKAY = 0x4F4B4159  // "OKAY"
    const val ID_FALI = 0x46414C49  // "FALI" (file failure)

    /** SYNC 协议最大数据块 */
    const val MAX_DATA = 64_000

    // —— Unix 文件模式 ——
    object Mode {
        const val S_IFMT   = 0xF000
        const val S_IFDIR  = 0x4000
        const val S_IFREG  = 0x8000
        const val S_IFLNK  = 0xA000
    }

    // —— 工具函数 ——

    /** 打包 SYNC 帧头 */
    fun frameHeader(id: Int, length: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(id)
            .putInt(length)
            .array()

    /** 将 null-terminated path 转为 SYNC payload */
    fun pathPayload(path: String): ByteArray =
        (path + "\u0000").toByteArray(Charsets.UTF_8)

    /** 将 [mode] + [size] + [mtime] + path 打包为 STAT 帧 payload */
    fun statPayload(path: String): ByteArray {
        val pathBytes = path.toByteArray(Charsets.UTF_8) + 0
        val buf = ByteBuffer.allocate(4 + 4 + 4 + pathBytes.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0)   // mode (server 填充)
        buf.putInt(0)   // size (server 填充)
        buf.putInt(0)   // mtime (server 填充)
        buf.put(pathBytes)
        return buf.array()
    }

    /** 将 [path] + [mode] + [mtime] 打包为 SEND 帧 payload */
    fun sendPayload(path: String, mode: Int, mtime: Int): ByteArray {
        val pathPart = "$path,$mode\u0000".toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(pathPart.size + 4).order(ByteOrder.BIG_ENDIAN)
        buf.put(pathPart)
        buf.putInt(mtime)
        return buf.array()
    }
}
