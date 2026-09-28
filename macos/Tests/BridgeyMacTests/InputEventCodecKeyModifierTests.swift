import XCTest
@testable import BridgeyMac

/// BRIDGEY KVM KEYBOARD V1: protocol extension tests for InputEventCodec's KEY modifiers byte.
/// VideoFrameFramingTests/TCPInputTransportTests already cover the original 5-byte KEY round trip
/// unchanged; these are additive, not a replacement. Mirrors InputEventCodecV2Tests's structure and
/// its Android counterpart, InputEventCodecKeyModifierTest.kt.
final class InputEventCodecKeyModifierTests: XCTestCase {
    private func legacyFiveByteKeyPayload(keyCode: Int32, action: KeyAction) -> Data {
        var buffer = Data(capacity: 5)
        buffer.appendBigEndian(UInt32(bitPattern: keyCode))
        buffer.append(UInt8(action.rawValue))
        return buffer
    }

    // MARK: - Backward compatibility: legacy 5-byte payload

    func testLegacyFiveBytePayloadStillDecodesAndDefaultsToNoModifiers() {
        let payload = legacyFiveByteKeyPayload(keyCode: 66, action: .down)
        let decoded = InputEventCodec.decode(type: InputFrameType.key, payload: payload)
        XCTAssertEqual(decoded, .key(keyCode: 66, action: .down, modifiers: 0))
    }

    func testLegacyFiveBytePayloadDecodesForUpToo() {
        let payload = legacyFiveByteKeyPayload(keyCode: 67, action: .up)
        let decoded = InputEventCodec.decode(type: InputFrameType.key, payload: payload)
        XCTAssertEqual(decoded, .key(keyCode: 67, action: .up, modifiers: 0))
    }

    // MARK: - New 6-byte payload with modifiers

    func testNoModifiersRoundTrips() {
        let event = InputEvent.key(keyCode: 66, action: .down, modifiers: 0)
        let (type, payload) = InputEventCodec.encode(event)
        XCTAssertEqual(type, InputFrameType.key)
        XCTAssertEqual(payload.count, 6)
        XCTAssertEqual(InputEventCodec.decode(type: type, payload: payload), event)
    }

    func testShiftModifierRoundTrips() {
        let event = InputEvent.key(keyCode: 29, action: .down, modifiers: KeyModifier.shift)
        let (type, payload) = InputEventCodec.encode(event)
        XCTAssertEqual(InputEventCodec.decode(type: type, payload: payload), event)
    }

    func testCtrlModifierRoundTrips() {
        let event = InputEvent.key(keyCode: 31, action: .down, modifiers: KeyModifier.control)
        let (type, payload) = InputEventCodec.encode(event)
        XCTAssertEqual(InputEventCodec.decode(type: type, payload: payload), event)
    }

    func testCmdMetaModifierRoundTrips() {
        let event = InputEvent.key(keyCode: 54, action: .down, modifiers: KeyModifier.meta)
        let (type, payload) = InputEventCodec.encode(event)
        XCTAssertEqual(InputEventCodec.decode(type: type, payload: payload), event)
    }

    func testCombinedModifiersRoundTrip() {
        let combined = KeyModifier.shift | KeyModifier.control | KeyModifier.alt | KeyModifier.meta
        let event = InputEvent.key(keyCode: 54, action: .down, modifiers: combined)
        let (type, payload) = InputEventCodec.encode(event)
        guard case let .key(_, _, modifiers) = InputEventCodec.decode(type: type, payload: payload)! else {
            return XCTFail("expected a decoded key event")
        }
        XCTAssertEqual(modifiers, combined)
    }

    // MARK: - Malformed / truncated payloads

    func testPayloadShorterThanFiveBytesFailsToDecode() {
        XCTAssertNil(InputEventCodec.decode(type: InputFrameType.key, payload: Data(count: 4)))
    }

    func testUnknownActionByteFailsToDecode() {
        var payload = Data(capacity: 6)
        payload.appendBigEndian(UInt32(66))
        payload.append(99) // not a valid KeyAction rawValue
        payload.append(0)
        XCTAssertNil(InputEventCodec.decode(type: InputFrameType.key, payload: payload))
    }
}
