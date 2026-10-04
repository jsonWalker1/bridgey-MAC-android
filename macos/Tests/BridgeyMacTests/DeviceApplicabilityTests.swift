import XCTest
@testable import BridgeyMac

/// MD-2 device roles and applicability: classification, direction, the audit's matrix, separation
/// of capability / platform / role / authorization, and per-device isolation.
final class DeviceApplicabilityTests: XCTestCase {
    private typealias Feature = FeatureApplicability.Feature

    private let androidPhone = DeviceProfile(platform: .android, kind: .phone)
    private let androidTablet = DeviceProfile(platform: .android, kind: .tablet)
    private let mac = DeviceProfile(platform: .macos, kind: .computer)
    private let unknown = DeviceProfile(platform: .unknown, kind: .unknown)

    private let allCapabilities = Dictionary(uniqueKeysWithValues:
        ["clipboard", "files", "notifications", "battery", "storage", "memory", "cpu", "temperature", "find_device",
         "ping", "links", "media", "calls", "photo_sync", "remote_screen_share", "kvm_input"].map { ($0, true) })

    private func peer(
        _ profile: DeviceProfile,
        id: String = "20000000-0000-4000-8000-00000000000b",
        capabilities: [String: Bool]? = nil
    ) -> DeviceDirectoryEntry {
        DeviceDirectoryEntry(deviceID: id, name: "peer", isTrusted: true, connection: .connected,
                             capabilities: capabilities ?? allCapabilities, platform: profile.platform,
                             kind: profile.kind, isRouted: false)
    }

    private func applicable(_ feature: Feature, _ source: DeviceProfile, _ target: DeviceProfile) -> Bool {
        FeatureApplicability.isApplicable(feature, source: source, target: target)
    }

    // MARK: - A. Device classification

    func testAndroidPhoneOwnsTheCellularLine() {
        let profile = DeviceProfile(platform: DevicePlatform(hint: "android"), kind: DeviceKind(hint: "phone"))
        XCTAssertEqual(profile, androidPhone)
        XCTAssertTrue(profile.ownsCellularLine)
    }

    func testMacIsAComputerWithoutACellularLine() {
        let profile = DeviceProfile(platform: DevicePlatform(hint: "macos"), kind: DeviceKind(hint: "computer"))
        XCTAssertEqual(profile, mac)
        XCTAssertFalse(profile.ownsCellularLine)
    }

    func testUnknownOrUnavailablePlatformHint() {
        XCTAssertEqual(DevicePlatform(hint: nil), .unknown)
        XCTAssertEqual(DevicePlatform(hint: ""), .unknown)
        XCTAssertEqual(DevicePlatform(hint: "windows"), .unknown)
    }

    func testMissingRoleIsUnknownAndOwnsNothing() {
        XCTAssertEqual(DeviceKind(hint: nil), .unknown)
        XCTAssertEqual(DeviceKind(hint: "watch"), .unknown)
        XCTAssertFalse(DeviceProfile(platform: .android, kind: .unknown).ownsCellularLine)
        XCTAssertFalse(androidTablet.ownsCellularLine)
    }

    func testTheDirectoryCarriesKindFromHints() {
        let entries = DeviceDirectory.entries(
            trusted: [.init(deviceID: "a", name: "A", platform: "android", deviceType: "phone")],
            presence: [:], connectedNames: [:], state: { _ in .connected }, capabilities: { _ in nil }, routedDeviceID: nil
        )
        XCTAssertEqual(entries[0].profile, androidPhone)
    }

    // MARK: - B. Direction

    func testDirectionIsSourceToTargetNotSymmetric() {
        XCTAssertTrue(applicable(.notificationMirror, androidPhone, mac), "Android → macOS")
        XCTAssertFalse(applicable(.notificationMirror, mac, androidPhone), "macOS → Android")
        XCTAssertTrue(applicable(.notificationActions, mac, androidPhone), "macOS → Android")
        XCTAssertFalse(applicable(.notificationActions, androidPhone, mac), "Android → macOS")
        XCTAssertFalse(applicable(.notificationMirror, mac, mac), "macOS → macOS")
        XCTAssertFalse(applicable(.notificationMirror, androidPhone, androidPhone), "Android → Android")
        XCTAssertTrue(applicable(.clipboard, androidPhone, androidPhone), "Android → Android")
        XCTAssertFalse(applicable(.clipboard, mac, mac), "macOS → macOS (Universal Clipboard)")
    }

    // MARK: - C. The audit's matrix

    func testAuditExamples() {
        XCTAssertTrue(applicable(.webHandoff, androidPhone, mac))
        XCTAssertFalse(applicable(.webHandoff, mac, mac), "macOS Continuity Handoff covers Mac → Mac")
        XCTAssertTrue(applicable(.booksHandoff, androidPhone, mac))
        XCTAssertFalse(applicable(.booksHandoff, mac, mac))
        XCTAssertFalse(applicable(.callControl, mac, mac))
        XCTAssertFalse(applicable(.callState, mac, mac))
        XCTAssertTrue(applicable(.screenShare, androidPhone, mac))
        XCTAssertFalse(applicable(.screenShare, mac, mac))
        XCTAssertTrue(applicable(.kvm, mac, androidPhone))
        XCTAssertFalse(applicable(.kvm, androidPhone, androidPhone))
        XCTAssertFalse(applicable(.kvm, mac, mac))
        XCTAssertTrue(applicable(.notificationMirror, androidPhone, mac))
        XCTAssertFalse(applicable(.notificationMirror, mac, mac))
        XCTAssertFalse(applicable(.mediaRemote, mac, mac), "no Android media remote between Macs")
        XCTAssertTrue(applicable(.mediaRemote, androidPhone, mac))
        XCTAssertTrue(applicable(.macPlayerControl, androidPhone, mac))
        XCTAssertTrue(applicable(.remoteStart, mac, androidPhone))
        XCTAssertFalse(applicable(.remoteStart, androidPhone, mac))
        XCTAssertTrue(applicable(.linkToPhone, mac, androidPhone))
        XCTAssertFalse(applicable(.linkToPhone, mac, mac))
        XCTAssertTrue(applicable(.photoSync, androidPhone, mac))
        XCTAssertFalse(applicable(.photoSync, mac, androidPhone))
    }

    func testAndroidToMacOffersEveryFeatureTheAuditLists() {
        let androidToMac: [Feature] = [.webHandoff, .booksHandoff, .screenShare, .notificationMirror, .mediaRemote,
                                       .macPlayerControl, .callState, .telemetry, .clipboard, .files, .photoSync]
        for feature in androidToMac { XCTAssertTrue(applicable(feature, androidPhone, mac), "\(feature)") }
        XCTAssertTrue(applicable(.kvm, mac, androidPhone), "KVM is Mac → Android (frozen; metadata only)")
    }

    func testCallsFollowTheCellularLineNotThePlatform() {
        XCTAssertTrue(applicable(.callControl, mac, androidPhone))
        XCTAssertFalse(applicable(.callControl, mac, androidTablet), "a tablet has no cellular line")
        XCTAssertFalse(applicable(.callControl, mac, DeviceProfile(platform: .android, kind: .unknown)), "unknown kind is not a phone")
        XCTAssertTrue(applicable(.callState, androidPhone, mac))
        XCTAssertFalse(applicable(.callState, androidTablet, mac))
    }

    func testTheTablesOfBothPlatformsListTheSameFeatures() {
        XCTAssertEqual(Feature.allCases.count, 18) // must match FeatureApplicability.Feature on Android
    }

    // MARK: - D. Separation of concerns

    func testCapabilityAloneDoesNotImplyApplicability() {
        let result = FeatureApplicability.evaluate(.webHandoff, local: mac, peer: peer(mac), localIsSource: false) { _ in true }
        XCTAssertEqual(result, .notApplicable, "a Mac peer that advertises `links` still gets no Web Handoff")
    }

    func testPlatformAloneDoesNotImplyAuthorization() {
        let result = FeatureApplicability.evaluate(.clipboard, local: mac, peer: peer(androidPhone), localIsSource: true) { _ in false }
        XCTAssertEqual(result, .notAuthorized)
    }

    func testAuthorizationDeniesAnOtherwiseApplicableOperation() {
        let allowed = FeatureApplicability.evaluate(.remoteStart, local: mac, peer: peer(androidPhone), localIsSource: true) { _ in true }
        XCTAssertEqual(allowed, .offered)
        let denied = FeatureApplicability.evaluate(.remoteStart, local: mac, peer: peer(androidPhone), localIsSource: true) { $0 != "remote_screen_share" }
        XCTAssertEqual(denied, .notAuthorized)
        XCTAssertTrue(FeatureApplicability.rule(for: .remoteStart).explicitAuthorization)
        XCTAssertTrue(FeatureApplicability.rule(for: .kvm).explicitAuthorization)
    }

    func testThePeersGrantIsRequiredEvenWithoutALocalKey() {
        // KVM has no key in the macOS catalog: the Android side's opt-in grant still decides.
        let noGrant = peer(androidPhone, capabilities: ["kvm_input": false])
        XCTAssertEqual(FeatureApplicability.evaluate(.kvm, local: mac, peer: noGrant, localIsSource: true) { _ in true }, .peerLacksCapability)
    }

    func testUnknownPlatformOrRoleDoesNotMakeAFeatureAvailable() {
        for feature in Feature.allCases where FeatureApplicability.rule(for: feature).directions != nil {
            XCTAssertFalse(applicable(feature, mac, unknown), "\(feature) to an unknown device")
            XCTAssertFalse(applicable(feature, unknown, mac), "\(feature) from an unknown device")
        }
        let result = FeatureApplicability.evaluate(.webHandoff, local: mac, peer: peer(unknown), localIsSource: false) { _ in true }
        XCTAssertEqual(result, .notApplicable)
        // Deliberately platform-independent features stay usable, still behind capability + grant.
        XCTAssertTrue(applicable(.files, unknown, mac))
        XCTAssertEqual(FeatureApplicability.evaluate(.files, local: mac, peer: peer(unknown), localIsSource: true) { _ in false }, .notAuthorized)
    }

    func testMissingCapabilityIsNotTreatedAsGranted() {
        XCTAssertEqual(FeatureApplicability.evaluate(.clipboard, local: mac, peer: peer(androidPhone, capabilities: [:]), localIsSource: true) { _ in true }, .peerLacksCapability)
        let noUpdateYet = DeviceDirectoryEntry(deviceID: "x", name: "x", isTrusted: true, connection: .connected, capabilities: nil,
                                               platform: .android, kind: .phone, isRouted: false)
        XCTAssertEqual(FeatureApplicability.evaluate(.clipboard, local: mac, peer: noUpdateYet, localIsSource: true) { _ in true }, .peerLacksCapability)
    }

    func testTelemetryNeedsOneMetricThatIsBothGrantedByThePeerAndLocally() {
        let onlyBattery = peer(androidPhone, capabilities: ["battery": true, "cpu": false])
        XCTAssertEqual(FeatureApplicability.evaluate(.telemetry, local: mac, peer: onlyBattery, localIsSource: false) { _ in true }, .offered)
        XCTAssertEqual(FeatureApplicability.evaluate(.telemetry, local: mac, peer: onlyBattery, localIsSource: false) { $0 == "cpu" }, .notAuthorized)
    }

    // MARK: - E. Multi-device isolation

    func testTwoConnectedDevicesGetIndependentResults() {
        let phone = peer(androidPhone, id: "10000000-0000-4000-8000-00000000000a")
        let otherMac = peer(mac, id: "20000000-0000-4000-8000-00000000000b")
        let phoneResult = FeatureApplicability.evaluate(.callControl, local: mac, peer: phone, localIsSource: true) { _ in true }
        let macResult = FeatureApplicability.evaluate(.callControl, local: mac, peer: otherMac, localIsSource: true) { _ in true }
        XCTAssertEqual(phoneResult, .offered)
        XCTAssertEqual(macResult, .notApplicable)

        // Authorization is evaluated for the device asked about, not for a routed/active one.
        let grants: [String: Set<String>] = [phone.deviceID: [], otherMac.deviceID: ["files"]]
        func files(_ entry: DeviceDirectoryEntry) -> FeatureApplicabilityResult {
            FeatureApplicability.evaluate(.files, local: mac, peer: entry, localIsSource: true) { grants[entry.deviceID]?.contains($0) == true }
        }
        XCTAssertEqual(files(phone), .notAuthorized)
        XCTAssertEqual(files(otherMac), .offered)
    }
}
