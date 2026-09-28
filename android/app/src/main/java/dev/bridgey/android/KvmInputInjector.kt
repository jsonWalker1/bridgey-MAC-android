package dev.bridgey.android

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager

private const val TAG = "KvmInputInjector"
private const val CURSOR_IDLE_HIDE_MS = 5_000L
/** KVM SCROLL SMOOTHNESS FIX (see KVM_MOUSE_V2_PHASE1.md scroll injection report): how long to
 * accumulate SCROLL wheel deltas before dispatching one coalesced gesture. A Mac trackpad/mouse can
 * burst ~80-90 events/s (~11-12ms apart, per this same comment before this fix); this caps gesture
 * dispatch at roughly 1000/SCROLL_COALESCE_WINDOW_MS per second. Was 80ms (batching ~7 raw ticks per
 * segment into 300-600px jumps, with a visible pause between each) - measured/logged during testing
 * here: with the old 80ms value equal to SCROLL_GESTURE_DURATION_MS, the flush's own "is the previous
 * dispatch still in flight" reschedule check would frequently collide with the gesture's own real
 * completion timing (both landing around the same ~80ms mark), forcing an entire EXTRA 80ms wait -
 * measured real inter-dispatch gaps of 165-734ms for an 80ms-declared segment. Lowered to ~1-2 raw
 * ticks per window so segments are small enough to read as continuous motion instead of discrete
 * jumps - see SCROLL_GESTURE_DURATION_MS for the other half of this fix (giving completion a real
 * margin before the next flush is due, so the same race can't recur at the new cadence either). */
private const val SCROLL_COALESCE_WINDOW_MS = 20L
/** KVM SCROLL ZOOM FIX: how long to wait, after the most recent scroll tick, before treating a scroll
 * burst as finished and lifting the continued touch (see BridgeyAccessibilityService.endScrollGesture).
 * Comfortably longer than [SCROLL_COALESCE_WINDOW_MS] so it never fires between two flushes of the
 * same burst, short enough that pausing scrolling promptly releases the touch. */
private const val SCROLL_LIFT_IDLE_MS = 200L
/** KVM SCROLL ZOOM FIX safety net: if a scroll gesture dispatch's completion callback never fires
 * (observed during testing here - dispatchGesture appears to silently never complete when the device
 * is locked), `scrollGestureInFlight` would otherwise stay stuck true forever, permanently disabling
 * ALL future scrolling for the rest of the session (both flushScroll and liftScroll just perpetually
 * reschedule while it's true). Force-clear it once it's been stuck this long. Comfortably longer than
 * any real dispatch should ever take. */
private const val SCROLL_GESTURE_STUCK_TIMEOUT_MS = 2_000L
/** KVM SCROLL SPEED FIX: raw Mac scroll deltas (NSEvent.scrollingDeltaX/Y) are sent through as Mac
 * "points" and were being applied 1:1 as Android drag pixels - measured/felt about 10x too slow on a
 * real device. Multiplies only the raw per-tick delta before it reaches the accumulator; does not
 * change coordinate mapping, the accumulator's own clamp, or the continued-stroke gesture lifecycle at
 * all. A single, clearly-named constant per design intent - tune this one value if 10x ends up feeling
 * too fast/slow in practice, nothing else needs to change. */
private const val SCROLL_SPEED_MULTIPLIER = 20f

/**
 * KVM PART 3 POC. The single hand-off point between the frozen M1 transport (InputEvent arriving
 * already-decrypted, already-sequence-checked, off the dormant "input" TCPInputTransport channel -
 * see VideoChannelManager.onInputEvent) and Android's real input-injection API
 * (BridgeyAccessibilityService.dispatchGesture). Deliberately the ONLY class that knows about both
 * sides, so the transport stays injection-agnostic and the injector stays transport-agnostic -
 * exactly the separation InputTransport.kt's own doc comment called for ("a future
 * InputSessionManager ... is what will bind InputEvent to actual Android injection APIs").
 *
 * Independent of Screen Share / MediaProjection by construction: nothing here touches
 * ScreenCaptureManager, and this is wired unconditionally whenever the input channel is open,
 * whether or not video is also running (see PairingCoordinator). This is what makes Headless KVM
 * (Mode B) the same code path as Visual KVM (Mode A), not a special case.
 */
internal class KvmInputInjector(private val context: Context, videoChannel: VideoChannelManager) {
    private val overlay = KvmCursorOverlay(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var loggedMissingAccessibilityService = false
    private val hideOverlayRunnable = Runnable { overlay.hide() }
    // KVM SCROLL PERFORMANCE FIX: mirrors CursorView's own epsilon-gated redraw (KvmCursorOverlay.kt)
    // one level up, so a burst of same-position events (a SCROLL where the mouse itself isn't
    // moving) doesn't even post the overlay-refresh Runnable, let alone invalidate a view. Read/
    // written only from the TCP reader thread (handlePointer's caller), which is always the same
    // single thread per connection - no synchronization needed.
    private var lastOverlayX = Float.NaN
    private var lastOverlayY = Float.NaN

    // KVM MOUSE V2 PHASE 2 scroll coalescing state. `scrollAccumulator` and `scrollFlushScheduled`
    // are touched from both the TCP reader thread (accumulate, in handleScroll) and the main thread
    // (drain, in flushScroll) - both guarded by `scrollLock`. `scrollGestureInFlight` is main-thread
    // only (set in flushScroll, cleared by the gesture's completion callback), so it needs no lock.
    private val scrollLock = Any()
    private val scrollAccumulator = ScrollGestureAccumulator()
    private var scrollFlushScheduled = false
    private var scrollGestureInFlight = false
    private var scrollGestureInFlightSinceMs = 0L
    private val flushScrollRunnable = Runnable { flushScroll() }
    private val liftScrollRunnable = Runnable { liftScroll() }

    init {
        videoChannel.onInputEvent = { event -> handle(event) }
    }

    private fun handle(event: InputEvent) {
        when (event) {
            is InputEvent.Pointer -> handlePointer(event)
            is InputEvent.Key -> handleKey(event)
            is InputEvent.Text -> handleText(event)
            is InputEvent.Gesture -> handleGesture(event)
        }
    }

    private fun handleKey(key: InputEvent.Key) {
        val service = BridgeyInputMethodService.instance
        if (service == null) {
            Log.w(TAG, "KEY event received but Bridgey's KVM Keyboard is not the active input method")
            return
        }
        mainHandler.post { service.injectKey(key.keyCode, key.action, key.modifiers) }
    }

    /** BRIDGEY KVM TOUCHPAD GESTURES V1. */
    private fun handleGesture(gesture: InputEvent.Gesture) {
        val service = BridgeyAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "GESTURE ${gesture.action} received but Bridgey's Accessibility Service is not " +
                "enabled - enable it in Settings > Accessibility to allow input injection")
            return
        }
        mainHandler.post { service.handleGesture(gesture.action) }
    }

    private fun handleText(text: InputEvent.Text) {
        val service = BridgeyInputMethodService.instance
        if (service == null) {
            Log.w(TAG, "TEXT event received but Bridgey's KVM Keyboard is not the active input method")
            return
        }
        mainHandler.post { service.injectText(text.text) }
    }

    private fun handlePointer(pointer: InputEvent.Pointer) {
        val metrics = context.resources.displayMetrics
        val (px, py) = KvmCoordinateMapper.toPixels(pointer.x, pointer.y, metrics.widthPixels, metrics.heightPixels)

        // KVM SCROLL PERFORMANCE FIX: only touch the overlay (post + Handler bookkeeping) when the
        // position actually moved by more than a negligible amount - a stationary-mouse SCROLL burst
        // (~80-90 events/s) used to post this Runnable and reset the hide-timer on every single tick
        // regardless, which was real Handler/Looper churn on top of the CursorView redraw this same
        // check already prevents one layer down (see KvmCursorOverlay.kt for the epsilon rationale).
        if (lastOverlayX.isNaN() || cursorPositionChanged(lastOverlayX, lastOverlayY, px, py)) {
            lastOverlayX = px
            lastOverlayY = py
            mainHandler.post {
                overlay.show()
                overlay.setPosition(px, py)
                mainHandler.removeCallbacks(hideOverlayRunnable)
                mainHandler.postDelayed(hideOverlayRunnable, CURSOR_IDLE_HIDE_MS)
            }
        }

        // KVM COORDINATE FIX: KvmCoordinateMapper's (px, py) is the position KvmPointerCalibration was
        // live-tuned against, which visually lands correctly ONLY once drawn inside a status-bar/cutout
        // -inset window - exactly what the (unmodified) KvmCursorOverlay above still does natively. But
        // BridgeyAccessibilityService.dispatchGesture always operates in true absolute screen space, so
        // it needs that same real inset added back in here - measured fresh every event (not a fixed
        // constant) so it stays correct across portrait/landscape rotation and different devices. Both
        // axes matter: in landscape a corner/edge camera cutout can produce a LEFT inset, not just top
        // (measured on an S23 Ultra: portrait locationOnScreen=(0,125), landscape=(125,105)).
        val inset = systemInsetPx()
        val gesturePx = px + inset.left
        val gesturePy = py + inset.top

        if (pointer.action == PointerAction.SCROLL) {
            handleScroll(pointer, gesturePx, gesturePy)
            return
        }

        val service = BridgeyAccessibilityService.instance
        if (service == null) {
            if (!loggedMissingAccessibilityService) {
                Log.w(TAG, "KVM input received but Bridgey's Accessibility Service is not enabled - " +
                    "enable it in Settings > Accessibility to allow input injection")
                loggedMissingAccessibilityService = true
            }
            return
        }
        loggedMissingAccessibilityService = false
        mainHandler.post { service.handlePointer(pointer.action, pointer.button, gesturePx, gesturePy) }
    }

    /** left/top of [systemInsetPx] - how far a plain, non-IN_SCREEN TYPE_APPLICATION_OVERLAY window
     * (i.e. exactly what [KvmCursorOverlay] uses) gets placed inward from the true screen origin by
     * WindowManager, on each axis. */
    private data class SystemInset(val left: Float, val top: Float)

    /** KVM COORDINATE FIX: queried fresh on every call (never cached) so a portrait<->landscape
     * rotation - where the inset can differ per axis, move axis, or vanish entirely - is always
     * reflected immediately. No hardcoded per-orientation constant. */
    private fun systemInsetPx(): SystemInset {
        val manager = context.getSystemService(WindowManager::class.java) ?: return SystemInset(0f, 0f)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            val top = if (id > 0) context.resources.getDimensionPixelSize(id).toFloat() else 0f
            return SystemInset(0f, top)
        }
        val insets = manager.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
        return SystemInset(insets.left.toFloat(), insets.top.toFloat())
    }

    /** KVM MOUSE V2 PHASE 2 (see KVM_MOUSE_V2_PHASE1.md scroll injection report). Called on the TCP
     * reader thread for every SCROLL event. Deliberately does the minimum possible work here - a
     * synchronized accumulate, plus at most once per coalescing window a single postDelayed call -
     * so a fast wheel burst never blocks the reader thread on gesture creation/dispatch (that all
     * happens later, on the main thread, in [flushScroll]). */
    private fun handleScroll(pointer: InputEvent.Pointer, px: Float, py: Float) {
        val shouldSchedule: Boolean
        synchronized(scrollLock) {
            // KVM SCROLL SPEED FIX: raw Mac deltas (NSEvent.scrollingDeltaX/Y, sent unscaled per
            // KvmMouseCaptureView.scrollWheel's own doc comment) are in Mac "points" - a much finer
            // unit than this device's physical pixels - and were being applied 1:1 as Android drag
            // distance, which measured/felt about 10x too slow. Scaling the raw per-tick delta right
            // here, before it ever reaches the accumulator, is the only change: coordinate mapping,
            // the accumulator's own clamp/summing logic, and the continued-stroke gesture lifecycle
            // (handleScrollGesture/endScrollGesture) are all completely untouched - a flush still
            // coalesces however many ticks arrived, it just now drags SCROLL_SPEED_MULTIPLIER times
            // further per unit of raw Mac delta. Sign is preserved exactly, so scroll direction is
            // unaffected.
            scrollAccumulator.accumulate(
                pointer.scrollDx * SCROLL_SPEED_MULTIPLIER,
                pointer.scrollDy * SCROLL_SPEED_MULTIPLIER,
                px,
                py,
            )
            shouldSchedule = !scrollFlushScheduled
            if (shouldSchedule) scrollFlushScheduled = true
        }
        if (shouldSchedule) {
            mainHandler.postDelayed(flushScrollRunnable, SCROLL_COALESCE_WINDOW_MS)
        }
        // KVM SCROLL ZOOM FIX: every new tick pushes the lift further out, so the continued touch
        // (see flushScroll/liftScroll) only actually lifts once ticks stop arriving for a real pause -
        // never mid-burst, however long the burst runs.
        mainHandler.removeCallbacks(liftScrollRunnable)
        mainHandler.postDelayed(liftScrollRunnable, SCROLL_LIFT_IDLE_MS)
    }

    /** Main-thread only. Drains whatever scroll delta accumulated during the coalescing window and
     * dispatches AT MOST one Accessibility gesture segment for it - never one per raw wheel tick.
     *
     * KVM SCROLL SMOOTHNESS FIX: deliberately does NOT wait for the previous segment's dispatch to
     * complete before firing this one (measured during testing here: dispatchGesture's own completion
     * callback has a real ~90-100ms floor on this device, regardless of declared duration - gating
     * each flush on it capped the achievable segment rate at roughly 10/s, and any small overrun
     * cascaded into a growing backlog, up to ~1000ms real gaps between dispatches). This now matches
     * BridgeyAccessibilityService.handleLeftButton's MOVE case exactly - an already-proven, already-
     * shipped pattern in this codebase for the same "continued stroke, many segments per second"
     * shape - which also never waits for a segment's completion before dispatching the next one.
     * `scrollGestureInFlight`/`scrollGestureInFlightSinceMs` are still updated here (unchanged), for
     * [liftScroll] - the one place still worth not overlapping, since it's the rare, one-shot terminal
     * action ending the whole burst, not a many-times-per-second continuation. */
    private fun flushScroll() {
        val request = synchronized(scrollLock) {
            scrollFlushScheduled = false
            scrollAccumulator.drain()
        } ?: return

        val service = BridgeyAccessibilityService.instance
        if (service == null) {
            if (!loggedMissingAccessibilityService) {
                Log.w(TAG, "SCROLL received but Bridgey's Accessibility Service is not enabled - " +
                    "enable it in Settings > Accessibility to allow input injection")
                loggedMissingAccessibilityService = true
            }
            return
        }
        loggedMissingAccessibilityService = false
        scrollGestureInFlight = true
        scrollGestureInFlightSinceMs = SystemClock.uptimeMillis()
        service.handleScrollGesture(request) { scrollGestureInFlight = false }
    }

    /** KVM SCROLL ZOOM FIX: main-thread only, fires [SCROLL_LIFT_IDLE_MS] after the last scroll tick
     * with no new one arriving - i.e. the scroll burst has genuinely paused. Lifts the continued touch
     * (see BridgeyAccessibilityService.endScrollGesture) so it doesn't stay pressed forever. Shares
     * `scrollGestureInFlight` with [flushScroll]: if a flush's dispatch is still in flight, reschedules
     * rather than lifting mid-dispatch or racing it. */
    private fun liftScroll() {
        if (scrollGestureStillInFlight(liftScrollRunnable)) return
        val service = BridgeyAccessibilityService.instance ?: return
        scrollGestureInFlight = true
        scrollGestureInFlightSinceMs = SystemClock.uptimeMillis()
        service.endScrollGesture { scrollGestureInFlight = false }
    }

    /** KVM SCROLL ZOOM FIX safety net. Returns true if the caller (flushScroll/liftScroll) should
     * reschedule [rescheduleRunnable] and return without dispatching anything this round. Returns
     * false if the caller should proceed - either nothing was in flight, or an in-flight dispatch was
     * just force-cleared because it had been stuck for [SCROLL_GESTURE_STUCK_TIMEOUT_MS] (see that
     * constant's doc for why this matters: without it, scroll can get silently, permanently stuck). */
    private fun scrollGestureStillInFlight(rescheduleRunnable: Runnable): Boolean {
        if (!scrollGestureInFlight) return false
        if (SystemClock.uptimeMillis() - scrollGestureInFlightSinceMs <= SCROLL_GESTURE_STUCK_TIMEOUT_MS) {
            mainHandler.postDelayed(rescheduleRunnable, SCROLL_COALESCE_WINDOW_MS)
            return true
        }
        Log.w(TAG, "SCROLL gesture dispatch callback never completed after " +
            "${SCROLL_GESTURE_STUCK_TIMEOUT_MS}ms - forcing recovery so scroll isn't stuck disabled")
        scrollGestureInFlight = false
        return false
    }
}
