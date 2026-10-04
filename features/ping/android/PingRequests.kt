package dev.bridgey.android

/**
 * Ping requests in flight, each bound to the device it targets (MD-3). A request is identified by
 * (deviceId, requestId): an acknowledgement completes it only when it comes from that device, so one
 * device can never complete, time out or clear another device's request. Thread-safe; the
 * coordinator sends, schedules timeouts and reports the outcome.
 */
internal class PingRequests {
    enum class Status { PINGING, DELIVERED, NOT_ACKNOWLEDGED, NOT_SENT }

    private data class Key(val deviceId: String, val requestId: String)

    private val pending = mutableSetOf<Key>()
    private val statuses = mutableMapOf<String, Status>()

    /** The latest outcome per device. */
    @Synchronized fun statuses(): Map<String, Status> = statuses.toMap()

    @Synchronized fun isPending(deviceId: String, requestId: String): Boolean = Key(deviceId, requestId) in pending

    @Synchronized
    fun begin(deviceId: String, requestId: String) {
        pending += Key(deviceId, requestId)
        statuses[deviceId] = Status.PINGING
    }

    /** True only for a pending request of exactly this device. */
    @Synchronized
    fun acknowledge(requestId: String, from: String): Boolean {
        if (!pending.remove(Key(from, requestId))) return false
        statuses[from] = Status.DELIVERED
        return true
    }

    /** The request could not be written to that device's session. */
    @Synchronized
    fun failed(deviceId: String, requestId: String) {
        if (pending.remove(Key(deviceId, requestId))) statuses[deviceId] = Status.NOT_SENT
    }

    /** True if the request was still pending (and is now marked unacknowledged). */
    @Synchronized
    fun timeOut(deviceId: String, requestId: String): Boolean {
        if (!pending.remove(Key(deviceId, requestId))) return false
        statuses[deviceId] = Status.NOT_ACKNOWLEDGED
        return true
    }

    /**
     * The device's session ended: its requests can never complete. Other devices are untouched.
     * Returns true if a request to that device was still pending.
     */
    @Synchronized
    fun deviceEnded(deviceId: String): Boolean {
        val hadPending = pending.removeAll { it.deviceId == deviceId }
        statuses.remove(deviceId)
        return hadPending
    }

    @Synchronized
    fun reset() {
        pending.clear()
        statuses.clear()
    }
}
