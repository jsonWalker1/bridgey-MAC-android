package dev.bridgey.android

/**
 * Find Device state per device (MD-3), from this device's point of view:
 * - [remoteRinging]: peers we asked to ring that confirmed they are ringing;
 * - [localRequesters]: peers that asked *this* device to ring. This device rings while at least one
 *   requester remains, so one peer stopping or disconnecting never silences another's request.
 * Thread-safe; the coordinator plays/stops the sound and sends the messages.
 */
internal class FindDeviceState {
    private val remote = mutableSetOf<String>()
    private val requesters = mutableSetOf<String>()

    @Synchronized fun remoteRinging(): Set<String> = remote.toSet()
    @Synchronized fun localRequesters(): Set<String> = requesters.toSet()
    @Synchronized fun isRinging(deviceId: String): Boolean = deviceId in remote
    @Synchronized fun isRingingLocally(): Boolean = requesters.isNotEmpty()

    /** find.started / find.stopped from a peer we asked. */
    @Synchronized
    fun remoteReported(deviceId: String, ringing: Boolean) {
        if (ringing) remote += deviceId else remote -= deviceId
    }

    /** find.start from a peer. Returns true when the local sound must start. */
    @Synchronized
    fun localRingRequested(by: String): Boolean {
        val wasRinging = requesters.isNotEmpty()
        requesters += by
        return !wasRinging
    }

    /**
     * find.stop from a peer, or the local grant for that peer was revoked: the peer no longer asks
     * this device to ring. Its own ringing is reported separately (find.started / stopped). True
     * when the local sound must stop.
     */
    @Synchronized
    fun peerStopped(deviceId: String): Boolean = removeRequester(deviceId)

    /** The user silenced this device. Returns the peers that had asked, to be told it stopped. */
    @Synchronized
    fun stopLocalRinging(): Set<String> = requesters.toSet().also { requesters.clear() }

    /** The device's session ended. Returns true when the local sound must stop. */
    @Synchronized
    fun deviceEnded(deviceId: String): Boolean {
        remote -= deviceId
        return removeRequester(deviceId)
    }

    @Synchronized
    fun reset() {
        remote.clear()
        requesters.clear()
    }

    private fun removeRequester(deviceId: String): Boolean {
        val wasRinging = requesters.isNotEmpty()
        requesters -= deviceId
        return wasRinging && requesters.isEmpty()
    }
}
