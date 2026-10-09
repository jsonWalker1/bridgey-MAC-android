import Foundation

// PER-PEER FILE TRANSFERS (MD-6)
//
// files.v1 is unchanged on the wire. What changes is ownership: every transfer belongs to the
// peer it was started with (outgoing) or the authenticated peer that offered it (incoming), and is
// identified by (deviceId, transferId). A transferId alone is chosen by the sender and only unique
// in practice, so it never correlates a message from one peer with another peer's transfer.
// Selection and routing are read once, when a transfer starts, and never again.

/// A transfer's identity: the peer it belongs to plus the sender-chosen transfer id.
struct FileTransferKey: Hashable {
    let deviceID: String
    let transferID: String

    /// Stable id for a transfer row (the UI's cancel/retry handle).
    var rowID: String { "\(deviceID)|\(transferID)" }

    init(deviceID: String, transferID: String) {
        self.deviceID = deviceID
        self.transferID = transferID
    }

    /// Splits at the last "|": transfer ids are UUIDs, a peer's deviceId is not validated.
    init?(rowID: String) {
        guard let separator = rowID.lastIndex(of: "|") else { return nil }
        let deviceID = String(rowID[..<separator])
        let transferID = String(rowID[rowID.index(after: separator)...])
        guard !deviceID.isEmpty, !transferID.isEmpty else { return nil }
        self.init(deviceID: deviceID, transferID: transferID)
    }
}

/// Transfers of one kind (incoming or outgoing), keyed per peer. Pure container: a lookup with
/// another peer's key finds nothing, and ending one peer leaves every other peer's entries.
struct FileTransferTable<Value> {
    private(set) var entries: [FileTransferKey: Value] = [:]

    var isEmpty: Bool { entries.isEmpty }
    var keys: [FileTransferKey] { Array(entries.keys) }
    var values: [Value] { Array(entries.values) }

    subscript(key: FileTransferKey) -> Value? { entries[key] }

    func contains(_ key: FileTransferKey) -> Bool { entries[key] != nil }

    /// False (and unchanged) when this peer already has a transfer with that id.
    @discardableResult
    mutating func insert(_ value: Value, for key: FileTransferKey) -> Bool {
        guard entries[key] == nil else { return false }
        entries[key] = value
        return true
    }

    @discardableResult
    mutating func remove(_ key: FileTransferKey) -> Value? {
        entries.removeValue(forKey: key)
    }

    /// The peer's session ended: removes and returns only its transfers.
    mutating func removeAll(deviceID: String) -> [(key: FileTransferKey, value: Value)] {
        let ended = entries.filter { $0.key.deviceID == deviceID }
        ended.keys.forEach { entries.removeValue(forKey: $0) }
        return ended.map { (key: $0.key, value: $0.value) }
    }

    mutating func removeAll() -> [(key: FileTransferKey, value: Value)] {
        let all = entries.map { (key: $0.key, value: $0.value) }
        entries.removeAll()
        return all
    }
}

/// Recently cancelled transfers, per peer, so late chunks/completions/acks of a cancelled transfer
/// are ignored instead of treated as a protocol error. Bounded; the oldest entries are dropped.
struct CancelledFileTransfers {
    static let capacity = 64
    private(set) var order: [FileTransferKey] = []

    func contains(_ key: FileTransferKey) -> Bool { order.contains(key) }

    mutating func insert(_ key: FileTransferKey) {
        guard !order.contains(key) else { return }
        order.append(key)
        if order.count > Self.capacity { order.removeFirst(order.count - Self.capacity) }
    }
}

enum FileTransferPolicy {
    /// Mac <-> Mac transfers need the local opt-in (off by default); every other pairing follows the
    /// normal applicability and authorization rules. Enforced for offering AND accepting.
    static func allows(local: DevicePlatform, peer: DevicePlatform, macToMacEnabled: Bool) -> Bool {
        !(local == .macos && peer == .macos) || macToMacEnabled
    }
}

enum FileTransferTarget {
    /// The peer a send goes to, resolved once when the operation starts: the selected peer when it
    /// is eligible, otherwise the only eligible peer, otherwise none (the user must choose). Never
    /// the routed peer by default and never a fallback.
    static func initial(selected: String?, eligible: [String]) -> String? {
        if let selected, eligible.contains(selected) { return selected }
        return eligible.count == 1 ? eligible[0] : nil
    }
}

/// files.chunk.ack is cumulative: "every chunk up to `sequence` arrived". The sender only moves its
/// acknowledged position forward, and only to a chunk it actually sent, so duplicate, reordered,
/// late or bogus acks cannot advance flow control past the data.
enum FileChunkAcknowledgement {
    static func advance(current: Int64, acknowledged: Int64, highestSent: Int64) -> Int64 {
        guard acknowledged >= 0, acknowledged <= highestSent else { return current }
        return max(current, acknowledged)
    }
}

/// Transfer status text that always names the peer.
enum FileTransferText {
    static func waiting(for peer: String) -> String { "Waiting for \(peer)…" }
    static func sending(_ file: String, to peer: String) -> String { "Sending \(file) → \(peer)…" }
    static func sending(_ file: String, to peer: String, progress: String) -> String { "Sending \(file) → \(peer): \(progress)" }
    static func verifying(_ file: String, on peer: String) -> String { "Verifying \(file) on \(peer)…" }
    static func saved(_ file: String, on peer: String) -> String { "\(file) saved on \(peer)" }
    static func receiving(_ file: String, from peer: String, progress: String) -> String { "Receiving \(file) ← \(peer): \(progress)" }
    static func received(_ file: String, from peer: String, folder: String) -> String { "Saved \(file) from \(peer) to \(folder)" }
    static func turnedOff(on peer: String) -> String { "File transfer is turned off on \(peer)" }
    static func cancelled(by peer: String) -> String { "Transfer cancelled by \(peer)" }
    static func notConnected(_ peer: String) -> String { "\(peer) is not connected — file was not sent" }
    static func reconnectToRetry(_ peer: String) -> String { "Reconnect \(peer) to retry" }
    static let chooseDevice = "Choose a device in the Bridgey panel to send files"
}
