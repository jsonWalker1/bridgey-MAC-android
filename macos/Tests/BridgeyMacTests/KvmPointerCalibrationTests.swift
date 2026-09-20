import XCTest
@testable import BridgeyMac

final class KvmPointerCalibrationTests: XCTestCase {
    func testAppliesTheFittedTranslationOffset() {
        let result = KvmPointerCalibration.apply(CGPoint(x: 0.5, y: 0.5))
        XCTAssertEqual(Double(result.x), 0.5 + Double(KvmPointerCalibration.offsetX), accuracy: 0.0000001)
        XCTAssertEqual(Double(result.y), 0.5 + Double(KvmPointerCalibration.offsetY), accuracy: 0.0000001)
    }

    func testClampsAtTheLowEdgeRatherThanGoingNegative() {
        let result = KvmPointerCalibration.apply(CGPoint(x: 0, y: 0))
        XCTAssertGreaterThanOrEqual(result.x, 0)
        XCTAssertGreaterThanOrEqual(result.y, 0)
    }

    func testClampsAtTheHighEdgeRatherThanExceedingOne() {
        // offsetX/offsetY are both negative in the fitted model, so the high edge can't actually be
        // exceeded today - this guards the invariant regardless, in case the fitted sign ever changes.
        let result = KvmPointerCalibration.apply(CGPoint(x: 1, y: 1))
        XCTAssertLessThanOrEqual(result.x, 1)
        XCTAssertLessThanOrEqual(result.y, 1)
    }

    func testOffsetMagnitudeIsASmallSubPixelFractionNotAGrossCorrection() {
        // Guards against a future edit accidentally turning this into a large offset: even the
        // larger, live-visually-tuned Y correction (-123px / 3088 ~= 0.0398) is still well under 5%
        // of the coordinate space - a few tens of pixels out of a ~1440x3088 display, not hundreds.
        XCTAssertLessThan(abs(KvmPointerCalibration.offsetX), 0.05)
        XCTAssertLessThan(abs(KvmPointerCalibration.offsetY), 0.05)
    }
}
