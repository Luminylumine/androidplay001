package com.phone.mirror.transport.adb.core

import com.phone.mirror.core.Result
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ADB stream 真实实现。
 *
 * 每个 stream 有一个 [localId] (host 侧) / [remoteId] (device 侧)，
 * 通过这两个 ID 在 [AdbConnectionImpl.readLoop] 做路由。
 *
 * ## 接收端
 * 用 [readChannel] 把后台 readLoop 收到的 WRTE payload 投递给调用方的 [read]。
 * 使用容量 256 的 BufferedChannel —— 足够容纳 shell 输出。
 *
 * ## OKAY flow control
 * 收到对端 OKAY 有两个用途：
 *  1. OPEN 后的首次 OKAY —— 把 device_local_id 存到 stream 的 remoteId
 *  2. 每次我们 write() 后对端 OKAY —— 唤醒 write() 里正在等待的协程
 */
class AdbStreamImpl internal constructor(
    private val connection: AdbConnectionImpl,
    override val localId: Int,
    private var remoteIdInternal: Int,
) : AdbStream {

    /** WRTE payload 缓冲 —— 256 足够 shell 输出 */
    private val readChannel = Channel<ByteArray>(capacity = 256)

    @Volatile
    private var okayReceived = false

    /** Java object 用于 synchronized wait/notify —— 必须是 java.lang.Object 否则 Kotlin 找不到 wait/notify */
    private val okayLock = java.lang.Object()

    @Volatile
    private var closeReceived = false

    @Volatile
    private var closedLocal = false

    // ---------- internal 给 AdbConnectionImpl 用 ----------

    internal suspend fun awaitOkay(timeoutMs: Long): Boolean {
        val ok = withTimeoutOrNull(timeoutMs) {
            synchronized(okayLock) {
                while (!okayReceived) okayLock.wait()
                okayReceived
            }
        }
        return ok ?: false
    }

    internal fun updateRemoteId(id: Int) {
        remoteIdInternal = id
    }

    override val remoteId: Int get() = remoteIdInternal

    override val isClosed: Boolean get() = closeReceived || closedLocal

    /** readLoop 投递 WRTE payload */
    internal suspend fun deliverPayload(payload: ByteArray) {
        if (isClosed) return
        readChannel.send(payload)
    }

    /** readLoop 收到对端 OKAY */
    internal fun deliverOkay() {
        synchronized(okayLock) {
            okayReceived = true
            okayLock.notifyAll()
        }
    }

    /** readLoop 收到对端 CLSE */
    internal fun deliverClose() {
        closeReceived = true
        runCatching { readChannel.close() }
        synchronized(okayLock) { okayLock.notifyAll() }
    }

    // ---------- AdbStream interface ----------

    override suspend fun write(data: ByteArray): Result<Unit> {
        if (isClosed) return Result.failure("stream closed")
        return try {
            synchronized(okayLock) { okayReceived = false }

            val pkt = AdbPacket.wrte(localId, remoteId, data)
            connection.writePacket(pkt)

            withTimeoutOrNull(30_000L) {
                synchronized(okayLock) {
                    while (!okayReceived && !closeReceived) okayLock.wait()
                }
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t.message ?: t.javaClass.simpleName, t)
        }
    }

    override suspend fun read(len: Int): ByteArray {
        if (closeReceived) return ByteArray(0)
        return try {
            val chunk = readChannel.receive()
            if (chunk.size > len) chunk.copyOf(len) else chunk
        } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            ByteArray(0)
        }
    }

    override suspend fun awaitClose() {
        if (closeReceived) return
        try {
            for (p in readChannel) { /* discard */ }
        } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
        }
    }

    /**
     * 本端关闭 stream。非 suspend —— AutoCloseable 签名。
     * 异步 fire-and-forget 发一个 CLSE（因为 writePacket 是 suspend）。
     */
    override fun close() {
        if (closedLocal || closeReceived) return
        closedLocal = true
        runCatching { readChannel.close() }
        synchronized(okayLock) { okayLock.notifyAll() }

        runCatching {
            val pkt = AdbPacket.clse(localId, remoteId)
            connection.launch {
                runCatching { connection.writePacket(pkt) }
            }
        }
    }
}
