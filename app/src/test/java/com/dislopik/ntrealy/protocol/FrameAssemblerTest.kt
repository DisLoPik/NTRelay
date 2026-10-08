package com.dislopik.ntrealy.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameAssemblerTest {

    private fun datagram(
        frame: Int,
        screen: ScreenId,
        packet: Int,
        isLast: Boolean,
        payload: ByteArray,
    ): ByteArray {
        val screenNibble = if (screen == ScreenId.TOP) 1 else 0
        val flags = (if (isLast) 0x10 else 0x00) or screenNibble
        val out = ByteArray(4 + payload.size)
        out[0] = frame.toByte()
        out[1] = flags.toByte()
        out[2] = 0
        out[3] = packet.toByte()
        payload.copyInto(out, 4)
        return out
    }

    private fun chunk(jpeg: ByteArray, size: Int = 3): List<ByteArray> =
        jpeg.toList().chunked(size).map { it.toByteArray() }

    @Test
    fun `single-packet frame is returned immediately`() {
        val assembler = FrameAssembler()
        val frame = assembler.accept(datagram(0, ScreenId.TOP, 0, isLast = true, payload = byteArrayOf(1, 2, 3)))

        assertEquals(ScreenId.TOP, frame?.screen)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame?.jpeg)
    }

    @Test
    fun `multi-packet frame is stitched in order`() {
        val assembler = FrameAssembler()
        val jpeg = ByteArray(10) { it.toByte() }
        val parts = chunk(jpeg, 4)

        assertNull(assembler.accept(datagram(0, ScreenId.TOP, 0, isLast = false, payload = parts[0])))
        assertNull(assembler.accept(datagram(0, ScreenId.TOP, 1, isLast = false, payload = parts[1])))
        val complete = assembler.accept(datagram(0, ScreenId.TOP, 2, isLast = true, payload = parts[2]))

        assertEquals(ScreenId.TOP, complete?.screen)
        assertArrayEquals(jpeg, complete?.jpeg)
    }

    @Test
    fun `top and bottom screens assemble independently`() {
        val assembler = FrameAssembler()
        val topJpeg = ByteArray(6) { (it + 100).toByte() }
        val bottomJpeg = ByteArray(6) { (it + 200).toByte() }

        assertNull(assembler.accept(datagram(0, ScreenId.TOP, 0, isLast = false, payload = topJpeg.copyOfRange(0, 3))))
        assertNull(assembler.accept(datagram(0, ScreenId.BOTTOM, 0, isLast = false, payload = bottomJpeg.copyOfRange(0, 3))))

        val bottom = assembler.accept(datagram(0, ScreenId.BOTTOM, 1, isLast = true, payload = bottomJpeg.copyOfRange(3, 6)))
        val top = assembler.accept(datagram(0, ScreenId.TOP, 1, isLast = true, payload = topJpeg.copyOfRange(3, 6)))

        assertEquals(ScreenId.BOTTOM, bottom?.screen)
        assertArrayEquals(bottomJpeg, bottom?.jpeg)
        assertArrayEquals(topJpeg, top?.jpeg)
    }

    @Test
    fun `out-of-order packet flushes and resyncs instead of corrupting the frame`() {
        val assembler = FrameAssembler()
        assertNull(assembler.accept(datagram(0, ScreenId.TOP, 0, isLast = false, payload = byteArrayOf(9, 9))))

        val complete = assembler.accept(datagram(1, ScreenId.TOP, 0, isLast = true, payload = byteArrayOf(7, 8)))

        assertArrayEquals(byteArrayOf(7, 8), complete?.jpeg)
    }

    @Test
    fun `lost packet drops the frame and resumes at the next packet 0`() {
        val assembler = FrameAssembler()
        assertNull(assembler.accept(datagram(0, ScreenId.TOP, 0, isLast = false, payload = byteArrayOf(1))))
        assertNull(assembler.accept(datagram(0, ScreenId.TOP, 2, isLast = false, payload = byteArrayOf(3))))
        assertNull(assembler.accept(datagram(0, ScreenId.TOP, 3, isLast = true, payload = byteArrayOf(4))))

        val next = assembler.accept(datagram(1, ScreenId.TOP, 0, isLast = true, payload = byteArrayOf(5)))
        assertArrayEquals(byteArrayOf(5), next?.jpeg)
    }

    @Test
    fun `frame joined mid-way is skipped until its next packet 0`() {
        val assembler = FrameAssembler()
        assertNull(assembler.accept(datagram(7, ScreenId.TOP, 4, isLast = false, payload = byteArrayOf(1))))
        assertNull(assembler.accept(datagram(7, ScreenId.TOP, 5, isLast = true, payload = byteArrayOf(2))))

        val next = assembler.accept(datagram(8, ScreenId.TOP, 0, isLast = true, payload = byteArrayOf(3)))
        assertArrayEquals(byteArrayOf(3), next?.jpeg)
    }

    @Test
    fun `screen is read from bit 0 even with NTR-HR full-width flags`() {
        val assembler = FrameAssembler()
        val raw = datagram(0, ScreenId.TOP, 0, isLast = true, payload = byteArrayOf(1))
        raw[1] = (0x10 or 0x07).toByte()

        assertEquals(ScreenId.TOP, assembler.accept(raw)?.screen)
    }

    @Test
    fun `frames increment normally across the same screen`() {
        val assembler = FrameAssembler()
        val first = assembler.accept(datagram(0, ScreenId.BOTTOM, 0, isLast = true, payload = byteArrayOf(1)))
        val second = assembler.accept(datagram(1, ScreenId.BOTTOM, 0, isLast = true, payload = byteArrayOf(2)))

        assertArrayEquals(byteArrayOf(1), first?.jpeg)
        assertArrayEquals(byteArrayOf(2), second?.jpeg)
    }

    @Test
    fun `ignores datagrams too small to carry the header`() {
        val assembler = FrameAssembler()
        assertNull(assembler.accept(ByteArray(0)))
        assertNull(assembler.accept(ByteArray(3)))
    }

    @Test
    fun `reset drops partially assembled state`() {
        val assembler = FrameAssembler()
        assertNull(assembler.accept(datagram(0, ScreenId.TOP, 0, isLast = false, payload = byteArrayOf(1))))
        assembler.reset()

        val complete = assembler.accept(datagram(5, ScreenId.TOP, 0, isLast = true, payload = byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(2), complete?.jpeg)
    }
}
