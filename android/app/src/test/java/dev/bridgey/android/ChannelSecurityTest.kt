package dev.bridgey.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSecurityTest {
    private val pairingKey = ByteArray(32) { it.toByte() }

    @Test fun channelKeyDerivationIsDeterministic() {
        val a = ChannelSecurity.deriveChannelKey(pairingKey, "session-1", "video", "android_to_mac")
        val b = ChannelSecurity.deriveChannelKey(pairingKey, "session-1", "video", "android_to_mac")
        assertArrayEquals(a, b)
    }

    @Test fun videoAndInputChannelsGetDistinctKeysForTheSameSession() {
        val video = ChannelSecurity.deriveChannelKey(pairingKey, "session-1", "video", "android_to_mac")
        val input = ChannelSecurity.deriveChannelKey(pairingKey, "session-1", "input", "android_to_mac")
        assertFalse(video.contentEquals(input))
    }

    @Test fun differentDirectionsGetDistinctKeysForTheSamePurposeAndSession() {
        val a = ChannelSecurity.deriveChannelKey(pairingKey, "session-1", "video", "android_to_mac")
        val b = ChannelSecurity.deriveChannelKey(pairingKey, "session-1", "video", "mac_to_android")
        assertFalse(a.contentEquals(b))
    }

    @Test fun differentSessionsGetDistinctKeys() {
        val a = ChannelSecurity.deriveChannelKey(pairingKey, "session-1", "video", "android_to_mac")
        val b = ChannelSecurity.deriveChannelKey(pairingKey, "session-2", "video", "android_to_mac")
        assertFalse(a.contentEquals(b))
    }

    @Test fun tokenAndAckProofAreDistinctEvenWithIdenticalInputs() {
        val key = ChannelSecurity.deriveChannelKey(pairingKey, "session-1", "video", "android_to_mac")
        val nonce = ChannelSecurity.generateOpenNonce()
        val token = ChannelSecurity.computeToken(key, "session-1", "video", nonce)
        val ack = ChannelSecurity.computeAckProof(key, "session-1", "video", nonce)
        assertFalse(token.contentEquals(ack))
    }

    @Test fun tokenVerificationSucceedsForTheCorrectKeyAndFailsForAnyOther() {
        val key = ChannelSecurity.deriveChannelKey(pairingKey, "session-1", "video", "android_to_mac")
        val wrongKey = ChannelSecurity.deriveChannelKey(pairingKey, "session-1", "input", "android_to_mac")
        val nonce = ChannelSecurity.generateOpenNonce()
        val token = ChannelSecurity.computeToken(key, "session-1", "video", nonce)
        assertTrue(ChannelSecurity.constantTimeEquals(token, ChannelSecurity.computeToken(key, "session-1", "video", nonce)))
        assertFalse(ChannelSecurity.constantTimeEquals(token, ChannelSecurity.computeToken(wrongKey, "session-1", "video", nonce)))
    }

    @Test fun uuidRoundTripsThroughItsSixteenByteBinaryForm() {
        val uuid = java.util.UUID.randomUUID().toString()
        val bytes = ChannelSecurity.uuidToBytes(uuid)
        assertEquals(16, bytes.size)
        assertEquals(uuid, ChannelSecurity.uuidFromBytes(bytes))
    }

    @Test fun sealThenOpenRoundTripsThePlaintext() {
        val key = ChannelSecurity.deriveChannelKey(pairingKey, "session-1", "video", "android_to_mac")
        val plaintext = "hello video channel".toByteArray(Charsets.UTF_8)
        val (nonce, ciphertext) = ChannelSecurity.seal(key, plaintext)
        val opened = ChannelSecurity.open(key, nonce, ciphertext)
        assertArrayEquals(plaintext, opened)
    }

    @Test fun openFailsForATamperedCiphertext() {
        val key = ChannelSecurity.deriveChannelKey(pairingKey, "session-1", "video", "android_to_mac")
        val (nonce, ciphertext) = ChannelSecurity.seal(key, "hello".toByteArray(Charsets.UTF_8))
        val tampered = ciphertext.copyOf().also { it[0] = it[0].inc() }
        assertEquals(null, ChannelSecurity.open(key, nonce, tampered))
    }

    @Test fun sealProducesADifferentNonceEveryTime() {
        val key = ChannelSecurity.deriveChannelKey(pairingKey, "session-1", "video", "android_to_mac")
        val (nonceA, _) = ChannelSecurity.seal(key, "same plaintext".toByteArray())
        val (nonceB, _) = ChannelSecurity.seal(key, "same plaintext".toByteArray())
        assertNotEquals(nonceA.toList(), nonceB.toList())
    }
}
