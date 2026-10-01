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
    // elapsedRealtime of the last notifications.post actually sent per logical id - lets a macOS
    // Clear All skip content the Mac cannot have seen yet (see dismissFromMacClearAll).
    private val lastForwardedAtMillis = java.util.concurrent.ConcurrentHashMap<String, Long>()
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
        callsController.updateTelephonyCallback(callStateListener)
        android.util.Log.i("Bridgey", "PLUGIN notification listener connected")
        // BRIDGEY NOTIFICATION++ RECONCILIATION: the registry and forwarded set are in-memory, so
        // after any (re)bind they are rebuilt from the real active set with the same identity the
        // post path uses - never raw sbn.key (audit L2/L3). If pairing reached Connected before this
        // bind, its resync request found no listener; reconciling here unconditionally covers that
        // ordering too (every send below is a safe no-op while disconnected).
        reconcile(repost = true, reason = "listener_bound")
    }

    override fun onListenerDisconnected() {
        mainHandler.removeCallbacks(cancelAllReconciliation)
        callsController.unregisterTelephonyCallback()
        if (activeService?.get() === this) activeService = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        android.util.Log.d("Bridgey", "PLUGIN notification observed package=${sbn.packageName}")
        if (sbn.packageName != packageName) logNotificationDiagnostics("POST", sbn)
        val bridgey = application as BridgeyApplication
        if (!bridgey.isPrimaryUser || !bridgey.isBridgeyEnabled || sbn.packageName == packageName) return
        forward(sbn, rankingMap, resync = false)
    }

    /** Builds the pure eligibility input for [eligibleNotificationId] from a live notification. */
    private fun eligibilityInput(sbn: StatusBarNotification, rankingMap: RankingMap?): NotificationEligibilityInput {
        val notification = sbn.notification
        val ranking = Ranking()
        val hasRanking = rankingMap?.getRanking(sbn.key, ranking) == true
        return NotificationEligibilityInput(
            systemKey = sbn.key,
            packageName = sbn.packageName,
            flags = notification.flags,
            category = notification.category,
            visibility = notification.visibility,
            rankingImportance = if (hasRanking) ranking.importance else null,
            title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty(),
            text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty(),
            shortcutId = notification.shortcutId,
        )
    }

    private fun eligibleNotificationId(input: NotificationEligibilityInput): String? {
        val bridgey = application as BridgeyApplication
        return eligibleNotificationId(
            input,
            ownPackageName = packageName,
            isApplicationEnabled = bridgey.settings::isNotificationApplicationEnabled,
            isIdleCall = { callsController.currentTelephonyCallType() == "idle" },
        )
    }

    /**
     * The single post path, shared by real posts, reconnect resync and reconciliation. Returns the
     * logical notificationId when [sbn] is eligible (and was recorded/forwarded), or `null` when it
     * is not - in which case this system key no longer backs any Mac notification, so it is
     * forgotten exactly like a removal (covers a previously forwarded notification that became
     * ineligible, e.g. a call once telephony is idle).
     */
    private fun forward(sbn: StatusBarNotification, rankingMap: RankingMap?, resync: Boolean): String? {
        val bridgey = application as BridgeyApplication
        val input = eligibilityInput(sbn, rankingMap)
        val applicationName = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)
        // The per-app filter list must keep offering apps the user disabled, so apps are observed
        // before the app filter applies - exactly where the pre-extraction code observed them.
        if (eligibleNotificationId(input, packageName, isApplicationEnabled = { true }) != null) {
            bridgey.settings.observeNotificationApplication(sbn.packageName, applicationName)
        }
        val notificationId = eligibleNotificationId(input)
        if (notificationId == null) {
            forgetSystemKey(sbn.key)
            return null
        }

        val notification = sbn.notification
        val isCall = notification.category == Notification.CATEGORY_CALL
        val ranking = Ranking()
        val hasRanking = rankingMap?.getRanking(sbn.key, ranking) == true
        val hasSound = notificationIsAudible(
            channelImportance = ranking.channel?.importance?.takeIf { hasRanking },
            channelHasSound = ranking.channel?.sound != null,
        )
        val title = input.title
        val text = input.text
        val applicationIcon = applicationIcon(sbn.packageName)
        val telephonyCallType = if (isCall) callsController.currentTelephonyCallType() else null

        forwardedNotifications.record(notificationId, sbn.key, sbn.packageName)?.let(::sendRemovedIfForwarded)
        pendingCallPosts.remove(notificationId)?.let(mainHandler::removeCallbacks)
        val callType = if (isCall) resolvedNotificationCallType(notification, telephonyCallType) else null
        val audioRoutes = if (isCall && callType == "incoming") callsController.availableAudioRoutes() else null
        val bluetoothRouteName = if (audioRoutes?.contains("BLUETOOTH") == true) callsController.bluetoothRouteName() else null
        val actions = storeActions(
            notificationId,
            notificationActionCandidates(notification, callType, callsController.canControlSystemCalls()),
        )
        val send = Runnable {
            pendingCallPosts.remove(notificationId)
            if (!forwardedNotifications.contains(notificationId, sbn.key)) return@Runnable
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
                resync = resync,
                conversationId = notification.shortcutId, // TAP ROUTING POC
            )
            markForwarded(notificationId)
            val now = android.os.SystemClock.elapsedRealtime()
            lastForwardedAtMillis[notificationId] = now
            if (lastForwardedAtMillis.size > MAX_TRACKED_FORWARDED_NOTIFICATIONS) {
                lastForwardedAtMillis.entries.removeIf { now - it.value >= MAC_CLEAR_ALL_FRESHNESS_MILLIS }
            }
        }
        if (shouldDelayCallPost(callType)) {
            pendingCallPosts[notificationId] = send
            mainHandler.postDelayed(send, CALL_POST_SETTLE_DELAY_MS)
        } else {
            send.run()
        }
        return notificationId
    }

    private fun markForwarded(notificationId: String) {
        forwardedNotificationIds.remove(notificationId)
        forwardedNotificationIds += notificationId
        while (forwardedNotificationIds.size > MAX_TRACKED_FORWARDED_NOTIFICATIONS) {
            forwardedNotificationIds.remove(forwardedNotificationIds.first())
        }
    }

    // minSdk 26: the system always calls this 3-argument overload (its default implementation is what
    // would fan out to the 1-argument one), so all removal handling lives here.
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (sbn.packageName != packageName) logNotificationDiagnostics("REMOVE", sbn)
        forgetSystemKey(sbn.key)
        // BRIDGEY NOTIFICATION++ RECONCILIATION: Clear All still sends every individual remove above
        // for immediate UX; one debounced authoritative sync then repairs anything those events
        // could not (audit section 6). Re-armed per callback, so a 30-item Clear All = one sync.
        if (reason == REASON_CANCEL_ALL) {
            mainHandler.removeCallbacks(cancelAllReconciliation)
            mainHandler.postDelayed(cancelAllReconciliation, CANCEL_ALL_SYNC_DEBOUNCE_MS)
        }
    }

    private val cancelAllReconciliation = Runnable { reconcile(repost = false, reason = "cancel_all") }

    /**
     * Drops [systemKey] from the registry. Only when it was the LAST key backing its logical
     * notification (e.g. the last live WhatsApp notification of a conversation) does the Mac copy go.
     */
    private fun forgetSystemKey(systemKey: String) {
        val notificationId = forwardedNotifications.removeSystemKey(systemKey) ?: return
        sendRemovedIfForwarded(notificationId)
    }

    /** [notificationId] no longer has any live Android system key behind it. */
    private fun sendRemovedIfForwarded(notificationId: String) {
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

    /**
     * BRIDGEY NOTIFICATION++ RECONCILIATION: rebuilds all in-memory bookkeeping from the real
     * active notification set (the source of truth), optionally re-posts every eligible notification
     * as a silent `resync`, then sends the authoritative `notifications.sync` so the Mac removes
     * whatever Android no longer has. Every send is a safe no-op while disconnected or while
     * notification forwarding is unavailable. Runs on the main thread, like every listener callback.
     */
    private fun reconcile(repost: Boolean, reason: String) {
        val bridgey = application as BridgeyApplication
        if (!bridgey.isPrimaryUser || !bridgey.isBridgeyEnabled) return
        // getActiveNotifications() returns null (or throws) while the listener is not bound. That
        // means "unknown", never "empty": an empty snapshot would make the Mac remove everything.
        // Not bound (yet) is harmless to skip - onListenerConnected reconciles once it binds.
        val active = runCatching { activeNotifications?.toList() }.getOrNull() ?: run {
            android.util.Log.w("Bridgey", "PLUGIN notification reconcile skipped reason=$reason: listener not bound")
            return
        }
        val ranking = currentRanking
        val previouslyForwarded = forwardedNotificationIds.toSet()
        forwardedNotifications.clear()
        forwardedNotificationIds.clear()
        val snapshot = linkedSetOf<String>()
        active.filterNot { it.packageName == packageName }.forEach { sbn ->
            val notificationId = if (repost) {
                forward(sbn, ranking, resync = true)
            } else {
                eligibleNotificationId(eligibilityInput(sbn, ranking))?.also { id ->
                    forwardedNotifications.record(id, sbn.key, sbn.packageName)
                    markForwarded(id)
                }
            }
            if (notificationId != null) snapshot += notificationId
        }
        (previouslyForwarded - snapshot).forEach { staleId ->
            pendingCallPosts.remove(staleId)?.let(mainHandler::removeCallbacks)
            removeActions(staleId)
        }
        bridgey.pairing.sendNotificationSync(snapshot.toList())
        android.util.Log.i(
            "Bridgey",
            "PLUGIN notification reconcile reason=$reason repost=$repost active=${active.size} synced=${snapshot.size}",
        )
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

    /**
     * BRIDGEY NOTIFICATION++ macOS CLEAR ALL: one id of a `notifications.dismissMany`. Mac only sends
     * it after at least 60 s without any post, so an id forwarded within that window carries content
     * the Mac cannot have seen when it evaluated the clear (e.g. a new message in the same
     * conversation) and is kept. Otherwise it is exactly a single Mac dismiss.
     */
    private fun dismissFromMacClearAll(notificationId: String): MacClearAllDismissOutcome {
        if (isTooRecentForMacClearAll(lastForwardedAtMillis[notificationId], android.os.SystemClock.elapsedRealtime())) {
            return MacClearAllDismissOutcome.TOO_RECENT
        }
        return if (dismissForwardedNotification(notificationId)) MacClearAllDismissOutcome.DISMISSED else MacClearAllDismissOutcome.UNKNOWN
    }

    /** A Mac dismiss cancels EVERY live Android notification behind the logical notification, so
     *  a multi-notification conversation cannot survive on the phone after its Mac copy is gone. */
    private fun dismissForwardedNotification(notificationId: String): Boolean {
        val systemKeys = forwardedNotifications.systemKeys(notificationId)
        if (systemKeys.isEmpty()) return false
        remoteDismissTracker.markPending(notificationId)
        mainHandler.post { systemKeys.forEach { cancelNotification(it) } }
        return true
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
            .forEach { forgetSystemKey(it.key) }
        // BRIDGEY CALL CONTINUITY: never leave the device stuck in speakerphone/Bluetooth-SCO
        // mode once the call actually ends.
        callsController.resetAudioRoute()
    }

    companion object {
        @Volatile
        private var activeService: java.lang.ref.WeakReference<BridgeyNotificationListenerService>? = null

        fun dismiss(notificationId: String): Boolean {
            val service = activeService?.get() ?: return false
            return service.dismissForwardedNotification(notificationId)
        }

        /** `notifications.dismissMany` (macOS Clear All). `UNKNOWN` when the listener is not bound. */
        fun dismissFromMacClearAll(notificationId: String): MacClearAllDismissOutcome =
            activeService?.get()?.dismissFromMacClearAll(notificationId) ?: MacClearAllDismissOutcome.UNKNOWN

        fun perform(notificationId: String, actionToken: String, replyText: String?, route: String? = null): Boolean {
            val service = activeService?.get() ?: return false
            return service.performAction(notificationId, actionToken, replyText, route)
        }

        fun filterChanged(packageName: String, enabled: Boolean) {
            activeService?.get()?.applyApplicationFilter(packageName, enabled)
        }

        /**
         * BRIDGEY NOTIFICATION++ RECONCILIATION: called from PairingCoordinator whenever notification
         * forwarding becomes available on a connected session (connect, reconnect, feature re-enabled
         * on either peer). Re-posts every eligible notification as a silent `resync` (the Mac's
         * request identifier is stable per notificationId, so this replaces rather than duplicates)
         * followed by the authoritative `notifications.sync`. If the listener is not bound yet,
         * nothing is lost: [onListenerConnected] always reconciles once it binds.
         */
        fun requestReconciliation() {
            activeService?.get()?.let { service ->
                service.mainHandler.post { service.reconcile(repost = true, reason = "session") }
            }
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
        private const val CANCEL_ALL_SYNC_DEBOUNCE_MS = 500L
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

/**
 * BRIDGEY NOTIFICATION++ RECONCILIATION: logical notificationId -> every live Android system key
 * behind it. With conversation identity (see [notificationIdentitySeed]) several Android
 * notifications can share one logical id; the logical notification - and its Mac copy - stays live
 * while at least one key remains. Every operation is idempotent.
 */
internal class ForwardedNotificationRegistry(private val limit: Int = 512) {
    private class Entry(val packageName: String) {
        val systemKeys = linkedSetOf<String>()
    }
    private val entriesByNotificationId = linkedMapOf<String, Entry>()
    private val notificationIdBySystemKey = mutableMapOf<String, String>()

    /**
     * Records [systemKey] as backing [notificationId]. Returns another notificationId only when this
     * key previously backed it and was its LAST key (the key moved to a new logical identity), so
     * the caller can remove that orphaned logical notification; otherwise `null`.
     */
    @Synchronized
    fun record(notificationId: String, systemKey: String, packageName: String): String? {
        val previousId = notificationIdBySystemKey[systemKey]
        val orphaned = if (previousId != null && previousId != notificationId) detach(previousId, systemKey) else null
        val entry = entriesByNotificationId.remove(notificationId) ?: Entry(packageName)
        entry.systemKeys += systemKey
        entriesByNotificationId[notificationId] = entry
        notificationIdBySystemKey[systemKey] = notificationId
        while (entriesByNotificationId.size > limit) {
            val evictedId = entriesByNotificationId.keys.first()
            entriesByNotificationId.remove(evictedId)?.systemKeys?.forEach(notificationIdBySystemKey::remove)
        }
        return orphaned
    }

    @Synchronized
    fun contains(notificationId: String, systemKey: String): Boolean =
        entriesByNotificationId[notificationId]?.systemKeys?.contains(systemKey) == true

    @Synchronized
    fun systemKeys(notificationId: String): Set<String> = entriesByNotificationId[notificationId]?.systemKeys?.toSet().orEmpty()

    /** Returns the notificationId only when [systemKey] was its last live key; `null` otherwise
     *  (unknown key, repeated removal, or other keys still backing the logical notification). */
    @Synchronized
    fun removeSystemKey(systemKey: String): String? {
        val notificationId = notificationIdBySystemKey[systemKey] ?: return null
        return detach(notificationId, systemKey)
    }

    @Synchronized
    fun removePackage(packageName: String): List<String> {
        val notificationIds = entriesByNotificationId.filterValues { it.packageName == packageName }.keys.toList()
        notificationIds.forEach { id -> entriesByNotificationId.remove(id)?.systemKeys?.forEach(notificationIdBySystemKey::remove) }
        return notificationIds
    }

    @Synchronized
    fun clear() {
        entriesByNotificationId.clear()
        notificationIdBySystemKey.clear()
    }

    private fun detach(notificationId: String, systemKey: String): String? {
        notificationIdBySystemKey.remove(systemKey)
        val entry = entriesByNotificationId[notificationId] ?: return null
        if (!entry.systemKeys.remove(systemKey)) return null
        if (entry.systemKeys.isNotEmpty()) return null
        entriesByNotificationId.remove(notificationId)
        return notificationId
    }
}

/**
 * BRIDGEY NOTIFICATION++ RECONCILIATION: the Android-side facts [eligibleNotificationId] needs,
 * extracted from a StatusBarNotification + its Ranking so the rules are testable on the JVM.
 * [rankingImportance] is `null` when no ranking was available for the notification.
 */
internal data class NotificationEligibilityInput(
    val systemKey: String,
    val packageName: String,
    val flags: Int,
    val category: String?,
    val visibility: Int,
    val rankingImportance: Int?,
    val title: String,
    val text: String,
    val shortcutId: String?,
)

/**
 * The ONE definition of "this Android notification is mirrored to the Mac, as this logical id".
 * Used by the live post path, listener re-bind, the reconciliation snapshot and resync alike, so
 * those can never disagree about identity (the audit's L3 bug was exactly such a disagreement).
 * Rules are unchanged from the pre-reconciliation onNotificationPosted: Bridgey's own package,
 * group summaries, ongoing non-call notifications, secret visibility, IMPORTANCE_MIN or lower,
 * empty title+text, per-app filter, and call notifications while telephony is idle are excluded.
 */
internal fun eligibleNotificationId(
    input: NotificationEligibilityInput,
    ownPackageName: String,
    isApplicationEnabled: (String) -> Boolean,
    isIdleCall: () -> Boolean = { false },
): String? {
    if (input.packageName == ownPackageName) return null
    if (input.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
    if (shouldIgnoreOngoingNotification(input.flags, input.category)) return null
    if (input.visibility == Notification.VISIBILITY_SECRET) return null
    if (input.rankingImportance != null && input.rankingImportance <= NotificationManager.IMPORTANCE_MIN) return null
    if (input.title.isEmpty() && input.text.isEmpty()) return null
    if (!isApplicationEnabled(input.packageName)) return null
    if (input.category == Notification.CATEGORY_CALL && isIdleCall()) return null
    return notificationToken(notificationIdentitySeed(input.packageName, input.shortcutId, input.systemKey))
}

/**
 * Splits a reconciliation snapshot into `notifications.sync` parts of at most [maxPerPart] ids.
 * Always at least one (possibly empty) part: an empty snapshot is itself the message "Android has
 * no eligible notifications" (e.g. after Clear All).
 */
internal fun notificationSyncParts(notificationIds: List<String>, maxPerPart: Int = MAX_NOTIFICATION_SYNC_IDS_PER_PART): List<List<String>> =
    if (notificationIds.isEmpty()) listOf(emptyList()) else notificationIds.chunked(maxPerPart)

internal const val MAX_NOTIFICATION_SYNC_IDS_PER_PART = 256

enum class MacClearAllDismissOutcome { DISMISSED, UNKNOWN, TOO_RECENT }

/** Mac evaluates a Clear All only after this long without any post; see dismissFromMacClearAll. */
internal const val MAC_CLEAR_ALL_FRESHNESS_MILLIS = 60_000L

internal fun isTooRecentForMacClearAll(lastForwardedAtMillis: Long?, nowMillis: Long): Boolean =
    lastForwardedAtMillis != null && nowMillis - lastForwardedAtMillis < MAC_CLEAR_ALL_FRESHNESS_MILLIS

/**
 * Validates a decrypted `notifications.dismissMany` payload (docs/protocol.md): version 1, reason
 * `mac_clear_all`, 1-256 ids of 64 lowercase hex characters. `null` = invalid message.
 */
internal fun parseNotificationDismissManyPayload(json: String): List<String>? = runCatching {
    val payload = org.json.JSONObject(json)
    if (payload.optInt("version") != 1 || payload.optString("reason") != "mac_clear_all") return null
    val array = payload.getJSONArray("notificationIds")
    val ids = (0 until array.length()).map { array.getString(it) }
    ids.takeIf { it.size in 1..MAX_NOTIFICATION_DISMISS_MANY_IDS && it.all { id -> id.matches(Regex("[0-9a-f]{64}")) } }
}.getOrNull()

internal const val MAX_NOTIFICATION_DISMISS_MANY_IDS = 256

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
