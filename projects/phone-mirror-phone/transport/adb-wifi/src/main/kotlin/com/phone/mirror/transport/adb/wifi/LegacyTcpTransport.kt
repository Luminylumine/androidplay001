package com.phone.mirror.transport.adb.wifi

import com.phone.mirror.core.Result
import com.phone.mirror.core.runCatchingResult
import com.phone.mirror.transport.adb.core.AdbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Legacy TCP ADB 传输 —— 针对传统的 `adb connect host:port`（明文 TCP，无 TLS）。
 *
 * Phase 0 验收用。
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
        runCatchingResult {
            val s = Socket()
            s.soTimeout = 0
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket = s
            Result.success(Unit)
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
                return@withContext if (total == 0) ByteArray(0) else buf.copyOf(total)
            }
            if (n == 0) continue
            total += n
        }
        buf
    }

    override suspend fun writeBytes(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val s = socket ?: throw IOException("LegacyTcpTransport: not connected")
            s.outputStream.write(data)
            s.outputStream.flush()
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
    }
}
