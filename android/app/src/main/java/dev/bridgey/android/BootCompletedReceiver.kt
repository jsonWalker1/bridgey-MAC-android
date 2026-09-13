package dev.bridgey.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the Bridgey foreground service after a device reboot, so discovery/pairing/Photo Sync
 * resume without the user having to manually reopen the app first. Only acts if Bridgey was
 * already turned on before the reboot — BridgeyConnectionService itself checks isBridgeyEnabled
 * again on startup, but reading the preference here first avoids waking the process for nothing
 * when Bridgey was off.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences("bridgey", Context.MODE_PRIVATE)
            .getBoolean("bridgey_enabled", true)
        if (!enabled) return
        context.startForegroundService(Intent(context, BridgeyConnectionService::class.java))
    }
}
