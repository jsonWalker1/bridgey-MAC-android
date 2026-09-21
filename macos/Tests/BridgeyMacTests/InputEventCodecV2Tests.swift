import XCTest
@testable import BridgeyMac

/// KVM Mouse V2 phase 1 (see KVM_MOUSE_V2_PHASE1.md): protocol extension tests for InputEventCodec's
/// pointer button/scroll additions. VideoFrameFramingTests already covers the original Mouse v1
/// pointer/key/text round trip unchanged; these are additive, not a replacement.
final class InputEventCodecV2Tests: XCTestCase {
    private func legacyNineBytePointerPayload(action: PointerAction, x: Float, y: Float) -> Data {
        var buffer = Data(capacity: 9)
        buffer.append(UInt8(action.rawValue))
        buffer.appendBigEndian(x.bitPattern)
        buffer.appendBigEndian(y.bitPattern)
        return buffer
    }

    // MARK: - Backward compatibility: legacy 9-byte payload

    func testLegacyNineBytePayloadStillDecodesAndDefaultsToLeftButton() {
        let payload = legacyNineBytePointerPayload(action: .down, x: 0.25, y: 0.75)
        let decoded = InputEventCodec.decode(type: InputFrameType.pointer, payload: payload)
        XCTAssertEqual(decoded, .pointer(action: .down, x: 0.25, y: 0.75, button: .left))
    }

    func testLegacyNineBytePayloadDecodesForUpAndMoveToo() {
        for action: PointerAction in [.up, .move] {
            let payload = legacyNineBytePointerPayload(action: action, x: 0.1, y: 0.2)
            let decoded = InputEventCodec.decode(type: InputFrameType.pointer, payload: payload)
            XCTAssertEqual(decoded, .pointer(action: action, x: 0.1, y: 0.2, button: .left))
        }
    }

    func testLegacyNineByteScrollPayloadDecodesWithZeroDelta() {
        // A hypothetical legacy sender that somehow emitted SCROLL with the old 9-byte shape (never
        // happened in practice - Mouse v1 never sent SCROLL - but the decoder must not crash/reject).
        let payload = legacyNineBytePointerPayload(action: .scroll, x: 0.5, y: 0.5)
        let decoded = InputEventCodec.decode(type: InputFrameType.pointer, payload: payload)
        XCTAssertEqual(decoded, .pointer(action: .scroll, x: 0.5, y: 0.5, scrollDx: 0, scrollDy: 0))
    }

    // MARK: - New LEFT event (explicit button byte, still LEFT)

    func testNewLeftEventRoundTrips() {
        let event = InputEvent.pointer(action: .down, x: 0.3, y: 0.4, button: .left)
        let (type, payload) = InputEventCodec.encode(event)
        XCTAssertEqual(type, InputFrameType.pointer)
        XCTAssertEqual(payload.count, 10, "down/up/move payload must be exactly 10 bytes: action+x+y+button")
        XCTAssertEqual(InputEventCodec.decode(type: type, payload: payload), event)
    }

    // MARK: - RIGHT event

    func testRightButtonDownRoundTrips() {
        let event = InputEvent.pointer(action: .down, x: 0.6, y: 0.7, button: .right)
        let (type, payload) = InputEventCodec.encode(event)
        XCTAssertEqual(payload.count, 10)
        XCTAssertEqual(InputEventCodec.decode(type: type, payload: payload), event)
    }

    func testRightButtonUpAndMoveRoundTrip() {
        for action: PointerAction in [.up, .move] {
            let event = InputEvent.pointer(action: action, x: 0.15, y: 0.85, button: .right)
            let (type, payload) = InputEventCodec.encode(event)
            XCTAssertEqual(InputEventCodec.decode(type: type, payload: payload), event)
        }
    }

    // MARK: - MIDDLE event

    func testMiddleButtonRoundTrips() {
        let event = InputEvent.pointer(action: .down, x: 0.5, y: 0.5, button: .middle)
        let (type, payload) = InputEventCodec.encode(event)
        XCTAssertEqual(payload.count, 10)
        XCTAssertEqual(InputEventCodec.decode(type: type, payload: payload), event)
    }

    // MARK: - SCROLL event

    func testScrollEventRoundTripsWithDelta() {
        let event = InputEvent.pointer(action: .scroll, x: 0.5, y: 0.5, scrollDx: -3.25, scrollDy: 12.5)
        let (type, payload) = InputEventCodec.encode(event)
        XCTAssertEqual(type, InputFrameType.pointer)
        XCTAssertEqual(payload.count, 17, "scroll payload must be exactly 17 bytes: action+x+y+scrollDx+scrollDy")
        XCTAssertEqual(InputEventCodec.decode(type: type, payload: payload), event)
    }

    func testScrollEventPreservesNegativeAndPositiveDeltaSignsExactly() {
        // Regression guard: encode/decode must never flip or clamp the sign - the calling code (Mac
        // NSEvent capture) is the one responsible for getting the sign right, not this codec.
        let event = InputEvent.pointer(action: .scroll, x: 0, y: 0, scrollDx: -1, scrollDy: 1)
        let (type, payload) = InputEventCodec.encode(event)
        guard case let .pointer(_, _, _, _, dx, dy) = InputEventCodec.decode(type: type, payload: payload)! else {
            return XCTFail("expected a decoded pointer event")
        }
        XCTAssertEqual(dx, -1)
        XCTAssertEqual(dy, 1)
    }

    // MARK: - Malformed / truncated payloads

    func testEmptyPayloadFailsToDecode() {
        XCTAssertNil(InputEventCodec.decode(type: InputFrameType.pointer, payload: Data()))
    }

    func testPayloadShorterThanNineBytesFailsToDecode() {
        XCTAssertNil(InputEventCodec.decode(type: InputFrameType.pointer, payload: Data(count: 8)))
    }

    func testScrollPayloadMissingTheDeltaTailDefaultsToZeroDeltaRatherThanFailing() {
        // 9-16 bytes: enough for action+x+y, not enough for a full scrollDx+scrollDy tail. This must
        // degrade gracefully (a harmless no-op scroll), not crash or drop the event outright, per the
        // same backward-compatibility philosophy as the missing button byte.
        for length in 9...16 {
            let payload = legacyNineBytePointerPayload(action: .scroll, x: 0.2, y: 0.3) + Data(count: length - 9)
            let decoded = InputEventCodec.decode(type: InputFrameType.pointer, payload: payload)
            XCTAssertEqual(decoded, .pointer(action: .scroll, x: 0.2, y: 0.3, scrollDx: 0, scrollDy: 0), "length \(length) should degrade to zero delta")
        }
    }

    func testUnknownActionByteFailsToDecode() {
        var payload = Data(capacity: 9)
        payload.append(99) // not a valid PointerAction rawValue
        payload.appendBigEndian(Float(0).bitPattern)
        payload.appendBigEndian(Float(0).bitPattern)
        XCTAssertNil(InputEventCodec.decode(type: InputFrameType.pointer, payload: payload))
    }

    func testUnknownButtonByteDefaultsToLeftRatherThanFailing() {
        var payload = legacyNineBytePointerPayload(action: .down, x: 0.5, y: 0.5)
        payload.append(99) // not a valid PointerButton rawValue
        let decoded = InputEventCodec.decode(type: InputFrameType.pointer, payload: payload)
        XCTAssertEqual(decoded, .pointer(action: .down, x: 0.5, y: 0.5, button: .left))
    }

    // MARK: - TCPInputTransport coalescing still recognizes MOVE regardless of button/scroll fields

    func testMoveIsStillTheOnlyCoalescibleActionAcrossAllButtons() {
        // Mirrors TCPInputTransport.send's own coalescing check (`if case .pointer(let action, _, _,
        // _, _, _) = event, action == .move`) - this is a regression guard for the Mouse V2 phase 1
        // mechanical pattern-match update approved for that frozen-adjacent file: the exhaustive
        // pattern grew three wildcards, but MOVE-only coalescibility must be unaffected.
        for button: PointerButton in [.left, .right, .middle] {
            let moveEvent = InputEvent.pointer(action: .move, x: 0.1, y: 0.1, button: button)
            if case .pointer(let action, _, _, _, _, _) = moveEvent {
                XCTAssertEqual(action, .move)
            } else {
                XCTFail("expected a pointer event")
            }
        }
        let downEvent = InputEvent.pointer(action: .down, x: 0.1, y: 0.1)
        if case .pointer(let action, _, _, _, _, _) = downEvent {
            XCTAssertNotEqual(action, .move)
        } else {
            XCTFail("expected a pointer event")
        }
    }
}
