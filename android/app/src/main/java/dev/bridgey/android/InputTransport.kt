package dev.bridgey.android

// First-class abstraction, independent of VideoTransport (frozen spec, section 7-8) and not tied to
// any UI or to AccessibilityService - InputSessionManager (M4/M5, not part of M1) is what will bind
// InputEvent to actual Android injection APIs.

internal enum class PointerAction { DOWN, UP, MOVE, SCROLL }
internal enum class KeyAction { DOWN, UP }

/** BRIDGEY KVM KEYBOARD V1 (protocol extension - see InputEventCodec's doc comment for the wire byte
 * this occupies). Bitmask, not an enum: a real key event can carry any combination simultaneously
 * (e.g. Ctrl+Shift+Z). Declaration must match Swift's KeyModifier enum exactly (same bit positions are
 * the wire value), mirroring how GestureAction's ordinals are a frozen cross-platform contract
 * elsewhere in this file. */
internal object KeyModifier {
    const val SHIFT: Int = 1 shl 0
    const val CONTROL: Int = 1 shl 1
    const val ALT: Int = 1 shl 2
    const val META: Int = 1 shl 3
}

/** BRIDGEY KVM TOUCHPAD GESTURES V1: a fully resolved semantic action - never coordinates, velocity,
 * duration, or raw touch points. [FORWARD] deliberately has no Android-side action (there is no
 * system-wide "forward" concept in AccessibilityService's GLOBAL_ACTION_* API, confirmed against the
 * actual android.jar - not guessed) - [BridgeyAccessibilityService.handleGesture] logs/reports it as
 * unsupported rather than silently ignoring it. Declaration order must match Swift's GestureAction
 * rawValue order exactly (BACK=0, FORWARD=1, HOME=2, NOTIFICATIONS=3, RECENTS=4) - ordinal is the wire
 * value, mirroring how PointerAction/PointerButton/KeyAction already do it above. */
internal enum class GestureAction { BACK, FORWARD, HOME, NOTIFICATIONS, RECENTS }

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
    /** BRIDGEY KVM KEYBOARD V1: [modifiers] is a [KeyModifier] bitmask, defaulted so every pre-existing
     * call site (and test) that only ever passed [keyCode]/[action] keeps compiling unchanged. */
    data class Key(val keyCode: Int, val action: KeyAction, val modifiers: Int = 0) : InputEvent()
    data class Text(val text: String) : InputEvent()
    /** BRIDGEY KVM TOUCHPAD GESTURES V1: a fully resolved semantic action only - see [GestureAction]'s
     * own doc comment for why no coordinates/velocity/duration/raw touch data are carried. */
    data class Gesture(val action: GestureAction) : InputEvent()
    // Controller (gaming, section 12) intentionally not modeled yet - not part of M1.
}

/** Encodes/decodes the plaintext payload carried inside a frame's ciphertext (frozen spec, section 4).
 * POINTER (down/up/move): [1B action][4B x Float32 BE][4B y Float32 BE][1B button]      -- 10 bytes
 * POINTER (scroll):       [1B action][4B x Float32 BE][4B y Float32 BE][4B scrollDx][4B scrollDy]
 *                                                                          Float32 BE   -- 17 bytes
 * KEY:     [4B keyCode BE][1B action][1B modifiers]                                       -- 6 bytes
 * TEXT:    UTF-8 bytes directly
 * GESTURE: [1B action]                                                                     -- 1 byte
 *
 * KVM Mouse V2 phase 1 backward compatibility: the original Mouse v1 payload was exactly 9 bytes
 * ([1B action][4B x][4B y], no button/scroll tail at all). [decode] below still accepts that legacy
 * 9-byte payload for down/up/move (defaulting to LEFT, i.e. exactly Mouse v1's only behavior) and a
 * legacy/short scroll payload (defaulting the delta to zero, a harmless no-op scroll) - it only
 * requires the ORIGINAL `>= 9` floor, reading the new trailing byte(s) only when actually present.
 * This is why no new frame type or version byte was needed: POINTER's own decode was already
 * tolerant of a payload longer than 9 bytes (`>=`, never `==`), so appending fields is
 * forward-compatible by construction, and reading them optionally, gated on payload length, is
 * backward-compatible with any peer still sending the plain 9-byte v1 shape.
 *
 * BRIDGEY KVM KEYBOARD V1 backward compatibility: the original KEY payload was exactly 5 bytes (no
 * modifiers byte at all). [decode] still accepts that legacy 5-byte shape, defaulting modifiers to 0
 * (no modifiers held) - exactly the same `>=` floor + optional-trailing-byte pattern as POINTER's
 * button/scroll extension above. */
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
            val buffer = java.nio.ByteBuffer.allocate(6).order(java.nio.ByteOrder.BIG_ENDIAN)
            buffer.putInt(event.keyCode)
            buffer.put(event.action.ordinal.toByte())
            buffer.put(event.modifiers.toByte())
            InputFrameType.KEY to buffer.array()
        }
        is InputEvent.Text -> InputFrameType.TEXT to event.text.toByteArray(Charsets.UTF_8)
        is InputEvent.Gesture -> InputFrameType.GESTURE to byteArrayOf(event.action.ordinal.toByte())
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
            val modifiers = if (payload.size >= 6) buffer.get().toInt() and 0xFF else 0
            InputEvent.Key(keyCode, action, modifiers)
        }
        InputFrameType.TEXT -> InputEvent.Text(String(payload, Charsets.UTF_8))
        InputFrameType.GESTURE -> {
            if (payload.isEmpty()) return null
            val action = GestureAction.entries.getOrNull(payload[0].toInt()) ?: return null
            InputEvent.Gesture(action)
        }
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
