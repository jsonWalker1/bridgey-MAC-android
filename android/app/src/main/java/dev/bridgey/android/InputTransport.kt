package dev.bridgey.android

// First-class abstraction, independent of VideoTransport (frozen spec, section 7-8) and not tied to
// any UI or to AccessibilityService - InputSessionManager (M4/M5, not part of M1) is what will bind
// InputEvent to actual Android injection APIs.

internal enum class PointerAction { DOWN, UP, MOVE, SCROLL }
internal enum class KeyAction { DOWN, UP }

/** KVM Mouse V2, phase 1 (protocol extension only). No mouse-button semantic existed anywhere in
 * Mouse v1 - dispatchGesture is touch-only, and left-click was the only button Mouse v1 ever
 * received, so there was nothing to distinguish it from. */
internal enum class PointerButton { LEFT, RIGHT, MIDDLE }

internal sealed class InputEvent {
    /** [button] only has meaning for DOWN/UP/MOVE (which physical button is down/up/dragging);
     * [scrollDx]/[scrollDy] only have meaning for SCROLL (the wheel/trackpad delta at that position,
     * not a position itself). Both default so every existing Mouse v1 call site - which only ever
     * constructed left-button down/up/move - keeps compiling unchanged. */
    data class Pointer(
        val action: PointerAction,
        val x: Float,
        val y: Float,
        val button: PointerButton = PointerButton.LEFT,
        val scrollDx: Float = 0f,
        val scrollDy: Float = 0f
    ) : InputEvent()
    data class Key(val keyCode: Int, val action: KeyAction) : InputEvent()
    data class Text(val text: String) : InputEvent()
    // Controller (gaming, section 12) intentionally not modeled yet - not part of M1.
}

/** Encodes/decodes the plaintext payload carried inside a frame's ciphertext (frozen spec, section 4).
 * POINTER (down/up/move): [1B action][4B x Float32 BE][4B y Float32 BE][1B button]      -- 10 bytes
 * POINTER (scroll):       [1B action][4B x Float32 BE][4B y Float32 BE][4B scrollDx][4B scrollDy]
 *                                                                          Float32 BE   -- 17 bytes
 * KEY:     [4B keyCode BE][1B action]
 * TEXT:    UTF-8 bytes directly
 *
 * KVM Mouse V2 phase 1 backward compatibility: the original Mouse v1 payload was exactly 9 bytes
 * ([1B action][4B x][4B y], no button/scroll tail at all). [decode] below still accepts that legacy
 * 9-byte payload for down/up/move (defaulting to LEFT, i.e. exactly Mouse v1's only behavior) and a
 * legacy/short scroll payload (defaulting the delta to zero, a harmless no-op scroll) - it only
 * requires the ORIGINAL `>= 9` floor, reading the new trailing byte(s) only when actually present.
 * This is why no new frame type or version byte was needed: POINTER's own decode was already
 * tolerant of a payload longer than 9 bytes (`>=`, never `==`), so appending fields is
 * forward-compatible by construction, and reading them optionally, gated on payload length, is
 * backward-compatible with any peer still sending the plain 9-byte v1 shape. */
internal object InputEventCodec {
    fun encode(event: InputEvent): Pair<Int, ByteArray> = when (event) {
        is InputEvent.Pointer -> {
            val isScroll = event.action == PointerAction.SCROLL
            val buffer = java.nio.ByteBuffer.allocate(if (isScroll) 17 else 10).order(java.nio.ByteOrder.BIG_ENDIAN)
            buffer.put(event.action.ordinal.toByte())
            buffer.putFloat(event.x)
            buffer.putFloat(event.y)
            if (isScroll) {
                buffer.putFloat(event.scrollDx)
                buffer.putFloat(event.scrollDy)
            } else {
                buffer.put(event.button.ordinal.toByte())
            }
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
            val x = buffer.float
            val y = buffer.float
            if (action == PointerAction.SCROLL) {
                if (payload.size < 17) {
                    InputEvent.Pointer(action, x, y, scrollDx = 0f, scrollDy = 0f)
                } else {
                    InputEvent.Pointer(action, x, y, scrollDx = buffer.float, scrollDy = buffer.float)
                }
            } else if (payload.size < 10) {
                InputEvent.Pointer(action, x, y, button = PointerButton.LEFT)
            } else {
                val buttonOrdinal = buffer.get().toInt()
                val button = PointerButton.entries.getOrNull(buttonOrdinal) ?: PointerButton.LEFT
                InputEvent.Pointer(action, x, y, button = button)
            }
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
