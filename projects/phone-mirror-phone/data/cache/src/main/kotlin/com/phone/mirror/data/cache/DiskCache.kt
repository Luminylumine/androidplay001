package com.phone.mirror.data.cache

import android.content.Context
import com.phone.mirror.core.Result
import com.phone.mirror.core.runResult
import java.io.File
import java.security.MessageDigest

/**
 * 会话级磁盘缓存。完全移植自 Windows C# DiskCache。
 *
 * 缓存 key 生成规则（1:1 移植）：
 * ```
 * raw = deviceId + "|" + mediaId + "|" + size + "|" + dateModified
 * key = SHA256(raw).toHex()
 * ```
 *
 * 用途：
 *  - 临时目录（session temp dir）：存放 ADB pull 下来的文件
 *  - 512px 规范缩略图 JPEG 存储（用于 Gallery 的快速展示）
 */
class DiskCache(context: Context) {

    /** 本次会话独立的缓存根目录 */
    private val rootDir: File = context.cacheDir.resolve("phone-mirror-cache").apply {
        if (!exists()) mkdirs()
    }

    /** 缩略图子目录（规范 512px JPEG） */
    val thumbDir: File = rootDir.resolve("thumbs").apply {
        if (!exists()) mkdirs()
    }

    /** 会话临时目录，用于 ADB pull 等临时文件 */
    val tempDir: File = rootDir.resolve("temp").apply {
        if (!exists()) mkdirs()
    }

    // —— Key 生成 ——

    /** 生成缓存 key。deviceId/mediaId 为 String，size 和 dateModified 用于避免缓存穿透 */
    fun cacheKey(
        deviceId: String,
        mediaId: Long,
        size: Long,
        dateModified: Long,
    ): String {
        val raw = "$deviceId|$mediaId|$size|$dateModified"
        return sha256Hex(raw)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // —— 缩略图读写 ——

    /** 返回某个缓存 key 对应的缩略图文件路径（无论是否存在） */
    fun thumbFile(key: String): File = thumbDir.resolve("$key.jpg")

    /** 缩略图是否已缓存 */
    fun hasThumb(key: String): Boolean = thumbFile(key).exists() && thumbFile(key).length() > 0

    /** 写入 512px 缩略图 */
    suspend fun writeThumb(key: String, thumbnailBytes: ByteArray): Result<File> {
        val f = thumbFile(key)
        return runResult {
            f.parentFile?.mkdirs()
            f.writeBytes(thumbnailBytes)
            f
        }
    }

    /** 读取缩略图（若不存在返回 null） */
    fun readThumb(key: String): ByteArray? = thumbFile(key).takeIf { it.exists() }?.readBytes()

    // —— 清理 ——

    /** 清空本次会话缓存 */
    fun clear() {
        rootDir.deleteRecursively()
        rootDir.mkdirs()
        thumbDir.mkdirs()
        tempDir.mkdirs()
    }
}
