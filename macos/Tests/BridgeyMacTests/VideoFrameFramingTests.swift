import XCTest
@testable import BridgeyMac

final class VideoFrameFramingTests: XCTestCase {
    private let nonce = Data((0..<frameNonceBytes).map { UInt8($0) })
    private let ciphertext = Data("encrypted-payload-bytes".utf8)

    func testEncodeThenParseRoundTripsAllHeaderFields() {
        let wire = VideoFrameFraming.encodeFrame(type: VideoFrameType.keyframe, streamId: 42, sequence: 7, captureTimestampMs: 1_700_000_000_000, nonce: nonce, ciphertext: ciphertext)
        var offset = wire.startIndex
        let declaredLength = Int(wire.readBigEndianUInt32(at: &offset))
        XCTAssertEqual(wire.count - 4, declaredLength)
        let body = wire[offset...]

        let parsed = VideoFrameFraming.parseFrameBody(Data(body))!
        XCTAssertEqual(frameVersion, parsed.header.version)
        XCTAssertEqual(VideoFrameType.keyframe, parsed.header.type)
        XCTAssertEqual(42, parsed.header.streamId)
        XCTAssertEqual(7, parsed.header.sequence)
        XCTAssertEqual(1_700_000_000_000, parsed.header.captureTimestampMs)
        XCTAssertEqual(nonce, parsed.header.nonce)
        XCTAssertEqual(ciphertext, parsed.ciphertext)
    }

    func testParseRejectsABodyTooShortToContainAHeader() {
        XCTAssertNil(VideoFrameFraming.parseFrameBody(Data(count: 10)))
    }

    func testParseAcceptsAZeroLengthCiphertextBody() {
        let wire = VideoFrameFraming.encodeFrame(type: VideoFrameType.keyframeRequest, streamId: 1, sequence: 1, captureTimestampMs: 0, nonce: nonce, ciphertext: Data())
        let body = wire[wire.index(wire.startIndex, offsetBy: 4)...]
        let parsed = VideoFrameFraming.parseFrameBody(Data(body))!
        XCTAssertEqual(0, parsed.ciphertext.count)
    }

    func testInputEventCodecRoundTripsPointerKeyAndText() {
        let pointer = InputEvent.pointer(action: .down, x: 0.25, y: 0.75)
        let (pType, pPayload) = InputEventCodec.encode(pointer)
        XCTAssertEqual(pointer, InputEventCodec.decode(type: pType, payload: pPayload))

        let key = InputEvent.key(keyCode: 66, action: .up)
        let (kType, kPayload) = InputEventCodec.encode(key)
        XCTAssertEqual(key, InputEventCodec.decode(type: kType, payload: kPayload))

        let text = InputEvent.text("hello android")
        let (tType, tPayload) = InputEventCodec.encode(text)
        XCTAssertEqual(text, InputEventCodec.decode(type: tType, payload: tPayload))
    }

    func testVideoAndInputFrameTypeRangesDoNotOverlap() {
        XCTAssertTrue(VideoFrameType.all.isDisjoint(with: InputFrameType.all))
    }
}
