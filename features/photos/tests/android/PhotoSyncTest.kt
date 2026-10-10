package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // MD-8: one target per batch - the preferred Mac (else the routed one) - and never another peer.

    private val m4 = "10000000-0000-4000-8000-00000000000a"
    private val work = "20000000-0000-4000-8000-00000000000b"

    @Test fun thePreferredMacIsTheTargetEvenWhenRoutingPointsElsewhere() {
        assertEquals(work, photoSyncTarget(preferredDeviceId = work, routedDeviceId = m4, eligible = listOf(m4, work)))
    }

    @Test fun anOfflinePreferredMacMeansNothingIsSentToTheConnectedOne() {
        // Routing has fallen back to the M4; Photo Sync does not follow it.
        assertNull(photoSyncTarget(preferredDeviceId = work, routedDeviceId = m4, eligible = listOf(m4)))
        assertNull("connected but cannot take photos", photoSyncTarget(work, m4, listOf(m4)))
    }

    @Test fun aReconnectedPreferredMacIsTheTargetOfTheNextScan() {
        assertNull(photoSyncTarget(work, m4, listOf(m4)))
        assertEquals(work, photoSyncTarget(work, m4, listOf(m4, work)))
    }

    @Test fun withoutAPreferredMacTheRoutedOneIsUsed() {
        assertEquals(m4, photoSyncTarget(preferredDeviceId = null, routedDeviceId = m4, eligible = listOf(m4, work)))
        assertNull(photoSyncTarget(null, null, listOf(m4, work)))
        assertNull("the routed Mac cannot take photos: no other Mac takes over", photoSyncTarget(null, m4, listOf(work)))
    }

    @Test fun aSingleMacBehavesAsBefore() {
        assertEquals(m4, photoSyncTarget(m4, m4, listOf(m4)))
        assertEquals(m4, photoSyncTarget(null, m4, listOf(m4)))
    }

    @Test fun aRoutingChangeDuringTheBatchDoesNotMoveIt() {
        val target = photoSyncTarget(work, work, listOf(m4, work))!!
        // Routing now points at the M4; the batch keeps checking its own target only.
        assertTrue(photoSyncContinues(target, listOf(m4, work)))
        assertEquals(work, target)
    }

    @Test fun theBatchStopsWhenItsTargetGoesAwayEvenIfAnotherMacIsConnected() {
        assertFalse(photoSyncContinues(work, listOf(m4)))
        assertFalse(photoSyncContinues(work, emptyList()))
    }

    @Test fun anUnrelatedPeerDisconnectingDoesNotStopTheBatch() {
        assertTrue(photoSyncContinues(work, listOf(work)))
    }

    @Test fun aScanRequestedDuringABatchRunsAfterItInsteadOfCancellingIt() {
        val gate = PhotoSyncBatchGate()
        assertTrue(gate.request())
        val first = gate.started()
        assertFalse("a running batch is not cancelled", gate.request())
        assertFalse(gate.request())
        assertTrue("one rescan after the batch", gate.finished(first))
        assertTrue(gate.request())
        val second = gate.started()
        assertFalse("nothing requested meanwhile", gate.finished(second))
    }

    @Test fun aBatchEndingAfterAResetCannotTouchANewerOne() {
        val gate = PhotoSyncBatchGate()
        val stale = gate.started()
        gate.reset() // Photo Sync stopped
        assertTrue(gate.request())
        val current = gate.started() // re-enabled: a new batch runs
        gate.request() // a rescan requested during the new batch
        assertFalse("the old batch's late end is ignored", gate.finished(stale))
        assertFalse("the new batch is still running", gate.request())
        assertTrue(gate.finished(current))
    }
}
