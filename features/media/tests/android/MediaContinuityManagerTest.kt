package dev.bridgey.android

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaContinuityManagerTest {

    @Test fun primarySessionPrefersWhicheverIsPlaying() {
        val sessions = listOf(MediaSessionSnapshot("spotify", isPlaying = false), MediaSessionSnapshot("youtube", isPlaying = true))
        assertEquals("youtube", selectPrimarySession(sessions, lastPlayingId = null, previousPrimaryId = "spotify"))
    }

    @Test fun primarySessionBreaksMultiplePlayingTiesTowardTheMostRecentlyStartedOne() {
        val sessions = listOf(MediaSessionSnapshot("spotify", isPlaying = true), MediaSessionSnapshot("youtube", isPlaying = true))
        assertEquals("youtube", selectPrimarySession(sessions, lastPlayingId = "youtube", previousPrimaryId = "spotify"))
        assertEquals("spotify", selectPrimarySession(sessions, lastPlayingId = "spotify", previousPrimaryId = "youtube"))
    }

    @Test fun primarySessionFallsBackToFirstPlayingWhenLastPlayingHintIsStale() {
        val sessions = listOf(MediaSessionSnapshot("spotify", isPlaying = true), MediaSessionSnapshot("youtube", isPlaying = true))
        assertEquals("spotify", selectPrimarySession(sessions, lastPlayingId = "chrome", previousPrimaryId = null))
    }

    @Test fun primarySessionKeepsThePreviousPrimaryPausedRatherThanBlankingTheMiniPlayer() {
        val sessions = listOf(MediaSessionSnapshot("spotify", isPlaying = false), MediaSessionSnapshot("youtube", isPlaying = false))
        assertEquals("spotify", selectPrimarySession(sessions, lastPlayingId = null, previousPrimaryId = "spotify"))
    }

    @Test fun primarySessionFallsBackToFirstWhenNothingIsPlayingAndNoPreviousPrimarySurvives() {
        val sessions = listOf(MediaSessionSnapshot("spotify", isPlaying = false))
        assertEquals("spotify", selectPrimarySession(sessions, lastPlayingId = null, previousPrimaryId = "youtube"))
    }

    @Test fun primarySessionIsNullWhenThereAreNoActiveSessions() {
        assertNull(selectPrimarySession(emptyList(), lastPlayingId = null, previousPrimaryId = "spotify"))
    }

    @Test fun actionsMustTargetTheCurrentGenerationExactly() {
        assertTrue(isActionGenerationValid(current = 11, requested = 11))
        assertFalse(isActionGenerationValid(current = 11, requested = 10))
        assertFalse(isActionGenerationValid(current = 11, requested = 12))
    }

    @Test fun sha256HexMatchesAKnownVector() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            sha256Hex("hello".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test fun sha256HexIsStableForIdenticalArtworkBytes() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(sha256Hex(bytes), sha256Hex(bytes.copyOf()))
    }

    @Test fun extrapolatedPositionHoldsStillWhenPaused() {
        val position = extrapolatedPosition(
            positionAtUpdate = 5_000, lastPositionUpdateElapsedRealtimeMs = 1_000,
            playbackSpeed = 0f, nowElapsedRealtimeMs = 10_000, durationMs = 60_000,
        )
        assertEquals(5_000L, position)
    }

    @Test fun extrapolatedPositionAdvancesAtPlaybackSpeedWhilePlaying() {
        val position = extrapolatedPosition(
            positionAtUpdate = 5_000, lastPositionUpdateElapsedRealtimeMs = 1_000,
            playbackSpeed = 1f, nowElapsedRealtimeMs = 4_000, durationMs = 60_000,
        )
        assertEquals(8_000L, position)
    }

    @Test fun extrapolatedPositionClampsToKnownDuration() {
        val position = extrapolatedPosition(
            positionAtUpdate = 59_000, lastPositionUpdateElapsedRealtimeMs = 0,
            playbackSpeed = 1f, nowElapsedRealtimeMs = 60_000, durationMs = 60_000,
        )
        assertEquals(60_000L, position)
    }

    @Test fun extrapolatedPositionNeverGoesNegative() {
        val position = extrapolatedPosition(
            positionAtUpdate = 0, lastPositionUpdateElapsedRealtimeMs = 5_000,
            playbackSpeed = 1f, nowElapsedRealtimeMs = 0, durationMs = 0,
        )
        assertEquals(0L, position)
    }

    @Test fun capabilitiesReflectTheSupportedActionBitmask() {
        assertEquals(
            setOf("play", "pause", "next", "previous", "seek"),
            capabilitiesFor(
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO,
            ),
        )
        assertEquals(emptySet<String>(), capabilitiesFor(0L))
    }

    @Test fun normalizedVolumeMapsAnArbitraryDeviceScaleToAFlatZeroToHundred() {
        assertEquals(50, normalizedVolume(current = 8, max = 16))
        assertEquals(0, normalizedVolume(current = 0, max = 15))
        assertEquals(100, normalizedVolume(current = 15, max = 15))
        assertNull(normalizedVolume(current = 5, max = 0))
    }

    @Test fun denormalizedVolumeIsTheInverseMapping() {
        assertEquals(8, denormalizedVolume(percent = 50, max = 16))
        assertEquals(15, denormalizedVolume(percent = 100, max = 15))
        assertEquals(0, denormalizedVolume(percent = 0, max = 15))
    }

    @Test fun denormalizedVolumeClampsOutOfRangePercentages() {
        assertEquals(0, denormalizedVolume(percent = -10, max = 15))
        assertEquals(15, denormalizedVolume(percent = 250, max = 15))
    }

    @Test fun firstTrackChangeIsAlwaysAllowed() {
        assertTrue(trackChangeAllowed(nowMs = 1_000, lastAcceptedAtMs = 0))
    }

    @Test fun trackChangeIsRejectedWhenTooSoonAfterTheLastAcceptedOne() {
        assertFalse(trackChangeAllowed(nowMs = 1_400, lastAcceptedAtMs = 1_000, minimumIntervalMs = 900))
        assertFalse(trackChangeAllowed(nowMs = 1_899, lastAcceptedAtMs = 1_000, minimumIntervalMs = 900))
    }

    @Test fun trackChangeIsAllowedOnceTheMinimumIntervalHasPassed() {
        assertTrue(trackChangeAllowed(nowMs = 1_900, lastAcceptedAtMs = 1_000, minimumIntervalMs = 900))
        assertTrue(trackChangeAllowed(nowMs = 5_000, lastAcceptedAtMs = 1_000, minimumIntervalMs = 900))
    }

    @Test fun aResolvedLabelThatMatchesThePackageNameIsNotUsable() {
        assertFalse(isUsableAppLabel("com.google.android.youtube", packageName = "com.google.android.youtube"))
    }

    @Test fun blankOrMissingLabelsAreNotUsable() {
        assertFalse(isUsableAppLabel(null, packageName = "com.spotify.music"))
        assertFalse(isUsableAppLabel("", packageName = "com.spotify.music"))
    }

    @Test fun aRealDisplayNameIsUsable() {
        assertTrue(isUsableAppLabel("YouTube", packageName = "com.google.android.youtube"))
        assertTrue(isUsableAppLabel("Spotify", packageName = "com.spotify.music"))
    }
}
