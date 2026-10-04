package dev.bridgey.android

// FEATURE APPLICABILITY (MD-1)
//
// Answers one question: "can feature X be offered from this device to that peer?" It keeps four
// things apart that the protocol and settings used to blur:
//
//   platform/device type  descriptive hints (discovery / trust metadata). They only decide what is
//                         *offered*; an unknown platform never hides anything. Never security.
//   direction             which platform is the source and which the target of the feature.
//   capability            the peer's features.update for this session (today the value is also the
//                         peer's grant to us - the wire does not separate the two).
//   authorization         the local grant for this device (BridgeySettings, per device).
//
// It is a static table per feature, not a rule engine. App layer: Core never sees it.

data class FeatureDirection(val from: DevicePlatform, val to: DevicePlatform)

enum class FeatureApplicabilityResult {
    OFFERED,
    /** This platform/device-type combination does not support or should not offer the feature. */
    NOT_APPLICABLE,
    /** The peer has not announced the feature for this session (or does not grant it to us). */
    PEER_LACKS_CAPABILITY,
    /** The local user has not granted the feature to this device. */
    NOT_AUTHORIZED,
}

internal object FeatureApplicability {
    private val ANDROID_TO_MAC = FeatureDirection(DevicePlatform.ANDROID, DevicePlatform.MACOS)
    private val MAC_TO_ANDROID = FeatureDirection(DevicePlatform.MACOS, DevicePlatform.ANDROID)
    private val ANDROID_TO_ANDROID = FeatureDirection(DevicePlatform.ANDROID, DevicePlatform.ANDROID)
    private val MAC_TO_MAC = FeatureDirection(DevicePlatform.MACOS, DevicePlatform.MACOS)
    private val EVERY_DIRECTION = setOf(ANDROID_TO_MAC, MAC_TO_ANDROID, ANDROID_TO_ANDROID, MAC_TO_MAC)

    /**
     * Directions in which the current implementation supports (and the product offers) a feature.
     * Mac → Mac is left out where macOS Continuity covers it (web links, clipboard) or where no Mac
     * can act (calls, notifications, media, Remote Start, KVM). Same table as the macOS side.
     */
    fun directions(feature: BridgeyFeature): Set<FeatureDirection> = when (feature) {
        BridgeyFeature.CLIPBOARD -> setOf(ANDROID_TO_MAC, MAC_TO_ANDROID, ANDROID_TO_ANDROID)
        BridgeyFeature.FILES, BridgeyFeature.FIND_DEVICE, BridgeyFeature.PING -> EVERY_DIRECTION
        BridgeyFeature.NOTIFICATIONS, BridgeyFeature.PHOTO_SYNC -> setOf(ANDROID_TO_MAC)
        BridgeyFeature.BATTERY, BridgeyFeature.STORAGE, BridgeyFeature.MEMORY, BridgeyFeature.CPU,
        BridgeyFeature.TEMPERATURE -> setOf(ANDROID_TO_MAC, MAC_TO_ANDROID)
        BridgeyFeature.LINKS, BridgeyFeature.MEDIA -> setOf(ANDROID_TO_MAC, MAC_TO_ANDROID)
        BridgeyFeature.CALLS -> setOf(ANDROID_TO_MAC, MAC_TO_ANDROID)
        BridgeyFeature.REMOTE_SCREEN_SHARE, BridgeyFeature.KVM_INPUT -> setOf(MAC_TO_ANDROID)
    }

    /** Calls need the Android end to own a cellular line: a known "tablet" is not offered. */
    fun requiresAndroidPhone(feature: BridgeyFeature): Boolean = feature == BridgeyFeature.CALLS

    fun evaluate(
        feature: BridgeyFeature,
        localPlatform: DevicePlatform,
        localDeviceType: String?,
        peer: DeviceDirectoryEntry,
        locallyAuthorized: Boolean,
    ): FeatureApplicabilityResult {
        if (localPlatform != DevicePlatform.UNKNOWN && peer.platform != DevicePlatform.UNKNOWN &&
            FeatureDirection(localPlatform, peer.platform) !in directions(feature)
        ) {
            return FeatureApplicabilityResult.NOT_APPLICABLE
        }
        if (requiresAndroidPhone(feature)) {
            val androidType = when {
                localPlatform == DevicePlatform.ANDROID -> localDeviceType
                peer.platform == DevicePlatform.ANDROID -> peer.deviceType
                else -> null
            }
            if (androidType != null && androidType != "phone") return FeatureApplicabilityResult.NOT_APPLICABLE
        }
        if (peer.capabilities?.get(feature.key) != true) return FeatureApplicabilityResult.PEER_LACKS_CAPABILITY
        if (!locallyAuthorized) return FeatureApplicabilityResult.NOT_AUTHORIZED
        return FeatureApplicabilityResult.OFFERED
    }
}
