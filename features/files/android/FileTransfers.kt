package dev.bridgey.android

// PER-PEER FILE TRANSFERS (MD-6)
//
// files.v1 is unchanged on the wire. What changes is ownership: every transfer belongs to the
// peer it was started with (outgoing) or the authenticated peer that offered it (incoming), and is
// identified by (deviceId, transferId). A transferId alone is chosen by the sender and only unique
// in practice, so it never correlates a message from one peer with another peer's transfer.
// Selection and routing are read once, when a transfer starts, and never again.

/** A transfer's identity: the peer it belongs to plus the sender-chosen transfer id. */
internal data class FileTransferKey(val deviceId: String, val transferId: String) {
    /** Stable id for a transfer row (the UI's cancel/retry handle). */
    val rowId: String get() = "$deviceId|$transferId"

    companion object {
        /** Splits at the last '|': transfer ids are UUIDs, a peer's deviceId is not validated. */
        fun fromRowId(rowId: String): FileTransferKey? {
            val separator = rowId.lastIndexOf('|')
            if (separator <= 0 || separator == rowId.length - 1) return null
            return FileTransferKey(rowId.substring(0, separator), rowId.substring(separator + 1))
        }
    }
}

/**
 * Transfers of one kind (incoming, outgoing jobs, pending acknowledgements), keyed per peer.
 * Thread-safe container: a lookup with another peer's key finds nothing, and ending one peer leaves
 * every other peer's entries.
 */
internal class FileTransferTable<V> {
    private val entries = LinkedHashMap<FileTransferKey, V>()

    @Synchronized operator fun get(key: FileTransferKey): V? = entries[key]
    @Synchronized fun contains(key: FileTransferKey): Boolean = key in entries
    @Synchronized fun isEmpty(): Boolean = entries.isEmpty()
    @Synchronized fun values(): List<V> = entries.values.toList()

    /** False (and unchanged) when this peer already has a transfer with that id. */
    @Synchronized
    fun insert(key: FileTransferKey, value: V): Boolean {
        if (key in entries) return false
        entries[key] = value
        return true
    }

    /** Replaces unconditionally (e.g. a send's job, set once the job object exists). */
    @Synchronized fun put(key: FileTransferKey, value: V) { entries[key] = value }

    @Synchronized fun remove(key: FileTransferKey): V? = entries.remove(key)

    /** Removes [key] only while it still maps to [value] (a stale completion cannot remove a newer entry). */
    @Synchronized fun remove(key: FileTransferKey, value: V): Boolean =
        if (entries[key] === value) { entries.remove(key); true } else false

    /** The peer's session ended: removes and returns only its transfers. */
    @Synchronized
    fun removeAll(deviceId: String): List<Pair<FileTransferKey, V>> {
        val ended = entries.filterKeys { it.deviceId == deviceId }.toList()
        ended.forEach { entries.remove(it.first) }
        return ended
    }

    @Synchronized
    fun removeAll(): List<Pair<FileTransferKey, V>> {
        val all = entries.toList()
        entries.clear()
        return all
    }
}

/**
 * Recently cancelled transfers, per peer, so late chunks/completions/acks of a cancelled transfer
 * are ignored instead of treated as a protocol error. Bounded; the oldest entries are dropped.
 */
internal class CancelledFileTransfers {
    private val order = ArrayDeque<FileTransferKey>()

    @Synchronized operator fun contains(key: FileTransferKey): Boolean = key in order

    @Synchronized
    fun add(key: FileTransferKey) {
        if (key in order) return
        order.addLast(key)
        while (order.size > CAPACITY) order.removeFirst()
    }

    companion object {
        const val CAPACITY = 64
    }
}

internal object FileTransferTarget {
    /**
     * The peer a send goes to, resolved once when the operation starts: the selected peer when it
     * is eligible, otherwise the only eligible peer, otherwise none (the user must choose). Never
     * the routed peer by default and never a fallback.
     */
    fun initial(selected: String?, eligible: List<String>): String? {
        if (selected != null && selected in eligible) return selected
        return eligible.singleOrNull()
    }
}

/** Transfer status text that always names the peer. */
internal object FileTransferText {
    fun waiting(peer: String) = "Waiting for $peer…"
    fun sending(file: String, peer: String) = "Sending $file → $peer…"
    fun sending(file: String, peer: String, progress: String) = "Sending $file → $peer: $progress"
    fun verifying(file: String, peer: String) = "Verifying $file on $peer…"
    fun saved(file: String, peer: String) = "$file saved on $peer"
    fun notAccepted(peer: String) = "$peer did not accept the file"
    fun notConfirmed(peer: String) = "$peer did not confirm the saved file"
    fun receiving(file: String, peer: String, progress: String) = "Receiving $file ← $peer: $progress"
    fun received(file: String, peer: String) = "$file from $peer saved to Download/Bridgey"
    fun turnedOff(peer: String) = "File transfer is turned off on $peer"
    fun cancelledBy(peer: String) = "Transfer cancelled by $peer"
    fun notConnected(peer: String) = "$peer is not connected — file was not sent"
    fun reconnectToRetry(peer: String) = "Reconnect $peer to retry"
    const val CHOOSE_DEVICE = "Choose a device in Bridgey to send files"
}
