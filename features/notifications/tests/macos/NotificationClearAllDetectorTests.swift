@testable import BridgeyMac
import XCTest

/// BRIDGEY NOTIFICATION++ macOS CLEAR ALL: the inference state machine (docs/protocol.md,
/// `notifications.dismissMany`).
final class NotificationClearAllDetectorTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_000_000)
    private func at(_ seconds: TimeInterval) -> Date { t0.addingTimeInterval(seconds) }

    private func stack(_ indexes: [Int], device: String = "phone") -> [String: ClearAllSnapshotEntry] {
        Dictionary(uniqueKeysWithValues: indexes.map { ("req-\(device)-\($0)", ClearAllSnapshotEntry(deviceID: device, notificationID: "id-\($0)")) })
    }

    private func observe(_ detector: inout NotificationClearAllDetector, _ current: [String: ClearAllSnapshotEntry],
                         at seconds: TimeInterval, active: Bool = true, settling: Bool = false) -> NotificationClearAllDetector.Action {
        detector.observe(current: current, active: active, settling: settling, now: at(seconds))
    }

    private func seeded(_ indexes: [Int]) -> NotificationClearAllDetector {
        var detector = NotificationClearAllDetector()
        XCTAssertEqual(observe(&detector, stack(indexes), at: 0), .update)
        return detector
    }

    // Single dismiss

    func testSingleDismissNeverTriggers() {
        var detector = seeded([1, 2, 3, 4, 5])
        detector.noteExplainedRemoval(["req-phone-5"], at: at(1))
        XCTAssertEqual(observe(&detector, stack([1, 2, 3, 4]), at: 10), .update)
        XCTAssertEqual(observe(&detector, stack([1, 2, 3, 4]), at: 20), .update)
    }

    func testSingleDismissOfTheLastNotificationIsExplained() {
        var detector = seeded([1])
        detector.noteExplainedRemoval(["req-phone-1"], at: at(1))
        XCTAssertEqual(observe(&detector, [:], at: 10), .explained)
    }

    // Mac Clear All

    func testClearAllDismissesTheLastSnapshotAfterTwoConsecutiveZeros() {
        var detector = seeded([1, 2, 3, 4, 5, 6, 7])
        XCTAssertEqual(observe(&detector, [:], at: 10), .zeroPending)
        guard case .dismissMany(let entries) = observe(&detector, [:], at: 20) else { return XCTFail("expected dismissMany") }
        XCTAssertEqual(entries.map(\.notificationID).sorted(), (1...7).map { "id-\($0)" }.sorted())
        XCTAssertEqual(observe(&detector, [:], at: 30), .update, "one clear, one dispatch")
    }

    func testATransientZeroDoesNotTrigger() {
        var detector = seeded([1, 2, 3])
        XCTAssertEqual(observe(&detector, [:], at: 10), .zeroPending)
        XCTAssertEqual(observe(&detector, stack([1, 2, 3]), at: 20), .update)
        XCTAssertEqual(observe(&detector, [:], at: 30), .zeroPending)
    }

    func testClearAllExcludesAlreadyExplainedIds() {
        var detector = seeded([1, 2, 3])
        detector.noteExplainedRemoval(["req-phone-2"], at: at(1))
        _ = observe(&detector, [:], at: 10)
        guard case .dismissMany(let entries) = observe(&detector, [:], at: 20) else { return XCTFail("expected dismissMany") }
        XCTAssertEqual(entries.map(\.notificationID), ["id-1", "id-3"])
    }

    func testMacEvictionBeyondTheLimitNeverLooksLikeAClear() {
        var detector = seeded(Array(0..<100))
        XCTAssertEqual(observe(&detector, stack(Array(50..<150)), at: 10), .update)
        XCTAssertEqual(observe(&detector, stack(Array(50..<150)), at: 20), .update)
    }

    // Android Clear All (no echo)

    func testAndroidClearAllIsExplainedAndNeverEchoed() {
        var detector = seeded([1, 2, 3, 4, 5])
        detector.noteExplainedRemoval((1...5).map { "req-phone-\($0)" }, at: at(2))
        XCTAssertEqual(observe(&detector, [:], at: 10), .explained)
        XCTAssertEqual(observe(&detector, [:], at: 20), .update)
    }

    func testExplanationsExpire() {
        var detector = seeded([1])
        detector.noteExplainedRemoval(["req-phone-1"], at: at(0))
        _ = observe(&detector, stack([1]), at: 200)
        XCTAssertEqual(observe(&detector, [:], at: 210), .zeroPending)
    }

    // Reconnect / posts / permissions

    func testSettlingNeverEvaluatesAndKeepsTheSnapshot() {
        var detector = seeded([1, 2, 3])
        XCTAssertEqual(observe(&detector, [:], at: 10, settling: true), .settling)
        XCTAssertEqual(observe(&detector, [:], at: 20, settling: true), .settling)
        XCTAssertEqual(detector.snapshot.count, 3)
        XCTAssertEqual(observe(&detector, stack([1, 2, 3, 4]), at: 70), .update)
    }

    func testResetMeansTheFirstZeroAfterReconnectIsNotAClear() {
        var detector = seeded([1, 2, 3])
        detector.reset()
        XCTAssertEqual(observe(&detector, [:], at: 10), .update)
    }

    func testInactiveClearsStateSoPermissionLossOrSyncOffNeverDispatches() {
        var detector = seeded([1, 2, 3])
        XCTAssertEqual(observe(&detector, [:], at: 10, active: false), .inactive)
        XCTAssertTrue(detector.snapshot.isEmpty)
        XCTAssertEqual(observe(&detector, [:], at: 20), .update)
        XCTAssertEqual(observe(&detector, [:], at: 30), .update)
    }

    // Message parts

    func testDismissManyIsSplitIntoPartsOfAtMost256() {
        let ids = (0..<600).map { String(format: "%064x", $0) }
        XCTAssertEqual(notificationDismissManyParts(ids).map(\.count), [256, 256, 88])
        XCTAssertEqual(notificationDismissManyParts(ids).flatMap { $0 }, ids)
        XCTAssertEqual(notificationDismissManyParts([]).count, 0)
    }
}
