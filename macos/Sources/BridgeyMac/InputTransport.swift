import Foundation

// First-class abstraction, independent of VideoTransport (frozen spec, section 7-8) and not tied to
// any UI or to injection APIs - a future InputSessionController (M4/M5, not part of M1) is what will
// bind InputEvent to actual macOS input-injection APIs. Direct port of InputTransport.kt.

enum PointerAction: Int { case down = 0, up = 1, move = 2, scroll = 3 }
enum KeyAction: Int { case down = 0, up = 1 }

/// KVM Mouse V2, phase 1 (protocol extension only - see BRIDGEY_KVM_STATE.md / the Mouse V2 design
/// doc for why this frozen-since-M1 file is being extended rather than left alone). No mouse-button
/// semantic existed anywhere in Mouse v1 - dispatchGesture is touch-only, and left-click was the only
/// button Mouse v1 ever captured or sent, so there was nothing to distinguish it from.
enum PointerButton: Int { case left = 0, right = 1, middle = 2 }

enum InputEvent: Equatable {
    /// `button` only has meaning for down/up/move (which physical button is down/up/dragging);
    /// `scrollDx`/`scrollDy` only have meaning for `.scroll` (the wheel/trackpad delta at that
    /// position, not a position itself). Both are carried on every case rather than splitting into
    /// separate enum cases so the wire codec below stays a single, simple switch over `action` - the
    /// "unused for this action" fields just take their zero/left defaults at construction. See
    /// InputEventCodec's doc comment for the exact per-action wire layout.
    case pointer(action: PointerAction, x: Float, y: Float, button: PointerButton = .left, scrollDx: Float = 0, scrollDy: Float = 0)
    case key(keyCode: Int32, action: KeyAction)
    case text(String)
    // Controller (gaming, section 12) intentionally not modeled yet - not part of M1.
}

/// Encodes/decodes the plaintext payload carried inside a frame's ciphertext (frozen spec, section 4).
/// POINTER (down/up/move): [1B action][4B x Float32 BE][4B y Float32 BE][1B button]      -- 10 bytes
/// POINTER (scroll):       [1B action][4B x Float32 BE][4B y Float32 BE][4B scrollDx][4B scrollDy]
///                                                                          Float32 BE   -- 17 bytes
/// KEY:     [4B keyCode BE][1B action]
/// TEXT:    UTF-8 bytes directly
///
/// KVM Mouse V2 phase 1 backward compatibility: the original Mouse v1 payload was exactly 9 bytes
/// ([1B action][4B x][4B y], no button/scroll tail at all). `decode` below still accepts that legacy
/// 9-byte payload for down/up/move (defaulting to `.left`, i.e. exactly Mouse v1's only behavior) and
/// a legacy/short scroll payload (defaulting the delta to zero, i.e. a harmless no-op scroll) - it
/// only requires the ORIGINAL `>= 9` floor, reading the new trailing byte(s) only when actually
/// present. This is why no new frame type or version byte was needed: POINTER's own decode was
/// already tolerant of a payload longer than 9 bytes (`>=`, never `==`), so appending fields is
/// forward-compatible by construction, and reading them optionally, gated on payload length, is
/// backward-compatible with any peer still sending the plain 9-byte v1 shape.
enum InputEventCodec {
    static func encode(_ event: InputEvent) -> (type: Int, payload: Data) {
        switch event {
        case let .pointer(action, x, y, button, scrollDx, scrollDy):
            var buffer = Data(capacity: action == .scroll ? 17 : 10)
            buffer.append(UInt8(action.rawValue))
            buffer.appendBigEndian(x.bitPattern)
            buffer.appendBigEndian(y.bitPattern)
            if action == .scroll {
                buffer.appendBigEndian(scrollDx.bitPattern)
                buffer.appendBigEndian(scrollDy.bitPattern)
            } else {
                buffer.append(UInt8(button.rawValue))
            }
            return (InputFrameType.pointer, buffer)
        case let .key(keyCode, action):
            var buffer = Data(capacity: 5)
            buffer.appendBigEndian(UInt32(bitPattern: keyCode))
            buffer.append(UInt8(action.rawValue))
            return (InputFrameType.key, buffer)
        case let .text(text):
            return (InputFrameType.text, Data(text.utf8))
        }
    }

    static func decode(type: Int, payload: Data) -> InputEvent? {
        switch type {
        case InputFrameType.pointer:
            guard payload.count >= 9 else { return nil }
            var offset = payload.startIndex
            guard let action = PointerAction(rawValue: Int(payload[offset])) else { return nil }
            offset += 1
            let x = Float(bitPattern: payload.readBigEndianUInt32(at: &offset))
            let y = Float(bitPattern: payload.readBigEndianUInt32(at: &offset))
            if action == .scroll {
                guard payload.count >= 17 else {
                    return .pointer(action: action, x: x, y: y, scrollDx: 0, scrollDy: 0)
                }
                let scrollDx = Float(bitPattern: payload.readBigEndianUInt32(at: &offset))
                let scrollDy = Float(bitPattern: payload.readBigEndianUInt32(at: &offset))
                return .pointer(action: action, x: x, y: y, scrollDx: scrollDx, scrollDy: scrollDy)
            }
            guard payload.count >= 10, let button = PointerButton(rawValue: Int(payload[offset])) else {
                return .pointer(action: action, x: x, y: y, button: .left)
            }
            return .pointer(action: action, x: x, y: y, button: button)
        case InputFrameType.key:
            guard payload.count >= 5 else { return nil }
            var offset = payload.startIndex
            let keyCode = Int32(bitPattern: payload.readBigEndianUInt32(at: &offset))
            guard let action = KeyAction(rawValue: Int(payload[offset])) else { return nil }
            return .key(keyCode: keyCode, action: action)
        case InputFrameType.text:
            return .text(String(decoding: payload, as: UTF8.self))
        default:
            return nil
        }
    }
}

protocol InputTransport: AnyObject {
    var capabilities: TransportCapabilities { get }
    var metrics: TransportMetrics { get }
    var onEventReceived: (InputEvent) -> Void { get set }
    var onDisconnected: (Error?) -> Void { get set }

    /// Pointer down/up and key down/up must never be silently dropped (frozen backpressure policy);
    /// only pointer move events are coalescible/droppable.
    func send(_ event: InputEvent) -> SendOutcome
    func close()
}
