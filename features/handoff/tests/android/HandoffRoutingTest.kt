package dev.bridgey.android

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MD-7 handoff per peer: explicit Mac on send, sender on receive, media stays with the routed peer. */
class HandoffRoutingTest {
    private val a = "10000000-0000-4000-8000-00000000000a"
    private val b = "20000000-0000-4000-8000-00000000000b"

    private class Sent(val kind: String, val payload: JSONObject, val deviceId: String)

    private fun quick(
        sent: MutableList<Sent> = mutableListOf(),
        connected: Set<String> = setOf(a, b),
        granted: (String) -> Boolean = { true },
    ): Pair<QuickActions, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        val context = unsafe.allocateInstance(android.content.ContextWrapper::class.java) as Context
        return QuickActions(
            context, scope, available = { true }, send = { _, _ -> false },
            sendTo = { kind, payload, deviceId -> sent += Sent(kind, payload, deviceId); deviceId in connected },
            sendable = { it in connected }, receivable = { true }, granted = granted,
            peerName = { if (it == a) "MacBook Air M4" else "MacBook Air – pracovní" },
        ) to scope
    }

    private fun offer(url: String) = JSONObject().put("version", 1).put("requestId", java.util.UUID.randomUUID().toString())
        .put("feature", "links").put("action", "offer").put("value", url)

    private fun result(id: String) = JSONObject().put("version", 1).put("requestId", id).put("feature", "links").put("accepted", true)

    @Test fun linksFromAnyPeerMediaOnlyFromTheRoutedOne() {
        assertTrue(QuickPayloadGate.accepts("links", fromRoutedPeer = false))
        assertFalse("a non-routed peer never controls media", QuickPayloadGate.accepts("media", fromRoutedPeer = false))
        assertTrue(QuickPayloadGate.accepts("media", fromRoutedPeer = true))
        assertFalse(QuickPayloadGate.accepts(null, fromRoutedPeer = true))
        assertFalse(QuickPayloadGate.accepts("files", fromRoutedPeer = true))
    }

    @Test fun oneMacSendsDirectlySeveralAskNoneSendsNothing() {
        assertEquals(HandoffTarget.None, HandoffTarget.resolve(emptyList()))
        assertEquals(HandoffTarget.Send(a), HandoffTarget.resolve(listOf(a)))
        assertEquals(HandoffTarget.Choose(listOf(a, b)), HandoffTarget.resolve(listOf(a, b)))
    }

    @Test fun aHandoffGoesToTheChosenMacOnlyAndOnlyItConfirmsIt() {
        val sent = mutableListOf<Sent>()
        val (q, scope) = quick(sent)
        assertTrue(q.sendBook(b, "{\"version\":1,\"title\":\"Alice\"}"))
        assertEquals(listOf(b), sent.map { it.deviceId })
        val id = sent.single().payload.getString("requestId")
        q.receive("quick.result", result(id), a) // the other Mac answering with B's id
        assertEquals("Sending…", q.status.value)
        q.receive("quick.result", result(id), b)
        assertTrue(q.status.value!!.startsWith("Link delivered to MacBook Air – pracovní"))
        scope.cancel()
    }

    @Test fun aPendingHandoffToOneMacDoesNotBlockAnother() {
        val sent = mutableListOf<Sent>()
        val (q, scope) = quick(sent)
        assertTrue(q.sendLink(a, "https://example.com/a"))
        assertTrue("B is independent of A's pending handoff", q.sendLink(b, "https://example.com/b"))
        assertFalse("but A has to wait for its own", q.sendLink(a, "https://example.com/a2"))
        assertEquals(listOf(a, b), sent.map { it.deviceId })
        scope.cancel()
    }

    @Test fun aDisconnectedMacGetsNothingAndNoOtherMacIsUsed() {
        val sent = mutableListOf<Sent>()
        val (q, scope) = quick(sent, connected = setOf(b))
        assertFalse(q.sendLink(a, "https://example.com/a"))
        assertTrue(sent.isEmpty())
        assertEquals("Web links are unavailable on MacBook Air M4", q.status.value)
        scope.cancel()
    }

    @Test fun receivedLinksArePerMacAndSurviveDisconnectAndRoutingChanges() {
        val sent = mutableListOf<Sent>()
        val (q, scope) = quick(sent)
        q.receive("quick.request", offer("https://a.example/"), a)
        q.receive("quick.request", offer("https://b.example/"), b)
        q.receive("quick.request", offer("https://a2.example/"), a) // A already has one
        assertEquals(setOf(a, b), q.receivedLinks.value.keys)
        assertEquals("MacBook Air M4", q.receivedLinks.value.getValue(a).peerName)
        assertEquals(listOf(true, true, false), sent.map { it.payload.getBoolean("accepted") })
        assertEquals(listOf(a, b, a), sent.map { it.deviceId }, "each result goes back to its sender")
        q.deviceEnded(a)
        q.routedPeerChanged()
        assertEquals(setOf(a, b), q.receivedLinks.value.keys)
        q.dismissLink(a)
        assertEquals(setOf(b), q.receivedLinks.value.keys)
        scope.cancel()
    }

    @Test fun turningTheGrantOffDropsThatMacsLink() {
        var allowB = true
        val (q, scope) = quick(granted = { it != b || allowB })
        q.receive("quick.request", offer("https://a.example/"), a)
        q.receive("quick.request", offer("https://b.example/"), b)
        allowB = false
        q.policyChanged()
        assertEquals(setOf(a), q.receivedLinks.value.keys)
        scope.cancel()
    }

    @Test fun aDisconnectEndsOnlyThatMacsPendingHandoff() {
        val sent = mutableListOf<Sent>()
        val (q, scope) = quick(sent)
        assertTrue(q.sendLink(a, "https://example.com/a"))
        assertTrue(q.sendLink(b, "https://example.com/b"))
        q.deviceEnded(a)
        assertTrue("A can be retried after reconnecting", q.sendLink(a, "https://example.com/a"))
        assertFalse("B's handoff is still pending", q.sendLink(b, "https://example.com/b2"))
        scope.cancel()
    }

    @Test fun anOutcomeIsReportedForItsOwnMacOnly() {
        val sent = mutableListOf<Sent>()
        val (q, scope) = quick(sent)
        assertTrue(q.sendLink(a, "https://example.com/a"))
        assertTrue(q.sendLink(b, "https://example.com/b"))
        q.receive("quick.result", result(sent.first { it.deviceId == a }.payload.getString("requestId")), a)
        assertTrue(q.linkStatus.value.getValue(a).startsWith("Link delivered to MacBook Air M4"))
        assertEquals("Sending…", q.linkStatus.value.getValue(b), "B's handoff is still unconfirmed, not 'delivered'")
        q.deviceEnded(a)
        assertEquals("Sending…", q.linkStatus.value.getValue(b), "A's disconnect is not B's outcome")
        scope.cancel()
    }

    private fun assertEquals(expected: Any, actual: Any, message: String) = org.junit.Assert.assertEquals(message, expected, actual)
}
