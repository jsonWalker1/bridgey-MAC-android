package dev.bridgey.android

import android.content.Context
import android.os.Environment
import android.os.StatFs

data class LocalStorageStatus(val usedBytes: Long, val totalBytes: Long)

fun normalizedStorageStatus(usedBytes: Long, totalBytes: Long): LocalStorageStatus? {
    if (totalBytes <= 0 || usedBytes < 0) return null
    return LocalStorageStatus(usedBytes = usedBytes.coerceAtMost(totalBytes), totalBytes = totalBytes)
}

/// Reads the primary internal/user-visible storage (the `/data` partition StatFs already needs no
/// runtime permission for) rather than any removable/secondary volume, matching what Android's own
/// Settings > Storage panel reports.
fun currentAndroidStorageStatus(context: Context): LocalStorageStatus? = try {
    val stat = StatFs(Environment.getDataDirectory().path)
    val totalBytes = stat.blockCountLong * stat.blockSizeLong
    val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
    normalizedStorageStatus(usedBytes = totalBytes - availableBytes, totalBytes = totalBytes)
} catch (e: Exception) {
    android.util.Log.w("Bridgey", "STORAGE read failed: ${e.message}")
    null
}
