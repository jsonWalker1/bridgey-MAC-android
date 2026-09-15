package dev.bridgey.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manager-level tests: two VideoChannelManagers wired directly to each other's receive() (standing
 * in for the real control channel, which is out of scope here - only negotiation/establishment/
 * reset behavior is under test, not the control-channel transport itself). */
class VideoChannelManagerTest {
    private val pairingKey = ByteArray(32) { it.toByte() }

    private class Harness(pairingKey: ByteArray, sessionId: () -> String?) {
        lateinit var peer: Harness
        val manager: VideoChannelManager = VideoChannelManager(
            available = { true },
            send = { kind, payload -> peer.manager.receive(kind, payload); true },
            pairingKeyProvider = { pairingKey },
            sessionIdProvider = sessionId,
            remoteHostProvider = { "127.0.0.1" },
        )
    }

    private fun wiredPair(sessionId: () -> String?): Pair<Harness, Harness> {
        val a = Harness(pairingKey, sessionId)
        val b = Harness(pairingKey, sessionId)
        a.peer = b; b.peer = a
        return a to b
    }

    private fun awaitState(get: () -> ChannelState, target: ChannelState, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (get() == target) return
            Thread.sleep(10)
        }
        assertEquals(target, get())
    }

    @Test fun videoAndInputNegotiateSimultaneouslyThroughTheManagerAndDeliverPayloads() {
        val sessionId = java.util.UUID.randomUUID().toString()
        val (a, b) = wiredPair { sessionId }

        val videoReceived = CountDownLatch(1)
        val inputReceived = CountDownLatch(1)
        b.manager.onVideoFrame = { videoReceived.countDown() }
        b.manager.onInputEvent = { inputReceived.countDown() }

        a.manager.offerVideo("android_to_mac", width = 1080, height = 2400, bitrateKbps = 4000, fps = 30)
        a.manager.offerInput("mac_to_android")

        awaitState({ a.manager.currentVideoState }, ChannelState.ACTIVE)
        awaitState({ b.manager.currentVideoState }, ChannelState.ACTIVE)
        awaitState({ a.manager.currentInputState }, ChannelState.ACTIVE)
        awaitState({ b.manager.currentInputState }, ChannelState.ACTIVE)

        val videoOutcome = a.manager.sendVideoFrame(
            EncodedVideoFrame(VideoFrameType.KEYFRAME, streamId = 1, captureTimestampMs = 1, payload = "hi".toByteArray()), droppable = false,
        )
        assertEquals(SendOutcome.Sent, videoOutcome)
        val inputOutcome = a.manager.sendInputEvent(InputEvent.Pointer(PointerAction.DOWN, 0.5f, 0.5f))
        assertEquals(SendOutcome.Sent, inputOutcome)

        assertTrue(videoReceived.await(5, TimeUnit.SECONDS))
        assertTrue(inputReceived.await(5, TimeUnit.SECONDS))
    }

    @Test fun resetFromActiveIsImmediateAndClosesTheTransport() {
        val sessionId = java.util.UUID.randomUUID().toString()
        val (a, b) = wiredPair { sessionId }
        a.manager.offerVideo("android_to_mac", 1080, 2400, 4000, 30)
        awaitState({ a.manager.currentVideoState }, ChannelState.ACTIVE)
        awaitState({ b.manager.currentVideoState }, ChannelState.ACTIVE)

        a.manager.reset()
        assertEquals(ChannelState.IDLE, a.manager.currentVideoState)
        assertEquals(ChannelState.IDLE, a.manager.currentInputState)
        val outcome = a.manager.sendVideoFrame(
            EncodedVideoFrame(VideoFrameType.DELTA, 1, 2, "x".toByteArray()), droppable = true,
        )
        assertTrue(outcome is SendOutcome.Failed)
    }

    @Test fun freshNegotiationAfterResetReachesActiveAgainWithANewSession() {
        var sessionId = java.util.UUID.randomUUID().toString()
        val (a, b) = wiredPair { sessionId }
        a.manager.offerVideo("android_to_mac", 1080, 2400, 4000, 30)
        awaitState({ a.manager.currentVideoState }, ChannelState.ACTIVE)
        awaitState({ b.manager.currentVideoState }, ChannelState.ACTIVE)

        a.manager.reset()
        b.manager.reset()
        assertEquals(ChannelState.IDLE, a.manager.currentVideoState)
        assertEquals(ChannelState.IDLE, b.manager.currentVideoState)

        // A reconnect establishes a brand new session id - channelKey derivation (verified at the
        // ChannelSecurity unit level) binds sessionId into the salt, so this is a distinct channelKey
        // from the first negotiation even though pairingKey/purpose/direction are unchanged.
        sessionId = java.util.UUID.randomUUID().toString()
        a.manager.offerVideo("android_to_mac", 1080, 2400, 4000, 30)
        awaitState({ a.manager.currentVideoState }, ChannelState.ACTIVE)
        awaitState({ b.manager.currentVideoState }, ChannelState.ACTIVE)
    }

    @Test fun busyChannelRejectsASecondSimultaneousOffer() {
        // A real Bridgey session has exactly one peer on its control channel, so a "second offer
        // while busy" can only come from that same peer trying to re-offer - a genuinely separate
        // third party cannot appear mid-session. Model that directly, with no peer replying (so the
        // channel sits in NEGOTIATING): a second local offerVideo() call must be a no-op guarded by
        // the existing `videoState != ChannelState.IDLE` check, not a second in-flight offer.
        var offersSent = 0
        val manager = VideoChannelManager(
            available = { true },
            send = { kind, _ -> if (kind == "video.offer") offersSent++; true },
            pairingKeyProvider = { pairingKey },
            sessionIdProvider = { java.util.UUID.randomUUID().toString() },
            remoteHostProvider = { "127.0.0.1" },
        )
        manager.offerVideo("android_to_mac", 1080, 2400, 4000, 30)
        assertEquals(ChannelState.NEGOTIATING, manager.currentVideoState)
        assertEquals(1, offersSent)

        manager.offerVideo("android_to_mac", 1080, 2400, 4000, 30)
        assertEquals(ChannelState.NEGOTIATING, manager.currentVideoState)
        assertEquals(1, offersSent)
    }
}
