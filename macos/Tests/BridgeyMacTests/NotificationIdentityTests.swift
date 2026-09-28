import XCTest
@testable import BridgeyMac

final class NotificationIdentityTests: XCTestCase {
    func testIdentifierIsDeterministicAndNamespacedByDevice() {
        let first = remoteNotificationRequestIdentifier(deviceID: "phone-a", notificationID: "notification-1")
        XCTAssertEqual(first, remoteNotificationRequestIdentifier(deviceID: "phone-a", notificationID: "notification-1"))
        XCTAssertNotEqual(first, remoteNotificationRequestIdentifier(deviceID: "phone-b", notificationID: "notification-1"))
        XCTAssertTrue(first.hasPrefix("bridgey.android."))
    }

    func testActionCategoryDependsOnScopedTokens() {
        let first = remoteNotificationCategoryIdentifier(
            deviceID: "phone-a",
            notificationID: "notification-1",
            actionTokens: ["action-a"]
        )
        XCTAssertEqual(first, remoteNotificationCategoryIdentifier(
            deviceID: "phone-a",
            notificationID: "notification-1",
            actionTokens: ["action-a"]
        ))
        XCTAssertNotEqual(first, remoteNotificationCategoryIdentifier(
            deviceID: "phone-a",
            notificationID: "notification-1",
            actionTokens: ["action-b"]
        ))
    }

    func testNotificationIconAcceptsOnlyBoundedPNGData() {
        let png = Data([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3])
        XCTAssertEqual(remoteNotificationIconData(png.base64EncodedString()), png)
        XCTAssertNil(remoteNotificationIconData(Data("not png".utf8).base64EncodedString()))
        XCTAssertNil(remoteNotificationIconData(Data(repeating: 0, count: maximumRemoteNotificationIconBytes + 1).base64EncodedString()))
    }

    func testNotificationIconFileNameIsContentAddressed() {
        let first = remoteNotificationIconFileName(packageName: "org.example", data: Data([1]))
        XCTAssertEqual(first, remoteNotificationIconFileName(packageName: "org.example", data: Data([1])))
        XCTAssertNotEqual(first, remoteNotificationIconFileName(packageName: "org.example", data: Data([2])))
        XCTAssertTrue(first.hasSuffix(".png"))
    }

    // BRIDGEY NOTIFICATION++ SOUND POLISH

    func testAudibleNotificationWithNoPriorHistoryPlaysSound() {
        XCTAssertTrue(shouldPlayNotificationSound(hasSound: true, timestamp: 1000, lastPlayedTimestamp: nil))
    }

    func testSilentNotificationNeverPlaysSoundRegardlessOfHistory() {
        XCTAssertFalse(shouldPlayNotificationSound(hasSound: false, timestamp: 1000, lastPlayedTimestamp: nil))
        XCTAssertFalse(shouldPlayNotificationSound(hasSound: false, timestamp: 2000, lastPlayedTimestamp: 1000))
    }

    func testMissingHasSoundFieldDefaultsToAudibleForBackwardCompatibility() {
        XCTAssertTrue(shouldPlayNotificationSound(hasSound: nil, timestamp: 1000, lastPlayedTimestamp: nil))
    }

    func testReplayOfTheSameTimestampDoesNotReplaySound() {
        // Covers both resync-on-reconnect and rapid duplicate posts of the same message.
        XCTAssertFalse(shouldPlayNotificationSound(hasSound: true, timestamp: 1000, lastPlayedTimestamp: 1000))
    }

    func testGenuinelyNewerMessageInAnAlreadyDeliveredConversationStillPlaysSound() {
        XCTAssertTrue(shouldPlayNotificationSound(hasSound: true, timestamp: 2000, lastPlayedTimestamp: 1000))
    }

    func testOutOfOrderOrStaleTimestampDoesNotReplaySound() {
        XCTAssertFalse(shouldPlayNotificationSound(hasSound: true, timestamp: 999, lastPlayedTimestamp: 1000))
    }
}
