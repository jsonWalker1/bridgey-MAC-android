package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Test

/** KVM PART 3 POC. Coordinate math is the one piece of the injection layer plain JUnit can exercise
 * directly - BridgeyAccessibilityService/KvmCursorOverlay depend on Android framework classes with
 * no Robolectric in this project's test setup (see ScreenCaptureManager for the same precedent: its
 * MediaProjection-dependent logic is likewise verified on-device, not in a unit test). */
class KvmCoordinateMapperTest {
    @Test
    fun centerNormalizedPositionMapsToScreenCenter() {
        val (x, y) = KvmCoordinateMapper.toPixels(0.5f, 0.5f, 1080, 2340)
        assertEquals(540f, x, 0.01f)
        assertEquals(1170f, y, 0.01f)
    }

    @Test
    fun topLeftCornerMapsToZero() {
        val (x, y) = KvmCoordinateMapper.toPixels(0f, 0f, 1080, 2340)
        assertEquals(0f, x, 0.01f)
        assertEquals(0f, y, 0.01f)
    }

    @Test
    fun bottomRightCornerClampsInsideTheScreenBounds() {
        val (x, y) = KvmCoordinateMapper.toPixels(1f, 1f, 1080, 2340)
        assertEquals(1079f, x, 0.01f)
        assertEquals(2339f, y, 0.01f)
    }

    @Test
    fun outOfRangeNormalizedValuesAreClampedRatherThanExtrapolated() {
        val (xLow, yLow) = KvmCoordinateMapper.toPixels(-0.4f, -1f, 1080, 2340)
        assertEquals(0f, xLow, 0.01f)
        assertEquals(0f, yLow, 0.01f)

        val (xHigh, yHigh) = KvmCoordinateMapper.toPixels(1.5f, 2f, 1080, 2340)
        assertEquals(1079f, xHigh, 0.01f)
        assertEquals(2339f, yHigh, 0.01f)
    }
}
