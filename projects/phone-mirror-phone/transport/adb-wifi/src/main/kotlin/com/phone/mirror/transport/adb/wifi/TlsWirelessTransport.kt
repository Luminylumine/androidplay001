package com.phone.mirror.transport.adb.wifi

import com.phone.mirror.core.Result
import com.phone.mirror.transport.adb.core.AdbTransport
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TLS Wireless ADB 传输 —— 对应 Android 11+ Wireless Debugging。
 *
 * 连接流程：
 *  1. 用 PairingManager 完成 pairing（获取 certificate）
 *  2. 创建 JavaKeyStore 加载自签证书
 *  3. 建立 TLS handshake
 *  4. 走标准 ADB CNXN/AUTH 握手（但不再走 RSA，走 TLS）
 */
class TlsWirelessTransport(
    private val host: String,
    private val port: Int,
    /** 由 PairingManager 生成的自签 certificate，用于 TLS 双向握手 */
    private val certificate: String,
) : AdbTransport {

    private var sslSocket: SSLSocket? = null

    override val isConnected: Boolean get() = sslSocket?.isConnected == true

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runResult {
            // TODO: 加载 certificate 并创建 TrustManager / KeyManager
            val sslCtx = SSLContext.getInstance("TLS")
            val s = sslCtx.socketFactory.createSocket() as SSLSocket
            s.connect(InetSocketAddress(host, port), 10_000)
            s.startHandshake()
            sslSocket = s
        }
    }

    override suspend fun readBytes(len: Int): ByteArray = withContext(Dispatchers.IO) {
        val buf = ByteArray(len)
        var total = 0
        val stream = sslSocket?.inputStream ?: error("tls socket not connected")
        while (total < len) {
            val n = stream.read(buf, total, len - total)
            if (n <= 0) break
            total += n
        }
        if (total == len) buf else buf.copyOf(total)
    }

    override suspend fun writeBytes(data: ByteArray) = withContext(Dispatchers.IO) {
        sslSocket?.outputStream?.write(data)
        sslSocket?.outputStream?.flush()
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        sslSocket?.close()
        sslSocket = null
    }
}
