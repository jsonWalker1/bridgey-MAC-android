package dev.bridgey.android

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

internal const val DEFAULT_INPUT_MAX_FRAME_BYTES = 64 * 1024 // input events are tiny; anything bigger is suspicious
internal const val DEFAULT_INPUT_QUEUE_CAPACITY = 32

/**
 * TCP implementation of InputTransport. Mirrors TcpVideoTransport's establishment/framing/security
 * handling exactly (same handshake, same frame envelope) - only the payload codec and the
 * never-drop policy (pointer down/up, key down/up) differ from video's keyframe-protection policy.
 * Own dedicated reader/writer threads, independent of the video transport's threads and of
 * PairingCoordinator's shared CoroutineScope.
 */
internal class TcpInputTransport(
    maxFrameBytes: Int = DEFAULT_INPUT_MAX_FRAME_BYTES,
    private val queueCapacity: Int = DEFAULT_INPUT_QUEUE_CAPACITY,
) : InputTransport {
    override val capabilities = TransportCapabilities(reliable = true, ordered = true, maxFrameBytes = maxFrameBytes)
    @Volatile private var metricsState = TransportMetrics()
    override val metrics: TransportMetrics get() = metricsState
    override var onEventReceived: (InputEvent) -> Unit = {}
    override var onDisconnected: (Throwable?) -> Unit = {}

    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var channelKey: ByteArray? = null
    private var sendSequence = 0L
    private var recvSequence = 0L
    private val closed = AtomicBoolean(false)
    private val sendQueue = LinkedBlockingDeque<QueuedFrame>()
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null

    private data class QueuedFrame(val bytes: ByteArray, val coalescible: Boolean)

    fun connectAsInitiator(host: String, port: Int, security: ChannelSecurityContext, timeoutMs: Int = 5_000): Boolean {
        val s = try {
            Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
        } catch (t: Throwable) {
            return false
        }
        return completeHandshake(s, security, timeoutMs, isInitiator = true)
    }

    fun acceptViaServerSocket(server: ServerSocket, security: ChannelSecurityContext, handshakeTimeoutMs: Int = 5_000): Boolean {
        serverSocket = server
        val s = try {
            server.accept()
        } catch (t: Throwable) {
            runCatching { server.close() }
            return false
        }
        return completeHandshake(s, security, handshakeTimeoutMs, isInitiator = false)
    }

    private fun completeHandshake(s: Socket, security: ChannelSecurityContext, timeoutMs: Int, isInitiator: Boolean): Boolean {
        s.soTimeout = timeoutMs
        val output = s.getOutputStream()
        val input = s.getInputStream()
        val ok = if (isInitiator) {
            ChannelSecurity.performInitiatorHandshake(output, input, security)
        } else {
            ChannelSecurity.performAcceptorHandshake(output, input, security)
        }
        if (!ok) {
            runCatching { s.close() }
            runCatching { serverSocket?.close() }
            return false
        }
        s.soTimeout = 0
        socket = s
        channelKey = security.channelKey
        sendSequence = 0
        recvSequence = 0
        closed.set(false)
        readerThread = Thread({ readLoop(input) }, "bridgey-input-reader").apply { isDaemon = true; start() }
        writerThread = Thread({ writeLoop(output) }, "bridgey-input-writer").apply { isDaemon = true; start() }
        return true
    }

    /** Pointer move is the only coalescible event type - down/up/key/text are never dropped. */
    override fun send(event: InputEvent): SendOutcome {
        val key = channelKey ?: return SendOutcome.Failed("not connected")
        if (closed.get()) return SendOutcome.Failed("closed")
        val (type, payload) = InputEventCodec.encode(event)
        val (nonce, ciphertext) = ChannelSecurity.seal(key, payload)
        val sequence = ++sendSequence
        val bytes = VideoFrameFraming.encodeFrame(type, streamId = 0, sequence, System.currentTimeMillis(), nonce, ciphertext)
        if (bytes.size > capabilities.maxFrameBytes) return SendOutcome.Failed("frame exceeds maxFrameBytes")
        val coalescible = event is InputEvent.Pointer && event.action == PointerAction.MOVE

        synchronized(sendQueue) {
            if (sendQueue.size >= queueCapacity) {
                val evictedCoalescible = sendQueue.removeFirstOccurrence(sendQueue.firstOrNull { it.coalescible })
                if (!evictedCoalescible && sendQueue.size >= queueCapacity) {
                    sendQueue.pollFirst()
                }
                metricsState = metricsState.copy(framesDropped = metricsState.framesDropped + 1)
            }
            sendQueue.addLast(QueuedFrame(bytes, coalescible))
        }
        return if (sendQueue.size > 1) SendOutcome.QueuedBounded else SendOutcome.Sent
    }

    private fun writeLoop(output: OutputStream) {
        try {
            while (!closed.get()) {
                val queued = sendQueue.take()
                output.write(queued.bytes)
                output.flush()
                metricsState = metricsState.copy(
                    framesSent = metricsState.framesSent + 1,
                    bytesSent = metricsState.bytesSent + queued.bytes.size,
                )
            }
        } catch (t: InterruptedException) {
            // normal on close()
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun readLoop(input: InputStream) {
        val key = channelKey ?: return
        try {
            while (!closed.get()) {
                val lengthBytes = ByteArray(4)
                readFully(input, lengthBytes)
                val frameLength = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                    ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                    ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                    (lengthBytes[3].toInt() and 0xFF)
                if (frameLength <= 0 || frameLength > capabilities.maxFrameBytes) {
                    fail(IllegalStateException("declared frame length $frameLength exceeds cap"))
                    return
                }
                val body = ByteArray(frameLength)
                readFully(input, body)
                val parsed = VideoFrameFraming.parseFrameBody(body)
                if (parsed == null || parsed.header.version != FRAME_VERSION || parsed.header.type !in InputFrameType.ALL) {
                    fail(IllegalStateException("malformed input frame"))
                    return
                }
                if (parsed.header.sequence <= recvSequence) {
                    continue
                }
                val plaintext = ChannelSecurity.open(key, parsed.header.nonce, parsed.ciphertext)
                if (plaintext == null) {
                    fail(IllegalStateException("decrypt/tag verification failed"))
                    return
                }
                recvSequence = parsed.header.sequence
                val event = InputEventCodec.decode(parsed.header.type, plaintext)
                if (event == null) {
                    fail(IllegalStateException("undecodable input event"))
                    return
                }
                metricsState = metricsState.copy(framesReceived = metricsState.framesReceived + 1)
                onEventReceived(event)
            }
        } catch (t: EOFException) {
            fail(null)
        } catch (t: Throwable) {
            if (!closed.get()) fail(t)
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
    }

    private fun fail(error: Throwable?) {
        if (closed.compareAndSet(false, true)) {
            teardown()
            onDisconnected(error)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            teardown()
        }
    }

    private fun teardown() {
        writerThread?.interrupt()
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
        synchronized(sendQueue) { sendQueue.clear() }
        socket = null
        serverSocket = null
        channelKey = null
    }
}
