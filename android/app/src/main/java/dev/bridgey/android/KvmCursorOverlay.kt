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
