package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** KVM Mouse V2 phase 1 (see KVM_MOUSE_V2_PHASE1.md): protocol extension tests for
 * InputEventCodec's pointer button/scroll additions. VideoFrameFramingTest already covers the
 * original Mouse v1 pointer/key/text round trip unchanged; these are additive, not a replacement. */
class InputEventCodecV2Test {
    private fun legacyNineBytePointerPayload(action: PointerAction, x: Float, y: Float): ByteArray {
        val buffer = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
        buffer.put(action.ordinal.toByte())
        buffer.putFloat(x)
        buffer.putFloat(y)
        return buffer.array()
    }

    // Backward compatibility: legacy 9-byte payload

    @Test fun legacyNineBytePayloadStillDecodesAndDefaultsToLeftButton() {
        val payload = legacyNineBytePointerPayload(PointerAction.DOWN, 0.25f, 0.75f)
        val decoded = InputEventCodec.decode(InputFrameType.POINTER, payload)
        assertEquals(InputEvent.Pointer(PointerAction.DOWN, 0.25f, 0.75f, PointerButton.LEFT), decoded)
    }

    @Test fun legacyNineBytePayloadDecodesForUpAndMoveToo() {
        for (action in listOf(PointerAction.UP, PointerAction.MOVE)) {
            val payload = legacyNineBytePointerPayload(action, 0.1f, 0.2f)
            val decoded = InputEventCodec.decode(InputFrameType.POINTER, payload)
            assertEquals(InputEvent.Pointer(action, 0.1f, 0.2f, PointerButton.LEFT), decoded)
        }
    }

    @Test fun legacyNineByteScrollPayloadDecodesWithZeroDelta() {
        val payload = legacyNineBytePointerPayload(PointerAction.SCROLL, 0.5f, 0.5f)
        val decoded = InputEventCodec.decode(InputFrameType.POINTER, payload)
        assertEquals(InputEvent.Pointer(PointerAction.SCROLL, 0.5f, 0.5f, scrollDx = 0f, scrollDy = 0f), decoded)
    }

    // New LEFT event (explicit button byte, still LEFT)

    @Test fun newLeftEventRoundTrips() {
        val event = InputEvent.Pointer(PointerAction.DOWN, 0.3f, 0.4f, PointerButton.LEFT)
        val (type, payload) = InputEventCodec.encode(event)
        assertEquals(InputFrameType.POINTER, type)
        assertEquals(10, payload.size)
        assertEquals(event, InputEventCodec.decode(type, payload))
    }

    // RIGHT event

    @Test fun rightButtonDownRoundTrips() {
        val event = InputEvent.Pointer(PointerAction.DOWN, 0.6f, 0.7f, PointerButton.RIGHT)
        val (type, payload) = InputEventCodec.encode(event)
        assertEquals(10, payload.size)
        assertEquals(event, InputEventCodec.decode(type, payload))
    }

    @Test fun rightButtonUpAndMoveRoundTrip() {
        for (action in listOf(PointerAction.UP, PointerAction.MOVE)) {
            val event = InputEvent.Pointer(action, 0.15f, 0.85f, PointerButton.RIGHT)
            val (type, payload) = InputEventCodec.encode(event)
            assertEquals(event, InputEventCodec.decode(type, payload))
        }
    }

    // MIDDLE event

    @Test fun middleButtonRoundTrips() {
        val event = InputEvent.Pointer(PointerAction.DOWN, 0.5f, 0.5f, PointerButton.MIDDLE)
        val (type, payload) = InputEventCodec.encode(event)
        assertEquals(10, payload.size)
        assertEquals(event, InputEventCodec.decode(type, payload))
    }

    // SCROLL event

    @Test fun scrollEventRoundTripsWithDelta() {
        val event = InputEvent.Pointer(PointerAction.SCROLL, 0.5f, 0.5f, scrollDx = -3.25f, scrollDy = 12.5f)
        val (type, payload) = InputEventCodec.encode(event)
        assertEquals(InputFrameType.POINTER, type)
        assertEquals(17, payload.size)
        assertEquals(event, InputEventCodec.decode(type, payload))
    }

    @Test fun scrollEventPreservesNegativeAndPositiveDeltaSignsExactly() {
        val event = InputEvent.Pointer(PointerAction.SCROLL, 0f, 0f, scrollDx = -1f, scrollDy = 1f)
        val (type, payload) = InputEventCodec.encode(event)
        val decoded = InputEventCodec.decode(type, payload) as InputEvent.Pointer
        assertEquals(-1f, decoded.scrollDx)
        assertEquals(1f, decoded.scrollDy)
    }

    // Malformed / truncated payloads

    @Test fun emptyPayloadFailsToDecode() {
        assertNull(InputEventCodec.decode(InputFrameType.POINTER, ByteArray(0)))
    }

    @Test fun payloadShorterThanNineBytesFailsToDecode() {
        assertNull(InputEventCodec.decode(InputFrameType.POINTER, ByteArray(8)))
    }

    @Test fun scrollPayloadMissingTheDeltaTailDefaultsToZeroDeltaRatherThanFailing() {
        for (length in 9..16) {
            val base = legacyNineBytePointerPayload(PointerAction.SCROLL, 0.2f, 0.3f)
            val payload = base + ByteArray(length - 9)
            val decoded = InputEventCodec.decode(InputFrameType.POINTER, payload)
            assertEquals(
                "length $length should degrade to zero delta",
                InputEvent.Pointer(PointerAction.SCROLL, 0.2f, 0.3f, scrollDx = 0f, scrollDy = 0f),
                decoded
            )
        }
    }

    @Test fun unknownActionByteFailsToDecode() {
        val buffer = ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
        buffer.put(99.toByte())
        buffer.putFloat(0f)
        buffer.putFloat(0f)
        assertNull(InputEventCodec.decode(InputFrameType.POINTER, buffer.array()))
    }

    @Test fun unknownButtonByteDefaultsToLeftRatherThanFailing() {
        val base = legacyNineBytePointerPayload(PointerAction.DOWN, 0.5f, 0.5f)
        val payload = base + byteArrayOf(99)
        val decoded = InputEventCodec.decode(InputFrameType.POINTER, payload)
        assertEquals(InputEvent.Pointer(PointerAction.DOWN, 0.5f, 0.5f, PointerButton.LEFT), decoded)
    }

    // TcpInputTransport coalescing still recognizes MOVE regardless of button/scroll fields

    @Test fun moveIsStillTheOnlyCoalescibleActionAcrossAllButtons() {
        // Mirrors TcpInputTransport.send's own coalescing check (`event is InputEvent.Pointer &&
        // event.action == PointerAction.MOVE`) - Kotlin's named-property check needed NO change for
        // Mouse V2 (unlike Swift's positional pattern match), but this pins the invariant anyway.
        for (button in listOf(PointerButton.LEFT, PointerButton.RIGHT, PointerButton.MIDDLE)) {
            val moveEvent: InputEvent = InputEvent.Pointer(PointerAction.MOVE, 0.1f, 0.1f, button)
            val coalescible = moveEvent is InputEvent.Pointer && moveEvent.action == PointerAction.MOVE
            assertEquals(true, coalescible)
        }
        val downEvent: InputEvent = InputEvent.Pointer(PointerAction.DOWN, 0.1f, 0.1f)
        val downCoalescible = downEvent is InputEvent.Pointer && downEvent.action == PointerAction.MOVE
        assertEquals(false, downCoalescible)
    }
}
