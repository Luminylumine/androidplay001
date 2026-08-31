package com.phone.mirror.transport.adb.core

import com.phone.mirror.core.Result
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ADB stream 真实实现。
 *
 * 每个 stream 有一个 [localId] (host 侧) / [remoteId] (device 侧)，
 * 通过这两个 ID 在 [AdbConnectionImpl.readLoop] 做路由。
 *
 * ## 接收端
 * 用 [readChannel] 把后台 readLoop 收到的 WRTE payload 投递给调用方的 [read]。
 * 使用容量 256 的 BufferedChannel —— 足够容纳 shell 输出（每个 WRTE 最多 1 MiB，
 * 但实际 shell 输出一般是 KB 级 chunk），避免 readLoop 被 block 而饿死其他 stream。
 *
 * ## OKAY flow control
 * 收到对端 OKAY 有两个用途：
 *  1. OPEN 后的首次 OKAY —— 把 device_local_id 存到 stream 的 remoteId
 *  2. 每次我们 write() 后对端 OKAY —— 唤醒 write() 里正在等待的协程，允许下一次 write
 *
 * MVP 简化：每次 write 后都等对端 OKAY 再返回，不做精确 window 追踪。
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

    /** Java object 用于 synchronized wait/notify —— 不能用 kotlinx Mutex，因为 awaitOkay 是非 suspend wait */
    private val okayLock = Any()

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

    /** readLoop 投递 WRTE payload —— suspend，channel 满了会背压 readLoop */
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
        // 确保 channel 关闭，让 read() 立即返回空
        runCatching { readChannel.close() }
        synchronized(okayLock) { okayLock.notifyAll() }
    }

    // ---------- AdbStream interface ----------

    override suspend fun write(data: ByteArray): Result<Unit> {
        if (isClosed) return Result.failure("stream closed")
        return runCatchingResult {
            // 简单 flow-control: 发 WRTE 前先 reset okayReceived，然后等对端 OKAY 再返回
            synchronized(okayLock) { okayReceived = false }

            val pkt = AdbPacket.wrte(localId, remoteId, data)
            connection.writePacket(pkt)

            // 等对端 OKAY —— 或 CLSE (stream 被关)
            withTimeoutOrNull(30_000L) {
                synchronized(okayLock) {
                    while (!okayReceived && !closeReceived) okayLock.wait()
                }
            }
            Result.success(Unit)
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
            // drain 所有剩余 payload
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

        // 异步发 CLSE —— 不阻塞
        runCatching {
            // AdbConnectionImpl 是 CoroutineScope，我们可以 launch
            val pkt = AdbPacket.clse(localId, remoteId)
            connection.launch {
                runCatching { connection.writePacket(pkt) }
            }
        }
    }

    private inline fun <T> runCatchingResult(block: () -> Result<T>): Result<T> = try {
        block()
    } catch (t: Throwable) {
        Result.failure(t.message ?: t.javaClass.simpleName, t)
    }
}
