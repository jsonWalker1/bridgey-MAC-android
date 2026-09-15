import XCTest
@testable import BridgeyMac

/// Controller-level tests: two VideoChannelControllers wired directly to each other's receive()
/// (standing in for the real control channel, which is out of scope here - only negotiation/
/// establishment/reset behavior is under test, not the control-channel transport itself). Mirrors
/// VideoChannelManagerTest.kt (Android).
final class VideoChannelControllerTests: XCTestCase {
    private let pairingKey = Data((0..<32).map { UInt8($0) })

    private final class Harness {
        var peer: Harness?
        let pairingKey: Data
        let sessionIdProvider: () -> String?
        // lazy so the `send` closure can safely capture [weak self] (self is fully initialized by
        // the time this is first accessed, i.e. after `peer` has been wired in wiredPair()).
        lazy var controller: VideoChannelController = VideoChannelController(
            available: { true },
            send: { [weak self] kind, payload in self?.peer?.controller.receive(kind: kind, payload: payload); return true },
            pairingKeyProvider: { [weak self] in self?.pairingKey },
            sessionIdProvider: sessionIdProvider,
            remoteHostProvider: { "127.0.0.1" }
        )
        init(pairingKey: Data, sessionId: @escaping () -> String?) {
            self.pairingKey = pairingKey
            self.sessionIdProvider = sessionId
        }
    }

    private func wiredPair(sessionId: @escaping () -> String?) -> (Harness, Harness) {
        let a = Harness(pairingKey: pairingKey, sessionId: sessionId)
        let b = Harness(pairingKey: pairingKey, sessionId: sessionId)
        a.peer = b
        b.peer = a
        return (a, b)
    }

    private func awaitState(_ get: @escaping () -> ChannelState, _ target: ChannelState, timeoutMs: Int = 5000) {
        let deadline = Date().addingTimeInterval(TimeInterval(timeoutMs) / 1000)
        while Date() < deadline {
            if get() == target { return }
            Thread.sleep(forTimeInterval: 0.01)
        }
        XCTAssertEqual(target, get())
    }

    func testVideoAndInputNegotiateSimultaneouslyThroughTheControllerAndDeliverPayloads() {
        let sessionId = UUID().uuidString.lowercased()
        let (a, b) = wiredPair(sessionId: { sessionId })

        let videoReceived = XCTestExpectation(description: "video received")
        let inputReceived = XCTestExpectation(description: "input received")
        b.controller.onVideoFrame = { _ in videoReceived.fulfill() }
        b.controller.onInputEvent = { _ in inputReceived.fulfill() }

        a.controller.offerVideo(direction: "android_to_mac", width: 1080, height: 2400, bitrateKbps: 4000, fps: 30)
        a.controller.offerInput(direction: "mac_to_android")

        awaitState({ a.controller.currentVideoState }, .active)
        awaitState({ b.controller.currentVideoState }, .active)
        awaitState({ a.controller.currentInputState }, .active)
        awaitState({ b.controller.currentInputState }, .active)

        let videoOutcome = a.controller.sendVideoFrame(EncodedVideoFrame(type: VideoFrameType.keyframe, streamId: 1, captureTimestampMs: 1, payload: Data("hi".utf8)), droppable: false)
        XCTAssertEqual(.sent, videoOutcome)
        let inputOutcome = a.controller.sendInputEvent(.pointer(action: .down, x: 0.5, y: 0.5))
        XCTAssertEqual(.sent, inputOutcome)

        wait(for: [videoReceived, inputReceived], timeout: 5)
    }

    func testResetFromActiveIsImmediateAndClosesTheTransport() {
        let sessionId = UUID().uuidString.lowercased()
        let (a, b) = wiredPair(sessionId: { sessionId })
        a.controller.offerVideo(direction: "android_to_mac", width: 1080, height: 2400, bitrateKbps: 4000, fps: 30)
        awaitState({ a.controller.currentVideoState }, .active)
        awaitState({ b.controller.currentVideoState }, .active)

        a.controller.reset()
        XCTAssertEqual(.idle, a.controller.currentVideoState)
        XCTAssertEqual(.idle, a.controller.currentInputState)
        let outcome = a.controller.sendVideoFrame(EncodedVideoFrame(type: VideoFrameType.delta, streamId: 1, captureTimestampMs: 2, payload: Data("x".utf8)), droppable: true)
        if case .failed = outcome {} else { XCTFail("expected failed outcome after reset") }
    }

    func testFreshNegotiationAfterResetReachesActiveAgainWithANewSession() {
        var sessionId = UUID().uuidString.lowercased()
        let (a, b) = wiredPair(sessionId: { sessionId })
        a.controller.offerVideo(direction: "android_to_mac", width: 1080, height: 2400, bitrateKbps: 4000, fps: 30)
        awaitState({ a.controller.currentVideoState }, .active)
        awaitState({ b.controller.currentVideoState }, .active)

        a.controller.reset()
        b.controller.reset()
        XCTAssertEqual(.idle, a.controller.currentVideoState)
        XCTAssertEqual(.idle, b.controller.currentVideoState)

        // A reconnect establishes a brand new session id - channelKey derivation (verified at the
        // ChannelSecurity unit level) binds sessionId into the salt, so this is a distinct channelKey
        // from the first negotiation even though pairingKey/purpose/direction are unchanged.
        sessionId = UUID().uuidString.lowercased()
        a.controller.offerVideo(direction: "android_to_mac", width: 1080, height: 2400, bitrateKbps: 4000, fps: 30)
        awaitState({ a.controller.currentVideoState }, .active)
        awaitState({ b.controller.currentVideoState }, .active)
    }

    func testBusyChannelRejectsASecondSimultaneousOffer() {
        // A real Bridgey session has exactly one peer on its control channel, so a "second offer
        // while busy" can only come from that same peer trying to re-offer. Model that directly,
        // with no peer replying (so the channel sits in negotiating): a second local offerVideo()
        // call must be a no-op guarded by the existing `videoState != .idle` check.
        var offersSent = 0
        let controller = VideoChannelController(
            available: { true },
            send: { kind, _ in if kind == "video.offer" { offersSent += 1 }; return true },
            pairingKeyProvider: { self.pairingKey },
            sessionIdProvider: { UUID().uuidString.lowercased() },
            remoteHostProvider: { "127.0.0.1" }
        )
        controller.offerVideo(direction: "android_to_mac", width: 1080, height: 2400, bitrateKbps: 4000, fps: 30)
        XCTAssertEqual(.negotiating, controller.currentVideoState)
        XCTAssertEqual(1, offersSent)

        controller.offerVideo(direction: "android_to_mac", width: 1080, height: 2400, bitrateKbps: 4000, fps: 30)
        XCTAssertEqual(.negotiating, controller.currentVideoState)
        XCTAssertEqual(1, offersSent)
    }
}
