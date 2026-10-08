package com.dislopik.ntrealy.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NTRPacketTest {

    @Test
    fun `parses a header from the reference byte layout`() {
        val raw = ByteArray(84)
        raw.writeIntLE(0, 0x12345678)
        raw.writeIntLE(4, 3000)
        raw.writeIntLE(8, 0)
        raw.writeIntLE(12, 901)
        raw.writeIntLE(16, (1 shl 8) or 4)
        raw.writeIntLE(20, 70)
        raw.writeIntLE(24, (16 * 2) shl 16)

        val header = NTRPacketHeader.parse(raw)

        assertEquals(3000, header.seq)
        assertEquals(0, header.type)
        assertEquals(901, header.cmd)
        assertEquals((1 shl 8) or 4, header.arg(0))
        assertEquals(70, header.arg(1))
        assertEquals((16 * 2) shl 16, header.arg(2))
        assertEquals(0, header.exdataLen)
    }

    @Test
    fun `encode produces exactly the header NTR expects`() {
        val encoded = NTRPacketHeader.encode(
            seq = 3000,
            cmd = 901,
            args = intArrayOf((1 shl 8) or 4, 70, (16 * 2) shl 16),
        )

        assertEquals(84, encoded.size)
        assertEquals(0x78, encoded[0].toInt() and 0xFF)
        assertEquals(0x56, encoded[1].toInt() and 0xFF)
        assertEquals(0x34, encoded[2].toInt() and 0xFF)
        assertEquals(0x12, encoded[3].toInt() and 0xFF)
        assertEquals(3000, encoded.readIntLE(4))
        assertEquals(0, encoded.readIntLE(8))
        assertEquals(901, encoded.readIntLE(12))
        assertEquals((1 shl 8) or 4, encoded.readIntLE(16))
        assertEquals(70, encoded.readIntLE(20))
        assertEquals((16 * 2) shl 16, encoded.readIntLE(24))
        assertEquals(0, encoded.readIntLE(80))
    }

    @Test
    fun `encode derive type 1 when exdata is present`() {
        val encoded = NTRPacketHeader.encode(seq = 1, cmd = 0, exdataLen = 4)
        assertEquals(1, encoded.readIntLE(8))
    }

    @Test
    fun `parse rejects a wrong magic number`() {
        val raw = ByteArray(84)
        raw.writeIntLE(0, 0xDEADBEEF.toInt())
        assertThrows(IllegalArgumentException::class.java) { NTRPacketHeader.parse(raw) }
    }

    @Test
    fun `parse rejects a truncated header`() {
        assertThrows(IllegalArgumentException::class.java) {
            NTRPacketHeader.parse(ByteArray(83))
        }
    }

    @Test
    fun `remote play packet carries the reference argument layout`() {
        val packet = buildRemotePlayPacket(
            quality = 70,
            priority = 4,
            priorityIsTop = true,
            qos = 16,
        )
        assertEquals(901, packet.cmd)
        assertEquals(3000, packet.seq)
        assertEquals(0, packet.type)
        assertEquals((1 shl 8) or 4, packet.arg(0))
        assertEquals(70, packet.arg(1))
        assertEquals((16 * 2) shl 16, packet.arg(2))
    }

    @Test
    fun `remote play packet pins NTR-HR to plain UDP + JPEG on the viewer port`() {
        val packet = buildRemotePlayPacket(quality = 70, priority = 4, priorityIsTop = true, qos = 16, viewerPort = 9001)
        assertEquals(1404036572, packet.arg(3))
        assertEquals(9001, packet.arg(4))
        assertEquals(0, packet.arg(5))
        assertEquals(0, packet.arg(6))
    }

    @Test
    fun `remote play packet defaults to viewer port 8001`() {
        val packet = buildRemotePlayPacket(quality = 70, priority = 4, priorityIsTop = true, qos = 16)
        assertEquals(8001, packet.arg(4))
    }

    @Test
    fun `remote play packet marks bottom screen as priority when asked`() {
        val packet = buildRemotePlayPacket(quality = 70, priority = 4, priorityIsTop = false, qos = 16)
        assertEquals(0 shl 8 or 4, packet.arg(0))
    }

    @Test
    fun `heartbeat packet has a bounded seq and command 0`() {
        val packet = buildHeartbeatPacket(42)
        assertEquals(0, packet.cmd)
        assertEquals(42, packet.seq)
        assertEquals(0, packet.type)
    }

    @Test
    fun `encode and parse round-trip`() {
        val args = IntArray(16) { it * 1000 + 7 }
        val original = NTRPacketHeader(seq = 12345, type = 1, cmd = 10, args = args, exdataLen = 2)
        val roundTripped = NTRPacketHeader.parse(
            NTRPacketHeader.encode(
                seq = original.seq,
                cmd = original.cmd,
                args = original.args,
                exdataLen = original.exdataLen,
                type = original.type,
            )
        )
        assertEquals(original, roundTripped)
        assertArrayEquals(args, roundTripped.args)
    }
}
