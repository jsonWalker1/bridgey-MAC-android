import XCTest
@testable import BridgeyMac

/// MD-3 Ping isolation: requests to two devices are independent (deviceId, requestId) pairs.
final class PingRequestsTests: XCTestCase {
    private let a = "10000000-0000-4000-8000-00000000000a"
    private let b = "20000000-0000-4000-8000-00000000000b"

    private final class FakeSession {
        var sent: [String] = []
    }

    func testPingsToTwoDevicesStayIndependent() {
        var pings = PingRequests()
        pings.begin(deviceID: a, requestID: "ra")
        pings.begin(deviceID: b, requestID: "rb")
        XCTAssertTrue(pings.isPending(deviceID: a, requestID: "ra"))
        XCTAssertTrue(pings.isPending(deviceID: b, requestID: "rb"))
        XCTAssertEqual(pings.statuses, [a: .pinging, b: .pinging])
    }

    func testAResponsesAndBTimesOut() {
        var pings = PingRequests()
        pings.begin(deviceID: a, requestID: "ra")
        pings.begin(deviceID: b, requestID: "rb")
        XCTAssertTrue(pings.acknowledge(requestID: "ra", from: a))
        XCTAssertTrue(pings.timeOut(deviceID: b, requestID: "rb"))
        XCTAssertEqual(pings.statuses, [a: .delivered, b: .notAcknowledged])
        XCTAssertFalse(pings.timeOut(deviceID: a, requestID: "ra"), "a delivered request cannot time out later")
    }

    func testBRespondsAndATimesOut() {
        var pings = PingRequests()
        pings.begin(deviceID: a, requestID: "ra")
        pings.begin(deviceID: b, requestID: "rb")
        XCTAssertTrue(pings.timeOut(deviceID: a, requestID: "ra"))
        XCTAssertTrue(pings.acknowledge(requestID: "rb", from: b))
        XCTAssertEqual(pings.statuses, [a: .notAcknowledged, b: .delivered])
    }

    func testADisconnectsAndBRemainsUsable() {
        var pings = PingRequests()
        pings.begin(deviceID: a, requestID: "ra")
        pings.begin(deviceID: b, requestID: "rb")
        XCTAssertTrue(pings.deviceEnded(a), "A had a request in flight")
        XCTAssertFalse(pings.deviceEnded(a))
        XCTAssertFalse(pings.isPending(deviceID: a, requestID: "ra"))
        XCTAssertNil(pings.statuses[a])
        XCTAssertTrue(pings.acknowledge(requestID: "rb", from: b))
        pings.begin(deviceID: b, requestID: "rb2")
        XCTAssertTrue(pings.isPending(deviceID: b, requestID: "rb2"))
    }

    func testBDisconnectsAndARemainsUsable() {
        var pings = PingRequests()
        pings.begin(deviceID: a, requestID: "ra")
        pings.begin(deviceID: b, requestID: "rb")
        pings.deviceEnded(b)
        XCTAssertTrue(pings.acknowledge(requestID: "ra", from: a))
        XCTAssertEqual(pings.statuses, [a: .delivered])
    }

    func testALateResponseCannotCompleteBsRequest() {
        var pings = PingRequests()
        pings.begin(deviceID: a, requestID: "ra")
        pings.begin(deviceID: b, requestID: "rb")
        XCTAssertFalse(pings.acknowledge(requestID: "rb", from: a), "A echoing B's request id completes nothing")
        XCTAssertTrue(pings.isPending(deviceID: b, requestID: "rb"))
        XCTAssertTrue(pings.timeOut(deviceID: a, requestID: "ra"))
        XCTAssertFalse(pings.acknowledge(requestID: "ra", from: a), "a late ack after the timeout completes nothing")
        XCTAssertEqual(pings.statuses[a], .notAcknowledged)
        XCTAssertEqual(pings.statuses[b], .pinging)
    }

    func testRequestIDsCannotCollideAcrossDevices() {
        var pings = PingRequests()
        pings.begin(deviceID: a, requestID: "same")
        pings.begin(deviceID: b, requestID: "same")
        XCTAssertTrue(pings.acknowledge(requestID: "same", from: a))
        XCTAssertTrue(pings.isPending(deviceID: b, requestID: "same"), "the same id on another device is another request")
        XCTAssertEqual(pings.statuses, [a: .delivered, b: .pinging])
    }

    func testChangingTheRoutedDeviceDoesNotTouchInFlightRequests() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: "50000000-0000-4000-8000-000000000000")
        for id in [a, b] {
            let session = FakeSession()
            manager.addPending(session, initiatedLocally: false, expectedDeviceID: nil)
            _ = manager.identify(session, as: id)
            manager.markConnected(session)
        }
        var pings = PingRequests()
        XCTAssertTrue(manager.deliver(to: a) { $0.sent.append("ping.request ra"); return true })
        pings.begin(deviceID: a, requestID: "ra")
        // Routing flips between A and B; neither the request nor the session table changes.
        for preferred in [b, a, b] {
            _ = DeviceRouting.activePeer(mode: .singleActive, preferredDeviceID: preferred, connected: manager.connectedInOrder)
        }
        XCTAssertTrue(pings.isPending(deviceID: a, requestID: "ra"))
        XCTAssertEqual(manager.connectedSession(for: a)?.sent, ["ping.request ra"])
        XCTAssertEqual(manager.connectedSession(for: b)?.sent, [])
        // The ack arrives on A's session: its sender identity completes A's request.
        XCTAssertTrue(pings.acknowledge(requestID: "ra", from: manager.connectedDeviceID(of: manager.connectedSession(for: a)!)!))
    }

    func testSingleDeviceBehaviourIsUnchanged() {
        var pings = PingRequests()
        pings.begin(deviceID: a, requestID: "r1")
        XCTAssertTrue(pings.acknowledge(requestID: "r1", from: a))
        XCTAssertEqual(pings.statuses[a], .delivered)
        pings.begin(deviceID: a, requestID: "r2")
        XCTAssertTrue(pings.timeOut(deviceID: a, requestID: "r2"))
        XCTAssertEqual(pings.statuses[a], .notAcknowledged)
        pings.begin(deviceID: a, requestID: "r3")
        pings.failed(deviceID: a, requestID: "r3")
        XCTAssertEqual(pings.statuses[a], .notSent)
    }
}
