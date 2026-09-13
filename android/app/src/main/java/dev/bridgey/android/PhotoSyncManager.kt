package dev.bridgey.android

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches the device's photo/video library for new MediaStore rows and offers each one to a
 * connected, trusted Mac via the existing files.v1 transfer path (see
 * PairingCoordinator.sendSyncAsset). Runs only while Bridgey and Photo Sync are both enabled and
 * the required media permissions are granted.
 */
internal class PhotoSyncManager(
    private val appContext: Context,
    private val pairing: PairingCoordinator,
    private val settings: BridgeySettings,
    private val scope: CoroutineScope,
) {
    private val preferences = appContext.getSharedPreferences("bridgey.photosync", Context.MODE_PRIVATE)
    private var observer: ContentObserver? = null
    private var registered = false
    private var scanJob: Job? = null

    fun start() {
        if (registered) return
        registered = true
        val handler = Handler(Looper.getMainLooper())
        val changeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = requestScan()
        }
        observer = changeObserver
        appContext.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, changeObserver)
        appContext.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, changeObserver)
        requestScan()
    }

    fun stop() {
        if (!registered) return
        registered = false
        observer?.let { runCatching { appContext.contentResolver.unregisterContentObserver(it) } }
        observer = null
        scanJob?.cancel()
    }

    fun requestScan() {
        if (!registered || !hasMediaPermission() || !settings.isEnabled(BridgeyFeature.PHOTO_SYNC, null)) return
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(1_500)
            runScan()
        }
    }

    private fun hasMediaPermission(): Boolean {
        val (imagePermission, videoPermission) = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES to Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE to Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return appContext.checkSelfPermission(imagePermission) == PackageManager.PERMISSION_GRANTED &&
            appContext.checkSelfPermission(videoPermission) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun runScan() {
        if (pairing.state.value !is PairingState.Connected) return
        val ledgerKeys = preferences.getStringSet(KEY_SYNCED, emptySet()).orEmpty()
        val pending = unsyncedAssets(queryCandidates(), ledgerKeys)
        for (asset in pending) {
            if (pairing.state.value !is PairingState.Connected) break
            if (!settings.isEnabled(BridgeyFeature.PHOTO_SYNC, null)) break
            sendOne(asset)
        }
    }

    private suspend fun sendOne(asset: MediaAssetRef) {
        val collection = if (asset.mediaType == MEDIA_TYPE_IMAGE) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = ContentUris.withAppendedId(collection, asset.mediaStoreId)
        val done = CompletableDeferred<Boolean>()
        pairing.sendSyncAsset(uri, asset.ledgerKey()) { success -> done.complete(success) }
        if (done.await()) markSynced(asset)
    }

    private fun markSynced(asset: MediaAssetRef) {
        val current = preferences.getStringSet(KEY_SYNCED, emptySet()).orEmpty()
        preferences.edit().putStringSet(KEY_SYNCED, current + asset.ledgerKey()).apply()
    }

    private fun queryCandidates(): List<MediaAssetRef> =
        queryMediaType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MEDIA_TYPE_IMAGE) +
            queryMediaType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MEDIA_TYPE_VIDEO)

    private fun queryMediaType(collection: Uri, mediaType: String): List<MediaAssetRef> {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE)
        val results = mutableListOf<MediaAssetRef>()
        runCatching {
            appContext.contentResolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    results += MediaAssetRef(mediaType, cursor.getLong(idIndex), cursor.getLong(dateIndex), cursor.getLong(sizeIndex))
                }
            }
        }
        return results
    }

    private companion object {
        const val KEY_SYNCED = "synced_keys"
    }
}
