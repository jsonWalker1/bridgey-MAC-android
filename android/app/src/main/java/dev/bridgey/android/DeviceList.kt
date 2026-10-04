package dev.bridgey.android

// DEVICE LIST (MD-4, UI layer)
//
// A read-only presentation of the device directory plus the user's selection. It never stores
// devices of its own (the directory is the source), never knows a feature, and never stands in for
// routing: selectedDeviceId only says which device a user action should target.

internal data class DeviceListItem(
    val deviceId: String,
    val name: String,
    val platform: DevicePlatform,
    val kind: DeviceKind,
    val isConnected: Boolean,
) {
    /** "Android · Phone", "macOS · Computer"; empty when nothing is known. */
    val detail: String
        get() = listOfNotNull(
            when (platform) {
                DevicePlatform.ANDROID -> "Android"
                DevicePlatform.MACOS -> "macOS"
                DevicePlatform.UNKNOWN -> null
            },
            when (kind) {
                DeviceKind.PHONE -> "Phone"
                DeviceKind.TABLET -> "Tablet"
                DeviceKind.COMPUTER -> "Computer"
                DeviceKind.UNKNOWN -> null
            },
        ).joinToString(" · ")
}

/**
 * The selected peer as the UI shows it, kept apart from the legacy routed peer (MD-4b).
 * Peer-centric: no device is "main"; the routed peer only matters to features that still use the
 * single-peer compatibility routing.
 */
internal data class SelectedDeviceContext(
    /** The peer the user is looking at; null when nothing is selected. */
    val selected: DeviceListItem?,
    /** The connected peer legacy single-peer features currently use (preferredDeviceId routing). */
    val routed: DeviceListItem?,
) {
    /**
     * Legacy feature state (telemetry, media, calls, files, clipboard, links, screen share) belongs
     * to the routed peer. It may be shown under the selected peer only when they are the same peer.
     */
    val legacyFeaturesApply: Boolean
        get() = selected != null && routed != null && selected.deviceId == routed.deviceId

    /** The routed peer when it is a different peer than the selected one, null otherwise. */
    val legacyFeaturesUseOtherPeer: DeviceListItem?
        get() = routed?.takeIf { it.deviceId != selected?.deviceId }

    companion object {
        fun make(items: List<DeviceListItem>, selectedDeviceId: String?, routedDeviceId: String?) = SelectedDeviceContext(
            selected = items.firstOrNull { it.deviceId == selectedDeviceId && it.isConnected },
            routed = items.firstOrNull { it.deviceId == routedDeviceId && it.isConnected },
        )
    }
}

/** Which device a feature action targets. Always an explicit device, never "the routed one". */
internal sealed interface DeviceTarget {
    data class Device(val deviceId: String) : DeviceTarget
    /** Several eligible devices and no eligible selection: the user picks one. */
    data class Choose(val deviceIds: List<String>) : DeviceTarget
    data object None : DeviceTarget
}

internal object DeviceList {
    /** Connected devices first, then the directory's stable order (name, then deviceId). */
    fun items(entries: List<DeviceDirectoryEntry>, connectedOnly: Boolean = true): List<DeviceListItem> = entries
        .map { DeviceListItem(it.deviceId, it.name, it.platform, it.kind, it.connection == PeerConnectionState.CONNECTED) }
        .filter { !connectedOnly || it.isConnected }
        .sortedBy { if (it.isConnected) 0 else 1 } // stable: keeps the directory order within each group

    /**
     * Keeps a selection only while that device is still listed and connected. When it goes away,
     * the only remaining connected device is selected (unambiguous); otherwise nothing is.
     */
    fun reconcile(selected: String?, items: List<DeviceListItem>): String? {
        val connected = items.filter { it.isConnected }
        if (selected != null && connected.any { it.deviceId == selected }) return selected
        return connected.singleOrNull()?.deviceId
    }

    /**
     * The target of an action among [eligible] devices (already filtered by applicability): the
     * selection if it is eligible, else the only eligible device, else the user chooses.
     */
    fun target(selected: String?, eligible: List<String>): DeviceTarget = when {
        selected != null && selected in eligible -> DeviceTarget.Device(selected)
        eligible.size == 1 -> DeviceTarget.Device(eligible.single())
        eligible.isEmpty() -> DeviceTarget.None
        else -> DeviceTarget.Choose(eligible)
    }
}
