package dev.bridgey.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFrameFramingTest {
    private val nonce = ByteArray(FRAME_NONCE_BYTES) { it.toByte() }
    private val ciphertext = "encrypted-payload-bytes".toByteArray(Charsets.UTF_8)

    @Test fun encodeThenParseRoundTripsAllHeaderFields() {
        val wire = VideoFrameFraming.encodeFrame(
            type = VideoFrameType.KEYFRAME, streamId = 42, sequence = 7, captureTimestampMs = 1_700_000_000_000, nonce, ciphertext,
        )
        // Simulate what the socket reader does: read 4B length, then that many more bytes.
        val declaredLength = ((wire[0].toInt() and 0xFF) shl 24) or ((wire[1].toInt() and 0xFF) shl 16) or
            ((wire[2].toInt() and 0xFF) shl 8) or (wire[3].toInt() and 0xFF)
        assertEquals(wire.size - 4, declaredLength)
        val body = wire.copyOfRange(4, wire.size)

        val parsed = VideoFrameFraming.parseFrameBody(body)!!
        assertEquals(FRAME_VERSION, parsed.header.version)
        assertEquals(VideoFrameType.KEYFRAME, parsed.header.type)
        assertEquals(42L, parsed.header.streamId)
        assertEquals(7L, parsed.header.sequence)
        assertEquals(1_700_000_000_000L, parsed.header.captureTimestampMs)
        assertArrayEquals(nonce, parsed.header.nonce)
        assertArrayEquals(ciphertext, parsed.ciphertext)
    }

    @Test fun parseRejectsABodyTooShortToContainAHeader() {
        assertNull(VideoFrameFraming.parseFrameBody(ByteArray(10)))
    }

    @Test fun parseAcceptsAZeroLengthCiphertextBody() {
        val wire = VideoFrameFraming.encodeFrame(VideoFrameType.KEYFRAME_REQUEST, 1, 1, 0, nonce, ByteArray(0))
        val body = wire.copyOfRange(4, wire.size)
        val parsed = VideoFrameFraming.parseFrameBody(body)!!
        assertEquals(0, parsed.ciphertext.size)
    }

    @Test fun inputEventCodecRoundTripsPointerKeyAndText() {
        val pointer = InputEvent.Pointer(PointerAction.DOWN, 0.25f, 0.75f)
        val (pType, pPayload) = InputEventCodec.encode(pointer)
        assertEquals(pointer, InputEventCodec.decode(pType, pPayload))

        val key = InputEvent.Key(66, KeyAction.UP)
        val (kType, kPayload) = InputEventCodec.encode(key)
        assertEquals(key, InputEventCodec.decode(kType, kPayload))

        val text = InputEvent.Text("hello android")
        val (tType, tPayload) = InputEventCodec.encode(text)
        assertEquals(text, InputEventCodec.decode(tType, tPayload))
    }

    @Test fun videoAndInputFrameTypeRangesDoNotOverlap() {
        assertEquals(emptySet<Int>(), VideoFrameType.ALL.intersect(InputFrameType.ALL))
    }
}
