package dev.bridgey.android

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * BOOKS HANDOFF ALPHA - "continue reading on the Mac". One operation behind both entry points
 * (the Quick Settings tile reading the reader app via accessibility, and Share → "Continue on Mac"
 * with a quote from any reader). Entry points only describe the source; this decides what to send.
 *
 * Context, best first: book title, page / progress, chapter, quote. Whatever is missing is simply
 * left out; with neither a title nor a quote there is nothing to continue and nothing is sent.
 * Sent as the "book" action of the existing Web links quick action; the Mac shows it as a card in
 * the Bridgey panel (no automation of Apple Books).
 */
internal data class BookSource(
    val title: String? = null,
    val chapter: String? = null,
    val page: Int? = null,
    val pages: Int? = null,
    val quote: String? = null,
    val app: String? = null,
)

internal data class BookPlan(val payload: String, val hasPosition: Boolean, val hasQuote: Boolean)

internal const val BOOK_TITLE_MAX = 200
internal const val BOOK_CHAPTER_MAX = 200
internal const val BOOK_QUOTE_MAX = 500
internal const val BOOK_PAYLOAD_MAX = 4096

private fun String?.clean(max: Int): String? =
    this?.replace(Regex("\\s+"), " ")?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

internal fun planBooksHandoff(source: BookSource): BookPlan? {
    val title = source.title.clean(BOOK_TITLE_MAX)
    val quote = source.quote.clean(BOOK_QUOTE_MAX)
    if (title == null && quote == null) return null
    val pages = source.pages?.takeIf { it in 1..100_000 }
    val page = source.page?.takeIf { it >= 1 && (pages == null || it <= pages) }
    val json = JSONObject().put("version", 1)
    title?.let { json.put("title", it) }
    source.chapter.clean(BOOK_CHAPTER_MAX)?.let { json.put("chapter", it) }
    page?.let { json.put("page", it) }
    if (page != null && pages != null) json.put("pages", pages)
    quote?.let { json.put("quote", it) }
    source.app.clean(64)?.let { json.put("app", it) }
    val payload = json.toString()
    if (payload.toByteArray().size > BOOK_PAYLOAD_MAX) return null
    return BookPlan(payload, hasPosition = page != null, hasQuote = quote != null)
}

private val POSITION_PATTERNS = listOf(
    // Google Play Books: "CHAPTER X. The Lobster Quadrille, stránka 64 z 91" / "…, page 64 of 91"
    Regex("""^(?:(.+?),\s+)?(?:stránka|strana|page)\s+(\d+)\s+(?:z|of)\s+(\d+)$""", RegexOption.IGNORE_CASE),
    // Scrub bar / generic readers: "64 / 91"
    Regex("""^()\s*(\d+)\s*/\s*(\d+)\s*$"""),
)

/** Reading position from reader UI texts (accessibility), best match wins: chapter, page, pages. */
internal fun parseReaderPosition(texts: List<String>): Triple<String?, Int, Int>? {
    var best: Triple<String?, Int, Int>? = null
    for (raw in texts) {
        val text = raw.replace(' ', ' ').trim()
        for (pattern in POSITION_PATTERNS) {
            val m = pattern.matchEntire(text) ?: continue
            val page = m.groupValues[2].toIntOrNull() ?: continue
            val pages = m.groupValues[3].toIntOrNull() ?: continue
            if (page < 1 || pages < page) continue
            val chapter = m.groupValues[1].takeIf { it.isNotBlank() }
            // Prefer a match that names the chapter; the scrub label ("64 / 91") is fresher for the page.
            best = when {
                best == null -> Triple(chapter, page, pages)
                chapter == null -> Triple(best.first, page, pages)
                else -> Triple(chapter, best.second, best.third)
            }
        }
    }
    return best
}

internal object BooksHandoff {
    private val scope = MainScope()

    fun perform(context: Context, source: BookSource?, origin: String) {
        val app = context.applicationContext
        val plan = source?.let(::planBooksHandoff)
        if (plan == null) return toast(app, "Nothing to continue — open a book in your reader first")
        if (!WebHandoff.macConnected(app)) return toast(app, "Mac not connected — nothing sent")
        val quick = (app as BridgeyApplication).pairing.quickActions
        android.util.Log.i("BridgeyBooksHandoff", "HANDOFF origin=$origin position=${plan.hasPosition} quote=${plan.hasQuote} bytes=${plan.payload.length}")
        val sent = quick.sendBook(plan.payload)
        // A busy feature (an earlier handoff not yet confirmed) leaves the old status in place;
        // it must never be reported as this handoff's outcome.
        if (!sent) return toast(app, notSentMessage(quick.status.value))
        toast(app, "Sending to Mac…")
        scope.launch {
            val status = withTimeoutOrNull(9_000) { quick.status.first { it != null && it != "Sending…" } }
            toast(app, bookOutcomeMessage(status, plan))
        }
    }

    fun toast(context: Context, message: String) {
        android.util.Log.i("BridgeyBooksHandoff", "FEEDBACK $message")
        android.os.Handler(context.mainLooper).post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}

internal fun bookOutcomeMessage(status: String?, plan: BookPlan): String {
    val what = listOfNotNull("book", "page".takeIf { plan.hasPosition }, "quote".takeIf { plan.hasQuote }).joinToString(" + ")
    return when {
        status == null -> "Mac did not confirm — try again"
        status.startsWith("Link delivered") -> "Sent $what — continue from Bridgey on your Mac"
        // An older Mac app declines the unknown "book" action as well.
        status.startsWith("Link declined") -> "Mac declined — dismiss the previous item in Bridgey on the Mac (or update Bridgey there)"
        else -> status
    }
}

internal enum class ShareRoute { WEB, BOOK }

/**
 * Share → "Continue on Mac": a URL is always web; text without a URL is a browser selection when
 * it comes from a browser (or, if the sender is unknown, while a browser is on screen), otherwise a
 * quote from a book.
 */
internal fun shareRoute(hasUrl: Boolean, senderPackage: String?, browserOnScreen: Boolean): ShareRoute = when {
    hasUrl -> ShareRoute.WEB
    senderPackage != null && senderPackage in WebHandoffPocService.BROWSERS -> ShareRoute.WEB
    senderPackage != null -> ShareRoute.BOOK
    browserOnScreen -> ShareRoute.WEB
    else -> ShareRoute.BOOK
}
