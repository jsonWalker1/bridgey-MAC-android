package dev.bridgey.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * BRIDGEY WEB HANDOFF TOOLBAR CHIP POC - EXPERIMENTAL, NOT PRODUCTION.
 *
 * Draws a small Bridgey icon as an accessibility overlay over the browser's own address bar (the
 * trailing end of the `url_bar` node, located from the accessibility tree, never from hard-coded
 * coordinates). It is shown only while a supported browser is the focused app window and its
 * address bar is visible and not being edited; it pulses once per new page. Tapping it opens a tiny
 * "Continue on Mac" card; Continue hands the URL (+ reading position or selection as a Text Fragment)
 * to [onContinue], which uses the existing Web Handoff POC transport.
 */
internal class WebHandoffToolbarChip(
    private val service: AccessibilityService,
    private val onContinue: () -> String?,
    private val status: () -> String?,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private var chip: ImageView? = null
    private var chipParams: WindowManager.LayoutParams? = null
    private var card: View? = null
    private var lastPageUrl: String? = null
    private var title: String? = null
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

    fun destroy() { main.removeCallbacksAndMessages(null); hideCard(); hideChip("destroy") }

    private fun update() {
        if (!enabled) return hideChip("disabled")
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
            title = null
            hideCard()
            pulse()
        }
        // The page title costs a tree walk, so it is looked up once per page (the heading may
        // only appear after the page finished loading).
        if (title == null && pageUrl != null) title = pageTitle(root)
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

    private fun pageTitle(root: AccessibilityNodeInfo): String? {
        var found: String? = null
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (found != null || depth > 40) return
            if (node.isHeading && !node.text.isNullOrBlank()) { found = node.text.toString().trim(); return }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return found?.take(80)
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
            setOnClickListener { toggleCard() }
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
        hideCard()
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

    private fun toggleCard() { if (card != null) hideCard() else showCard() }

    private fun showCard() {
        val chipAt = chipParams ?: return
        val pad = dp(16)
        val heading = TextView(service).apply { text = "Bridgey"; setTextColor(Color.rgb(120, 130, 150)); textSize = 12f }
        val action = TextView(service).apply { text = "Continue on Mac"; setTextColor(Color.WHITE); textSize = 17f; setPadding(0, dp(4), 0, dp(6)) }
        val page = TextView(service).apply {
            text = "“${title ?: lastPageUrl ?: "This page"}”"; setTextColor(Color.rgb(200, 210, 225)); textSize = 14f; maxLines = 2
        }
        val result = TextView(service).apply { setTextColor(Color.rgb(140, 200, 255)); textSize = 13f; visibility = View.GONE }
        val button = Button(service).apply {
            text = "Continue"; isAllCaps = false
            setOnClickListener {
                val sent = onContinue()
                result.visibility = View.VISIBLE
                result.text = if (sent == null) "Nothing to hand off on this page" else "Sending to Mac…"
                isEnabled = false
                if (sent != null) {
                    main.postDelayed({ result.text = status() ?: "Sent" }, 1500)
                    main.postDelayed({ hideCard() }, 4500)
                }
            }
        }
        val view = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.argb(240, 22, 28, 44)) }
            elevation = dp(8).toFloat()
            addView(heading); addView(action); addView(page); addView(result)
            addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END; topMargin = dp(6)
            })
            // Any touch outside the card closes it and still reaches the browser.
            setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) hideCard(); false }
        }
        val width = dp(260)
        val screenWidth = service.resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (chipAt.x + chipAt.width - width).coerceIn(dp(8), (screenWidth - width - dp(8)).coerceAtLeast(dp(8)))
            y = chipAt.y + chipAt.height + dp(10)
        }
        wm.addView(view, params)
        card = view
        log("CARD shown title=$title")
    }

    private fun hideCard() {
        val view = card ?: return
        runCatching { wm.removeView(view) }
        card = null
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val WATCHDOG_MS = 500L
        const val SETTLE_MS = 150L
        const val FIRST_SHOW_SETTLE_MS = 450L
        const val MAX_SETTLE_RETRIES = 12
    }
}
