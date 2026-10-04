import Foundation
import XCTest
@testable import BridgeyMac

/// MD-1 routing foundation: addressed delivery, receive identity, per-device lifecycle, device-
/// scoped authorization changes, the device directory and feature applicability. In-memory peers
/// against the same Core components PairingCoordinator uses; no network.
final class MultiDeviceRoutingTests: XCTestCase {
    private final class FakeSession {
        let label: String
        var delivered: [String] = []
        init(_ label: String) { self.label = label }
    }

    /// A feature-style per-device store driven only by lifecycle events.
    private final class PerDeviceStore: PeerLifecycleObserver {
        var state: [String: String] = [:]
        var events: [String] = []
        func sessionStarted(deviceID: String) { state[deviceID] = "live"; events.append("started:\(deviceID)") }
        func sessionEnded(deviceID: String) { state[deviceID] = nil; events.append("ended:\(deviceID)") }
        func authorizationChanged(deviceID: String) { events.append("auth:\(deviceID)") }
    }

    private let local = "50000000-0000-4000-8000-000000000000"
    private let a = "10000000-0000-4000-8000-00000000000a"
    private let b = "20000000-0000-4000-8000-00000000000b"

    @discardableResult
    private func connect(_ manager: PeerSessionManager<FakeSession>, _ deviceID: String) -> FakeSession {
        let session = FakeSession(deviceID)
        manager.addPending(session, initiatedLocally: false, expectedDeviceID: nil)
        XCTAssertEqual(manager.identify(session, as: deviceID).result, .identified)
        XCTAssertTrue(manager.markConnected(session))
        return session
    }

    private func send(_ manager: PeerSessionManager<FakeSession>, to deviceID: String, _ message: String) -> Bool {
        manager.deliver(to: deviceID) { session in
            session.delivered.append(message)
            return true
        }
    }

    // MARK: - Addressing and receive identity

    func testTwoPeerSessionsCoexist() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        let sessionA = connect(manager, a)
        let sessionB = connect(manager, b)
        XCTAssertTrue(manager.connectedSession(for: a) === sessionA)
        XCTAssertTrue(manager.connectedSession(for: b) === sessionB)
        XCTAssertFalse(sessionA === sessionB)
    }

    func testSendToAReachesOnlyAAndSendToBReachesOnlyB() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        let sessionA = connect(manager, a)
        let sessionB = connect(manager, b)

        XCTAssertTrue(send(manager, to: a, "one"))
        XCTAssertEqual(sessionA.delivered, ["one"])
        XCTAssertEqual(sessionB.delivered, [], "sending to A never touches B")

        XCTAssertTrue(send(manager, to: b, "two"))
        XCTAssertEqual(sessionA.delivered, ["one"])
        XCTAssertEqual(sessionB.delivered, ["two"])
    }

    func testSendNeverFallsBackToAnotherDeviceOrAnUnauthenticatedSession() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        let sessionA = connect(manager, a)
        XCTAssertFalse(send(manager, to: b, "x"), "B is not connected: nothing is delivered, not even to A")
        XCTAssertEqual(sessionA.delivered, [])

        let handshaking = FakeSession(b)
        manager.addPending(handshaking, initiatedLocally: false, expectedDeviceID: nil)
        XCTAssertEqual(manager.identify(handshaking, as: b).result, .identified)
        XCTAssertFalse(send(manager, to: b, "y"), "a session that is not yet authenticated is not addressable")
        XCTAssertEqual(handshaking.delivered, [])
    }

    func testReceiveIdentifiesAVersusB() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        let sessionA = connect(manager, a)
        let sessionB = connect(manager, b)
        XCTAssertEqual(manager.connectedDeviceID(of: sessionA), a)
        XCTAssertEqual(manager.connectedDeviceID(of: sessionB), b)

        let pending = FakeSession("pending")
        manager.addPending(pending, initiatedLocally: false, expectedDeviceID: nil)
        XCTAssertNil(manager.connectedDeviceID(of: pending), "an unidentified socket has no sender identity")
    }

    func testAddressingIsIndependentOfTheRoutedPeer() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        let sessionA = connect(manager, a)
        let sessionB = connect(manager, b)
        let routed = DeviceRouting.activePeer(mode: .singleActive, preferredDeviceID: a, connected: manager.connectedInOrder)
        XCTAssertEqual(routed, a)
        XCTAssertTrue(send(manager, to: b, "to-b"))
        XCTAssertEqual(sessionB.delivered, ["to-b"])
        XCTAssertEqual(sessionA.delivered, [], "the routed peer is not involved in addressed delivery")
    }

    // MARK: - Lifecycle

    func testSessionEndedForAKeepsBState() {
        let lifecycle = PeerLifecycle()
        let store = PerDeviceStore()
        let sessionA = FakeSession(a), sessionB = FakeSession(b)
        lifecycle.addObserver(store)
        lifecycle.sessionStarted(deviceID: a, session: sessionA)
        lifecycle.sessionStarted(deviceID: b, session: sessionB)
        lifecycle.sessionEnded(deviceID: a, session: sessionA)
        XCTAssertNil(store.state[a])
        XCTAssertEqual(store.state[b], "live")
        XCTAssertEqual(store.events, ["started:\(a)", "started:\(b)", "ended:\(a)"])
    }

    func testSessionEndedIsEmittedOncePerStartedSessionOnly() {
        let lifecycle = PeerLifecycle()
        let store = PerDeviceStore()
        let session = FakeSession(a)
        lifecycle.addObserver(store)
        lifecycle.sessionEnded(deviceID: a, session: session) // handshake failed before it ever started
        lifecycle.sessionStarted(deviceID: a, session: session)
        lifecycle.sessionStarted(deviceID: a, session: session) // duplicate start is ignored
        lifecycle.sessionEnded(deviceID: a, session: session)
        lifecycle.sessionEnded(deviceID: a, session: session)
        XCTAssertEqual(store.events, ["started:\(a)", "ended:\(a)"])
    }

    func testAnOlderSessionEndingNeverEndsTheNewerSessionOfTheSameDevice() {
        let lifecycle = PeerLifecycle()
        let store = PerDeviceStore()
        let old = FakeSession(a), new = FakeSession(a)
        lifecycle.addObserver(store)
        lifecycle.sessionStarted(deviceID: a, session: old)
        lifecycle.sessionStarted(deviceID: a, session: new) // reconnect before the old end was seen
        lifecycle.sessionEnded(deviceID: a, session: old)   // late end of the old session
        XCTAssertEqual(store.state[a], "live")
        XCTAssertEqual(store.events, ["started:\(a)", "ended:\(a)", "started:\(a)"])
        lifecycle.sessionEnded(deviceID: a, session: new)
        XCTAssertNil(store.state[a])
    }

    func testAStartForASessionThatIsNoLongerCurrentIsIgnored() {
        let lifecycle = PeerLifecycle()
        let store = PerDeviceStore()
        lifecycle.addObserver(store)
        lifecycle.sessionStarted(deviceID: a, session: FakeSession(a)) { false }
        XCTAssertEqual(store.events, [], "a session that already ended must not leave a ghost start")
        XCTAssertEqual(lifecycle.startedDeviceIDs, [])
    }

    func testObserversAreHeldWeakly() {
        let lifecycle = PeerLifecycle()
        var store: PerDeviceStore? = PerDeviceStore()
        lifecycle.addObserver(store!)
        store = nil
        lifecycle.sessionStarted(deviceID: a, session: FakeSession(a)) // must not crash or retain
        XCTAssertEqual(lifecycle.startedDeviceIDs, [a])
    }

    // MARK: - Authorization

    func testPerDeviceGrantChangeIsScopedToThatDevice() {
        let changed = DeviceAuthorization.changedDevices(
            oldGlobal: [BridgeyFeature.clipboard: true],
            newGlobal: [BridgeyFeature.clipboard: true],
            oldPerDevice: [a: [.clipboard: true], b: [.files: true]],
            newPerDevice: [a: [.clipboard: false], b: [.files: true]],
            devices: [a, b]
        )
        XCTAssertEqual(changed, [a])
    }

    func testGlobalGrantChangeAffectsEveryDevice() {
        let changed = DeviceAuthorization.changedDevices(
            oldGlobal: [BridgeyFeature.clipboard: true],
            newGlobal: [BridgeyFeature.clipboard: false],
            oldPerDevice: [String: [BridgeyFeature: Bool]](),
            newPerDevice: [:],
            devices: [a, b]
        )
        XCTAssertEqual(changed, [a, b])
    }

    func testForgottenDevicesAreNeverReported() {
        let forgotten = "30000000-0000-4000-8000-00000000000c"
        let changed = DeviceAuthorization.changedDevices(
            oldGlobal: [BridgeyFeature.clipboard: true],
            newGlobal: [BridgeyFeature.clipboard: true],
            oldPerDevice: [forgotten: [.clipboard: false]],
            newPerDevice: [:],
            devices: [a, b]
        )
        XCTAssertEqual(changed, [])
    }

    func testAuthorizationEventsReachOnlyTheChangedDevices() {
        let lifecycle = PeerLifecycle()
        let store = PerDeviceStore()
        lifecycle.addObserver(store)
        lifecycle.authorizationChanged(deviceIDs: [b])
        XCTAssertEqual(store.events, ["auth:\(b)"])
    }

    @MainActor
    func testSettingsPerDeviceGrantDoesNotChangeOtherDevices() {
        let deviceA = UUID().uuidString.lowercased()
        let deviceB = UUID().uuidString.lowercased()
        let settings = BridgeySettings()
        defer { settings.removeDevice(deviceA) }
        let before = settings.isEnabled(.clipboard, for: deviceB)
        settings.setForDevice(deviceA, feature: .clipboard, enabled: false)
        XCTAssertFalse(settings.isEnabled(.clipboard, for: deviceA))
        XCTAssertEqual(settings.isEnabled(.clipboard, for: deviceB), before, "another device's grant is untouched")
    }

    // MARK: - Directory

    func testDirectoryProjectsTrustPresenceAndSessions() {
        let entries = DeviceDirectory.entries(
            trusted: [
                .init(deviceID: a, name: "Phone (stored)", platform: "android", deviceType: "phone"),
                .init(deviceID: b, name: "Work Mac", platform: nil, deviceType: nil),
            ],
            presence: [b: PeerPresence(deviceID: b, name: "Work Mac", platform: "macos", deviceType: "computer", protocolVersion: 1, endpoints: [])],
            connectedNames: [a: "Galaxy"],
            state: { $0 == self.a ? .connected : .offline },
            capabilities: { $0 == self.a ? ["clipboard": true] : nil },
            routedDeviceID: a
        )
        XCTAssertEqual(entries.map(\.deviceID), [a, b])
        let phone = entries[0]
        XCTAssertEqual(phone.name, "Galaxy", "the session's announced name wins")
        XCTAssertEqual(phone.platform, .android, "metadata recorded on an authenticated connection")
        XCTAssertEqual(phone.connection, .connected)
        XCTAssertEqual(phone.capabilities, ["clipboard": true])
        XCTAssertTrue(phone.isRouted)
        let mac = entries[1]
        XCTAssertEqual(mac.platform, .macos, "a live discovery hint fills in missing metadata")
        XCTAssertEqual(mac.connection, .offline)
        XCTAssertNil(mac.capabilities)
        XCTAssertFalse(mac.isRouted)
    }

    func testRecordedMetadataWinsOverASpoofableLiveHint() {
        let entries = DeviceDirectory.entries(
            trusted: [.init(deviceID: a, name: "Phone", platform: "android", deviceType: "phone")],
            presence: [a: PeerPresence(deviceID: a, name: "Phone", platform: "macos", deviceType: "tablet", protocolVersion: 1, endpoints: [])],
            connectedNames: [:],
            state: { _ in .connected },
            capabilities: { _ in nil },
            routedDeviceID: nil
        )
        XCTAssertEqual(entries[0].platform, .android)
        XCTAssertEqual(entries[0].deviceType, "phone")
    }

    func testUnknownPlatformHintStaysUnknown() {
        XCTAssertEqual(DevicePlatform(hint: nil), .unknown)
        XCTAssertEqual(DevicePlatform(hint: "windows"), .unknown)
        XCTAssertEqual(DevicePlatform(hint: "Android"), .android)
    }

    // MARK: - Applicability

    private func peer(_ platform: DevicePlatform, deviceType: String? = nil, capabilities: [String: Bool]? = nil) -> DeviceDirectoryEntry {
        let all = Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map { ($0.rawValue, true) })
        return DeviceDirectoryEntry(deviceID: b, name: "peer", isTrusted: true, connection: .connected,
                                    capabilities: capabilities ?? all, platform: platform, deviceType: deviceType, isRouted: false)
    }

    private func offer(_ feature: BridgeyFeature, from local: DevicePlatform, localType: String? = nil, to peer: DeviceDirectoryEntry, authorized: Bool = true) -> FeatureApplicabilityResult {
        FeatureApplicability.evaluate(feature, localPlatform: local, localDeviceType: localType, peer: peer, locallyAuthorized: authorized)
    }

    func testWebLinksAreOfferedAndroidToMacButNotMacToMac() {
        XCTAssertEqual(offer(.links, from: .android, to: peer(.macos)), .offered)
        XCTAssertEqual(offer(.links, from: .macos, to: peer(.android)), .offered)
        XCTAssertEqual(offer(.links, from: .macos, to: peer(.macos)), .notApplicable, "macOS Continuity covers Mac → Mac")
        XCTAssertEqual(offer(.links, from: .android, to: peer(.android)), .notApplicable)
    }

    func testCallsAreNeverOfferedBetweenMacsAndNeedAPhone() {
        XCTAssertEqual(offer(.calls, from: .macos, to: peer(.android, deviceType: "phone")), .offered)
        XCTAssertEqual(offer(.calls, from: .macos, to: peer(.macos)), .notApplicable)
        XCTAssertEqual(offer(.calls, from: .macos, to: peer(.android, deviceType: "tablet")), .notApplicable)
        XCTAssertEqual(offer(.calls, from: .android, localType: "phone", to: peer(.macos)), .offered)
        XCTAssertEqual(offer(.calls, from: .android, localType: "tablet", to: peer(.macos)), .notApplicable)
        XCTAssertEqual(offer(.calls, from: .android, to: peer(.android)), .notApplicable)
    }

    func testNotificationsFlowOnlyFromAndroidToMac() {
        XCTAssertEqual(offer(.notifications, from: .android, to: peer(.macos)), .offered)
        XCTAssertEqual(offer(.notifications, from: .macos, to: peer(.android)), .notApplicable)
        XCTAssertEqual(offer(.notifications, from: .macos, to: peer(.macos)), .notApplicable)
        XCTAssertEqual(offer(.notifications, from: .android, to: peer(.android)), .notApplicable)
    }

    func testFilesFindAndPingAreOfferedInEveryDirection() {
        for feature in [BridgeyFeature.files, .findDevice, .ping] {
            for (from, to) in [(DevicePlatform.android, DevicePlatform.macos), (.macos, .android), (.macos, .macos), (.android, .android)] {
                XCTAssertEqual(offer(feature, from: from, to: peer(to)), .offered, "\(feature) \(from) → \(to)")
            }
        }
    }

    func testClipboardIsNotOfferedMacToMac() {
        XCTAssertEqual(offer(.clipboard, from: .macos, to: peer(.macos)), .notApplicable)
        XCTAssertEqual(offer(.clipboard, from: .android, to: peer(.android)), .offered)
    }

    func testRemoteStartIsMacToAndroidOnly() {
        XCTAssertEqual(offer(.remoteScreenShare, from: .macos, to: peer(.android)), .offered)
        XCTAssertEqual(offer(.remoteScreenShare, from: .android, to: peer(.macos)), .notApplicable)
        XCTAssertEqual(offer(.remoteScreenShare, from: .macos, to: peer(.macos)), .notApplicable)
    }

    func testCapabilityAndAuthorizationAreSeparateFromPlatform() {
        XCTAssertEqual(offer(.clipboard, from: .macos, to: peer(.android, capabilities: ["clipboard": false])), .peerLacksCapability)
        XCTAssertEqual(offer(.clipboard, from: .macos, to: peer(.android, capabilities: [:])), .peerLacksCapability)
        XCTAssertEqual(offer(.clipboard, from: .macos, to: peer(.android), authorized: false), .notAuthorized)
    }

    func testUnknownPlatformNeverHidesAFeatureButSecurityStillApplies() {
        XCTAssertEqual(offer(.links, from: .macos, to: peer(.unknown)), .offered, "a missing hint never restricts")
        XCTAssertEqual(offer(.links, from: .macos, to: peer(.unknown), authorized: false), .notAuthorized)
        XCTAssertEqual(offer(.links, from: .macos, to: peer(.unknown, capabilities: [:])), .peerLacksCapability)
    }
}
