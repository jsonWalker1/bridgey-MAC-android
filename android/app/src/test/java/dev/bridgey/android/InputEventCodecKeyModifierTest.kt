package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** BRIDGEY KVM KEYBOARD V1: protocol extension tests for InputEventCodec's KEY modifiers byte.
 * VideoFrameFramingTest/TcpInputTransportTest already cover the original 5-byte KEY round trip
 * unchanged; these are additive, not a replacement. */
class InputEventCodecKeyModifierTest {
    private fun legacyFiveByteKeyPayload(keyCode: Int, action: KeyAction): ByteArray {
        val buffer = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(keyCode)
        buffer.put(action.ordinal.toByte())
        return buffer.array()
    }

    // Backward compatibility: legacy 5-byte payload

    @Test fun legacyFiveBytePayloadStillDecodesAndDefaultsToNoModifiers() {
        val payload = legacyFiveByteKeyPayload(66, KeyAction.DOWN)
        val decoded = InputEventCodec.decode(InputFrameType.KEY, payload)
        assertEquals(InputEvent.Key(66, KeyAction.DOWN, modifiers = 0), decoded)
    }

    @Test fun legacyFiveBytePayloadDecodesForUpToo() {
        val payload = legacyFiveByteKeyPayload(67, KeyAction.UP)
        val decoded = InputEventCodec.decode(InputFrameType.KEY, payload)
        assertEquals(InputEvent.Key(67, KeyAction.UP, modifiers = 0), decoded)
    }

    // New 6-byte payload with modifiers

    @Test fun noModifiersRoundTrips() {
        val event = InputEvent.Key(66, KeyAction.DOWN, modifiers = 0)
        val (type, payload) = InputEventCodec.encode(event)
        assertEquals(InputFrameType.KEY, type)
        assertEquals(6, payload.size)
        assertEquals(event, InputEventCodec.decode(type, payload))
    }

    @Test fun shiftModifierRoundTrips() {
        val event = InputEvent.Key(29, KeyAction.DOWN, modifiers = KeyModifier.SHIFT)
        val (type, payload) = InputEventCodec.encode(event)
        assertEquals(event, InputEventCodec.decode(type, payload))
    }

    @Test fun ctrlModifierRoundTrips() {
        val event = InputEvent.Key(31, KeyAction.DOWN, modifiers = KeyModifier.CONTROL)
        val (type, payload) = InputEventCodec.encode(event)
        assertEquals(event, InputEventCodec.decode(type, payload))
    }

    @Test fun cmdMetaModifierRoundTrips() {
        val event = InputEvent.Key(54, KeyAction.DOWN, modifiers = KeyModifier.META)
        val (type, payload) = InputEventCodec.encode(event)
        assertEquals(event, InputEventCodec.decode(type, payload))
    }

    @Test fun combinedModifiersRoundTrip() {
        val combined = KeyModifier.SHIFT or KeyModifier.CONTROL or KeyModifier.ALT or KeyModifier.META
        val event = InputEvent.Key(54, KeyAction.DOWN, modifiers = combined)
        val (type, payload) = InputEventCodec.encode(event)
        val decoded = InputEventCodec.decode(type, payload) as InputEvent.Key
        assertEquals(combined, decoded.modifiers)
    }

    // Malformed / truncated payloads

    @Test fun payloadShorterThanFiveBytesFailsToDecode() {
        assertNull(InputEventCodec.decode(InputFrameType.KEY, ByteArray(4)))
    }

    @Test fun unknownActionByteFailsToDecode() {
        val buffer = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(66)
        buffer.put(99.toByte())
        buffer.put(0.toByte())
        assertNull(InputEventCodec.decode(InputFrameType.KEY, buffer.array()))
    }
}
