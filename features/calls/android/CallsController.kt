package dev.bridgey.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

/**
 * Call-specific state resolution and system call-control actions, extracted from
 * BridgeyNotificationListenerService so the notification listener only owns generic
 * notification plumbing. Behavior is unchanged from the pre-extraction implementation;
 * this file introduces no new call functionality.
 */

internal enum class SystemCallAction { ANSWER, END }

// BRIDGEY CALL CONTINUITY: audio route selection. Kept as plain strings on the wire (matching
// every other Bridgey message payload convention) rather than a new enum-encoding scheme.
internal val KNOWN_AUDIO_ROUTES = setOf("EARPIECE", "SPEAKER", "BLUETOOTH")

internal fun isValidAudioRoute(route: String?): Boolean = route == null || route in KNOWN_AUDIO_ROUTES

internal data class NotificationActionCandidate(
    val title: String,
    val pendingIntent: PendingIntent? = null,
    val remoteInputs: List<RemoteInput> = emptyList(),
    val systemCallAction: SystemCallAction? = null,
)

internal data class CallStyleFallbackAction(val title: String, val extraKey: String)

internal fun notificationCallType(value: Int): String = when (value) {
    1 -> "incoming"
    2 -> "ongoing"
    3 -> "screening"
    else -> "unknown"
}

internal fun telephonyCallType(value: Int): String? = when (value) {
    TelephonyManager.CALL_STATE_RINGING -> "incoming"
    TelephonyManager.CALL_STATE_OFFHOOK -> "ongoing"
    TelephonyManager.CALL_STATE_IDLE -> "idle"
    else -> null
}

internal fun resolvedNotificationCallType(
    reportedType: String,
    hasAnswer: Boolean,
    hasDecline: Boolean,
    hasHangUp: Boolean,
    hasFullScreenIntent: Boolean = false,
    telephonyCallType: String? = null,
): String = when {
    telephonyCallType == "incoming" -> "incoming"
    telephonyCallType == "ongoing" -> "ongoing"
    // Samsung's dialer reports CALL_TYPE_ONGOING while the phone is still ringing.
    // Its full-screen intent is the stable, language-independent incoming-call signal.
    hasFullScreenIntent -> "incoming"
    hasAnswer && hasDecline && !hasHangUp -> "incoming"
    hasAnswer && hasHangUp && !hasDecline -> "screening"
    hasHangUp && !hasAnswer && !hasDecline -> "ongoing"
    else -> reportedType
}

internal fun resolvedNotificationCallType(notification: Notification, telephonyCallType: String?): String {
    val reportedType = notificationCallType(notification.extras.getInt("android.callType", 0))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return reportedType
    val hasAnswer = notification.extras.callActionPendingIntent(Notification.EXTRA_ANSWER_INTENT) != null
    val hasDecline = notification.extras.callActionPendingIntent(Notification.EXTRA_DECLINE_INTENT) != null
    val hasHangUp = notification.extras.callActionPendingIntent(Notification.EXTRA_HANG_UP_INTENT) != null
    val hasFullScreenIntent = notification.fullScreenIntent != null
    val resolvedType = resolvedNotificationCallType(
        reportedType = reportedType,
        hasAnswer = hasAnswer,
        hasDecline = hasDecline,
        hasHangUp = hasHangUp,
        hasFullScreenIntent = hasFullScreenIntent,
        telephonyCallType = telephonyCallType,
    )
    android.util.Log.d(
        "Bridgey",
        "PLUGIN call state reported=$reportedType resolved=$resolvedType " +
            "phoneState=${telephonyCallType ?: "unavailable"} fullScreen=$hasFullScreenIntent " +
            "answer=$hasAnswer decline=$hasDecline hangUp=$hasHangUp",
    )
    return resolvedType
}

internal fun systemCallActionTitles(callType: String, sdkInt: Int): List<String> = when (callType) {
    "incoming" -> buildList {
        if (sdkInt >= Build.VERSION_CODES.P) add("Decline")
        add("Answer")
    }
    "ongoing" -> if (sdkInt >= Build.VERSION_CODES.P) listOf("Hang Up") else emptyList()
    "screening" -> buildList {
        if (sdkInt >= Build.VERSION_CODES.P) add("Hang Up")
        add("Answer")
    }
    else -> emptyList()
}

internal fun systemCallActionCandidates(callType: String, sdkInt: Int): List<NotificationActionCandidate> =
    systemCallActionTitles(callType, sdkInt).map { title ->
        NotificationActionCandidate(
            title = title,
            systemCallAction = if (title == "Answer") SystemCallAction.ANSWER else SystemCallAction.END,
        )
    }

internal fun callStyleFallbackActions(callType: String): List<CallStyleFallbackAction> = when (callType) {
    "incoming" -> listOf(
        CallStyleFallbackAction("Decline", Notification.EXTRA_DECLINE_INTENT),
        CallStyleFallbackAction("Answer", Notification.EXTRA_ANSWER_INTENT),
    )
    "ongoing" -> listOf(CallStyleFallbackAction("Hang Up", Notification.EXTRA_HANG_UP_INTENT))
    "screening" -> listOf(
        CallStyleFallbackAction("Hang Up", Notification.EXTRA_HANG_UP_INTENT),
        CallStyleFallbackAction("Answer", Notification.EXTRA_ANSWER_INTENT),
    )
    else -> emptyList()
}

internal fun shouldDelayCallPost(callType: String?): Boolean = callType == "ongoing"

internal fun notificationActionCandidates(
    notification: Notification,
    callType: String?,
    canControlSystemCalls: Boolean,
): List<NotificationActionCandidate> {
    if (callType != null && canControlSystemCalls) {
        return systemCallActionCandidates(callType, Build.VERSION.SDK_INT)
    }
    val candidates = notification.actions.orEmpty().mapNotNull { action ->
        val pendingIntent = action.actionIntent ?: return@mapNotNull null
        NotificationActionCandidate(
            title = action.title?.toString().orEmpty(),
            pendingIntent = pendingIntent,
            remoteInputs = action.remoteInputs.orEmpty().toList(),
        )
    }.toMutableList()
    if (callType != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        callStyleFallbackActions(callType).forEach { action ->
            val pendingIntent = notification.extras.callActionPendingIntent(action.extraKey) ?: return@forEach
            if (candidates.none { it.pendingIntent == pendingIntent }) {
                candidates += NotificationActionCandidate(action.title, pendingIntent)
            }
        }
        return candidates
    }
    // BRIDGEY NOTIFICATION++ POC: a generic "Open" action, entirely independent of any specific
    // app - `contentIntent` is the PendingIntent every notification uses for its own default
    // tap-to-open behavior (not an entry in `notification.actions`, which only covers explicit
    // action buttons like WhatsApp's "Reply"/"Mark as read" - WhatsApp's main message notification
    // has no declared "Open" action at all, so without this there is no way to open the app
    // remotely). Appended last so real app-declared actions keep priority within the existing
    // 4-action cap (see storeActions/MAX_FORWARDED_ACTIONS) - a notification with 4+ of its own
    // actions won't also get "Open", a documented, deliberately minimal POC limitation.
    notification.contentIntent?.let { candidates += NotificationActionCandidate("Open", it) }
    return candidates
}

@Suppress("DEPRECATION")
private fun Bundle.callActionPendingIntent(key: String): PendingIntent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, PendingIntent::class.java)
    } else {
        getParcelable(key) as? PendingIntent
    }

/**
 * Owns telephony call-state observation and executes system call-control actions
 * (answer/hang-up) via TelecomManager on behalf of BridgeyNotificationListenerService.
 * `isCallIntegrationEnabled` mirrors the service's prior direct read of
 * `BridgeySettings.state.value.directCallsEnabled`.
 */
internal class CallsController(
    private val context: Context,
    private val isCallIntegrationEnabled: () -> Boolean,
) {
    private var telephonyCallback: TelephonyCallback? = null

    @Suppress("DEPRECATION")
    fun currentTelephonyCallType(): String? {
        if (!isCallIntegrationEnabled()) return null
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return telephonyCallType(context.getSystemService(TelephonyManager::class.java).callState)
    }

    fun canControlSystemCalls(): Boolean =
        isCallIntegrationEnabled() &&
            context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun performSystemCallAction(action: SystemCallAction): Boolean {
        if (!canControlSystemCalls()) return false
        val telecom = context.getSystemService(TelecomManager::class.java)
        return when (action) {
            SystemCallAction.ANSWER -> {
                telecom.acceptRingingCall()
                true
            }
            SystemCallAction.END -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && telecom.endCall()
        }
    }

    /**
     * BRIDGEY CALL CONTINUITY: routes the current/next call's audio to the requested endpoint.
     * Prefers the modern `AudioManager.setCommunicationDevice` API (S+, the platform's own
     * recommended replacement for the deprecated speakerphone/BluetoothSco toggles) and falls
     * back to those deprecated calls below S, since minSdk is 26. Never throws - a route that
     * disappears between the Mac's selection and this call (Bluetooth disconnecting while
     * ringing, say) just fails to apply rather than crashing; the call itself is unaffected
     * since answering and routing are separate steps.
     */
    @Suppress("DEPRECATION")
    fun applyAudioRoute(route: String): Boolean {
        if (route !in KNOWN_AUDIO_ROUTES) return false
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val device = audioManager.availableCommunicationDevices.firstOrNull { matchesRoute(it, route) }
                    ?: return false
                audioManager.setCommunicationDevice(device)
            } else {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                when (route) {
                    "EARPIECE" -> {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                        audioManager.isSpeakerphoneOn = false
                    }
                    "SPEAKER" -> {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                        audioManager.isSpeakerphoneOn = true
                    }
                    "BLUETOOTH" -> {
                        if (!audioManager.isBluetoothScoAvailableOffCall) return false
                        audioManager.isSpeakerphoneOn = false
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                    }
                }
                true
            }
        }.getOrElse {
            android.util.Log.w("Bridgey", "PLUGIN audio route apply failed route=$route", it)
            false
        }
    }

    /** Clears any call-audio routing back to the system default - called once the call ends, so
     *  Android never gets stuck in speakerphone/Bluetooth-SCO mode afterward. */
    @Suppress("DEPRECATION")
    fun resetAudioRoute() {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                audioManager.isSpeakerphoneOn = false
                if (audioManager.isBluetoothScoOn) {
                    audioManager.isBluetoothScoOn = false
                    audioManager.stopBluetoothSco()
                }
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }.onFailure { android.util.Log.w("Bridgey", "PLUGIN audio route reset failed", it) }
    }

    /** Routes Android actually reports as available right now - the Mac must only ever offer a
     *  route that genuinely exists (never hardcode Bluetooth as available, per spec). */
    fun availableAudioRoutes(): List<String> {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return listOf("EARPIECE")
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                buildList {
                    if (devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }) add("EARPIECE")
                    if (devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) add("SPEAKER")
                    if (devices.any { it.type in BLUETOOTH_CALL_DEVICE_TYPES }) add("BLUETOOTH")
                }
            } else {
                @Suppress("DEPRECATION")
                buildList {
                    add("EARPIECE")
                    add("SPEAKER")
                    if (audioManager.isBluetoothScoAvailableOffCall) add("BLUETOOTH")
                }
            }
        }.getOrDefault(listOf("EARPIECE"))
    }

    /** The connected Bluetooth call device's own reported name (S+ only - see class doc for why
     *  there's no generic pre-S equivalent). `null` means "show a generic Bluetooth label instead
     *  of a device name" - never a hardcoded device name. */
    fun bluetoothRouteName(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return null
        return runCatching {
            audioManager.availableCommunicationDevices
                .firstOrNull { it.type in BLUETOOTH_CALL_DEVICE_TYPES }
                ?.productName?.toString()
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun matchesRoute(device: AudioDeviceInfo, route: String): Boolean = when (route) {
        "EARPIECE" -> device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        "SPEAKER" -> device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        "BLUETOOTH" -> device.type in BLUETOOTH_CALL_DEVICE_TYPES
        else -> false
    }

    fun updateTelephonyCallback(listener: CallActivityListener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        unregisterTelephonyCallback()
        if (!isCallIntegrationEnabled()) return
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val callback = BridgeyCallStateCallback(listener)
        runCatching {
            // context.mainExecutor requires API 28+; only reached here on API 31+ (the guard above).
            context.getSystemService(TelephonyManager::class.java).registerTelephonyCallback(context.mainExecutor, callback)
            telephonyCallback = callback
        }.onFailure { android.util.Log.w("Bridgey", "PLUGIN call-state listener registration failed", it) }
    }

    fun unregisterTelephonyCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        telephonyCallback?.let { callback ->
            runCatching { context.getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(callback) }
        }
        telephonyCallback = null
    }

    internal interface CallActivityListener {
        fun onRingingOrActive()
        fun onIdle()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class BridgeyCallStateCallback(
        private val listener: CallActivityListener,
    ) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            android.util.Log.d("Bridgey", "PLUGIN telephony state=${telephonyCallType(state) ?: "idle"}")
            when (state) {
                TelephonyManager.CALL_STATE_RINGING,
                TelephonyManager.CALL_STATE_OFFHOOK,
                -> listener.onRingingOrActive()
                TelephonyManager.CALL_STATE_IDLE -> listener.onIdle()
            }
        }
    }

    private companion object {
        @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
        val BLUETOOTH_CALL_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}
