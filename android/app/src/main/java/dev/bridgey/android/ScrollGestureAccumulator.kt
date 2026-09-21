package dev.bridgey.android

/** Default cap on how far a single coalesced scroll gesture can drag, in pixels. Conservative on
 * purpose (see KVM_MOUSE_V2_PHASE1.md phase 2 report): this exists only to keep a pathological
 * accumulated delta (e.g. a very long coalescing window, or a malformed/huge event) from producing
 * an absurd off-screen drag - it is not a tuned "feels right" distance, since no aggressive
 * multiplier/scaling was applied to begin with (1 wheel-delta unit = 1 px, unscaled). */
internal const val DEFAULT_MAX_SCROLL_GESTURE_DISTANCE_PX = 600f

/** KVM MOUSE V2 PHASE 2 (see KVM_MOUSE_V2_PHASE1.md scroll injection report). Pure,
 * Android-framework-free accumulator that coalesces rapid SCROLL wheel deltas (a Mac trackpad/mouse
 * can burst ~80-90 events/s) into a single pending request, so the caller can dispatch ONE
 * Accessibility gesture per coalescing window instead of one per wheel tick. Not thread-safe by
 * itself - the caller (KvmInputInjector) is responsible for synchronizing access, since it's shared
 * between the TCP reader thread (accumulate) and the main thread (drain).
 */
internal class ScrollGestureAccumulator(private val maxDistancePx: Float = DEFAULT_MAX_SCROLL_GESTURE_DISTANCE_PX) {
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var hasPending = false
    private var lastX = 0f
    private var lastY = 0f

    /** Adds a new wheel delta to whatever is already pending, and remembers (x, y) as the most
     * recent pointer position - the place a coalesced gesture should originate from (requirement:
     * scroll must happen at the pointer's position, not some earlier/stale one). */
    fun accumulate(dx: Float, dy: Float, x: Float, y: Float) {
        pendingDx += dx
        pendingDy += dy
        lastX = x
        lastY = y
        hasPending = true
    }

    /** Drains and clears whatever is pending. Returns null if nothing was accumulated, or if the
     * accumulated delta nets out to exactly zero (e.g. equal and opposite deltas coalesced together)
     * - a zero-distance gesture would be a pointless dispatch. The returned request's dx/dy are
     * clamped to +/-[maxDistancePx] each; sign is preserved exactly (no inversion). */
    fun drain(): ScrollGestureRequest? {
        if (!hasPending) return null
        val dx = pendingDx
        val dy = pendingDy
        pendingDx = 0f
        pendingDy = 0f
        hasPending = false
        if (dx == 0f && dy == 0f) return null
        return ScrollGestureRequest(
            x = lastX,
            y = lastY,
            dx = dx.coerceIn(-maxDistancePx, maxDistancePx),
            dy = dy.coerceIn(-maxDistancePx, maxDistancePx),
        )
    }
}

/** A single coalesced scroll gesture to dispatch: drag from (x, y) to (x + dx, y + dy). This is the
 * "content follows touch" model - a positive dy drags content down by dy pixels, matching the same
 * "moving the content, not the scrollbar" convention scrollingDeltaY is already documented to use on
 * the Mac side (see KvmMouseCaptureView.scrollWheel's doc comment) - no additional sign inversion. */
internal data class ScrollGestureRequest(val x: Float, val y: Float, val dx: Float, val dy: Float) {
    val startX: Float get() = x
    val startY: Float get() = y
    val endX: Float get() = x + dx
    val endY: Float get() = y + dy
}
