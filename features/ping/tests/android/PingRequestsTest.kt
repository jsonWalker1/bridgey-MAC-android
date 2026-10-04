package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MD-3 Ping isolation: requests to two devices are independent (deviceId, requestId) pairs. */
class PingRequestsTest {
    private val a = "10000000-0000-4000-8000-00000000000a"
    private val b = "20000000-0000-4000-8000-00000000000b"
    private class FakeSession { val sent = mutableListOf<String>() }

    private fun twoPending() = PingRequests().apply {
        begin(a, "ra")
        begin(b, "rb")
    }

    @Test
    fun pingsToTwoDevicesStayIndependent() {
        val pings = twoPending()
        assertTrue(pings.isPending(a, "ra"))
        assertTrue(pings.isPending(b, "rb"))
        assertEquals(mapOf(a to PingRequests.Status.PINGING, b to PingRequests.Status.PINGING), pings.statuses())
    }

    @Test
    fun aRespondsAndBTimesOut() {
        val pings = twoPending()
        assertTrue(pings.acknowledge("ra", from = a))
        assertTrue(pings.timeOut(b, "rb"))
        assertEquals(mapOf(a to PingRequests.Status.DELIVERED, b to PingRequests.Status.NOT_ACKNOWLEDGED), pings.statuses())
        assertFalse("a delivered request cannot time out later", pings.timeOut(a, "ra"))
    }

    @Test
    fun bRespondsAndATimesOut() {
        val pings = twoPending()
        assertTrue(pings.timeOut(a, "ra"))
        assertTrue(pings.acknowledge("rb", from = b))
        assertEquals(mapOf(a to PingRequests.Status.NOT_ACKNOWLEDGED, b to PingRequests.Status.DELIVERED), pings.statuses())
    }

    @Test
    fun aDisconnectsAndBRemainsUsable() {
        val pings = twoPending()
        assertTrue("A had a request in flight", pings.deviceEnded(a))
        assertFalse(pings.deviceEnded(a))
        assertFalse(pings.isPending(a, "ra"))
        assertNull(pings.statuses()[a])
        assertTrue(pings.acknowledge("rb", from = b))
        pings.begin(b, "rb2")
        assertTrue(pings.isPending(b, "rb2"))
    }

    @Test
    fun bDisconnectsAndARemainsUsable() {
        val pings = twoPending()
        pings.deviceEnded(b)
        assertTrue(pings.acknowledge("ra", from = a))
        assertEquals(mapOf(a to PingRequests.Status.DELIVERED), pings.statuses())
    }

    @Test
    fun aLateResponseCannotCompleteBsRequest() {
        val pings = twoPending()
        assertFalse("A echoing B's request id completes nothing", pings.acknowledge("rb", from = a))
        assertTrue(pings.isPending(b, "rb"))
        assertTrue(pings.timeOut(a, "ra"))
        assertFalse("a late ack after the timeout completes nothing", pings.acknowledge("ra", from = a))
        assertEquals(PingRequests.Status.NOT_ACKNOWLEDGED, pings.statuses()[a])
        assertEquals(PingRequests.Status.PINGING, pings.statuses()[b])
    }

    @Test
    fun requestIdsCannotCollideAcrossDevices() {
        val pings = PingRequests()
        pings.begin(a, "same")
        pings.begin(b, "same")
        assertTrue(pings.acknowledge("same", from = a))
        assertTrue("the same id on another device is another request", pings.isPending(b, "same"))
    }

    @Test
    fun changingTheRoutedDeviceDoesNotTouchInFlightRequests() {
        val manager = PeerSessionManager<FakeSession>("50000000-0000-4000-8000-000000000000")
        for (id in listOf(a, b)) {
            val session = FakeSession()
            manager.addPending(session, initiatedLocally = false, expectedDeviceId = null)
            manager.identify(session, id)
            manager.markConnected(session)
        }
        val pings = PingRequests()
        assertTrue(manager.deliver(a) { it.sent += "ping.request ra"; true })
        pings.begin(a, "ra")
        for (preferred in listOf(b, a, b)) DeviceRouting.activePeer(DeviceRoutingMode.SINGLE_ACTIVE, preferred, manager.connectedInOrder())
        assertTrue(pings.isPending(a, "ra"))
        assertEquals(listOf("ping.request ra"), manager.connectedSession(a)!!.sent)
        assertEquals(emptyList<String>(), manager.connectedSession(b)!!.sent)
        val sender = manager.connectedDeviceId(manager.connectedSession(a)!!)!!
        assertTrue(pings.acknowledge("ra", from = sender))
    }

    @Test
    fun concurrentRequestsToManyDevicesStayIsolated() {
        val pings = PingRequests()
        val threads = (0 until 8).map { index ->
            Thread {
                val device = "device-$index"
                repeat(500) { n ->
                    pings.begin(device, "r$n")
                    check(pings.acknowledge("r$n", from = device))
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(8, pings.statuses().size)
        assertTrue(pings.statuses().values.all { it == PingRequests.Status.DELIVERED })
    }

    @Test
    fun singleDeviceBehaviourIsUnchanged() {
        val pings = PingRequests()
        pings.begin(a, "r1")
        assertTrue(pings.acknowledge("r1", from = a))
        assertEquals(PingRequests.Status.DELIVERED, pings.statuses()[a])
        pings.begin(a, "r2")
        assertTrue(pings.timeOut(a, "r2"))
        assertEquals(PingRequests.Status.NOT_ACKNOWLEDGED, pings.statuses()[a])
        pings.begin(a, "r3")
        pings.failed(a, "r3")
        assertEquals(PingRequests.Status.NOT_SENT, pings.statuses()[a])
    }
}
