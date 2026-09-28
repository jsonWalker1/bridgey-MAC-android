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

func remoteNotificationIconFileName(packageName: String, data: Data) -> String {
    var input = Data(packageName.utf8)
    input.append(0)
    input.append(data)
    let digest = SHA256.hash(data: input).map { String(format: "%02x", $0) }.joined()
    return "\(digest).png"
}
