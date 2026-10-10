package dev.bridgey.android

// HANDOFF PER PEER (MD-7)
//
// Handoff and the Web links / book cards ride `quick.request` / `quick.result` (feature "links").
// The same kinds also carry feature "media", which is not migrated and stays with the routed peer.
// The wire format is unchanged: the destination is the session a message is sent on, the sender
// is the authenticated session it arrived on.

/** "links" from any connected peer, "media" only from the routed peer, anything else never. */
internal object QuickPayloadGate {
    fun accepts(feature: String?, fromRoutedPeer: Boolean): Boolean = when (feature) {
        "links" -> true
        "media" -> fromRoutedPeer
        else -> false
    }
}

/**
 * Where a target-less handoff (browser chip, Share, Play Books tile) goes: the only eligible Mac,
 * a choice among several, or nowhere. Never the routed peer by default and never a fallback.
 */
internal sealed interface HandoffTarget {
    data class Send(val deviceId: String) : HandoffTarget
    data class Choose(val deviceIds: List<String>) : HandoffTarget
    data object None : HandoffTarget

    companion object {
        fun resolve(eligible: List<String>): HandoffTarget = when (eligible.size) {
            0 -> None
            1 -> Send(eligible[0])
            else -> Choose(eligible)
        }
    }
}

/** A web link received from a Mac, kept per sender until opened or dismissed (also offline). */
data class ReceivedLink(val deviceId: String, val peerName: String, val url: String)
