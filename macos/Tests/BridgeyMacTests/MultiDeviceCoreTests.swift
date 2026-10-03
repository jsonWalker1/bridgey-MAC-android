import Foundation
import Security
import XCTest
@testable import BridgeyMac

/// VIRTUAL multi-device Core tests: several independent peers are simulated in-process against the
/// same Core components PairingCoordinator uses (PeerSessionManager, DeviceRegistry,
/// DevicePresence, DeviceRouting, ReconnectPlanner, PeerFeatureState). They prove the architectural
/// invariants; they do not prove real multi-device hardware behaviour.
final class MultiDeviceCoreTests: XCTestCase {
    private final class FakeSession {
        let label: String
        init(_ label: String) { self.label = label }
    }

    // Lowercase UUIDs, ordered: A < B < C < D < LOCAL_HIGH, LOCAL_LOW < everything.
    private let localLow = "00000000-0000-4000-8000-000000000000"
    private let local = "50000000-0000-4000-8000-000000000000"
    private let a = "10000000-0000-4000-8000-00000000000a"
    private let b = "20000000-0000-4000-8000-00000000000b"
    private let c = "30000000-0000-4000-8000-00000000000c"
    private let d = "40000000-0000-4000-8000-00000000000d"

    // MARK: - helpers

    @discardableResult
    private func connect(
        _ manager: PeerSessionManager<FakeSession>,
        _ deviceID: String,
        initiatedLocally: Bool = false,
        capabilities: [String: Bool]? = nil
    ) -> FakeSession {
        let session = FakeSession(deviceID)
        manager.addPending(session, initiatedLocally: initiatedLocally, expectedDeviceID: initiatedLocally ? deviceID : nil)
        XCTAssertEqual(manager.identify(session, as: deviceID).result, .identified)
        XCTAssertTrue(manager.markConnected(session))
        if let capabilities { XCTAssertTrue(manager.setCapabilities(capabilities, for: session)) }
        return session
    }

    private struct Snapshot: Equatable {
        let state: PeerConnectionState
        let phase: PeerSessionPhase?
        let capabilities: [String: Bool]?
        let sessionID: ObjectIdentifier?
    }

    private func snapshot(_ manager: PeerSessionManager<FakeSession>, _ id: String) -> Snapshot {
        let session = manager.session(for: id)
        return Snapshot(
            state: manager.state(of: id),
            phase: session.flatMap(manager.phase(of:)),
            capabilities: manager.capabilities(for: id),
            sessionID: session.map(ObjectIdentifier.init)
        )
    }

    private func active(_ manager: PeerSessionManager<FakeSession>, preferred: String?, mode: DeviceRoutingMode = .singleActive) -> String? {
        DeviceRouting.activePeer(mode: mode, preferredDeviceID: preferred, connected: manager.connectedInOrder)
    }

    private func peer(_ service: String, id: String?, name: String = "Phone", platform: String? = "android",
                      type: String? = nil, host: String? = "192.168.0.10", port: Int? = 42_458) -> DiscoveredPeer {
        DiscoveredPeer(serviceName: service, deviceIDHint: id, deviceNameHint: name, platformHint: platform,
                       deviceTypeHint: type, protocolVersionHint: 1, host: host, port: port)
    }

    private func makeRegistry(_ tag: String = UUID().uuidString) -> DeviceRegistry {
        let trust = MacTrustRegistry(service: "dev.bridgey.tests.multidevice.\(tag)", migrating: [])
        addTeardownBlock { trust.deleteStorageForTesting() }
        return DeviceRegistry(trust: trust)
    }

    private func key(_ seed: String) -> String { Data("identity-\(seed)".utf8).base64EncodedString() }

    // MARK: - 1. Device identity

    func testDifferentDeviceIDsAreDifferentDevices() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        connect(manager, a)
        connect(manager, b)
        XCTAssertEqual(Set(manager.connectedInOrder.map(\.deviceID)), [a, b])
        XCTAssertFalse(manager.session(for: a) === manager.session(for: b))
    }

    func testSameDeviceIDCannotSilentlyBecomeADifferentIdentityKey() {
        let registry = makeRegistry()
        XCTAssertTrue(registry.remember(deviceID: a, name: "Phone", identityKey: key("a")))
        XCTAssertEqual(registry.evaluate(deviceID: a, identityKey: key("a")), .trusted)
        XCTAssertEqual(registry.evaluate(deviceID: a, identityKey: key("attacker")), .identityMismatch)
        XCTAssertFalse(registry.remember(deviceID: a, name: "Phone", identityKey: key("attacker")), "rebinding must be refused")
        XCTAssertEqual(registry.identityKey(for: a), key("a"))
        XCTAssertEqual(registry.evaluate(deviceID: b, identityKey: key("b")), .unknown)
    }

    func testServiceNameHostnameAndAddressAreNotIdentity() {
        let presence = DevicePresence.group([
            peer("Bridgey-10000000", id: a, host: "phone.local"),
            peer("Bridgey-10000000 (2)", id: a, host: "192.168.0.10"),
            peer("Bridgey-other", id: a, host: "fe80::1"),
        ], localDeviceID: local)
        XCTAssertEqual(Array(presence.keys), [a], "three adverts of one deviceId are one device")
        XCTAssertEqual(presence[a]?.endpoints.count, 3)

        let sameNameSameHost = DevicePresence.group([
            peer("Bridgey-x", id: a, name: "MacBook", host: "mac.local"),
            peer("Bridgey-x (2)", id: b, name: "MacBook", host: "mac.local"),
        ], localDeviceID: local)
        XCTAssertEqual(Set(sameNameSameHost.keys), [a, b], "identical name/host never merges two deviceIds")
    }

    func testEndpointChangeUpdatesPresenceNotIdentity() {
        let registry = makeRegistry()
        XCTAssertTrue(registry.remember(deviceID: c, name: "Galaxy", identityKey: key("c")))
        registry.updatePresence(DevicePresence.group([peer("Bridgey-c", id: c, host: "192.168.0.20")], localDeviceID: local))
        registry.updatePresence(DevicePresence.group([peer("Bridgey-c (2)", id: c, host: "192.168.0.77")], localDeviceID: local))
        XCTAssertEqual(registry.trustedDeviceIDs, [c])
        XCTAssertEqual(registry.endpoints(for: c).map(\.host), ["192.168.0.77"])
        XCTAssertEqual(registry.evaluate(deviceID: c, identityKey: key("c")), .trusted)
    }

    func testPlatformAndTypeDoNotParticipateInIdentity() {
        let asMac = DevicePresence.group([peer("s1", id: a, platform: "macos", type: "computer")], localDeviceID: local)
        let asAndroid = DevicePresence.group([peer("s1", id: a, platform: "android", type: "phone")], localDeviceID: local)
        XCTAssertEqual(Array(asMac.keys), Array(asAndroid.keys))
        let registry = makeRegistry()
        XCTAssertTrue(registry.remember(deviceID: a, name: "X", identityKey: key("a")))
        registry.updatePresence(asMac)
        XCTAssertEqual(registry.evaluate(deviceID: a, identityKey: key("a")), .trusted)
        registry.updatePresence(asAndroid)
        XCTAssertEqual(registry.evaluate(deviceID: a, identityKey: key("a")), .trusted)
    }

    func testOwnAdvertIsFilteredByDeviceIDEvenAfterServiceRename() {
        let presence = DevicePresence.group([
            peer("Bridgey-50000000 (2)", id: local, name: "This Mac"),
            peer("Bridgey-a", id: a),
            peer("legacy-without-id", id: nil),
            peer("unresolved", id: b, host: nil, port: nil),
        ], localDeviceID: local)
        XCTAssertEqual(Array(presence.keys), [a])
    }

    // MARK: - 2. Multiple simultaneous sessions / 9. failure isolation

    func testThreeSimultaneousSessionsAreIndependent() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        let sessionA = connect(manager, a, capabilities: ["clipboard": true])
        let beforeB = snapshot(manager, a)
        connect(manager, b, initiatedLocally: true, capabilities: ["clipboard": false])
        XCTAssertEqual(snapshot(manager, a), beforeB, "connecting B does not touch A")
        let beforeC = (snapshot(manager, a), snapshot(manager, b))
        connect(manager, c, capabilities: ["files": true])
        XCTAssertEqual(snapshot(manager, a), beforeC.0)
        XCTAssertEqual(snapshot(manager, b), beforeC.1)
        XCTAssertEqual(manager.capabilities(for: a), ["clipboard": true])
        XCTAssertEqual(manager.capabilities(for: b), ["clipboard": false])
        XCTAssertEqual(manager.capabilities(for: c), ["files": true])

        // A disconnects (heartbeat timeout / socket failure / explicit disconnect all end here).
        let keep = (snapshot(manager, b), snapshot(manager, c))
        XCTAssertEqual(manager.remove(sessionA), a)
        XCTAssertEqual(manager.state(of: a), .offline)
        XCTAssertEqual(snapshot(manager, b), keep.0)
        XCTAssertEqual(snapshot(manager, c), keep.1)

        // A reconnects with a new session: B and C untouched.
        let newA = connect(manager, a)
        XCTAssertFalse(newA === sessionA)
        XCTAssertEqual(snapshot(manager, b), keep.0)
        XCTAssertEqual(snapshot(manager, c), keep.1)
        XCTAssertEqual(manager.state(of: a), .connected)
    }

    func testPhasesAreTrackedPerSession() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        connect(manager, a)
        let sessionB = FakeSession("b")
        manager.addPending(sessionB, initiatedLocally: false, expectedDeviceID: nil)
        XCTAssertEqual(manager.identify(sessionB, as: b).result, .identified)
        XCTAssertTrue(manager.setPhase(.verifying, for: sessionB))
        XCTAssertEqual(manager.state(of: a), .connected)
        XCTAssertEqual(manager.state(of: b), .verifying)
        XCTAssertTrue(manager.verifyingSession === sessionB)
        XCTAssertEqual(manager.connectedInOrder.map(\.deviceID), [a], "a verifying peer is not connected")
    }

    func testFailureOfASessionInEveryPhaseIsIsolated() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        connect(manager, b)
        connect(manager, c)
        let keep = (snapshot(manager, b), snapshot(manager, c))

        // Reconnect failure: an outgoing dial to A that never identifies.
        let dial = FakeSession("dial-a")
        manager.addPending(dial, initiatedLocally: true, expectedDeviceID: a)
        XCTAssertTrue(manager.isBusy(a))
        XCTAssertEqual(manager.remove(dial), a, "a failed dial reports its target, so only that device backs off")
        XCTAssertNil(manager.remove(dial), "removing twice reports nothing")
        XCTAssertFalse(manager.isBusy(a))

        // Handshake failure after identification.
        let handshake = FakeSession("hs-a")
        manager.addPending(handshake, initiatedLocally: false, expectedDeviceID: nil)
        _ = manager.identify(handshake, as: a)
        XCTAssertEqual(manager.remove(handshake), a)

        // Heartbeat timeout / socket failure on a connected A.
        let connected = connect(manager, a)
        XCTAssertEqual(manager.remove(connected), a)

        XCTAssertEqual(snapshot(manager, b), keep.0)
        XCTAssertEqual(snapshot(manager, c), keep.1)
        XCTAssertEqual(manager.state(of: a), .offline)
    }

    // MARK: - 3. Duplicate session rule (per deviceId)

    func testDuplicateRuleIsPerDeviceID() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        let x = connect(manager, a)
        let duplicate = FakeSession("a-again")
        manager.addPending(duplicate, initiatedLocally: false, expectedDeviceID: nil)
        XCTAssertEqual(manager.identify(duplicate, as: a).result, .rejectedDuplicate)
        XCTAssertTrue(manager.session(for: a) === x, "the live session survives")
        XCTAssertNil(manager.remove(duplicate), "closing the rejected duplicate must not remove the live session")
        XCTAssertTrue(manager.session(for: a) === x)

        connect(manager, b)
        connect(manager, c)
        XCTAssertEqual(manager.connectedInOrder.map(\.deviceID), [a, b, c])
    }

    func testSimultaneousDialRaceConvergesOnTheSameConnectionOnBothSides() {
        // Device L (lower id) and H (higher id) dial each other at the same moment.
        let low = PeerSessionManager<FakeSession>(localDeviceID: localLow)
        let high = PeerSessionManager<FakeSession>(localDeviceID: local)
        // Connection 1: L -> H. Connection 2: H -> L. Each side has one outgoing and one incoming.
        let lowOut = FakeSession("conn1@L"), highIn = FakeSession("conn1@H")
        let highOut = FakeSession("conn2@H"), lowIn = FakeSession("conn2@L")
        low.addPending(lowOut, initiatedLocally: true, expectedDeviceID: local)
        low.addPending(lowIn, initiatedLocally: false, expectedDeviceID: nil)
        high.addPending(highOut, initiatedLocally: true, expectedDeviceID: localLow)
        high.addPending(highIn, initiatedLocally: false, expectedDeviceID: nil)

        // Offers arrive first on both sides (incoming identified first), then answers.
        XCTAssertEqual(low.identify(lowIn, as: local).result, .identified)
        XCTAssertEqual(high.identify(highIn, as: localLow).result, .identified)
        let lowAnswer = low.identify(lowOut, as: local)
        let highAnswer = high.identify(highOut, as: localLow)

        // Connection 1 (initiated by the lower id) wins on both sides.
        XCTAssertEqual(lowAnswer.result, .identified)
        XCTAssertTrue(lowAnswer.displaced === lowIn)
        XCTAssertEqual(highAnswer.result, .rejectedDuplicate)
        XCTAssertTrue(low.session(for: local) === lowOut)
        XCTAssertTrue(high.session(for: localLow) === highIn)
    }

    func testTalkingToOurselvesIsRejected() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        let loop = FakeSession("loop")
        manager.addPending(loop, initiatedLocally: true, expectedDeviceID: nil)
        XCTAssertEqual(manager.identify(loop, as: local).result, .rejectedSelf)
    }

    // MARK: - 4. Pending sessions

    func testPendingSessionBecomesDeviceSessionOnlyAfterIdentification() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        let live = connect(manager, a)
        let socket = FakeSession("accepted")
        manager.addPending(socket, initiatedLocally: false, expectedDeviceID: nil)
        XCTAssertNil(manager.deviceID(of: socket))
        XCTAssertTrue(manager.contains(socket))
        XCTAssertTrue(manager.session(for: a) === live, "an unidentified socket never collides with A")
        XCTAssertEqual(manager.state(of: b), .offline)

        XCTAssertEqual(manager.identify(socket, as: b).result, .identified)
        XCTAssertEqual(manager.deviceID(of: socket), b)
        XCTAssertEqual(manager.state(of: b), .connecting)
        XCTAssertTrue(manager.session(for: a) === live)
    }

    func testASessionCannotChangeItsDeviceIDMidHandshake() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        let socket = FakeSession("s")
        manager.addPending(socket, initiatedLocally: false, expectedDeviceID: nil)
        XCTAssertEqual(manager.identify(socket, as: a).result, .identified)
        XCTAssertEqual(manager.identify(socket, as: b).result, .rejectedIdentityChange)
        XCTAssertEqual(manager.deviceID(of: socket), a)
    }

    // MARK: - 5. CONNECTED != ACTIVE / 8. per-session capabilities

    func testSwitchingActivePeerMutatesNoSessionState() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        connect(manager, a, capabilities: ["clipboard": true, "files": false])
        connect(manager, b, capabilities: ["clipboard": false, "files": true])
        let before = (snapshot(manager, a), snapshot(manager, b))

        XCTAssertEqual(active(manager, preferred: a), a)
        XCTAssertEqual(active(manager, preferred: b), b)
        XCTAssertEqual(active(manager, preferred: a, mode: .multipleActive), a)

        XCTAssertEqual(snapshot(manager, a), before.0)
        XCTAssertEqual(snapshot(manager, b), before.1)
        XCTAssertEqual(manager.state(of: a), .connected, "inactive is still connected")
        XCTAssertEqual(manager.capabilities(for: a), ["clipboard": true, "files": false])
        XCTAssertEqual(manager.capabilities(for: b), ["clipboard": false, "files": true])
    }

    // MARK: - 6. Preferred device

    func testPreferredDeviceFallbackAndRestore() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        XCTAssertNil(active(manager, preferred: a), "preferred offline: nothing to route to, preference kept")
        connect(manager, b)
        XCTAssertEqual(active(manager, preferred: a), b, "preferred offline: earliest connected")
        let sessionA = connect(manager, a)
        XCTAssertEqual(active(manager, preferred: a), a, "preferred connected: preferred is active")
        connect(manager, c)
        XCTAssertEqual(active(manager, preferred: a), a)
        manager.remove(sessionA)
        XCTAssertEqual(active(manager, preferred: a), b, "fallback is the earliest still-connected device")
        connect(manager, a)
        XCTAssertEqual(active(manager, preferred: a), a, "preferred returns and is active again")
        XCTAssertEqual(active(manager, preferred: nil), b, "no preference: earliest connected")
    }

    // MARK: - 7. No fake features.update

    func testEverySessionGetsItsRealFeatureStateRegardlessOfActivePeer() {
        let perDevice: [String: [BridgeyFeature: Bool]] = [a: [.clipboard: true], b: [.clipboard: true, .files: false]]
        let isEnabled: (BridgeyFeature, String) -> Bool = { feature, deviceID in perDevice[deviceID]?[feature] ?? true }
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        connect(manager, a)
        connect(manager, b)

        for preferred in [a, b] {
            let activePeer = active(manager, preferred: preferred)
            XCTAssertNotNil(activePeer)
            for deviceID in [a, b] {
                let payload = PeerFeatureState.payload(for: deviceID, isEnabled: isEnabled)
                XCTAssertEqual(payload.count, BridgeyFeature.allCases.count)
                XCTAssertEqual(payload[BridgeyFeature.clipboard.rawValue], true, "inactive \(deviceID) is never sent disabled capabilities")
                XCTAssertTrue(payload.values.contains(true))
            }
            XCTAssertEqual(PeerFeatureState.payload(for: b, isEnabled: isEnabled)[BridgeyFeature.files.rawValue], false,
                           "only the real per-device setting turns a feature off")
        }
    }

    // MARK: - 10. Trust is pairwise

    func testTrustIsPairwiseAndNeverPropagated() {
        let registryA = makeRegistry(), registryB = makeRegistry(), registryC = makeRegistry()
        XCTAssertTrue(registryA.remember(deviceID: b, name: "B", identityKey: key("b")))
        XCTAssertTrue(registryB.remember(deviceID: a, name: "A", identityKey: key("a")))
        XCTAssertTrue(registryB.remember(deviceID: c, name: "C", identityKey: key("c")))
        XCTAssertTrue(registryC.remember(deviceID: b, name: "B", identityKey: key("b")))
        XCTAssertEqual(registryA.evaluate(deviceID: c, identityKey: key("c")), .unknown)
        XCTAssertEqual(registryC.evaluate(deviceID: a, identityKey: key("a")), .unknown)
        XCTAssertEqual(registryA.trustedDeviceIDs, [b])
    }

    // MARK: - 11. Migration of the legacy single-device state

    func testLegacySingleDeviceTrustRecordMigratesUnchanged() throws {
        let service = "dev.bridgey.tests.multidevice.legacy.\(UUID().uuidString)"
        // Exactly what the previous release wrote: {id: {id, name, identityKey}}, no metadata.
        let legacyJSON = #"{"\#(a)":{"id":"\#(a)","name":"Galaxy S23","identityKey":"\#(key("a"))"}}"#
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "trusted-devices-v1",
            kSecValueData as String: Data(legacyJSON.utf8),
        ]
        XCTAssertEqual(SecItemAdd(add as CFDictionary, nil), errSecSuccess)
        let trust = MacTrustRegistry(service: service, migrating: [])
        defer { trust.deleteStorageForTesting() }
        let registry = DeviceRegistry(trust: trust)

        XCTAssertEqual(registry.trustedDeviceIDs, [a])
        XCTAssertEqual(registry.identityKey(for: a), key("a"))
        XCTAssertEqual(registry.evaluate(deviceID: a, identityKey: key("a")), .trusted)
        let device = try XCTUnwrap(registry.device(a))
        XCTAssertEqual(device.name, "Galaxy S23")
        XCTAssertNil(device.platform)
        XCTAssertNil(device.lastSeen)

        XCTAssertEqual(DeviceRouting.migratedPreferredDeviceID(stored: nil, migrated: false, trustedDeviceIDs: registry.trustedDeviceIDs), a)
        XCTAssertNil(DeviceRouting.migratedPreferredDeviceID(stored: nil, migrated: true, trustedDeviceIDs: [a]), "migration runs once")
        XCTAssertNil(DeviceRouting.migratedPreferredDeviceID(stored: nil, migrated: false, trustedDeviceIDs: [a, b]), "no guess with several devices")
        XCTAssertEqual(DeviceRouting.migratedPreferredDeviceID(stored: b, migrated: false, trustedDeviceIDs: [a]), b)

        // Metadata recorded later keeps the identity untouched and survives a reload.
        let seen = Date(timeIntervalSince1970: 1_800_000_000)
        registry.updatePresence(DevicePresence.group([peer("Bridgey-a", id: a, platform: "android", type: "phone")], localDeviceID: local))
        XCTAssertTrue(registry.recordConnection(deviceID: a, name: "Galaxy S23", at: seen))
        let reloaded = DeviceRegistry(trust: MacTrustRegistry(service: service, migrating: []))
        XCTAssertEqual(reloaded.identityKey(for: a), key("a"))
        XCTAssertEqual(reloaded.device(a)?.platform, "android")
        XCTAssertEqual(reloaded.device(a)?.deviceType, "phone")
        XCTAssertEqual(reloaded.device(a)?.lastSeen, seen)
    }

    func testSingleDeviceBehaviourIsUnchangedWithOnePeer() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: local)
        let preferred = DeviceRouting.migratedPreferredDeviceID(stored: nil, migrated: false, trustedDeviceIDs: [a])
        XCTAssertNil(active(manager, preferred: preferred))
        connect(manager, a)
        XCTAssertEqual(active(manager, preferred: preferred), a)
    }

    // MARK: - Discovery / reconnect

    func testDiscoveryOfMultipleTrustedDevicesDialsEachIndependently() {
        let presence = DevicePresence.group([
            peer("Bridgey-a", id: a, platform: "macos", type: "computer", host: "10.0.0.1"),
            peer("Bridgey-b", id: b, platform: "macos", type: "computer", host: "10.0.0.2"),
            peer("Bridgey-c", id: c, platform: "android", type: "phone", host: "10.0.0.3"),
            peer("Bridgey-d", id: d, platform: "android", type: "tablet", host: "10.0.0.4"),
            peer("Bridgey-x", id: "60000000-0000-4000-8000-000000000006", host: "10.0.0.9"),
        ], localDeviceID: localLow)
        let trusted: Set = [a, b, c, d]
        var busy: Set<String> = [b]
        let targets = ReconnectPlanner.discoveryDialTargets(
            localDeviceID: localLow, trustedDeviceIDs: trusted, presence: presence, isBusy: { busy.contains($0) }
        )
        XCTAssertEqual(targets.map(\.deviceID), [a, c, d], "untrusted and busy devices are not dialled")
        XCTAssertEqual(targets.map(\.endpoint.host), ["10.0.0.1", "10.0.0.3", "10.0.0.4"])

        busy = []
        let fromHigherID = ReconnectPlanner.discoveryDialTargets(
            localDeviceID: "ffffffff-0000-4000-8000-000000000000", trustedDeviceIDs: trusted, presence: presence, isBusy: { _ in false }
        )
        XCTAssertTrue(fromHigherID.isEmpty, "the lower id dials; the higher id waits (per pair, no platform rule)")
    }

    func testReconnectRotatesThroughEndpointsOfOneDevice() {
        let presence = DevicePresence.group([
            peer("Bridgey-a", id: a, host: "10.0.0.1"), peer("Bridgey-a (2)", id: a, host: "10.0.0.2"),
        ], localDeviceID: localLow)
        let hosts = (0..<4).map { attempt in
            ReconnectPlanner.discoveryDialTargets(localDeviceID: localLow, trustedDeviceIDs: [a], presence: presence,
                                                  endpointIndex: { _ in attempt }, isBusy: { _ in false }).first?.endpoint.host
        }
        XCTAssertEqual(hosts, ["10.0.0.1", "10.0.0.2", "10.0.0.1", "10.0.0.2"])
    }

    func testOwnAdvertFilterIsCaseInsensitive() {
        let presence = DevicePresence.group([peer("Bridgey-x", id: local), peer("Bridgey-a", id: a)],
                                            localDeviceID: local.uppercased())
        XCTAssertEqual(Array(presence.keys), [a])
    }

    func testTXTTypeIsAnAdditiveHintAndUnknownKeysAreIgnored() {
        let parsed = DiscoveryTXTRecord.parse(serviceName: "Bridgey-a", attributes: [
            "id": Data(a.utf8), "name": Data("Tablet".utf8), "platform": Data("android".utf8),
            "type": Data("tablet".utf8), "future-key": Data("whatever".utf8),
        ])
        XCTAssertEqual(parsed.deviceIDHint, a)
        XCTAssertEqual(parsed.deviceTypeHint, "tablet")
        let invalid = DiscoveryTXTRecord.parse(serviceName: "s", attributes: ["type": Data("Not A Type!".utf8)])
        XCTAssertNil(invalid.deviceTypeHint)
        let legacy = DiscoveryTXTRecord.parse(serviceName: "s", attributes: ["id": Data(a.utf8)])
        XCTAssertNil(legacy.deviceTypeHint)
    }

    // MARK: - 12. Topology / platform independence + the A/B/C/D scenario

    func testTopologyMatrixUsesIdenticalCoreLogic() {
        // Mac->Mac, Mac->Android, Android->Mac, Android->Android: the platform is only a hint, so the
        // Core result for each pair must be identical.
        var results: [String] = []
        for localPlatform in ["macos", "android"] {
            for remotePlatform in ["macos", "android"] {
                let manager = PeerSessionManager<FakeSession>(localDeviceID: localLow)
                let presence = DevicePresence.group([peer("svc", id: a, platform: remotePlatform)], localDeviceID: localLow)
                let targets = ReconnectPlanner.discoveryDialTargets(localDeviceID: localLow, trustedDeviceIDs: [a], presence: presence, isBusy: manager.isBusy)
                connect(manager, a, initiatedLocally: true, capabilities: ["clipboard": true])
                results.append("\(targets.map(\.deviceID))|\(manager.state(of: a))|\(active(manager, preferred: a) ?? "-")")
                _ = localPlatform
            }
        }
        XCTAssertEqual(Set(results).count, 1, "all four topologies must produce the same Core outcome: \(results)")
    }

    func testFourDeviceLANScenario() {
        // Local device is MacBook Air B. Peers: MacBook Pro A, Galaxy S23 C, Android tablet D.
        let me = "25000000-0000-4000-8000-0000000000bb"
        let registry = makeRegistry()
        for (id, name) in [(a, "MacBook"), (c, "Galaxy S23"), (d, "Tablet")] {
            XCTAssertTrue(registry.remember(deviceID: id, name: name, identityKey: key(id)))
        }
        let manager = PeerSessionManager<FakeSession>(localDeviceID: me)
        var adverts = [
            peer("Bridgey-a", id: a, name: "MacBook", platform: "macos", type: "computer", host: "10.0.0.1"),
            peer("Bridgey-c", id: c, name: "Galaxy S23", platform: "android", type: "phone", host: "10.0.0.3"),
            peer("Bridgey-d", id: d, name: "Tablet", platform: "android", type: "tablet", host: "10.0.0.4"),
        ]
        registry.updatePresence(DevicePresence.group(adverts, localDeviceID: me))
        XCTAssertEqual(Set(registry.presentDeviceIDs), [a, c, d])

        // All establish authenticated sessions (A dials us since a < me; we dial C and D).
        let sessionA = connect(manager, a)
        let sessionC = connect(manager, c, initiatedLocally: true)
        let sessionD = connect(manager, d, initiatedLocally: true)
        for id in [a, c, d] { XCTAssertEqual(registry.evaluate(deviceID: id, identityKey: key(id)), .trusted) }

        // A disconnects; C changes IP; D leaves Wi-Fi.
        manager.remove(sessionA)
        manager.remove(sessionC)
        manager.remove(sessionD)
        adverts = [
            peer("Bridgey-c (2)", id: c, name: "Galaxy S23", platform: "android", type: "phone", host: "10.0.0.33"),
        ]
        registry.updatePresence(DevicePresence.group(adverts, localDeviceID: me))
        XCTAssertEqual(manager.state(of: d), .offline)
        XCTAssertTrue(registry.trustedDeviceIDs.contains(d), "D is offline but still trusted")
        XCTAssertTrue(registry.endpoints(for: d).isEmpty)

        let dial = ReconnectPlanner.discoveryDialTargets(localDeviceID: me, trustedDeviceIDs: registry.trustedDeviceIDs,
                                                         presence: registry.presence, isBusy: manager.isBusy)
        XCTAssertEqual(dial.map(\.deviceID), [c])
        XCTAssertEqual(dial.first?.endpoint.host, "10.0.0.33", "C is the same device at its new address")

        // A returns, C returns with the new IP; nobody re-pairs.
        adverts.append(peer("Bridgey-a", id: a, name: "MacBook", platform: "macos", type: "computer", host: "10.0.0.1"))
        registry.updatePresence(DevicePresence.group(adverts, localDeviceID: me))
        connect(manager, a)
        connect(manager, c, initiatedLocally: true)
        XCTAssertEqual(registry.evaluate(deviceID: a, identityKey: key(a)), .trusted)
        XCTAssertEqual(registry.evaluate(deviceID: c, identityKey: key(c)), .trusted)
        XCTAssertEqual(Set(manager.connectedInOrder.map(\.deviceID)), [a, c])
        XCTAssertEqual(manager.state(of: d), .offline)
        XCTAssertEqual(registry.trustedDeviceIDs, [a, c, d], "no device was added, replaced or re-paired")
    }

    func testIdenticalDisplayNamesStayDistinctDevices() {
        let registry = makeRegistry()
        XCTAssertTrue(registry.remember(deviceID: a, name: "MacBook Pro", identityKey: key("a")))
        XCTAssertTrue(registry.remember(deviceID: b, name: "MacBook Pro", identityKey: key("b")))
        XCTAssertTrue(registry.remember(deviceID: c, name: "Galaxy", identityKey: key("c")))
        XCTAssertTrue(registry.remember(deviceID: d, name: "Galaxy", identityKey: key("d")))
        XCTAssertEqual(registry.trustedDeviceIDs.count, 4)
        XCTAssertEqual(registry.evaluate(deviceID: a, identityKey: key("b")), .identityMismatch)
    }
}
