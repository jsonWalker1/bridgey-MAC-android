package dev.bridgey.android

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BOOKS HANDOFF ALPHA: resolver, fallbacks, reader position parsing, share routing and the
 *  quick-action send/ack behaviour it relies on (with a fake book source, no real reader). */
class BooksHandoffAlphaTest {
    private val alice = "Alice's Adventures in Wonderland"

    // ---- resolver + fallbacks ----------------------------------------------------------------

    @Test fun fullContextIsSentWithEveryField() {
        val plan = planBooksHandoff(BookSource(alice, "CHAPTER X. The Lobster Quadrille", 64, 91, "Will you, won’t you", "com.google.android.apps.books"))!!
        val json = JSONObject(plan.payload)
        assertEquals(1, json.getInt("version"))
        assertEquals(alice, json.getString("title"))
        assertEquals("CHAPTER X. The Lobster Quadrille", json.getString("chapter"))
        assertEquals(64, json.getInt("page")); assertEquals(91, json.getInt("pages"))
        assertEquals("Will you, won’t you", json.getString("quote"))
        assertTrue(plan.hasPosition); assertTrue(plan.hasQuote)
    }

    @Test fun missingContextIsLeftOutNotInvented() {
        val titleOnly = JSONObject(planBooksHandoff(BookSource(title = alice))!!.payload)
        assertEquals(setOf("version", "title"), titleOnly.keys().asSequence().toSet())
        val quoteOnly = planBooksHandoff(BookSource(quote = "  So she set to work,\n and very soon  "))!!
        assertEquals("So she set to work, and very soon", JSONObject(quoteOnly.payload).getString("quote"))
        assertFalse(quoteOnly.hasPosition)
    }

    @Test fun nothingToContinueWithoutTitleOrQuote() {
        assertNull(planBooksHandoff(BookSource(chapter = "CHAPTER I.", page = 3, pages = 91)))
        assertNull(planBooksHandoff(BookSource(title = "   ", quote = "")))
    }

    @Test fun impossiblePagesAreDropped() {
        assertFalse(JSONObject(planBooksHandoff(BookSource(alice, page = 95, pages = 91))!!.payload).has("page"))
        assertFalse(JSONObject(planBooksHandoff(BookSource(alice, page = 0, pages = 91))!!.payload).has("page"))
        // A page without a known total is still useful; a total without a page is not sent.
        assertEquals(7, JSONObject(planBooksHandoff(BookSource(alice, page = 7))!!.payload).getInt("page"))
        assertFalse(JSONObject(planBooksHandoff(BookSource(alice, pages = 91))!!.payload).has("pages"))
    }

    @Test fun longFieldsAreTruncatedAndPayloadStaysSmall() {
        val plan = planBooksHandoff(BookSource("T".repeat(1000), "C".repeat(1000), 1, 2, "Q".repeat(5000)))!!
        val json = JSONObject(plan.payload)
        assertEquals(BOOK_TITLE_MAX, json.getString("title").length)
        assertEquals(BOOK_QUOTE_MAX, json.getString("quote").length)
        assertTrue(plan.payload.toByteArray().size <= BOOK_PAYLOAD_MAX)
    }

    // ---- reader position (Play Books accessibility strings) ----------------------------------

    @Test fun playBooksDescriptionAndScrubLabel() {
        assertEquals(Triple("CHAPTER X. The Lobster Quadrille", 64, 91),
            parseReaderPosition(listOf("CHAPTER X. The Lobster Quadrille, stránka 64 z 91")))
        assertEquals(Triple("CHAPTER II. The Pool of Tears", 9, 91),
            parseReaderPosition(listOf("CHAPTER II. The Pool of Tears, page 9 of 91")))
        // The description can be stale (page 1) while the scrub label is current.
        assertEquals(Triple("CHAPTER X. The Lobster Quadrille", 64, 91),
            parseReaderPosition(listOf("CHAPTER X. The Lobster Quadrille, stránka 1 z 91", "64 / 91")))
    }

    @Test fun genericPositionsAndNoise() {
        assertEquals(Triple(null, 12, 300), parseReaderPosition(listOf("Settings", "12 / 300")))
        assertNull(parseReaderPosition(listOf("The year 1865", "page 92 of 91", "Chapter 3")))
    }

    // ---- share routing --------------------------------------------------------------------------

    @Test fun sharedTextGoesToTheRightHandoff() {
        assertEquals(ShareRoute.WEB, shareRoute(hasUrl = true, senderPackage = "com.amazon.kindle", browserOnScreen = false))
        assertEquals(ShareRoute.WEB, shareRoute(false, "com.brave.browser", false))
        assertEquals(ShareRoute.BOOK, shareRoute(false, "com.flyersoft.moonreader", true))
        assertEquals(ShareRoute.WEB, shareRoute(false, null, browserOnScreen = true))
        assertEquals(ShareRoute.BOOK, shareRoute(false, null, browserOnScreen = false))
    }

    // ---- outcome messages ---------------------------------------------------------------------

    @Test fun outcomeNeverClaimsDeliveryWithoutConfirmation() {
        val plan = planBooksHandoff(BookSource(alice, page = 64, pages = 91))!!
        assertEquals("Mac did not confirm — try again", bookOutcomeMessage(null, plan))
        assertEquals("Sent book + page — continue from Bridgey on your Mac",
            bookOutcomeMessage("Link delivered — waiting for the user to open it", plan))
        assertTrue(bookOutcomeMessage("Link declined — check settings or dismiss the previous link", plan).startsWith("Mac declined"))
    }

    // ---- quick-action send / ack (the real QuickActions class) --------------------------------

    private fun quick(available: Boolean = true, sendOk: Boolean = true, sent: MutableList<JSONObject> = mutableListOf()): Pair<QuickActions, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // QuickActions only needs a Context to open received links, which these tests never do.
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        val context = unsafe.allocateInstance(android.content.ContextWrapper::class.java) as Context
        return QuickActions(context, scope, { available }, { _, payload -> sent += payload; sendOk }) to scope
    }

    private fun result(id: String, accepted: Boolean) =
        JSONObject().put("version", 1).put("requestId", id).put("feature", "links").put("accepted", accepted)

    @Test fun bookIsSentAsTheBookActionOfWebLinks() {
        val sent = mutableListOf<JSONObject>()
        val (q, scope) = quick(sent = sent)
        assertTrue(q.sendBook(planBooksHandoff(BookSource(alice, page = 3, pages = 91))!!.payload))
        assertEquals("links", sent.single().getString("feature"))
        assertEquals("book", sent.single().getString("action"))
        assertEquals("Sending…", q.status.value)
        scope.cancel()
    }

    @Test fun offlineOrDisabledReportsNotSent() {
        val (offline, s1) = quick(sendOk = false)
        assertFalse(offline.sendBook("{\"version\":1,\"title\":\"x\"}"))
        assertEquals("Not connected — request not sent", notSentMessage(offline.status.value))
        val (disabled, s2) = quick(available = false)
        assertFalse(disabled.sendBook("{\"version\":1,\"title\":\"x\"}"))
        assertEquals("Feature is unavailable on one of your devices", disabled.status.value)
        s1.cancel(); s2.cancel()
    }

    @Test fun secondHandoffWhileFirstIsPendingIsNotSentAndSaysSo() {
        val sent = mutableListOf<JSONObject>()
        val (q, scope) = quick(sent = sent)
        assertTrue(q.sendBook("{\"version\":1,\"title\":\"first\"}"))
        // The old "Sending…" status must not be mistaken for this handoff being sent.
        assertFalse(q.sendLink("https://example.com/"))
        assertEquals(1, sent.size)
        assertEquals("Not sent — the previous handoff is still in progress", notSentMessage(q.status.value))
        scope.cancel()
    }

    @Test fun staleOrDuplicateAcksAreIgnored() {
        val sent = mutableListOf<JSONObject>()
        val (q, scope) = quick(sent = sent)
        assertTrue(q.sendBook("{\"version\":1,\"title\":\"x\"}"))
        val id = sent.single().getString("requestId")
        q.receive("quick.result", result(java.util.UUID.randomUUID().toString(), accepted = true)) // stale id
        assertEquals("Sending…", q.status.value)
        q.receive("quick.result", result(id, accepted = false))
        assertTrue(q.status.value!!.startsWith("Link declined"))
        q.receive("quick.result", result(id, accepted = true)) // duplicate ack after the first answer
        assertTrue(q.status.value!!.startsWith("Link declined"))
        scope.cancel()
    }

    @Test fun disconnectClearsPendingSoTheNextHandoffCanBeSent() {
        val sent = mutableListOf<JSONObject>()
        val (q, scope) = quick(sent = sent)
        assertTrue(q.sendBook("{\"version\":1,\"title\":\"x\"}"))
        q.reset() // connection lost / reconnect
        assertNull(q.status.value)
        assertTrue(q.sendBook("{\"version\":1,\"title\":\"y\"}"))
        assertEquals(2, sent.size)
        scope.cancel()
    }
}
