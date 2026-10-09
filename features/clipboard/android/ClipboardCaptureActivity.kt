package dev.bridgey.android

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class ClipboardCaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDimAmount(0f)
    }

    override fun onResume() {
        super.onResume()
        Handler(Looper.getMainLooper()).postDelayed({
            // Clipboard access is allowed only once this user-initiated activity is foreground.
            val clipboard = getSystemService(ClipboardManager::class.java)
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@ClipboardCaptureActivity)?.toString()
            if (text.isNullOrEmpty()) {
                Toast.makeText(this@ClipboardCaptureActivity, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            } else {
                val appContext = applicationContext
                (application as BridgeyApplication).pairing.sendText(text) { result ->
                    Handler(Looper.getMainLooper()).post {
                        val message = when (result) {
                            ClipboardSendResult.DELIVERED -> "Clipboard sent to Mac"
                            ClipboardSendResult.EMPTY -> "Clipboard is empty"
                            ClipboardSendResult.DISABLED -> "Clipboard is turned off on one of your devices"
                            ClipboardSendResult.NOT_CONNECTED -> "Not connected — clipboard not sent"
                            ClipboardSendResult.CONNECTION_LOST -> "Send failed — connection lost"
                            ClipboardSendResult.NO_ACKNOWLEDGEMENT -> "Mac did not confirm delivery"
                            ClipboardSendResult.TOO_LARGE -> "Clipboard exceeds 32 KiB. Send it as a file."
                            ClipboardSendResult.NO_TARGET -> "Open Bridgey and choose a device to send the clipboard"
                        }
                        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            finishAndRemoveTask()
            overridePendingTransition(0, 0)
        }, 150)
    }
}
