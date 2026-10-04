package dev.bridgey.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryTxtRecordTest {
    @Test fun parsesBoundedHints() {
        val peer = DiscoveryTxtRecord.parse(
            "Bridgey-Pixel",
            mapOf(
                "id" to "550e8400-e29b-41d4-a716-446655440000".bytes(),
                "name" to "Pixel 10 Pro".bytes(),
                "platform" to "android".bytes(),
                "version" to "1".bytes(),
            ),
        )
        assertEquals("Pixel 10 Pro", peer.deviceNameHint)
        assertEquals(1, peer.protocolVersionHint)
    }

    @Test fun rejectsOversizedAndInvalidHints() {
        val peer = DiscoveryTxtRecord.parse(
            "Safe fallback",
            mapOf(
                "name" to "x".repeat(65).bytes(),
                "id" to "not-a-uuid".bytes(),
                "platform" to "MAC OS!".bytes(),
                "version" to "0".bytes(),
            ),
        )
        assertEquals("Safe fallback", peer.deviceNameHint)
        assertNull(peer.deviceIdHint)
        assertNull(peer.platformHint)
        assertNull(peer.protocolVersionHint)
    }

    @Test fun typeIsAnAdditiveHintAndUnknownKeysAreIgnored() {
        val peer = DiscoveryTxtRecord.parse(
            "Bridgey-a",
            mapOf(
                "id" to "550e8400-e29b-41d4-a716-446655440000".bytes(),
                "type" to "tablet".bytes(),
                "future-key" to "whatever".bytes(),
            ),
        )
        assertEquals("tablet", peer.deviceTypeHint)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", peer.deviceIdHint)
        assertNull(DiscoveryTxtRecord.parse("s", mapOf("type" to "Not A Type!".bytes())).deviceTypeHint)
        assertNull(DiscoveryTxtRecord.parse("s", mapOf("id" to "550e8400-e29b-41d4-a716-446655440000".bytes())).deviceTypeHint)
    }

    private fun String.bytes() = toByteArray(Charsets.UTF_8)
}

