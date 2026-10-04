import Foundation

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

struct FeatureDirection: Hashable {
    let from: DevicePlatform
    let to: DevicePlatform
}

enum FeatureApplicabilityResult: Equatable {
    case offered
    /// This platform/device-type combination does not support or should not offer the feature.
    case notApplicable
    /// The peer has not announced the feature for this session (or does not grant it to us).
    case peerLacksCapability
    /// The local user has not granted the feature to this device.
    case notAuthorized
}

enum FeatureApplicability {
    private static let androidToMac = FeatureDirection(from: .android, to: .macos)
    private static let macToAndroid = FeatureDirection(from: .macos, to: .android)
    private static let androidToAndroid = FeatureDirection(from: .android, to: .android)
    private static let macToMac = FeatureDirection(from: .macos, to: .macos)
    private static let everyDirection: Set<FeatureDirection> = [androidToMac, macToAndroid, androidToAndroid, macToMac]

    /// Directions in which the current implementation supports (and the product offers) a feature.
    /// Mac → Mac is left out where macOS Continuity covers it (web links, clipboard) or where no
    /// Mac can act (calls, notifications, media, Remote Start).
    static func directions(for feature: BridgeyFeature) -> Set<FeatureDirection> {
        switch feature {
        case .clipboard: [androidToMac, macToAndroid, androidToAndroid]
        case .files, .findDevice, .ping: everyDirection
        case .notifications, .photoSync: [androidToMac]
        case .battery, .storage, .memory, .cpu, .temperature: [androidToMac, macToAndroid]
        case .links, .media: [androidToMac, macToAndroid]
        case .calls: [androidToMac, macToAndroid]
        case .remoteScreenShare: [macToAndroid]
        }
    }

    /// Calls need the Android end to own a cellular line: a known "tablet" is not offered.
    static func requiresAndroidPhone(_ feature: BridgeyFeature) -> Bool { feature == .calls }

    static func evaluate(
        _ feature: BridgeyFeature,
        localPlatform: DevicePlatform,
        localDeviceType: String?,
        peer: DeviceDirectoryEntry,
        locallyAuthorized: Bool
    ) -> FeatureApplicabilityResult {
        if localPlatform != .unknown, peer.platform != .unknown,
           !directions(for: feature).contains(FeatureDirection(from: localPlatform, to: peer.platform)) {
            return .notApplicable
        }
        if requiresAndroidPhone(feature) {
            let androidType = localPlatform == .android ? localDeviceType : (peer.platform == .android ? peer.deviceType : nil)
            if let androidType, androidType != "phone" { return .notApplicable }
        }
        guard peer.capabilities?[feature.rawValue] == true else { return .peerLacksCapability }
        guard locallyAuthorized else { return .notAuthorized }
        return .offered
    }
}
