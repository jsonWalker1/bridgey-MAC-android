import XCTest
@testable import BridgeyMac

final class ChannelLifecycleTests: XCTestCase {
    func testHappyPathInitiatorFlow() {
        var s: ChannelState = .idle
        s = ChannelLifecycle.transition(s, .offerSent); XCTAssertEqual(.negotiating, s)
        s = ChannelLifecycle.transition(s, .acceptReceived); XCTAssertEqual(.connecting, s)
        s = ChannelLifecycle.transition(s, .socketConnected); XCTAssertEqual(.handshaking, s)
        s = ChannelLifecycle.transition(s, .handshakeSucceeded); XCTAssertEqual(.active, s)
    }

    func testRejectDuringNegotiationReturnsToIdle() {
        XCTAssertEqual(.idle, ChannelLifecycle.transition(.negotiating, .rejectReceived))
    }

    func testNegotiationTimeoutReturnsToIdle() {
        XCTAssertEqual(.idle, ChannelLifecycle.transition(.negotiating, .negotiationTimeout))
    }

    func testConnectFailureLeadsToFailed() {
        XCTAssertEqual(.failed, ChannelLifecycle.transition(.connecting, .connectFailed))
    }

    func testHandshakeFailureLeadsToFailed() {
        XCTAssertEqual(.failed, ChannelLifecycle.transition(.handshaking, .handshakeFailed))
    }

    func testMalformedOrTamperedWhileActiveLeadsToFailed() {
        XCTAssertEqual(.failed, ChannelLifecycle.transition(.active, .malformedOrTampered))
    }

    func testUnexpectedSocketCloseWhileActiveLeadsToFailed() {
        XCTAssertEqual(.failed, ChannelLifecycle.transition(.active, .socketClosed))
    }

    func testExplicitStopFromActiveGoesThroughClosingToIdle() {
        var s = ChannelLifecycle.transition(.active, .stopRequested)
        XCTAssertEqual(.closing, s)
        s = ChannelLifecycle.transition(s, .socketClosed)
        XCTAssertEqual(.idle, s)
    }

    func testControllerCanMoveOnFromFailedBackToIdle() {
        XCTAssertEqual(.idle, ChannelLifecycle.transition(.failed, .stopRequested))
    }

    func testSessionResetIsUnconditionalFromEveryState() {
        let allStates: [ChannelState] = [.idle, .negotiating, .connecting, .handshaking, .active, .closing, .failed]
        for state in allStates {
            XCTAssertEqual(.idle, ChannelLifecycle.transition(state, .sessionReset), "from \(state)")
        }
    }

    func testUnexpectedEventsAreIgnoredNotCrashedOn() {
        XCTAssertEqual(.active, ChannelLifecycle.transition(.active, .acceptReceived))
        XCTAssertEqual(.idle, ChannelLifecycle.transition(.idle, .handshakeFailed))
    }
}
