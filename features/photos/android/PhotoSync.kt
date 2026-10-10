package dev.bridgey.android

internal const val MEDIA_TYPE_IMAGE = "image"
internal const val MEDIA_TYPE_VIDEO = "video"

internal data class MediaAssetRef(
    val mediaType: String,
    val mediaStoreId: Long,
    val dateModifiedSeconds: Long,
    val size: Long,
)

internal fun MediaAssetRef.ledgerKey(): String = "$mediaType:$mediaStoreId:$dateModifiedSeconds:$size"

/**
 * A MediaStore row not already recorded under its exact (id, dateModified, size) triple is
 * treated as new — if a row id gets reused for different content, the changed size/date makes
 * it resync rather than being silently skipped.
 */
internal fun unsyncedAssets(candidates: List<MediaAssetRef>, syncedLedgerKeys: Set<String>): List<MediaAssetRef> =
    candidates.filterNot { it.ledgerKey() in syncedLedgerKeys }

/**
 * MD-8: the Mac a Photo Sync batch goes to, resolved once for the whole batch. The preferred Mac
 * (the user's "Use … for these features" choice) when one is configured - and then only it: if it
 * is offline or cannot take photos, nothing is sent, even though routing itself may have fallen
 * back to another connected Mac. Only without a preferred Mac the routed peer is used (D1).
 * Null: nothing is sent (never another peer).
 */
internal fun photoSyncTarget(preferredDeviceId: String?, routedDeviceId: String?, eligible: List<String>): String? =
    (preferredDeviceId ?: routedDeviceId)?.takeIf { it in eligible }

/** A batch goes on only while its own target is still eligible; another connected peer never takes over. */
internal fun photoSyncContinues(target: String, eligibleNow: List<String>): Boolean = target in eligibleNow

/**
 * MD-8: a scan requested while a batch runs does not cancel it (that would leave its in-flight
 * asset unmarked and send it again to whatever peer the next batch targets); it runs once the
 * batch has finished.
 */
internal class PhotoSyncBatchGate {
    private var running = false
    private var rescan = false
    /** Bumped by every batch start and reset: a batch that ended after a reset cannot touch a newer one. */
    private var generation = 0

    /** True: start a scan now. False: a batch is running; it scans again when it finishes. */
    @Synchronized fun request(): Boolean {
        if (running) { rescan = true; return false }
        return true
    }

    /** Returns this batch's token for [finished]. */
    @Synchronized fun started(): Int {
        running = true
        return ++generation
    }

    /** The batch [token] ended; true when another scan was requested meanwhile. A stale token is ignored. */
    @Synchronized fun finished(token: Int): Boolean {
        if (token != generation) return false
        running = false
        val again = rescan
        rescan = false
        return again
    }

    @Synchronized fun reset() { running = false; rescan = false; generation++ }
}

/** MD-8: a batch whose assets keep failing (e.g. the Mac rejects them) gives way to the next scan. */
internal const val PHOTO_SYNC_MAX_CONSECUTIVE_FAILURES = 3
