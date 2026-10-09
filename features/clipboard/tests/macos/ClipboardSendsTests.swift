import XCTest
@testable import BridgeyMac

/// MD-5 clipboard: explicit sends to one peer, tracked per (deviceId, messageId).
final class ClipboardSendsTests: XCTestCase {
    private let a = "10000000-0000-4000-8000-00000000000a"
    private let b = "20000000-0000-4000-8000-00000000000b"

    private final class FakeSession { var received: [String] = [] }

    private func twoPeers() -> PeerSessionManager<FakeSession> {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: "50000000-0000-4000-8000-000000000000")
        for id in [a, b] {
            let session = FakeSession()
            manager.addPending(session, initiatedLocally: false, expectedDeviceID: nil)
            _ = manager.identify(session, as: id)
            manager.markConnected(session)
        }
        return manager
    }

    private func items(routed: String) -> [DeviceListItem] {
        [DeviceListItem(deviceID: a, name: "MacBook Air – pracovní", platform: .macos, kind: .computer, isConnected: true),
         DeviceListItem(deviceID: b, name: "Tomáš MacBook Air M4 můj", platform: .macos, kind: .computer, isConnected: true)]
    }

    // MARK: - Targeting and routing separation

    func testTheSelectedPeerReceivesAndTheRoutedPeerDoesNot() {
        let manager = twoPeers()
        for (selected, routed) in [(a, b), (b, a)] {
            let context = SelectedDeviceContext.make(items: items(routed: routed), selectedDeviceID: selected, routedDeviceID: routed)
            let target = context.selected!.deviceID // the card's Clipboard button sends to the selected peer
            XCTAssertTrue(manager.deliver(to: target) { $0.received.append("clipboard.update"); return true })
            XCTAssertEqual(target, selected)
        }
        XCTAssertEqual(manager.connectedSession(for: a)?.received, ["clipboard.update"], "A got exactly its own send")
        XCTAssertEqual(manager.connectedSession(for: b)?.received, ["clipboard.update"], "B got exactly its own send")
    }

    func testChangingRoutingDoesNotChangeTheClipboardTarget() {
        let selected = a
        for routed in [b, a, b] {
            let context = SelectedDeviceContext.make(items: items(routed: routed), selectedDeviceID: selected, routedDeviceID: routed)
            XCTAssertEqual(context.selected?.deviceID, selected)
        }
    }

    func testChangingSelectionChangesTheTarget() {
        XCTAssertEqual(SelectedDeviceContext.make(items: items(routed: b), selectedDeviceID: a, routedDeviceID: b).selected?.deviceID, a)
        XCTAssertEqual(SelectedDeviceContext.make(items: items(routed: b), selectedDeviceID: b, routedDeviceID: b).selected?.deviceID, b)
    }

    func testADisconnectedSelectedPeerIsNotReplacedByAnotherPeer() {
        let onlyB = [DeviceListItem(deviceID: b, name: "B", platform: .macos, kind: .computer, isConnected: true)]
        let context = SelectedDeviceContext.make(items: onlyB, selectedDeviceID: a, routedDeviceID: b)
        XCTAssertNil(context.selected, "no fallback to the routed peer")
        let manager = PeerSessionManager<FakeSession>(localDeviceID: "50000000-0000-4000-8000-000000000000")
        XCTAssertFalse(manager.deliver(to: a) { _ in true }, "a send to a peer that is not connected is not delivered anywhere")
    }

    // MARK: - Per-device send state

    func testSimultaneousSendsToTwoPeersCompleteIndependently() {
        var sends = ClipboardSends()
        sends.begin(deviceID: a, messageID: "ma")
        sends.begin(deviceID: b, messageID: "mb")
        XCTAssertTrue(sends.acknowledge(messageID: "ma", from: a))
        XCTAssertTrue(sends.isPending(deviceID: b, messageID: "mb"))
        XCTAssertTrue(sends.acknowledge(messageID: "mb", from: b))
        XCTAssertEqual(sends.statuses, [a: .delivered, b: .delivered])
    }

    func testAnAckFromAnotherPeerCompletesNothing() {
        var sends = ClipboardSends()
        sends.begin(deviceID: a, messageID: "ma")
        XCTAssertFalse(sends.acknowledge(messageID: "ma", from: b))
        XCTAssertTrue(sends.isPending(deviceID: a, messageID: "ma"))
    }

    func testDisconnectEndsOnlyThatPeersSendWithoutMovingIt() {
        var sends = ClipboardSends()
        sends.begin(deviceID: a, messageID: "ma")
        sends.begin(deviceID: b, messageID: "mb")
        XCTAssertEqual(sends.deviceEnded(a), ["ma"])
        XCTAssertEqual(sends.statuses[a], .disconnected)
        XCTAssertFalse(sends.isPending(deviceID: b, messageID: "ma"), "A's send never becomes B's")
        XCTAssertTrue(sends.isPending(deviceID: b, messageID: "mb"))
        XCTAssertFalse(sends.acknowledge(messageID: "ma", from: a), "a late ack after the disconnect completes nothing")
    }

    func testRejectionAndTimeoutAreRecordedPerPeer() {
        var sends = ClipboardSends()
        sends.begin(deviceID: a, messageID: "ma")
        sends.begin(deviceID: b, messageID: "mb")
        XCTAssertTrue(sends.reject(messageID: "ma", from: a))
        XCTAssertTrue(sends.timeOut(deviceID: b, messageID: "mb"))
        XCTAssertEqual(sends.statuses, [a: .rejected, b: .notAcknowledged])
        XCTAssertFalse(sends.acknowledge(messageID: "mb", from: b), "an ack after the timeout completes nothing")
    }

    // MARK: - Receive

    func testARetransmissionIsAcknowledgedAgainNotAppliedTwice() {
        XCTAssertEqual(ClipboardReceiveAction.forMessage(isNewMessageID: true), .apply)
        XCTAssertEqual(ClipboardReceiveAction.forMessage(isNewMessageID: false), .acknowledgeAgain)
    }

    func testClipboardFromAnotherMacIsNotAccepted() {
        let mac = DeviceProfile(platform: .macos, kind: .computer)
        let phone = DeviceProfile(platform: .android, kind: .phone)
        let unknown = DeviceProfile(platform: .unknown, kind: .unknown)
        XCTAssertFalse(ClipboardReceiveAction.acceptsSender(mac, receiver: mac))
        XCTAssertTrue(ClipboardReceiveAction.acceptsSender(phone, receiver: mac))
        XCTAssertTrue(ClipboardReceiveAction.acceptsSender(mac, receiver: phone))
        // Pairings recorded before platform hints keep working.
        XCTAssertTrue(ClipboardReceiveAction.acceptsSender(unknown, receiver: mac))
    }

    func testAuthorizationIsTheSendersOwnGrant() {
        // Receivers check settings.isEnabled(.clipboard, for: sender); per-device grants never leak.
        let grants: [String: Bool] = [a: true, b: false]
        XCTAssertTrue(effectiveFeatureEnabled(globalEnabled: true, deviceEnabled: grants[a]))
        XCTAssertFalse(effectiveFeatureEnabled(globalEnabled: true, deviceEnabled: grants[b]))
    }

    // MARK: - Target-less entry points

    func testTargetlessSendsGoOnlyToTheOnlyEligiblePeer() {
        XCTAssertEqual(ClipboardTarget.forTargetlessSend(eligible: [a]), a)
        XCTAssertNil(ClipboardTarget.forTargetlessSend(eligible: [a, b]), "several peers: the user must choose")
        XCTAssertNil(ClipboardTarget.forTargetlessSend(eligible: []), "no peers: nothing is sent")
    }
}
