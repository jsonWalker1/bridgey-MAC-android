package dev.bridgey.android

// FEATURE APPLICABILITY (MD-1, device roles MD-2)
//
// Answers "should Bridgey offer this feature from this source device to this target device?" and
// keeps five things apart:
//
//   product feature   what the user sees (Web Handoff, Books Handoff, Calls control, ...). One
//                     capability key can carry several of them (`links` = web + books + link to
//                     phone), so applicability is decided per product feature, not per key.
//   profile           platform + kind hints and the roles derived from them (e.g. "owns the
//                     cellular line"). Unverified hints: they can only hide a feature, never grant
//                     one. An unknown platform or kind never makes a feature applicable.
//   direction         source → target. Local and remote are a separate question (see evaluate).
//   capability        the peer's features.update for this session (today the value also carries
//                     the peer's grant to us - the wire does not separate the two).
//   authorization     the local grant for that device (BridgeySettings). Explicitly authorized
//                     features (Remote Start, KVM) stay opt-in on the device that is acted on.
//
// A static table per feature (the matrix of the multi-device readiness audit), not a rule engine.
// App layer: Core never sees it. Mode and Context are not part of it.

data class FeatureDirection(val from: DevicePlatform, val to: DevicePlatform)

enum class FeatureApplicabilityResult {
    OFFERED,
    /** Platform, kind or role of source/target does not fit, or the product does not offer it. */
    NOT_APPLICABLE,
    /** The peer has not announced the feature for this session (or does not grant it to us). */
    PEER_LACKS_CAPABILITY,
    /** The local user has not granted the feature to this device. */
    NOT_AUTHORIZED,
}

internal object FeatureApplicability {
    /** Product features of the readiness audit (§3/§4), named for what the user sees. */
    enum class Feature {
        CLIPBOARD, FILES, PHOTO_SYNC, NOTIFICATION_MIRROR, NOTIFICATION_ACTIONS, CALL_STATE, CALL_CONTROL,
        MEDIA_REMOTE, MAC_PLAYER_CONTROL, TELEMETRY, FIND_DEVICE, PING, WEB_HANDOFF, BOOKS_HANDOFF,
        LINK_TO_PHONE, SCREEN_SHARE, REMOTE_START, KVM,
    }

    /** Which end of a direction a role requirement applies to. */
    enum class End { SOURCE, TARGET }

    data class Rule(
        /** Allowed source → target platforms; null = deliberately platform-independent. */
        val directions: Set<FeatureDirection>?,
        /** features.update keys that carry the capability (any one suffices; empty = not negotiated). */
        val capabilityKeys: List<String>,
        /** This end must own the cellular line. */
        val cellularEnd: End? = null,
        /** Opt-in on the device that is acted on; never offered without an explicit grant. */
        val explicitAuthorization: Boolean = false,
    )

    private val ANDROID_TO_MAC = FeatureDirection(DevicePlatform.ANDROID, DevicePlatform.MACOS)
    private val MAC_TO_ANDROID = FeatureDirection(DevicePlatform.MACOS, DevicePlatform.ANDROID)
    private val ANDROID_TO_ANDROID = FeatureDirection(DevicePlatform.ANDROID, DevicePlatform.ANDROID)

    /**
     * The audit's applicability matrix. Mac → Mac is not offered where macOS Continuity covers it
     * (web links, clipboard) or where no Mac can act (calls, notifications, media, screen share,
     * Remote Start, KVM). Same table as the macOS side.
     */
    fun rule(feature: Feature): Rule = when (feature) {
        Feature.CLIPBOARD -> Rule(setOf(ANDROID_TO_MAC, MAC_TO_ANDROID, ANDROID_TO_ANDROID), listOf("clipboard"))
        Feature.FILES -> Rule(null, listOf("files"))
        Feature.PHOTO_SYNC -> Rule(setOf(ANDROID_TO_MAC), listOf("photo_sync"))
        Feature.NOTIFICATION_MIRROR -> Rule(setOf(ANDROID_TO_MAC), listOf("notifications"))
        Feature.NOTIFICATION_ACTIONS -> Rule(setOf(MAC_TO_ANDROID), listOf("notifications"))
        Feature.CALL_STATE -> Rule(setOf(ANDROID_TO_MAC), listOf("calls"), cellularEnd = End.SOURCE)
        Feature.CALL_CONTROL -> Rule(setOf(MAC_TO_ANDROID), listOf("calls"), cellularEnd = End.TARGET)
        Feature.MEDIA_REMOTE -> Rule(setOf(ANDROID_TO_MAC), listOf("media"))
        // Source = the Android controller, target = the Mac whose player is controlled.
        Feature.MAC_PLAYER_CONTROL -> Rule(setOf(ANDROID_TO_MAC), listOf("media"))
        Feature.TELEMETRY -> Rule(setOf(ANDROID_TO_MAC, MAC_TO_ANDROID), listOf("battery", "storage", "memory", "cpu", "temperature"))
        Feature.FIND_DEVICE -> Rule(null, listOf("find_device"))
        Feature.PING -> Rule(null, listOf("ping"))
        Feature.WEB_HANDOFF, Feature.BOOKS_HANDOFF -> Rule(setOf(ANDROID_TO_MAC), listOf("links"))
        Feature.LINK_TO_PHONE -> Rule(setOf(MAC_TO_ANDROID), listOf("links"))
        // Started on the phone (MediaProjection consent); no negotiated key.
        Feature.SCREEN_SHARE -> Rule(setOf(ANDROID_TO_MAC), emptyList())
        Feature.REMOTE_START -> Rule(setOf(MAC_TO_ANDROID), listOf("remote_screen_share"), explicitAuthorization = true)
        Feature.KVM -> Rule(setOf(MAC_TO_ANDROID), listOf("kvm_input"), explicitAuthorization = true)
    }

    /**
     * Product + platform + role only: should [feature] exist from [source] to [target] at all?
     * No capability, no authorization, no notion of local or remote.
     */
    fun isApplicable(feature: Feature, source: DeviceProfile, target: DeviceProfile): Boolean {
        val rule = rule(feature)
        // Also false for an unknown platform: it matches no direction.
        if (rule.directions != null && FeatureDirection(source.platform, target.platform) !in rule.directions) return false
        return when (rule.cellularEnd) {
            End.SOURCE -> source.ownsCellularLine
            End.TARGET -> target.ownsCellularLine
            null -> true
        }
    }

    /**
     * Whether [feature] is offered between this device and [peer], in the given direction
     * ([localIsSource]: this device is the source). Checks, in order: applicability, the peer's
     * capability, the local grant for that peer ([isLocallyAuthorized] by key; a key the local
     * catalog does not know has no local grant to check, the peer's grant still applies).
     */
    fun evaluate(
        feature: Feature,
        local: DeviceProfile,
        peer: DeviceDirectoryEntry,
        localIsSource: Boolean,
        isLocallyAuthorized: (String) -> Boolean,
    ): FeatureApplicabilityResult {
        val source = if (localIsSource) local else peer.profile
        val target = if (localIsSource) peer.profile else local
        if (!isApplicable(feature, source, target)) return FeatureApplicabilityResult.NOT_APPLICABLE
        val keys = rule(feature).capabilityKeys
        if (keys.isEmpty()) return FeatureApplicabilityResult.OFFERED
        val capable = keys.filter { peer.capabilities?.get(it) == true }
        if (capable.isEmpty()) return FeatureApplicabilityResult.PEER_LACKS_CAPABILITY
        return if (capable.any(isLocallyAuthorized)) FeatureApplicabilityResult.OFFERED else FeatureApplicabilityResult.NOT_AUTHORIZED
    }
}
