package dev.bridgey.android

// NOTIFICATIONS: ONE TARGET MAC (MD-9)
//
// Android mirrors its notifications to exactly one Mac at a time: the preferred Mac, or - only when
// no Mac is preferred - the routed one. A preferred Mac that is offline or not eligible means no
// target at all, never another Mac. The wire format is unchanged: the destination is the session a
// message is sent on, the sender is the authenticated session it arrived on.

/** The Mac notifications go to: the preferred Mac (no fallback), else the routed one; only an eligible one. */
internal fun notificationTarget(preferredDeviceId: String?, routedDeviceId: String?, eligible: List<String>): String? =
    (preferredDeviceId ?: routedDeviceId)?.takeIf { it in eligible }

/** Dismiss / dismissMany / action are accepted only from the current target Mac. */
internal fun acceptsNotificationCommand(sender: String?, target: String?): Boolean = sender != null && sender == target

/**
 * Holds the committed notification target and decides, per evaluation, which Mac must be reconciled
 * (it just became the target, or the target reconnected) and which eligible non-target Macs must
 * have this phone's copies cleared (an empty `notifications.sync`). Keyed by session object, so a
 * reconnect counts as new. Sends and command checks read [target], so they never act on a target
 * that was not evaluated here (and therefore cleaned up / reconciled). Thread-safe.
 */
internal class NotificationTargetTracker {
    data class Step(val reconcile: String?, val clear: List<String>)

    /** The committed target: the one Mac notifications go to, or null. */
    @Volatile var target: String? = null
        private set
    private var reconciledSession: Any? = null
    private val clearedSessions = mutableMapOf<String, Any>()

    /** [eligible]: deviceId -> its connected session, for every Mac that may receive notifications. */
    @Synchronized
    fun update(target: String?, eligible: Map<String, Any>): Step {
        clearedSessions.entries.removeAll { (id, session) -> id == target || eligible[id] !== session }
        val clear = eligible.filter { (id, session) -> id != target && clearedSessions[id] !== session }.keys.toList()
        clear.forEach { clearedSessions[it] = eligible.getValue(it) }
        val targetSession = target?.let(eligible::get)
        val reconcile = target?.takeIf { targetSession != null && targetSession !== reconciledSession }
        reconciledSession = targetSession
        this.target = target
        return Step(reconcile, clear)
    }
}
