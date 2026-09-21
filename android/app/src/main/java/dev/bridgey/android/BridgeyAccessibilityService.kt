package dev.bridgey.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent

private const val TAG = "BridgeyA11y"

/**
 * KVM PART 3 POC. The Android-side input-INJECTION layer (as distinct from InputTransport, which
 * only carries the already-authenticated, already-decrypted InputEvent off the wire - see
 * KvmInputInjector for that hand-off). This is a real, user-enabled AccessibilityService using its
 * sanctioned `dispatchGesture` API - the same mechanism Play-Store-approved remote-support apps
 * (TeamViewer, AnyDesk QuickSupport) use, not an undocumented trick. It requires the user to
 * explicitly enable "Bridgey" under Settings > Accessibility (a real, revocable, one-time consent
 * step) and is completely independent of MediaProjection/Screen Share - see BridgeyApplication /
 * PairingCoordinator for how the KVM_INPUT feature and Screen Share are wired as two unrelated
 * on/off switches.
 *
 * Streaming touch is done via the standard "continued stroke" pattern: DOWN starts a
 * StrokeDescription with willContinue=true; each MOVE calls continueStroke(...) on the previous
 * stroke with the incremental path segment; UP calls continueStroke(..., willContinue=false) to end
 * it. A DOWN immediately followed by an UP at the same point (no intervening MOVE) naturally
 * dispatches as a plain tap - this is the smallest event the KVM POC proves end to end.
 *
 * Only pointer (touch) injection is implemented in this POC. Keyboard injection deliberately has no
 * AccessibilityService equivalent (dispatchGesture is touch-only) - see the Part 3 final report for
 * why a future InputMethodService is the correct next step for KEY/TEXT events, not attempted here.
 */
class BridgeyAccessibilityService : AccessibilityService() {
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var strokeStartUptimeMs = 0L
    private var lastX = 0f
    private var lastY = 0f
    // KVM LMB DRAG SCROLL FIX: raw (unamplified) last incoming position for LEFT-button drag, used
    // only to compute the next raw delta (accumulateLmbDelta) - independent of lastX/lastY, which
    // track the AMPLIFIED touch position actually being dragged and diverge from the raw Mac cursor
    // position once any amplification has been applied.
    private var lastRawX = 0f
    private var lastRawY = 0f
    // KVM LMB REALTIME FIX: coalescing state for LEFT-button drag, mirroring the same "accumulate raw
    // ticks over a short window, dispatch ONE segment per window" shape KvmInputInjector's wheel-scroll
    // pipeline already uses (see that file's SCROLL_COALESCE_WINDOW_MS) - kept entirely local to this
    // service/this button rather than routing through wheel's OWN scrollStroke, since running two
    // independent continued strokes at once would be a real multi-touch collision, not just a repeat
    // of the double-tap issue wheel's own fix already solved. Main-thread only (posted here via
    // lmbHandler, same as KvmInputInjector's mainHandler) - no synchronization needed.
    private var pendingLmbDx = 0f
    private var pendingLmbDy = 0f
    private var lmbFlushScheduled = false
    private val lmbHandler = Handler(Looper.getMainLooper())
    private val lmbFlushRunnable = Runnable { flushLmbDrag() }
    private var rightButtonDownX = 0f
    private var rightButtonDownY = 0f
    // KVM SCROLL ZOOM FIX: state for the single continued stroke a whole scroll burst is dispatched
    // as (see handleScrollGesture/endScrollGesture) - independent of currentStroke (LEFT button drag),
    // since scroll and a left-button drag are conceptually different touch streams.
    private var scrollStroke: GestureDescription.StrokeDescription? = null
    private var scrollStrokeStartUptimeMs = 0L
    private var scrollCurrentX = 0f
    private var scrollCurrentY = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "KVM accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(TAG, "KVM accessibility service disabled by user")
        instance = null
        currentStroke = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        currentStroke = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Gesture-dispatch-only service: no content inspection, nothing to react to here.
    }

    override fun onInterrupt() {}

    /** KVM Mouse V2 (see KVM_MOUSE_V2_PHASE1.md): [button] selects which Android-side gesture
     * strategy applies. LEFT is byte-for-byte Mouse v1 behavior. SCROLL is never routed here -
     * KvmInputInjector intercepts it before this call, since it isn't a button gesture at all. */
    internal fun handlePointer(action: PointerAction, button: PointerButton, xPx: Float, yPx: Float) {
        when (button) {
            PointerButton.LEFT -> handleLeftButton(action, xPx, yPx)
            PointerButton.RIGHT -> handleRightButtonAsLongPress(action, xPx, yPx)
            PointerButton.MIDDLE -> {
                // Transport/model support only (Mouse V2 phase 1): no Android touch gesture has a
                // meaningful middle-button equivalent yet, so this deliberately no-ops rather than
                // guessing at one - exactly how Mouse v1 already no-op'd SCROLL.
                Log.i(TAG, "MIDDLE button $action received at ($xPx, $yPx) - not implemented (Mouse V2 phase 1: model only)")
            }
        }
    }

    private fun handleLeftButton(action: PointerAction, xPx: Float, yPx: Float) {
        when (action) {
            PointerAction.DOWN -> {
                val path = Path().apply { moveTo(xPx, yPx) }
                strokeStartUptimeMs = SystemClock.uptimeMillis()
                lastX = xPx
                lastY = yPx
                lastRawX = xPx
                lastRawY = yPx
                pendingLmbDx = 0f
                pendingLmbDy = 0f
                val stroke = GestureDescription.StrokeDescription(path, 0, STROKE_SEGMENT_MS, true)
                currentStroke = stroke
                dispatch(stroke)
            }
            PointerAction.MOVE -> {
                // KVM LMB REALTIME FIX: root cause of the "nothing happens until release" bug was
                // dispatching a NEW continueStroke() on every single raw MOVE tick (which can arrive
                // every ~1-8ms for a real mouse) while declaring EACH one a full STROKE_SEGMENT_MS
                // (60ms) animation - each new dispatch pre-empts the previous one's not-yet-finished
                // 60ms window long before it can visually progress, so the touch barely moves until
                // ticks stop arriving (i.e. near UP), at which point the last segment finally gets to
                // run its full course - a sudden jump. Fix: accumulate raw ticks here (same shape as
                // KvmInputInjector's wheel-scroll coalescing) and only actually dispatch on a timer via
                // flushLmbDrag - never more than once per LMB_DRAG_COALESCE_WINDOW_MS, matching a
                // segment duration short enough to actually complete before the next flush is due.
                if (currentStroke == null) return
                accumulateLmbDelta(xPx, yPx)
                if (!lmbFlushScheduled) {
                    lmbFlushScheduled = true
                    lmbHandler.postDelayed(lmbFlushRunnable, LMB_DRAG_COALESCE_WINDOW_MS)
                }
            }
            PointerAction.UP -> {
                val previous = currentStroke
                lmbHandler.removeCallbacks(lmbFlushRunnable)
                lmbFlushScheduled = false
                val elapsed = (SystemClock.uptimeMillis() - strokeStartUptimeMs).coerceAtLeast(0)
                currentStroke = null
                if (previous == null) {
                    // An UP with no matching DOWN (e.g. the channel opened mid-gesture) - dispatch a
                    // standalone tap rather than silently dropping it. No active drag to amplify, so
                    // this uses the raw incoming position directly - correct for a genuine tap.
                    pendingLmbDx = 0f
                    pendingLmbDy = 0f
                    val tapPath = Path().apply { moveTo(xPx, yPx) }
                    dispatch(GestureDescription.StrokeDescription(tapPath, 0, STROKE_SEGMENT_MS, false))
                } else {
                    // Folds this UP event's own raw delta into whatever coalesced movement was still
                    // pending, so a release doesn't drop the last few ms of drag. For a plain click (no
                    // intervening MOVE, nothing pending), this nets to ~0 delta regardless of the
                    // multiplier - still resolves to a zero-length segment, i.e. a tap, exactly as
                    // before.
                    accumulateLmbDelta(xPx, yPx)
                    val fromX = lastX
                    val fromY = lastY
                    val (toX, toY) = drainLmbTarget()
                    val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
                    dispatch(previous.continueStroke(path, elapsed, LMB_DRAG_SEGMENT_DURATION_MS, false))
                }
            }
            PointerAction.SCROLL -> {} // Unreachable: KvmInputInjector routes SCROLL away from handlePointer entirely.
        }
    }

    /** KVM LMB REALTIME FIX: accumulates this MOVE/UP tick's raw delta (amplified) into the pending
     * coalesced buffer - does NOT dispatch or touch lastX/lastY itself. Call [drainLmbTarget] (from the
     * scheduled flush, or from UP) to actually turn the accumulated buffer into a dispatched segment. */
    private fun accumulateLmbDelta(xPx: Float, yPx: Float) {
        val rawDx = xPx - lastRawX
        val rawDy = yPx - lastRawY
        lastRawX = xPx
        lastRawY = yPx
        pendingLmbDx += rawDx * LMB_DRAG_SCROLL_MULTIPLIER
        pendingLmbDy += rawDy * LMB_DRAG_SCROLL_MULTIPLIER
    }

    /** KVM LMB REALTIME FIX: drains the pending coalesced (amplified) delta into a screen-clamped
     * absolute target, updates lastX/lastY to it, and resets the buffer - call exactly once per
     * dispatched segment (from [flushLmbDrag] or from UP). */
    private fun drainLmbTarget(): Pair<Float, Float> {
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - 1).coerceAtLeast(0).toFloat()
        val maxY = (metrics.heightPixels - 1).coerceAtLeast(0).toFloat()
        val targetX = (lastX + pendingLmbDx).coerceIn(0f, maxX)
        val targetY = (lastY + pendingLmbDy).coerceIn(0f, maxY)
        pendingLmbDx = 0f
        pendingLmbDy = 0f
        lastX = targetX
        lastY = targetY
        return targetX to targetY
    }

    /** KVM LMB REALTIME FIX: main-thread only, fires [LMB_DRAG_COALESCE_WINDOW_MS] after the first
     * unflushed MOVE tick of a burst. Dispatches AT MOST one continueStroke segment per window - never
     * one per raw MOVE tick - covering however much (amplified) delta accumulated during it. A no-op
     * if the drag already ended (currentStroke null) or nothing accumulated (e.g. a burst of ticks
     * that net to ~0 movement). */
    private fun flushLmbDrag() {
        lmbFlushScheduled = false
        val previous = currentStroke ?: run { pendingLmbDx = 0f; pendingLmbDy = 0f; return }
        if (pendingLmbDx == 0f && pendingLmbDy == 0f) return
        val fromX = lastX
        val fromY = lastY
        val (toX, toY) = drainLmbTarget()
        val elapsed = (SystemClock.uptimeMillis() - strokeStartUptimeMs).coerceAtLeast(0)
        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        val stroke = previous.continueStroke(path, elapsed, LMB_DRAG_SEGMENT_DURATION_MS, true)
        currentStroke = stroke
        dispatch(stroke)
    }

    /**
     * KVM Mouse V2 EXPERIMENTAL BEHAVIOR - RIGHT CLICK -> LONG PRESS / CONTEXT GESTURE.
     *
     * This is explicitly NOT a real mouse right-button semantic: AccessibilityService.dispatchGesture
     * has no concept of a mouse button at all, only touch contact points. What is actually injected
     * into Android is a single stationary touch held for longer than the system's long-press timeout
     * - the same gesture a finger would produce by touching and holding, which Android's own UI
     * conventionally maps to a context menu. Whether that reliably approximates "right-click" depends
     * entirely on what the focused app does with a long-press at that point; some apps have no
     * long-press behavior at all. Do not treat this as equivalent to a desktop right mouse button.
     *
     * The dispatched duration is real-clock-independent (unlike the continued-stroke pattern
     * `handleLeftButton` uses): DOWN only records the position, and the actual gesture fires once on
     * UP, using a duration deliberately longer than [ViewConfiguration.getLongPressTimeout] plus a
     * safety margin - regardless of how quickly the real right mouse button was pressed and released.
     * A real right-click is instant on a desktop; it should not require physically holding the button
     * down for half a second to register as a "long press" here.
     */
    private fun handleRightButtonAsLongPress(action: PointerAction, xPx: Float, yPx: Float) {
        when (action) {
            PointerAction.DOWN -> {
                rightButtonDownX = xPx
                rightButtonDownY = yPx
                Log.i(TAG, "RIGHT button DOWN at ($xPx, $yPx) - long-press gesture will fire on UP")
            }
            PointerAction.MOVE -> {
                // Right-drag has no modeled semantic yet - track the latest position only, so a small
                // amount of incidental drift before release still long-presses where the button was
                // actually released, not where it was first pressed.
                rightButtonDownX = xPx
                rightButtonDownY = yPx
            }
            PointerAction.UP -> {
                val path = Path().apply { moveTo(rightButtonDownX, rightButtonDownY) }
                val duration = (ViewConfiguration.getLongPressTimeout() + LONG_PRESS_SAFETY_MARGIN_MS).toLong()
                Log.i(TAG, "RIGHT button UP at ($rightButtonDownX, $rightButtonDownY) - dispatching LONG PRESS / CONTEXT GESTURE, duration=${duration}ms (this is NOT a real right-click, see class doc)")
                dispatch(GestureDescription.StrokeDescription(path, 0, duration, false))
            }
            PointerAction.SCROLL -> {}
        }
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // KVM LMB DRAG SCROLL FIX: `xPx, yPx` (a MOVE/UP tick's incoming position) is a proportional 1:1
    // mapping of the Mac cursor's position onto the Mac Screen Share window (KvmCoordinateMapper.toPixels
    // + VideoContentGeometry.normalizedPoint on the Mac side), NOT a fixed px-per-point ratio - it scales
    // with however large the user has that window sized, unlike the wheel's raw NSEvent.scrollingDeltaX/Y,
    // which is window-size-independent. Routing this through the wheel's OWN scroll gesture
    // (BridgeyAccessibilityService's separate `scrollStroke`) in parallel with this button's
    // `currentStroke` would put two independent touches on screen at once - a real multi-touch collision,
    // not just a repeat of the double-tap issue the wheel fix already solved - so accumulateLmbDelta/
    // drainLmbTarget (below) instead amplify the raw incremental delta applied on top of the current
    // (already-amplified) touch position, on the SAME single continued stroke. A still mouse (delta ~0)
    // still resolves to a zero-distance segment (a plain click), regardless of the multiplier's value.

    /**
     * KVM MOUSE V2 PHASE 2 - SCROLL gesture injection (see KVM_MOUSE_V2_PHASE1.md). Approximates a
     * mouse-wheel scroll as a single short touch drag, since dispatchGesture has no native
     * mouse-wheel concept (only touch contact points) - this deliberately does NOT try to simulate a
     * physical wheel, only to produce practical Android scrolling. [request] is already the
     * COALESCED sum of a whole burst of wheel ticks (see ScrollGestureAccumulator /
     * KvmInputInjector) - exactly one dispatchGesture() call per call here, never per raw wheel tick.
     *
     * "Content follows touch": the touch starts at the pointer position and drags by (dx, dy) - a
     * positive dy drags content down by dy pixels, matching the "moving the content, not the
     * scrollbar" convention scrollingDeltaY already uses on the Mac side. No sign inversion. The
     * conversion from wheel delta to drag distance is intentionally 1:1 (no multiplier) pending real
     * on-device measurement - see the phase 2 report.
     *
     * [onComplete] always fires exactly once (whether the gesture actually completed or was
     * cancelled by the system) so the caller can track "is a scroll gesture currently in flight"
     * without needing its own timer - this is how overlapping/back-to-back scroll gestures are
     * avoided (the caller does not schedule a new one while the previous hasn't finished).
     */
    internal fun handleScrollGesture(request: ScrollGestureRequest, onComplete: () -> Unit) {
        abandonStaleScrollStrokeIfNeeded()
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - 1).coerceAtLeast(0).toFloat()
        val maxY = (metrics.heightPixels - 1).coerceAtLeast(0).toFloat()

        // KVM SCROLL ZOOM FIX: root cause (confirmed via runtime logging) was that every coalesced
        // scroll flush used to dispatch its OWN independent willContinue=false stroke, always
        // restarting at the same point (the mouse position doesn't move during a pure scroll). A
        // sustained scroll then produced a rapid sequence of same-location touch-down/lift pairs a
        // few tens of ms apart - well inside Android's double-tap timing/distance thresholds - which
        // browsers/WebViews interpret as a double-tap-to-zoom gesture instead of independent scrolls.
        // Fix: chain the whole scroll burst into ONE continued stroke (same pattern handleLeftButton
        // already uses for drags) - there is only ever one touch-down for the burst, so it can never
        // look like a repeated tap. The touch point keeps moving further in the scroll direction
        // instead of resetting, and is only lifted once scrolling actually pauses (see
        // endScrollGesture, called by KvmInputInjector after an idle timeout).
        val previousStroke = scrollStroke
        val fromX: Float
        val fromY: Float
        val elapsedMs: Long
        if (previousStroke == null) {
            fromX = request.startX.coerceIn(0f, maxX)
            fromY = request.startY.coerceIn(0f, maxY)
            scrollStrokeStartUptimeMs = SystemClock.uptimeMillis()
            elapsedMs = 0L
        } else {
            fromX = scrollCurrentX
            fromY = scrollCurrentY
            elapsedMs = (SystemClock.uptimeMillis() - scrollStrokeStartUptimeMs).coerceAtLeast(0)
        }
        val toX = (fromX + request.dx).coerceIn(0f, maxX)
        val toY = (fromY + request.dy).coerceIn(0f, maxY)
        scrollCurrentX = toX
        scrollCurrentY = toY

        val path = Path().apply { moveTo(fromX, fromY); lineTo(toX, toY) }
        val stroke = if (previousStroke == null) {
            GestureDescription.StrokeDescription(path, elapsedMs, SCROLL_GESTURE_DURATION_MS, true)
        } else {
            previousStroke.continueStroke(path, elapsedMs, SCROLL_GESTURE_DURATION_MS, true)
        }
        scrollStroke = stroke
        dispatchScrollStroke(stroke, onComplete)
    }

    /** KVM SCROLL ZOOM FIX: lifts the continued scroll touch (willContinue=false) - called by
     * [KvmInputInjector] once no new scroll delta has arrived for a short idle window, i.e. scrolling
     * has actually paused. A no-op (immediate onComplete) if no scroll stroke is currently active. */
    internal fun endScrollGesture(onComplete: () -> Unit) {
        abandonStaleScrollStrokeIfNeeded()
        val previousStroke = scrollStroke
        if (previousStroke == null) {
            onComplete()
            return
        }
        scrollStroke = null
        val elapsedMs = (SystemClock.uptimeMillis() - scrollStrokeStartUptimeMs).coerceAtLeast(0)
        val liftPath = Path().apply { moveTo(scrollCurrentX, scrollCurrentY) }
        val stroke = previousStroke.continueStroke(liftPath, elapsedMs, SCROLL_LIFT_DURATION_MS, false)
        dispatchScrollStroke(stroke, onComplete)
    }

    /** KVM SCROLL ZOOM FIX safety net: Android enforces a hard maximum total duration on a chain of
     * continued strokes - GestureDescription.Builder.addStroke throws IllegalStateException past it,
     * which is a REAL crash confirmed during testing here (a scroll stroke stayed continuously open
     * for over 80 real seconds - root cause not fully isolated, plausibly related to unrelated
     * connection-retry activity - with no genuine pause ever reaching [endScrollGesture]). Critically,
     * lifting via continueStroke is subject to the exact same limit as continuing, so once a stroke is
     * already this stale there is no safe way to gracefully end it either - it must be abandoned
     * outright (no further dispatch for it at all) rather than attempting one more continueStroke call
     * that would itself throw. [MAX_SCROLL_STROKE_AGE_MS] is chosen with wide margin under Android's
     * actual limit, so this only ever fires for a pathologically long-lived burst, never normal
     * scrolling. */
    private fun abandonStaleScrollStrokeIfNeeded() {
        if (scrollStroke != null && SystemClock.uptimeMillis() - scrollStrokeStartUptimeMs > MAX_SCROLL_STROKE_AGE_MS) {
            scrollStroke = null
        }
    }

    private fun dispatchScrollStroke(stroke: GestureDescription.StrokeDescription, onComplete: () -> Unit) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        var completed = false
        val complete = {
            if (!completed) {
                completed = true
                onComplete()
            }
        }
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = complete()
                override fun onCancelled(gestureDescription: GestureDescription?) = complete()
            },
            null,
        )
        // dispatchGesture returns false synchronously (e.g. service not fully ready) without ever
        // calling the callback - the caller must still be released from "in flight" in that case, or
        // scroll would silently jam forever after a single failed dispatch.
        if (!dispatched) complete()
    }

    companion object {
        private const val STROKE_SEGMENT_MS = 60L
        /** KVM LMB DRAG SCROLL FIX: unlike SCROLL_SPEED_MULTIPLIER (which scales a window-size-
         * independent raw wheel delta), this scales an ALREADY window-size-dependent proportional
         * cursor movement - there is no single fixed "correct" ratio to measure here (it varies with
         * how large the user has the Mac Screen Share window sized), so this is a reasonable default
         * requiring the same live by-feel tuning SCROLL_SPEED_MULTIPLIER itself went through - adjust
         * this one value if it ends up feeling too fast/slow in practice. */
        private const val LMB_DRAG_SCROLL_MULTIPLIER = 3f
        /** KVM LMB REALTIME FIX: how long to accumulate raw MOVE ticks before dispatching one
         * coalesced continueStroke segment - mirrors KvmInputInjector's SCROLL_COALESCE_WINDOW_MS
         * (same value, 20ms), which is already proven smooth for the same underlying continued-stroke
         * mechanism. Kept as its own separate constant (not literally shared) so tuning one can never
         * accidentally affect the other. */
        private const val LMB_DRAG_COALESCE_WINDOW_MS = 20L
        /** KVM LMB REALTIME FIX: declared duration of each coalesced LMB drag segment - was the bug's
         * actual root cause at STROKE_SEGMENT_MS (60ms): real MOVE ticks can arrive every ~1-8ms, far
         * faster than a 60ms-declared animation can complete, so each new continueStroke() pre-empted
         * the previous one before it ever visually progressed (confirmed via logging during testing
         * here - the touch only reached its final position once ticks stopped arriving, i.e. near UP).
         * Mirrors SCROLL_GESTURE_DURATION_MS (12ms, comfortably shorter than the 20ms window above) -
         * the same proven relationship wheel's own fix already established for this exact API. */
        private const val LMB_DRAG_SEGMENT_DURATION_MS = 12L
        /** KVM Mouse V2 EXPERIMENTAL right-click-as-long-press: added on top of the system's own
         * ViewConfiguration.getLongPressTimeout() (device/accessibility-setting dependent, not
         * hardcoded here) so the dispatched gesture reliably exceeds it rather than racing it. */
        private const val LONG_PRESS_SAFETY_MARGIN_MS = 100
        /** KVM SCROLL SMOOTHNESS FIX: was 80ms, equal to KvmInputInjector.SCROLL_COALESCE_WINDOW_MS -
         * measured during testing here to cause a scheduling race (the flush's "still in flight" check
         * and the gesture's own real completion landed at almost the same ~80ms mark, so any small
         * overrun forced an entire EXTRA coalescing window's wait - observed real inter-dispatch gaps
         * of 165-734ms). Kept meaningfully SHORTER than SCROLL_COALESCE_WINDOW_MS (20ms) so a segment's
         * real completion callback - normally only a few ms past its declared duration, per logging
         * here - reliably lands before the next flush is due, instead of racing it. */
        internal const val SCROLL_GESTURE_DURATION_MS = 12L
        /** KVM SCROLL ZOOM FIX: duration of the final zero-distance segment that lifts the continued
         * scroll stroke (see endScrollGesture) - short, since it carries no movement of its own. */
        private const val SCROLL_LIFT_DURATION_MS = 20L
        /** KVM SCROLL ZOOM FIX safety net (see abandonStaleScrollStrokeIfNeeded): far longer than any
         * normal scroll burst and with wide margin under Android's actual per-gesture maximum (a real
         * crash during testing put that real limit somewhere under ~80s), so this only ever fires for
         * a pathologically long-running burst, never normal scrolling. */
        private const val MAX_SCROLL_STROKE_AGE_MS = 2_000L

        @Volatile
        var instance: BridgeyAccessibilityService? = null
            private set

        /** Mirrors NotificationAccess.isEnabled's exact pattern (BridgeyNotificationListenerService.kt) -
         *  reads the system's own enabled-services list rather than relying on `instance`, so Settings
         *  UI can reflect the real system state even before this process's service has (re)connected. */
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, BridgeyAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
        }
    }
}
