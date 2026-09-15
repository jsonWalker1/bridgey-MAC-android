import Foundation

// First-class abstraction, independent of VideoTransport (frozen spec, section 7-8) and not tied to
// any UI or to injection APIs - a future InputSessionController (M4/M5, not part of M1) is what will
// bind InputEvent to actual macOS input-injection APIs. Direct port of InputTransport.kt.

enum PointerAction: Int { case down = 0, up = 1, move = 2, scroll = 3 }
enum KeyAction: Int { case down = 0, up = 1 }

enum InputEvent: Equatable {
    case pointer(action: PointerAction, x: Float, y: Float)
    case key(keyCode: Int32, action: KeyAction)
    case text(String)
    // Controller (gaming, section 12) intentionally not modeled yet - not part of M1.
}

/// Encodes/decodes the plaintext payload carried inside a frame's ciphertext (frozen spec, section 4).
/// POINTER: [1B action][4B x Float32 BE][4B y Float32 BE]
/// KEY:     [4B keyCode BE][1B action]
/// TEXT:    UTF-8 bytes directly
enum InputEventCodec {
    static func encode(_ event: InputEvent) -> (type: Int, payload: Data) {
        switch event {
        case let .pointer(action, x, y):
            var buffer = Data(capacity: 9)
            buffer.append(UInt8(action.rawValue))
            buffer.appendBigEndian(x.bitPattern)
            buffer.appendBigEndian(y.bitPattern)
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
            return .pointer(action: action, x: x, y: y)
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
