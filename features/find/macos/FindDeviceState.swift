import Foundation

/// Find Device state per device (MD-3), from this device's point of view:
/// - `remoteRinging`: peers we asked to ring that confirmed they are ringing;
/// - `localRequesters`: peers that asked *this* device to ring. This device rings while at least
///   one requester remains, so one peer stopping or disconnecting never silences another's request.
/// Pure state; the coordinator plays/stops the sound and sends the messages.
struct FindDeviceState: Equatable {
    private(set) var remoteRinging: Set<String> = []
    private(set) var localRequesters: Set<String> = []

    var isRingingLocally: Bool { !localRequesters.isEmpty }

    func isRinging(_ deviceID: String) -> Bool { remoteRinging.contains(deviceID) }

    /// find.started / find.stopped from a peer we asked.
    mutating func remoteReported(_ deviceID: String, ringing: Bool) {
        if ringing { remoteRinging.insert(deviceID) } else { remoteRinging.remove(deviceID) }
    }

    /// find.start from a peer. Returns true when the local sound must start.
    @discardableResult
    mutating func localRingRequested(by deviceID: String) -> Bool {
        let wasRinging = isRingingLocally
        localRequesters.insert(deviceID)
        return !wasRinging
    }

    /// find.stop from a peer, or the local grant for that peer was revoked: the peer no longer
    /// asks this device to ring. Its own ringing is reported separately (find.started / stopped).
    /// Returns true when the local sound must stop.
    @discardableResult
    mutating func peerStopped(_ deviceID: String) -> Bool {
        let wasRinging = isRingingLocally
        localRequesters.remove(deviceID)
        return wasRinging && !isRingingLocally
    }

    /// The user silenced this device. Returns the peers that had asked, to be told it stopped.
    mutating func stopLocalRinging() -> Set<String> {
        defer { localRequesters = [] }
        return localRequesters
    }

    /// The device's session ended. Returns true when the local sound must stop.
    @discardableResult
    mutating func deviceEnded(_ deviceID: String) -> Bool {
        remoteRinging.remove(deviceID)
        let wasRinging = isRingingLocally
        localRequesters.remove(deviceID)
        return wasRinging && !isRingingLocally
    }

    mutating func reset() { self = FindDeviceState() }
}
