import Foundation

/// Clipboard sends in flight, each bound to the device it targets (MD-5). A send is the pair
/// (deviceId, messageId): its clipboard.ack / clipboard.rejected completes it only when it comes
/// from that device, so one peer can never complete, time out or clear another peer's send.
/// Pure state; the coordinator sends, schedules timeouts and runs completions.
struct ClipboardSends: Equatable {
    enum Status: Equatable {
        case sending
        case delivered
        case rejected
        case notAcknowledged
        case notSent
        case disconnected
    }

    struct Key: Hashable {
        let deviceID: String
        let messageID: String
    }

    private(set) var pending: Set<Key> = []
    /// The latest outcome per device.
    private(set) var statuses: [String: Status] = [:]

    func isPending(deviceID: String, messageID: String) -> Bool {
        pending.contains(Key(deviceID: deviceID, messageID: messageID))
    }

    mutating func begin(deviceID: String, messageID: String) {
        pending.insert(Key(deviceID: deviceID, messageID: messageID))
        statuses[deviceID] = .sending
    }

    /// clipboard.ack from `deviceID`. True only for a pending send to exactly that device.
    @discardableResult
    mutating func acknowledge(messageID: String, from deviceID: String) -> Bool {
        finish(Key(deviceID: deviceID, messageID: messageID), as: .delivered)
    }

    /// clipboard.rejected from `deviceID` (its clipboard is off for us).
    @discardableResult
    mutating func reject(messageID: String, from deviceID: String) -> Bool {
        finish(Key(deviceID: deviceID, messageID: messageID), as: .rejected)
    }

    @discardableResult
    mutating func timeOut(deviceID: String, messageID: String) -> Bool {
        finish(Key(deviceID: deviceID, messageID: messageID), as: .notAcknowledged)
    }

    @discardableResult
    mutating func failed(deviceID: String, messageID: String) -> Bool {
        finish(Key(deviceID: deviceID, messageID: messageID), as: .notSent)
    }

    /// The device's session ended: its sends can never complete. Returns their message ids so the
    /// caller can fail their completions. Other devices are untouched.
    mutating func deviceEnded(_ deviceID: String) -> [String] {
        let ended = pending.filter { $0.deviceID == deviceID }
        pending.subtract(ended)
        if !ended.isEmpty { statuses[deviceID] = .disconnected } else { statuses[deviceID] = nil }
        return ended.map(\.messageID).sorted()
    }

    private mutating func finish(_ key: Key, as status: Status) -> Bool {
        guard pending.remove(key) != nil else { return false }
        statuses[key.deviceID] = status
        return true
    }
}

/// What a receiver does with an incoming clipboard message, decided by its session's replay
/// window. A retransmission (the sender retried before our ack arrived) is not corruption: it is
/// acknowledged again and not applied a second time.
enum ClipboardReceiveAction: Equatable {
    case apply
    case acknowledgeAgain

    static func forMessage(isNewMessageID: Bool) -> ClipboardReceiveAction {
        isNewMessageID ? .apply : .acknowledgeAgain
    }

    /// Whether clipboard from `sender` may be applied here: the same direction rule that decides
    /// whether it is offered (no Mac -> Mac). A sender whose platform is unknown (recorded before
    /// platform hints) keeps being accepted, so existing pairings are not cut off.
    static func acceptsSender(_ sender: DeviceProfile, receiver: DeviceProfile) -> Bool {
        sender.platform == .unknown || FeatureApplicability.isApplicable(.clipboard, source: sender, target: receiver)
    }
}

/// The device a target-less clipboard entry point (keyboard shortcut, tile, notification action)
/// sends to: the only eligible peer, or none - never a routed or "main" peer.
enum ClipboardTarget {
    static func forTargetlessSend(eligible: [String]) -> String? {
        eligible.count == 1 ? eligible[0] : nil
    }
}
