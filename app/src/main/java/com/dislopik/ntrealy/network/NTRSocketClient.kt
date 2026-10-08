package com.dislopik.ntrealy.network

import com.dislopik.ntrealy.protocol.NTRCommands
import com.dislopik.ntrealy.protocol.NTRPacketHeader
import com.dislopik.ntrealy.protocol.buildHeartbeatPacket
import com.dislopik.ntrealy.protocol.buildRemotePlayPacket
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The TCP command connection to NTR on port 8000: sends remote play (cmd 901),
 * then heartbeats until closed, reconnecting if the link drops. All socket I/O
 * happens on its own thread.
 */
class NTRSocketClient(
    private val host: String,
    private val onState: (ConnectionState) -> Unit,
    private val onDebug: (String) -> Unit = {},
    private val onAlive: () -> Unit = {},
    private val bindSocket: (Socket) -> Unit = {},
) {

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var running = false

    @Volatile
    private var settings: RemotePlaySettings = RemotePlaySettings()

    @Volatile
    private var settingsChanged = false

    private var thread: Thread? = null

    data class RemotePlaySettings(
        val quality: Int = 70,
        val priority: Int = 4,
        val priorityIsTop: Boolean = true,
        val qos: Int = 16,
        val viewerPort: Int = NTRCommands.UDP_PORT,
    )

    fun start(initial: RemotePlaySettings) {
        if (running) return
        settings = initial
        running = true
        thread = Thread({ runLoop() }, "NTR-TCP").apply { isDaemon = true; start() }
    }

    fun updateSettings(new: RemotePlaySettings) {
        settings = new
        settingsChanged = true
    }

    fun close() {
        running = false
        closeQuietly(socket)
    }

    private fun runLoop() {
        var reconnects = 0
        while (running) {
            var s: Socket? = null
            try {
                s = Socket().also { socket = it }
                if (!running) break
                openSocket(s)
                sendRemotePlay(s.getOutputStream())
                reconnects = 0
                onState(ConnectionState.Connected)
                onDebug("Connected to $host:${NTRCommands.TCP_PORT}, remote-play started")
                heartbeatLoop(s)
            } catch (e: UnknownHostException) {
                if (!running) break
                onState(ConnectionState.Error("Invalid 3DS address \"$host\" - enter the IP shown on the 3DS"))
                break
            } catch (e: ConnectException) {
                if (!running) break
                onState(ConnectionState.Error(
                    "Connection refused by $host:${NTRCommands.TCP_PORT} - is NTR running on the 3DS? " +
                        "(remote play is New 3DS only)"
                ))
                break
            } catch (e: SocketTimeoutException) {
                if (!running) break
                if (reconnects >= MAX_RECONNECTS) {
                    onState(ConnectionState.Error(
                        "No response from $host:${NTRCommands.TCP_PORT}. Check the IP, that the 3DS " +
                            "is on the same Wi-Fi (2.4 GHz only), that no VPN is active, and that the " +
                            "router doesn't isolate wireless clients."
                    ))
                    break
                }
                reconnects++
                onDebug("Connect timed out, retrying...")
                sleepQuietly(RECONNECT_DELAY_MS)
            } catch (e: IOException) {
                if (!running) break
                if (reconnects >= MAX_RECONNECTS) {
                    onState(ConnectionState.Error("Connection lost: ${e.message}"))
                    break
                }
                reconnects++
                onDebug("Link dropped (${e.message}), reconnecting...")
                sleepQuietly(RECONNECT_DELAY_MS)
            } catch (e: Exception) {
                if (running) {
                    onState(ConnectionState.Error(e.message ?: "Unexpected error"))
                }
                break
            } finally {
                // NTR serves one client at a time, so a lingering socket would block the reconnect
                closeQuietly(s)
                if (socket === s) socket = null
            }
        }
    }

    private fun heartbeatLoop(s: Socket) {
        val input = s.getInputStream()
        val output = s.getOutputStream()
        var awaitingReply = false
        while (running && !s.isClosed) {
            if (settingsChanged) {
                settingsChanged = false
                sendRemotePlay(output)
                onDebug("Stream settings updated")
            }
            if (!awaitingReply) {
                writePacket(output, buildHeartbeatPacket())
                awaitingReply = true
            }
            val reply = try {
                readPacket(input)
            } catch (e: SocketTimeoutException) {
                continue
            }
            onAlive()
            if (reply.cmd == NTRCommands.CMD_HEARTBEAT) {
                awaitingReply = false
            }
            if (reply.exdataLen > 0) {
                val exdata = ByteArray(reply.exdataLen)
                readFully(input, exdata, idleTimeoutAllowed = false)
                String(exdata, Charsets.UTF_8).lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { onDebug("NTR: $it") }
            }
            if (!awaitingReply) sleepQuietly(HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun openSocket(s: Socket) {
        s.tcpNoDelay = true
        bindSocket(s)
        s.connect(InetSocketAddress(host, NTRCommands.TCP_PORT), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_TIMEOUT_MS
    }

    private fun sendRemotePlay(out: OutputStream) {
        val current = settings
        writePacket(out, buildRemotePlayPacket(
            quality = current.quality,
            priority = current.priority,
            priorityIsTop = current.priorityIsTop,
            qos = current.qos,
            viewerPort = current.viewerPort,
        ))
    }

    private fun writePacket(out: OutputStream, header: NTRPacketHeader) {
        out.write(NTRPacketHeader.encode(
            seq = header.seq,
            cmd = header.cmd,
            args = header.args,
            exdataLen = header.exdataLen,
            type = header.type,
        ))
        out.flush()
    }

    private fun readPacket(input: InputStream): NTRPacketHeader {
        val headerBytes = ByteArray(NTRCommands.HEADER_SIZE)
        readFully(input, headerBytes, idleTimeoutAllowed = true)
        return NTRPacketHeader.parse(headerBytes)
    }

    /**
     * A read timeout is only rethrown before the first byte. Once a packet has
     * started it is read to the end, or the stream would be misaligned for good.
     */
    private fun readFully(input: InputStream, buf: ByteArray, idleTimeoutAllowed: Boolean) {
        var offset = 0
        var stalls = 0
        while (offset < buf.size) {
            val n = try {
                input.read(buf, offset, buf.size - offset)
            } catch (e: SocketTimeoutException) {
                if (idleTimeoutAllowed && offset == 0) throw e
                if (++stalls > MAX_MID_PACKET_STALLS) throw IOException("NTR stopped mid-packet")
                continue
            }
            if (n < 0) throw EOFException("Stream closed by peer")
            offset += n
        }
    }

    private fun closeQuietly(s: Socket?) {
        try {
            s?.close()
        } catch (_: IOException) {
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
        private const val HEARTBEAT_INTERVAL_MS = 1000L
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECTS = 3
        private const val MAX_MID_PACKET_STALLS = 2
    }
}
