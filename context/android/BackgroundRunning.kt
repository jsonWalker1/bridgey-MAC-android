package dev.bridgey.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization exemption ("run in background without restrictions"). Bridgey keeps a
 * foreground service for the Mac connection; the exemption is the safety net for the moments it is
 * not running (e.g. right after an update), when Samsung's Freecess would otherwise freeze the app.
 */
internal object BackgroundRunning {
    private const val PREFS = "bridgey_background"
    private const val ASKED = "asked_ignore_battery_optimizations"

    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** The system dialog "Let app always run in background?". */
    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        runCatching { context.startActivity(direct) }.recoverCatching { context.startActivity(fallback) }
    }

    /** Shows the dialog at most once; afterwards only the "Background running" card offers it. */
    fun askOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false) || isUnrestricted(context)) return
        prefs.edit().putBoolean(ASKED, true).apply()
        request(context)
    }
}
