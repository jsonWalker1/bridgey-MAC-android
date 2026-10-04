package dev.bridgey.android

import java.io.File

sealed class CpuStatus {
    data class Available(val percent: Int) : CpuStatus()
    object Unavailable : CpuStatus()
}

data class CpuSample(val total: Long, val idle: Long)

/// Parses the first ("cpu ", aggregate across all cores) line of /proc/stat: user nice system idle
/// iowait irq softirq [steal [guest [guest_nice]]], all in USER_HZ jiffies since boot. iowait counts
/// as idle (standard convention - waiting for I/O isn't CPU work). Returns null for anything that
/// doesn't look like a valid aggregate line, rather than throwing.
fun parseProcStatCpuLine(line: String?): CpuSample? {
    if (line == null) return null
    val parts = line.trim().split(Regex("\\s+"))
    if (parts.size < 8 || parts[0] != "cpu") return null
    val fields = parts.drop(1).map { it.toLongOrNull() ?: return null }
    if (fields.size < 7) return null
    val user = fields[0]
    val nice = fields[1]
    val system = fields[2]
    val idle = fields[3]
    val iowait = fields[4]
    val irq = fields[5]
    val softirq = fields[6]
    val steal = fields.getOrElse(7) { 0L }
    if (user < 0 || nice < 0 || system < 0 || idle < 0 || iowait < 0 || irq < 0 || softirq < 0 || steal < 0) return null
    val total = user + nice + system + idle + iowait + irq + softirq + steal
    return CpuSample(total = total, idle = idle + iowait)
}

/// Delta-based utilization between two samples. Needs a previous sample (returns null - "not yet
/// known", distinct from Unavailable - for the very first one, so callers can seed silently rather
/// than briefly flashing "unavailable"). A zero/negative total delta (no elapsed jiffies, or a
/// counter reset/rollover) can't be trusted and reports Unavailable rather than a nonsense number.
fun computeCpuPercent(previous: CpuSample?, current: CpuSample): CpuStatus? {
    if (previous == null) return null
    val totalDelta = current.total - previous.total
    val idleDelta = current.idle - previous.idle
    if (totalDelta <= 0 || idleDelta < 0 || idleDelta > totalDelta) return CpuStatus.Unavailable
    val busy = totalDelta - idleDelta
    val percent = ((busy * 100.0) / totalDelta).let(Math::round).toInt().coerceIn(0, 100)
    return CpuStatus.Available(percent)
}

/// Best-effort: /proc/stat has been SELinux-restricted for regular (untrusted_app) processes since
/// Android 8, and remains inaccessible even on this project's own test device (Samsung S23 Ultra,
/// Android 16/API 36) - confirmed via `adb shell run-as dev.bridgey.android cat /proc/stat` ->
/// "Permission denied". Any read failure (SecurityException, IOException, missing file, malformed
/// content) returns null rather than throwing, matching the project's "explicit unavailable, never
/// invent a value" telemetry policy. Kept as a real attempt (not stubbed to always-null) so it still
/// works on whatever device/OS combination it remains permitted on.
fun readProcStatCpuSample(): CpuSample? = try {
    val line = File("/proc/stat").bufferedReader().use { it.readLine() }
    parseProcStatCpuLine(line)
} catch (e: Exception) {
    android.util.Log.w("Bridgey", "CPU read failed: ${e.message}")
    null
}
