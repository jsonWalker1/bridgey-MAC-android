import Foundation

/// Ping requests in flight, each bound to the device it targets (MD-3). A request is identified by
/// (deviceId, requestId): an acknowledgement completes it only when it comes from that device, so
/// one device can never complete, time out or clear another device's request. Pure state; the
/// coordinator sends, schedules timeouts and reports the outcome.
struct PingRequests: Equatable {
    enum Status: Equatable {
        case pinging
        case delivered
        case notAcknowledged
        case notSent
    }

    private struct Key: Hashable {
        let deviceID: String
        let requestID: String
    }

    private var pending: Set<Key> = []
    /// The latest outcome per device.
    private(set) var statuses: [String: Status] = [:]

    func isPending(deviceID: String, requestID: String) -> Bool {
        pending.contains(Key(deviceID: deviceID, requestID: requestID))
    }

    func pendingCount(for deviceID: String) -> Int {
        pending.filter { $0.deviceID == deviceID }.count
    }

    mutating func begin(deviceID: String, requestID: String) {
        pending.insert(Key(deviceID: deviceID, requestID: requestID))
        statuses[deviceID] = .pinging
    }

    /// True only for a pending request of exactly this device.
    @discardableResult
    mutating func acknowledge(requestID: String, from deviceID: String) -> Bool {
        guard pending.remove(Key(deviceID: deviceID, requestID: requestID)) != nil else { return false }
        statuses[deviceID] = .delivered
        return true
    }

    /// The request could not be written to that device's session.
    mutating func failed(deviceID: String, requestID: String) {
        guard pending.remove(Key(deviceID: deviceID, requestID: requestID)) != nil else { return }
        statuses[deviceID] = .notSent
    }

    /// True if the request was still pending (and is now marked unacknowledged).
    @discardableResult
    mutating func timeOut(deviceID: String, requestID: String) -> Bool {
        guard pending.remove(Key(deviceID: deviceID, requestID: requestID)) != nil else { return false }
        statuses[deviceID] = .notAcknowledged
        return true
    }

    /// The device's session ended: its requests can never complete. Other devices are untouched.
    /// Returns true if a request to that device was still pending.
    @discardableResult
    mutating func deviceEnded(_ deviceID: String) -> Bool {
        let hadPending = pending.contains { $0.deviceID == deviceID }
        pending = pending.filter { $0.deviceID != deviceID }
        statuses[deviceID] = nil
        return hadPending
    }

    mutating func reset() { self = PingRequests() }
}
