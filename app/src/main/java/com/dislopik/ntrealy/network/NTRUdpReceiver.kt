package com.dislopik.ntrealy.network

import com.dislopik.ntrealy.protocol.NTRCommands
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import kotlinx.coroutines.channels.SendChannel

class NTRUdpReceiver(
    private val sink: SendChannel<ByteArray>,
    private val port: Int = NTRCommands.UDP_PORT,
    private val onError: (String) -> Unit = {},
) {

    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null

    private var thread: Thread? = null

    /**
     * Binds on the calling thread so a busy port is reported to the caller and
     * [close] always frees the port before returning.
     */
    fun start() {
        if (running) return
        val s = try {
            DatagramSocket(port)
        } catch (e: BindException) {
            throw SocketException("UDP port $port is already in use by another app")
        } catch (e: SocketException) {
            throw SocketException("Cannot open UDP port $port: ${e.message}")
        }
        runCatching { s.receiveBufferSize = RECEIVE_BUFFER_BYTES }
        socket = s
        running = true
        thread = Thread({ runLoop(s) }, "NTR-UDP").apply { isDaemon = true; start() }
    }

    fun close() {
        running = false
        socket?.close()
        socket = null
    }

    private fun runLoop(s: DatagramSocket) {
        val buffer = ByteArray(NTRCommands.MAX_UDP_DATAGRAM)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running) {
            try {
                s.receive(packet)
                sink.trySend(buffer.copyOf(packet.length))
            } catch (e: IOException) {
                if (running) onError("Receiving frames failed: ${e.message}")
                break
            }
        }
        s.close()
    }

    private companion object {
        const val RECEIVE_BUFFER_BYTES = 1 shl 20
    }
}
