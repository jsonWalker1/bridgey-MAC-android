package dev.bridgey.android

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

internal const val DEFAULT_VIDEO_MAX_FRAME_BYTES = 4 * 1024 * 1024 // 4 MiB - covers a keyframe at higher bitrate/resolution than the PoC baseline
internal const val DEFAULT_VIDEO_QUEUE_CAPACITY = 8

/**
 * TCP implementation of VideoTransport (frozen spec: implementation #1, not the architecture).
 * Owns its own dedicated reader/writer threads - never touches the CoroutineScope the rest of
 * PairingCoordinator shares, so a slow video write can never starve unrelated IO-bound work.
 */
internal class TcpVideoTransport(
    maxFrameBytes: Int = DEFAULT_VIDEO_MAX_FRAME_BYTES,
    private val queueCapacity: Int = DEFAULT_VIDEO_QUEUE_CAPACITY,
) : VideoTransport {
    override val capabilities = TransportCapabilities(reliable = true, ordered = true, maxFrameBytes = maxFrameBytes)
    @Volatile private var metricsState = TransportMetrics()
    override val metrics: TransportMetrics get() = metricsState
    override var onFrameReceived: (EncodedVideoFrame) -> Unit = {}
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

    private data class QueuedFrame(val bytes: ByteArray, val droppable: Boolean)

    /** Client role (algorithm A, steps A6-A14): the side that sent the offer and received the
     * accept. Blocking - call from a background thread. */
    fun connectAsInitiator(host: String, port: Int, security: ChannelSecurityContext, timeoutMs: Int = 5_000): Boolean {
        val s = try {
            Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }
        } catch (t: Throwable) {
            return false
        }
        return completeHandshake(s, security, timeoutMs, isInitiator = true)
    }

    /** Server role (steps A11-A13): the side that received the offer and sent the accept. The
     * ServerSocket is created and its ephemeral port announced by VideoChannelManager BEFORE this
     * is called (the port must be known synchronously to put in the *.accept message), so this
     * just blocks on accept() + handshake using that already-bound socket. */
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
        startThreads(input, output)
        return true
    }

    private fun startThreads(input: InputStream, output: OutputStream) {
        readerThread = Thread({ readLoop(input) }, "bridgey-video-reader").apply { isDaemon = true; start() }
        writerThread = Thread({ writeLoop(output) }, "bridgey-video-writer").apply { isDaemon = true; start() }
    }

    /** Algorithm B: send. Never blocks the caller on network I/O - just enqueues. */
    override fun send(frame: EncodedVideoFrame, droppable: Boolean): SendOutcome {
        val key = channelKey ?: return SendOutcome.Failed("not connected")
        if (closed.get()) return SendOutcome.Failed("closed")
        val (nonce, ciphertext) = ChannelSecurity.seal(key, frame.payload)
        val sequence = ++sendSequence
        val bytes = VideoFrameFraming.encodeFrame(frame.type, frame.streamId, sequence, frame.captureTimestampMs, nonce, ciphertext)
        if (bytes.size > capabilities.maxFrameBytes) return SendOutcome.Failed("frame exceeds maxFrameBytes")

        synchronized(sendQueue) {
            if (sendQueue.size >= queueCapacity) {
                // Evict the oldest droppable entry first; if this frame itself isn't droppable and
                // the queue is still full of non-droppable entries, evict the oldest regardless -
                // per the frozen backpressure policy, a keyframe/pointer-down must still get through.
                val evictedDroppable = sendQueue.removeFirstOccurrence(sendQueue.firstOrNull { it.droppable })
                if (!evictedDroppable && sendQueue.size >= queueCapacity) {
                    sendQueue.pollFirst()
                }
                metricsState = metricsState.copy(framesDropped = metricsState.framesDropped + 1)
            }
            sendQueue.addLast(QueuedFrame(bytes, droppable))
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

    /** Algorithm C: receive. */
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
                if (parsed == null || parsed.header.version != FRAME_VERSION || parsed.header.type !in VideoFrameType.ALL) {
                    fail(IllegalStateException("malformed video frame"))
                    return
                }
                if (parsed.header.sequence <= recvSequence) {
                    continue // stale/replayed - silently dropped, connection stays open
                }
                val plaintext = ChannelSecurity.open(key, parsed.header.nonce, parsed.ciphertext)
                if (plaintext == null) {
                    fail(IllegalStateException("decrypt/tag verification failed"))
                    return
                }
                recvSequence = parsed.header.sequence
                metricsState = metricsState.copy(framesReceived = metricsState.framesReceived + 1)
                onFrameReceived(EncodedVideoFrame(parsed.header.type, parsed.header.streamId, parsed.header.captureTimestampMs, plaintext))
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

    /** Algorithm D: teardown. */
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
