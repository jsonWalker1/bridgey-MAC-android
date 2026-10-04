import Foundation

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
//   direction         source → target. Local and remote are a separate question (see `evaluate`).
//   capability        the peer's features.update for this session (today the value also carries
//                     the peer's grant to us - the wire does not separate the two).
//   authorization     the local grant for that device (BridgeySettings). Explicitly authorized
//                     features (Remote Start, KVM) stay opt-in on the device that is acted on.
//
// A static table per feature (the matrix of the multi-device readiness audit), not a rule engine.
// App layer: Core never sees it. Mode and Context are not part of it.

struct FeatureDirection: Hashable {
    let from: DevicePlatform
    let to: DevicePlatform
}

enum FeatureApplicabilityResult: Equatable {
    case offered
    /// Platform, kind or role of source/target does not fit, or the product does not offer it.
    case notApplicable
    /// The peer has not announced the feature for this session (or does not grant it to us).
    case peerLacksCapability
    /// The local user has not granted the feature to this device.
    case notAuthorized
}

enum FeatureApplicability {
    /// Product features of the readiness audit (§3/§4), named for what the user sees.
    enum Feature: CaseIterable {
        case clipboard
        case files
        case photoSync
        case notificationMirror
        case notificationActions
        case callState
        case callControl
        case mediaRemote
        case macPlayerControl
        case telemetry
        case findDevice
        case ping
        case webHandoff
        case booksHandoff
        case linkToPhone
        case screenShare
        case remoteStart
        case kvm
    }

    /// Which end of a direction a role requirement applies to.
    enum End { case source, target }

    struct Rule {
        /// Allowed source → target platforms; nil = deliberately platform-independent.
        let directions: Set<FeatureDirection>?
        /// features.update keys that carry the capability (any one suffices; empty = not negotiated).
        let capabilityKeys: [String]
        /// This end must own the cellular line.
        var cellularEnd: End? = nil
        /// Opt-in on the device that is acted on; never offered without an explicit grant.
        var explicitAuthorization = false
    }

    private static let androidToMac = FeatureDirection(from: .android, to: .macos)
    private static let macToAndroid = FeatureDirection(from: .macos, to: .android)
    private static let androidToAndroid = FeatureDirection(from: .android, to: .android)

    /// The audit's applicability matrix. Mac → Mac is not offered where macOS Continuity covers it
    /// (web links, clipboard) or where no Mac can act (calls, notifications, media, screen share,
    /// Remote Start, KVM). Same table as the Android side.
    static func rule(for feature: Feature) -> Rule {
        switch feature {
        case .clipboard: Rule(directions: [androidToMac, macToAndroid, androidToAndroid], capabilityKeys: ["clipboard"])
        case .files: Rule(directions: nil, capabilityKeys: ["files"])
        case .photoSync: Rule(directions: [androidToMac], capabilityKeys: ["photo_sync"])
        case .notificationMirror: Rule(directions: [androidToMac], capabilityKeys: ["notifications"])
        case .notificationActions: Rule(directions: [macToAndroid], capabilityKeys: ["notifications"])
        case .callState: Rule(directions: [androidToMac], capabilityKeys: ["calls"], cellularEnd: .source)
        case .callControl: Rule(directions: [macToAndroid], capabilityKeys: ["calls"], cellularEnd: .target)
        case .mediaRemote: Rule(directions: [androidToMac], capabilityKeys: ["media"])
        // Source = the Android controller, target = the Mac whose player is controlled.
        case .macPlayerControl: Rule(directions: [androidToMac], capabilityKeys: ["media"])
        case .telemetry: Rule(directions: [androidToMac, macToAndroid], capabilityKeys: ["battery", "storage", "memory", "cpu", "temperature"])
        case .findDevice: Rule(directions: nil, capabilityKeys: ["find_device"])
        case .ping: Rule(directions: nil, capabilityKeys: ["ping"])
        case .webHandoff, .booksHandoff: Rule(directions: [androidToMac], capabilityKeys: ["links"])
        case .linkToPhone: Rule(directions: [macToAndroid], capabilityKeys: ["links"])
        // Started on the phone (MediaProjection consent); no negotiated key.
        case .screenShare: Rule(directions: [androidToMac], capabilityKeys: [])
        case .remoteStart: Rule(directions: [macToAndroid], capabilityKeys: ["remote_screen_share"], explicitAuthorization: true)
        case .kvm: Rule(directions: [macToAndroid], capabilityKeys: ["kvm_input"], explicitAuthorization: true)
        }
    }

    /// Product + platform + role only: should `feature` exist from `source` to `target` at all?
    /// No capability, no authorization, no notion of local or remote.
    static func isApplicable(_ feature: Feature, source: DeviceProfile, target: DeviceProfile) -> Bool {
        let rule = rule(for: feature)
        if let directions = rule.directions,
           !directions.contains(FeatureDirection(from: source.platform, to: target.platform)) {
            return false // also false for an unknown platform: it matches no direction
        }
        switch rule.cellularEnd {
        case .source?: return source.ownsCellularLine
        case .target?: return target.ownsCellularLine
        case nil: return true
        }
    }

    /// Whether `feature` is offered between this device and `peer`, in the given direction
    /// (`localIsSource`: this device is the source). Checks, in order: applicability, the peer's
    /// capability, the local grant for that peer (`isLocallyAuthorized(key)`; a key the local
    /// catalog does not know has no local grant to check, the peer's grant still applies).
    static func evaluate(
        _ feature: Feature,
        local: DeviceProfile,
        peer: DeviceDirectoryEntry,
        localIsSource: Bool,
        isLocallyAuthorized: (String) -> Bool
    ) -> FeatureApplicabilityResult {
        let source = localIsSource ? local : peer.profile
        let target = localIsSource ? peer.profile : local
        guard isApplicable(feature, source: source, target: target) else { return .notApplicable }
        let keys = rule(for: feature).capabilityKeys
        guard !keys.isEmpty else { return .offered }
        let capable = keys.filter { peer.capabilities?[$0] == true }
        guard !capable.isEmpty else { return .peerLacksCapability }
        return capable.contains(where: isLocallyAuthorized) ? .offered : .notAuthorized
    }
}
