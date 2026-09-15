package dev.bridgey.android

// First-class abstraction, independent of VideoTransport (frozen spec, section 7-8) and not tied to
// any UI or to AccessibilityService - InputSessionManager (M4/M5, not part of M1) is what will bind
// InputEvent to actual Android injection APIs.

internal enum class PointerAction { DOWN, UP, MOVE, SCROLL }
internal enum class KeyAction { DOWN, UP }

internal sealed class InputEvent {
    data class Pointer(val action: PointerAction, val x: Float, val y: Float) : InputEvent()
    data class Key(val keyCode: Int, val action: KeyAction) : InputEvent()
    data class Text(val text: String) : InputEvent()
    // Controller (gaming, section 12) intentionally not modeled yet - not part of M1.
}

/** Encodes/decodes the plaintext payload carried inside a frame's ciphertext (frozen spec, section 4).
 * POINTER: [1B action][4B x Float32 BE][4B y Float32 BE](+[4B scrollDx][4B scrollDy] if action=SCROLL)
 * KEY:     [4B keyCode BE][1B action]
 * TEXT:    UTF-8 bytes directly */
internal object InputEventCodec {
    fun encode(event: InputEvent): Pair<Int, ByteArray> = when (event) {
        is InputEvent.Pointer -> {
            val buffer = java.nio.ByteBuffer.allocate(9).order(java.nio.ByteOrder.BIG_ENDIAN)
            buffer.put(event.action.ordinal.toByte())
            buffer.putFloat(event.x)
            buffer.putFloat(event.y)
            InputFrameType.POINTER to buffer.array()
        }
        is InputEvent.Key -> {
            val buffer = java.nio.ByteBuffer.allocate(5).order(java.nio.ByteOrder.BIG_ENDIAN)
            buffer.putInt(event.keyCode)
            buffer.put(event.action.ordinal.toByte())
            InputFrameType.KEY to buffer.array()
        }
        is InputEvent.Text -> InputFrameType.TEXT to event.text.toByteArray(Charsets.UTF_8)
    }

    fun decode(type: Int, payload: ByteArray): InputEvent? = when (type) {
        InputFrameType.POINTER -> {
            if (payload.size < 9) return null
            val buffer = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.BIG_ENDIAN)
            val actionOrdinal = buffer.get().toInt()
            val action = PointerAction.entries.getOrNull(actionOrdinal) ?: return null
            InputEvent.Pointer(action, buffer.float, buffer.float)
        }
        InputFrameType.KEY -> {
            if (payload.size < 5) return null
            val buffer = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.BIG_ENDIAN)
            val keyCode = buffer.int
            val actionOrdinal = buffer.get().toInt()
            val action = KeyAction.entries.getOrNull(actionOrdinal) ?: return null
            InputEvent.Key(keyCode, action)
        }
        InputFrameType.TEXT -> InputEvent.Text(String(payload, Charsets.UTF_8))
        else -> null
    }
}

internal interface InputTransport {
    val capabilities: TransportCapabilities
    val metrics: TransportMetrics
    var onEventReceived: (InputEvent) -> Unit
    var onDisconnected: (Throwable?) -> Unit

    /** Pointer down/up and key down/up must never be silently dropped (frozen backpressure policy);
     * only pointer move events are coalescible/droppable. */
    fun send(event: InputEvent): SendOutcome
    fun close()
}
