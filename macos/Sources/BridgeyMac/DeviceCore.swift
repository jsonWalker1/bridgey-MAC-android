import Foundation

// MULTI-DEVICE CORE
//
// Every Bridgey installation is an equal peer: a Mac, an Android phone or tablet, or any future
// platform. The Core is built from these pieces, and nothing in them knows which platform is on
// the other side:
//
//   LocalDevice        this installation: deviceId + identity key + name + platform + deviceType
//   DeviceRegistry     trusted peers keyed by deviceId (existing MacTrustRegistry storage),
//                      plus ephemeral presence (endpoints from discovery)
//   PeerSessionManager sessions[deviceId] -> PeerSession, plus pending sockets that have not yet
//                      said who they are. Sessions are fully independent of each other.
//   DeviceRouting      the routing/compatibility seam: which connected device today's
//                      single-peer features use (activePeer). Not a property of any session.
//
// Identity is deviceId + the pinned identity key. Bonjour service name, hostname, IP address, port,
// platform and deviceType are discovery hints and never identify, trust, route or reconnect a
// device. Trust is pairwise and never propagated. CONNECTED != ACTIVE.

struct LocalDevice {
    let deviceID: String
    let identity: MacIdentity
    var name: String
    let platform = "macos"
    let deviceType = "computer"

    var identityKey: String { identity.publicKey }

    /// Loads this Mac's persistent identity from the same stores as before (UserDefaults
    /// `deviceID`, Keychain `dev.bridgey.identity`). Identity generation is unchanged.
    static func load(name: String) -> LocalDevice {
        let defaults = UserDefaults.standard
        let deviceID: String
        if let stored = defaults.string(forKey: "deviceID"), UUID(uuidString: stored) != nil {
            deviceID = stored
        } else {
            deviceID = UUID().uuidString.lowercased()
            defaults.set(deviceID, forKey: "deviceID")
        }
        return LocalDevice(deviceID: deviceID, identity: MacIdentity(), name: name)
    }
}

// MARK: - Presence (discovery, keyed by deviceId)

struct PeerEndpoint: Equatable {
    let serviceName: String
    let host: String
    let port: Int
}

struct PeerPresence: Equatable {
    let deviceID: String
    let name: String
    let platform: String?
    let deviceType: String?
    let protocolVersion: Int?
    let endpoints: [PeerEndpoint]
}

enum DevicePresence {
    /// Groups discovery adverts by their TXT deviceId. One device may be advertised under several
    /// service names (mDNS renames, stale re-registrations); one service name is never a device.
    /// Adverts without a valid id, unresolved adverts and our own advert are not peers.
    static func group(_ peers: [DiscoveredPeer], localDeviceID: String) -> [String: PeerPresence] {
        var grouped: [String: [DiscoveredPeer]] = [:]
        let own = localDeviceID.lowercased() // TXT ids are lowercased; a legacy stored id may not be
        for peer in peers {
            guard let id = peer.deviceIDHint, id != own, peer.host != nil, peer.port != nil else { continue }
            grouped[id, default: []].append(peer)
        }
        return grouped.mapValues { adverts in
            let sorted = adverts.sorted { $0.serviceName < $1.serviceName }
            let first = sorted[0]
            return PeerPresence(
                deviceID: first.deviceIDHint!,
                name: first.deviceNameHint,
                platform: first.platformHint,
                deviceType: first.deviceTypeHint,
                protocolVersion: first.protocolVersionHint,
                endpoints: sorted.map { PeerEndpoint(serviceName: $0.serviceName, host: $0.host!, port: $0.port!) }
            )
        }
    }
}

// MARK: - Registry (trust + presence)

enum TrustEvaluation: Equatable {
    case trusted
    case unknown
    /// Known deviceId presenting a different identity key: always rejected, never rebound.
    case identityMismatch
}

final class DeviceRegistry {
    private let trust: MacTrustRegistry
    private(set) var presence: [String: PeerPresence] = [:]

    init(trust: MacTrustRegistry) {
        self.trust = trust
    }

    var trustedDeviceIDs: Set<String> { trust.deviceIDs }
    var devices: [MacTrustedDevice] { trust.devices }
    var presentDeviceIDs: [String] { Array(presence.keys) }

    func device(_ deviceID: String) -> MacTrustedDevice? { trust.device(deviceID) }
    func identityKey(for deviceID: String) -> String? { trust.identityKey(for: deviceID) }

    func evaluate(deviceID: String, identityKey: String) -> TrustEvaluation {
        guard let pinned = trust.identityKey(for: deviceID) else { return .unknown }
        return pinned == identityKey ? .trusted : .identityMismatch
    }

    /// Creates or refreshes a pairwise trust record. Never rebinds a deviceId to another key.
    @discardableResult
    func remember(deviceID: String, name: String, identityKey: String) -> Bool {
        if evaluate(deviceID: deviceID, identityKey: identityKey) == .identityMismatch { return false }
        return trust.remember(deviceID: deviceID, name: name, identityKey: identityKey)
    }

    /// Records an authenticated connection: lastSeen plus the discovery hints for that deviceId.
    /// Hints are descriptive only and can never change the identity of the record.
    @discardableResult
    func recordConnection(deviceID: String, name: String, at date: Date) -> Bool {
        guard trust.identityKey(for: deviceID) != nil else { return false }
        let hints = presence[deviceID]
        return trust.updateMetadata(
            deviceID: deviceID,
            name: name,
            platform: hints?.platform,
            deviceType: hints?.deviceType,
            protocolVersion: hints?.protocolVersion,
            lastSeen: date
        )
    }

    func forget(_ deviceID: String) { trust.remove(deviceID: deviceID) }

    func updatePresence(_ presence: [String: PeerPresence]) { self.presence = presence }

    func endpoints(for deviceID: String) -> [PeerEndpoint] { presence[deviceID]?.endpoints ?? [] }
}

// MARK: - Sessions

enum PeerSessionPhase: Equatable {
    case connecting
    case verifying
    case connected
}

enum PeerConnectionState: Equatable {
    case offline
    case connecting
    case verifying
    case connected
}

enum PeerIdentifyResult: Equatable {
    case identified
    /// Another session already owns this deviceId; the new one must be closed.
    case rejectedDuplicate
    /// The socket claims to be this device.
    case rejectedSelf
    /// The session was already identified as a different device.
    case rejectedIdentityChange
    /// Not a session this manager knows (already removed).
    case rejectedUnknown
}

/// Owns which session belongs to which device. One session per deviceId; any number of devices.
/// `Session` is the platform's transport-owning session object; this class never touches I/O.
final class PeerSessionManager<Session: AnyObject> {
    private struct Entry {
        let session: Session
        let initiatedLocally: Bool
        var phase: PeerSessionPhase
        var capabilities: [String: Bool]?
        var connectionOrder: Int?
    }

    private struct Pending {
        let session: Session
        let initiatedLocally: Bool
        let expectedDeviceID: String?
    }

    let localDeviceID: String
    private var entries: [String: Entry] = [:]
    private var pending: [Pending] = []
    private var nextConnectionOrder = 0

    init(localDeviceID: String) {
        self.localDeviceID = localDeviceID
    }

    /// A socket that has not yet identified its device (accepted, or dialled before the answer).
    func addPending(_ session: Session, initiatedLocally: Bool, expectedDeviceID: String?) {
        guard !contains(session) else { return }
        pending.append(Pending(session: session, initiatedLocally: initiatedLocally, expectedDeviceID: expectedDeviceID))
    }

    /// Binds a session to the deviceId it announced. Duplicate protection is scoped to that deviceId:
    /// a connected session always wins; while both are still handshaking, the connection initiated
    /// by the lower deviceId wins on both sides, so a simultaneous dial converges instead of
    /// tearing down both. `displaced` is a losing session the caller must close.
    func identify(_ session: Session, as deviceID: String) -> (result: PeerIdentifyResult, displaced: Session?) {
        if let current = self.deviceID(of: session) {
            return (current == deviceID ? .identified : .rejectedIdentityChange, nil)
        }
        guard let index = pending.firstIndex(where: { $0.session === session }) else { return (.rejectedUnknown, nil) }
        guard deviceID != localDeviceID else { return (.rejectedSelf, nil) }
        let candidate = pending[index]
        var displaced: Session?
        if let existing = entries[deviceID] {
            let preferredInitiator = min(localDeviceID, deviceID)
            func initiator(_ initiatedLocally: Bool) -> String { initiatedLocally ? localDeviceID : deviceID }
            guard existing.phase != .connected,
                  initiator(candidate.initiatedLocally) == preferredInitiator,
                  initiator(existing.initiatedLocally) != preferredInitiator else {
                return (.rejectedDuplicate, nil)
            }
            displaced = existing.session
        }
        pending.remove(at: index)
        entries[deviceID] = Entry(session: session, initiatedLocally: candidate.initiatedLocally, phase: .connecting)
        return (.identified, displaced)
    }

    @discardableResult
    func setPhase(_ phase: PeerSessionPhase, for session: Session) -> Bool {
        guard let id = deviceID(of: session) else { return false }
        entries[id]?.phase = phase
        return true
    }

    @discardableResult
    func markConnected(_ session: Session) -> Bool {
        guard let id = deviceID(of: session) else { return false }
        entries[id]?.phase = .connected
        if entries[id]?.connectionOrder == nil {
            entries[id]?.connectionOrder = nextConnectionOrder
            nextConnectionOrder += 1
        }
        return true
    }

    /// Stores the capabilities this peer negotiated (its features.update). Per session, never shared.
    @discardableResult
    func setCapabilities(_ capabilities: [String: Bool], for session: Session) -> Bool {
        guard let id = deviceID(of: session) else { return false }
        entries[id]?.capabilities = capabilities
        return true
    }

    /// Removes exactly this session object. A rejected duplicate never removes the live session
    /// registered for the same deviceId. Returns the device this session was for: its identified
    /// deviceId, or the target of a still-pending dial. Nil if it was already removed.
    @discardableResult
    func remove(_ session: Session) -> String? {
        if let index = pending.firstIndex(where: { $0.session === session }) {
            return pending.remove(at: index).expectedDeviceID
        }
        guard let id = deviceID(of: session) else { return nil }
        entries.removeValue(forKey: id)
        return id
    }

    func contains(_ session: Session) -> Bool {
        pending.contains { $0.session === session } || deviceID(of: session) != nil
    }

    func deviceID(of session: Session) -> String? {
        entries.first { $0.value.session === session }?.key
    }

    func session(for deviceID: String) -> Session? { entries[deviceID]?.session }

    func phase(of session: Session) -> PeerSessionPhase? {
        deviceID(of: session).flatMap { entries[$0]?.phase }
    }

    func capabilities(for deviceID: String) -> [String: Bool]? { entries[deviceID]?.capabilities }

    func state(of deviceID: String) -> PeerConnectionState {
        switch entries[deviceID]?.phase {
        case nil: return .offline
        case .connecting: return .connecting
        case .verifying: return .verifying
        case .connected: return .connected
        }
    }

    /// Connected devices in the order they connected (the routing fallback order).
    var connectedInOrder: [(deviceID: String, order: Int)] {
        entries.compactMap { id, entry in
            entry.phase == .connected ? entry.connectionOrder.map { (id, $0) } : nil
        }.sorted { $0.order < $1.order }
    }

    /// A device with a session, or an outgoing dial still waiting for its answer.
    func isBusy(_ deviceID: String) -> Bool {
        entries[deviceID] != nil || pending.contains { $0.expectedDeviceID == deviceID }
    }

    var verifyingSession: Session? {
        entries.values.first { $0.phase == .verifying }?.session
    }

    var identifiedSessions: [Session] { entries.values.map(\.session) }
    var pendingSessions: [Session] { pending.map(\.session) }
}

// MARK: - Routing compatibility seam

enum DeviceRoutingMode: String {
    /// Several devices may be connected; one is selected for today's single-peer features.
    case singleActive
    /// Several devices may be active. Features will route by deviceId once they are migrated;
    /// until then the legacy single-peer features still read `activePeer`.
    case multipleActive
}

enum DeviceRouting {
    /// The device today's single-peer features use. A pure function of the connected sessions and
    /// the user's preference: it never changes trust, capabilities or connection state.
    /// Preferred device if connected, otherwise the earliest-connected device.
    static func activePeer(
        mode: DeviceRoutingMode,
        preferredDeviceID: String?,
        connected: [(deviceID: String, order: Int)]
    ) -> String? {
        // Both modes select the same legacy peer until features route by deviceId themselves.
        _ = mode
        if let preferredDeviceID, connected.contains(where: { $0.deviceID == preferredDeviceID }) {
            return preferredDeviceID
        }
        return connected.min { $0.order < $1.order }?.deviceID
    }

    /// One-time migration from the single-device era: the only trusted device becomes the
    /// preferred one, so an existing user keeps routing features to the same device.
    static func migratedPreferredDeviceID(stored: String?, migrated: Bool, trustedDeviceIDs: Set<String>) -> String? {
        if let stored { return stored }
        guard !migrated, trustedDeviceIDs.count == 1 else { return nil }
        return trustedDeviceIDs.first
    }
}

// MARK: - Reconnect

enum ReconnectPlanner {
    /// Trusted, discovered devices without a session or dial in flight. Per pair the lower deviceId
    /// dials (the higher one accepts); no platform takes part in the decision.
    static func discoveryDialTargets(
        localDeviceID: String,
        trustedDeviceIDs: Set<String>,
        presence: [String: PeerPresence],
        endpointIndex: (String) -> Int = { _ in 0 },
        isBusy: (String) -> Bool
    ) -> [(deviceID: String, endpoint: PeerEndpoint)] {
        presence.keys.sorted().compactMap { id in
            guard trustedDeviceIDs.contains(id), localDeviceID < id, !isBusy(id),
                  let endpoints = presence[id]?.endpoints, !endpoints.isEmpty else { return nil }
            // Retries rotate through a device's endpoints, so a stale advert cannot pin every attempt.
            return (id, endpoints[endpointIndex(id) % endpoints.count])
        }
    }
}

// MARK: - features.update

enum PeerFeatureState {
    /// The real local feature state for one peer. Every connected session receives this, active or
    /// not: inactivity is routing, never a capability.
    static func payload(for deviceID: String, isEnabled: (BridgeyFeature, String) -> Bool) -> [String: Bool] {
        Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map { ($0.rawValue, isEnabled($0, deviceID)) })
    }
}
