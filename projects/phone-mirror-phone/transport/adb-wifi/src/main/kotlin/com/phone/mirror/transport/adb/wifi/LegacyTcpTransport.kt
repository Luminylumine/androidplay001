package com.phone.mirror.transport.adb.wifi

import com.phone.mirror.core.Result
import com.phone.mirror.core.runResult
import com.phone.mirror.transport.adb.core.AdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Legacy TCP ADB 传输 —— 针对传统的 `adb connect host:port`（明文 TCP，无 TLS）。
 *
 * 这种连接没有 Wireless Debugging 的配对流程。
 * 典型端口: target 上 adbd 已启用 TCP 监听 (target 侧 `setprop service.adb.tcp.port 5555` + `stop adbd; start adbd`)。
 *
 * Phase 0 验收用的就是这个 —— 先拿到 LegacyTcpTransport 能连上，证明协议层是对的。
 */
class LegacyTcpTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 10_000,
) : AdbTransport {

    @Volatile
    private var socket: Socket? = null

    override val isConnected: Boolean get() = socket?.let { !it.isClosed && it.isConnected } ?: false

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runResult {
            val s = Socket()
            s.soTimeout = 0            // 无限超时 —— 上层协程 cancel 会中断
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket = s
        }
    }

    override suspend fun readBytes(len: Int): ByteArray = withContext(Dispatchers.IO) {
        val stream = socket?.getInputStream()
            ?: throw IOException("LegacyTcpTransport: not connected")

        val buf = ByteArray(len)
        var total = 0
        while (total < len) {
            val n = stream.read(buf, total, len - total)
            if (n < 0) {
                // EOF —— 返回已读的部分或空
                return@withContext if (total == 0) ByteArray(0) else buf.copyOf(total)
            }
            if (n == 0) {
                // 永远不该发生（InputStream.read 的 contract: 只有 EOF 返回 -1）
                yield()
                continue
            }
            total += n
        }
        buf
    }

    override suspend fun writeBytes(data: ByteArray) = withContext(Dispatchers.IO) {
        val s = socket ?: throw IOException("LegacyTcpTransport: not connected")
        s.getOutputStream().apply {
            write(data)
            flush()
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
    }
}
