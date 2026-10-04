@testable import BridgeyMac
import XCTest

/// BRIDGEY NOTIFICATION++ RECONCILIATION: `notifications.sync` validation, multi-part assembly and
/// the stale-notification selection that drives removal.
final class NotificationSyncTests: XCTestCase {
    private func hexID(_ n: Int) -> String { String(format: "%064x", n) }
    private let phone = "android-device-1"
    private let otherPhone = "android-device-2"

    private func payload(_ ids: [String], syncId: String = "sync-1", part: Int = 1, parts: Int = 1, version: Int = 1) -> NotificationSyncPayload {
        NotificationSyncPayload(version: version, syncId: syncId, part: part, parts: parts, notificationIds: ids)
    }

    private func delivered(_ ids: [Int], device: String) -> [DeliveredRemoteNotification] {
        ids.map { DeliveredRemoteNotification(requestIdentifier: "req-\(device)-\($0)", androidDeviceID: device, androidNotificationID: hexID($0)) }
    }

    // Validation

    func testValidPayloadsIncludingEmptyAndFullParts() {
        XCTAssertTrue(isValidNotificationSyncPayload(payload([])))
        XCTAssertTrue(isValidNotificationSyncPayload(payload([hexID(1)])))
        XCTAssertTrue(isValidNotificationSyncPayload(payload((0..<256).map(hexID))))
    }

    func testRejectsMalformedPayloads() {
        XCTAssertFalse(isValidNotificationSyncPayload(payload((0..<257).map(hexID))))
        XCTAssertFalse(isValidNotificationSyncPayload(payload([hexID(1)], version: 2)))
        XCTAssertFalse(isValidNotificationSyncPayload(payload([], syncId: "")))
        XCTAssertFalse(isValidNotificationSyncPayload(payload([], part: 0)))
        XCTAssertFalse(isValidNotificationSyncPayload(payload([], part: 3, parts: 2)))
        XCTAssertFalse(isValidNotificationSyncPayload(payload([], parts: 33)))
        XCTAssertFalse(isValidNotificationSyncPayload(payload(["not-hex"])))
        XCTAssertFalse(isValidNotificationSyncPayload(payload([hexID(1).uppercased().replacingOccurrences(of: "0", with: "A")])))
        XCTAssertFalse(isValidNotificationSyncPayload(payload([String(hexID(1).dropLast())])))
    }

    // Assembly

    func testSinglePartSyncCompletesImmediately() {
        let assembler = NotificationSyncAssembler()
        XCTAssertEqual(assembler.add(payload([hexID(1)]), deviceID: phone), [hexID(1)])
        XCTAssertEqual(assembler.add(payload([], syncId: "sync-2"), deviceID: phone), [])
    }

    func testMultipartSyncCompletesOnlyWithEveryPartInAnyOrder() {
        let assembler = NotificationSyncAssembler()
        let first = (0..<256).map(hexID), second = (256..<512).map(hexID)
        XCTAssertNil(assembler.add(payload(second, part: 2, parts: 2), deviceID: phone))
        XCTAssertEqual(assembler.add(payload(first, part: 1, parts: 2), deviceID: phone), Set(first + second))
    }

    func testIncompleteSyncYieldsNothingAndIsSupersededByANewerSync() {
        let assembler = NotificationSyncAssembler()
        XCTAssertNil(assembler.add(payload([hexID(1)], syncId: "old", part: 1, parts: 2), deviceID: phone))
        XCTAssertEqual(assembler.add(payload([hexID(2)], syncId: "new"), deviceID: phone), [hexID(2)])
        // The old sync's missing part arriving late must not complete a mixed snapshot.
        XCTAssertNil(assembler.add(payload([hexID(3)], syncId: "old", part: 2, parts: 2), deviceID: phone))
    }

    func testPartsOfDifferentDevicesNeverMix() {
        let assembler = NotificationSyncAssembler()
        XCTAssertNil(assembler.add(payload([hexID(1)], part: 1, parts: 2), deviceID: phone))
        XCTAssertNil(assembler.add(payload([hexID(2)], part: 2, parts: 2), deviceID: otherPhone))
        XCTAssertEqual(assembler.add(payload([hexID(3)], part: 2, parts: 2), deviceID: phone), [hexID(1), hexID(3)])
    }

    // Mac reconciliation selection

    func testSyncOfARemovesBAndC() {
        let stale = staleRemoteNotificationIdentifiers(delivered: delivered([1, 2, 3], device: phone), deviceID: phone, snapshot: [hexID(1)])
        XCTAssertEqual(stale, ["req-\(phone)-2", "req-\(phone)-3"])
    }

    func testEmptySyncRemovesEveryNotificationOfThatDevice() {
        let stale = staleRemoteNotificationIdentifiers(delivered: delivered([1, 2, 3], device: phone), deviceID: phone, snapshot: [])
        XCTAssertEqual(stale.count, 3)
    }

    func testIncompleteSyncRemovesNothing() {
        let assembler = NotificationSyncAssembler()
        let snapshot = assembler.add(payload([hexID(1)], part: 1, parts: 2), deviceID: phone)
        XCTAssertNil(snapshot) // nothing to apply, so no removal is ever computed
    }

    func testOtherDevicesAndNonBridgeyNotificationsAreNeverSelected() {
        let items = delivered([1, 2], device: phone) + delivered([1, 2], device: otherPhone) + [
            DeliveredRemoteNotification(requestIdentifier: "some-local-notification", androidDeviceID: nil, androidNotificationID: nil),
        ]
        let stale = staleRemoteNotificationIdentifiers(delivered: items, deviceID: phone, snapshot: [])
        XCTAssertEqual(stale, ["req-\(phone)-1", "req-\(phone)-2"])
    }

    func testApplyingTheSameSyncTwiceIsHarmless() {
        var items = delivered([1, 2, 3], device: phone)
        let first = staleRemoteNotificationIdentifiers(delivered: items, deviceID: phone, snapshot: [hexID(1)])
        items.removeAll { first.contains($0.requestIdentifier) }
        XCTAssertEqual(staleRemoteNotificationIdentifiers(delivered: items, deviceID: phone, snapshot: [hexID(1)]), [])
    }

    // Silent resync payload decoding

    func testResyncFlagDecodesAndIsOptional() throws {
        let base = #"{"packageName":"p","applicationName":"A","notificationId":"n","title":"t","text":"x","timestamp":1"#
        let plain = try JSONDecoder().decode(RemoteNotificationPayload.self, from: Data((base + "}").utf8))
        let resync = try JSONDecoder().decode(RemoteNotificationPayload.self, from: Data((base + #","resync":true}"#).utf8))
        XCTAssertNil(plain.resync)
        XCTAssertEqual(resync.resync, true)
    }
}
