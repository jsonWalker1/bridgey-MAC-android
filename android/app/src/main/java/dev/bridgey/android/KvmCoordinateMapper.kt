package dev.bridgey.android

/**
 * KVM PART 3 POC. Pure coordinate math for turning a normalized (0..1) pointer position - the wire
 * representation InputEvent.Pointer already uses (frozen M1 spec) - into a concrete on-screen pixel
 * position, for both KVM operating modes:
 *   - Visual KVM: the Mac derives (nx, ny) from VideoContentGeometry's inverse mapping (a real click
 *     inside the displayed video rectangle).
 *   - Headless KVM: there is no video rectangle, so the Mac instead maintains its own virtual cursor
 *     position (starting at the center, moved by relative mouse deltas) and still sends it as the
 *     same normalized (nx, ny) - the wire protocol does not change between the two modes, only what
 *     the sender derives it from. See KvmInputInjector for where this is consumed.
 * Kept separate from KvmInputInjector/BridgeyAccessibilityService (which depend on Android framework
 * classes not available to plain JUnit) purely so this arithmetic has real unit test coverage.
 */
internal object KvmCoordinateMapper {
    fun toPixels(nx: Float, ny: Float, screenWidthPx: Int, screenHeightPx: Int): Pair<Float, Float> {
        val clampedX = nx.coerceIn(0f, 1f)
        val clampedY = ny.coerceIn(0f, 1f)
        val px = (clampedX * screenWidthPx).coerceIn(0f, (screenWidthPx - 1).coerceAtLeast(0).toFloat())
        val py = (clampedY * screenHeightPx).coerceIn(0f, (screenHeightPx - 1).coerceAtLeast(0).toFloat())
        return px to py
    }
}
