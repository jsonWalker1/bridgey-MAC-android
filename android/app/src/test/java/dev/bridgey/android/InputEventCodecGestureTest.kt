package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BRIDGEY KVM TOUCHPAD GESTURES V1: wire round-trip tests for the new GESTURE frame type. Mirrors
 * InputEventCodecV2Test's structure. GestureAction's declaration order must exactly match Swift's
 * GestureAction rawValue order (ordinal = wire byte) - these tests pin that against the actual byte
 * values, not just against round-tripping through the same enum. */
class InputEventCodecGestureTest {
    @Test fun everyGestureActionRoundTrips() {
        for (action in GestureAction.entries) {
            val event = InputEvent.Gesture(action)
            val (type, payload) = InputEventCodec.encode(event)
            assertEquals(InputFrameType.GESTURE, type)
            assertEquals(1, payload.size)
            assertEquals(event, InputEventCodec.decode(type, payload))
        }
    }

    @Test fun ordinalsMatchTheFrozenSwiftRawValueOrder() {
        assertEquals(0, GestureAction.BACK.ordinal)
        assertEquals(1, GestureAction.FORWARD.ordinal)
        assertEquals(2, GestureAction.HOME.ordinal)
        assertEquals(3, GestureAction.NOTIFICATIONS.ordinal)
        assertEquals(4, GestureAction.RECENTS.ordinal)
    }

    @Test fun decodesTheRawByteForEachAction() {
        assertEquals(InputEvent.Gesture(GestureAction.BACK), InputEventCodec.decode(InputFrameType.GESTURE, byteArrayOf(0)))
        assertEquals(InputEvent.Gesture(GestureAction.FORWARD), InputEventCodec.decode(InputFrameType.GESTURE, byteArrayOf(1)))
        assertEquals(InputEvent.Gesture(GestureAction.HOME), InputEventCodec.decode(InputFrameType.GESTURE, byteArrayOf(2)))
        assertEquals(InputEvent.Gesture(GestureAction.NOTIFICATIONS), InputEventCodec.decode(InputFrameType.GESTURE, byteArrayOf(3)))
        assertEquals(InputEvent.Gesture(GestureAction.RECENTS), InputEventCodec.decode(InputFrameType.GESTURE, byteArrayOf(4)))
    }

    @Test fun emptyPayloadFailsToDecode() {
        assertNull(InputEventCodec.decode(InputFrameType.GESTURE, ByteArray(0)))
    }

    @Test fun unknownActionByteFailsToDecode() {
        assertNull(InputEventCodec.decode(InputFrameType.GESTURE, byteArrayOf(99)))
    }

    @Test fun gestureIsNeverCoalescible() {
        // Mirrors TcpInputTransport.send's coalescing check (`event is InputEvent.Pointer && event.action
        // == PointerAction.MOVE`) - a Gesture event structurally can never match that `is` check, so it
        // requires no change to the transport and is never dropped/coalesced away.
        val event: InputEvent = InputEvent.Gesture(GestureAction.HOME)
        val coalescible = event is InputEvent.Pointer && event.action == PointerAction.MOVE
        assertEquals(false, coalescible)
    }
}
