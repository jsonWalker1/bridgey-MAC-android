import XCTest
@testable import BridgeyMac

final class TCPInputTransportTests: XCTestCase {
    private let pairingKey = Data((0..<32).map { UInt8($0) })
    private let sessionId = UUID().uuidString.lowercased()

    private func security() -> ChannelSecurityContext {
        ChannelSecurityContext(channelKey: ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: sessionId, purpose: "input", direction: "mac_to_android"), sessionId: sessionId, purpose: "input")
    }

    private func connectedPair() -> (TCPInputTransport, TCPInputTransport)? {
        let sec = security()
        guard let (listener, port) = EphemeralTCPListener.start() else { return nil }
        let acceptor = TCPInputTransport()
        let acceptExpectation = XCTestExpectation(description: "acceptor handshake")
        var acceptResult = false
        Thread { acceptResult = acceptor.acceptViaListener(listener, security: sec); acceptExpectation.fulfill() }.start()

        let initiator = TCPInputTransport()
        let ok = initiator.connectAsInitiator(host: "127.0.0.1", port: port, security: sec)
        XCTAssertTrue(ok)
        wait(for: [acceptExpectation], timeout: 5)
        XCTAssertTrue(acceptResult)
        guard ok, acceptResult else { return nil }
        return (initiator, acceptor)
    }

    func testHandshakeSucceedsAndPointerEventRoundTrips() {
        guard let (initiator, acceptor) = connectedPair() else { return XCTFail("setup failed") }
        let received = XCTestExpectation(description: "event received")
        var event: InputEvent?
        acceptor.onEventReceived = { event = $0; received.fulfill() }

        let outcome = initiator.send(.pointer(action: .down, x: 0.5, y: 0.5))
        XCTAssertEqual(.sent, outcome)
        wait(for: [received], timeout: 5)
        XCTAssertEqual(.pointer(action: .down, x: 0.5, y: 0.5), event)

        initiator.close(); acceptor.close()
    }

    func testKeyAndTextEventsRoundTrip() {
        guard let (initiator, acceptor) = connectedPair() else { return XCTFail("setup failed") }
        var events: [InputEvent] = []
        let both = XCTestExpectation(description: "both events")
        both.expectedFulfillmentCount = 2
        acceptor.onEventReceived = { events.append($0); both.fulfill() }

        _ = initiator.send(.key(keyCode: 66, action: .down))
        _ = initiator.send(.text("hello"))
        wait(for: [both], timeout: 5)
        XCTAssertEqual(.key(keyCode: 66, action: .down), events[0])
        XCTAssertEqual(.text("hello"), events[1])

        initiator.close(); acceptor.close()
    }

    func testPointerDownIsNeverCoalescedAwayEvenUnderQueuePressure() {
        guard let (initiator, acceptor) = connectedPair() else { return XCTFail("setup failed") }
        _ = initiator.send(.pointer(action: .move, x: 0.1, y: 0.1))
        _ = initiator.send(.pointer(action: .move, x: 0.2, y: 0.2))
        _ = initiator.send(.pointer(action: .move, x: 0.3, y: 0.3))
        let outcome = initiator.send(.pointer(action: .down, x: 0.9, y: 0.9))
        if case .failed = outcome { XCTFail("pointer down must not be dropped") }
        initiator.close(); acceptor.close()
    }
}
