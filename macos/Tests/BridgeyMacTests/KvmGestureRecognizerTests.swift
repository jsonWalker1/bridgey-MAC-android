import XCTest
@testable import BridgeyMac

/// BRIDGEY FINAL MAPPING (2026-09-23): exactly four gestures -
///   2F swipe LEFT -> BACK, 2F swipe RIGHT -> FORWARD, 3F DOWN -> NOTIFICATIONS, 4F DOWN -> RECENTS.
/// All three finger counts use the same plain aggregate-centroid distance+straightness technique - see
/// KvmGestureRecognizer's doc comment for why the earlier anchor+mover 2F design was reverted.
final class KvmGestureRecognizerTests: XCTestCase {
    private var recognizer: KvmGestureRecognizer!
    private var emitted: [GestureAction] = []

    override func setUp() {
        super.setUp()
        recognizer = KvmGestureRecognizer()
        emitted = []
        recognizer.onGesture = { [weak self] action in self?.emitted.append(action) }
    }

    // MARK: - Touch-session helpers

    /// Drives a plain N-finger (2, 3, or 4) aggregate-centroid swipe: all fingers move together from
    /// `from` to `to` in `steps` evenly-spaced increments, then the session ends. Finger identities
    /// don't matter (only the centroid does), so synthetic fixed ids are fine.
    private func simulateGroupSwipe(
        fingerCount: Int,
        from: CGPoint,
        to: CGPoint,
        steps: Int = 5,
        stepInterval: TimeInterval = 0.02,
        startTimestamp: TimeInterval = 1000
    ) {
        for step in 0...steps {
            let t = CGFloat(step) / CGFloat(steps)
            let point = CGPoint(x: from.x + (to.x - from.x) * t, y: from.y + (to.y - from.y) * t)
            let touches = (0..<fingerCount).map { KvmTouchPoint(id: $0, position: point) }
            recognizer.touchesChanged(touches: touches, timestamp: startTimestamp + TimeInterval(step) * stepInterval)
        }
        recognizer.touchesEnded()
    }

    // MARK: - The four supported gestures

    func testTwoFingerSwipeLeftEmitsBack() {
        let d = KvmGestureThresholds.twoFingerMinDistance + 0.1
        simulateGroupSwipe(fingerCount: 2, from: CGPoint(x: 0.6, y: 0.5), to: CGPoint(x: 0.6 - d, y: 0.5))
        XCTAssertEqual(emitted, [.back])
    }

    func testTwoFingerSwipeRightEmitsForward() {
        let d = KvmGestureThresholds.twoFingerMinDistance + 0.1
        simulateGroupSwipe(fingerCount: 2, from: CGPoint(x: 0.4, y: 0.5), to: CGPoint(x: 0.4 + d, y: 0.5))
        XCTAssertEqual(emitted, [.forward])
    }

    func testThreeFingerSwipeDownEmitsNotifications() {
        // NSTouch.normalizedPosition: Y increases upward - a physically-downward swipe is a smaller Y.
        simulateGroupSwipe(fingerCount: 3, from: CGPoint(x: 0.5, y: 0.6), to: CGPoint(x: 0.5, y: 0.2))
        XCTAssertEqual(emitted, [.notifications])
    }

    func testFourFingerSwipeDownEmitsRecents() {
        simulateGroupSwipe(fingerCount: 4, from: CGPoint(x: 0.5, y: 0.6), to: CGPoint(x: 0.5, y: 0.2))
        XCTAssertEqual(emitted, [.recents])
    }

    // MARK: - 2F distance/straightness gate

    func testTwoFingerSwipeBelowMinDistanceEmitsNothing() {
        let small = KvmGestureThresholds.twoFingerMinDistance * 0.3
        simulateGroupSwipe(fingerCount: 2, from: CGPoint(x: 0.6, y: 0.5), to: CGPoint(x: 0.6 - small, y: 0.5))
        XCTAssertTrue(emitted.isEmpty)
    }

    func testTwoFingerSwipeTooDiagonalEmitsNothing() {
        // Roughly 45 degrees - both axes exceed maxPerpendicularDeviation relative to the other, so
        // neither a horizontal nor a vertical gesture can be confidently resolved.
        let d = KvmGestureThresholds.twoFingerMinDistance
        simulateGroupSwipe(fingerCount: 2, from: CGPoint(x: 0.3, y: 0.3), to: CGPoint(x: 0.3 - d, y: 0.3 + d))
        XCTAssertTrue(emitted.isEmpty)
    }

    func testTwoFingerSwipeVerticalEmitsNothing() {
        let d = KvmGestureThresholds.twoFingerMinDistance + 0.1
        simulateGroupSwipe(fingerCount: 2, from: CGPoint(x: 0.5, y: 0.5), to: CGPoint(x: 0.5, y: 0.5 - d))
        XCTAssertTrue(emitted.isEmpty)
    }

    // MARK: - Directions deliberately dropped from the final mapping

    func testThreeFingerSwipeUpEmitsNothingDroppedFromFinalMapping() {
        simulateGroupSwipe(fingerCount: 3, from: CGPoint(x: 0.5, y: 0.2), to: CGPoint(x: 0.5, y: 0.6))
        XCTAssertTrue(emitted.isEmpty)
    }

    func testFourFingerSwipeUpEmitsNothingDroppedFromFinalMapping() {
        simulateGroupSwipe(fingerCount: 4, from: CGPoint(x: 0.5, y: 0.2), to: CGPoint(x: 0.5, y: 0.6))
        XCTAssertTrue(emitted.isEmpty)
    }

    func testThreeFingerSwipeLeftEmitsNothingMovedToTwoFinger() {
        simulateGroupSwipe(fingerCount: 3, from: CGPoint(x: 0.6, y: 0.5), to: CGPoint(x: 0.2, y: 0.5))
        XCTAssertTrue(emitted.isEmpty)
    }

    func testFourFingerSwipeLeftEmitsNothingNotInScope() {
        simulateGroupSwipe(fingerCount: 4, from: CGPoint(x: 0.6, y: 0.5), to: CGPoint(x: 0.2, y: 0.5))
        XCTAssertTrue(emitted.isEmpty)
    }

    // MARK: - Negative: must never fire

    func testOneFingerMovementNeverStartsTracking() {
        let touches0 = [KvmTouchPoint(id: 0, position: CGPoint(x: 0.5, y: 0.2))]
        let touches1 = [KvmTouchPoint(id: 0, position: CGPoint(x: 0.5, y: 0.8))]
        recognizer.touchesChanged(touches: touches0, timestamp: 0)
        recognizer.touchesChanged(touches: touches1, timestamp: 0.1)
        recognizer.touchesEnded()
        XCTAssertTrue(emitted.isEmpty)
        XCTAssertEqual(recognizer.state, .idle)
    }

    func testDisplacementBelowThresholdEmitsNothing() {
        let tiny = KvmGestureThresholds.threeFingerMinDistance * 0.3
        simulateGroupSwipe(fingerCount: 3, from: CGPoint(x: 0.5, y: 0.5), to: CGPoint(x: 0.5, y: 0.5 - tiny))
        XCTAssertTrue(emitted.isEmpty)
    }

    func testTooDiagonalMovementEmitsNothing() {
        // Roughly 45 degrees - both axes exceed maxPerpendicularDeviation relative to the other, so
        // neither a horizontal nor a vertical gesture can be confidently resolved.
        let d = KvmGestureThresholds.threeFingerMinDistance
        simulateGroupSwipe(fingerCount: 3, from: CGPoint(x: 0.3, y: 0.3), to: CGPoint(x: 0.3 + d, y: 0.3 - d))
        XCTAssertTrue(emitted.isEmpty)
    }

    func testFingerCountChangeMidGestureCancels() {
        let touches3 = (0..<3).map { KvmTouchPoint(id: $0, position: CGPoint(x: 0.3, y: 0.5)) }
        recognizer.touchesChanged(touches: touches3, timestamp: 0)
        XCTAssertEqual(recognizer.state, .tracking)
        let touches4 = (0..<4).map { KvmTouchPoint(id: $0, position: CGPoint(x: 0.4, y: 0.5)) }
        recognizer.touchesChanged(touches: touches4, timestamp: 0.02)
        XCTAssertEqual(recognizer.state, .cancelled)
        // Further touches for this now-cancelled session must not resurrect or emit anything.
        recognizer.touchesChanged(touches: touches4, timestamp: 0.04)
        recognizer.touchesEnded()
        XCTAssertTrue(emitted.isEmpty)
    }

    func testExceedingMaxDurationCancels() {
        let touches = (0..<3).map { KvmTouchPoint(id: $0, position: CGPoint(x: 0.6, y: 0.5)) }
        recognizer.touchesChanged(touches: touches, timestamp: 0)
        XCTAssertEqual(recognizer.state, .tracking)
        let laterTouches = (0..<3).map { KvmTouchPoint(id: $0, position: CGPoint(x: 0.2, y: 0.5)) }
        recognizer.touchesChanged(touches: laterTouches, timestamp: KvmGestureThresholds.maxGestureDuration + 0.1)
        XCTAssertEqual(recognizer.state, .cancelled)
        recognizer.touchesEnded()
        XCTAssertTrue(emitted.isEmpty)
    }

    func testTouchesCancelledNeverEmits() {
        let touches = (0..<3).map { KvmTouchPoint(id: $0, position: CGPoint(x: 0.6, y: 0.5)) }
        recognizer.touchesChanged(touches: touches, timestamp: 0)
        let laterTouches = (0..<3).map { KvmTouchPoint(id: $0, position: CGPoint(x: 0.1, y: 0.5)) }
        recognizer.touchesChanged(touches: laterTouches, timestamp: 0.05)
        recognizer.touchesCancelled()
        XCTAssertEqual(recognizer.state, .idle)
        XCTAssertTrue(emitted.isEmpty)
    }

    func testExactlyOneGesturePerTouchSession() {
        simulateGroupSwipe(fingerCount: 3, from: CGPoint(x: 0.5, y: 0.6), to: CGPoint(x: 0.5, y: 0.2))
        XCTAssertEqual(emitted.count, 1)
        // A second, independent session immediately after must produce exactly one more - never zero,
        // never two, and the recognizer must be back in a fresh, usable state.
        simulateGroupSwipe(fingerCount: 3, from: CGPoint(x: 0.5, y: 0.6), to: CGPoint(x: 0.5, y: 0.2))
        XCTAssertEqual(emitted.count, 2)
    }

    func testStateReturnsToIdleAfterASuccessfulGesture() {
        simulateGroupSwipe(fingerCount: 3, from: CGPoint(x: 0.5, y: 0.6), to: CGPoint(x: 0.5, y: 0.2))
        XCTAssertEqual(recognizer.state, .idle)
    }

    func testTouchesEndedWithNoActiveSessionIsANoOp() {
        recognizer.touchesEnded()
        XCTAssertTrue(emitted.isEmpty)
        XCTAssertEqual(recognizer.state, .idle)
    }
}
