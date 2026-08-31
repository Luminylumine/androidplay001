package com.phone.mirror.data.gallery

/**
 * GalleryRowParser —— **1:1 关键移植自 Windows C# GalleryRowParser.cs**。
 *
 * 为什么不能简单按逗号 split？
 *  - MediaStore `_data` 字段可能包含逗号（例如 `/storage/emulated/0/Pictures/summer, 2024/photo.jpg`）
 *  - DISPLAY_NAME、ALBUM 等字段也可能包含逗号
 *  - Windows 实现里把 `_data` 放在最后一列来简化处理 —— 这样前面字段用列名前缀分割即可，
 *    `_data` 剩下的整段都归它，不再需要转义
 *
 * 因此本解析器采用 **"下一列名 = "** 边界算法：
 *  1. 预定义列顺序（确保 `_data` 在末尾）
 *  2. 从左到右扫描：找下一个 `"<nextColumnName>="` 字符串出现位置
 *  3. 当前列值 = 从当前位置到下一列名前的所有字符
 *  4. 最后一列（_data） = 剩余全部
 *
 * 这样无论字段内包含多少逗号、等号都不会破坏解析。
 */
object GalleryRowParser {

    /**
     * MediaStore query projection —— **_data 必须在最后**。
     * 顺序直接影响解析算法。
     */
    val PROJECTION_FIELDS: List<String> = listOf(
        "_ID",
        "_SIZE",
        "DATE_MODIFIED",
        "MIME_TYPE",
        "WIDTH",
        "HEIGHT",
        "DURATION",
        "TITLE",
        "BUCKET_DISPLAY_NAME",
        "_DATA",            // ← 必须在最末尾！
    )

    /**
     * 把设备上 shell cmd media query 输出的一整行 text 解析为 [GalleryItem]。
     *
     * 单行样例（shell 里执行 `content query --uri content://media/external/images/media ... --projection ...`）：
     * ```
     * Row: 0 _ID=123 _SIZE=1048576 DATE_MODIFIED=1714000000 MIME_TYPE=image/jpeg WIDTH=4096 HEIGHT=3072 DURATION=0 TITLE=vacation BUCKET_DISPLAY_NAME=Vacation, 2024 _DATA=/storage/emulated/0/Pictures/Vacation, 2024/IMG_0001.jpg
     * ```
     */
    fun parse(rowText: String, deviceId: String): GalleryItem? {
        // 去掉前缀 "Row: N "（shell content query 输出格式）
        val line = rowText.trimStart()
        val payload = if (line.startsWith("Row:")) {
            // 跳过 "Row: 0 " 前缀 —— 找到第一个空格后的内容
            val firstSpace = line.indexOf(' ')
            val afterRow = line.substring(firstSpace + 1)
            val idx = afterRow.indexOf(' ')
            if (idx >= 0) afterRow.substring(idx + 1) else afterRow
        } else {
            line
        }

        val parsed = LinkedHashMap<String, String>()
        var cursor = 0
        val len = payload.length

        for ((i, col) in PROJECTION_FIELDS.withIndex()) {
            val searchKey = "$col="
            val keyIdx = payload.indexOf(searchKey, cursor)
            if (keyIdx < 0) return null  // 列缺失，整行放弃

            // 当前列值起点
            val valueStart = keyIdx + searchKey.length

            // 当前列值终点：
            //  - 若不是最后一列 → 找下一个 PROJECTION_FIELDS[i+1] 的 "=" 位置
            //  - 若是最后一列（_data）→ 直接到行尾
            val valueEnd = if (i < PROJECTION_FIELDS.lastIndex) {
                val nextCol = PROJECTION_FIELDS[i + 1]
                val nextSearch = "$nextCol="
                val nextIdx = payload.indexOf(nextSearch, valueStart)
                if (nextIdx < 0) len else nextIdx
            } else {
                len // 最后一列吃到行尾
            }

            val value = payload.substring(valueStart, valueEnd).trim()
            parsed[col] = value
            cursor = valueEnd
        }

        return GalleryItem(
            mediaId = parsed["_ID"]?.toLongOrNull() ?: return null,
            data = parsed["_DATA"] ?: return null,
            size = parsed["_SIZE"]?.toLongOrNull() ?: 0L,
            dateModified = parsed["DATE_MODIFIED"]?.toLongOrNull() ?: 0L,
            deviceId = deviceId,
            mimeType = parsed["MIME_TYPE"] ?: "",
            width = parsed["WIDTH"]?.toIntOrNull() ?: 0,
            height = parsed["HEIGHT"]?.toIntOrNull() ?: 0,
            durationMs = parsed["DURATION"]?.toLongOrNull() ?: 0L,
            title = parsed["TITLE"] ?: "",
            albumName = parsed["BUCKET_DISPLAY_NAME"] ?: "",
            tombstoneTs = null,
        )
    }
}
