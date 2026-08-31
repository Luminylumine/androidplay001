package com.phone.mirror.transport.adb.core

import com.phone.mirror.core.Result

/**
 * ADB 底层传输抽象 —— TCP、USB、TLS-Wireless 都实现这个接口。
 *
 * 传输层只负责字节级别的收发，不理解 ADB 协议。
 * 真正的 ADB 包 (AdbPacket) 由上层 [AdbConnection] 解释。
 */
interface AdbTransport {
    /** 建立连接（TCP 连接 / USB claim + setup） */
    suspend fun connect(): Result<Unit>

    /** 读取 raw bytes，长度为 [len]。返回实际读取到的字节数组（可能少于 len，若 EOF 返回空） */
    suspend fun readBytes(len: Int): ByteArray

    /** 写入 raw bytes */
    suspend fun writeBytes(data: ByteArray)

    /** 关闭底层连接，释放资源 */
    suspend fun close()

    /** 是否已连接 */
    val isConnected: Boolean
}
