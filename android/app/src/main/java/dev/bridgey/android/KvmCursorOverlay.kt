package dev.bridgey.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

private const val TAG = "KvmCursorOverlay"
private const val CURSOR_RADIUS_DP = 10f

/** KVM SCROLL PERFORMANCE FIX (see KVM_MOUSE_V2_PHASE1.md / the scroll performance root-cause
 * report): a position difference at or below this many pixels is treated as "didn't move" and must
 * not trigger a redraw. Small enough to be visually meaningless against the 10dp cursor dot, large
 * enough to absorb float jitter from repeated identical-position events (e.g. SCROLL, which reports
 * the same on-screen point on every tick since the mouse itself isn't moving). */
private const val CURSOR_POSITION_EPSILON_PX = 0.5f

/** Pure, Android-framework-free so it has real unit test coverage (same rationale as
 * KvmCoordinateMapper) - CursorView itself extends android.view.View and can't be instantiated in a
 * plain JUnit test. True iff the position moved by more than [CURSOR_POSITION_EPSILON_PX] on either
 * axis. */
internal fun cursorPositionChanged(oldX: Float, oldY: Float, newX: Float, newY: Float): Boolean {
    return kotlin.math.abs(newX - oldX) > CURSOR_POSITION_EPSILON_PX ||
        kotlin.math.abs(newY - oldY) > CURSOR_POSITION_EPSILON_PX
}

/**
 * KVM PART 3 POC. Visual feedback only - draws a small dot at the position the Mac's mouse maps to,
 * so the phone's OWN screen shows where KVM input is about to land. This matters most for Headless
 * KVM (Screen Share OFF): the phone's physical display is the only place to see the cursor at all,
 * since there is no Mac-side video to look at. Uses the same TYPE_APPLICATION_OVERLAY mechanism as
 * PocketGuard (already-granted SYSTEM_ALERT_WINDOW permission - no new permission needed), but with
 * the OPPOSITE touch flags: FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE, since this view must never
 * intercept a real touch - actual input injection happens separately via
 * BridgeyAccessibilityService.dispatchGesture, not by this overlay consuming anything.
 */
internal class KvmCursorOverlay(private val context: Context) {
    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private var view: CursorView? = null

    fun show() {
        if (view != null) return
        val manager = windowManager ?: return
        val cursorView = CursorView(context)
        // KVM COORDINATE FIX (reverted): a prior attempt added FLAG_LAYOUT_IN_SCREEN /
        // FLAG_LAYOUT_NO_LIMITS / layoutInDisplayCutoutMode here to make this window's local (0,0)
        // land on the true absolute screen origin, matching BridgeyAccessibilityService.dispatchGesture's
        // coordinate space directly. That broke the *visual* cursor position: KvmPointerCalibration's
        // live-tuned offsets were dialed in by eye against THIS window's natural (status-bar-inset)
        // placement, so removing that inset here moved the dot without moving what calibration expects.
        // Deliberately left as a plain (non-IN_SCREEN) TYPE_APPLICATION_OVERLAY window, so WindowManager
        // keeps auto-insetting it below the status bar/cutout exactly as it always did - the coordinate
        // fix now lives entirely on the gesture-dispatch side (see KvmInputInjector.statusBarInsetPx),
        // which adds the same real, runtime-measured inset to the dispatchGesture target instead of
        // removing it from this window.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { manager.addView(cursorView, params) }
            .onSuccess { view = cursorView; Log.i(TAG, "cursor overlay shown") }
            .onFailure { Log.w(TAG, "failed to show cursor overlay: ${it.message}") }
    }

    fun setPosition(xPx: Float, yPx: Float) {
        view?.setPosition(xPx, yPx)
    }

    fun hide() {
        val current = view ?: return
        view = null
        runCatching { windowManager?.removeView(current) }
        Log.i(TAG, "cursor overlay hidden")
    }

    private class CursorView(context: Context) : View(context) {
        private val radiusPx = CURSOR_RADIUS_DP * resources.displayMetrics.density
        private var x = -1f
        private var y = -1f
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 60, 60) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }

        fun setPosition(px: Float, py: Float) {
            if (!cursorPositionChanged(x, y, px, py)) return
            x = px
            y = py
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (x < 0f || y < 0f) return
            canvas.drawCircle(x, y, radiusPx, fill)
            canvas.drawCircle(x, y, radiusPx, stroke)
        }
    }
}
