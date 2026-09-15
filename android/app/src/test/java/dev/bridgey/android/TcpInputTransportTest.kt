package dev.bridgey.android

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpInputTransportTest {
    private val pairingKey = ByteArray(32) { it.toByte() }
    private val sessionId = java.util.UUID.randomUUID().toString()
    private fun security() = ChannelSecurityContext(
        ChannelSecurity.deriveChannelKey(pairingKey, sessionId, "input", "mac_to_android"), sessionId, "input",
    )

    private fun connectedPair(): Pair<TcpInputTransport, TcpInputTransport> {
        val sec = security()
        val server = ServerSocket(0)
        val acceptor = TcpInputTransport()
        val acceptLatch = CountDownLatch(1)
        val acceptResult = AtomicReference<Boolean>()
        Thread({ acceptResult.set(acceptor.acceptViaServerSocket(server, sec)); acceptLatch.countDown() }).start()

        val initiator = TcpInputTransport()
        val ok = initiator.connectAsInitiator("127.0.0.1", server.localPort, sec)
        assertTrue(ok)
        assertTrue(acceptLatch.await(5, TimeUnit.SECONDS))
        assertTrue(acceptResult.get() == true)
        return initiator to acceptor
    }

    @Test fun handshakeSucceedsAndPointerEventRoundTrips() {
        val (initiator, acceptor) = connectedPair()
        val received = CountDownLatch(1)
        val event = AtomicReference<InputEvent>()
        acceptor.onEventReceived = { event.set(it); received.countDown() }

        val outcome = initiator.send(InputEvent.Pointer(PointerAction.DOWN, 0.5f, 0.5f))
        assertEquals(SendOutcome.Sent, outcome)
        assertTrue(received.await(5, TimeUnit.SECONDS))
        assertEquals(InputEvent.Pointer(PointerAction.DOWN, 0.5f, 0.5f), event.get())

        initiator.close(); acceptor.close()
    }

    @Test fun keyAndTextEventsRoundTrip() {
        val (initiator, acceptor) = connectedPair()
        val events = mutableListOf<InputEvent>()
        val latch = CountDownLatch(2)
        acceptor.onEventReceived = { events.add(it); latch.countDown() }

        initiator.send(InputEvent.Key(66, KeyAction.DOWN))
        initiator.send(InputEvent.Text("hello"))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(InputEvent.Key(66, KeyAction.DOWN), events[0])
        assertEquals(InputEvent.Text("hello"), events[1])

        initiator.close(); acceptor.close()
    }

    @Test fun pointerDownIsNeverCoalescedAwayEvenUnderQueuePressure() {
        // send() requires an established channelKey (post-handshake), so connect a real pair first;
        // the acceptor is left undrained so the queue backs up under a tight burst of sends.
        val (initiator, acceptor) = connectedPair()
        initiator.send(InputEvent.Pointer(PointerAction.MOVE, 0.1f, 0.1f))
        initiator.send(InputEvent.Pointer(PointerAction.MOVE, 0.2f, 0.2f))
        initiator.send(InputEvent.Pointer(PointerAction.MOVE, 0.3f, 0.3f))
        // A DOWN event must still be accepted (not silently discarded) even though the queue may be
        // under pressure from the bursted MOVE events above.
        val outcome = initiator.send(InputEvent.Pointer(PointerAction.DOWN, 0.9f, 0.9f))
        assertTrue(outcome is SendOutcome.Sent || outcome is SendOutcome.QueuedBounded)
        initiator.close(); acceptor.close()
    }
}
