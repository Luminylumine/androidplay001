package com.phone.mirror.transport.adb.wifi

import com.phone.mirror.core.Result
import com.phone.mirror.core.runCatchingResult
import com.phone.mirror.transport.adb.core.AdbTransport
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TLS Wireless ADB 传输 —— Phase 1 用。当前 stub。
 */
class TlsWirelessTransport(
    private val host: String,
    private val port: Int,
    private val certificate: String,
) : AdbTransport {

    private var sslSocket: SSLSocket? = null

    override val isConnected: Boolean get() = sslSocket?.isConnected == true

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val sslCtx = SSLContext.getInstance("TLS")
            val s = sslCtx.socketFactory.createSocket() as SSLSocket
            s.connect(InetSocketAddress(host, port), 10_000)
            s.startHandshake()
            sslSocket = s
            Result.success(Unit)
        }
    }

    override suspend fun readBytes(len: Int): ByteArray = withContext(Dispatchers.IO) {
        val s = sslSocket ?: error("tls socket not connected")
        val buf = ByteArray(len)
        var total = 0
        val stream = s.inputStream
        while (total < len) {
            val n = stream.read(buf, total, len - total)
            if (n <= 0) break
            total += n
        }
        if (total == len) buf else buf.copyOf(total)
    }

    override suspend fun writeBytes(data: ByteArray) {
        withContext(Dispatchers.IO) {
            val s = sslSocket ?: error("tls socket not connected")
            s.outputStream.write(data)
            s.outputStream.flush()
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        sslSocket?.close()
        sslSocket = null
    }
}
