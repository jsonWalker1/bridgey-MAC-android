package dev.bridgey.android

import android.app.ActivityManager
import android.content.Context

data class LocalMemoryStatus(val usedBytes: Long, val totalBytes: Long)

fun normalizedMemoryStatus(usedBytes: Long, totalBytes: Long): LocalMemoryStatus? {
    if (totalBytes <= 0 || usedBytes < 0) return null
    return LocalMemoryStatus(usedBytes = usedBytes.coerceAtMost(totalBytes), totalBytes = totalBytes)
}

/// ActivityManager.getMemoryInfo() needs no runtime permission and matches what the OS itself
/// considers "total"/"used" RAM (same source Settings > Memory-adjacent panels read from).
fun currentAndroidMemoryStatus(context: Context): LocalMemoryStatus? = try {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(info)
    normalizedMemoryStatus(usedBytes = info.totalMem - info.availMem, totalBytes = info.totalMem)
} catch (e: Exception) {
    android.util.Log.w("Bridgey", "MEMORY read failed: ${e.message}")
    null
}
