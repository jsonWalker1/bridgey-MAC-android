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
import kotlinx.coroutines.launch
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
    private val stateScope = kotlinx.coroutines.MainScope()
    private var connectionWatch: kotlinx.coroutines.Job? = null

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
        chip = WebHandoffToolbarChip(
            this,
            onTap = { WebHandoff.perform(this, chipSource(), origin = "chip") },
            isConnected = { WebHandoff.macConnected(this) },
            log = ::log,
        ).also { it.requestUpdate(0) }
        current = java.lang.ref.WeakReference(this)
        // When Android starts the Bridgey process only to bind this service (after an update or a
        // reboot), nothing else starts the connection's foreground service, and Samsung's Freecess
        // then freezes the app in the background - the connection drops every few seconds and never
        // settles. A bound accessibility service may start a foreground service from the background;
        // BridgeyConnectionService itself stops again if Bridgey is turned off.
        runCatching { startForegroundService(Intent(this, BridgeyConnectionService::class.java)) }
            .onFailure { log("could not start the connection service: ${it.javaClass.simpleName}") }
        // The chip needs a connected Mac; connection changes produce no accessibility event.
        // The Mac's feature list (Web links on/off) arrives shortly after the connection itself.
        connectionWatch = stateScope.launch {
            val pairing = (application as BridgeyApplication).pairing
            kotlinx.coroutines.flow.merge(pairing.state, pairing.remoteFeatures).collect { chip?.requestUpdate(0) }
        }
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
        connectionWatch?.cancel()
        stateScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        chip?.requestUpdate()
        if (event.packageName?.toString() in READERS) return rememberReaderPosition()
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
        // Opening the share sheet (or tapping the chip) clears the browser selection; keep the last
        // real selection so it can still be handed off (it expires after two minutes).
        if (selected.isBlank() || source?.isEditable == true) return
        lastSelection = JSONObject()
            .put("nodeText", text.take(400))
            .put("from", from).put("to", to)
            .put("selected", selected.take(400))
            .put("viewId", source?.viewIdResourceName ?: JSONObject.NULL)
            .put("class", source?.className?.toString() ?: JSONObject.NULL)
        lastSelectionAt = android.os.SystemClock.elapsedRealtime()
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

    /** A selection made in the last two minutes, with up to three words of context on each side. */
    private fun freshSelection(): Triple<String, String, String>? {
        val selection = lastSelection ?: return null
        val selected = selection.optString("selected")
        if (selected.isBlank() || android.os.SystemClock.elapsedRealtime() - lastSelectionAt > 120_000) return null
        val nodeText = selection.optString("nodeText")
        val from = selection.optInt("from"); val to = selection.optInt("to")
        val prefix = nodeText.take(from.coerceIn(0, nodeText.length)).trim().split(Regex("\\s+")).takeLast(3).joinToString(" ")
        val suffix = nodeText.drop(to.coerceIn(0, nodeText.length)).trim().split(Regex("\\s+")).take(3).joinToString(" ")
        return Triple(selected, prefix, suffix)
    }

    /** Reading position: the first sentence of the first fully visible paragraph-sized text. */
    private fun readingText(root: AccessibilityNodeInfo): String? {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun flatten(node: AccessibilityNodeInfo) {
            if (nodes.size >= MAX_SCAN_NODES) return
            nodes += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::flatten)
        }
        flatten(root)
        val webRoot = nodes.firstOrNull { it.className == "android.webkit.WebView" } ?: return null
        val web = bounds(webRoot)
        val reading = nodes.firstOrNull { node ->
            val b = bounds(node)
            isDescendant(node, webRoot) && !node.isPassword && !node.isEditable &&
                b.top >= web.top && b.bottom <= web.bottom && node.isVisibleToUser &&
                label(node).split(Regex("\\s+")).size >= 6
        }
        return reading?.let(::label)?.split(Regex("(?<=[.!?])\\s"))?.firstOrNull()
    }

    /**
     * Books Handoff: the reader app's window (title = book title) and its position description
     * ("CHAPTER X. …, stránka 64 z 91"; the scrub label "64 / 91" when the toolbar is shown).
     * Only the position/title strings are kept; page text is not collected.
     */
    private fun readerSourceOnScreen(): BookSource? {
        val window = windows.firstOrNull { w ->
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                w.root?.packageName?.toString() in READERS
        } ?: return null
        val root = window.root ?: return null
        val title = window.title?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val positions = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 14 || positions.size > 60) return
            (node.contentDescription ?: node.text)?.toString()?.takeIf { it.length < 200 && it.any(Char::isDigit) }?.let(positions::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        val position = parseReaderPosition(positions)
        // At the start of a book the "chapter" in the description is the book title itself.
        val chapter = position?.first?.takeIf { it != title }
        log("READER title=${title != null} chapter=${chapter != null} page=${position?.second}")
        return BookSource(title, chapter, position?.second, position?.third, app = root.packageName?.toString())
    }

    /** A browser page window is on screen (behind the share sheet / shade). */
    internal fun browserOnScreen(): Boolean = windows.any { w ->
        w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
            w.root?.let { it.packageName?.toString() in BROWSERS && urlField(it) != null } == true
    }

    // The Quick Settings shade covers the reader when the tile is tapped, and a fully covered app
    // window is not reported to accessibility - so the last position seen while the reader was on
    // screen is remembered (only title/chapter/page strings, at most every 1.5 s).
    private var lastReader: BookSource? = null
    private var lastReaderAt = 0L
    private var readerCheckQueued = false

    private fun rememberReaderPosition() {
        if (readerCheckQueued) return
        readerCheckQueued = true
        android.os.Handler(mainLooper).postDelayed({
            readerCheckQueued = false
            readerSourceOnScreen()?.let { lastReader = it; lastReaderAt = android.os.SystemClock.elapsedRealtime() }
        }, 1_500)
    }

    /** The reader on screen, else the position remembered from it in the last five minutes. */
    internal fun readerSource(): BookSource? =
        readerSourceOnScreen() ?: lastReader?.takeIf { android.os.SystemClock.elapsedRealtime() - lastReaderAt < 5 * 60_000 }
            ?.also { log("READER using remembered position") }

    /** Browser chip: the page in front, a fresh selection, else the reading position. */
    private fun chipSource(): WebHandoffSource? {
        val root = browserRoot() ?: return null
        val url = pageUrl(root) ?: return null
        val sel = freshSelection()
        return WebHandoffSource(url, sel?.first, sel?.second.orEmpty(), sel?.third.orEmpty(),
            readingText = if (sel == null) readingText(root) else null)
    }

    /**
     * Share → "Continue on Mac": enrich the shared URL with context only when it is the same page
     * the browser shows (or last showed); shared text without a URL is a selection on that page.
     */
    internal fun shareSource(sharedUrl: String?, sharedText: String?): WebHandoffSource? {
        val root = browserRoot()
        val known = root?.let(::pageUrl) ?: lastUrlByBrowser.values.lastOrNull()
        if (sharedUrl == null) {
            val page = known ?: return null
            val text = sharedText?.trim()?.takeIf { it.isNotEmpty() && it.length <= 500 } ?: return WebHandoffSource(page)
            val sel = freshSelection()?.takeIf { it.first.trim() == text }
            return WebHandoffSource(page, text, sel?.second.orEmpty(), sel?.third.orEmpty())
        }
        if (known == null || !samePage(sharedUrl, known)) {
            log("SHARE no page match (known=${known != null})")
            return WebHandoffSource(sharedUrl)
        }
        val sel = freshSelection()
        return WebHandoffSource(sharedUrl, sel?.first, sel?.second.orEmpty(), sel?.third.orEmpty(),
            readingText = if (sel == null) root?.let(::readingText) else null)
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

    // The browser's selection menu is a separate window of the same package; prefer the window that
    // has the address field (the page itself).
    private fun browserRoot(): AccessibilityNodeInfo? {
        val roots = windows.mapNotNull { it.root }.filter { it.packageName?.toString() in BROWSERS }
        return roots.firstOrNull { urlField(it) != null } ?: roots.firstOrNull()
            ?: rootInActiveWindow?.takeIf { it.packageName?.toString() in BROWSERS }
    }

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
        /** The running service, for Share → "Continue on Mac" context (null when not enabled). */
        @Volatile internal var current: java.lang.ref.WeakReference<WebHandoffPocService>? = null
        val BROWSERS = setOf("com.brave.browser", "com.android.chrome", "com.sec.android.app.sbrowser")
        /** Books Handoff Alpha: reader apps whose position may be read (on an explicit tap only). */
        val READERS = setOf("com.google.android.apps.books")
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
