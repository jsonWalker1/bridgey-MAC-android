import XCTest
@testable import BridgeyMac

final class BridgeySettingsTests: XCTestCase {
    func testGlobalSwitchOverridesDeviceSetting() {
        XCTAssertFalse(effectiveFeatureEnabled(globalEnabled: false, deviceEnabled: true))
        XCTAssertFalse(effectiveFeatureEnabled(globalEnabled: false, deviceEnabled: nil))
    }

    func testDeviceSwitchOverridesEnabledGlobalDefault() {
        XCTAssertFalse(effectiveFeatureEnabled(globalEnabled: true, deviceEnabled: false))
        XCTAssertTrue(effectiveFeatureEnabled(globalEnabled: true, deviceEnabled: true))
        XCTAssertTrue(effectiveFeatureEnabled(globalEnabled: true, deviceEnabled: nil))
    }

    func testFeatureRequiresBothDevicesToOfferIt() {
        XCTAssertTrue(effectiveFeatureAvailable(localEnabled: true, remoteEnabled: true))
        XCTAssertFalse(effectiveFeatureAvailable(localEnabled: true, remoteEnabled: false))
        XCTAssertFalse(effectiveFeatureAvailable(localEnabled: false, remoteEnabled: true))
    }


    func testNewerFeaturesAreOffForLegacyPeers() {
        XCTAssertFalse(featureEnabledByLegacyPeer(.calls))
        XCTAssertFalse(featureEnabledByLegacyPeer(.ping))
        XCTAssertFalse(featureEnabledByLegacyPeer(.photoSync))
        XCTAssertFalse(featureEnabledByLegacyPeer(.remoteScreenShare))
        XCTAssertFalse(featureEnabledByLegacyPeer(.storage))
        XCTAssertFalse(featureEnabledByLegacyPeer(.memory))
        XCTAssertFalse(featureEnabledByLegacyPeer(.cpu))
        XCTAssertFalse(featureEnabledByLegacyPeer(.temperature))
        XCTAssertTrue(featureEnabledByLegacyPeer(.battery))
    }

    /// Each telemetry metric is its own BridgeyFeature case, so toggling one in the settings map
    /// structurally cannot affect the others - there is no shared "telemetry" flag left to entangle them.
    func testTelemetryMetricsAreIndependentEntriesNotOneSharedSwitch() {
        var allOn = Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map { ($0, true) })
        allOn[.storage] = false
        XCTAssertFalse(effectiveFeatureEnabled(globalEnabled: allOn[.storage]!, deviceEnabled: nil))
        XCTAssertTrue(effectiveFeatureEnabled(globalEnabled: allOn[.memory]!, deviceEnabled: nil))
        XCTAssertTrue(effectiveFeatureEnabled(globalEnabled: allOn[.cpu]!, deviceEnabled: nil))
        XCTAssertTrue(effectiveFeatureEnabled(globalEnabled: allOn[.temperature]!, deviceEnabled: nil))
    }

    /// Advanced Screen Continuity - Remote Start must be opt-in (off by default), unlike Bridgey's
    /// usual default-on convenience features: it lets a trusted peer trigger local device behavior.
    @MainActor
    func testRemoteScreenShareDefaultsToDisabled() {
        UserDefaults.standard.removeObject(forKey: "settings.global.remote_screen_share")
        let settings = BridgeySettings()
        XCTAssertFalse(settings.isEnabled(.remoteScreenShare, for: nil))
    }

    @MainActor
    func testSyncIndexTracksAssetKeysPerDirectoryWithoutTouchingOtherDirectories() throws {
        let settings = BridgeySettings()
        let directoryA = FileManager.default.temporaryDirectory
            .appendingPathComponent("bridgey-sync-test-a-\(UUID().uuidString)", isDirectory: true)
        let directoryB = FileManager.default.temporaryDirectory
            .appendingPathComponent("bridgey-sync-test-b-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directoryA, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: directoryB, withIntermediateDirectories: true)
        defer {
            try? FileManager.default.removeItem(at: directoryA)
            try? FileManager.default.removeItem(at: directoryB)
        }

        XCTAssertFalse(settings.isAssetSynced("abc123", directory: directoryA))

        settings.markAssetSynced("abc123", directory: directoryA)

        XCTAssertTrue(settings.isAssetSynced("abc123", directory: directoryA))
        XCTAssertFalse(settings.isAssetSynced("abc123", directory: directoryB))

        settings.markAssetSynced("def456", directory: directoryA)
        XCTAssertTrue(settings.isAssetSynced("abc123", directory: directoryA))
        XCTAssertTrue(settings.isAssetSynced("def456", directory: directoryA))
    }
}
