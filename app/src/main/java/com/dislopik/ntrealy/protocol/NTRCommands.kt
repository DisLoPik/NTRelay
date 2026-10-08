package com.dislopik.ntrealy.protocol

object NTRCommands {
    const val MAGIC = 0x12345678

    const val TCP_PORT = 8000

    const val UDP_PORT = 8001
    const val UDP_PORT_MIN = 1024
    const val UDP_PORT_MAX = 65535

    const val HEADER_SIZE = 84
    const val ARG_COUNT = 16

    /** NTR datagrams are at most 1448 bytes; the slack guards against forks sending more. */
    const val MAX_UDP_DATAGRAM = 2048

    const val CMD_HEARTBEAT = 0
    const val CMD_RELOAD = 4
    const val CMD_WRITE_MEMORY = 10
    const val CMD_REMOTE_PLAY = 901

    const val ARG_PRIORITY_SCREEN_TOP = 1
    const val ARG_PRIORITY_SCREEN_BOTTOM = 0

    /** NTR-HR only reads remote-play args[4..6] when args[3] holds this value. */
    const val REMOTE_PLAY_EXT_MAGIC = 1404036572

    const val QOS_DISABLED = 101
}
