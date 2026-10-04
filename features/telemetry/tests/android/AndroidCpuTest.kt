package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCpuTest {
    @Test
    fun parsesValidAggregateLine() {
        val sample = parseProcStatCpuLine("cpu  100 10 50 800 5 0 0 0 0 0")
        assertEquals(CpuSample(total = 965, idle = 805), sample)
    }

    @Test
    fun rejectsNonCpuLine() {
        assertNull(parseProcStatCpuLine("cpu0 100 10 50 800 5 0 0"))
    }

    @Test
    fun rejectsMalformedLine() {
        assertNull(parseProcStatCpuLine("cpu  not a number here at all"))
        assertNull(parseProcStatCpuLine("cpu  100 10"))
        assertNull(parseProcStatCpuLine(null))
        assertNull(parseProcStatCpuLine(""))
    }

    @Test
    fun rejectsNegativeFields() {
        assertNull(parseProcStatCpuLine("cpu  -5 10 50 800 5 0 0"))
    }

    @Test
    fun firstSampleReturnsNullNotUnavailable() {
        // Distinct from Unavailable: caller should seed silently, not show an error state.
        val current = CpuSample(total = 1000, idle = 800)
        assertNull(computeCpuPercent(previous = null, current = current))
    }

    @Test
    fun idleSystemReportsLowPercent() {
        val previous = CpuSample(total = 1000, idle = 900)
        val current = CpuSample(total = 2000, idle = 1890)
        // busy = 1000 - 990 = 10 -> 1%
        assertEquals(CpuStatus.Available(1), computeCpuPercent(previous, current))
    }

    @Test
    fun normalUtilizationComputesExpectedPercent() {
        val previous = CpuSample(total = 1000, idle = 800)
        val current = CpuSample(total = 2000, idle = 1400)
        // totalDelta=1000, idleDelta=600, busy=400 -> 40%
        assertEquals(CpuStatus.Available(40), computeCpuPercent(previous, current))
    }

    @Test
    fun highUtilizationNearlyPegsCpu() {
        val previous = CpuSample(total = 1000, idle = 800)
        val current = CpuSample(total = 2000, idle = 805)
        // totalDelta=1000, idleDelta=5, busy=995 -> 100% (rounds up from 99.5)
        val status = computeCpuPercent(previous, current)
        assertTrue(status is CpuStatus.Available && status.percent in 99..100)
    }

    @Test
    fun zeroElapsedTimeIsUnavailable() {
        val sample = CpuSample(total = 1000, idle = 800)
        assertEquals(CpuStatus.Unavailable, computeCpuPercent(sample, sample))
    }

    @Test
    fun counterRolloverOrResetIsUnavailable() {
        val previous = CpuSample(total = 5000, idle = 4000)
        val current = CpuSample(total = 100, idle = 50) // counters went backwards
        assertEquals(CpuStatus.Unavailable, computeCpuPercent(previous, current))
    }

    @Test
    fun idleDeltaExceedingTotalDeltaIsUnavailable() {
        // Shouldn't happen with real data, but must not crash or go negative.
        val previous = CpuSample(total = 1000, idle = 100)
        val current = CpuSample(total = 1100, idle = 1000)
        assertEquals(CpuStatus.Unavailable, computeCpuPercent(previous, current))
    }

    @Test
    fun percentIsClampedTo0To100() {
        val previous = CpuSample(total = 1000, idle = 1000)
        val current = CpuSample(total = 2000, idle = 1000)
        // totalDelta=1000, idleDelta=0, busy=1000 -> exactly 100%, must not exceed
        val status = computeCpuPercent(previous, current)
        assertEquals(CpuStatus.Available(100), status)
    }
}
