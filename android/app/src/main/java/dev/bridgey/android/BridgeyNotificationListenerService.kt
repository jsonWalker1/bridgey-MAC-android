package dev.bridgey.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class BridgeyNotificationListenerService : NotificationListenerService() {
    private val forwardedNotifications = ForwardedNotificationRegistry()
    private val storedActions = linkedMapOf<String, StoredNotificationAction>()
    private val actionTokensByNotificationId = mutableMapOf<String, List<String>>()
    private val applicationIcons = linkedMapOf<String, String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCallPosts = mutableMapOf<String, Runnable>()
    private val forwardedNotificationIds = mutableSetOf<String>()
    private val remoteDismissTracker = RemoteDismissTracker()
    private val callsController = CallsController(
        context = this,
        isCallIntegrationEnabled = { (application as BridgeyApplication).settings.state.value.directCallsEnabled },
    )
    private val callStateListener = object : CallsController.CallActivityListener {
        override fun onRingingOrActive() = refreshActiveCallNotifications()
        override fun onIdle() = removeActiveForwardedCalls()
    }

    override fun onDestroy() {
        callsController.unregisterTelephonyCallback()
        pendingCallPosts.values.forEach(mainHandler::removeCallbacks)
        pendingCallPosts.clear()
        if (activeService?.get() === this) activeService = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeService = java.lang.ref.WeakReference(this)
        activeNotifications.orEmpty()
            .filterNot { it.packageName == packageName }
            .forEach { forwardedNotifications.record(notificationToken(it.key), it.key, it.packageName) }
        callsController.updateTelephonyCallback(callStateListener)
        android.util.Log.i("Bridgey", "PLUGIN notification listener connected")
    }

    override fun onListenerDisconnected() {
        callsController.unregisterTelephonyCallback()
        if (activeService?.get() === this) activeService = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        android.util.Log.d("Bridgey", "PLUGIN notification observed package=${sbn.packageName}")
        if (sbn.packageName != packageName) logNotificationDiagnostics("POST", sbn)
        val bridgey = application as BridgeyApplication
        if (!bridgey.isPrimaryUser || !bridgey.isBridgeyEnabled || sbn.packageName == packageName) return

        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val isCall = notification.category == Notification.CATEGORY_CALL
        if (shouldIgnoreOngoingNotification(notification.flags, notification.category)) return
        if (notification.visibility == Notification.VISIBILITY_SECRET) return

        val ranking = Ranking()
        val hasRanking = rankingMap.getRanking(sbn.key, ranking)
        if (hasRanking && ranking.importance <= NotificationManager.IMPORTANCE_MIN) return
        val hasSound = notificationIsAudible(
            channelImportance = ranking.channel?.importance?.takeIf { hasRanking },
            channelHasSound = ranking.channel?.sound != null,
        )

        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val applicationName = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)
        bridgey.settings.observeNotificationApplication(sbn.packageName, applicationName)
        if (!bridgey.settings.isNotificationApplicationEnabled(sbn.packageName)) return
        val applicationIcon = applicationIcon(sbn.packageName)

        val telephonyCallType = if (isCall) callsController.currentTelephonyCallType() else null
        if (telephonyCallType == "idle") {
            removeForwardedCall(sbn.key)
            return
        }
        val notificationId = notificationToken(notificationIdentitySeed(sbn.packageName, notification.shortcutId, sbn.key))
        forwardedNotifications.record(notificationId, sbn.key, sbn.packageName)
        pendingCallPosts.remove(notificationId)?.let(mainHandler::removeCallbacks)
        val callType = if (isCall) resolvedNotificationCallType(notification, telephonyCallType) else null
        val audioRoutes = if (isCall && callType == "incoming") callsController.availableAudioRoutes() else null
        val bluetoothRouteName = if (audioRoutes?.contains("BLUETOOTH") == true) callsController.bluetoothRouteName() else null
        val actions = storeActions(
            notificationId,
            notificationActionCandidates(notification, callType, callsController.canControlSystemCalls()),
        )
        val forward = Runnable {
            pendingCallPosts.remove(notificationId)
            if (forwardedNotifications.systemKey(notificationId) != sbn.key) return@Runnable
            bridgey.pairing.sendNotification(
                packageName = sbn.packageName,
                applicationName = applicationName,
                notificationId = notificationId,
                title = title,
                text = text,
                timestamp = sbn.postTime,
                actions = actions,
                applicationIcon = applicationIcon,
                callType = callType,
                hasSound = hasSound,
                availableAudioRoutes = audioRoutes,
                bluetoothRouteName = bluetoothRouteName,
            )
            forwardedNotificationIds += notificationId
            while (forwardedNotificationIds.size > MAX_TRACKED_FORWARDED_NOTIFICATIONS) {
                forwardedNotificationIds.remove(forwardedNotificationIds.first())
            }
        }
        if (shouldDelayCallPost(callType)) {
            pendingCallPosts[notificationId] = forward
            mainHandler.postDelayed(forward, CALL_POST_SETTLE_DELAY_MS)
        } else {
            forward.run()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        if (sbn.packageName != packageName) logNotificationDiagnostics("REMOVE", sbn)
        val notificationId = forwardedNotifications.removeSystemKey(sbn.key) ?: return
        pendingCallPosts.remove(notificationId)?.let(mainHandler::removeCallbacks)
        removeActions(notificationId)
        if (!forwardedNotificationIds.remove(notificationId)) return
        // BRIDGEY NOTIFICATION++ POC: this removal was Mac's own dismiss request completing, not an
        // independent local action - see RemoteDismissTracker's doc comment. Consume the marker and
        // stop here rather than echoing a redundant notifications.remove back to Mac.
        if (remoteDismissTracker.consumeIfPending(notificationId)) return
        val bridgey = application as BridgeyApplication
        if (!bridgey.isPrimaryUser || !bridgey.isBridgeyEnabled) return
        bridgey.pairing.sendNotificationRemoved(notificationId)
    }

    // BRIDGEY NOTIFICATION++ POC PHASE 2 (TEMPORARY): logs every field that could plausibly identify
    // a "logical conversation" so real WhatsApp POST/REMOVE events can be compared - see the
    // Notification++ Phase 2 audit. Remove once the conversation-identity strategy is confirmed
    // against real device behavior; not meant to ship long-term.
    private fun logNotificationDiagnostics(phase: String, sbn: StatusBarNotification) {
        runCatching {
            val n = sbn.notification
            val extras = n.extras
            val shortcutId = if (android.os.Build.VERSION.SDK_INT >= 26) n.shortcutId else null
            val channelId = if (android.os.Build.VERSION.SDK_INT >= 26) n.channelId else null
            val isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
            val messagingMessages = runCatching {
                @Suppress("DEPRECATION")
                val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                raw?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }
                    ?.map { "sender=${it.senderPerson?.name ?: it.sender} text=${it.text}" }
            }.getOrNull()
            val historicMessages = runCatching {
                @Suppress("DEPRECATION")
                val raw = extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES)
                raw?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }
                    ?.map { "sender=${it.senderPerson?.name ?: it.sender} text=${it.text}" }
            }.getOrNull()
            val people = runCatching {
                extras.getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST)
                    ?.map { "name=${it.name} hasIcon=${it.icon != null} uri=${it.uri}" }
            }.getOrNull()
            android.util.Log.i(
                "Bridgey",
                "NOTIF_DIAG phase=$phase package=${sbn.packageName} id=${sbn.id} tag=${sbn.tag} " +
                    "key=${sbn.key} groupKey=${sbn.groupKey} isGroupSummary=$isGroupSummary " +
                    "channelId=$channelId shortcutId=$shortcutId category=${n.category} flags=${n.flags} " +
                    "conversationTitle=${extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)} " +
                    "title=${extras.getCharSequence(Notification.EXTRA_TITLE)} " +
                    "text=${extras.getCharSequence(Notification.EXTRA_TEXT)} " +
                    "bigText=${extras.getCharSequence(Notification.EXTRA_BIG_TEXT)} " +
                    "subText=${extras.getCharSequence(Notification.EXTRA_SUB_TEXT)} " +
                    "selfDisplayName=${extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)} " +
                    "people=$people messages=$messagingMessages historicMessages=$historicMessages",
            )
        }.onFailure { android.util.Log.w("Bridgey", "NOTIF_DIAG failed", it) }
    }

    private fun dismissForwardedNotification(notificationId: String): Boolean {
        val systemKey = forwardedNotifications.systemKey(notificationId) ?: return false
        remoteDismissTracker.markPending(notificationId)
        android.os.Handler(android.os.Looper.getMainLooper()).post { cancelNotification(systemKey) }
        return true
    }

    /**
     * BRIDGEY NOTIFICATION++ POC: re-forwards every currently active, eligible notification through
     * the exact same [onNotificationPosted] path used for a real new post/update - called once
     * Bridgey's pairing session (re)reaches Connected (see PairingCoordinator.completeIfConfirmed's
     * mediaRemote.sendFreshState() for the identical existing pattern for media state). This is what
     * makes a notification that arrived while the Mac was disconnected show up once it reconnects,
     * instead of being silently lost forever (the previous behavior: onNotificationPosted's own
     * sendNotification call already no-ops safely while disconnected, but nothing ever retried it).
     *
     * Deliberately reuses [onNotificationPosted] rather than a new resync-specific code path: the
     * Mac side's UNNotificationRequest identifier is stable per notificationId (see
     * remoteNotificationRequestIdentifier), so UNUserNotificationCenter.add() with that same
     * identifier safely replaces an already-delivered banner instead of duplicating it - resyncing
     * a notification the Mac already has is a safe no-visual-op, not a duplicate-creation risk.
     */
    private fun resyncActiveNotifications() {
        val ranking = currentRanking
        activeNotifications.orEmpty()
            .filterNot { it.packageName == packageName }
            .forEach { onNotificationPosted(it, ranking) }
    }

    @Synchronized
    private fun applyApplicationFilter(packageName: String, enabled: Boolean) {
        if (enabled) return
        val bridgey = application as BridgeyApplication
        forwardedNotifications.removePackage(packageName).forEach { notificationId ->
            pendingCallPosts.remove(notificationId)?.let(mainHandler::removeCallbacks)
            removeActions(notificationId)
            if (forwardedNotificationIds.remove(notificationId) && bridgey.isPrimaryUser && bridgey.isBridgeyEnabled) {
                bridgey.pairing.sendNotificationRemoved(notificationId)
            }
        }
    }

    @Synchronized
    private fun storeActions(
        notificationId: String,
        actions: List<NotificationActionCandidate>,
    ): List<ForwardedNotificationAction> {
        removeActions(notificationId)
        val forwarded = actions.take(MAX_FORWARDED_ACTIONS).mapIndexedNotNull { index, action ->
            val title = action.title.trim().take(64)
            val pendingIntent = action.pendingIntent
            if (title.isEmpty()) return@mapIndexedNotNull null
            val remoteInputs = action.remoteInputs.filter(RemoteInput::getAllowFreeFormInput).toTypedArray()
            val token = notificationActionToken(notificationId, index)
            storedActions[token] = StoredNotificationAction(
                notificationId = notificationId,
                pendingIntent = pendingIntent,
                remoteInputs = remoteInputs,
                systemCallAction = action.systemCallAction,
            )
            ForwardedNotificationAction(token = token, title = title, allowsReply = remoteInputs.isNotEmpty())
        }
        actionTokensByNotificationId[notificationId] = forwarded.map(ForwardedNotificationAction::token)
        while (storedActions.size > MAX_STORED_ACTIONS) storedActions.remove(storedActions.keys.first())
        return forwarded
    }

    @Synchronized
    private fun removeActions(notificationId: String) {
        actionTokensByNotificationId.remove(notificationId).orEmpty().forEach(storedActions::remove)
    }

    @Synchronized
    private fun applicationIcon(packageName: String): String? {
        applicationIcons[packageName]?.let { return it }
        val drawable = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
        if (drawable == null) {
            android.util.Log.w("Bridgey", "ICON_DIAG getApplicationIcon failed or null package=$packageName")
            return null
        }
        val encoded = ICON_SIZES.firstNotNullOfOrNull { size ->
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val bytes = ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
            bitmap.recycle()
            android.util.Log.i("Bridgey", "ICON_DIAG package=$packageName size=$size rawBytes=${bytes.size} fitsLimit=${bytes.size <= MAX_ICON_BYTES}")
            bytes.takeIf { it.size <= MAX_ICON_BYTES }
                ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        }
        if (encoded == null) {
            android.util.Log.w("Bridgey", "ICON_DIAG all sizes exceeded limit package=$packageName")
            return null
        }
        applicationIcons[packageName] = encoded
        while (applicationIcons.size > MAX_CACHED_ICONS) applicationIcons.remove(applicationIcons.keys.first())
        return encoded
    }

    @Synchronized
    private fun performAction(notificationId: String, actionToken: String, replyText: String?, route: String? = null): Boolean {
        val action = storedActions[actionToken]?.takeIf { it.notificationId == notificationId } ?: return false
        if (replyText != null && action.remoteInputs.isEmpty()) return false
        return runCatching {
            val succeeded = if (action.systemCallAction != null) {
                val answered = callsController.performSystemCallAction(action.systemCallAction)
                if (answered && action.systemCallAction == SystemCallAction.ANSWER && route != null) {
                    applyAudioRouteWithSettleRetry(route)
                }
                answered
            } else if (replyText != null && action.remoteInputs.isNotEmpty()) {
                val results = Bundle().apply {
                    action.remoteInputs.forEach { putCharSequence(it.resultKey, replyText.take(MAX_REPLY_LENGTH)) }
                }
                val fillInIntent = Intent()
                RemoteInput.addResultsToIntent(action.remoteInputs, fillInIntent, results)
                action.pendingIntent?.send(this, 0, fillInIntent)
                true
            } else {
                action.pendingIntent?.send()
                true
            }
            if (succeeded) storedActions.remove(actionToken)
            android.util.Log.i(
                "Bridgey",
                "PLUGIN notification action performed system=${action.systemCallAction != null} success=$succeeded",
            )
            succeeded
        }.getOrElse {
            android.util.Log.w("Bridgey", "PLUGIN notification action failed", it)
            false
        }
    }

    /**
     * BRIDGEY CALL CONTINUITY: applies the route once immediately (best-effort, since Telecom
     * doesn't confirm exactly when the call audio session is actually up), then once more after
     * the same settle delay already used elsewhere for call-related timing
     * ([CALL_POST_SETTLE_DELAY_MS]) - the platform can reset audio routing right as the call
     * connects, and a single immediate attempt isn't always reliable across devices.
     */
    private fun applyAudioRouteWithSettleRetry(route: String) {
        callsController.applyAudioRoute(route)
        mainHandler.postDelayed({ callsController.applyAudioRoute(route) }, CALL_POST_SETTLE_DELAY_MS)
    }

    private fun refreshActiveCallNotifications() {
        val ranking = currentRanking
        activeNotifications.orEmpty()
            .filter { it.packageName != packageName && it.notification.category == Notification.CATEGORY_CALL }
            .forEach { onNotificationPosted(it, ranking) }
    }

    private fun removeActiveForwardedCalls() {
        activeNotifications.orEmpty()
            .filter { it.packageName != packageName && it.notification.category == Notification.CATEGORY_CALL }
            .forEach { removeForwardedCall(it.key) }
        // BRIDGEY CALL CONTINUITY: never leave the device stuck in speakerphone/Bluetooth-SCO
        // mode once the call actually ends.
        callsController.resetAudioRoute()
    }

    private fun removeForwardedCall(systemKey: String) {
        val notificationId = forwardedNotifications.removeSystemKey(systemKey) ?: return
        pendingCallPosts.remove(notificationId)?.let(mainHandler::removeCallbacks)
        removeActions(notificationId)
        val bridgey = application as BridgeyApplication
        if (
            forwardedNotificationIds.remove(notificationId) &&
            bridgey.isPrimaryUser && bridgey.isBridgeyEnabled
        ) {
            bridgey.pairing.sendNotificationRemoved(notificationId)
        }
    }

    companion object {
        @Volatile
        private var activeService: java.lang.ref.WeakReference<BridgeyNotificationListenerService>? = null

        fun dismiss(notificationId: String): Boolean {
            val service = activeService?.get() ?: return false
            return service.dismissForwardedNotification(notificationId)
        }

        fun perform(notificationId: String, actionToken: String, replyText: String?, route: String? = null): Boolean {
            val service = activeService?.get() ?: return false
            return service.performAction(notificationId, actionToken, replyText, route)
        }

        fun filterChanged(packageName: String, enabled: Boolean) {
            activeService?.get()?.applyApplicationFilter(packageName, enabled)
        }

        /** BRIDGEY NOTIFICATION++ POC: called from PairingCoordinator once the pairing session
         *  (re)reaches Connected - see [resyncActiveNotifications]'s doc comment. */
        fun resyncOnReconnect() {
            activeService?.get()?.mainHandler?.post { activeService?.get()?.resyncActiveNotifications() }
        }

        fun callPermissionsChanged() {
            activeService?.get()?.let { service ->
                service.mainHandler.post {
                    service.callsController.updateTelephonyCallback(service.callStateListener)
                }
            }
        }

        /**
         * Executes a Mac-requested answer/decline/hangup (the `calls.action` v2 message) via the
         * same TelecomManager-backed control path already used for the notification-driven
         * Answer/Decline/Hang Up actions. There is no per-call tracking here (that would need
         * InCallService, which cannot bind for a non-privileged app — see CONTROL_INCALL_EXPERIENCE
         * in docs/architecture.md), so this always acts on whatever call is currently ringing or
         * active rather than a specific call ID.
         */
        fun performCallAction(action: String, route: String? = null): Boolean {
            val service = activeService?.get() ?: return false
            val systemAction = when (action) {
                "answer" -> SystemCallAction.ANSWER
                "decline", "hangup" -> SystemCallAction.END
                else -> return false
            }
            val succeeded = service.callsController.performSystemCallAction(systemAction)
            if (succeeded && systemAction == SystemCallAction.ANSWER && route != null) {
                service.applyAudioRouteWithSettleRetry(route)
            }
            return succeeded
        }

        private const val MAX_FORWARDED_ACTIONS = 4
        private const val MAX_STORED_ACTIONS = 2_048
        private const val MAX_REPLY_LENGTH = 4_096
        private const val MAX_ICON_BYTES = 20 * 1024
        private const val MAX_CACHED_ICONS = 128
        private const val MAX_TRACKED_FORWARDED_NOTIFICATIONS = 512
        private const val CALL_POST_SETTLE_DELAY_MS = 450L
        private val ICON_SIZES = listOf(64, 48, 32)
    }
}

data class ForwardedNotificationAction(
    val token: String,
    val title: String,
    val allowsReply: Boolean,
)

private data class StoredNotificationAction(
    val notificationId: String,
    val pendingIntent: PendingIntent?,
    val remoteInputs: Array<RemoteInput>,
    val systemCallAction: SystemCallAction?,
)

internal class ForwardedNotificationRegistry(private val limit: Int = 512) {
    private data class Entry(val systemKey: String, val packageName: String)
    private val entriesByNotificationId = linkedMapOf<String, Entry>()

    @Synchronized
    fun record(notificationId: String, systemKey: String, packageName: String) {
        entriesByNotificationId.remove(notificationId)
        entriesByNotificationId[notificationId] = Entry(systemKey, packageName)
        while (entriesByNotificationId.size > limit) {
            entriesByNotificationId.remove(entriesByNotificationId.keys.first())
        }
    }

    @Synchronized
    fun systemKey(notificationId: String): String? = entriesByNotificationId[notificationId]?.systemKey

    @Synchronized
    fun removeSystemKey(systemKey: String): String? {
        val notificationId = entriesByNotificationId.entries.firstOrNull { it.value.systemKey == systemKey }?.key ?: return null
        entriesByNotificationId.remove(notificationId)
        return notificationId
    }

    @Synchronized
    fun removePackage(packageName: String): List<String> {
        val notificationIds = entriesByNotificationId.filterValues { it.packageName == packageName }.keys.toList()
        notificationIds.forEach(entriesByNotificationId::remove)
        return notificationIds
    }
}

/**
 * BRIDGEY NOTIFICATION++ POC: tracks notificationIds whose removal was just requested BY MAC (via
 * [BridgeyNotificationListenerService]'s `dismissForwardedNotification`) so the resulting
 * `onNotificationRemoved` system callback - which fires identically regardless of whether a removal
 * was user-initiated or programmatic, Android exposes no "origin" on it - can be told apart from an
 * independent local dismiss and NOT echoed back to Mac as a second, redundant `notifications.remove`.
 * This is the explicit origin-tracking the Mac-dismiss -> Android-cancel -> Android-reports-removal
 * loop needs: without it, the round trip is harmless (Mac's own removal is already idempotent) but
 * is exactly the kind of accidental feedback this POC is required to prevent by design, not by luck.
 */
internal class RemoteDismissTracker {
    private val pending = mutableSetOf<String>()

    @Synchronized
    fun markPending(notificationId: String) {
        pending += notificationId
    }

    /** Returns `true` (and consumes the marker) the first time this notificationId is checked after
     *  [markPending] - a second, unrelated removal of the same ID later is correctly treated as an
     *  independent event rather than silently swallowed forever. */
    @Synchronized
    fun consumeIfPending(notificationId: String): Boolean = pending.remove(notificationId)
}

/**
 * BRIDGEY NOTIFICATION++ PHASE 2: real-device evidence (dumpsys notification, live WhatsApp
 * messages) confirmed that WhatsApp's per-message notification carries a stable
 * `Notification.shortcutId` (e.g. "112575488491769@lid") that stays IDENTICAL across repeated
 * messages from the same conversation, while differing between conversations - exactly the
 * "logical conversation identity" signal the spec asked for, present on the individual message
 * notification (not just the group summary, which Bridgey already skips). Namespaced by
 * packageName so two apps can't collide on the same shortcutId value, and by a fixed tag so a
 * future non-conversation use of shortcutId can't collide with this one. Falls back to the
 * existing systemKey-based identity for apps that don't expose a shortcutId (confirmed via the
 * same real-device capture: eM Client and system notifications reported shortcutId=null).
 */
internal fun notificationIdentitySeed(packageName: String, shortcutId: String?, systemKey: String): String =
    if (!shortcutId.isNullOrBlank()) "$packageName conversation $shortcutId" else systemKey

internal fun notificationToken(systemKey: String): String = MessageDigest.getInstance("SHA-256")
    .digest(systemKey.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun notificationActionToken(notificationId: String, index: Int): String = notificationToken("$notificationId\u0000$index")

/**
 * BRIDGEY NOTIFICATION++ POC: validates an incoming `notifications.action` payload's three fields
 * before it's allowed to reach [BridgeyNotificationListenerService.perform] - extracted out of
 * PairingCoordinator.receiveNotificationAction so it's testable without needing a live Session, a
 * decrypted Message, or Android's NotificationListenerService plumbing at all. `replyText` is `null`
 * (open/plain-action request) vs. present-but-possibly-empty (a reply) - see `payload.has("replyText")`
 * at the call site - `null` and `""` are deliberately NOT the same case here.
 */
internal fun isValidNotificationActionPayload(notificationId: String, actionToken: String, replyText: String?): Boolean =
    notificationId.isNotBlank() &&
        notificationId.length <= 512 &&
        actionToken.matches(Regex("[0-9a-f]{64}")) &&
        (replyText?.length ?: 0) <= 4_096

internal fun shouldIgnoreOngoingNotification(flags: Int, category: String?): Boolean =
    flags and Notification.FLAG_ONGOING_EVENT != 0 && category != Notification.CATEGORY_CALL

/**
 * BRIDGEY NOTIFICATION++ SOUND POLISH: whether the mirrored macOS notification should be allowed
 * to play a sound, mirroring Android's own real notification-channel semantics (post-Oreo, the
 * channel - not `Notification.sound`/`.defaults` - is what actually determines audibility).
 * [channelImportance] is `null` when no ranking/channel could be resolved for this notification -
 * preserves the pre-existing always-audible behavior in that case, so older/untracked
 * notifications aren't unexpectedly silenced. Real-device evidence: WhatsApp posts some messages
 * on a channel literally named "silent_notifications_6" - exactly the case this must catch.
 */
internal fun notificationIsAudible(channelImportance: Int?, channelHasSound: Boolean?): Boolean = when {
    channelImportance == null -> true
    channelImportance < NotificationManager.IMPORTANCE_DEFAULT -> false
    else -> channelHasSound != false
}

object NotificationAccess {
    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, BridgeyNotificationListenerService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }
}
