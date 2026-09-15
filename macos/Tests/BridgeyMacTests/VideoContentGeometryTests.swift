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
}
