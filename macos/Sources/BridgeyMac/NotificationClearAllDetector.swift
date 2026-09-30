import Foundation

// BRIDGEY NOTIFICATION++ macOS CLEAR ALL
//
// macOS sends no callback when the user clears the whole Bridgey stack in Notification Center
// (measured 2026-09-30: a single dismiss reports UNNotificationDismissActionIdentifier, a stack
// clear reports nothing). The action is inferred from observable state only: the delivered Bridgey
// notifications going from N > 0 to 0, confirmed on two consecutive heartbeat checks, outside a
// settling window, and not explained by removals Bridgey made itself or by per-notification
// dismiss callbacks. macOS's silent eviction beyond 100 notifications per app never empties the
// list, so it never looks like a clear. See docs/protocol.md, `notifications.dismissMany`.

/// One delivered Bridgey notification as tracked for Clear All (keyed by UN request identifier).
struct ClearAllSnapshotEntry: Equatable, Comparable {
    let deviceID: String
    let notificationID: String

    static func < (lhs: ClearAllSnapshotEntry, rhs: ClearAllSnapshotEntry) -> Bool {
        (lhs.deviceID, lhs.notificationID) < (rhs.deviceID, rhs.notificationID)
    }
}

struct NotificationClearAllDetector {
    enum Action: Equatable {
        /// Evaluation disabled (not connected, forwarding off, or notifications not authorized).
        case inactive
        /// New session / post / permission change still settling: the snapshot only grows.
        case settling
        /// Normal observation: snapshot replaced by the current delivered set.
        case update
        /// First observation of N > 0 -> 0; a second consecutive zero is required to act.
        case zeroPending
        /// Everything that vanished is explained by Bridgey's own removals or dismiss callbacks.
        case explained
        /// Inferred user Clear All: dismiss these on Android.
        case dismissMany([ClearAllSnapshotEntry])
    }

    /// No evaluation within this long after a new session, a post or a permission change:
    /// getDeliveredNotifications was measured to lag up to ~25 s behind add(), and replaced
    /// notifications briefly vanish from it.
    static let settlingInterval: TimeInterval = 60
    /// How long a Bridgey-initiated removal or a dismiss callback keeps explaining a vanished id.
    static let explanationLifetime: TimeInterval = 120

    private(set) var snapshot: [String: ClearAllSnapshotEntry] = [:]
    private var zeroPending = false
    private var explainedAt: [String: Date] = [:]

    mutating func reset() {
        snapshot = [:]
        zeroPending = false
        explainedAt = [:]
    }

    /// Bridgey itself removed these request identifiers, or the user dismissed them one by one
    /// (already forwarded to Android as `notifications.dismiss`).
    mutating func noteExplainedRemoval(_ requestIdentifiers: [String], at now: Date) {
        requestIdentifiers.forEach { explainedAt[$0] = now }
    }

    /// - Parameters:
    ///   - active: connected, forwarding available on both peers, notifications authorized with
    ///     Notification Center enabled. Inactive clears all state.
    ///   - settling: within [settlingInterval] of a new session, post or permission change.
    mutating func observe(current: [String: ClearAllSnapshotEntry], active: Bool, settling: Bool, now: Date) -> Action {
        guard active else {
            reset()
            return .inactive
        }
        explainedAt = explainedAt.filter { now.timeIntervalSince($0.value) < Self.explanationLifetime }
        let unexplainedPrevious = snapshot.filter { explainedAt[$0.key] == nil }

        if settling {
            zeroPending = false
            snapshot = unexplainedPrevious.merging(current) { _, new in new }
            return .settling
        }
        if current.isEmpty && !snapshot.isEmpty {
            if unexplainedPrevious.isEmpty {
                snapshot = [:]
                zeroPending = false
                return .explained
            }
            guard zeroPending else {
                zeroPending = true
                return .zeroPending
            }
            snapshot = [:]
            zeroPending = false
            return .dismissMany(unexplainedPrevious.values.sorted())
        }
        zeroPending = false
        snapshot = current
        return .update
    }
}

/// `notifications.dismissMany` (docs/protocol.md): at most this many IDs per message.
let maximumNotificationDismissManyIDs = 256

/// Splits a bulk dismissal into independent messages of at most [maximumNotificationDismissManyIDs].
func notificationDismissManyParts(_ notificationIDs: [String]) -> [[String]] {
    stride(from: 0, to: notificationIDs.count, by: maximumNotificationDismissManyIDs).map {
        Array(notificationIDs[$0..<min($0 + maximumNotificationDismissManyIDs, notificationIDs.count)])
    }
}
