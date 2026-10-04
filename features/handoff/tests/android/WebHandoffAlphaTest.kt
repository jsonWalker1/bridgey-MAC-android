package dev.bridgey.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WEB HANDOFF ALPHA: one handoff operation for the browser chip and Share → "Continue on Mac". */
class WebHandoffAlphaTest {
    private val page = "https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Overview"

    @Test fun selectionWinsOverReadingPositionAndCarriesContext() {
        val plan = planWebHandoff(WebHandoffSource(page, "HTTP flow", "Components of", "When a client", readingText = "ignored"))!!
        assertEquals(WebHandoffKind.SELECTION, plan.kind)
        assertEquals("$page#:~:text=Components%20of-,HTTP%20flow,-When%20a%20client", plan.url)
    }

    @Test fun readingPositionWhenNothingIsSelected() {
        val plan = planWebHandoff(WebHandoffSource(page, readingText = "Clients and servers communicate"))!!
        assertEquals(WebHandoffKind.READING_POSITION, plan.kind)
        assertTrue(plan.url.endsWith("#:~:text=Clients%20and%20servers%20communicate"))
    }

    @Test fun contextThatCannotBeALinkFallsBackToThePlainUrl() {
        // A fragment that pushes the link over the 4,096-byte limit must not block the handoff.
        val longText = (1..4).joinToString(" ") { "x".repeat(1500) }
        val plan = planWebHandoff(WebHandoffSource(page, selectedText = longText, readingText = longText))!!
        assertEquals(WebHandoffKind.URL_ONLY, plan.kind)
        assertEquals(page, plan.url)
    }

    @Test fun aBrowserTextFragmentLinkIsKeptAndReportedAsSuch() {
        val shared = "$page#:~:text=Clients"
        assertEquals(WebHandoffPlan(shared, WebHandoffKind.BROWSER_TEXT_FRAGMENT), planWebHandoff(WebHandoffSource(shared)))
        // ...and it beats our own guess of the reading position.
        assertEquals(WebHandoffKind.BROWSER_TEXT_FRAGMENT, planWebHandoff(WebHandoffSource(shared, readingText = "Clients and servers"))!!.kind)
    }

    @Test fun nonWebPagesAreNeverSent() {
        assertNull(planWebHandoff(WebHandoffSource("javascript:alert(1)", readingText = "x")))
        assertNull(planWebHandoff(WebHandoffSource("file:///sdcard/a.html")))
    }

    @Test fun sharedTextYieldsTheUrlOrNothing() {
        assertEquals("https://example.com/a?b=1", sharedUrl("Example page https://example.com/a?b=1"))
        assertEquals("https://example.com/a", sharedUrl("see https://example.com/a)."))
        assertNull(sharedUrl("just some selected words"))
        assertNull(sharedUrl(null))
    }

    @Test fun samePageIgnoresWhatTheAddressBarElides() {
        assertTrue(samePage("https://www.example.com/a/", "example.com/a"))
        assertTrue(samePage("https://example.com/a#section", "https://example.com/a"))
        assertFalse(samePage("https://example.com/a", "https://example.com/b"))
    }

    @Test fun outcomeMessagesNeverClaimDeliveryWithoutConfirmation() {
        assertEquals("Mac did not confirm — try again", outcomeMessage(null, WebHandoffKind.URL_ONLY))
        assertTrue(outcomeMessage("Link delivered — waiting for the user to open it", WebHandoffKind.SELECTION).startsWith("Sent page + selected text"))
        assertTrue(outcomeMessage("Link declined — check settings or dismiss the previous link", WebHandoffKind.URL_ONLY).startsWith("Mac declined"))
        assertEquals("Not connected — request not sent", outcomeMessage("Not connected — request not sent", WebHandoffKind.URL_ONLY))
    }

    @Test fun aStaleOutcomeIsNeverReportedForANewHandoff() {
        // A pending earlier handoff leaves the old status in place; it must read as "not sent".
        assertEquals("Not sent — the previous handoff is still in progress",
            notSentMessage("Link delivered — waiting for the user to open it"))
        assertEquals("Feature is unavailable on one of your devices", notSentMessage("Feature is unavailable on one of your devices"))
    }

    @Test fun shareTargetOnlyAcceptsSharedText() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val share = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .first { it.getAttributeNS(ns, "name") == ".WebHandoffShareActivity" }
        val actions = share.getElementsByTagName("action").let { l -> (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "name") } }
        val types = share.getElementsByTagName("data").let { l -> (0 until l.length).map { (l.item(it) as org.w3c.dom.Element).getAttributeNS(ns, "mimeType") } }
        assertEquals(listOf("android.intent.action.SEND"), actions)
        assertEquals(listOf("text/plain"), types)
    }
}
