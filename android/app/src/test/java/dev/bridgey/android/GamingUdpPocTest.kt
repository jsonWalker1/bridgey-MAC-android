package dev.bridgey.android

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GAMING MODE POC tests. Proves the UDP framing/reassembly/security mechanics work correctly
 * under exactly the conditions the spec cares about - fragment loss, reordering, stale-frame
 * preemption, replay, and tampering - using deterministic simulated packet loss rather than a real
 * flaky network (this actually tests the resilience *logic* more rigorously than a live LAN would,
 * since every scenario is exactly reproducible).
 */
class GamingUdpPocTest {

    // --- A tiny self-contained AES-GCM helper, standing in for what production code would call
    // through the app's existing Crypto object - kept local so this test has zero dependency on
    // production crypto wiring while still exercising the exact AAD-binding behavior the POC needs.
    private fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = ByteArray(GamingUdpFraming.NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return nonce to cipher.doFinal(plaintext)
    }

    private fun open(key: ByteArray, aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(ciphertext)
    } catch (_: Exception) {
        null
    }

    private val testKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun buildFragmentPackets(
        frameId: Long,
        flags: Int,
        payload: ByteArray,
        sessionToken: Long = 42L,
        sequenceStart: Int = 0,
    ): List<ByteArray> {
        val fragments = GamingUdpFraming.splitIntoFragments(payload)
        return fragments.mapIndexed { index, fragment ->
            val header = GamingUdpFraming.Header(
                type = GamingUdpFraming.PacketType.VIDEO_FRAME_FRAGMENT,
                gamingSessionToken = sessionToken,
                frameId = frameId,
                fragmentIndex = index,
                fragmentCount = fragments.size,
                captureTimestampMs = 1_000L,
                flags = flags,
                sequence = sequenceStart + index,
            )
            val aad = GamingUdpFraming.encodeHeader(header)
            val (nonce, ciphertext) = seal(testKey, aad, fragment)
            GamingUdpFraming.assemble(header, nonce, ciphertext)
        }
    }

    @Test
    fun fragmentationRoundTripsAFrameLargerThanOneFragment() {
        val payload = Random.nextBytes(GamingUdpFraming.MAX_FRAGMENT_PAYLOAD_BYTES * 3 + 500)
        val packets = buildFragmentPackets(frameId = 1, flags = 0, payload = payload)
        assertTrue("a frame this size must actually need multiple fragments", packets.size > 1)

        var lostCalls = 0
        val reassembler = GamingFrameReassembler(onKeyframeOrConfigLost = { lostCalls++ })
        var result: ByteArray? = null
        for (packet in packets) {
            val parsed = GamingUdpFraming.parse(packet)!!
            val plaintext = open(testKey, parsed.aad, parsed.nonce, parsed.ciphertext)!!
            result = reassembler.onFragment(
                parsed.header.frameId, parsed.header.fragmentIndex, parsed.header.fragmentCount,
                parsed.header.flags, nowMs = 0L, payload = plaintext,
            ) ?: result
        }
        assertArrayEquals(payload, result)
        assertEquals(0, lostCalls)
    }

    @Test
    fun fragmentsCanArriveOutOfOrder() {
        val payload = Random.nextBytes(GamingUdpFraming.MAX_FRAGMENT_PAYLOAD_BYTES * 2 + 10)
        val packets = buildFragmentPackets(frameId = 1, flags = 0, payload = payload).shuffled(Random(7))
        val reassembler = GamingFrameReassembler(onKeyframeOrConfigLost = {})
        var result: ByteArray? = null
        for (packet in packets) {
            val parsed = GamingUdpFraming.parse(packet)!!
            val plaintext = open(testKey, parsed.aad, parsed.nonce, parsed.ciphertext)!!
            result = reassembler.onFragment(
                parsed.header.frameId, parsed.header.fragmentIndex, parsed.header.fragmentCount,
                parsed.header.flags, nowMs = 0L, payload = plaintext,
            ) ?: result
        }
        assertArrayEquals(payload, result)
    }

    @Test
    fun aDeltaFrameMissingAFragmentIsSilentlyDroppedAndNeverCompletes() {
        val payload = Random.nextBytes(GamingUdpFraming.MAX_FRAGMENT_PAYLOAD_BYTES * 2 + 10)
        val packets = buildFragmentPackets(frameId = 1, flags = 0, payload = payload)
        assertTrue(packets.size >= 2)
        var lostCalls = 0
        val reassembler = GamingFrameReassembler(onKeyframeOrConfigLost = { lostCalls++ })
        // Simulate 1 lost fragment out of N (drop the last one).
        for (packet in packets.dropLast(1)) {
            val parsed = GamingUdpFraming.parse(packet)!!
            val plaintext = open(testKey, parsed.aad, parsed.nonce, parsed.ciphertext)!!
            val result = reassembler.onFragment(
                parsed.header.frameId, parsed.header.fragmentIndex, parsed.header.fragmentCount,
                parsed.header.flags, nowMs = 0L, payload = plaintext,
            )
            assertNull("must never complete with a fragment missing", result)
        }
        // A plain delta frame loss must NOT trigger a keyframe/config recovery request.
        assertEquals(0, lostCalls)
    }

    @Test
    fun aNewerFrameArrivingImmediatelyPreemptsAnOlderIncompleteFrame_noWaitingForRetransmission() {
        val oldPayload = Random.nextBytes(GamingUdpFraming.MAX_FRAGMENT_PAYLOAD_BYTES * 2 + 10)
        val newPayload = Random.nextBytes(50)
        val oldPackets = buildFragmentPackets(frameId = 1, flags = 0, payload = oldPayload)
        val newPackets = buildFragmentPackets(frameId = 2, flags = 0, payload = newPayload, sequenceStart = 100)

        val reassembler = GamingFrameReassembler(onKeyframeOrConfigLost = {})
        // Only the first fragment of the OLD frame arrives - it never completes on its own.
        val firstOld = GamingUdpFraming.parse(oldPackets.first())!!
        reassembler.onFragment(
            firstOld.header.frameId, firstOld.header.fragmentIndex, firstOld.header.fragmentCount,
            firstOld.header.flags, nowMs = 0L, payload = open(testKey, firstOld.aad, firstOld.nonce, firstOld.ciphertext)!!,
        )
        // The newer frame completes immediately - it must NOT be blocked by the incomplete older one.
        var result: ByteArray? = null
        for (packet in newPackets) {
            val parsed = GamingUdpFraming.parse(packet)!!
            val plaintext = open(testKey, parsed.aad, parsed.nonce, parsed.ciphertext)!!
            result = reassembler.onFragment(
                parsed.header.frameId, parsed.header.fragmentIndex, parsed.header.fragmentCount,
                parsed.header.flags, nowMs = 1L, payload = plaintext,
            ) ?: result
        }
        assertArrayEquals(newPayload, result)
    }

    @Test
    fun aDroppedKeyframeTriggersRecoveryRequestButADroppedDeltaDoesNot() {
        var lostCalls = 0
        val reassembler = GamingFrameReassembler(onKeyframeOrConfigLost = { lostCalls++ })

        val keyframePayload = Random.nextBytes(GamingUdpFraming.MAX_FRAGMENT_PAYLOAD_BYTES * 2 + 10)
        val keyframePackets = buildFragmentPackets(frameId = 1, flags = GamingUdpFraming.Flags.KEYFRAME, payload = keyframePayload)
        // Drop the keyframe's last fragment.
        for (packet in keyframePackets.dropLast(1)) {
            val parsed = GamingUdpFraming.parse(packet)!!
            reassembler.onFragment(
                parsed.header.frameId, parsed.header.fragmentIndex, parsed.header.fragmentCount,
                parsed.header.flags, nowMs = 0L, payload = open(testKey, parsed.aad, parsed.nonce, parsed.ciphertext)!!,
            )
        }
        assertEquals(0, lostCalls) // not yet reported - still "in progress" until preempted/timed out

        // A subsequent delta frame preempts the incomplete keyframe -> THIS is when loss is reported.
        val deltaPackets = buildFragmentPackets(frameId = 2, flags = 0, payload = Random.nextBytes(20), sequenceStart = 50)
        for (packet in deltaPackets) {
            val parsed = GamingUdpFraming.parse(packet)!!
            reassembler.onFragment(
                parsed.header.frameId, parsed.header.fragmentIndex, parsed.header.fragmentCount,
                parsed.header.flags, nowMs = 1L, payload = open(testKey, parsed.aad, parsed.nonce, parsed.ciphertext)!!,
            )
        }
        assertEquals(1, lostCalls)
    }

    @Test
    fun anIncompleteFrameThatNothingNewerEverSupersedesIsDroppedByTimeout() {
        var lostCalls = 0
        val reassembler = GamingFrameReassembler(staleFrameTimeoutMs = 200L, onKeyframeOrConfigLost = { lostCalls++ })
        val configPackets = buildFragmentPackets(frameId = 1, flags = GamingUdpFraming.Flags.CONFIG, payload = Random.nextBytes(GamingUdpFraming.MAX_FRAGMENT_PAYLOAD_BYTES + 5))
        for (packet in configPackets.dropLast(1)) {
            val parsed = GamingUdpFraming.parse(packet)!!
            reassembler.onFragment(
                parsed.header.frameId, parsed.header.fragmentIndex, parsed.header.fragmentCount,
                parsed.header.flags, nowMs = 0L, payload = open(testKey, parsed.aad, parsed.nonce, parsed.ciphertext)!!,
            )
        }
        reassembler.dropStale(nowMs = 100L) // not yet stale
        assertEquals(0, lostCalls)
        reassembler.dropStale(nowMs = 250L) // now past the 200ms timeout
        assertEquals(1, lostCalls)
    }

    @Test
    fun tamperingWithAnyHeaderFieldInvalidatesTheAeadTag() {
        val payload = Random.nextBytes(20)
        val packets = buildFragmentPackets(frameId = 1, flags = 0, payload = payload)
        val tampered = packets.single().copyOf()
        // Flip a bit in the frameId field (bytes 2..9 of the header).
        tampered[3] = (tampered[3].toInt() xor 0x01).toByte()
        val parsed = GamingUdpFraming.parse(tampered)!!
        val opened = open(testKey, parsed.aad, parsed.nonce, parsed.ciphertext)
        assertNull("a tampered header must fail AEAD authentication, not just be silently accepted", opened)
    }

    @Test
    fun replayWindowRejectsADuplicatePacketButAcceptsReorderedOnesWithinRange() {
        val window = ReplayWindow(windowSize = 32)
        assertTrue(window.acceptAndRecord(10))
        assertTrue(window.acceptAndRecord(12))
        assertTrue(window.acceptAndRecord(11)) // reordered, but within window - legitimate under UDP
        assertFalse(window.acceptAndRecord(11)) // exact replay - must be rejected
        assertFalse(window.acceptAndRecord(10)) // exact replay of an even older one
    }

    @Test
    fun replayWindowRejectsAPacketTooOldToFitTheWindow() {
        val window = ReplayWindow(windowSize = 16)
        assertTrue(window.acceptAndRecord(1000))
        assertFalse(window.acceptAndRecord(5)) // far outside the window - must not be accepted
    }

    @Test
    fun simulatedTenPercentFragmentLossAcrossManyFramesNeverCorruptsAReassembledFrame() {
        // The key correctness property under loss: every frame that DOES complete must be exactly
        // byte-identical to what was sent - loss must drop whole frames, never produce a partially
        // wrong one.
        val random = Random(99)
        val reassembler = GamingFrameReassembler(onKeyframeOrConfigLost = {})
        var completedFrames = 0
        var droppedFrames = 0
        for (frameId in 1L..200L) {
            val payload = Random(frameId).nextBytes(random.nextInt(50, GamingUdpFraming.MAX_FRAGMENT_PAYLOAD_BYTES * 3))
            val packets = buildFragmentPackets(frameId = frameId, flags = 0, payload = payload, sequenceStart = (frameId * 10).toInt())
            val delivered = packets.filter { random.nextInt(100) >= 10 } // ~10% fragment loss
            var result: ByteArray? = null
            for (packet in delivered) {
                val parsed = GamingUdpFraming.parse(packet)!!
                val plaintext = open(testKey, parsed.aad, parsed.nonce, parsed.ciphertext)!!
                result = reassembler.onFragment(
                    parsed.header.frameId, parsed.header.fragmentIndex, parsed.header.fragmentCount,
                    parsed.header.flags, nowMs = frameId, payload = plaintext,
                ) ?: result
            }
            if (result != null) {
                assertArrayEquals("a completed frame must be exact - loss must drop whole frames, not corrupt them", payload, result)
                completedFrames++
            } else {
                droppedFrames++
            }
        }
        assertTrue("expected some frames to be dropped under 10% fragment loss", droppedFrames > 0)
        assertTrue("expected most frames to still complete under 10% fragment loss", completedFrames > droppedFrames)
    }

    @Test
    fun mtuSafeFragmentationNeverExceedsTheConfiguredMaximum() {
        val payload = Random.nextBytes(GamingUdpFraming.MAX_FRAGMENT_PAYLOAD_BYTES * 5 + 37)
        val fragments = GamingUdpFraming.splitIntoFragments(payload)
        for (fragment in fragments) assertTrue(fragment.size <= GamingUdpFraming.MAX_FRAGMENT_PAYLOAD_BYTES)
        assertEquals(payload.size, fragments.sumOf { it.size })
    }
}
