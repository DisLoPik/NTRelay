package com.dislopik.ntrealy.service

import com.dislopik.ntrealy.network.ConnectionState
import com.dislopik.ntrealy.network.NTRSocketClient
import com.dislopik.ntrealy.network.NTRUdpReceiver
import com.dislopik.ntrealy.protocol.AssembledFrame
import com.dislopik.ntrealy.protocol.FrameAssembler
import com.dislopik.ntrealy.protocol.ScreenId
import com.dislopik.ntrealy.render.DecodedFrame
import com.dislopik.ntrealy.render.FrameDecoder
import com.dislopik.ntrealy.ui.StreamConfig
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The streaming pipeline, shared by the service and the UI:
 * UDP datagrams -> FrameAssembler -> FrameDecoder -> [frames], with the TCP
 * client driving [state] and a watchdog ending the session when the 3DS is gone.
 */
object StreamSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<DecodedFrame>(
        extraBufferCapacity = 3,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<DecodedFrame> = _frames.asSharedFlow()

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _debugLog = MutableStateFlow<List<String>>(emptyList())
    val debugLog: StateFlow<List<String>> = _debugLog.asStateFlow()

    /** Bumped by every [stop] so callbacks from an old session's threads are ignored. */
    @Volatile
    private var generation = 0

    @Volatile
    private var tcpClient: NTRSocketClient? = null

    @Volatile
    private var lastFrameNanos = 0L

    @Volatile
    private var lastAliveNanos = 0L

    @Volatile
    private var connectedAtNanos = 0L

    private var udpReceiver: NTRUdpReceiver? = null
    private var viewerPort = 0
    private var jobs: List<Job> = emptyList()
    private val frameCount = AtomicInteger()

    @Synchronized
    fun start(
        config: StreamConfig,
        bindSocket: (Socket) -> Unit = {},
    ) {
        stop()
        val session = generation
        lastFrameNanos = 0
        lastAliveNanos = 0
        connectedAtNanos = 0
        viewerPort = config.viewerPort
        _debugLog.value = emptyList()
        _state.value = ConnectionState.Connecting

        appendDebug("Connecting to ${config.host}, receiving on UDP ${config.viewerPort}...")

        // Listen before the handshake so the first frames aren't lost
        val datagrams = Channel<ByteArray>(DATAGRAM_BUFFER)
        val receiver = NTRUdpReceiver(
            sink = datagrams,
            port = config.viewerPort,
            onError = { message -> fail(session, message) },
        )
        try {
            receiver.start()
        } catch (e: SocketException) {
            _state.value = ConnectionState.Error(e.message ?: "Cannot open UDP port ${config.viewerPort}")
            return
        }
        udpReceiver = receiver

        // Assembly never falls behind, so a slow decoder drops whole frames
        // instead of packets (one lost packet ruins a frame)
        val assembled = Channel<AssembledFrame>(
            capacity = ASSEMBLED_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val assembleJob = scope.launch {
            val assembler = FrameAssembler()
            for (datagram in datagrams) {
                assembler.accept(datagram)?.let { assembled.trySend(it) }
            }
        }
        val decodeJob = scope.launch {
            val decoder = FrameDecoder(config.colorMode)
            for (frame in assembled) {
                val decoded = decoder.decode(frame) ?: continue
                lastFrameNanos = System.nanoTime()
                frameCount.incrementAndGet()
                _frames.tryEmit(decoded)
            }
        }

        tcpClient = NTRSocketClient(
            host = config.host,
            bindSocket = bindSocket,
            onState = { newState -> onClientState(session, newState) },
            onDebug = { line -> if (session == generation) appendDebug(line) },
            onAlive = { lastAliveNanos = System.nanoTime() },
        ).also { it.start(config.toRemotePlaySettings()) }

        // Frames alone may pause legitimately (NTR-HR skips unchanged frames, the
        // lid can be closed), so only fail when the 3DS stops answering too
        val watchdogJob = scope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (_state.value !is ConnectionState.Connected) continue
                val now = System.nanoTime()
                val lastSignOfLife = maxOf(lastFrameNanos, lastAliveNanos, connectedAtNanos)
                val failure = when {
                    lastFrameNanos == 0L && now - connectedAtNanos > FIRST_FRAME_TIMEOUT_NS ->
                        "Stream timed out - the 3DS accepted the connection but no frames " +
                            "arrived on UDP port ${config.viewerPort}"
                    now - lastSignOfLife > LINK_TIMEOUT_NS ->
                        "Lost the 3DS - no frames or replies for ${LINK_TIMEOUT_NS / 1_000_000_000} s"
                    else -> null
                }
                if (failure != null) {
                    fail(session, failure)
                    break
                }
            }
        }

        val fpsJob = scope.launch {
            while (true) {
                delay(1000)
                _fps.value = frameCount.getAndSet(0)
            }
        }

        jobs = listOf(assembleJob, decodeJob, watchdogJob, fpsJob)
    }

    fun updateSettings(newConfig: StreamConfig) {
        // The viewer port can't change while the UDP socket is bound to it
        tcpClient?.updateSettings(newConfig.toRemotePlaySettings().copy(viewerPort = viewerPort))
    }

    fun abort(message: String) = fail(generation, message)

    @Synchronized
    fun stop() {
        generation++
        tcpClient?.close()
        tcpClient = null
        udpReceiver?.close()
        udpReceiver = null
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        frameCount.set(0)
        _fps.value = 0
        if (_state.value !is ConnectionState.Error) {
            _state.value = ConnectionState.Idle
        }
    }

    @Synchronized
    private fun fail(session: Int, message: String) {
        if (session != generation) return
        appendDebug(message)
        _state.value = ConnectionState.Error(message)
        stop()
    }

    @Synchronized
    private fun onClientState(session: Int, newState: ConnectionState) {
        if (session != generation) return
        when (newState) {
            is ConnectionState.Error -> fail(session, newState.message)
            is ConnectionState.Connected -> {
                connectedAtNanos = System.nanoTime()
                _state.value = newState
            }
            else -> _state.value = newState
        }
    }

    private fun appendDebug(line: String) {
        _debugLog.update { (it + line).takeLast(MAX_DEBUG_LINES) }
    }

    private fun StreamConfig.toRemotePlaySettings() = NTRSocketClient.RemotePlaySettings(
        quality = quality,
        priority = priority,
        priorityIsTop = priorityScreen == ScreenId.TOP,
        qos = qos,
        viewerPort = viewerPort,
    )

    private const val DATAGRAM_BUFFER = 256
    private const val ASSEMBLED_BUFFER = 2
    private const val WATCHDOG_INTERVAL_MS = 1000L
    private const val FIRST_FRAME_TIMEOUT_NS = 10_000_000_000L
    private const val LINK_TIMEOUT_NS = 15_000_000_000L
    private const val MAX_DEBUG_LINES = 20
}
