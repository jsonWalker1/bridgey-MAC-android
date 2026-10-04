package dev.bridgey.android

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

// Android -> Mac half of Media Continuity. Publishes whatever Android considers the "primary"
// active MediaSession (Spotify/YouTube/etc.) to the Mac, and executes play/pause/next/previous/seek
// commands the Mac sends back. Mirrors the existing Mac -> Android direction (MediaController.swift,
// QuickActions.kt's "media" quick.request/media.state handling) but is a separate, dedicated message
// family (media.remote.*) so the two directions never collide on the same wire vocabulary.

data class MediaRemoteState(
    val hasSession: Boolean = false,
    val packageName: String? = null,
    val appLabel: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val playing: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val playbackSpeed: Float = 1f,
    val positionTimestamp: Long = 0,
    val artworkHash: String? = null,
    val artwork: ByteArray? = null,
    val capabilities: Set<String> = emptySet(),
    val volume: Int? = null,
)

internal data class MediaSessionSnapshot(val id: String, val isPlaying: Boolean)

/**
 * Deterministic MVP primary-session choice: prefer whatever is playing (breaking ties toward the
 * session that most recently started playing); otherwise keep the previous primary if it still
 * exists (so pausing the only session doesn't blank the mini-player); otherwise fall back to
 * whatever the system happens to return first. No multi-session UI - one primary, always.
 */
internal fun selectPrimarySession(
    sessions: List<MediaSessionSnapshot>,
    lastPlayingId: String?,
    previousPrimaryId: String?,
): String? {
    val playing = sessions.filter { it.isPlaying }
    if (playing.isNotEmpty()) {
        return playing.firstOrNull { it.id == lastPlayingId }?.id ?: playing.first().id
    }
    if (previousPrimaryId != null && sessions.any { it.id == previousPrimaryId }) return previousPrimaryId
    return sessions.firstOrNull()?.id
}

/** A Mac-issued action must target the generation Android is currently on, or it's stale. */
internal fun isActionGenerationValid(current: Long, requested: Long): Boolean = current == requested

/**
 * next/previous aren't instantaneous the way play/pause is: the player has to actually load the
 * new item (a real network fetch for a streamed video/track), which measurably takes time. Firing
 * another skip before that finishes doesn't queue up - it desyncs the player's own idea of "current
 * position in the up-next queue" from what's actually loaded, and on YouTube specifically this
 * surfaces to the user as a "this content can't be played" error rather than a queued second skip.
 * Observed on real hardware: repeated next/previous ~300-700ms apart (fast manual clicking, or a
 * held/auto-repeating hardware key) reliably reproduced this. A flat minimum interval between
 * *accepted* track changes - independent of whether they're next or previous - gives the player
 * time to settle before the next one is allowed through.
 */
internal const val MINIMUM_TRACK_CHANGE_INTERVAL_MS = 900L
internal fun trackChangeAllowed(nowMs: Long, lastAcceptedAtMs: Long, minimumIntervalMs: Long = MINIMUM_TRACK_CHANGE_INTERVAL_MS): Boolean =
    lastAcceptedAtMs == 0L || nowMs - lastAcceptedAtMs >= minimumIntervalMs

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * PackageManager.getApplicationLabel() falls back to returning the package name itself when it
 * can't resolve a real display label (e.g. a package-visibility failure) - that raw reverse-DNS
 * identifier must never be treated as a usable, user-facing app name.
 */
internal fun isUsableAppLabel(resolved: String?, packageName: String): Boolean =
    !resolved.isNullOrEmpty() && resolved != packageName

internal fun capabilitiesFor(actions: Long): Set<String> {
    val result = mutableSetOf<String>()
    if (actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L) result += "play"
    if (actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L) result += "pause"
    if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) result += "next"
    if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) result += "previous"
    if (actions and PlaybackState.ACTION_SEEK_TO != 0L) result += "seek"
    return result
}

/** Normalizes a session's own volume scale (0..max, whatever max happens to be - AudioManager's
 * STREAM_MUSIC max varies by device, a remote VolumeProvider's max is arbitrary) to the flat 0-100
 * range the wire protocol and existing Mac-native media widget already use. */
internal fun normalizedVolume(current: Int, max: Int): Int? {
    if (max <= 0) return null
    return ((current * 100) / max).coerceIn(0, 100)
}

/** Inverse of [normalizedVolume]: maps an incoming 0-100 request back onto a session's own scale. */
internal fun denormalizedVolume(percent: Int, max: Int): Int =
    ((percent.coerceIn(0, 100) * max) / 100).coerceIn(0, max)

/**
 * PlaybackState.position/lastPositionUpdateTime are anchored to SystemClock.elapsedRealtime(),
 * which is meaningless off-device. This resolves the position as of "right now" on Android, so the
 * outbound payload can re-anchor it to a wall-clock timestamp the Mac can compare against its own
 * clock. If paused, playbackSpeed is 0 so the elapsed-age term drops out and position holds still.
 *
 * Takes primitives rather than a PlaybackState so it stays a plain, framework-free function -
 * testable in a normal JVM unit test without Robolectric/instrumentation.
 */
internal fun extrapolatedPosition(
    positionAtUpdate: Long,
    lastPositionUpdateElapsedRealtimeMs: Long,
    playbackSpeed: Float,
    nowElapsedRealtimeMs: Long,
    durationMs: Long,
): Long {
    val ageMs = (nowElapsedRealtimeMs - lastPositionUpdateElapsedRealtimeMs).coerceAtLeast(0)
    val raw = positionAtUpdate + (ageMs * playbackSpeed).toLong()
    val upperBound = if (durationMs > 0) durationMs else Long.MAX_VALUE
    return raw.coerceIn(0, upperBound)
}

class MediaContinuityManager(
    context: Context,
    private val available: () -> Boolean,
    private val send: (String, JSONObject) -> Boolean,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val componentName = ComponentName(appContext, BridgeyNotificationListenerService::class.java)
    private val sessionManager = appContext.getSystemService(MediaSessionManager::class.java)

    private val controllers = mutableMapOf<String, MediaController>()
    private val callbacks = mutableMapOf<String, MediaController.Callback>()
    private var primaryId: String? = null
    private var lastPlayingId: String? = null
    private var generation = 0L
    private var sequence = 0L
    private var lastSentArtworkHash: String? = null
    private var lastSnapshot = MediaRemoteState()
    private var started = false
    private var lastTrackChangeElapsedMs = 0L

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { refreshSessions(it) }

    /** Safe to call once; observation runs for the app's lifetime regardless of pairing/connection
     * state (cheap and event-driven) - only the outbound `send` is gated by [available]. */
    fun start() {
        if (started) return
        started = true
        runCatching {
            sessionManager?.addOnActiveSessionsChangedListener(sessionsListener, componentName, handler)
        }.onFailure {
            android.util.Log.w("Bridgey", "MEDIA could not register active-sessions listener: ${it.message}")
        }
        refreshSessions()
    }

    /** Called after a fresh connection is confirmed: the Mac must never rely on state that
     * predates this connection, so resend everything we currently know, artwork included. */
    fun sendFreshState() {
        android.util.Log.i("Bridgey", "MEDIA reconnect: resending fresh state hasSession=${lastSnapshot.hasSession}")
        lastSentArtworkHash = null
        publish(lastSnapshot)
    }

    /** Called on disconnect/session teardown. Session *observation* keeps running (it is
     * independent of the pairing connection); only the send-side dedup state is cleared so the
     * next reconnect's sendFreshState() re-includes artwork rather than assuming it's cached. */
    fun reset() {
        lastSentArtworkHash = null
    }

    /** Called whenever local settings or the peer's advertised feature state changes. */
    fun policyChanged() {
        if (available()) sendFreshState()
    }

    fun handleAction(payload: JSONObject): Pair<Boolean, String?> = runCatching {
        if (!available()) return@runCatching false to "no_session"
        val requestedGeneration = payload.optLong("generation", -1)
        if (!isActionGenerationValid(generation, requestedGeneration)) return@runCatching false to "stale_generation"
        val controller = primaryId?.let(controllers::get) ?: return@runCatching false to "no_session"
        val transportControls = controller.transportControls
        when (val action = payload.optString("action")) {
            "play" -> { transportControls.play(); true to null }
            "pause" -> { transportControls.pause(); true to null }
            "toggle" -> {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) transportControls.pause() else transportControls.play()
                true to null
            }
            "next", "previous" -> {
                val now = SystemClock.elapsedRealtime()
                if (!trackChangeAllowed(now, lastTrackChangeElapsedMs)) {
                    false to "rate_limited"
                } else {
                    lastTrackChangeElapsedMs = now
                    if (action == "next") transportControls.skipToNext() else transportControls.skipToPrevious()
                    true to null
                }
            }
            "seek" -> {
                val position = payload.optLong("value", -1)
                if (position < 0) false to "invalid" else { transportControls.seekTo(position); true to null }
            }
            "volume" -> {
                val percent = payload.optInt("value", -1)
                if (percent !in 0..100) false to "invalid" else applyVolume(controller, percent)
            }
            else -> false to "unsupported"
        }
    }.getOrElse { false to "execution_failed" }

    private fun refreshSessions(reported: List<MediaController>? = null) {
        val active = reported ?: runCatching { sessionManager?.getActiveSessions(componentName) }.getOrNull().orEmpty()
        val activeIds = active.map(::idFor).toSet()
        callbacks.keys.filter { it !in activeIds }.forEach { id ->
            callbacks.remove(id)?.let { callback -> controllers[id]?.unregisterCallback(callback) }
            controllers.remove(id)
        }
        active.forEach { controller ->
            val id = idFor(controller)
            controllers[id] = controller
            if (id !in callbacks) {
                android.util.Log.i("Bridgey", "MEDIA session discovered package=$id")
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = onSessionEvent(id, state)
                    override fun onMetadataChanged(metadata: MediaMetadata?) = onSessionEvent(id, controller.playbackState)
                    override fun onSessionDestroyed() = removeSession(id)
                }
                callbacks[id] = callback
                controller.registerCallback(callback, handler)
            }
        }
        recomputePrimaryAndPublish()
    }

    /**
     * Resolves a real, human-readable app name (e.g. "YouTube") for whatever app owns the current
     * MediaSession - never the raw reverse-DNS package name. getApplicationLabel() itself falls
     * back to returning the package name when it can't resolve a real label (most commonly because
     * of package-visibility restrictions on API 30+, see the <queries> block in the manifest); that
     * raw identifier must never reach the UI, so a null here means "show a generic fallback",
     * handled on the Mac side rather than guessing a name from the package string.
     */
    private fun humanReadableAppLabel(packageName: String?): String? {
        if (packageName == null) return null
        val resolved = runCatching {
            val info = appContext.packageManager.getApplicationInfo(packageName, 0)
            appContext.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()?.trim()
        return resolved.takeIf { isUsableAppLabel(it, packageName) }
    }

    private fun idFor(controller: MediaController): String = controller.packageName ?: "unknown"

    private fun removeSession(id: String) {
        android.util.Log.i("Bridgey", "MEDIA session removed package=$id")
        callbacks.remove(id)
        controllers.remove(id)
        recomputePrimaryAndPublish()
    }

    private fun onSessionEvent(id: String, state: PlaybackState?) {
        if (state?.state == PlaybackState.STATE_PLAYING) lastPlayingId = id
        recomputePrimaryAndPublish()
    }

    private fun recomputePrimaryAndPublish() {
        val snapshots = controllers.map { (id, controller) ->
            MediaSessionSnapshot(id, controller.playbackState?.state == PlaybackState.STATE_PLAYING)
        }
        val newPrimary = selectPrimarySession(snapshots, lastPlayingId, primaryId)
        if (newPrimary != primaryId) {
            generation += 1
            android.util.Log.i("Bridgey", "MEDIA primary session changed from=$primaryId to=$newPrimary generation=$generation")
        }
        primaryId = newPrimary
        publish(primaryId?.let(controllers::get)?.let(::buildSnapshot) ?: MediaRemoteState())
    }

    private fun buildSnapshot(controller: MediaController): MediaRemoteState {
        val metadata = controller.metadata
        val state = controller.playbackState
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0
        val position = state?.let {
            extrapolatedPosition(it.position, it.lastPositionUpdateTime, it.playbackSpeed, SystemClock.elapsedRealtime(), durationMs)
        } ?: 0
        val label = humanReadableAppLabel(controller.packageName)
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val artwork = bitmap?.let(::compressArtwork)
        val (volumeSupported, volumePercent) = readVolume(controller)
        return MediaRemoteState(
            hasSession = true,
            packageName = controller.packageName,
            appLabel = label?.take(128),
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.take(256),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.take(256),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.take(256),
            playing = state?.state == PlaybackState.STATE_PLAYING,
            position = position,
            duration = durationMs,
            playbackSpeed = state?.playbackSpeed ?: 1f,
            positionTimestamp = System.currentTimeMillis(),
            artworkHash = artwork?.let(::sha256Hex),
            artwork = artwork,
            capabilities = capabilitiesFor(state?.actions ?: 0L) + if (volumeSupported) setOf("volume") else emptySet(),
            volume = volumePercent,
        )
    }

    /**
     * Most phone-local players (Spotify, YouTube Music playing through the device speaker/
     * headphones) never implement a custom VolumeProvider - their MediaController.PlaybackInfo
     * reports PLAYBACK_TYPE_LOCAL, and volume is really just the device's music stream. Only
     * PLAYBACK_TYPE_REMOTE sessions (cast-style/Bluetooth-negotiated volume) carry a real
     * VolumeProvider, and even then only when volumeControl isn't VOLUME_CONTROL_FIXED.
     */
    /**
     * Volume is the one action that does NOT flow back through MediaController.Callback:
     * AudioManager.setStreamVolume() (the LOCAL path - by far the common case for phone-local
     * playback like YouTube/Spotify) is a completely separate system from MediaSession playback
     * state/metadata, so nothing about it ever fires onPlaybackStateChanged/onMetadataChanged. Every
     * *other* action (play/pause/next/previous/seek) is naturally followed by a fresh
     * media.remote.state because the resulting MediaSession callback triggers
     * recomputePrimaryAndPublish() on its own - volume needed that same republish added explicitly,
     * or the Mac never learns the new value and its slider is left showing the last state it had
     * (which looks exactly like "the change got reverted").
     */
    private fun applyVolume(controller: MediaController, percent: Int): Pair<Boolean, String?> {
        val info = controller.playbackInfo ?: return false to "unsupported"
        val applied = if (info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            val audioManager = appContext.getSystemService(AudioManager::class.java) ?: return false to "unsupported"
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, denormalizedVolume(percent, max), 0)
            true
        } else if (info.volumeControl != android.media.VolumeProvider.VOLUME_CONTROL_FIXED) {
            controller.setVolumeTo(denormalizedVolume(percent, info.maxVolume), 0)
            true
        } else {
            false
        }
        if (!applied) return false to "unsupported"
        recomputePrimaryAndPublish()
        return true to null
    }

    private fun readVolume(controller: MediaController): Pair<Boolean, Int?> {
        val info = controller.playbackInfo ?: return false to null
        return if (info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) {
            val audioManager = appContext.getSystemService(AudioManager::class.java)
            val max = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
            val current = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            true to normalizedVolume(current, max)
        } else if (info.volumeControl != android.media.VolumeProvider.VOLUME_CONTROL_FIXED) {
            true to normalizedVolume(info.currentVolume, info.maxVolume)
        } else {
            false to null
        }
    }

    private fun compressArtwork(bitmap: Bitmap): ByteArray? {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= 0) return null
        val scale = 128f / longestSide
        val width = maxOf(1, (bitmap.width * scale).toInt())
        val height = maxOf(1, (bitmap.height * scale).toInt())
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, width, height, true) else bitmap
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            out.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        // Mirrors MediaController.swift's smallMediaArtwork: a single quality pass, and skip the
        // artwork entirely (not a slow iterative re-encode) if it doesn't fit the target.
        return bytes.takeIf { it.size <= 16 * 1024 }
    }

    private fun publish(snapshot: MediaRemoteState) {
        lastSnapshot = snapshot
        if (!available()) return
        sequence += 1
        val includeArtwork = snapshot.artworkHash != null && snapshot.artworkHash != lastSentArtworkHash
        if (includeArtwork) lastSentArtworkHash = snapshot.artworkHash
        android.util.Log.i(
            "Bridgey",
            "MEDIA state sent hasSession=${snapshot.hasSession} package=${snapshot.packageName} " +
                "title=${snapshot.title} artist=${snapshot.artist} playing=${snapshot.playing} " +
                "position=${snapshot.position} duration=${snapshot.duration} generation=$generation sequence=$sequence " +
                "artworkHash=${snapshot.artworkHash} artworkIncluded=$includeArtwork volume=${snapshot.volume}",
        )
        send("media.remote.state", snapshot.toPayload(generation, sequence, includeArtwork))
    }

    private fun MediaRemoteState.toPayload(generation: Long, sequence: Long, includeArtwork: Boolean): JSONObject {
        val json = JSONObject()
            .put("version", 1)
            .put("generation", generation)
            .put("sequence", sequence)
            .put("hasSession", hasSession)
            .put("playing", playing)
            .put("position", position)
            .put("duration", duration)
            .put("playbackSpeed", playbackSpeed.toDouble())
            .put("positionTimestamp", positionTimestamp)
            .put("capabilities", JSONArray(capabilities.toList()))
        packageName?.let { json.put("packageName", it) }
        appLabel?.let { json.put("appLabel", it) }
        title?.let { json.put("title", it) }
        artist?.let { json.put("artist", it) }
        album?.let { json.put("album", it) }
        artworkHash?.let { json.put("artworkHash", it) }
        volume?.let { json.put("volume", it) }
        if (includeArtwork && artwork != null) {
            json.put("artwork", Base64.encodeToString(artwork, Base64.NO_WRAP))
        }
        return json
    }
}
