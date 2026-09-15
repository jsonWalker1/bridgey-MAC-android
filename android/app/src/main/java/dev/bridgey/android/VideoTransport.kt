package dev.bridgey.android

// Minimal transport abstraction (frozen spec, section 3): expresses transport semantics, not TCP
// specifics. TcpVideoTransport is implementation #1; a future UDP/QUIC implementation satisfies the
// same interface without VideoChannelManager, encoder, or decoder ever changing.

internal data class TransportCapabilities(
    val reliable: Boolean,
    val ordered: Boolean,
    val maxFrameBytes: Int,
)

internal data class TransportMetrics(
    val framesSent: Long = 0,
    val framesReceived: Long = 0,
    val framesDropped: Long = 0,
    val bytesSent: Long = 0,
)

internal sealed interface SendOutcome {
    data object Sent : SendOutcome
    data object QueuedBounded : SendOutcome
    data class Failed(val reason: String) : SendOutcome
}

internal data class EncodedVideoFrame(
    val type: Int, // VideoFrameType.*
    val streamId: Long,
    val captureTimestampMs: Long,
    val payload: ByteArray,
)

internal interface VideoTransport {
    val capabilities: TransportCapabilities
    val metrics: TransportMetrics
    var onFrameReceived: (EncodedVideoFrame) -> Unit
    var onDisconnected: (Throwable?) -> Unit

    /** Enqueues a frame for sending. `droppable=false` (KEYFRAME, CONFIG) must never be silently
     * dropped by backpressure; `droppable=true` (DELTA) may be, per the frozen backpressure policy. */
    fun send(frame: EncodedVideoFrame, droppable: Boolean): SendOutcome
    fun close()
}
