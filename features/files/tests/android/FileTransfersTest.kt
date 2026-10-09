package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** MD-6 files: every transfer belongs to one peer and is identified by (deviceId, transferId). */
class FileTransfersTest {
    private val a = "10000000-0000-4000-8000-00000000000a"
    private val b = "20000000-0000-4000-8000-00000000000b"
    private val x = "30000000-0000-4000-8000-0000000000aa"
    private val y = "40000000-0000-4000-8000-0000000000bb"

    private class Transfer { var progress = 0; var cancelled = false }
    private class FakeSession { val received = mutableListOf<String>() }

    // Targeting

    @Test
    fun theSelectedPeerIsTheTargetWhateverIsRouted() {
        assertEquals(a, FileTransferTarget.initial(a, listOf(a, b)))
        assertEquals(b, FileTransferTarget.initial(b, listOf(a, b)))
    }

    @Test
    fun oneEligiblePeerIsChosenAutomatically() {
        assertEquals(a, FileTransferTarget.initial(null, listOf(a)))
        assertEquals("B is not eligible, A is the only choice", a, FileTransferTarget.initial(b, listOf(a)))
    }

    @Test
    fun noTargetMeansNoSendAndNoFallback() {
        assertNull(FileTransferTarget.initial(null, emptyList()))
        assertNull("a disconnected selection falls back to nothing", FileTransferTarget.initial(a, emptyList()))
        assertNull("several peers: the user must choose", FileTransferTarget.initial(null, listOf(a, b)))
    }

    @Test
    fun anOutgoingTransferGoesOnlyToItsPeersSession() {
        val manager = PeerSessionManager<FakeSession>("50000000-0000-4000-8000-000000000000").apply {
            for (id in listOf(a, b)) {
                val session = FakeSession()
                addPending(session, initiatedLocally = false, expectedDeviceId = null)
                identify(session, id)
                markConnected(session)
            }
        }
        val key = FileTransferKey(a, x)
        assertTrue(manager.deliver(key.deviceId) { it.received += "files.offer"; true })
        assertEquals(listOf("files.offer"), manager.connectedSession(a)?.received)
        assertEquals("the other peer receives nothing", emptyList<String>(), manager.connectedSession(b)?.received)
    }

    // Per-peer state

    @Test
    fun simultaneousTransfersToTwoPeersAreIndependent() {
        val outgoing = FileTransferTable<Transfer>()
        val toA = Transfer()
        val toB = Transfer()
        assertTrue(outgoing.insert(FileTransferKey(a, x), toA))
        assertTrue(outgoing.insert(FileTransferKey(b, y), toB))
        outgoing[FileTransferKey(a, x)]?.progress = 50
        assertEquals(50, toA.progress)
        assertEquals("progress of A never touches B", 0, toB.progress)
        assertSame(toA, outgoing.remove(FileTransferKey(a, x)))
        assertSame("A completing leaves B running", toB, outgoing[FileTransferKey(b, y)])
    }

    @Test
    fun anAcknowledgementFromOnePeerNeverCompletesAnotherPeersTransfer() {
        val pending = FileTransferTable<Transfer>()
        val toB = Transfer()
        pending.insert(FileTransferKey(b, x), toB)
        // files.accept / files.complete.ack / files.cancel from A carrying B's transfer id:
        assertNull(pending.remove(FileTransferKey(a, x)))
        assertSame(toB, pending[FileTransferKey(b, x)])
    }

    @Test
    fun theSameTransferIdFromTwoPeersStaysTwoTransfers() {
        val incoming = FileTransferTable<Transfer>()
        val fromA = Transfer()
        val fromB = Transfer()
        assertTrue(incoming.insert(FileTransferKey(a, x), fromA))
        assertTrue("no collision across peers", incoming.insert(FileTransferKey(b, x), fromB))
        assertFalse("a duplicate offer of the same peer is refused", incoming.insert(FileTransferKey(a, x), Transfer()))
        incoming.remove(FileTransferKey(a, x))?.cancelled = true
        assertTrue(fromA.cancelled)
        assertFalse(fromB.cancelled)
    }

    @Test
    fun disconnectingOnePeerEndsOnlyItsTransfers() {
        val jobs = FileTransferTable<Transfer>()
        jobs.insert(FileTransferKey(a, x), Transfer())
        jobs.insert(FileTransferKey(a, y), Transfer())
        val toB = Transfer()
        jobs.insert(FileTransferKey(b, x), toB)
        val ended = jobs.removeAll(a)
        assertEquals(setOf(x, y), ended.map { it.first.transferId }.toSet())
        assertSame("B's transfer continues", toB, jobs[FileTransferKey(b, x)])
        assertEquals(1, jobs.values().size)
    }

    @Test
    fun aReconnectedPeerStartsWithoutStaleTransfers() {
        val incoming = FileTransferTable<Transfer>()
        val cancelled = CancelledFileTransfers()
        val old = FileTransferKey(a, x)
        incoming.insert(old, Transfer())
        incoming.removeAll(a).forEach { cancelled.add(it.first) }
        assertFalse("the interrupted transfer is gone, not resurrected", incoming.contains(old))
        assertTrue("late messages of the old transfer are ignored", old in cancelled)
        val new = FileTransferKey(a, y)
        assertTrue(incoming.insert(new, Transfer()))
        assertFalse(new in cancelled)
    }

    @Test
    fun aStaleCompletionCannotRemoveANewerEntry() {
        val jobs = FileTransferTable<Transfer>()
        val key = FileTransferKey(a, x)
        val old = Transfer()
        val newer = Transfer()
        jobs.put(key, old)
        jobs.put(key, newer)
        assertFalse(jobs.remove(key, old))
        assertSame(newer, jobs[key])
    }

    @Test
    fun ownershipIsFixedWhenTheTransferStarts() {
        var selected = a
        val key = FileTransferKey(FileTransferTarget.initial(selected, listOf(a, b))!!, x)
        selected = b
        assertEquals(a, key.deviceId)
        assertFalse(key.deviceId == selected)
    }

    @Test
    fun cancelledTransfersArePerPeerAndBounded() {
        val cancelled = CancelledFileTransfers()
        cancelled.add(FileTransferKey(a, x))
        assertFalse(FileTransferKey(b, x) in cancelled)
        repeat(CancelledFileTransfers.CAPACITY + 5) { cancelled.add(FileTransferKey(b, "$it")) }
        assertFalse("oldest dropped first", FileTransferKey(a, x) in cancelled)
        assertTrue(FileTransferKey(b, "${CancelledFileTransfers.CAPACITY + 4}") in cancelled)
    }

    @Test
    fun rowIdRoundTrips() {
        val key = FileTransferKey(a, x)
        assertEquals(key, FileTransferKey.fromRowId(key.rowId))
        assertNull(FileTransferKey.fromRowId("no-separator"))
        assertNull(FileTransferKey.fromRowId("|$x"))
        // A peer's deviceId is not validated: the split is at the last '|' (transfer ids are UUIDs).
        val odd = FileTransferKey("a|b", x)
        assertEquals(odd, FileTransferKey.fromRowId(odd.rowId))
    }

    // Attribution

    @Test
    fun statusTextNamesThePeer() {
        assertEquals("Sending photo.jpg → MacBook Air – pracovní…", FileTransferText.sending("photo.jpg", "MacBook Air – pracovní"))
        assertEquals("document.pdf saved on M4", FileTransferText.saved("document.pdf", "M4"))
        assertTrue(FileTransferText.receiving("a.txt", "Galaxy S23 Ultra", "50%").startsWith("Receiving a.txt ← Galaxy S23 Ultra"))
    }
}
