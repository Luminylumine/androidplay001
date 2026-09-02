package com.phone.mirror.transport.adb.core

import com.phone.mirror.core.Result

/**
 * 多路复用的 ADB stream，由 [AdbConnection.open] 获得。
 *
 * 每个 stream 有唯一 [localId] / [remoteId]，底层 ADB 协议用 ID 做路由。
 */
interface AdbStream : AutoCloseable {
    /** 本端 stream 句柄（adb 内部 id） */
    val localId: Int

    /** 远端 stream 句柄（adb 内部 id） */
    val remoteId: Int

    /** 向 stream 写入数据 */
    suspend fun write(data: ByteArray): Result<Unit>

    /** 从 stream 读取数据。若流关闭或 EOF，返回空数组 */
    suspend fun read(len: Int): ByteArray

    /** 等待 stream 关闭（对等端 CLSE） */
    suspend fun awaitClose()

    /** 是否已关闭 */
    val isClosed: Boolean

    /** 关闭本 stream */
    override fun close()
}
