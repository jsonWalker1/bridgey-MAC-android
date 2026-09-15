package dev.bridgey.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Binary frame format (frozen spec, section 4):
//   [4B frameLength][1B version][1B type][8B streamId][8B sequence][8B captureTimestampMs][12B nonce][ciphertext+16B tag]
// frameLength counts everything AFTER the frameLength field itself.
// Shared by both the video channel and the input channel - only the `type` value range differs.

internal const val FRAME_VERSION = 1
internal const val FRAME_NONCE_BYTES = 12
internal const val FRAME_HEADER_BYTES_AFTER_LENGTH = 1 + 1 + 8 + 8 + 8 + FRAME_NONCE_BYTES // = 38

internal object VideoFrameType {
    const val CONFIG = 0
    const val KEYFRAME = 1
    const val DELTA = 2
    const val KEYFRAME_REQUEST = 3
    const val STREAM_RESTART = 4
    val ALL = setOf(CONFIG, KEYFRAME, DELTA, KEYFRAME_REQUEST, STREAM_RESTART)
}

internal object InputFrameType {
    const val POINTER = 16
    const val KEY = 17
    const val TEXT = 18
    // 19 = CONTROLLER, reserved, unused in M1.
    val ALL = setOf(POINTER, KEY, TEXT)
}

internal data class FrameHeader(
    val version: Int,
    val type: Int,
    val streamId: Long,
    val sequence: Long,
    val captureTimestampMs: Long,
    val nonce: ByteArray,
)

internal data class ParsedFrame(val header: FrameHeader, val ciphertext: ByteArray)

internal object VideoFrameFraming {
    /** Encodes a complete wire frame, including the 4-byte length prefix, ready to write to a socket. */
    fun encodeFrame(
        type: Int,
        streamId: Long,
        sequence: Long,
        captureTimestampMs: Long,
        nonce: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(nonce.size == FRAME_NONCE_BYTES) { "nonce must be $FRAME_NONCE_BYTES bytes" }
        val afterLength = FRAME_HEADER_BYTES_AFTER_LENGTH + ciphertext.size
        val buffer = ByteBuffer.allocate(4 + afterLength).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(afterLength)
        buffer.put(FRAME_VERSION.toByte())
        buffer.put(type.toByte())
        buffer.putLong(streamId)
        buffer.putLong(sequence)
        buffer.putLong(captureTimestampMs)
        buffer.put(nonce)
        buffer.put(ciphertext)
        return buffer.array()
    }

    /**
     * Parses the frame body - everything the socket read AFTER the 4-byte length prefix
     * (i.e. exactly `frameLength` bytes). Returns null for a body too short to even contain a
     * header; does not itself enforce a maximum size - the caller must reject an oversized
     * declared `frameLength` before ever reading/allocating that many bytes (see TcpVideoTransport).
     */
    fun parseFrameBody(body: ByteArray): ParsedFrame? {
        if (body.size < FRAME_HEADER_BYTES_AFTER_LENGTH) return null
        val buffer = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt() and 0xFF
        val type = buffer.get().toInt() and 0xFF
        val streamId = buffer.long
        val sequence = buffer.long
        val captureTimestampMs = buffer.long
        val nonce = ByteArray(FRAME_NONCE_BYTES).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        return ParsedFrame(FrameHeader(version, type, streamId, sequence, captureTimestampMs, nonce), ciphertext)
    }
}
