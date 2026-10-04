package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoSyncTest {
    @Test fun unsyncedAssetsSkipsAnythingAlreadyRecordedUnderItsExactLedgerKey() {
        val alreadySynced = MediaAssetRef(MEDIA_TYPE_IMAGE, mediaStoreId = 1, dateModifiedSeconds = 100, size = 500)
        val new = MediaAssetRef(MEDIA_TYPE_VIDEO, mediaStoreId = 2, dateModifiedSeconds = 200, size = 600)

        val pending = unsyncedAssets(
            candidates = listOf(alreadySynced, new),
            syncedLedgerKeys = setOf(alreadySynced.ledgerKey()),
        )

        assertEquals(listOf(new), pending)
    }

    @Test fun unsyncedAssetsTreatsAReusedRowIdWithChangedContentAsNew() {
        val original = MediaAssetRef(MEDIA_TYPE_IMAGE, mediaStoreId = 1, dateModifiedSeconds = 100, size = 500)
        val reusedRowId = original.copy(dateModifiedSeconds = 999, size = 42)

        val pending = unsyncedAssets(
            candidates = listOf(reusedRowId),
            syncedLedgerKeys = setOf(original.ledgerKey()),
        )

        assertEquals(listOf(reusedRowId), pending)
    }

    @Test fun ledgerKeyIncludesMediaTypeIdDateAndSize() {
        val asset = MediaAssetRef(MEDIA_TYPE_IMAGE, mediaStoreId = 7, dateModifiedSeconds = 12, size = 34)

        assertEquals("image:7:12:34", asset.ledgerKey())
    }
}
