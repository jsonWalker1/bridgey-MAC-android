package dev.bridgey.android

/**
 * GAMING MODE POC. Sliding-window anti-replay check (same construction IPsec/DTLS use): accepts a
 * sequence number exactly once, tolerates UDP's out-of-order delivery within a bounded window, and
 * rejects both replays and packets too old to fit in the window. Sequence is treated as an unsigned
 * 32-bit counter (wraps after ~4 billion packets - session-lifetime, never a concern in practice).
 */
internal class ReplayWindow(private val windowSize: Int = 64) {
    private var highestSequence = -1L
    private var bitmask = 0L

    @Synchronized
    fun acceptAndRecord(sequence: Int): Boolean {
        val seq = sequence.toLong() and 0xFFFFFFFFL
        if (highestSequence < 0) {
            highestSequence = seq
            bitmask = 1L
            return true
        }
        val diff = seq - highestSequence
        if (diff > 0) {
            bitmask = if (diff >= windowSize) 1L else (bitmask shl diff.toInt()) or 1L
            highestSequence = seq
            return true
        }
        val backBits = -diff
        if (backBits >= windowSize) return false
        val bit = 1L shl backBits.toInt()
        if (bitmask and bit != 0L) return false
        bitmask = bitmask or bit
        return true
    }
}

/**
 * GAMING MODE POC (not wired into production decode). Reassembles MTU-safe UDP fragments into
 * complete video frame payloads, with the two loss behaviors the spec calls for:
 *   - a newer frame arriving preempts (drops) any older, still-incomplete frame immediately -
 *     never wait for retransmission of a stale realtime frame;
 *   - an incomplete frame that nothing newer ever supersedes is dropped after `staleFrameTimeoutMs`
 *     (handles "the stream paused" / "the last-ever fragment was the one lost" cases the preemption
 *     path can't catch on its own).
 * Either path invokes `onKeyframeOrConfigLost` if the dropped frame was flagged KEYFRAME or CONFIG,
 * so the caller can request a fresh one over the existing TCP control channel (reusing
 * VideoFrameType.KEYFRAME_REQUEST - no new recovery mechanism needed). Ordinary delta frame loss is
 * silent by design: dropping one frame and continuing is exactly the desired behavior, not an error.
 */
internal class GamingFrameReassembler(
    private val staleFrameTimeoutMs: Long = 200L,
    private val onKeyframeOrConfigLost: () -> Unit,
) {
    private class InProgress(fragmentCount: Int, val flags: Int, val firstSeenAtMs: Long) {
        val fragments = arrayOfNulls<ByteArray>(fragmentCount)
        var received = 0
    }

    private val inProgress = LinkedHashMap<Long, InProgress>()
    private var highestFrameIdSeen = -1L

    @Synchronized
    fun onFragment(
        frameId: Long,
        fragmentIndex: Int,
        fragmentCount: Int,
        flags: Int,
        nowMs: Long,
        payload: ByteArray,
    ): ByteArray? {
        if (frameId < highestFrameIdSeen) return null
        if (frameId > highestFrameIdSeen) {
            dropOlderThan(frameId)
            highestFrameIdSeen = frameId
        }
        val state = inProgress.getOrPut(frameId) { InProgress(fragmentCount, flags, nowMs) }
        if (fragmentIndex >= state.fragments.size) return null
        if (state.fragments[fragmentIndex] == null) {
            state.fragments[fragmentIndex] = payload
            state.received++
        }
        if (state.received != state.fragments.size) return null
        inProgress.remove(frameId)
        val total = state.fragments.sumOf { it?.size ?: 0 }
        val out = ByteArray(total)
        var offset = 0
        for (fragment in state.fragments) {
            fragment ?: return null
            fragment.copyInto(out, offset)
            offset += fragment.size
        }
        return out
    }

    @Synchronized
    fun dropStale(nowMs: Long) {
        val staleIds = inProgress.entries
            .filter { nowMs - it.value.firstSeenAtMs > staleFrameTimeoutMs }
            .map { it.key }
        for (id in staleIds) reportIfImportant(inProgress.remove(id))
    }

    private fun dropOlderThan(frameId: Long) {
        val staleIds = inProgress.keys.filter { it < frameId }
        for (id in staleIds) reportIfImportant(inProgress.remove(id))
    }

    private fun reportIfImportant(dropped: InProgress?) {
        if (dropped != null && (dropped.flags and (GamingUdpFraming.Flags.KEYFRAME or GamingUdpFraming.Flags.CONFIG)) != 0) {
            onKeyframeOrConfigLost()
        }
    }
}
