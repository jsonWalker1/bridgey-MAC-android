package dev.bridgey.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

/** NSD SELF-HEALING: discovery never gives up after a failure; it retries with bounded backoff. */
class DiscoveryRetryTest {
    @Test fun retriesQuicklyFirstThenBacksOffToOncePerMinute() {
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 60_000L), (0..5).map(::discoveryRetryDelayMillis))
    }

    @Test fun negativeAttemptIsTreatedAsTheFirst() {
        assertEquals(2_000L, discoveryRetryDelayMillis(-1))
    }
}
