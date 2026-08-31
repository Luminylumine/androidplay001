package com.phone.mirror.transport.adb.core

/**
 * ADB 协议数据包。
 *
 * ADB 协议头部为 24 字节：
 * ```
 * uint32 command
 * uint32 arg0
 * uint32 arg1
 * uint32 payload_length
 * uint32 checksum
 * uint32 magic            (command ^ 0xFFFFFFFF)
 * ```
 */
data class AdbPacket(
    /** 4 字符命令字，如 "CNXN", "AUTH", "OPEN", "OKAY", "WRTE", "CLSE" */
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0),
) {
    /** payload 的长度 */
    val length: Int get() = payload.size

    /** 校验值（所有 payload 字节之和 mod 2^32） */
    val checksum: Int = payload.sumOf { it.toInt() }

    /** magic = command XOR 0xFFFFFFFF */
    val magic: Int get() = command xor 0xFFFF_FFFF.toInt()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbPacket) return false
        return command == other.command &&
            arg0 == other.arg0 &&
            arg1 == other.arg1 &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        // 常用命令字
        const val CMD_CNXN = 0x4e584e43  // "CNXN"
        const val CMD_AUTH = 0x48545541  // "AUTH"
        const val CMD_OPEN = 0x4e45504f  // "OPEN"
        const val CMD_OKAY = 0x59414b4f  // "OKAY"
        const val CMD_WRTE = 0x45545257  // "WRTE"
        const val CMD_CLSE = 0x45534c43  // "CLSE"
        const val CMD_SYNC = 0x434e5953  // "SYNC"
    }
}
