package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidStorageTest {
    @Test
    fun normalizesValidStorage() {
        assertEquals(
            LocalStorageStatus(usedBytes = 512_000_000_000L, totalBytes = 1_000_000_000_000L),
            normalizedStorageStatus(usedBytes = 512_000_000_000L, totalBytes = 1_000_000_000_000L),
        )
    }

    @Test
    fun clampsUsedAboveTotal() {
        assertEquals(1_000L, normalizedStorageStatus(usedBytes = 2_000L, totalBytes = 1_000L)?.usedBytes)
    }

    @Test
    fun rejectsInvalidTotals() {
        assertNull(normalizedStorageStatus(usedBytes = 0L, totalBytes = 0L))
        assertNull(normalizedStorageStatus(usedBytes = 0L, totalBytes = -1L))
        assertNull(normalizedStorageStatus(usedBytes = -1L, totalBytes = 1_000L))
    }
}
