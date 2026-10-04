package dev.bridgey.android

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView

/**
 * BRIDGEY WEB HANDOFF TOOLBAR CHIP POC - EXPERIMENTAL, NOT PRODUCTION.
 *
 * Draws a small Bridgey icon as an accessibility overlay over the browser's own address bar (the
 * trailing end of the `url_bar` node, located from the accessibility tree, never from hard-coded
 * coordinates). It is shown only while a supported browser is the focused app window and its
 * address bar is visible and not being edited, and only while a Mac is connected (no misleading
 * "send" without one); it pulses once per new page. Tapping it hands off immediately ([onTap] ->
 * [WebHandoff.perform], which reports the outcome).
 */
internal class WebHandoffToolbarChip(
    private val service: AccessibilityService,
    private val onTap: () -> Unit,
    private val isConnected: () -> Boolean,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private var chip: ImageView? = null
    private var chipParams: WindowManager.LayoutParams? = null
    private var lastPageUrl: String? = null
    private var updateQueued = false
    private var enabled = true
    // Window/rotation animations report transient toolbar bounds; the chip only appears or moves
    // once the same position is measured twice in a row, and transient states are re-checked.
    private var candidate: Rect? = null
    private var settleRetries = 0
    // The service only receives events from browser packages (privacy), so switching to another
    // app produces no event at all. While the chip is on screen, re-check the foreground window.
    private val watchdog = object : Runnable {
        override fun run() { if (chip != null) { update(); main.postDelayed(this, WATCHDOG_MS) } }
    }

    /** Coalesces bursts of accessibility events into one layout pass. */
    fun requestUpdate(delayMs: Long = 80) {
        settleRetries = 0
        schedule(delayMs)
    }

    private fun schedule(delayMs: Long) {
        if (updateQueued) return
        updateQueued = true
        main.postDelayed({ updateQueued = false; update() }, delayMs)
    }

    fun setEnabled(value: Boolean) { enabled = value; schedule(0) }

    fun destroy() { main.removeCallbacksAndMessages(null); hideChip("destroy") }

    private fun update() {
        if (!enabled) return hideChip("disabled")
        if (!isConnected()) return hideChip("no connected Mac")
        val window = focusedAppWindow()
        val browser = window?.root?.packageName?.toString()
        if (window == null || browser !in WebHandoffPocService.BROWSERS) {
            return hide("browser not focused (${browser ?: "none"})")
        }
        val root = window.root ?: return hide("no root")
        val bar = WebHandoffPocService.urlField(root) ?: return hide("no url field")
        val barBounds = Rect().also(bar::getBoundsInScreen)
        val screen = Rect().also { window.getBoundsInScreen(it) }
        // Chromium hides the toolbar while scrolling (top controls slide away); the url bar is then
        // not visible to the user or has no on-screen height left.
        if (!bar.isVisibleToUser || barBounds.height() < dp(20) || barBounds.top < screen.top) {
            return hide("url bar hidden $barBounds")
        }
        // During the rotation animation the reported bounds are transiently distorted (tall, mid
        // screen); an address field is always a wide strip near an edge.
        if (barBounds.width() < barBounds.height() * 3 || barBounds.height() > dp(80) || !screen.contains(barBounds) ||
            barBounds.right > service.resources.displayMetrics.widthPixels) {
            return hide("url bar bounds unstable $barBounds")
        }
        if (barBounds != candidate) {
            candidate = Rect(barBounds)
            return recheck()
        }
        settleRetries = 0
        if (bar.isFocused) return hide("url bar editing")
        val text = bar.text?.toString().orEmpty()
        val pageUrl = text.takeIf { it.contains('.') && !it.contains(' ') }
        showChip(barBounds, hasContext = pageUrl != null)
        if (pageUrl != null && pageUrl != lastPageUrl) {
            lastPageUrl = pageUrl
            pulse()
        }
    }

    /** Hidden for a possibly transient reason (window/rotation animation): look again shortly. */
    private fun hide(reason: String) { recheck(); hideChip(reason) }

    private fun recheck() {
        // A hidden chip waits out the ~0.5 s app-open animation, whose scaled toolbar can hold
        // still for one 150 ms interval.
        if (settleRetries++ < MAX_SETTLE_RETRIES) schedule(if (chip == null) FIRST_SHOW_SETTLE_MS else SETTLE_MS)
    }

    private fun focusedAppWindow(): AccessibilityWindowInfo? {
        val windows = service.windows
        // A keyguard, notification shade or IME in front means the browser is not what the user sees.
        if (windows.any { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.isFocused }) return null
        return windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
    }

    private fun showChip(barBounds: Rect, hasContext: Boolean) {
        val size = dp(30)
        // Trailing end of the URL field, vertically centred on it: the domain text is left aligned,
        // so this is the field's empty end in the common case.
        val x = barBounds.right - size - dp(4)
        val y = barBounds.centerY() - size / 2
        val view = chip ?: ImageView(service).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            contentDescription = "Continue on Mac with Bridgey"
            setOnClickListener {
                // A short tap-down scale gives immediate feedback; the result arrives as a toast.
                animate().scaleX(0.85f).scaleY(0.85f).setDuration(90).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }.start()
                onTap()
            }
        }.also { created ->
            val params = WindowManager.LayoutParams(
                size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
            wm.addView(created, params)
            chip = created; chipParams = params
            main.removeCallbacks(watchdog); main.postDelayed(watchdog, WATCHDOG_MS)
            log("CHIP shown at ($x,$y) bar=$barBounds")
        }
        view.alpha = if (hasContext) 1f else 0.45f
        val params = chipParams ?: return
        if (params.x != x || params.y != y) {
            params.x = x; params.y = y
            runCatching { wm.updateViewLayout(view, params) }
            log("CHIP moved to ($x,$y) bar=$barBounds")
        }
    }

    private fun hideChip(reason: String) {
        val view = chip ?: return
        runCatching { wm.removeView(view) }
        chip = null; chipParams = null
        main.removeCallbacks(watchdog)
        log("CHIP hidden: $reason")
    }

    /** Two gentle scale pulses: "this page can continue on your Mac". */
    private fun pulse() {
        val view = chip ?: return
        view.animate().cancel()
        fun once(then: () -> Unit) = view.animate().scaleX(1.18f).scaleY(1.18f).setDuration(220).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(260).withEndAction(then).start()
        }.start()
        main.postDelayed({ once { main.postDelayed({ once {} }, 180) } }, 350)
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val WATCHDOG_MS = 500L
        const val SETTLE_MS = 150L
        const val FIRST_SHOW_SETTLE_MS = 450L
        const val MAX_SETTLE_RETRIES = 12
    }
}
