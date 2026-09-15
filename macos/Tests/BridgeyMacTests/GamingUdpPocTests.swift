import CryptoKit
import XCTest
@testable import BridgeyMac

/// GAMING MODE POC tests - Swift mirror of GamingUdpPocTest.kt, proving the same protocol design
/// works identically on macOS/CryptoKit, with no platform-specific blocker.
final class GamingUdpPocTests: XCTestCase {
    private let testKey = SymmetricKey(size: .bits256)

    private func seal(aad: Data, plaintext: Data) -> (nonce: Data, ciphertext: Data) {
        let sealed = try! AES.GCM.seal(plaintext, using: testKey, authenticating: aad)
        return (Data(sealed.nonce), sealed.ciphertext + sealed.tag)
    }

    private func open(aad: Data, nonce: Data, ciphertext: Data) -> Data? {
        guard ciphertext.count >= GamingUdpFraming.tagBytes,
              let gcmNonce = try? AES.GCM.Nonce(data: nonce) else { return nil }
        guard let box = try? AES.GCM.SealedBox(
            nonce: gcmNonce,
            ciphertext: ciphertext.dropLast(GamingUdpFraming.tagBytes),
            tag: ciphertext.suffix(GamingUdpFraming.tagBytes)
        ) else { return nil }
        return try? AES.GCM.open(box, using: testKey, authenticating: aad)
    }

    private func buildFragmentPackets(
        frameId: UInt64, flags: Int, payload: Data, sessionToken: UInt64 = 42, sequenceStart: UInt32 = 0
    ) -> [Data] {
        let fragments = GamingUdpFraming.splitIntoFragments(payload)
        return fragments.enumerated().map { index, fragment in
            let header = GamingUdpFraming.Header(
                type: GamingUdpFraming.PacketType.videoFrameFragment,
                gamingSessionToken: sessionToken,
                frameId: frameId,
                fragmentIndex: index,
                fragmentCount: fragments.count,
                captureTimestampMs: 1000,
                flags: flags,
                sequence: sequenceStart + UInt32(index)
            )
            let aad = GamingUdpFraming.encodeHeader(header)
            let (nonce, ciphertext) = seal(aad: aad, plaintext: fragment)
            return GamingUdpFraming.assemble(header, nonce: nonce, ciphertext: ciphertext)
        }
    }

    func testFragmentationRoundTripsAFrameLargerThanOneFragment() {
        let payload = Data((0..<(GamingUdpFraming.maxFragmentPayloadBytes * 3 + 500)).map { _ in UInt8.random(in: 0...255) })
        let packets = buildFragmentPackets(frameId: 1, flags: 0, payload: payload)
        XCTAssertGreaterThan(packets.count, 1)

        var lostCalls = 0
        let reassembler = GamingFrameReassembler(onKeyframeOrConfigLost: { lostCalls += 1 })
        var result: Data?
        for packet in packets {
            let parsed = GamingUdpFraming.parse(packet)!
            let plaintext = open(aad: parsed.aad, nonce: parsed.nonce, ciphertext: parsed.ciphertext)!
            result = reassembler.onFragment(
                frameId: parsed.header.frameId, fragmentIndex: parsed.header.fragmentIndex,
                fragmentCount: parsed.header.fragmentCount, flags: parsed.header.flags,
                nowMs: 0, payload: plaintext
            ) ?? result
        }
        XCTAssertEqual(result, payload)
        XCTAssertEqual(lostCalls, 0)
    }

    func testADeltaFrameMissingAFragmentNeverCompletes() {
        let payload = Data((0..<(GamingUdpFraming.maxFragmentPayloadBytes * 2 + 10)).map { _ in UInt8.random(in: 0...255) })
        let packets = buildFragmentPackets(frameId: 1, flags: 0, payload: payload)
        XCTAssertGreaterThanOrEqual(packets.count, 2)
        var lostCalls = 0
        let reassembler = GamingFrameReassembler(onKeyframeOrConfigLost: { lostCalls += 1 })
        for packet in packets.dropLast() {
            let parsed = GamingUdpFraming.parse(packet)!
            let plaintext = open(aad: parsed.aad, nonce: parsed.nonce, ciphertext: parsed.ciphertext)!
            let result = reassembler.onFragment(
                frameId: parsed.header.frameId, fragmentIndex: parsed.header.fragmentIndex,
                fragmentCount: parsed.header.fragmentCount, flags: parsed.header.flags,
                nowMs: 0, payload: plaintext
            )
            XCTAssertNil(result)
        }
        XCTAssertEqual(lostCalls, 0, "a plain delta loss must not trigger keyframe/config recovery")
    }

    func testANewerFramePreemptsAnOlderIncompleteFrameImmediately() {
        let oldPayload = Data((0..<(GamingUdpFraming.maxFragmentPayloadBytes * 2 + 10)).map { _ in UInt8.random(in: 0...255) })
        let newPayload = Data((0..<50).map { _ in UInt8.random(in: 0...255) })
        let oldPackets = buildFragmentPackets(frameId: 1, flags: 0, payload: oldPayload)
        let newPackets = buildFragmentPackets(frameId: 2, flags: 0, payload: newPayload, sequenceStart: 100)

        let reassembler = GamingFrameReassembler(onKeyframeOrConfigLost: {})
        let firstOld = GamingUdpFraming.parse(oldPackets.first!)!
        _ = reassembler.onFragment(
            frameId: firstOld.header.frameId, fragmentIndex: firstOld.header.fragmentIndex,
            fragmentCount: firstOld.header.fragmentCount, flags: firstOld.header.flags,
            nowMs: 0, payload: open(aad: firstOld.aad, nonce: firstOld.nonce, ciphertext: firstOld.ciphertext)!
        )
        var result: Data?
        for packet in newPackets {
            let parsed = GamingUdpFraming.parse(packet)!
            let plaintext = open(aad: parsed.aad, nonce: parsed.nonce, ciphertext: parsed.ciphertext)!
            result = reassembler.onFragment(
                frameId: parsed.header.frameId, fragmentIndex: parsed.header.fragmentIndex,
                fragmentCount: parsed.header.fragmentCount, flags: parsed.header.flags,
                nowMs: 1, payload: plaintext
            ) ?? result
        }
        XCTAssertEqual(result, newPayload)
    }

    func testADroppedKeyframeTriggersRecoveryButADroppedDeltaDoesNot() {
        var lostCalls = 0
        let reassembler = GamingFrameReassembler(onKeyframeOrConfigLost: { lostCalls += 1 })
        let keyframePayload = Data((0..<(GamingUdpFraming.maxFragmentPayloadBytes * 2 + 10)).map { _ in UInt8.random(in: 0...255) })
        let keyframePackets = buildFragmentPackets(frameId: 1, flags: GamingUdpFraming.Flags.keyframe, payload: keyframePayload)
        for packet in keyframePackets.dropLast() {
            let parsed = GamingUdpFraming.parse(packet)!
            _ = reassembler.onFragment(
                frameId: parsed.header.frameId, fragmentIndex: parsed.header.fragmentIndex,
                fragmentCount: parsed.header.fragmentCount, flags: parsed.header.flags,
                nowMs: 0, payload: open(aad: parsed.aad, nonce: parsed.nonce, ciphertext: parsed.ciphertext)!
            )
        }
        XCTAssertEqual(lostCalls, 0)
        let deltaPackets = buildFragmentPackets(frameId: 2, flags: 0, payload: Data([1, 2, 3]), sequenceStart: 50)
        for packet in deltaPackets {
            let parsed = GamingUdpFraming.parse(packet)!
            _ = reassembler.onFragment(
                frameId: parsed.header.frameId, fragmentIndex: parsed.header.fragmentIndex,
                fragmentCount: parsed.header.fragmentCount, flags: parsed.header.flags,
                nowMs: 1, payload: open(aad: parsed.aad, nonce: parsed.nonce, ciphertext: parsed.ciphertext)!
            )
        }
        XCTAssertEqual(lostCalls, 1)
    }

    func testTamperingWithAHeaderFieldInvalidatesTheAeadTag() {
        let payload = Data([1, 2, 3, 4])
        var tampered = buildFragmentPackets(frameId: 1, flags: 0, payload: payload).first!
        tampered[3] ^= 0x01
        let parsed = GamingUdpFraming.parse(tampered)!
        XCTAssertNil(open(aad: parsed.aad, nonce: parsed.nonce, ciphertext: parsed.ciphertext))
    }

    func testReplayWindowRejectsDuplicatesButAcceptsInWindowReordering() {
        let window = ReplayWindow(windowSize: 32)
        XCTAssertTrue(window.acceptAndRecord(10))
        XCTAssertTrue(window.acceptAndRecord(12))
        XCTAssertTrue(window.acceptAndRecord(11))
        XCTAssertFalse(window.acceptAndRecord(11))
        XCTAssertFalse(window.acceptAndRecord(10))
    }

    func testMtuSafeFragmentationNeverExceedsTheConfiguredMaximum() {
        let payload = Data((0..<(GamingUdpFraming.maxFragmentPayloadBytes * 5 + 37)).map { _ in UInt8.random(in: 0...255) })
        let fragments = GamingUdpFraming.splitIntoFragments(payload)
        for fragment in fragments { XCTAssertLessThanOrEqual(fragment.count, GamingUdpFraming.maxFragmentPayloadBytes) }
        XCTAssertEqual(fragments.reduce(0) { $0 + $1.count }, payload.count)
    }
}
