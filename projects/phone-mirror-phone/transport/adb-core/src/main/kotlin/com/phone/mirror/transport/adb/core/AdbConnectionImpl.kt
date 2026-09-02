package com.phone.mirror.transport.adb.core

import com.phone.mirror.core.Result
import com.phone.mirror.core.runCatchingResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

/**
 * 真实的 ADB 连接实现。负责：
 *
 *  1. 底层 [transport] 字节流的 packet 收/发 (packet framing)
 *  2. CNXN/AUTH 握手 (legacy)
 *  3. stream 多路复用：OPEN → OKAY → WRTE/CLSE
 *  4. 后台 read-loop：持续读 transport，按 localId 分发到 stream 的接收 channel
 *
 * **线程模型**：
 *  - 所有写入 transport 的操作通过 [writeMutex] 串行化（TCP 字节流上多个 packet 不能交叉）
 *  - 一个后台 [readerJob] 持续从 transport 读 packet，然后 dispatch 到各 stream channel
 *
 * **ACK 规则**：
 *  - OPEN 成功：device 发 OKAY(arg0=device_local_id, arg1=host_local_id) —— arg0 存为 stream 的 remoteId
 *  - 每次 WRTE 后：device 发 OKAY 作为 window update —— 唤醒 write()
 *  - CLSE：双方都可能发，收到后关闭 stream
 */
class AdbConnectionImpl(
    private val transport: AdbTransport,
    private val keyPair: AdbKeyPair,
) : AdbConnection, CoroutineScope {

    override val coroutineContext: CoroutineContext = Dispatchers.IO + SupervisorJob()

    /** 下一个可用的 localId —— 从 1 开始 (0 保留) */
    private var nextLocalId = 1
    private val nextLocalIdLock = Any()

    /** 写 transport 的互斥锁 —— 保证多个 stream 不会同时写 header + payload 乱序 */
    private val writeMutex = Mutex()

    /** 活跃 stream: key = localId (host 侧) */
    private val streams = mutableMapOf<Int, AdbStreamImpl>()
    private val streamsLock = Any()

    @Volatile
    private var closed = false

    private var readerJob: kotlinx.coroutines.Job? = null
    private var deviceBanner: String? = null

    // ---------- 公开 API ----------

    /**
     * 建立 ADB 会话（含 CNXN/AUTH 握手 + 启动后台 reader loop）。
     * @param ourBanner 我们在 CNXN payload 中宣布的 banner，默认 "host::"
     * @return 对端 CNXN banner (通常是 "device::ro.product.model")，或 Failure
     */
    suspend fun connect(ourBanner: String = "host::"): Result<String> {
        return runCatchingResult {
            ensureActive()
            if (!transport.isConnected) {
                val conn = transport.connect()
                if (conn is Result.Failure) return@runCatchingResult conn
            }

            // 1. 发 CNXN
            writePacket(
                AdbPacket(
                    command = AdbCommand.CNXN,
                    arg0 = AdbCommand.VERSION_CURRENT,
                    arg1 = AdbCommand.MAX_DATA,
                    payload = ourBanner.toByteArray(Charsets.UTF_8),
                ),
            )

            // 2. 读第一个 packet
            val first = readPacket()
            when (first.command) {
                AdbCommand.CNXN -> deviceBanner = first.bannerText()
                AdbCommand.AUTH -> {
                    if (first.arg0 != AdbCommand.AUTH_TOKEN) {
                        error("unexpected AUTH arg0=${first.arg0}")
                    }
                    runCatching { handleLegacyAuth(first.payload) }
                        .onFailure { t ->
                            throw IllegalStateException("AUTH handshake failed: ${t.message}", t)
                        }
                }
                AdbCommand.STLS -> error("STLS (Wireless Debugging TLS) not supported in Phase 0 — use legacy TCP")
                else -> error("unexpected first packet: ${first.command}")
            }

            // 3. 启动后台 reader loop
            readerJob = launch(Dispatchers.IO) { readLoop() }
            Result.success(deviceBanner.orEmpty())
        }
    }

    override suspend fun open(service: String): Result<AdbStream> {
        return runCatchingResult {
            val localId = synchronized(nextLocalIdLock) { nextLocalId++ }
            val openPkt = AdbPacket.open(service, localId)
            writePacket(openPkt)

            val stream = AdbStreamImpl(this, localId, remoteIdInternal = 0)
            synchronized(streamsLock) { streams[localId] = stream }

            if (!stream.awaitOkay(timeoutMs = 30_000)) {
                synchronized(streamsLock) { streams.remove(localId) }
                error("OPEN '$service' timeout — peer did not respond with OKAY within 30s")
            }

            Result.success(stream)
        }
    }

    override suspend fun shell(command: String): Result<String> {
        val openResult = open("shell:$command")
        val stream = (openResult as? Result.Success)?.value
            ?: return Result.failure("open shell:$command failed", (openResult as? Result.Failure)?.cause)
        val sb = StringBuilder()
        try {
            while (!stream.isClosed) {
                val data = stream.read(65536)
                if (data.isEmpty()) break
                sb.append(String(data, Charsets.UTF_8))
            }
        } finally {
            runCatching { stream.close() }
        }
        return Result.success(sb.toString().trim())
    }

    override suspend fun close() {
        if (closed) return
        closed = true

        val snapshot = synchronized(streamsLock) { streams.values.toList() }
        snapshot.forEach { it.deliverClose() }
        synchronized(streamsLock) { streams.clear() }

        runCatching { readerJob?.cancel() }
        readerJob = null
        runCatching { transport.close() }
        runCatching { cancel() }
    }

    // ---------- AUTH ----------

    private suspend fun handleLegacyAuth(token: ByteArray) {
        // 第一次: SIGNATURE
        val sig = keyPair.signAdbToken(token)
        writePacket(AdbPacket.authSignature(sig))

        val resp1 = readPacket()
        when (resp1.command) {
            AdbCommand.CNXN -> { deviceBanner = resp1.bannerText(); return }
            AdbCommand.AUTH -> {
                if (resp1.arg0 != AdbCommand.AUTH_TOKEN) {
                    error("expected AUTH TOKEN after SIGNATURE, got arg0=${resp1.arg0}")
                }
                // signature 被拒 -> 我们的 key 不在 trusted list -> 发 PUBLIC KEY 让用户授权
                writePacket(AdbPacket.authPublicKey(keyPair.authPayload))
                val resp2 = readPacket()
                if (resp2.command != AdbCommand.CNXN) {
                    error("expected CNXN after RSAPUBLICKEY, got ${resp2.command} — did user approve on target?")
                }
                deviceBanner = resp2.bannerText()
            }
            else -> error("unexpected response after SIGNATURE: ${resp1.command}")
        }
    }

    // ---------- readLoop ----------

    /**
     * 后台循环：持续从 transport 读 packet 并 dispatch。
     *
     * 路由规则 (host 视角):
     *  - arg1 = host 侧 localId (用来在 streams map 查找)
     *  - arg0 = device 侧 localId (remoteId，用来回复)
     */
    private suspend fun readLoop() {
        try {
            while (!closed) {
                ensureActive()
                val pkt = readPacket()
                val localId = pkt.arg1   // host 侧 id (map key)
                val remoteId = pkt.arg0 // device 侧 id

                when (pkt.command) {
                    AdbCommand.WRTE -> {
                        synchronized(streamsLock) { streams[localId] }
                            ?.deliverPayload(pkt.payload)
                    }
                    AdbCommand.OKAY -> {
                        synchronized(streamsLock) {
                            streams[localId]?.let { stream ->
                                // 首次 OKAY: 更新 remoteId (之前是 0)
                                if (stream.remoteId == 0) stream.updateRemoteId(remoteId)
                                stream.deliverOkay()
                            }
                        }
                    }
                    AdbCommand.CLSE -> {
                        val stream = synchronized(streamsLock) { streams[localId] }
                        if (stream != null) {
                            synchronized(streamsLock) { streams.remove(localId) }
                            stream.deliverClose()
                        }
                    }
                    else -> {
                        // CNXN/AUTH 在 readLoop 里不该再出现 —— 忽略
                    }
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // 正常退出
        } catch (t: Throwable) {
            if (!closed) {
                synchronized(streamsLock) {
                    streams.values.forEach { it.deliverClose() }
                    streams.clear()
                }
            }
        }
    }

    // ---------- internal helpers ----------

    internal suspend fun readPacket(): AdbPacket {
        val header = transport.readBytes(24)
        if (header.isEmpty()) error("transport EOF while reading header")
        if (header.size < 24) error("truncated header: ${header.size} < 24")

        val buf = ByteBuffer.wrap(header, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val dataLen = buf.int
        val dataCheck = buf.int
        val magic = buf.int

        // 入站报文完整性校验（纵深防御：即使底层是明文 TCP，伪造/损坏报文也应被拒绝）
        if (dataLen < 0 || dataLen > AdbCommand.MAX_DATA) {
            error("invalid data_length: $dataLen (max ${AdbCommand.MAX_DATA})")
        }
        val expectedMagic = command xor 0xFFFF_FFFF.toInt()
        if (magic != expectedMagic) {
            error("bad magic: expected 0x${expectedMagic.toUInt().toString(16)}, got 0x${magic.toUInt().toString(16)}")
        }

        val payload = if (dataLen > 0) transport.readBytes(dataLen) else ByteArray(0)
        if (payload.size < dataLen) error("truncated payload: ${payload.size} < $dataLen")

        val pkt = AdbPacket(command, arg0, arg1, payload)
        val expectedChecksum = pkt.checksum
        if (dataCheck != expectedChecksum) {
            error("bad checksum: expected 0x${expectedChecksum.toUInt().toString(16)}, got 0x${dataCheck.toUInt().toString(16)}")
        }
        return pkt
    }

    internal suspend fun writePacket(pkt: AdbPacket) {
        writeMutex.withLock {
            transport.writeBytes(pkt.encode())
        }
    }

    private fun AdbPacket.bannerText(): String {
        val end = payload.indexOf(0)
        val real = if (end >= 0) payload.copyOf(end) else payload
        return String(real, Charsets.UTF_8)
    }
}
