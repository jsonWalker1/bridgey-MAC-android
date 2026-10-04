package dev.bridgey.android

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "PocketGuard"
private const val PROXIMITY_DEBOUNCE_MS = 2_500L
private const val UNLOCK_MAX_DURATION_MS = 1_500L
private const val UNLOCK_MIN_DISTANCE_FRACTION = 0.35f
private const val UNLOCK_MAX_HORIZONTAL_DRIFT_DP = 140f

/** Lowest practical WindowManager.LayoutParams.screenBrightness override - deliberately not 0f
 *  (some OEMs treat that as "panel off" rather than "dimmest possible"), and this is a *per-window*
 *  brightness override, not a Settings.System write: no WRITE_SETTINGS permission needed, and it
 *  self-reverts the instant this window is removed (including on process death - the window
 *  manager owns window cleanup, not us), so there is no "stuck at minimum brightness" state to ever
 *  clean up by hand. */
private const val MINIMUM_PRACTICAL_BRIGHTNESS = 0.01f

/**
 * Advanced Screen Continuity - Pocket Mode. Deliberately orthogonal to the STREAMING session (the
 * same architectural principle the M2.1 orientation fix relies on): this never touches MediaCodec,
 * VirtualDisplay, MediaProjection, or the TCP video/control channels. It only adds a fully
 * transparent, non-rendering touch-interceptor window on top of everything else. Because it draws
 * nothing, it contributes nothing to whatever MediaProjection's VirtualDisplay mirrors - the Mac's
 * stream is completely unaffected by Pocket Mode being engaged or not (verified: there is no
 * Android API to selectively exclude a rendered overlay from a mirrored capture, so the only way to
 * guarantee zero effect on the Mac's view is for the guard to never render anything at all).
 *
 * Recovery path: this overlay sits above normal app windows but below system UI (status bar,
 * notification shade, keyguard), so pulling down the notification shade and tapping "Stop" on the
 * Screen Sharing foreground notification always works as an escape hatch, even if the unlock
 * gesture somehow fails to register.
 */
internal class PocketGuard(private val context: Context) {
    private val mutableActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = mutableActive.asStateFlow()

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val proximitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private var overlayView: View? = null
    private var autoDetectionEnabled = false
    private var proximityCoveredSinceMs: Long? = null
    private var dimActive = false

    // Debounced/hysteresis proximity state machine: a brief hand-covering or laying the phone face
    // down for a moment does not by itself engage the guard - only SUSTAINED coverage does, and
    // uncovering the sensor again never auto-disengages (that's an explicit, deliberate design
    // choice from the spec: only the unlock gesture - or the notification's Stop action - exits).
    //
    // CONFIRMED PLATFORM LIMITATION (real S23 Ultra, One UI): this listener reliably receives its
    // one initial reading on registration, but the underlying hardware's real near/far transitions
    // - independently confirmed via `SensorService: [EARPROXIMITY] ... TouchProxValue` toggling in
    // the system log while covering the sensor - are never delivered to a normal 3rd-party app's
    // SensorEventListener on this device/OS build. Tried SENSOR_DELAY_FASTEST instead of NORMAL;
    // no change. This is a genuine Samsung/One UI restriction, not a bug in this registration - no
    // public API exists to request unfiltered delivery, so Automatic Pocket Detection may simply
    // never trigger on some Samsung devices. Manual Pocket Mode (the "Enable now" button) is
    // unaffected and is the reliable path everywhere.
    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!autoDetectionEnabled || mutableActive.value) {
                proximityCoveredSinceMs = null
                return
            }
            val range = proximitySensor?.maximumRange ?: 5f
            val covered = event.values.isNotEmpty() && event.values[0] < range
            val now = SystemClock.elapsedRealtime()
            if (!covered) {
                proximityCoveredSinceMs = null
                return
            }
            val since = proximityCoveredSinceMs ?: now.also { proximityCoveredSinceMs = it }
            if (now - since >= PROXIMITY_DEBOUNCE_MS) {
                Log.i(TAG, "auto pocket detection: sustained proximity coverage for ${now - since}ms - engaging guard")
                engage(dimScreen = true)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun setAutoDetectionEnabled(enabled: Boolean) {
        if (autoDetectionEnabled == enabled) return
        autoDetectionEnabled = enabled
        val sensors = sensorManager
        val sensor = proximitySensor
        if (enabled && sensors != null && sensor != null) {
            sensors.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "auto pocket detection enabled")
        } else {
            sensors?.unregisterListener(proximityListener)
            proximityCoveredSinceMs = null
            Log.i(TAG, "auto pocket detection disabled${if (sensor == null) " (no proximity sensor on this device)" else ""}")
        }
    }

    fun canEngage(): Boolean = Settings.canDrawOverlays(context)

    /** Idempotent - returns true if the guard is (now) active, false only if the overlay
     *  permission is missing or adding the window otherwise failed. `dimScreen` is true only for
     *  proximity-triggered auto-detection (per spec) - a manual "Enable now" tap never dims, since
     *  the user is visibly looking at the screen right now to have tapped it. */
    fun engage(dimScreen: Boolean = false): Boolean {
        if (mutableActive.value) return true
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "cannot engage pocket guard: overlay permission not granted")
            return false
        }
        val manager = windowManager ?: return false
        val metrics = context.resources.displayMetrics
        val detector = TwoFingerSwipeDownDetector(
            minDistancePx = metrics.heightPixels * UNLOCK_MIN_DISTANCE_FRACTION,
            maxDurationMs = UNLOCK_MAX_DURATION_MS,
            maxHorizontalDriftPx = UNLOCK_MAX_HORIZONTAL_DRIFT_DP * metrics.density,
            onRecognized = {
                Log.i(TAG, "unlock gesture recognized")
                disengage(reason = "unlock gesture recognized")
            },
        )
        val view = object : View(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                detector.onTouch(event)
                return true // consume everything - blocking accidental touches is the entire point
            }
        }.apply { setBackgroundColor(Color.TRANSPARENT) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        if (dimScreen) {
            // A per-window brightness override, NOT a Settings.System write - see
            // MINIMUM_PRACTICAL_BRIGHTNESS's doc comment for why this needs no special permission
            // and cannot leave the device stuck dim. The current system value is only read here for
            // the log line below (nothing to restore later - there's no persistent state to undo).
            params.screenBrightness = MINIMUM_PRACTICAL_BRIGHTNESS
        }
        return runCatching { manager.addView(view, params) }
            .onSuccess {
                overlayView = view
                dimActive = dimScreen
                mutableActive.value = true
                val previousBrightness = runCatching {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                }.getOrNull()
                Log.i(
                    TAG,
                    "pocket guard engaged: transparent touch-interceptor added (renders nothing - " +
                        "MediaProjection/encoder/VirtualDisplay/TCP session untouched, Mac view unchanged)" +
                        if (dimScreen) "; dimmed to $MINIMUM_PRACTICAL_BRIGHTNESS (system value was $previousBrightness, unchanged - window override only)" else "",
                )
            }
            .onFailure { Log.e(TAG, "failed to add touch-guard overlay: ${it.message}") }
            .isSuccess
    }

    fun disengage(reason: String) {
        if (!mutableActive.value) return
        val view = overlayView
        val wasDimmed = dimActive
        overlayView = null
        dimActive = false
        mutableActive.value = false
        proximityCoveredSinceMs = null
        // Removing the window is ALL that's needed to restore normal brightness: the override was
        // only ever a property of this window, so it stops applying the instant the window is gone
        // - the same removeView() call already used for the non-dimmed case. This is also why
        // Pocket Mode can never leave the phone stuck dim: any teardown path that reaches here
        // (explicit unlock, screen sharing stopping, the feature being turned off, or even this
        // process dying and the window manager reclaiming orphaned windows) restores it the same way.
        if (view != null) runCatching { windowManager?.removeView(view) }
        Log.i(TAG, "pocket guard disengaged: $reason${if (wasDimmed) " (brightness override removed with the window)" else ""}")
    }
}

/**
 * Requires two fingers moving downward together, across roughly a third of the screen height,
 * within a bounded time window. Chosen deliberately over a single tap/swipe: fabric, keys, or skin
 * contact inside a pocket essentially never produces a clean, simultaneous two-finger gesture, so
 * this is both easy to perform on purpose (no visual target needed - it can be done by feel) and
 * very unlikely to trigger by accident.
 */
private class TwoFingerSwipeDownDetector(
    private val minDistancePx: Float,
    private val maxDurationMs: Long,
    private val maxHorizontalDriftPx: Float,
    private val onRecognized: () -> Unit,
) {
    private var startTimeMs = 0L
    private val startPositions = mutableMapOf<Int, FloatArray>()

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (startPositions.size >= 2) {
                    reset() // a third finger joined mid-gesture - not a clean two-finger swipe
                    return
                }
                val index = event.actionIndex
                startPositions[event.getPointerId(index)] = floatArrayOf(event.getX(index), event.getY(index))
                if (startPositions.size == 1) startTimeMs = SystemClock.elapsedRealtime()
            }
            MotionEvent.ACTION_MOVE -> if (startPositions.size == 2) evaluate(event)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
    }

    private fun evaluate(event: MotionEvent) {
        if (SystemClock.elapsedRealtime() - startTimeMs > maxDurationMs) {
            reset()
            return
        }
        var minDownwardDistance = Float.MAX_VALUE
        for ((pointerId, start) in startPositions) {
            val index = event.findPointerIndex(pointerId)
            if (index < 0) {
                reset()
                return
            }
            val dx = event.getX(index) - start[0]
            val dy = event.getY(index) - start[1]
            if (dy <= 0f || kotlin.math.abs(dx) > maxHorizontalDriftPx) return // not recognized yet - keep waiting
            minDownwardDistance = minOf(minDownwardDistance, dy)
        }
        if (minDownwardDistance >= minDistancePx) {
            onRecognized()
            reset()
        }
    }

    private fun reset() {
        startPositions.clear()
        startTimeMs = 0L
    }
}
