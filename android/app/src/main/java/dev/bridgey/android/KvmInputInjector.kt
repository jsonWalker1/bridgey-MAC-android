package dev.bridgey.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val TAG = "KvmInputInjector"
private const val CURSOR_IDLE_HIDE_MS = 5_000L

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

    init {
        videoChannel.onInputEvent = { event -> handle(event) }
    }

    private fun handle(event: InputEvent) {
        when (event) {
            is InputEvent.Pointer -> handlePointer(event)
            is InputEvent.Key -> Log.i(TAG, "KEY events not injected in this POC (needs an InputMethodService - see Part 3 report)")
            is InputEvent.Text -> Log.i(TAG, "TEXT events not injected in this POC (needs an InputMethodService - see Part 3 report)")
        }
    }

    private fun handlePointer(pointer: InputEvent.Pointer) {
        val metrics = context.resources.displayMetrics
        val (px, py) = KvmCoordinateMapper.toPixels(pointer.x, pointer.y, metrics.widthPixels, metrics.heightPixels)

        mainHandler.post {
            overlay.show()
            overlay.setPosition(px, py)
            mainHandler.removeCallbacks(hideOverlayRunnable)
            mainHandler.postDelayed(hideOverlayRunnable, CURSOR_IDLE_HIDE_MS)
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
        if (pointer.action == PointerAction.SCROLL) {
            Log.i(TAG, "SCROLL not implemented in this POC")
            return
        }
        mainHandler.post { service.handlePointer(pointer.action, px, py) }
    }
}
