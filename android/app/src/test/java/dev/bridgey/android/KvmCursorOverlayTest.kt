package dev.bridgey.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** KVM SCROLL PERFORMANCE FIX (see KVM_MOUSE_V2_PHASE1.md scroll performance root-cause report):
 * [cursorPositionChanged] is what CursorView.setPosition now gates its invalidate() call on, so a
 * high-frequency SCROLL burst reporting the same on-screen point (the mouse itself isn't moving
 * during a scroll) stops forcing a fullscreen overlay redraw on every single tick. */
class KvmCursorOverlayTest {
    @Test fun identicalPositionDoesNotCountAsChanged() {
        assertFalse(cursorPositionChanged(100f, 200f, 100f, 200f))
    }

    @Test fun negligibleDifferenceBelowEpsilonDoesNotCountAsChanged() {
        assertFalse(cursorPositionChanged(100f, 200f, 100.1f, 200f))
        assertFalse(cursorPositionChanged(100f, 200f, 100f, 200.2f))
        assertFalse(cursorPositionChanged(100f, 200f, 100.49f, 199.51f))
    }

    @Test fun exactlyAtTheEpsilonBoundaryDoesNotCountAsChanged() {
        // The comparison is strictly-greater-than the epsilon, so a delta of exactly the epsilon
        // itself must still be treated as unchanged.
        assertFalse(cursorPositionChanged(100f, 200f, 100.5f, 200f))
        assertFalse(cursorPositionChanged(100f, 200f, 100f, 200.5f))
    }

    @Test fun genuinelyChangedPositionCountsAsChanged() {
        assertTrue(cursorPositionChanged(100f, 200f, 105f, 200f))
        assertTrue(cursorPositionChanged(100f, 200f, 100f, 210f))
        assertTrue(cursorPositionChanged(100f, 200f, 90f, 190f))
    }

    @Test fun justOverTheEpsilonOnEitherAxisCountsAsChanged() {
        assertTrue(cursorPositionChanged(100f, 200f, 100.51f, 200f))
        assertTrue(cursorPositionChanged(100f, 200f, 100f, 199.49f))
    }

    @Test fun negativeMovementIsDetectedTheSameAsPositiveMovement() {
        assertTrue(cursorPositionChanged(100f, 200f, 50f, 200f))
        assertTrue(cursorPositionChanged(100f, 200f, 100f, 150f))
    }

    @Test fun theInitialOffscreenSentinelPositionCountsAsChangedOnFirstRealPosition() {
        // CursorView starts at (-1, -1) before any real pointer event arrives - the very first
        // setPosition call must still redraw so the cursor actually appears.
        assertTrue(cursorPositionChanged(-1f, -1f, 500f, 800f))
    }
}
