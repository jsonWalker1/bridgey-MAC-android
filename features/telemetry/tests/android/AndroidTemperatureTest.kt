package dev.bridgey.android

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidTemperatureTest {
    @Test
    fun mapsAllKnownThermalStatusValues() {
        assertEquals("none", androidThermalStateName(PowerManager.THERMAL_STATUS_NONE))
        assertEquals("light", androidThermalStateName(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals("moderate", androidThermalStateName(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals("severe", androidThermalStateName(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals("critical", androidThermalStateName(PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals("emergency", androidThermalStateName(PowerManager.THERMAL_STATUS_EMERGENCY))
        assertEquals("shutdown", androidThermalStateName(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test
    fun rejectsUnknownThermalStatusValue() {
        assertNull(androidThermalStateName(999))
    }

    @Test
    fun maxCpuTemperatureTakesHottestCpuRelatedZone() {
        val zones = listOf(
            "aoss-0" to 31_800,
            "cpuss-0" to 32_600,
            "cpu-1-3" to 40_600,
            "gpuss-0" to 31_900,
            "cpu-0-1" to 33_700,
        )
        assertEquals(41, maxCpuTemperatureCelsius(zones))
    }

    @Test
    fun ignoresNonCpuZonesEntirely() {
        val zones = listOf("battery" to 35_000, "gpuss-0" to 40_000, "video" to 50_000)
        assertNull(maxCpuTemperatureCelsius(zones))
    }

    @Test
    fun rejectsOutOfRangeReadings() {
        // A misread/garbage zone reporting an impossible value must not be trusted.
        val zones = listOf("cpu-0-0" to 999_999_999)
        assertNull(maxCpuTemperatureCelsius(zones))
    }

    @Test
    fun rejectsNegativeReadings() {
        val zones = listOf("cpu-0-0" to -5000)
        assertNull(maxCpuTemperatureCelsius(zones))
    }

    @Test
    fun emptyZoneListIsUnavailable() {
        assertNull(maxCpuTemperatureCelsius(emptyList()))
    }

    @Test
    fun thermalDisplayLabelMapsAndroidAndMacVocabulariesToSharedTiers() {
        assertEquals("Normal" to 0, thermalDisplayLabel("none"))
        assertEquals("Normal" to 0, thermalDisplayLabel("nominal"))
        assertEquals("Fair" to 1, thermalDisplayLabel("light"))
        assertEquals("Fair" to 1, thermalDisplayLabel("fair"))
        assertEquals("Warm" to 1, thermalDisplayLabel("moderate"))
        assertEquals("Serious" to 2, thermalDisplayLabel("serious"))
        assertEquals("Serious" to 2, thermalDisplayLabel("severe"))
        assertEquals("Critical" to 3, thermalDisplayLabel("critical"))
        assertEquals("Critical" to 3, thermalDisplayLabel("emergency"))
        assertEquals("Critical" to 3, thermalDisplayLabel("shutdown"))
    }

    @Test
    fun thermalDisplayLabelFallsBackGracefullyForUnknownState() {
        val (label, tier) = thermalDisplayLabel("weird")
        assertEquals("Weird", label)
        assertEquals(1, tier)
    }
}
