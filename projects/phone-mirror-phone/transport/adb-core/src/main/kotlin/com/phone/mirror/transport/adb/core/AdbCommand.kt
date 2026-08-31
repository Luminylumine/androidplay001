package com.phone.mirror.transport.adb.core

/**
 * ADB protocol command 常量 —— 从 AOSP wire protocol 精确值抄出。
 *
 * 所有 magic = command xor 0xFFFFFFFF (uint32)
 * 所有 header 字段均为 **uint32 little-endian**
 *
 * 参考: packages/modules/adb/adb.h, protocol.h
 */
object AdbCommand {
    const val CNXN = 0x4E584E43  // "CNXN"  1314410051
    const val AUTH = 0x48545541  // "AUTH"  1213486401
    const val OPEN = 0x4E45504F  // "OPEN"  1313165391
    const val OKAY = 0x59414B4F  // "OKAY"  1497451343
    const val CLSE = 0x45534C43  // "CLSE"  1163086915
    const val WRTE = 0x45545257  // "WRTE"  1163154007
    const val SYNC = 0x434E5953  // "SYNC"  1129208147
    const val STLS = 0x534C5453  // "STLS"  1397511251

    /** AUTH arg0 子类型 */
    const val AUTH_TOKEN = 1        // device -> host: 20-byte SHA-1 token
    const val AUTH_SIGNATURE = 2    // host -> device: RSA-SHA1 256-byte signature
    const val AUTH_RSAPUBLICKEY = 3 // host -> device: base64 public key + NUL

    /** CNXN arg0: ADB protocol version */
    const val VERSION_LEGACY = 0x01000000
    const val VERSION_SKIP_CHECKSUM = 0x01000001
    const val VERSION_CURRENT = 0x01000001

    /** CNXN arg1: 我们宣布的 max payload —— 用 1 MiB */
    const val MAX_DATA = 0x00100000  // 1048576

    /** Legacy payload 上限 */
    const val MAX_DATA_LEGACY = 4096

    /**
     * command uint32 -> wire bytes (LE)
     */
    fun commandBytes(command: Int): ByteArray {
        return byteArrayOf(
            (command and 0xFF).toByte(),
            ((command ushr 8) and 0xFF).toByte(),
            ((command ushr 16) and 0xFF).toByte(),
            ((command ushr 24) and 0xFF).toByte(),
        )
    }

    /**
     * magic = command xor 0xFFFFFFFF (uint32 LE)
     */
    fun magic(command: Int): Int = command xor 0xFFFF_FFFF.toInt()
}
