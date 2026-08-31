package com.phone.mirror.transport.adb.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB 协议层精确向量测试 —— 用 GPT 给定的已知向量验证我们的 wire 编码正确。
 *
 * 这些测试不依赖 Android API (纯 JVM)，可以在 Android Studio 或命令行直接跑。
 *
 * 关键参考: AOSP protocol.h / adb.h / GPT Phase0-2 文档 Q2.7
 */
class AdbPacketTest {

    // ---------------- command constants ----------------

    @Test fun `command constants match AOSP hex`() {
        assertEquals(0x4E584E43, AdbCommand.CNXN)
        assertEquals(0x48545541, AdbCommand.AUTH)
        assertEquals(0x4E45504F, AdbCommand.OPEN)
        assertEquals(0x59414B4F, AdbCommand.OKAY)
        assertEquals(0x45534C43, AdbCommand.CLSE)
        assertEquals(0x45545257, AdbCommand.WRTE)
        assertEquals(0x434E5953, AdbCommand.SYNC)
    }

    @Test fun `magic = command xor 0xFFFFFFFF`() {
        // CNXN magic = 0x4E584E43 xor 0xFFFFFFFF = 0xB1A7B1BC
        assertEquals(0xB1A7B1BC.toInt(), AdbCommand.magic(AdbCommand.CNXN))
        // 验证：GPT 说 magic 是 BCB1A7B1？让我直接算
        // 0x4E584E43 = 0100 1110 0101 1000 0100 1110 0100 0011
        // xor 0xFFFFFFFF = 1011 0001 1010 0111 1011 0001 1011 1100
        // = 0xB1A7B1BC —— 不是 BCB1A7B1
        // 但 GPT Phase0-2 Q2.7 里说 "magic=BCB1A7B1"，可能是我记错了，让 GPT 答案为准？
        // 让我们直接算...
        val cmd = 0x4E584E43
        val magic = cmd xor 0xFFFF_FFFF.toInt()
        // 在 Kotlin Int (32-bit signed) 上，0xFFFFFFFF.toInt() = -1，xor 后得到正确值
        assertEquals("0xb1a7b1bc", magic.toUInt().toString(16))
    }

    // ---------------- CNXN test vector ----------------

    @Test fun `CNXN packet: host:: banner checksum = 562`() {
        // payload = "host::" (6 bytes, ASCII)
        // h=104, o=111, s=115, t=116, :=58, :=58
        // sum = 104+111+115+116+58+58 = 562 ✓
        val payload = "host::".toByteArray(Charsets.UTF_8)
        assertEquals(6, payload.size)

        val pkt = AdbPacket(
            command = AdbCommand.CNXN,
            arg0 = AdbCommand.VERSION_CURRENT,  // 0x01000001
            arg1 = AdbCommand.MAX_DATA,          // 0x00100000
            payload = payload,
        )

        // Checksum —— 所有 payload unsigned byte 之和
        assertEquals(562, pkt.checksum)

        // Magic
        assertEquals(0xB1A7B1BC.toInt(), pkt.magic)
    }

    @Test fun `CNXN wire encoding 24-byte header exact layout`() {
        val pkt = AdbPacket.cnxn()
        val wire = pkt.encode()

        assertEquals(24 + 6, wire.size) // 24 header + 6 "host::"

        val header = ByteBuffer.wrap(wire, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbCommand.CNXN, header.int)
        assertEquals(0x01000001, header.int)   // version
        assertEquals(0x00100000, header.int)   // max data
        assertEquals(6, header.int)            // data_length
        assertEquals(562, header.int)          // checksum
        assertEquals(0xB1A7B1BC.toInt(), header.int) // magic

        // payload 是 "host::" UTF-8
        val payload = wire.copyOfRange(24, wire.size)
        assertArrayEquals("host::".toByteArray(Charsets.UTF_8), payload)
    }

    @Test fun `decode is inverse of encode`() {
        val orig = AdbPacket(
            command = AdbCommand.OPEN,
            arg0 = 12345,
            arg1 = 0,
            payload = "shell:id\0".toByteArray(Charsets.UTF_8),
        )
        val wire = orig.encode()
        val decoded = AdbPacket.decode(wire.copyOfRange(0, 24), wire.copyOfRange(24, wire.size))

        assertEquals(orig.command, decoded.command)
        assertEquals(orig.arg0, decoded.arg0)
        assertEquals(orig.arg1, decoded.arg1)
        assertArrayEquals(orig.payload, decoded.payload)
    }

    // ---------------- AUTH token packet ----------------

    @Test fun `AUTH/TOKEN arg0 = 1, 20 bytes payload`() {
        val token = ByteArray(20) { it.toByte() }
        val pkt = AdbPacket(AdbCommand.AUTH, AdbCommand.AUTH_TOKEN, 0, token)
        assertEquals(AdbCommand.AUTH_TOKEN, pkt.arg0)
        assertEquals(20, pkt.payload.size)
        assertEquals(0xFFFF_FFFF.toInt() xor AdbCommand.AUTH, pkt.magic)
    }

    // ---------------- OPEN service name must end with NUL ----------------

    @Test fun `OPEN payload = service + NUL terminator`() {
        val pkt = AdbPacket.open("shell:ls", localId = 1)
        val expected = "shell:ls\u0000".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, pkt.payload)
        assertEquals(AdbCommand.OPEN, pkt.command)
        assertEquals(1, pkt.arg0)
        assertEquals(0, pkt.arg1)
    }

    // ---------------- checksum for empty payload is 0 ----------------

    @Test fun `empty payload checksum is 0`() {
        val pkt = AdbPacket(AdbCommand.OKAY, 1, 2)
        assertEquals(0, pkt.checksum)
        val wire = pkt.encode()
        val header = ByteBuffer.wrap(wire, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
        header.int; header.int; header.int; header.int
        assertEquals(0, header.int) // checksum
    }
}
