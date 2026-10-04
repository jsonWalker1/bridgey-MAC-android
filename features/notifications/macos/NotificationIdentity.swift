import CryptoKit
import Foundation

func remoteNotificationRequestIdentifier(deviceID: String, notificationID: String) -> String {
    let digest = SHA256.hash(data: Data("\(deviceID)\u{0}\(notificationID)".utf8))
        .map { String(format: "%02x", $0) }
        .joined()
    return "bridgey.android.\(digest)"
}

func remoteNotificationCategoryIdentifier(deviceID: String, notificationID: String, actionTokens: [String]) -> String {
    let value = ([deviceID, notificationID] + actionTokens).joined(separator: "\u{0}")
    let digest = SHA256.hash(data: Data(value.utf8))
        .map { String(format: "%02x", $0) }
        .joined()
    return "bridgey.android.actions.\(digest)"
}

/**
 * BRIDGEY NOTIFICATION++ SOUND POLISH: `UNUserNotificationCenter.add()` re-alerts (sound + banner)
 * every time it's called with the same identifier, even when the call is just a resync-on-reconnect
 * or a duplicate/replayed post of content the user has already seen - real-device evidence showed
 * WhatsApp rapid-fire the same message post 3x within the same second, and Bridgey's own
 * resyncActiveNotifications() re-posts every active notification on every reconnect. Comparing
 * against the highest Android `postTime` (`timestamp`) already delivered for this logical identity
 * - reusing the existing timestamp field already on the wire, no new field needed for this part -
 * distinguishes "genuinely new content" (timestamp advanced) from "the same content again"
 * (timestamp unchanged), so replays/resyncs stay silent while a real new message still sounds.
 */
func shouldPlayNotificationSound(hasSound: Bool?, timestamp: Int64, lastPlayedTimestamp: Int64?) -> Bool {
    guard hasSound ?? true else { return false }
    guard let lastPlayedTimestamp else { return true }
    return timestamp > lastPlayedTimestamp
}

let maximumRemoteNotificationIconBytes = 20 * 1024

func remoteNotificationIconData(_ encoded: String?) -> Data? {
    guard let encoded,
          encoded.count <= 28 * 1024,
          let data = Data(base64Encoded: encoded),
          !data.isEmpty,
          data.count <= maximumRemoteNotificationIconBytes,
          data.starts(with: [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]) else { return nil }
    return data
}

func remoteNotificationIconFileName(packageName: String, data: Data, uniqueSuffix: String? = nil) -> String {
    var input = Data(packageName.utf8)
    input.append(0)
    input.append(data)
    let digest = SHA256.hash(data: input).map { String(format: "%02x", $0) }.joined()
    guard let uniqueSuffix else { return "\(digest).png" }
    return "\(digest)-\(uniqueSuffix).png"
}

// MARK: - BRIDGEY NOTIFICATION++ RECONCILIATION (`notifications.sync`, docs/protocol.md)

let maximumNotificationSyncIDsPerPart = 256
let maximumNotificationSyncParts = 32

/// Android's authoritative snapshot (possibly one of several parts) of every notification ID it
/// currently considers eligible for forwarding.
struct NotificationSyncPayload: Codable, Equatable {
    let version: Int
    let syncId: String
    let part: Int
    let parts: Int
    let notificationIds: [String]
}

/// Structural validation. A payload failing this is an invalid message (existing handling).
func isValidNotificationSyncPayload(_ payload: NotificationSyncPayload) -> Bool {
    guard payload.version == 1,
          !payload.syncId.isEmpty, payload.syncId.count <= 64,
          payload.parts >= 1, payload.parts <= maximumNotificationSyncParts,
          payload.part >= 1, payload.part <= payload.parts,
          payload.notificationIds.count <= maximumNotificationSyncIDsPerPart else { return false }
    return payload.notificationIds.allSatisfy(isValidAndroidNotificationID)
}

/// Android notification IDs are SHA-256 hex digests (`notificationToken` on Android).
func isValidAndroidNotificationID(_ id: String) -> Bool {
    id.count == 64 && id.utf8.allSatisfy { (0x30...0x39).contains($0) || (0x61...0x66).contains($0) }
}

/// Collects the parts of one `syncId` per Android device and yields the complete snapshot only once
/// every part has arrived. Only the newest `syncId` per device is kept: a newer sync supersedes an
/// older incomplete one, which is then simply never applied (an incomplete sync deletes nothing).
final class NotificationSyncAssembler {
    private struct Pending {
        let syncId: String
        let parts: Int
        var received: [Int: [String]]
    }
    private var pendingByDevice: [String: Pending] = [:]

    /// Returns the full snapshot when [payload] completes its sync, otherwise `nil`.
    func add(_ payload: NotificationSyncPayload, deviceID: String) -> Set<String>? {
        var pending = pendingByDevice[deviceID]
        if pending == nil || pending?.syncId != payload.syncId || pending?.parts != payload.parts {
            pending = Pending(syncId: payload.syncId, parts: payload.parts, received: [:])
        }
        pending!.received[payload.part] = payload.notificationIds
        guard pending!.received.count == pending!.parts else {
            pendingByDevice[deviceID] = pending
            return nil
        }
        pendingByDevice[deviceID] = nil
        return Set(pending!.received.values.joined())
    }

    func reset() { pendingByDevice.removeAll() }
}

/// The facts reconciliation needs from one delivered macOS notification (from its `userInfo`).
struct DeliveredRemoteNotification: Equatable {
    let requestIdentifier: String
    let androidDeviceID: String?
    let androidNotificationID: String?
}

/// Request identifiers of delivered notifications that belong to [deviceID] but whose Android
/// notification is absent from [snapshot]. Other devices' and non-Bridgey notifications are never
/// returned. Pure, so applying the same snapshot twice yields nothing the second time.
func staleRemoteNotificationIdentifiers(
    delivered: [DeliveredRemoteNotification],
    deviceID: String,
    snapshot: Set<String>
) -> [String] {
    delivered.compactMap { item in
        guard item.androidDeviceID == deviceID,
              let notificationID = item.androidNotificationID,
              !snapshot.contains(notificationID) else { return nil }
        return item.requestIdentifier
    }
}
