import XCTest
@testable import BridgeyMac

final class VideoContentGeometryTests: XCTestCase {
    func testFitLetterboxesAPortraitSourceInsideAWiderContainer() {
        let rect = VideoContentGeometry.contentRect(
            sourceSize: CGSize(width: 1080, height: 2340),
            mode: .fit,
            in: CGRect(x: 0, y: 0, width: 1000, height: 1000)
        )
        XCTAssertEqual(rect.height, 1000, accuracy: 0.001)
        XCTAssertLessThan(rect.width, 1000)
        XCTAssertEqual(rect.midX, 500, accuracy: 0.001, "letterboxed horizontally, so centered in the container")
    }

    func testFillCoversTheContainerWithNoLetterboxing() {
        let rect = VideoContentGeometry.contentRect(
            sourceSize: CGSize(width: 1080, height: 2340),
            mode: .fill,
            in: CGRect(x: 0, y: 0, width: 1000, height: 1000)
        )
        XCTAssertGreaterThanOrEqual(rect.width, 1000 - 0.001)
        XCTAssertGreaterThanOrEqual(rect.height, 1000 - 0.001)
    }

    func testMissingSourceSizeReturnsTheContainerBoundsUnchanged() {
        let bounds = CGRect(x: 0, y: 0, width: 480, height: 900)
        XCTAssertEqual(VideoContentGeometry.contentRect(sourceSize: nil, mode: .fit, in: bounds), bounds)
    }

    func testZeroSizedContainerReturnsItselfRatherThanDividingByZero() {
        let bounds = CGRect.zero
        XCTAssertEqual(
            VideoContentGeometry.contentRect(sourceSize: CGSize(width: 1080, height: 2340), mode: .fit, in: bounds),
            bounds
        )
    }

    func testNormalizedPointRoundTripsTheCenterOfAFullyFillingContentRect() throws {
        let bounds = CGRect(x: 0, y: 0, width: 1000, height: 1000)
        let normalized = try XCTUnwrap(VideoContentGeometry.normalizedPoint(
            CGPoint(x: 500, y: 500), sourceSize: CGSize(width: 1000, height: 1000), mode: .fit, in: bounds
        ))
        XCTAssertEqual(Double(normalized.x), 0.5, accuracy: 0.001)
        XCTAssertEqual(Double(normalized.y), 0.5, accuracy: 0.001)
    }

    func testNormalizedPointReturnsNilOutsideTheLetterboxedContentRect() {
        // A portrait source inside a wide, short container leaves horizontal letterbox bars.
        let bounds = CGRect(x: 0, y: 0, width: 2000, height: 500)
        let normalized = VideoContentGeometry.normalizedPoint(
            CGPoint(x: 5, y: 250), sourceSize: CGSize(width: 1080, height: 2340), mode: .fit, in: bounds
        )
        XCTAssertNil(normalized, "a click in the letterbox bar must not map to any phone-screen position")
    }

    func testNormalizedPointMapsTopLeftAndBottomRightCornersOfTheContentRect() throws {
        let bounds = CGRect(x: 0, y: 0, width: 1080, height: 2340)
        let topLeft = try XCTUnwrap(VideoContentGeometry.normalizedPoint(
            CGPoint(x: 0, y: 0), sourceSize: CGSize(width: 1080, height: 2340), mode: .fit, in: bounds
        ))
        let bottomRight = VideoContentGeometry.normalizedPoint(
            CGPoint(x: 1080, y: 2340), sourceSize: CGSize(width: 1080, height: 2340), mode: .fit, in: bounds
        )
        XCTAssertEqual(Double(topLeft.x), 0, accuracy: 0.001)
        XCTAssertEqual(Double(topLeft.y), 0, accuracy: 0.001)
        XCTAssertNil(bottomRight, "the far corner point is exclusive of the rect (matches CGRect.contains)")
    }
}
