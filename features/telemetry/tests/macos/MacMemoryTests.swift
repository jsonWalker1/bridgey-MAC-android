import XCTest
@testable import BridgeyMac

final class MacMemoryTests: XCTestCase {
    func testNormalizesValidMemory() {
        XCTAssertEqual(
            normalizedMemoryStatus(usedBytes: 6_000_000_000, totalBytes: 12_000_000_000),
            LocalMemoryStatus(usedBytes: 6_000_000_000, totalBytes: 12_000_000_000)
        )
    }

    func testClampsUsedAboveTotal() {
        XCTAssertEqual(
            normalizedMemoryStatus(usedBytes: 2_000, totalBytes: 1_000)?.usedBytes,
            1_000
        )
    }

    func testRejectsInvalidTotals() {
        XCTAssertNil(normalizedMemoryStatus(usedBytes: 0, totalBytes: 0))
        XCTAssertNil(normalizedMemoryStatus(usedBytes: 0, totalBytes: -1))
        XCTAssertNil(normalizedMemoryStatus(usedBytes: -1, totalBytes: 1_000))
    }

    func testCurrentMacMemoryStatusIsPlausible() {
        // Smoke test against the real host - can't assert exact values, but the sampler must
        // return an internally-consistent reading (or nil on genuine host_statistics64 failure).
        guard let status = currentMacMemoryStatus() else { return }
        XCTAssertGreaterThan(status.totalBytes, 0)
        XCTAssertLessThanOrEqual(status.usedBytes, status.totalBytes)
        XCTAssertGreaterThanOrEqual(status.usedBytes, 0)
    }
}
