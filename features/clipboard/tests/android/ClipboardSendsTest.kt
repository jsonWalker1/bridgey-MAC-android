package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MD-5 clipboard: explicit sends to one peer, tracked per (deviceId, messageId). */
class ClipboardSendsTest {
    private val a = "10000000-0000-4000-8000-00000000000a"
    private val b = "20000000-0000-4000-8000-00000000000b"
    private class FakeSession { val received = mutableListOf<String>() }

    private fun twoPeers() = PeerSessionManager<FakeSession>("50000000-0000-4000-8000-000000000000").apply {
        for (id in listOf(a, b)) {
            val session = FakeSession()
            addPending(session, initiatedLocally = false, expectedDeviceId = null)
            identify(session, id)
            markConnected(session)
        }
    }

    private val items = listOf(
        DeviceListItem(a, "MacBook Air – pracovní", DevicePlatform.MACOS, DeviceKind.COMPUTER, true),
        DeviceListItem(b, "Tomáš MacBook Air M4 můj", DevicePlatform.MACOS, DeviceKind.COMPUTER, true),
    )

    @Test
    fun theSelectedPeerReceivesAndTheRoutedPeerDoesNot() {
        val manager = twoPeers()
        for ((selected, routed) in listOf(a to b, b to a)) {
            val target = SelectedDeviceContext.make(items, selected, routed).selected!!.deviceId
            assertEquals(selected, target)
            assertTrue(manager.deliver(target) { it.received += "clipboard.update"; true })
        }
        assertEquals("A got exactly its own send", listOf("clipboard.update"), manager.connectedSession(a)!!.received)
        assertEquals("B got exactly its own send", listOf("clipboard.update"), manager.connectedSession(b)!!.received)
    }

    @Test
    fun changingRoutingDoesNotChangeTheClipboardTarget() {
        for (routed in listOf(b, a, b)) assertEquals(a, SelectedDeviceContext.make(items, a, routed).selected?.deviceId)
    }

    @Test
    fun aDisconnectedSelectedPeerIsNotReplacedByAnotherPeer() {
        val context = SelectedDeviceContext.make(items.filter { it.deviceId == b }, a, b)
        assertNull("no fallback to the routed peer", context.selected)
        val manager = PeerSessionManager<FakeSession>("50000000-0000-4000-8000-000000000000")
        assertFalse(manager.deliver(a) { true })
    }

    @Test
    fun simultaneousSendsToTwoPeersCompleteIndependently() {
        val sends = ClipboardSends()
        sends.begin(a, "ma")
        sends.begin(b, "mb")
        assertTrue(sends.acknowledge("ma", from = a))
        assertTrue(sends.isPending(b, "mb"))
        assertTrue(sends.acknowledge("mb", from = b))
        assertEquals(mapOf(a to ClipboardSends.Status.DELIVERED, b to ClipboardSends.Status.DELIVERED), sends.statuses())
    }

    @Test
    fun anAckFromAnotherPeerCompletesNothing() {
        val sends = ClipboardSends()
        sends.begin(a, "ma")
        assertFalse(sends.acknowledge("ma", from = b))
        assertTrue(sends.isPending(a, "ma"))
    }

    @Test
    fun disconnectEndsOnlyThatPeersSendWithoutMovingIt() {
        val sends = ClipboardSends()
        sends.begin(a, "ma")
        sends.begin(b, "mb")
        assertEquals(listOf("ma"), sends.deviceEnded(a))
        assertEquals(ClipboardSends.Status.DISCONNECTED, sends.statuses()[a])
        assertFalse("A's send never becomes B's", sends.isPending(b, "ma"))
        assertTrue(sends.isPending(b, "mb"))
        assertFalse("a late ack after the disconnect completes nothing", sends.acknowledge("ma", from = a))
    }

    @Test
    fun rejectionAndTimeoutAreRecordedPerPeer() {
        val sends = ClipboardSends()
        sends.begin(a, "ma")
        sends.begin(b, "mb")
        assertTrue(sends.reject("ma", from = a))
        assertTrue(sends.timeOut(b, "mb"))
        assertEquals(mapOf(a to ClipboardSends.Status.REJECTED, b to ClipboardSends.Status.NOT_ACKNOWLEDGED), sends.statuses())
    }

    @Test
    fun aRetransmissionIsAcknowledgedAgainNotAppliedTwice() {
        assertEquals(ClipboardReceiveAction.APPLY, ClipboardReceiveAction.forMessage(isNewMessageId = true))
        assertEquals(ClipboardReceiveAction.ACKNOWLEDGE_AGAIN, ClipboardReceiveAction.forMessage(isNewMessageId = false))
    }

    @Test
    fun clipboardFromAnotherMacIsNotAccepted() {
        val mac = DeviceProfile(DevicePlatform.MACOS, DeviceKind.COMPUTER)
        val phone = DeviceProfile(DevicePlatform.ANDROID, DeviceKind.PHONE)
        val unknown = DeviceProfile(DevicePlatform.UNKNOWN, DeviceKind.UNKNOWN)
        assertFalse(ClipboardReceiveAction.acceptsSender(mac, mac))
        assertTrue(ClipboardReceiveAction.acceptsSender(phone, mac))
        assertTrue(ClipboardReceiveAction.acceptsSender(mac, phone))
        assertTrue(ClipboardReceiveAction.acceptsSender(phone, phone))
        // Pairings recorded before platform hints keep working.
        assertTrue(ClipboardReceiveAction.acceptsSender(unknown, phone))
    }

    @Test
    fun authorizationIsTheSendersOwnGrant() {
        val grants = mapOf(a to true, b to false)
        assertTrue(effectiveFeatureEnabled(globalEnabled = true, deviceEnabled = grants[a]))
        assertFalse(effectiveFeatureEnabled(globalEnabled = true, deviceEnabled = grants[b]))
    }

    @Test
    fun targetlessSendsGoOnlyToTheOnlyEligiblePeer() {
        assertEquals(a, ClipboardTarget.forTargetlessSend(listOf(a)))
        assertNull("several peers: the user must choose", ClipboardTarget.forTargetlessSend(listOf(a, b)))
        assertNull("no peers: nothing is sent", ClipboardTarget.forTargetlessSend(emptyList()))
    }
}
