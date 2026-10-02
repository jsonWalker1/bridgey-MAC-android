package dev.bridgey.android

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * BRIDGEY WEB HANDOFF POC - EXPERIMENTAL, NOT PRODUCTION.
 *
 * A separate, opt-in accessibility service (the KVM service deliberately cannot read window
 * content) that measures how much web-page context Bridgey can capture from a Chromium browser on
 * Android, and builds a "continuation URL" the Mac can open with no browser automation: the page
 * URL plus an HTML `#id` and/or a W3C Text Fragment (`#:~:text=`), both of which Safari and
 * Chromium scroll to (and the text fragment also highlights) on their own.
 *
 * Driven from adb for the POC (broadcasts need the shell-only DUMP permission):
 *   adb shell am broadcast -a dev.bridgey.webpoc.DUMP
 *   adb shell am broadcast -a dev.bridgey.webpoc.PICK [--ei x 500 --ei y 1200] [--ez send true]
 *   adb shell am broadcast -a dev.bridgey.webpoc.SELECTION [--ez send true]
 * PICK without coordinates shows a tap overlay ("tap the element to continue on Mac").
 * Only text, roles, ids, bounds and link targets are read; password/editable fields are skipped,
 * and nothing leaves the phone unless `send` is set (then only the continuation URL is sent, via
 * the existing explicit "send link" quick action).
 *
 * Toolbar chip UX POC: [WebHandoffToolbarChip] shows a Bridgey icon over the browser's address bar;
 * `adb shell am broadcast -a dev.bridgey.webpoc.CHIP --ez on false` turns it off.
 */
class WebHandoffPocService : AccessibilityService() {
    private var lastSelection: JSONObject? = null
    // The browser hides its URL bar while text is selected (selection action bar), so the last URL
    // seen in the bar is remembered per browser.
    private val lastUrlByBrowser = mutableMapOf<String, String>()
    private var overlay: FrameLayout? = null
    private var lastSelectionAt = 0L
    private var chip: WebHandoffToolbarChip? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DUMP -> dumpTree()
                ACTION_PICK -> {
                    val send = intent.getBooleanExtra("send", false)
                    if (intent.hasExtra("x") && intent.hasExtra("y")) {
                        pickAt(intent.getIntExtra("x", 0), intent.getIntExtra("y", 0), send)
                    } else {
                        showPickOverlay(send)
                    }
                }
                ACTION_SELECTION -> handOffSelection(intent.getBooleanExtra("send", false))
                ACTION_CHIP -> chip?.setEnabled(intent.getBooleanExtra("on", true))
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter().apply {
            addAction(ACTION_DUMP); addAction(ACTION_PICK); addAction(ACTION_SELECTION); addAction(ACTION_CHIP)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter, android.Manifest.permission.DUMP, null)
        }
        chip = WebHandoffToolbarChip(this, ::continueFromChip, { (application as BridgeyApplication).pairing.quickActions.status.value }, ::log)
            .also { it.requestUpdate(0) }
        log("service connected")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        chip?.requestUpdate(300)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        removeOverlay()
        chip?.destroy()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        chip?.requestUpdate()
        val browser = event.packageName?.toString() ?: return
        if (browser !in BROWSERS) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            if (event.source?.viewIdResourceName?.substringAfter(":id/") in URL_FIELD_IDS) browserRoot()?.let(::pageUrl)
            return
        }
        val source = event.source
        if (source?.isPassword == true) return
        val text = source?.text?.toString() ?: event.text.joinToString("")
        val from = event.fromIndex
        val to = event.toIndex
        val selected = if (from in 0 until to && to <= text.length) text.substring(from, to) else ""
        lastSelection = JSONObject()
            .put("nodeText", text.take(400))
            .put("from", from).put("to", to)
            .put("selected", selected.take(400))
            .put("viewId", source?.viewIdResourceName ?: JSONObject.NULL)
            .put("class", source?.className?.toString() ?: JSONObject.NULL)
        lastSelectionAt = if (selected.isNotBlank()) android.os.SystemClock.elapsedRealtime() else 0L
        log("SELECTION event ${lastSelection}")
    }

    // ---- Phase 1: evidence dump -----------------------------------------------------------

    private fun dumpTree() {
        val root = browserRoot() ?: return log("DUMP: no browser window in front")
        var count = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_DUMP_NODES) return
            count++
            if (!node.isPassword) log("DUMP ${"  ".repeat(depth.coerceAtMost(12))}${describe(node)}")
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        log("DUMP done nodes=$count url=${pageUrl(root)}")
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        val extras = node.extras?.let { bundle ->
            bundle.keySet().joinToString(",") { key -> "$key=${bundle.get(key).toString().take(120)}" }
        }.orEmpty()
        return "${node.className} id=${node.viewIdResourceName} text=${node.text?.toString()?.take(80)} " +
            "desc=${node.contentDescription?.toString()?.take(60)} heading=${if (Build.VERSION.SDK_INT >= 28) node.isHeading else "?"} " +
            "click=${node.isClickable} scroll=${node.isScrollable} editable=${node.isEditable} bounds=$bounds " +
            "extras={$extras}"
    }

    // ---- Phase 2/3: element picker + WebAnchor ---------------------------------------------

    private fun pickAt(x: Int, y: Int, send: Boolean) {
        val root = browserRoot() ?: return report("Open a page in the browser first", null)
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun flatten(node: AccessibilityNodeInfo) {
            if (nodes.size >= MAX_SCAN_NODES) return
            nodes += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::flatten)
        }
        flatten(root)
        val webRoot = nodes.firstOrNull { it.className == "android.webkit.WebView" }
        val webNodes = if (webRoot != null) nodes.filter { isDescendant(it, webRoot) } else nodes
        // Deepest node under the point that carries text or is clickable (smallest area wins).
        val hit = webNodes
            .filter { !it.isPassword && !it.isEditable && bounds(it).contains(x, y) && (label(it).isNotBlank() || it.isClickable) }
            .minByOrNull { bounds(it).width().toLong() * bounds(it).height() }
            ?: return report("No web element under ($x,$y)", null)
        val index = webNodes.indexOf(hit)
        val heading = webNodes.take(index + 1).lastOrNull { Build.VERSION.SDK_INT >= 28 && it.isHeading && label(it).isNotBlank() }
        val before = webNodes.take(index).lastOrNull { label(it).isNotBlank() && !isDescendant(hit, it) }
        val after = webNodes.drop(index + 1).firstOrNull { label(it).isNotBlank() && !isDescendant(it, hit) }
        val targetText = elementText(hit)
        val htmlId = hit.viewIdResourceName?.takeIf { !it.contains(":id/") }
            ?: generateSequence(hit) { it.parent }.take(4).mapNotNull { it.viewIdResourceName?.takeIf { id -> !id.contains(":id/") } }.firstOrNull()
        val href = linkTarget(hit)
        val url = pageUrl(root)
        val webBounds = webRoot?.let(::bounds)
        val anchor = JSONObject()
            .put("pageUrl", url ?: JSONObject.NULL)
            .put("pageTitle", webRoot?.let(::label)?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("target", JSONObject()
                .put("role", hit.extras?.getString("AccessibilityNodeInfo.chromeRole") ?: hit.className?.toString())
                .put("text", targetText.take(300))
                .put("htmlId", htmlId ?: JSONObject.NULL)
                .put("href", href ?: JSONObject.NULL)
                .put("heading", Build.VERSION.SDK_INT >= 28 && hit.isHeading)
                .put("clickable", hit.isClickable)
                .put("bounds", bounds(hit).flattenToString()))
            .put("context", JSONObject()
                .put("nearestHeading", heading?.let(::label)?.take(200) ?: JSONObject.NULL)
                .put("before", before?.let(::label)?.takeLast(120) ?: JSONObject.NULL)
                .put("after", after?.let(::label)?.take(120) ?: JSONObject.NULL))
            .put("viewport", JSONObject()
                .put("web", webBounds?.flattenToString() ?: JSONObject.NULL)
                .put("relativeY", if (webBounds != null && webBounds.height() > 0) (bounds(hit).top - webBounds.top).toDouble() / webBounds.height() else JSONObject.NULL))
        val continuation = url?.let { continuationUrl(it, htmlId, targetText) }
        anchor.put("continuationUrl", continuation ?: JSONObject.NULL)
        log("ANCHOR $anchor")
        report("Picked: ${targetText.take(60)}", continuation.takeIf { send })
    }

    private fun handOffSelection(send: Boolean) {
        val root = browserRoot() ?: return report("Open a page in the browser first", null)
        val selection = lastSelection ?: return report("No text selection seen yet", null)
        val selected = selection.optString("selected")
        val url = pageUrl(root) ?: lastUrlByBrowser.values.lastOrNull()
            ?: return report("No page URL (root=${root.packageName} bar=${root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/url_bar").size} cached=${lastUrlByBrowser.keys})", null)
        // The selection's own node text gives the words immediately around it, so the fragment can
        // carry prefix/suffix context and land on this occurrence, not the first one on the page.
        val nodeText = selection.optString("nodeText")
        val from = selection.optInt("from"); val to = selection.optInt("to")
        val prefix = nodeText.take(from.coerceIn(0, nodeText.length)).trim().split(Regex("\\s+")).takeLast(3).joinToString(" ")
        val suffix = nodeText.drop(to.coerceIn(0, nodeText.length)).trim().split(Regex("\\s+")).take(3).joinToString(" ")
        val continuation = if (selected.isNotBlank()) continuationUrl(url, selection.optString("viewId").takeIf { it.isNotBlank() && it != "null" && !it.contains(":id/") }, selected, prefix, suffix) else url
        log("SELECTION handoff selected=${selected.take(120)} url=$continuation")
        report("Selection: ${selected.take(60)}", continuation.takeIf { send })
    }

    /**
     * Toolbar chip "Continue": a fresh text selection wins; otherwise the first fully visible
     * paragraph-sized text in the page (the reading position) becomes the Text Fragment.
     */
    private fun continueFromChip(): String? {
        val root = browserRoot() ?: return null
        val selected = lastSelection?.optString("selected").orEmpty()
        if (selected.isNotBlank() && android.os.SystemClock.elapsedRealtime() - lastSelectionAt < 120_000) {
            handOffSelection(send = true)
            return "selection"
        }
        val url = pageUrl(root) ?: return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun flatten(node: AccessibilityNodeInfo) {
            if (nodes.size >= MAX_SCAN_NODES) return
            nodes += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::flatten)
        }
        flatten(root)
        val webRoot = nodes.firstOrNull { it.className == "android.webkit.WebView" }
        val web = webRoot?.let(::bounds)
        val reading = nodes.firstOrNull { node ->
            val b = bounds(node)
            web != null && isDescendant(node, webRoot) && !node.isPassword && !node.isEditable &&
                b.top >= web.top && b.bottom <= web.bottom && node.isVisibleToUser &&
                label(node).split(Regex("\\s+")).size >= 6
        }
        // A whole paragraph is long; its first sentence is enough to land on it.
        val text = reading?.let(::label)?.split(Regex("(?<=[.!?])\\s"))?.firstOrNull()
        val continuation = continuationUrl(url, null, text)
        log("CHIP continue reading=${text?.take(120)} url=$continuation")
        report("Continue on Mac: ${text?.take(40) ?: url}", continuation)
        return continuation
    }

    private fun report(message: String, sendUrl: String?) {
        log("RESULT $message")
        android.os.Handler(mainLooper).post { Toast.makeText(this, "Web Handoff POC: $message", Toast.LENGTH_SHORT).show() }
        if (sendUrl != null) {
            (application as BridgeyApplication).pairing.quickActions.sendLink(sendUrl)
            log("SENT continuation url via links quick action length=${sendUrl.length}")
        }
    }

    private fun showPickOverlay(send: Boolean) {
        android.os.Handler(mainLooper).post {
            removeOverlay()
            val wm = getSystemService(WindowManager::class.java)
            val view = FrameLayout(this).apply {
                setBackgroundColor(Color.argb(30, 40, 120, 255))
                addView(TextView(context).apply {
                    text = "Web Handoff: ťukni na prvek, který chceš otevřít na Macu"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(200, 30, 30, 30))
                    setPadding(32, 24, 32, 24)
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        val x = event.rawX.toInt(); val y = event.rawY.toInt()
                        removeOverlay()
                        android.os.Handler(mainLooper).postDelayed({ pickAt(x, y, send) }, 150)
                    }
                    true
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            wm.addView(view, params)
            overlay = view
        }
    }

    private fun removeOverlay() {
        overlay?.let { view -> runCatching { getSystemService(WindowManager::class.java).removeView(view) } }
        overlay = null
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun browserRoot(): AccessibilityNodeInfo? =
        windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() in BROWSERS }
            ?: rootInActiveWindow?.takeIf { it.packageName?.toString() in BROWSERS }

    private fun pageUrl(root: AccessibilityNodeInfo): String? {
        val browser = root.packageName?.toString().orEmpty()
        val bar = urlField(root)
        val text = bar?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && !bar.isFocused }
            ?: return lastUrlByBrowser[browser]
        val url = if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
        lastUrlByBrowser[browser] = url
        return url
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun label(node: AccessibilityNodeInfo): String =
        (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()

    /** A container's own text is often empty in Chromium; join its descendants' text. */
    private fun elementText(node: AccessibilityNodeInfo): String {
        label(node).takeIf { it.isNotBlank() }?.let { return it }
        val parts = mutableListOf<String>()
        fun collect(n: AccessibilityNodeInfo) {
            if (parts.size > 20) return
            label(n).takeIf { it.isNotBlank() && !n.isPassword }?.let(parts::add)
            for (i in 0 until n.childCount) n.getChild(i)?.let(::collect)
        }
        collect(node)
        return parts.distinct().joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    private fun linkTarget(node: AccessibilityNodeInfo): String? =
        generateSequence(node) { it.parent }.take(4).mapNotNull { n ->
            n.extras?.keySet()?.firstOrNull { it.contains("url", ignoreCase = true) }?.let { key -> n.extras.get(key)?.toString() }
        }.firstOrNull { it.startsWith("http") }

    private fun isDescendant(node: AccessibilityNodeInfo, ancestor: AccessibilityNodeInfo): Boolean =
        generateSequence(node.parent) { it.parent }.take(64).any { it == ancestor }

    private fun log(message: String) { android.util.Log.i("BridgeyWebPoc", message) }

    companion object {
        const val ACTION_DUMP = "dev.bridgey.webpoc.DUMP"
        const val ACTION_PICK = "dev.bridgey.webpoc.PICK"
        const val ACTION_SELECTION = "dev.bridgey.webpoc.SELECTION"
        const val ACTION_CHIP = "dev.bridgey.webpoc.CHIP"
        val BROWSERS = setOf("com.brave.browser", "com.android.chrome", "com.sec.android.app.sbrowser")
        /** Address field view ids: Chromium (Brave, Chrome) and Samsung Internet. */
        private val URL_FIELD_IDS = listOf("url_bar", "location_bar_edit_text")

        fun urlField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val browser = root.packageName?.toString() ?: return null
            return URL_FIELD_IDS.firstNotNullOfOrNull { root.findAccessibilityNodeInfosByViewId("$browser:id/$it").firstOrNull() }
        }
        private const val MAX_DUMP_NODES = 400
        private const val MAX_SCAN_NODES = 3_000
    }
}

/**
 * Builds a URL that Safari and Chromium resolve on their own: `#id` scrolls to the element, and a
 * Text Fragment (`:~:text=`) scrolls to and highlights the text, taking precedence when it matches.
 * Long text uses the `start,end` form. Pure, so it can be unit tested.
 */
internal fun continuationUrl(pageUrl: String, htmlId: String?, text: String?, prefix: String = "", suffix: String = ""): String {
    val base = pageUrl.substringBefore('#')
    val words = text.orEmpty().replace(Regex("\\s+"), " ").trim().split(' ').filter { it.isNotBlank() }
    val directive = when {
        words.isEmpty() -> null
        words.size <= 8 -> encodeTextFragment(words.joinToString(" "))
        else -> encodeTextFragment(words.take(4).joinToString(" ")) + "," + encodeTextFragment(words.takeLast(4).joinToString(" "))
    }
    val fragment = buildString {
        if (!htmlId.isNullOrBlank()) append(java.net.URLEncoder.encode(htmlId, "UTF-8"))
        if (directive != null) {
            append(":~:text=")
            if (prefix.isNotBlank()) append(encodeTextFragment(prefix)).append("-,")
            append(directive)
            if (suffix.isNotBlank()) append(",-").append(encodeTextFragment(suffix))
        }
    }
    return if (fragment.isEmpty()) base else "$base#$fragment"
}

/** Text Fragment component encoding: percent-encode everything except unreserved characters;
 *  `-`, `,` and `&` are syntax inside the directive and must always be encoded. */
internal fun encodeTextFragment(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("-", "%2D").replace("*", "%2A")
