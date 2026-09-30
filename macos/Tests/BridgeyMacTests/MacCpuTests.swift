import XCTest
@testable import BridgeyMac

final class MacCpuTests: XCTestCase {
    func testFirstSampleReturnsNilNotUnavailable() {
        // Distinct from .unavailable: caller should seed silently, not show an error state.
        let current = CpuSample(total: 1000, idle: 800)
        XCTAssertNil(computeCpuPercent(previous: nil, current: current))
    }

    func testIdleSystemReportsLowPercent() {
        let previous = CpuSample(total: 1000, idle: 900)
        let current = CpuSample(total: 2000, idle: 1890)
        // busy = 1000 - 990 = 10 -> 1%
        XCTAssertEqual(computeCpuPercent(previous: previous, current: current), .available(1))
    }

    func testNormalUtilizationComputesExpectedPercent() {
        let previous = CpuSample(total: 1000, idle: 800)
        let current = CpuSample(total: 2000, idle: 1400)
        // totalDelta=1000, idleDelta=600, busy=400 -> 40%
        XCTAssertEqual(computeCpuPercent(previous: previous, current: current), .available(40))
    }

    func testHighUtilizationNearlyPegsCpu() {
        let previous = CpuSample(total: 1000, idle: 800)
        let current = CpuSample(total: 2000, idle: 805)
        // totalDelta=1000, idleDelta=5, busy=995 -> rounds to 99 or 100
        guard case .available(let percent) = computeCpuPercent(previous: previous, current: current) else {
            return XCTFail("expected .available")
        }
        XCTAssertTrue((99...100).contains(percent))
    }

    func testZeroElapsedTimeIsUnavailable() {
        let sample = CpuSample(total: 1000, idle: 800)
        XCTAssertEqual(computeCpuPercent(previous: sample, current: sample), .unavailable)
    }

    func testCounterRolloverOrResetIsUnavailable() {
        let previous = CpuSample(total: 5000, idle: 4000)
        let current = CpuSample(total: 100, idle: 50) // counters went backwards
        XCTAssertEqual(computeCpuPercent(previous: previous, current: current), .unavailable)
    }

    func testIdleDeltaExceedingTotalDeltaIsUnavailable() {
        let previous = CpuSample(total: 1000, idle: 100)
        let current = CpuSample(total: 1100, idle: 1000)
        XCTAssertEqual(computeCpuPercent(previous: previous, current: current), .unavailable)
    }

    func testPercentIsClampedTo0To100() {
        let previous = CpuSample(total: 1000, idle: 1000)
        let current = CpuSample(total: 2000, idle: 1000)
        // totalDelta=1000, idleDelta=0, busy=1000 -> exactly 100%, must not exceed
        XCTAssertEqual(computeCpuPercent(previous: previous, current: current), .available(100))
    }

    func testCurrentMacCpuSampleIsPlausible() {
        // Smoke test against the real host - can't assert exact values, but a successful read must
        // be internally consistent (or nil on genuine host_statistics failure).
        guard let sample = currentMacCpuSample() else { return }
        XCTAssertGreaterThan(sample.total, 0)
        XCTAssertLessThanOrEqual(sample.idle, sample.total)
    }
}
