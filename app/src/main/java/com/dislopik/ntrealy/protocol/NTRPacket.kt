package com.dislopik.ntrealy.protocol

/** The 84-byte NTR command header. All integers are little-endian. */
data class NTRPacketHeader(
    val seq: Int,
    val type: Int,
    val cmd: Int,
    val args: IntArray = IntArray(NTRCommands.ARG_COUNT),
    val exdataLen: Int = 0,
) {

    fun arg(index: Int): Int = args[index]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NTRPacketHeader) return false
        return seq == other.seq && type == other.type && cmd == other.cmd &&
            exdataLen == other.exdataLen && args.contentEquals(other.args)
    }

    override fun hashCode(): Int {
        var result = seq
        result = 31 * result + type
        result = 31 * result + cmd
        result = 31 * result + args.contentHashCode()
        result = 31 * result + exdataLen
        return result
    }

    companion object {

        fun parse(raw: ByteArray): NTRPacketHeader {
            require(raw.size >= NTRCommands.HEADER_SIZE) {
                "NTR packet header too small: ${raw.size} bytes (need ${NTRCommands.HEADER_SIZE})"
            }
            val magic = raw.readIntLE(0)
            require(magic == NTRCommands.MAGIC) {
                "Bad NTR magic 0x${magic.toString(16)} - data is most likely malformed"
            }
            val seq = raw.readIntLE(4)
            val type = raw.readIntLE(8)
            val cmd = raw.readIntLE(12)
            val args = IntArray(NTRCommands.ARG_COUNT) { i -> raw.readIntLE(16 + i * 4) }
            val exdataLen = raw.readIntLE(80)
            return NTRPacketHeader(seq, type, cmd, args, exdataLen)
        }

        /** A negative [type] is derived from [exdataLen]: 1 when data follows, else 0. */
        fun encode(
            seq: Int,
            cmd: Int,
            args: IntArray = IntArray(NTRCommands.ARG_COUNT),
            exdataLen: Int = 0,
            type: Int = -1,
        ): ByteArray {
            val out = ByteArray(NTRCommands.HEADER_SIZE)
            out.writeIntLE(0, NTRCommands.MAGIC)
            out.writeIntLE(4, seq)
            out.writeIntLE(8, if (type >= 0) type else if (exdataLen > 0) 1 else 0)
            out.writeIntLE(12, cmd)
            for (i in 0 until NTRCommands.ARG_COUNT) {
                out.writeIntLE(16 + i * 4, if (i < args.size) args[i] else 0)
            }
            out.writeIntLE(80, exdataLen)
            return out
        }
    }
}

/**
 * Remote play, cmd 901. args[3..6] are NTR-HR's extension (other forks ignore them):
 * without it NTR-HR reuses its X+Y menu settings, which may select a viewer port,
 * Reliable Stream or lossless mode this client can't receive.
 */
fun buildRemotePlayPacket(
    quality: Int,
    priority: Int,
    priorityIsTop: Boolean,
    qos: Int,
    viewerPort: Int = NTRCommands.UDP_PORT,
): NTRPacketHeader {
    val args = IntArray(NTRCommands.ARG_COUNT)
    args[0] = ((if (priorityIsTop) NTRCommands.ARG_PRIORITY_SCREEN_TOP
    else NTRCommands.ARG_PRIORITY_SCREEN_BOTTOM) shl 8) or (priority % 256)
    args[1] = quality
    args[2] = (qos * 2) shl 16
    args[3] = NTRCommands.REMOTE_PLAY_EXT_MAGIC
    args[4] = viewerPort // high 16 bits are format flags; 0 = plain UDP + JPEG
    args[5] = 0 // audio off
    args[6] = 0 // standard 400 px top screen
    return NTRPacketHeader(seq = 3000, type = 0, cmd = NTRCommands.CMD_REMOTE_PLAY, args = args)
}

fun buildHeartbeatPacket(seq: Int = (0..99).random()): NTRPacketHeader =
    NTRPacketHeader(seq = seq, type = 0, cmd = NTRCommands.CMD_HEARTBEAT)

fun ByteArray.readIntLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

fun ByteArray.writeIntLE(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}

fun ByteArray.readUByteAt(offset: Int): Int = this[offset].toInt() and 0xFF
