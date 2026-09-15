import XCTest
@testable import BridgeyMac

final class ChannelSecurityTests: XCTestCase {
    private let pairingKey = Data((0..<32).map { UInt8($0) })

    func testChannelKeyDerivationIsDeterministic() {
        let a = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-1", purpose: "video", direction: "android_to_mac")
        let b = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-1", purpose: "video", direction: "android_to_mac")
        XCTAssertEqual(a, b)
    }

    func testVideoAndInputChannelsGetDistinctKeysForTheSameSession() {
        let video = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-1", purpose: "video", direction: "android_to_mac")
        let input = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-1", purpose: "input", direction: "android_to_mac")
        XCTAssertNotEqual(video, input)
    }

    func testDifferentDirectionsGetDistinctKeysForTheSamePurposeAndSession() {
        let a = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-1", purpose: "video", direction: "android_to_mac")
        let b = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-1", purpose: "video", direction: "mac_to_android")
        XCTAssertNotEqual(a, b)
    }

    func testDifferentSessionsGetDistinctKeys() {
        let a = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-1", purpose: "video", direction: "android_to_mac")
        let b = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-2", purpose: "video", direction: "android_to_mac")
        XCTAssertNotEqual(a, b)
    }

    func testTokenAndAckProofAreDistinctEvenWithIdenticalInputs() {
        let key = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-1", purpose: "video", direction: "android_to_mac")
        let nonce = ChannelSecurity.generateOpenNonce()
        let token = ChannelSecurity.computeToken(channelKey: key, sessionId: "session-1", purpose: "video", openNonce: nonce)
        let ack = ChannelSecurity.computeAckProof(channelKey: key, sessionId: "session-1", purpose: "video", openNonce: nonce)
        XCTAssertNotEqual(token, ack)
    }

    func testTokenVerificationSucceedsForTheCorrectKeyAndFailsForAnyOther() {
        let key = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-1", purpose: "video", direction: "android_to_mac")
        let wrongKey = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-1", purpose: "input", direction: "android_to_mac")
        let nonce = ChannelSecurity.generateOpenNonce()
        let token = ChannelSecurity.computeToken(channelKey: key, sessionId: "session-1", purpose: "video", openNonce: nonce)
        XCTAssertTrue(ChannelSecurity.constantTimeEquals(token, ChannelSecurity.computeToken(channelKey: key, sessionId: "session-1", purpose: "video", openNonce: nonce)))
        XCTAssertFalse(ChannelSecurity.constantTimeEquals(token, ChannelSecurity.computeToken(channelKey: wrongKey, sessionId: "session-1", purpose: "video", openNonce: nonce)))
    }

    func testUUIDRoundTripsThroughItsSixteenByteBinaryForm() {
        let uuid = UUID().uuidString.lowercased()
        let bytes = ChannelSecurity.uuidToBytes(uuid)
        XCTAssertEqual(bytes?.count, 16)
        XCTAssertEqual(ChannelSecurity.uuidFromBytes(bytes!), uuid)
    }

    func testSealThenOpenRoundTripsThePlaintext() {
        let key = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-1", purpose: "video", direction: "android_to_mac")
        let plaintext = Data("hello video channel".utf8)
        let sealed = ChannelSecurity.seal(key: key, plaintext: plaintext)
        XCTAssertNotNil(sealed)
        let opened = ChannelSecurity.open(key: key, nonce: sealed!.nonce, ciphertext: sealed!.ciphertext)
        XCTAssertEqual(opened, plaintext)
    }

    func testOpenFailsForATamperedCiphertext() {
        let key = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-1", purpose: "video", direction: "android_to_mac")
        let sealed = ChannelSecurity.seal(key: key, plaintext: Data("hello".utf8))!
        var tampered = sealed.ciphertext
        tampered[tampered.startIndex] ^= 0xFF
        XCTAssertNil(ChannelSecurity.open(key: key, nonce: sealed.nonce, ciphertext: tampered))
    }

    func testSealProducesADifferentNonceEveryTime() {
        let key = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: "session-1", purpose: "video", direction: "android_to_mac")
        let a = ChannelSecurity.seal(key: key, plaintext: Data("same plaintext".utf8))!
        let b = ChannelSecurity.seal(key: key, plaintext: Data("same plaintext".utf8))!
        XCTAssertNotEqual(a.nonce, b.nonce)
    }
}
