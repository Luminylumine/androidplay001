package com.phone.mirror.transport.adb.wifi

import com.phone.mirror.core.Result
import com.phone.mirror.transport.adb.core.AdbTransport
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Legacy TCP ADB 传输 —— 针对传统的 `adb connect host:port`（明文 TCP，无 TLS）。
 *
 * 这种连接没有 Wirelsss Debugging 的配对流程，端口固定（常见为 5555 / 5037）。
 */
class LegacyTcpTransport(
    private val host: String,
    private val port: Int,
) : AdbTransport {

    private var socket: Socket? = null

    override val isConnected: Boolean get() = socket?.isConnected == true

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runResult {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 10_000)
            socket = s
        }
    }

    override suspend fun readBytes(len: Int): ByteArray = withContext(Dispatchers.IO) {
        val buf = ByteArray(len)
        var total = 0
        val stream = socket?.getInputStream() ?: error("socket not connected")
        while (total < len) {
            val n = stream.read(buf, total, len - total)
            if (n <= 0) break
            total += n
        }
        if (total == len) buf else buf.copyOf(total)
    }

    override suspend fun writeBytes(data: ByteArray) = withContext(Dispatchers.IO) {
        socket?.getOutputStream()?.write(data)
        socket?.getOutputStream()?.flush()
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        socket?.close()
        socket = null
    }
}
