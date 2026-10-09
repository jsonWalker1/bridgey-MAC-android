package dev.bridgey.android

/**
 * Clipboard sends in flight, each bound to the device it targets (MD-5). A send is the pair
 * (deviceId, messageId): its clipboard.ack / clipboard.rejected completes it only when it comes
 * from that device, so one peer can never complete, time out or clear another peer's send.
 * Thread-safe; the coordinator sends, schedules timeouts and runs completions.
 */
internal class ClipboardSends {
    enum class Status { SENDING, DELIVERED, REJECTED, NOT_ACKNOWLEDGED, NOT_SENT, DISCONNECTED }

    private data class Key(val deviceId: String, val messageId: String)

    private val pending = mutableSetOf<Key>()
    private val statuses = mutableMapOf<String, Status>()

    @Synchronized fun statuses(): Map<String, Status> = statuses.toMap()
    @Synchronized fun isPending(deviceId: String, messageId: String): Boolean = Key(deviceId, messageId) in pending

    @Synchronized
    fun begin(deviceId: String, messageId: String) {
        pending += Key(deviceId, messageId)
        statuses[deviceId] = Status.SENDING
    }

    /** clipboard.ack from [from]. True only for a pending send to exactly that device. */
    fun acknowledge(messageId: String, from: String): Boolean = finish(Key(from, messageId), Status.DELIVERED)

    /** clipboard.rejected from [from] (its clipboard is off for us). */
    fun reject(messageId: String, from: String): Boolean = finish(Key(from, messageId), Status.REJECTED)

    fun timeOut(deviceId: String, messageId: String): Boolean = finish(Key(deviceId, messageId), Status.NOT_ACKNOWLEDGED)

    fun failed(deviceId: String, messageId: String): Boolean = finish(Key(deviceId, messageId), Status.NOT_SENT)

    /**
     * The device's session ended: its sends can never complete. Returns their message ids so the
     * caller can fail their completions. Other devices are untouched.
     */
    @Synchronized
    fun deviceEnded(deviceId: String): List<String> {
        val ended = pending.filter { it.deviceId == deviceId }
        pending.removeAll(ended.toSet())
        if (ended.isNotEmpty()) statuses[deviceId] = Status.DISCONNECTED else statuses.remove(deviceId)
        return ended.map { it.messageId }.sorted()
    }

    @Synchronized
    private fun finish(key: Key, status: Status): Boolean {
        if (!pending.remove(key)) return false
        statuses[key.deviceId] = status
        return true
    }
}

/**
 * What a receiver does with an incoming clipboard message, decided by its session's replay window.
 * A retransmission (the sender retried before our ack arrived) is not corruption: it is
 * acknowledged again and not applied a second time.
 */
internal enum class ClipboardReceiveAction {
    APPLY,
    ACKNOWLEDGE_AGAIN,
    ;

    companion object {
        fun forMessage(isNewMessageId: Boolean): ClipboardReceiveAction = if (isNewMessageId) APPLY else ACKNOWLEDGE_AGAIN

        /**
         * Whether clipboard from [sender] may be applied here: the same direction rule that decides
         * whether it is offered (no Mac -> Mac). A sender whose platform is unknown (recorded before
         * platform hints) keeps being accepted, so existing pairings are not cut off.
         */
        fun acceptsSender(sender: DeviceProfile, receiver: DeviceProfile): Boolean =
            sender.platform == DevicePlatform.UNKNOWN ||
                FeatureApplicability.isApplicable(FeatureApplicability.Feature.CLIPBOARD, sender, receiver)
    }
}

/**
 * The device a target-less clipboard entry point (tile, notification action, share) sends to: the
 * only eligible peer, or none - never a routed or "main" peer.
 */
internal object ClipboardTarget {
    fun forTargetlessSend(eligible: List<String>): String? = eligible.singleOrNull()
}
