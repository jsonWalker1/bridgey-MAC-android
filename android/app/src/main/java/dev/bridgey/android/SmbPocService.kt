package dev.bridgey.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress

private const val TAG = "SmbPocService"
private const val CHANNEL_ID = "bridgey_smb_poc"
private const val NOTIFICATION_ID = 74450

/**
 * BRIDGEY SMB SERVER FEASIBILITY POC - isolated, throwaway foreground service. See
 * SmbPocServer.kt's doc comment for what this is and how to delete it entirely.
 *
 * Exists only to (a) satisfy Android's requirement that a long-lived network server survive
 * backgrounding via a foreground service, matching the exact pattern already used by
 * BridgeyConnectionService/ScreenCaptureService elsewhere in this app, and (b) resolve the phone's
 * current Wi-Fi IPv4 address to bind the SMB server to (never a wildcard/all-interfaces bind - this
 * is what "bind only to the LAN interface" actually means in code).
 */
internal class SmbPocService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                SmbPocServer.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startPoc()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SmbPocServer.stop()
        super.onDestroy()
    }

    private fun startPoc() {
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        val address = currentWifiIPv4Address()
        if (address == null) {
            Log.e(TAG, "No Wi-Fi IPv4 address found - is the phone on Wi-Fi?")
            updateNotification("Failed: not on Wi-Fi")
            return
        }
        SmbPocServer.start(applicationContext, address)
            .onSuccess {
                updateNotification("Serving \\\\${SmbPocServer.SERVER_NAME}:${SmbPocServer.SMB_PORT}\\${SmbPocServer.SHARE_NAME}")
            }
            .onFailure { updateNotification("Failed: ${it.message}") }
    }

    /** Real LAN-facing address only - deliberately NOT `InetAddress.getLocalHost()` (unreliable on
     *  Android) and NOT a wildcard bind. Mirrors how BridgeyConnectionService's own discovery/pairing
     *  code resolves the active network for the exact same reason. */
    private fun currentWifiIPv4Address(): InetAddress? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val network: Network = cm.activeNetwork ?: return null
        val caps: NetworkCapabilities = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val linkProperties: LinkProperties = cm.getLinkProperties(network) ?: return null
        return linkProperties.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
    }

    private fun buildNotification(status: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bridgey SMB POC", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stopIntent = Intent(this, SmbPocService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Bridgey SMB POC")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_bridgey_notification)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopPending).build())
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        const val ACTION_STOP = "dev.bridgey.android.smbpoc.STOP"
    }
}
