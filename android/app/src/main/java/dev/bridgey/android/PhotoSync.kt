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
