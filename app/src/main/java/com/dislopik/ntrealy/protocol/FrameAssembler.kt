package com.dislopik.ntrealy.protocol

import java.io.ByteArrayOutputStream

/**
 * Stitches NTR's UDP datagrams back into complete JPEG frames, per screen.
 *
 * Datagram header: byte 0 = frame index, byte 1 = isLast (high nibble) and
 * screen (low nibble), byte 3 = packet index. The payload follows.
 */
class FrameAssembler {

    private val top = ScreenAssembler()
    private val bottom = ScreenAssembler()

    fun accept(datagram: ByteArray): AssembledFrame? {
        if (datagram.size < HEADER_SIZE) return null
        val frameIndex = datagram.readUByteAt(0)
        val flags = datagram.readUByteAt(1)
        val isLast = (flags and 0xF0) == 0x10
        val screen = ScreenId.fromFrameHeader(flags and 0x0F)
        val packetIndex = datagram.readUByteAt(3)

        val assembler = if (screen == ScreenId.TOP) top else bottom
        val jpeg = assembler.accept(frameIndex, packetIndex, isLast, datagram) ?: return null
        return AssembledFrame(screen, jpeg)
    }

    fun reset() {
        top.reset()
        bottom.reset()
    }
}

private const val HEADER_SIZE = 4

data class AssembledFrame(
    val screen: ScreenId,
    val jpeg: ByteArray,
)

private class ScreenAssembler {

    private var expectedFrame = -1
    private var expectedPacket = -1
    private val buffer = ByteArrayOutputStream()

    fun accept(frameIndex: Int, packetIndex: Int, isLast: Boolean, datagram: ByteArray): ByteArray? {
        if (packetIndex == 0) {
            expectedFrame = frameIndex
            expectedPacket = 0
            buffer.reset()
        } else if (frameIndex != expectedFrame || packetIndex != expectedPacket) {
            // Packet 0 holds the JPEG headers, so a frame with a gap can never decode:
            // drop it and wait for the next packet 0
            reset()
            return null
        }
        buffer.write(datagram, HEADER_SIZE, datagram.size - HEADER_SIZE)
        expectedPacket++
        if (isLast) {
            val complete = buffer.toByteArray()
            reset()
            return complete
        }
        return null
    }

    fun reset() {
        expectedFrame = -1
        expectedPacket = -1
        buffer.reset()
    }
}
