package dev.bridgey.android

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.File

sealed class TemperatureStatus {
    data class Known(val thermalState: String, val celsius: Int?) : TemperatureStatus()
    object Unavailable : TemperatureStatus()
}

/// Maps PowerManager's THERMAL_STATUS_* ints to the small stable wire vocabulary shared with
/// macOS's ProcessInfo.ThermalState ("none"/"nominal" both mean no thermal pressure). Android has
/// more levels than macOS; the extremes still round-trip losslessly on the wire, they just fold
/// together for display (see [thermalDisplayLabel]).
fun androidThermalStateName(status: Int): String? = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "none"
    PowerManager.THERMAL_STATUS_LIGHT -> "light"
    PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
    PowerManager.THERMAL_STATUS_SEVERE -> "severe"
    PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
    else -> null
}

private val CPU_ZONE_TYPE = Regex("^cpu")

/// Pure calc: given each thermal zone's raw `type` label and millidegree reading, returns the max
/// across zones that look CPU-related, in whole Celsius - or null if none qualify or the reading is
/// outside a sane device range (real temps are well under 150C; anything else is a misread/garbage
/// zone, not a value worth reporting).
fun maxCpuTemperatureCelsius(zones: List<Pair<String, Int>>): Int? {
    val millidegrees = zones.filter { (type, _) -> CPU_ZONE_TYPE.containsMatchIn(type) }.map { it.second }
    if (millidegrees.isEmpty()) return null
    val maxMilli = millidegrees.max()
    if (maxMilli !in 0..150_000) return null
    return Math.round(maxMilli / 1000.0).toInt()
}

/// Best-effort real CPU-area temperature from raw thermal-zone sysfs nodes. NOT a documented or
/// guaranteed Android API - confirmed readable without any permission on this project's own test
/// device (Samsung S23 Ultra, Android 16) via zones named cpuss-*/cpu-<cluster>-<core>, but sysfs
/// permissions are OEM/kernel-specific and commonly locked to root on other devices/versions.
///
/// Each zone is read independently (own try/catch) rather than under one shared try: some zones on
/// this device throw EINVAL on read (transient/driver-specific, not a permission issue) - letting
/// that abort the whole scan would silently discard every OTHER zone's perfectly good reading, not
/// just the one that failed.
fun currentAndroidCpuTemperatureCelsius(): Int? {
    val zones = try {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
    } catch (e: Exception) {
        android.util.Log.w("Bridgey", "TEMPERATURE zone listing failed: ${e.message}")
        null
    } ?: return null
    val readings = zones.mapNotNull { zone ->
        try {
            val type = File(zone, "type").takeIf { it.canRead() }?.readText()?.trim() ?: return@mapNotNull null
            val milli = File(zone, "temp").takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull() ?: return@mapNotNull null
            type to milli
        } catch (e: Exception) {
            android.util.Log.w("Bridgey", "TEMPERATURE zone read failed for ${zone.name}: ${e.message}")
            null
        }
    }
    return maxCpuTemperatureCelsius(readings)
}

/// PowerManager.getCurrentThermalStatus() needs API 29+ (Bridgey's minSdk is 26) - reports explicit
/// Unavailable below that rather than guessing.
fun currentAndroidTemperatureStatus(context: Context): TemperatureStatus {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return TemperatureStatus.Unavailable
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        ?: return TemperatureStatus.Unavailable
    val stateName = androidThermalStateName(powerManager.currentThermalStatus) ?: return TemperatureStatus.Unavailable
    return TemperatureStatus.Known(thermalState = stateName, celsius = currentAndroidCpuTemperatureCelsius())
}

/// Shared cross-platform display bucket for ANY thermal state string this app might show (its own
/// platform's values, or a peer's - macOS uses a different vocabulary). Purely a display concern:
/// the real platform-native state string is still what's stored/sent: this only picks a label and a
/// severity tier (0=fine .. 3=critical) for consistent, understandable styling on either side.
fun thermalDisplayLabel(state: String): Pair<String, Int> = when (state) {
    "none", "nominal" -> "Normal" to 0
    "light", "fair" -> "Fair" to 1
    "moderate" -> "Warm" to 1
    "serious", "severe" -> "Serious" to 2
    "critical", "emergency", "shutdown" -> "Critical" to 3
    else -> state.replaceFirstChar { it.uppercase() } to 1
}
