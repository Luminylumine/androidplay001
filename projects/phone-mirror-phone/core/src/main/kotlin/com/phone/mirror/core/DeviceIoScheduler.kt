package com.phone.mirror.core

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * 设备 I/O 调度器 —— 1:1 移植自 Windows C# DeviceIoScheduler.cs。
 *
 * 设计思想：
 *  - Metadata 读取（快速，小数据量）始终允许并发 2 个
 *  - Transfer（文件传输）根据传输类型限流：USB OTG 可到 2，TCP/WiFi 严格限制为 1
 *  - ThumbBatch（缩略图批处理）同 Transfer：USB=2, TCP=1
 *
 * Kotlin 中使用 [Semaphore] 替代 C# 的 SemaphoreSlim，保持 acquire/release 语义。
 * [Semaphore.acquire] / [Semaphore.release] 都是挂起友好的。
 */
class DeviceIoScheduler(
    /** 当前连接的传输类型，影响 Transfer/ThumbBatch 的并发度 */
    val transportType: TransportType,
) {
    enum class TransportType { USB, TCP }

    // 所有设备共享的 Metadata 信号量（固定并发度 2）
    private val metadataLimiter = Semaphore(2)

    // 每个 DeviceIoScheduler 实例的 Transfer / ThumbBatch 信号量
    private val transferLimiter = Semaphore(permitsForTransfer(transportType))
    private val thumbBatchLimiter = Semaphore(permitsForThumbBatch(transportType))

    companion object {
        /** Metadata 并发度固定为 2，与传输类型无关 */
        private const val METADATA_PERMITS = 2

        /** Transfer 并发度：USB=2, TCP=1 */
        private fun permitsForTransfer(type: TransportType): Int = when (type) {
            TransportType.USB -> 2
            TransportType.TCP -> 1
        }

        /** ThumbBatch 并发度：USB=2, TCP=1 */
        private fun permitsForThumbBatch(type: TransportType): Int = when (type) {
            TransportType.USB -> 2
            TransportType.TCP -> 1
        }
    }

    /** 执行 Metadata 工作（协程挂起等待槽位） */
    suspend fun <T> withMetadata(block: suspend () -> T): T {
        metadataLimiter.acquire()
        return try {
            block()
        } finally {
            metadataLimiter.release()
        }
    }

    /** 执行文件传输工作 */
    suspend fun <T> withTransfer(block: suspend () -> T): T {
        transferLimiter.acquire()
        return try {
            block()
        } finally {
            transferLimiter.release()
        }
    }

    /** 执行缩略图批处理工作 */
    suspend fun <T> withThumbBatch(block: suspend () -> T): T {
        thumbBatchLimiter.acquire()
        return try {
            block()
        } finally {
            thumbBatchLimiter.release()
        }
    }
}
