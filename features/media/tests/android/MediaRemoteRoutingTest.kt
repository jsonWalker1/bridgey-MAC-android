package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MD-10: Media Continuity goes to exactly one Mac (same rules as MD-9 notifications). */
class MediaRemoteRoutingTest {
    private val m4 = "mac-m4"
    private val work = "mac-work"
    private val m4Session = Any()
    private val workSession = Any()

    @Test fun preferredEligibleMacIsTheTarget() {
        assertEquals(m4, mediaRemoteTarget(preferredDeviceId = m4, routedDeviceId = work, eligible = listOf(m4, work)))
    }

    @Test fun preferredMacOfflineMeansNoTargetAndNoFallback() {
        assertNull(mediaRemoteTarget(preferredDeviceId = m4, routedDeviceId = work, eligible = listOf(work)))
    }

    @Test fun withoutPreferenceTheRoutedEligibleMacIsTheTarget() {
        assertEquals(work, mediaRemoteTarget(preferredDeviceId = null, routedDeviceId = work, eligible = listOf(work)))
    }

    @Test fun noEligibleMacMeansNoTarget() {
        assertNull(mediaRemoteTarget(preferredDeviceId = null, routedDeviceId = work, eligible = emptyList()))
        assertNull(mediaRemoteTarget(preferredDeviceId = m4, routedDeviceId = m4, eligible = emptyList()))
    }

    @Test fun actionsAreAcceptedOnlyFromTheTarget() {
        assertTrue(acceptsMediaRemoteAction(sender = m4, target = m4))
        assertFalse(acceptsMediaRemoteAction(sender = work, target = m4))
        assertFalse(acceptsMediaRemoteAction(sender = m4, target = null))
    }

    @Test fun targetChangeSendsFreshStateToTheNewTargetAndClearsTheOldOne() {
        val tracker = NotificationTargetTracker()
        val eligible = mapOf(m4 to m4Session, work to workSession)
        tracker.update(m4, eligible)
        assertEquals(NotificationTargetTracker.Step(reconcile = work, clear = listOf(m4)), tracker.update(work, eligible))
        assertEquals(work, tracker.target)
    }

    @Test fun nonTargetMacThatReconnectsIsOnlyCleared() {
        val tracker = NotificationTargetTracker()
        tracker.update(m4, mapOf(m4 to m4Session, work to workSession))
        tracker.update(m4, mapOf(m4 to m4Session))
        assertEquals(NotificationTargetTracker.Step(reconcile = null, clear = listOf(work)), tracker.update(m4, mapOf(m4 to m4Session, work to Any())))
    }

    @Test fun clearedStateIsANoSessionPayloadWithoutAnyPlaybackDetails() {
        val payload = mediaRemoteStatePayload(MediaRemoteState(), generation = 4, sequence = 12, includeArtwork = false)
        assertFalse(payload.getBoolean("hasSession"))
        assertFalse(payload.getBoolean("playing"))
        assertEquals(4L, payload.getLong("generation"))
        assertEquals(12L, payload.getLong("sequence"))
        listOf("title", "artist", "album", "packageName", "appLabel", "artwork", "artworkHash", "volume").forEach {
            assertFalse("$it must not be sent", payload.has(it))
        }
    }

    @Test fun fullStatePayloadCarriesTheTrack() {
        val state = MediaRemoteState(hasSession = true, packageName = "p", title = "T", playing = true, capabilities = setOf("play"))
        val payload = mediaRemoteStatePayload(state, generation = 1, sequence = 2, includeArtwork = false)
        assertTrue(payload.getBoolean("hasSession"))
        assertEquals("T", payload.getString("title"))
        assertFalse(payload.has("artwork"))
    }
}
