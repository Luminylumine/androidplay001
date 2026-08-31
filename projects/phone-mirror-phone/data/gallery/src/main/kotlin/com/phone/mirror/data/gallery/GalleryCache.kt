package com.phone.mirror.data.gallery

/**
 * 基于 Magic Number 的文件格式嗅探器。**1:1 移植自 Windows C# GalleryCache.cs**。
 *
 * 为什么不依赖 MIME_TYPE？
 *  - 某些 Android OEM 错误报告 MIME_TYPE（尤其是 HEIC/AVIF）
 *  - 远端 pull 过程 MIME_TYPE 未就绪
 *  - 嗅探前 16 字节即可可靠区分主流图片格式
 */
object GalleryCache {

    /** 嗅探的最小字节数（足够覆盖所有 magic + ISO-BMFF brand box） */
    private const val SNIFF_MIN = 16

    enum class ImageFormat {
        JPEG, PNG, WEBP, HEIC, HEIF, AVIF, UNKNOWN;

        /** 标准 image/xxx MIME 字符串 */
        val mimeType: String get() = when (this) {
            JPEG  -> "image/jpeg"
            PNG   -> "image/png"
            WEBP  -> "image/webp"
            HEIC  -> "image/heic"
            HEIF  -> "image/heif"
            AVIF  -> "image/avif"
            UNKNOWN -> "application/octet-stream"
        }
    }

    /**
     * 嗅探文件前 N 字节，返回格式。
     *
     * 各格式 magic (与 Windows C# 字节对表完全一致)：
     *  - JPEG     : `FF D8 FF`
     *  - PNG      : `89 50 4E 47 0D 0A 1A 0A`
     *  - WEBP     : 字节 0..3 = "RIFF"，字节 8..11 = "WEBP"
     *  - HEIC/HEIF/AVIF (ISO-BMFF): Box 结构，[4, 8) = "ftyp"，[8, 12) = 4 字符 brand field
     *                支持的 brand：heic, heix, mif1, hevc, msf1, avif, avis
     */
    fun sniff(header: ByteArray): ImageFormat {
        if (header.size < SNIFF_MIN) return ImageFormat.UNKNOWN

        // —— JPEG ——
        if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
            return ImageFormat.JPEG
        }

        // —— PNG ——
        if (header.size >= 8 &&
            header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() &&
            header[3] == 0x47.toByte() &&
            header[4] == 0x0D.toByte() &&
            header[5] == 0x0A.toByte() &&
            header[6] == 0x1A.toByte() &&
            header[7] == 0x0A.toByte()
        ) {
            return ImageFormat.PNG
        }

        // —— WEBP ——
        if (header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() &&
            header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() &&
            header[11] == 'P'.code.toByte()
        ) {
            return ImageFormat.WEBP
        }

        // —— ISO-BMFF (HEIC/HEIF/AVIF) ——
        // Box 结构: size(4) | "ftyp"(4) | brand(4) | ...
        // 先找 "ftyp" 四字节出现位置（从 box 头跳过 size 字段）
        val ftypIdx = indexOfAscii(header, "ftyp".toByteArray())
        if (ftypIdx >= 0 && ftypIdx + 8 < header.size) {
            val brand = String(header, ftypIdx + 4, 4, Charsets.US_ASCII)
            return when (brand) {
                "heic", "heix" -> ImageFormat.HEIC
                "mif1", "hevc", "msf1" -> ImageFormat.HEIF
                "avif", "avis" -> ImageFormat.AVIF
                else -> ImageFormat.UNKNOWN
            }
        }

        return ImageFormat.UNKNOWN
    }

    /** 在 [buf] 中查找 ASCII 字节序列 [needle] 的出现位置 */
    private fun indexOfAscii(buf: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..(buf.size - needle.size)) {
            for (j in needle.indices) {
                if (buf[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
