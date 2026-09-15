package dev.bridgey.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

private const val CHANNEL_ID = "bridgey_screen_share_v1"
private const val NOTIFICATION_ID = 42_460

/**
 * Foreground service required by the platform to host a MediaProjection capture
 * (foregroundServiceType="mediaProjection"). Thin wrapper: all actual capture/encode/channel logic
 * lives in ScreenCaptureManager (PairingCoordinator.screenCapture), mirroring how
 * BridgeyConnectionService is a thin host around PairingCoordinator itself.
 */
class ScreenCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as BridgeyApplication).pairing.screenCapture.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val metrics = windowManagerMetrics()
        (application as BridgeyApplication).pairing.screenCapture.start(
            projectionManager, resultCode, data, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
        )
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun windowManagerMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Streaming screen to Mac")
            .setSmallIcon(dev.bridgey.android.R.drawable.ic_bridgey_notification)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        (application as BridgeyApplication).pairing.screenCapture.stop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.bridgey.android.SCREEN_CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
    }
}
