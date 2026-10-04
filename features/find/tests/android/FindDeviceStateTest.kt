package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MD-3 Find Device isolation: ringing state per device, on both sides of the request. */
class FindDeviceStateTest {
    private val a = "10000000-0000-4000-8000-00000000000a"
    private val b = "20000000-0000-4000-8000-00000000000b"

    @Test
    fun startingAndStoppingFindIsPerDevice() {
        val find = FindDeviceState()
        find.remoteReported(a, ringing = true)
        assertTrue(find.isRinging(a))
        assertFalse(find.isRinging(b))

        find.remoteReported(b, ringing = true)
        assertTrue(find.isRinging(a))
        assertTrue(find.isRinging(b))

        find.remoteReported(a, ringing = false) // A confirms it stopped
        assertFalse(find.isRinging(a))
        assertTrue(find.isRinging(b))
    }

    @Test
    fun disconnectOfALeavesBUntouchedAndReconnectInheritsNothing() {
        val find = FindDeviceState()
        find.remoteReported(a, ringing = true)
        find.remoteReported(b, ringing = true)
        find.localRingRequested(b)

        find.deviceEnded(a)
        assertFalse(find.isRinging(a))
        assertTrue(find.isRinging(b))
        assertEquals("B's request to ring this device stays", setOf(b), find.localRequesters())

        // A reconnects: nothing about A is restored, and it does not take over B's state.
        assertFalse(find.isRinging(a))
        assertFalse(a in find.localRequesters())
        assertTrue(find.isRinging(b))
    }

    @Test
    fun thisDeviceRingsWhileAnyRequesterRemains() {
        val find = FindDeviceState()
        assertTrue("first request starts the sound", find.localRingRequested(a))
        assertFalse("second request keeps it", find.localRingRequested(b))
        assertFalse("B still wants it to ring", find.peerStopped(a))
        assertTrue(find.isRingingLocally())
        assertTrue("last requester stops the sound", find.peerStopped(b))
        assertFalse(find.isRingingLocally())
    }

    @Test
    fun disconnectOfTheOnlyRequesterStopsTheSoundButNotAnothersRequest() {
        val find = FindDeviceState()
        find.localRingRequested(a)
        find.localRingRequested(b)
        assertFalse(find.deviceEnded(a))
        assertTrue(find.isRingingLocally())
        assertTrue(find.deviceEnded(b))
        assertFalse(find.isRingingLocally())
    }

    @Test
    fun aPeerStoppingUsDoesNotChangeWhatWeKnowAboutItsRinging() {
        val find = FindDeviceState()
        find.remoteReported(a, ringing = true)
        find.localRingRequested(a)
        assertTrue("A no longer asks us to ring", find.peerStopped(a))
        assertTrue("only A's own find.stopped says A stopped ringing", find.isRinging(a))
        find.remoteReported(a, ringing = false)
        assertFalse(find.isRinging(a))
    }

    @Test
    fun silencingThisDeviceReportsEveryRequester() {
        val find = FindDeviceState()
        find.localRingRequested(a)
        find.localRingRequested(b)
        find.remoteReported(b, ringing = true)
        assertEquals(setOf(a, b), find.stopLocalRinging())
        assertFalse(find.isRingingLocally())
        assertTrue("silencing this device does not stop a peer we made ring", find.isRinging(b))
    }

    @Test
    fun singleDeviceBehaviourIsUnchanged() {
        val find = FindDeviceState()
        find.remoteReported(a, ringing = true)
        assertTrue(find.isRinging(a))
        find.remoteReported(a, ringing = false)
        assertFalse(find.isRinging(a))
        assertTrue(find.localRingRequested(a))
        assertTrue(find.peerStopped(a))
        assertFalse(find.isRingingLocally())
    }
}
