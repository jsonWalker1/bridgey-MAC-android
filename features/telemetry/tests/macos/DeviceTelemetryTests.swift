import XCTest
@testable import BridgeyMac

/// MD-4c per-device telemetry: values belong to the peer that sent them, the subscription follows
/// the shown peer only, and each subscriber of this device is served independently.
final class DeviceTelemetryTests: XCTestCase {
    private let a = "10000000-0000-4000-8000-00000000000a"
    private let b = "20000000-0000-4000-8000-00000000000b"

    // MARK: - State isolation

    func testAUpdateLeavesBUnchangedAndViceVersa() {
        var store = DeviceTelemetryStore()
        store.update(a) { $0.battery = RemoteBatteryStatus(level: 82, isCharging: false) }
        XCTAssertEqual(store[a]?.battery?.level, 82)
        XCTAssertNil(store[b])
        store.update(b) { $0.cpu = .available(17) }
        XCTAssertEqual(store[a]?.battery?.level, 82)
        XCTAssertNil(store[a]?.cpu, "B's CPU never appears for A")
        XCTAssertEqual(store[b]?.cpu, .available(17))
        XCTAssertNil(store[b]?.battery, "A's battery never appears for B")
    }

    func testDisconnectOfARemovesOnlyA() {
        var store = DeviceTelemetryStore()
        store.update(a) { $0.storage = RemoteStorageStatus(usedBytes: 1, totalBytes: 2) }
        store.update(b) { $0.memory = RemoteMemoryStatus(usedBytes: 3, totalBytes: 4) }
        store.remove(a)
        XCTAssertNil(store[a])
        XCTAssertEqual(store[b]?.memory, RemoteMemoryStatus(usedBytes: 3, totalBytes: 4))
    }

    func testReconnectStartsFreshWithoutAnotherPeersValues() {
        var store = DeviceTelemetryStore()
        store.update(a) { $0.battery = RemoteBatteryStatus(level: 50, isCharging: true) }
        store.update(b) { $0.battery = RemoteBatteryStatus(level: 90, isCharging: false) }
        store.remove(a)
        XCTAssertNil(store[a], "A comes back with nothing, not with B's values")
        store.update(a) { $0.battery = RemoteBatteryStatus(level: 51, isCharging: true) }
        XCTAssertEqual(store[a]?.battery?.level, 51)
        XCTAssertEqual(store[b]?.battery?.level, 90)
    }

    func testAMissingMetricStaysUnavailableAndIsNeverFilledFromAnotherPeer() {
        var store = DeviceTelemetryStore()
        store.update(a) { $0.cpu = .unavailable } // e.g. Android CPU not readable
        store.update(b) { $0.cpu = .available(40) }
        XCTAssertEqual(store[a]?.cpu, .unavailable)
        XCTAssertNil(store[a]?.temperature)
    }

    func testRevokedGrantClearsOnlyThatPeersMetric() {
        var store = DeviceTelemetryStore()
        store.update(a) { $0.battery = RemoteBatteryStatus(level: 10, isCharging: false); $0.cpu = .available(5) }
        store.update(b) { $0.battery = RemoteBatteryStatus(level: 20, isCharging: false) }
        store.prune { deviceID, metric in !(deviceID == a && metric == .battery) }
        XCTAssertNil(store[a]?.battery)
        XCTAssertEqual(store[a]?.cpu, .available(5))
        XCTAssertEqual(store[b]?.battery?.level, 20)
        store.clear(.cpu, for: a)
        XCTAssertNil(store[a], "a peer with no values left is dropped")
    }

    // MARK: - Subscription follows the shown peer

    func testOpeningThePanelSubscribesTheSelectedPeer() {
        var subscription = TelemetrySubscription()
        XCTAssertEqual(subscription.show(a) { _ in true }, [.subscribe(a)])
        XCTAssertEqual(subscription.subscribedDeviceID, a)
    }

    func testChangingSelectionUnsubscribesTheOldPeerAndSubscribesTheNewOne() {
        var subscription = TelemetrySubscription()
        _ = subscription.show(a) { _ in true }
        XCTAssertEqual(subscription.show(b) { _ in true }, [.unsubscribe(a), .subscribe(b)])
        XCTAssertEqual(subscription.show(b) { _ in true }, [], "showing the same peer again sends nothing")
    }

    func testClosingThePanelStopsTheSubscription() {
        var subscription = TelemetrySubscription()
        _ = subscription.show(a) { _ in true }
        XCTAssertEqual(subscription.show(nil) { _ in true }, [.unsubscribe(a)])
        XCTAssertNil(subscription.subscribedDeviceID)
        XCTAssertEqual(subscription.show(nil) { _ in true }, [], "no background subscription")
    }

    func testDisconnectAndReconnectOfTheShownPeerResubscribesItOnly() {
        var subscription = TelemetrySubscription()
        _ = subscription.show(a) { _ in true }
        subscription.sessionEnded(a)
        XCTAssertNil(subscription.subscribedDeviceID)
        XCTAssertEqual(subscription.sessionStarted(b), [], "another peer connecting is not subscribed")
        XCTAssertEqual(subscription.sessionStarted(a), [.subscribe(a)])
    }

    func testAPeerThatIsNotConnectedIsSubscribedWhenItConnects() {
        var subscription = TelemetrySubscription()
        XCTAssertEqual(subscription.show(a) { _ in false }, [])
        XCTAssertEqual(subscription.sessionStarted(a), [.subscribe(a)])
    }

    func testAnEndedSessionOfAnotherPeerDoesNotTouchTheSubscription() {
        var subscription = TelemetrySubscription()
        _ = subscription.show(a) { _ in true }
        subscription.sessionEnded(b)
        XCTAssertEqual(subscription.subscribedDeviceID, a)
    }

    // MARK: - Publish side: subscribers of this device

    func testSamplingRunsWhileAnySubscriberRemains() {
        var subscribers = TelemetrySubscribers()
        XCTAssertTrue(subscribers.add(a), "first subscriber starts the loop")
        XCTAssertFalse(subscribers.add(b))
        XCTAssertFalse(subscribers.remove(a), "B still displays this device")
        XCTAssertTrue(subscribers.remove(b), "last subscriber stops the loop")
        XCTAssertTrue(subscribers.isEmpty)
    }

    func testTheDeadBandIsPerSubscriber() {
        var subscribers = TelemetrySubscribers()
        subscribers.add(a)
        let status = LocalStorageStatus(usedBytes: 1_000_000_000, totalBytes: 2_000_000_000)
        XCTAssertTrue(subscribers.shouldSend(storage: status, to: a))
        subscribers.sent(storage: status, to: a)
        XCTAssertFalse(subscribers.shouldSend(storage: status, to: a), "unchanged value is not resent to A")
        subscribers.add(b)
        XCTAssertTrue(subscribers.shouldSend(storage: status, to: b), "a new subscriber always gets the value")
        let moved = LocalStorageStatus(usedBytes: 1_000_000_000 + TelemetrySubscribers.changeThresholdBytes, totalBytes: 2_000_000_000)
        XCTAssertTrue(subscribers.shouldSend(storage: moved, to: a))
    }

    func testAReEnabledGrantResetsOnlyThatSubscribersDeadBand() {
        var subscribers = TelemetrySubscribers()
        subscribers.add(a)
        subscribers.add(b)
        let storage = LocalStorageStatus(usedBytes: 7, totalBytes: 9)
        subscribers.sent(storage: storage, to: a)
        subscribers.sent(storage: storage, to: b)
        subscribers.resetDeadBand(a)
        XCTAssertTrue(subscribers.shouldSend(storage: storage, to: a), "A gets the value again right away")
        XCTAssertFalse(subscribers.shouldSend(storage: storage, to: b), "B's dead-band is untouched")
    }

    func testResubscribingResetsThatSubscribersDeadBand() {
        var subscribers = TelemetrySubscribers()
        subscribers.add(a)
        let memory = LocalMemoryStatus(usedBytes: 5, totalBytes: 10)
        subscribers.sent(memory: memory, to: a)
        subscribers.remove(a)
        subscribers.add(a)
        XCTAssertTrue(subscribers.shouldSend(memory: memory, to: a))
    }

    // MARK: - Selection vs routing

    func testTheShownTelemetryFollowsTheSelectionNotTheRoutedPeer() {
        let items = [
            DeviceListItem(deviceID: a, name: "S23 Ultra", platform: .android, kind: .phone, isConnected: true),
            DeviceListItem(deviceID: b, name: "MacBook Air", platform: .macos, kind: .computer, isConnected: true),
        ]
        var store = DeviceTelemetryStore()
        store.update(a) { $0.battery = RemoteBatteryStatus(level: 82, isCharging: false) }
        store.update(b) { $0.cpu = .available(17) }
        let context = SelectedDeviceContext.make(items: items, selectedDeviceID: a, routedDeviceID: b)
        XCTAssertEqual(store[context.selected!.deviceID]?.battery?.level, 82, "selected A shows A's battery")
        XCTAssertNil(store[context.selected!.deviceID]?.cpu, "and never the routed B's CPU")
        XCTAssertFalse(context.legacyFeaturesApply)
        XCTAssertEqual(context.legacyFeaturesUseOtherPeer?.deviceID, b)
    }
}
