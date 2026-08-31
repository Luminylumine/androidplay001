package com.phone.mirror.transport.adb.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB 24-byte header + payload packet —— wire protocol 精确实现。
 *
 * Header 布局 (全部 uint32 LE):
 * ```
 * 0x00  4  command       e.g. CNXN=0x4E584E43, AUTH=0x48545541
 * 0x04  4  arg0          context-dependent
 * 0x08  4  arg1          context-dependent
 * 0x0C  4  data_length   payload 字节数
 * 0x10  4  data_check    payload checksum (所有 unsigned byte 之和 mod 2^32)
 * 0x14  4  magic         command XOR 0xFFFFFFFF
 * ```
 * 然后紧跟 `data_length` 字节的 payload。
 *
 * Checksum 规则: 只算 payload 的每个 unsigned byte 之和。
 * Magic 规则: command ^ 0xFFFFFFFF (uint32)。
 */
class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = ByteArray(0),
) {
    /** 总长度 (header + payload) */
    val wireSize: Int get() = 24 + payload.size

    /** 这个 packet 的 checksum (所有 payload unsigned byte 之和) */
    val checksum: Int get() {
        var sum = 0
        for (b in payload) {
            sum += (b.toInt() and 0xFF)
        }
        return sum
    }

    /** magic = command ^ 0xFFFFFFFF */
    val magic: Int get() = command xor 0xFFFF_FFFF.toInt()

    /**
     * 编码为 wire bytes (24-byte header + payload)
     */
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(wireSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(checksum)
        buf.putInt(magic)
        buf.put(payload)
        return buf.array()
    }

    /**
     * 以 human-readable 形式打印，用于 debug。
     */
    override fun toString(): String {
        val cmdName = when (command) {
            AdbCommand.CNXN -> "CNXN"
            AdbCommand.AUTH -> "AUTH"
            AdbCommand.OPEN -> "OPEN"
            AdbCommand.OKAY -> "OKAY"
            AdbCommand.CLSE -> "CLSE"
            AdbCommand.WRTE -> "WRTE"
            AdbCommand.SYNC -> "SYNC"
            AdbCommand.STLS -> "STLS"
            else -> String(AdbCommand.commandBytes(command).reversedArray())
        }
        val preview = if (payload.isNotEmpty()) {
            val text = String(payload, Charsets.UTF_8)
            "\"${text.replace("\n", "\\n").take(40)}\""
        } else {
            "empty"
        }
        return "AdbPacket($cmdName arg0=${arg0.toHexString()} arg1=${arg1.toHexString()} len=${payload.size} payload=$preview)"
    }

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
        /**
         * 从 wire bytes 解码一个 packet。
         * [header] 必须至少 24 字节。[extraPayload] 如果已经读了部分 payload 可以传进来。
         */
        fun decode(header: ByteArray, extraPayload: ByteArray = ByteArray(0)): AdbPacket {
            require(header.size >= 24) { "header must be >= 24 bytes, got ${header.size}" }
            val buf = ByteBuffer.wrap(header, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
            val command = buf.int
            val arg0 = buf.int
            val arg1 = buf.int
            val dataLen = buf.int
            val checksum = buf.int
            val magic = buf.int

            // 验证 magic
            val expectedMagic = command xor 0xFFFF_FFFF.toInt()
            require(magic == expectedMagic) {
                "bad magic: expected ${expectedMagic.toHexString()}, got ${magic.toHexString()}"
            }

            val payload = if (extraPayload.size >= dataLen) {
                extraPayload.copyOf(dataLen)
            } else {
                extraPayload + ByteArray(dataLen - extraPayload.size) // 后面由调用方填入
            }

            val pkt = AdbPacket(command, arg0, arg1, payload)

            // 验证 checksum (如果 payload 已完整)
            if (payload.size == dataLen) {
                val expectedChecksum = pkt.checksum
                require(checksum == expectedChecksum) {
                    "bad checksum: expected ${expectedChecksum.toHexString()}, got ${checksum.toHexString()}"
                }
            }

            return pkt
        }

        /** 预计算的 CNXN "host::" payload —— Phase 0 CNXN 发送 */
        val HOST_BANNER_PAYLOAD = "host::".toByteArray(Charsets.UTF_8)

        /** 快速构造 CNXN packet */
        fun cnxn(
            version: Int = AdbCommand.VERSION_CURRENT,
            maxData: Int = AdbCommand.MAX_DATA,
            banner: ByteArray = HOST_BANNER_PAYLOAD,
        ): AdbPacket = AdbPacket(AdbCommand.CNXN, version, maxData, banner)

        /** 快速构造 AUTH/SIGNATURE */
        fun authSignature(sig256: ByteArray): AdbPacket {
            require(sig256.size == 256) { "signature must be 256 bytes, got ${sig256.size}" }
            return AdbPacket(AdbCommand.AUTH, AdbCommand.AUTH_SIGNATURE, 0, sig256)
        }

        /** 快速构造 AUTH/RSAPUBLICKEY */
        fun authPublicKey(pub: ByteArray): AdbPacket =
            AdbPacket(AdbCommand.AUTH, AdbCommand.AUTH_RSAPUBLICKEY, 0, pub)

        /** 快速构造 OPEN */
        fun open(service: String, localId: Int): AdbPacket {
            val payload = (service + "\u0000").toByteArray(Charsets.UTF_8)
            return AdbPacket(AdbCommand.OPEN, localId, 0, payload)
        }

        /** 快速构造 OKAY */
        fun okay(localId: Int, remoteId: Int): AdbPacket =
            AdbPacket(AdbCommand.OKAY, localId, remoteId, ByteArray(0))

        /** 快速构造 WRTE */
        fun wrte(localId: Int, remoteId: Int, payload: ByteArray): AdbPacket =
            AdbPacket(AdbCommand.WRTE, localId, remoteId, payload)

        /** 快速构造 CLSE */
        fun clse(localId: Int, remoteId: Int): AdbPacket =
            AdbPacket(AdbCommand.CLSE, localId, remoteId, ByteArray(0))

        private fun Int.toHexString(): String = "0x${this.toUInt().toString(16).padStart(8, '0')}"
    }
}
