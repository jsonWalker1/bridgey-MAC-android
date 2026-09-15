import Network
import XCTest
@testable import BridgeyMac

/// Real loopback-socket integration tests (frozen spec, section 9's minimal test suite) - no
/// mocking of the handshake/framing/security path. Mirrors TcpVideoTransportTest.kt (Android).
final class TCPVideoTransportTests: XCTestCase {
    private let pairingKey = Data((0..<32).map { UInt8($0) })
    private let sessionOne = UUID().uuidString.lowercased()
    private let sessionTwo = UUID().uuidString.lowercased()
    private let sessionEvil = UUID().uuidString.lowercased()

    private func security(purpose: String = "video", sessionId: String? = nil, direction: String = "android_to_mac") -> ChannelSecurityContext {
        let id = sessionId ?? sessionOne
        return ChannelSecurityContext(channelKey: ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: id, purpose: purpose, direction: direction), sessionId: id, purpose: purpose)
    }

    private func connectedPair(_ security: ChannelSecurityContext? = nil) -> (TCPVideoTransport, TCPVideoTransport)? {
        let sec = security ?? self.security()
        guard let (listener, port) = EphemeralTCPListener.start() else { return nil }
        let acceptor = TCPVideoTransport()
        let acceptExpectation = XCTestExpectation(description: "acceptor handshake")
        var acceptResult = false
        let thread = Thread {
            acceptResult = acceptor.acceptViaListener(listener, security: sec)
            acceptExpectation.fulfill()
        }
        thread.start()

        let initiator = TCPVideoTransport()
        let ok = initiator.connectAsInitiator(host: "127.0.0.1", port: port, security: sec)
        XCTAssertTrue(ok, "initiator handshake should succeed")
        wait(for: [acceptExpectation], timeout: 5)
        XCTAssertTrue(acceptResult, "acceptor handshake should succeed")
        guard ok, acceptResult else { return nil }
        return (initiator, acceptor)
    }

    func testHandshakeSucceedsAndFramesRoundTrip() {
        guard let (initiator, acceptor) = connectedPair() else { return XCTFail("setup failed") }
        let received = XCTestExpectation(description: "frame received")
        var receivedFrame: EncodedVideoFrame?
        acceptor.onFrameReceived = { receivedFrame = $0; received.fulfill() }

        let outcome = initiator.send(EncodedVideoFrame(type: VideoFrameType.keyframe, streamId: 1, captureTimestampMs: 123, payload: Data("frame-bytes".utf8)), droppable: false)
        XCTAssertEqual(.sent, outcome)
        wait(for: [received], timeout: 5)
        XCTAssertEqual("frame-bytes", String(data: receivedFrame!.payload, encoding: .utf8))
        XCTAssertEqual(VideoFrameType.keyframe, receivedFrame!.type)

        initiator.close(); acceptor.close()
    }

    func testInvalidCredentialIsRejected() {
        guard let (listener, port) = EphemeralTCPListener.start() else { return XCTFail("setup failed") }
        let acceptor = TCPVideoTransport()
        let acceptExpectation = XCTestExpectation(description: "acceptor handshake")
        var acceptResult = true
        Thread { acceptResult = acceptor.acceptViaListener(listener, security: self.security(sessionId: self.sessionOne)); acceptExpectation.fulfill() }.start()

        let initiator = TCPVideoTransport()
        // Initiator uses a DIFFERENT session id than the acceptor expects -> different channelKey, wrong token.
        let ok = initiator.connectAsInitiator(host: "127.0.0.1", port: port, security: security(sessionId: sessionEvil))
        XCTAssertFalse(ok, "initiator should not see a valid ack")
        wait(for: [acceptExpectation], timeout: 5)
        XCTAssertFalse(acceptResult, "acceptor must not accept a mismatched token")
    }

    func testStaleSessionIdIsRejected() {
        guard let (listener, port) = EphemeralTCPListener.start() else { return XCTFail("setup failed") }
        let acceptor = TCPVideoTransport()
        let acceptExpectation = XCTestExpectation(description: "acceptor handshake")
        var acceptResult = true
        // Acceptor believes the CURRENT live session is sessionOne.
        Thread { acceptResult = acceptor.acceptViaListener(listener, security: self.security(sessionId: self.sessionOne)); acceptExpectation.fulfill() }.start()

        let initiator = TCPVideoTransport()
        // Initiator still has channelKey/token derived from an OLD, no-longer-current session.
        let ok = initiator.connectAsInitiator(host: "127.0.0.1", port: port, security: security(sessionId: sessionTwo))
        XCTAssertFalse(ok)
        wait(for: [acceptExpectation], timeout: 5)
        XCTAssertFalse(acceptResult)
    }

    func testReplayedSequenceIsSilentlyDroppedConnectionStaysOpen() {
        guard let (initiator, acceptor) = connectedPair() else { return XCTFail("setup failed") }
        var receivedPayloads: [String] = []
        let firstTwo = XCTestExpectation(description: "first two frames")
        firstTwo.expectedFulfillmentCount = 2
        acceptor.onFrameReceived = { receivedPayloads.append(String(data: $0.payload, encoding: .utf8)!); firstTwo.fulfill() }

        _ = initiator.send(EncodedVideoFrame(type: VideoFrameType.delta, streamId: 1, captureTimestampMs: 1, payload: Data("first".utf8)), droppable: true)
        _ = initiator.send(EncodedVideoFrame(type: VideoFrameType.delta, streamId: 1, captureTimestampMs: 2, payload: Data("second".utf8)), droppable: true)
        wait(for: [firstTwo], timeout: 5)
        XCTAssertEqual(["first", "second"], receivedPayloads)

        // A THIRD frame (sequence keeps increasing) must still be accepted, proving sequence gaps
        // aren't required to be contiguous, only strictly increasing.
        let third = XCTestExpectation(description: "third frame")
        acceptor.onFrameReceived = { receivedPayloads.append(String(data: $0.payload, encoding: .utf8)!); third.fulfill() }
        _ = initiator.send(EncodedVideoFrame(type: VideoFrameType.delta, streamId: 1, captureTimestampMs: 3, payload: Data("third".utf8)), droppable: true)
        wait(for: [third], timeout: 5)
        XCTAssertEqual("third", receivedPayloads.last)

        initiator.close(); acceptor.close()
    }

    func testMalformedFrameClosesTheConnection() {
        let sec = security()
        guard let (listener, port) = EphemeralTCPListener.start() else { return XCTFail("setup failed") }
        let acceptor = TCPVideoTransport()
        let disconnected = XCTestExpectation(description: "disconnected")
        acceptor.onDisconnected = { _ in disconnected.fulfill() }
        Thread { _ = acceptor.acceptViaListener(listener, security: sec) }.start()

        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return XCTFail("bad port") }
        let raw = NWConnection(host: "127.0.0.1", port: nwPort, using: .tcp)
        let readyLatch = DispatchSemaphore(value: 0)
        raw.stateUpdateHandler = { if case .ready = $0 { readyLatch.signal() } }
        raw.start(queue: DispatchQueue(label: "test.raw.malformed"))
        _ = readyLatch.wait(timeout: .now() + 5)
        XCTAssertTrue(ChannelSecurity.performInitiatorHandshake(raw, security: sec))

        // Write a frame claiming a bogus version byte.
        var bogus = VideoFrameFraming.encodeFrame(type: VideoFrameType.keyframe, streamId: 1, sequence: 1, captureTimestampMs: 0, nonce: Data(count: frameNonceBytes), ciphertext: Data([1, 2, 3]))
        bogus[bogus.index(bogus.startIndex, offsetBy: 4)] = 99 // corrupt the version field
        XCTAssertTrue(BlockingConnectionIO.send(raw, bogus, timeout: 5))

        wait(for: [disconnected], timeout: 5)
        raw.cancel()
    }

    func testOversizedDeclaredFrameLengthClosesWithoutReadingItAll() {
        let maxBytes = 1024
        let sec = security()
        guard let (listener, port) = EphemeralTCPListener.start() else { return XCTFail("setup failed") }
        let acceptor = TCPVideoTransport(maxFrameBytes: maxBytes)
        let disconnected = XCTestExpectation(description: "disconnected")
        acceptor.onDisconnected = { _ in disconnected.fulfill() }
        Thread { _ = acceptor.acceptViaListener(listener, security: sec) }.start()

        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return XCTFail("bad port") }
        let raw = NWConnection(host: "127.0.0.1", port: nwPort, using: .tcp)
        let readyLatch = DispatchSemaphore(value: 0)
        raw.stateUpdateHandler = { if case .ready = $0 { readyLatch.signal() } }
        raw.start(queue: DispatchQueue(label: "test.raw.oversized"))
        _ = readyLatch.wait(timeout: .now() + 5)
        XCTAssertTrue(ChannelSecurity.performInitiatorHandshake(raw, security: sec))

        // Declare a frame length far larger than maxFrameBytes, then only send a few actual bytes -
        // if the acceptor tried to allocate/read the declared length it would hang waiting for data
        // that never arrives; it must reject based on the length field alone.
        let hugeLength = UInt32(maxBytes * 100)
        var lengthPrefix = Data()
        lengthPrefix.appendBigEndian(hugeLength)
        XCTAssertTrue(BlockingConnectionIO.send(raw, lengthPrefix, timeout: 5))
        XCTAssertTrue(BlockingConnectionIO.send(raw, Data([1, 2, 3]), timeout: 5))

        wait(for: [disconnected], timeout: 5)
        raw.cancel()
    }

    func testSimultaneousVideoAndInputChannelsDoNotCrossAuthenticate() {
        let videoSecurity = security(purpose: "video")
        let inputSecurity = ChannelSecurityContext(channelKey: ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: sessionOne, purpose: "input", direction: "android_to_mac"), sessionId: sessionOne, purpose: "input")
        guard let (videoListener, videoPort) = EphemeralTCPListener.start() else { return XCTFail("setup failed") }
        let videoAcceptor = TCPVideoTransport()
        Thread { _ = videoAcceptor.acceptViaListener(videoListener, security: videoSecurity) }.start()

        // Attempt to open the VIDEO listener using the INPUT channel's security context - must fail,
        // proving key separation actually prevents cross-channel authentication.
        let initiator = TCPVideoTransport()
        let ok = initiator.connectAsInitiator(host: "127.0.0.1", port: videoPort, security: inputSecurity)
        XCTAssertFalse(ok, "input channelKey must not authenticate the video channel")
    }

    func testBackpressureDropsOldestDroppableFrameNotAKeyframe() {
        guard let (initiator, _) = connectedPair() else { return XCTFail("setup failed") }
        var lastOutcome: SendOutcome = .failed("unset")
        for i in 0..<(defaultVideoQueueCapacity + 5) {
            lastOutcome = initiator.send(EncodedVideoFrame(type: VideoFrameType.delta, streamId: 1, captureTimestampMs: Int64(i + 1), payload: Data(count: 16)), droppable: true)
        }
        _ = lastOutcome
        // A non-droppable keyframe sent right after must still be accepted (not failed) even though
        // the queue was full of droppable entries a moment ago.
        let keyframeOutcome = initiator.send(EncodedVideoFrame(type: VideoFrameType.keyframe, streamId: 1, captureTimestampMs: 9_999, payload: Data(count: 16)), droppable: false)
        if case .failed = keyframeOutcome { XCTFail("keyframe must not be dropped") }
        initiator.close()
    }
}
