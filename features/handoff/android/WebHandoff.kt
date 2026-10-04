package dev.bridgey.android

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WEB HANDOFF ALPHA - the one operation behind both entry points (the browser chip and
 * Share → "Continue on Mac"). Entry points only describe the source; this decides what to send.
 *
 * Priority: an explicit text selection (with the words around it) > a text fragment the browser
 * already put in the shared link > the reading position (first visible sentence) > the plain URL. Context never blocks the handoff: anything that cannot be
 * turned into a valid link falls back to the page URL. Only the continuation URL leaves the phone,
 * through the existing "send link" quick action, which the Mac queues for an explicit Open.
 */
internal data class WebHandoffSource(
    val pageUrl: String,
    val selectedText: String? = null,
    val selectionPrefix: String = "",
    val selectionSuffix: String = "",
    val readingText: String? = null,
)

internal enum class WebHandoffKind { SELECTION, READING_POSITION, BROWSER_TEXT_FRAGMENT, URL_ONLY }

internal data class WebHandoffPlan(val url: String, val kind: WebHandoffKind)

internal fun planWebHandoff(source: WebHandoffSource): WebHandoffPlan? {
    val page = validatedWebLink(source.pageUrl) ?: return null
    val selected = source.selectedText?.trim().orEmpty()
    if (selected.isNotEmpty()) {
        validatedWebLink(continuationUrl(page, null, selected, source.selectionPrefix, source.selectionSuffix))
            ?.let { return WebHandoffPlan(it, WebHandoffKind.SELECTION) }
    }
    // A browser's own "share with highlight" link already points at what the user marked.
    if (page.contains("#:~:text=")) return WebHandoffPlan(page, WebHandoffKind.BROWSER_TEXT_FRAGMENT)
    val reading = source.readingText?.trim().orEmpty()
    if (reading.isNotEmpty()) {
        validatedWebLink(continuationUrl(page, null, reading))?.let { return WebHandoffPlan(it, WebHandoffKind.READING_POSITION) }
    }
    return WebHandoffPlan(page, WebHandoffKind.URL_ONLY)
}

private val URL_IN_TEXT = Regex("""https?://[^\s<>"]+""")

/** Browsers share "Title https://…" or just the URL; selected text is shared without a URL. */
internal fun sharedUrl(text: String?): String? =
    text?.let { URL_IN_TEXT.find(it)?.value?.trimEnd('.', ',', ')', ']') }?.let(::validatedWebLink)

/** Same page, ignoring scheme, "www.", fragment and a trailing slash (the URL bar elides those). */
internal fun samePage(a: String, b: String): Boolean {
    fun key(u: String) = u.trim().lowercase()
        .substringBefore('#')
        .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        .trimEnd('/')
    return key(a) == key(b)
}

internal object WebHandoff {
    private val scope = MainScope()

    fun macConnected(context: Context): Boolean {
        val pairing = (context.applicationContext as BridgeyApplication).pairing
        return pairing.state.value is PairingState.Connected && pairing.isFeatureAvailable(BridgeyFeature.LINKS)
    }

    /** Sends the best continuation for [source] (or reports why not) and shows the outcome. */
    fun perform(context: Context, source: WebHandoffSource?, origin: String) {
        val app = context.applicationContext
        val plan = source?.let(::planWebHandoff)
        if (plan == null) return toast(app, "Nothing to continue on Mac — no web page address")
        if (!macConnected(app)) return toast(app, "Mac not connected — nothing sent")
        val quick = (app as BridgeyApplication).pairing.quickActions
        android.util.Log.i("BridgeyWebHandoff", "HANDOFF origin=$origin kind=${plan.kind} length=${plan.url.length}")
        val sent = quick.sendLink(plan.url)
        // A busy feature (an earlier handoff not yet confirmed) leaves the old status in place;
        // it must never be reported as this handoff's outcome.
        if (!sent) return toast(app, notSentMessage(quick.status.value))
        toast(app, "Sending to Mac…")
        scope.launch {
            val status = withTimeoutOrNull(9_000) { quick.status.first { it != null && it != "Sending…" } }
            toast(app, outcomeMessage(status, plan.kind))
        }
    }

    private fun toast(context: Context, message: String) {
        android.util.Log.i("BridgeyWebHandoff", "FEEDBACK $message")
        android.os.Handler(context.mainLooper).post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}

internal fun outcomeMessage(status: String?, kind: WebHandoffKind): String {
    val what = when (kind) {
        WebHandoffKind.SELECTION -> "page + selected text"
        WebHandoffKind.READING_POSITION -> "page + reading position"
        WebHandoffKind.BROWSER_TEXT_FRAGMENT -> "page + highlighted text"
        WebHandoffKind.URL_ONLY -> "page"
    }
    return when {
        status == null -> "Mac did not confirm — try again"
        status.startsWith("Link delivered") -> "Sent $what — open it from Bridgey on your Mac"
        status.startsWith("Link declined") -> "Mac declined — dismiss the previous link in Bridgey on the Mac first"
        else -> status
    }
}

/** sendLink did not send: either a reason it set itself, or an earlier handoff is still pending. */
internal fun notSentMessage(status: String?): String = when {
    status == null || status == "Sending…" || status.startsWith("Link delivered") || status.startsWith("Link declined") ||
        status.startsWith("The other device") -> "Not sent — the previous handoff is still in progress"
    else -> status
}
