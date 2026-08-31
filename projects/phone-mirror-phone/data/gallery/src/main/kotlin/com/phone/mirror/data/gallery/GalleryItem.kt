package com.phone.mirror.data.gallery

import java.text.SimpleDateFormat
import java.util.Date

/**
 * 相册条目。与 Windows C# GalleryItem 字段完全对应，用于 Room 持久化和 UI 展示。
 */
data class GalleryItem(
    /** Android MediaStore._ID  */
    val mediaId: Long,
    /** 完整文件路径 (MediaStore.MediaColumns.DATA) */
    val data: String,
    /** 文件大小 (bytes) */
    val size: Long,
    /** 最后修改时间 (unix seconds) */
    val dateModified: Long,
    /** 设备序列号 */
    val deviceId: String,
    /** MIME 类型 (image/jpeg, image/png, video/mp4 ...) */
    val mimeType: String,
    /** 宽 (像素) */
    val width: Int = 0,
    /** 高 (像素) */
    val height: Int = 0,
    /** 视频时长 (ms)；图片固定为 0 */
    val durationMs: Long = 0,
    /** 标题 (MediaStore.DISPLAY_NAME 去掉扩展名) */
    val title: String = "",
    /** 相册名 (来自 GalleryRowParser / MediaStore.BUCKET_DISPLAY_NAME) */
    val albumName: String = "",
    /** 如果 != null，说明在远端已被删除，下次同步应从 Room 剔除 */
    val tombstoneTs: Long? = null,
) {

    /**
     * 用于排序的 key：dateModified 倒序 → mediaId 倒序，保证稳定。
     * 值相同字段保证不同 item 不相等。
     */
    val sortKey: SortKey get() = SortKey(-dateModified, -mediaId)

    /** 按 albumName 分组的 key（空 albumName 归入 "未分类"） */
    val albumKey: String get() = albumName.ifBlank { "未分类" }

    /** 展示时间 (yyyy-MM-dd HH:mm) */
    val displayTime: String get() {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm")
        return df.format(Date(dateModified * 1000))
    }

    data class SortKey(val dateModifiedNeg: Long, val mediaIdNeg: Long) : Comparable<SortKey> {
        override fun compareTo(other: SortKey): Int {
            val d = dateModifiedNeg.compareTo(other.dateModifiedNeg)
            return if (d != 0) d else mediaIdNeg.compareTo(other.mediaIdNeg)
        }
    }
}
