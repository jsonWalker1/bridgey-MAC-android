import XCTest
@testable import BridgeyMac

final class MacStorageTests: XCTestCase {
    func testNormalizesValidStorage() {
        XCTAssertEqual(
            normalizedStorageStatus(usedBytes: 512_000_000_000, totalBytes: 1_000_000_000_000),
            LocalStorageStatus(usedBytes: 512_000_000_000, totalBytes: 1_000_000_000_000)
        )
    }

    func testClampsUsedAboveTotal() {
        XCTAssertEqual(
            normalizedStorageStatus(usedBytes: 2_000, totalBytes: 1_000)?.usedBytes,
            1_000
        )
    }

    func testRejectsInvalidTotals() {
        XCTAssertNil(normalizedStorageStatus(usedBytes: 0, totalBytes: 0))
        XCTAssertNil(normalizedStorageStatus(usedBytes: 0, totalBytes: -1))
        XCTAssertNil(normalizedStorageStatus(usedBytes: -1, totalBytes: 1_000))
    }
}
