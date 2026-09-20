import XCTest
@testable import BridgeyMac

final class KvmPointerCalibrationTests: XCTestCase {
    private let portraitSourceSize = CGSize(width: 720, height: 1544)
    private let landscapeSourceSize = CGSize(width: 1544, height: 720)

    func testAppliesThePortraitOffsetForAPortraitSource() {
        let result = KvmPointerCalibration.apply(CGPoint(x: 240, y: 450), sourceSize: portraitSourceSize)
        let offset = KvmPointerCalibration.portraitOffset
        XCTAssertEqual(Double(result.x), 240 + Double(offset.xPt), accuracy: 0.0000001)
        XCTAssertEqual(Double(result.y), 450 + Double(offset.yPt), accuracy: 0.0000001)
    }

    func testAppliesTheLandscapeOffsetForALandscapeSource() {
        let result = KvmPointerCalibration.apply(CGPoint(x: 240, y: 450), sourceSize: landscapeSourceSize)
        let offset = KvmPointerCalibration.landscapeOffset
        XCTAssertEqual(Double(result.x), 240 + Double(offset.xPt), accuracy: 0.0000001)
        XCTAssertEqual(Double(result.y), 450 + Double(offset.yPt), accuracy: 0.0000001)
    }

    func testFallsBackToPortraitForAMissingOrDegenerateSourceSize() {
        XCTAssertEqual(KvmPointerCalibration.offset(for: nil).yPt, KvmPointerCalibration.portraitOffset.yPt)
        XCTAssertEqual(KvmPointerCalibration.offset(for: .zero).yPt, KvmPointerCalibration.portraitOffset.yPt)
    }

    func testLandscapeOffsetIsIndependentOfPortrait() {
        // Landscape and portrait are tuned from entirely separate live sessions - this pins that they
        // don't accidentally get coupled (e.g. one being defined in terms of the other).
        XCTAssertNotEqual(KvmPointerCalibration.landscapeOffset.xPt, KvmPointerCalibration.portraitOffset.xPt)
    }

    func testPortraitOffsetMagnitudeIsASmallPointNudgeNotAGrossCorrection() {
        // Guards against a future edit accidentally turning this into a large offset: the live-tuned
        // correction is tens of points, not hundreds, out of a KVM capture view that's typically a
        // few hundred points wide/tall.
        XCTAssertLessThan(abs(KvmPointerCalibration.portraitOffset.xPt), 50)
        XCTAssertLessThan(abs(KvmPointerCalibration.portraitOffset.yPt), 50)
    }

    func testCorrectionScalesWithContentRectInsteadOfBeingFrozenToOneWindowSize() {
        // Regression test for the resize bug: applying the SAME window-point offset before
        // normalization must produce a DIFFERENT normalized delta at a different content width,
        // because the physical few-point nudge is now a different fraction of the content. (A
        // post-normalization fixed-fraction offset, the old design, would fail this by construction -
        // the normalized delta would be identical regardless of content size.)
        let smallBounds = CGRect(x: 0, y: 0, width: 480, height: 900)
        let largeBounds = CGRect(x: 0, y: 0, width: 960, height: 1800)

        let rawPoint = CGPoint(x: 240, y: 450)
        let calibratedPoint = KvmPointerCalibration.apply(rawPoint, sourceSize: portraitSourceSize)

        let smallNormalized = try? XCTUnwrap(VideoContentGeometry.normalizedPoint(
            calibratedPoint, sourceSize: portraitSourceSize, mode: .fit, in: smallBounds
        ))
        let largeNormalized = try? XCTUnwrap(VideoContentGeometry.normalizedPoint(
            calibratedPoint, sourceSize: portraitSourceSize, mode: .fit, in: largeBounds
        ))
        let smallBaseline = try? XCTUnwrap(VideoContentGeometry.normalizedPoint(
            rawPoint, sourceSize: portraitSourceSize, mode: .fit, in: smallBounds
        ))
        let largeBaseline = try? XCTUnwrap(VideoContentGeometry.normalizedPoint(
            rawPoint, sourceSize: portraitSourceSize, mode: .fit, in: largeBounds
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
