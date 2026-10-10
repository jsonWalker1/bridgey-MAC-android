package dev.bridgey.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

internal class QuickRequestSequence {
    private val last = mutableMapOf<String, Long>()
    @Synchronized fun accept(feature: String, sequence: Long): Boolean {
        if (feature !in setOf("links", "media") || sequence !in 1..9_007_199_254_740_991L || sequence <= (last[feature] ?: 0)) return false
        last[feature] = sequence
        return true
    }
}

internal fun validatedWebLink(value: String): String? {
    val text = value.trim()
    if (text.toByteArray(Charsets.UTF_8).size > 4096 || text.any { it.isWhitespace() || it.code < 32 || it == '\\' }) return null
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    return text.takeIf { uri.scheme?.lowercase() in setOf("https", "http") &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535) }
}

data class MacMediaState(
    val player: String = "", val title: String = "", val artist: String = "",
    val playing: Boolean = false, val position: Int = 0, val duration: Int = 0, val volume: Int = 0,
    val detail: String = "Enable a player in Mac Settings → Media",
    val artwork: String? = null,
)

/**
 * Quick actions over `quick.request` / `quick.result`. MD-7: Web links and book handoff are per
 * peer (explicit target on send, sender on receive, one received link per Mac); Mac media stays
 * with the routed peer (`available` / `send`).
 */
class QuickActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val available: (BridgeyFeature) -> Boolean,
    private val send: (String, JSONObject) -> Boolean,
    /** One message on exactly this peer's session (no routed fallback). */
    private val sendTo: (String, JSONObject, String) -> Boolean,
    /** Whether a handoff/link may be sent to / accepted from this peer now. */
    private val sendable: (String) -> Boolean,
    private val receivable: (String) -> Boolean,
    /** Whether the local grant still allows this peer's received link (also while it is offline). */
    private val granted: (String) -> Boolean,
    private val peerName: (String) -> String,
) {
    private val mutableLinks = MutableStateFlow<Map<String, ReceivedLink>>(emptyMap())
    /** MD-7: received links, one per sending Mac. */
    val receivedLinks = mutableLinks.asStateFlow()
    private val mutableStatus = MutableStateFlow<String?>(null)
    val status = mutableStatus.asStateFlow()
    private val mutableLinkStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    /** The latest Web link / book handoff status per Mac, so an outcome is never another Mac's. */
    val linkStatus = mutableLinkStatus.asStateFlow()

    private fun linkStatus(deviceId: String, text: String) {
        mutableStatus.value = text
        mutableLinkStatus.update { it + (deviceId to text) }
    }
    private val mutableMedia = MutableStateFlow(MacMediaState())
    val media = mutableMedia.asStateFlow()
    /** "media" (routed peer) or "links|<deviceId>" -> (requestId, timeout). */
    private val pending = mutableMapOf<String, Pair<String, Job>>()
    private var sequence = 0L

    private fun linksKey(deviceId: String) = "links|$deviceId"

    /** Mac media (routed peer). Returns true only when the request was actually sent. */
    @Synchronized fun request(feature: BridgeyFeature, action: String, value: String = ""): Boolean {
        if (!available(feature)) { mutableStatus.value = "Feature is unavailable on one of your devices"; return false }
        return start(feature.key, feature.key, action, value, { mutableStatus.value = it }) { kind, payload -> send(kind, payload) }
    }

    /** Web link / book handoff to exactly [deviceId]. Returns true only when it was actually sent. */
    @Synchronized private fun requestTo(deviceId: String, action: String, value: String): Boolean {
        if (!sendable(deviceId)) { linkStatus(deviceId, "Web links are unavailable on ${peerName(deviceId)}"); return false }
        return start(linksKey(deviceId), BridgeyFeature.LINKS.key, action, value, { linkStatus(deviceId, it) }) { kind, payload ->
            sendTo(kind, payload, deviceId)
        }
    }

    private fun start(
        key: String, feature: String, action: String, value: String,
        report: (String) -> Unit, deliver: (String, JSONObject) -> Boolean,
    ): Boolean {
        if (key in pending) return false
        val id = UUID.randomUUID().toString()
        val expiry = scope.launch {
            delay(8000)
            synchronized(this@QuickActions) {
                if (pending[key]?.first == id) {
                    pending.remove(key)
                    report("The other device did not confirm the request")
                }
            }
        }
        pending[key] = id to expiry
        sequence += 1
        report("Sending…")
        if (!deliver("quick.request", JSONObject().put("version", 1).put("requestId", id)
                .put("feature", feature).put("action", action).put("value", value).put("sequence", sequence))) {
            pending.remove(key)?.second?.cancel()
            report("Not connected — request not sent")
            return false
        }
        return true
    }

    fun sendLink(deviceId: String, value: String): Boolean {
        val url = validatedWebLink(value)
        if (url == null) { mutableStatus.value = "Copy a valid http or https link first"; return false }
        return requestTo(deviceId, "offer", url)
    }

    /** Books Handoff: a small JSON description of the book/position (see BooksHandoff.kt). */
    fun sendBook(deviceId: String, payload: String): Boolean {
        if (payload.isBlank() || payload.toByteArray().size > BOOK_PAYLOAD_MAX) {
            mutableStatus.value = "Book details too large — not sent"; return false
        }
        return requestTo(deviceId, "book", payload)
    }

    /** A quick-action message from [from] (the authenticated sender; the routed peer for media). */
    @Synchronized fun receive(kind: String, payload: JSONObject, from: String) {
        if (payload.opt("version") != 1) return
        when (kind) {
            "quick.request" -> {
                val id = payload.optString("requestId")
                if (runCatching { UUID.fromString(id) }.isFailure) return
                val link = validatedWebLink(payload.optString("value"))
                val accepted = receivable(from) && payload.optString("feature") == "links" &&
                    payload.optString("action") == "offer" && link != null && from !in mutableLinks.value
                if (accepted) mutableLinks.update { it + (from to ReceivedLink(from, peerName(from), link!!)) }
                sendTo("quick.result", JSONObject().put("version", 1).put("requestId", id).put("feature", "links")
                    .put("accepted", accepted), from)
            }
            "quick.result" -> {
                val feature = payload.optString("feature")
                // Only the sender's own request completes; another peer's result never does.
                val key = if (feature == "links") linksKey(from) else feature
                if (pending[key]?.first != payload.optString("requestId")) return
                pending.remove(key)?.second?.cancel()
                val name = peerName(from)
                val accepted = payload.opt("accepted") == true
                when {
                    feature == "links" && accepted -> linkStatus(from, "Link delivered to $name — waiting for the user to open it")
                    feature == "links" -> linkStatus(from, "Link declined by $name — check settings or dismiss the previous link")
                    accepted -> mutableStatus.value = "Media command completed"
                    else -> mutableStatus.value = "Player unavailable or busy — check Mac media settings and try again"
                }
            }
            "media.state" -> {
                if (!available(BridgeyFeature.MEDIA)) return
                val position = payload.opt("position") as? Int ?: return
                val duration = payload.opt("duration") as? Int ?: return
                val volume = payload.opt("volume") as? Int ?: return
                if (position !in 0..604800 || duration !in 0..604800 || volume !in 0..100 ||
                    payload.opt("playing") !is Boolean) return
                mutableMedia.value = MacMediaState(
                    payload.optString("player").take(64), payload.optString("title").take(256),
                    payload.optString("artist").take(256), payload.getBoolean("playing"),
                    position, duration, volume, payload.optString("detail").take(256),
                    (payload.opt("artwork") as? String)?.takeIf { it.length <= 21848 },
                )
            }
        }
    }

    fun openLink(deviceId: String) {
        val received = mutableLinks.value[deviceId] ?: return
        val link = validatedWebLink(received.url) ?: return
        if (!granted(deviceId)) { dismissLink(deviceId); return }
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { dismissLink(deviceId) }
            .onFailure { mutableStatus.value = "No browser available" }
    }
    fun dismissLink(deviceId: String) { mutableLinks.update { it - deviceId } }

    @Synchronized fun policyChanged() {
        mutableLinks.update { links -> links.filterKeys(granted) }
        if (!available(BridgeyFeature.MEDIA)) mutableMedia.value = MacMediaState()
        pending.entries.removeAll { (key, operation) ->
            val disabled = if (key.startsWith("links|")) !sendable(key.removePrefix("links|"))
            else BridgeyFeature.entries.find { it.key == key }?.let { !available(it) } ?: true
            if (disabled) { operation.second.cancel(); mutableStatus.value = null }
            disabled
        }
    }

    /** The routed peer changed: only the media state (which belongs to it) goes. */
    @Synchronized fun routedPeerChanged() {
        pending.remove(BridgeyFeature.MEDIA.key)?.second?.cancel()
        mutableMedia.value = MacMediaState()
    }

    /** The peer's session ended: its unconfirmed request is over; its received link stays (D2). */
    @Synchronized fun deviceEnded(deviceId: String) {
        if (pending.remove(linksKey(deviceId))?.second?.cancel() != null) {
            linkStatus(deviceId, "${peerName(deviceId)} disconnected — not confirmed")
        }
    }

    /** Bridgey paused or stopped: everything goes. */
    @Synchronized fun reset() {
        pending.values.forEach { it.second.cancel() }; pending.clear()
        mutableLinks.value = emptyMap(); mutableLinkStatus.value = emptyMap()
        mutableMedia.value = MacMediaState(); mutableStatus.value = null
    }
}
