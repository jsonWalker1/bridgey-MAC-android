import XCTest
@testable import BridgeyMac

final class KvmPointerCalibrationTests: XCTestCase {
    func testAppliesTheFittedWindowPointOffset() {
        let result = KvmPointerCalibration.apply(CGPoint(x: 240, y: 450))
        XCTAssertEqual(Double(result.x), 240 + Double(KvmPointerCalibration.offsetXPt), accuracy: 0.0000001)
        XCTAssertEqual(Double(result.y), 450 + Double(KvmPointerCalibration.offsetYPt), accuracy: 0.0000001)
    }

    func testOffsetMagnitudeIsASmallPointNudgeNotAGrossCorrection() {
        // Guards against a future edit accidentally turning this into a large offset: the live-tuned
        // correction is tens of points, not hundreds, out of a KVM capture view that's typically a
        // few hundred points wide/tall.
        XCTAssertLessThan(abs(KvmPointerCalibration.offsetXPt), 50)
        XCTAssertLessThan(abs(KvmPointerCalibration.offsetYPt), 50)
    }

    func testCorrectionScalesWithContentRectInsteadOfBeingFrozenToOneWindowSize() {
        // This is the regression test for the resize bug: applying the SAME window-point offset
        // before normalization must produce a DIFFERENT normalized delta at a different content
        // width, because the physical few-point nudge is now a different fraction of the content.
        // (A post-normalization fixed-fraction offset, the old design, would fail this by construction
        // - the normalized delta would be identical regardless of content size.)
        let smallBounds = CGRect(x: 0, y: 0, width: 480, height: 900)
        let largeBounds = CGRect(x: 0, y: 0, width: 960, height: 1800)
        let sourceSize = CGSize(width: 720, height: 1544)

        let rawPoint = CGPoint(x: 240, y: 450)
        let calibratedPoint = KvmPointerCalibration.apply(rawPoint)

        let smallNormalized = try? XCTUnwrap(VideoContentGeometry.normalizedPoint(
            calibratedPoint, sourceSize: sourceSize, mode: .fit, in: smallBounds
        ))
        let largeNormalized = try? XCTUnwrap(VideoContentGeometry.normalizedPoint(
            calibratedPoint, sourceSize: sourceSize, mode: .fit, in: largeBounds
        ))
        let smallBaseline = try? XCTUnwrap(VideoContentGeometry.normalizedPoint(
            rawPoint, sourceSize: sourceSize, mode: .fit, in: smallBounds
        ))
        let largeBaseline = try? XCTUnwrap(VideoContentGeometry.normalizedPoint(
            rawPoint, sourceSize: sourceSize, mode: .fit, in: largeBounds
        ))

        guard let smallNormalized, let largeNormalized, let smallBaseline, let largeBaseline else {
            XCTFail("expected all four points to fall inside their content rects")
            return
        }

        let smallDelta = Double(smallNormalized.y - smallBaseline.y)
        let largeDelta = Double(largeNormalized.y - largeBaseline.y)

        XCTAssertNotEqual(smallDelta, largeDelta, accuracy: 0.0000001)
        // The larger window has double the content height, so the same physical point offset should
        // produce roughly half the normalized delta.
        XCTAssertEqual(smallDelta / largeDelta, 2.0, accuracy: 0.05)
    }
}
