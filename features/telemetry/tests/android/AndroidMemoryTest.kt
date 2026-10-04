package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMemoryTest {
    @Test
    fun normalizesValidMemory() {
        assertEquals(
            LocalMemoryStatus(usedBytes = 6_000_000_000L, totalBytes = 12_000_000_000L),
            normalizedMemoryStatus(usedBytes = 6_000_000_000L, totalBytes = 12_000_000_000L),
        )
    }

    @Test
    fun clampsUsedAboveTotal() {
        assertEquals(1_000L, normalizedMemoryStatus(usedBytes = 2_000L, totalBytes = 1_000L)?.usedBytes)
    }

    @Test
    fun rejectsInvalidTotals() {
        assertNull(normalizedMemoryStatus(usedBytes = 0L, totalBytes = 0L))
        assertNull(normalizedMemoryStatus(usedBytes = 0L, totalBytes = -1L))
        assertNull(normalizedMemoryStatus(usedBytes = -1L, totalBytes = 1_000L))
    }

    @Test
    fun usedNeverExceedsTotal() {
        val status = normalizedMemoryStatus(usedBytes = 999_999L, totalBytes = 1_000L)
        assertTrue(status != null && status.usedBytes <= status.totalBytes)
    }

    @Test
    fun acceptsPositiveTotalWithZeroUsed() {
        assertEquals(0L, normalizedMemoryStatus(usedBytes = 0L, totalBytes = 1_000L)?.usedBytes)
    }
}
