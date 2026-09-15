package dev.bridgey.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
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

    internal fun handlePointer(action: PointerAction, xPx: Float, yPx: Float) {
        when (action) {
            PointerAction.DOWN -> {
                val path = Path().apply { moveTo(xPx, yPx) }
                strokeStartUptimeMs = SystemClock.uptimeMillis()
                lastX = xPx
                lastY = yPx
                val stroke = GestureDescription.StrokeDescription(path, 0, STROKE_SEGMENT_MS, true)
                currentStroke = stroke
                dispatch(stroke)
            }
            PointerAction.MOVE -> {
                val previous = currentStroke ?: return
                val path = Path().apply { moveTo(lastX, lastY); lineTo(xPx, yPx) }
                val elapsed = (SystemClock.uptimeMillis() - strokeStartUptimeMs).coerceAtLeast(0)
                lastX = xPx
                lastY = yPx
                val stroke = previous.continueStroke(path, elapsed, STROKE_SEGMENT_MS, true)
                currentStroke = stroke
                dispatch(stroke)
            }
            PointerAction.UP -> {
                val previous = currentStroke
                val path = Path().apply { moveTo(lastX, lastY); lineTo(xPx, yPx) }
                val elapsed = (SystemClock.uptimeMillis() - strokeStartUptimeMs).coerceAtLeast(0)
                currentStroke = null
                if (previous == null) {
                    // An UP with no matching DOWN (e.g. the channel opened mid-gesture) - dispatch a
                    // standalone tap rather than silently dropping it.
                    val tapPath = Path().apply { moveTo(xPx, yPx) }
                    dispatch(GestureDescription.StrokeDescription(tapPath, 0, STROKE_SEGMENT_MS, false))
                } else {
                    dispatch(previous.continueStroke(path, elapsed, STROKE_SEGMENT_MS, false))
                }
            }
            PointerAction.SCROLL -> {
                // Not implemented in this POC (see KvmInputInjector) - dispatchGesture has no native
                // scroll-wheel concept; a production version would synthesize a short drag or use
                // AccessibilityNodeInfo.ACTION_SCROLL_FORWARD/BACKWARD on the focused scrollable node.
            }
        }
    }

    private fun dispatch(stroke: GestureDescription.StrokeDescription) {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val STROKE_SEGMENT_MS = 60L

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
