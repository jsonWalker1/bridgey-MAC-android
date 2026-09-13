import AppKit
import CryptoKit
import Foundation

// macOS -> Android half of Media Continuity. Receives whatever Android considers its "primary"
// active MediaSession (Spotify/YouTube/etc.) via media.remote.state, and sends play/pause/toggle/
// next/previous/seek commands back via media.remote.action. This is the counterpart to the
// existing Mac -> Android direction (MediaController.swift), kept as a separate dedicated message
// family so the two directions never collide on the same wire vocabulary or feature semantics.

struct MediaRemoteStatePayload: Codable {
    let version: Int
    let generation: Int64
    let sequence: Int64
    let hasSession: Bool
    var packageName: String? = nil
    var appLabel: String? = nil
    var title: String? = nil
    var artist: String? = nil
    var album: String? = nil
    var playing: Bool = false
    var position: Int64 = 0
    var duration: Int64 = 0
    var playbackSpeed: Double = 1
    var positionTimestamp: Int64 = 0
    var artworkHash: String? = nil
    var artwork: String? = nil
    var capabilities: [String] = []
    var volume: Int? = nil
}

struct MediaRemoteActionPayload: Codable {
    let version: Int
    let requestId: String
    let action: String
    var value: Int64? = nil
    let generation: Int64
}

struct MediaRemoteActionAckPayload: Codable {
    let version: Int
    let requestId: String
    let accepted: Bool
    var reason: String? = nil
}

/// Newer-state-wins guard for media.remote.state: a state is only applied if its (generation,
/// sequence) is strictly greater than the last accepted one. `reset()` (called on disconnect)
/// clears this so the very next state after a reconnect - whatever its numbers are, since Android's
/// own counters do not reset on reconnect - is always accepted rather than compared against a
/// generation/sequence pair from before the connection restarted.
struct MediaRemoteOrderingGuard {
    private var lastAccepted: (generation: Int64, sequence: Int64)?

    mutating func accept(generation: Int64, sequence: Int64) -> Bool {
        if let last = lastAccepted,
           generation < last.generation || (generation == last.generation && sequence <= last.sequence) {
            return false
        }
        lastAccepted = (generation, sequence)
        return true
    }

    mutating func reset() { lastAccepted = nil }
}

/// Small bounded in-memory cache so re-displaying the same track doesn't re-decode its artwork.
/// Not persisted to disk - artwork is only relevant for the lifetime of one connection/app run.
@MainActor
final class MediaRemoteArtworkCache {
    private var images: [String: NSImage] = [:]
    private var order: [String] = []
    private let limit: Int

    init(limit: Int = 12) { self.limit = limit }

    func image(for hash: String) -> NSImage? { images[hash] }

    @discardableResult
    func store(_ data: Data, hash: String) -> NSImage? {
        if let existing = images[hash] { return existing }
        guard let image = NSImage(data: data) else { return nil }
        images[hash] = image
        order.append(hash)
        while order.count > limit { images.removeValue(forKey: order.removeFirst()) }
        return image
    }

    func reset() {
        images.removeAll()
        order.removeAll()
    }
}

/// Display-facing snapshot of Android's currently reported primary media session. Intentionally
/// has no UI attached in this phase - MediaRemotePopoverView (or Boring Notch, or anything else)
/// can observe `state` once it exists.
struct MediaRemoteState {
    var hasSession = false
    var generation: Int64 = 0
    var packageName: String?
    var appLabel: String?
    var title: String?
    var artist: String?
    var album: String?
    var playing = false
    var position: Int64 = 0
    var duration: Int64 = 0
    var playbackSpeed: Double = 1
    var positionTimestamp: Int64 = 0
    var capabilities: [String] = []
    var artwork: NSImage?
    var volume: Int?
}

/// Extrapolates a live position from a state snapshot without any timer/polling on the sender's
/// side - mirrors MediaContinuityManager.kt's extrapolatedPosition. `nowMs` is epoch millis on this
/// Mac; `positionTimestamp` is epoch millis on Android at the moment it sampled `position`, so this
/// assumes the two clocks are reasonably in sync (true for any NTP-synced phone/Mac), which is
/// adequate for a progress bar even with a few hundred ms of skew.
func extrapolatedPosition(_ state: MediaRemoteState, nowMs: Int64) -> Int64 {
    guard state.playing else { return state.position }
    let ageMs = max(0, nowMs - state.positionTimestamp)
    let raw = state.position + Int64(Double(ageMs) * state.playbackSpeed)
    let upperBound = state.duration > 0 ? state.duration : Int64.max
    return min(max(raw, 0), upperBound)
}

private func sha256Hex(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
}

@MainActor
final class MediaRemoteController: ObservableObject {
    @Published private(set) var state = MediaRemoteState()
    @Published private(set) var lastActionRejectedReason: String?
    var allowed: () -> Bool = { false }
    var sendAction: (_ action: String, _ value: Int64?, _ generation: Int64) -> Void = { _, _, _ in }

    private var ordering = MediaRemoteOrderingGuard()
    private let artworkCache = MediaRemoteArtworkCache()

    func receive(_ payload: MediaRemoteStatePayload) {
        guard allowed() else { return }
        guard ordering.accept(generation: payload.generation, sequence: payload.sequence) else {
            NSLog("MEDIA remote state rejected as stale generation=%lld sequence=%lld", payload.generation, payload.sequence)
            return
        }
        var artwork: NSImage?
        if let hash = payload.artworkHash {
            if let encoded = payload.artwork, let data = Data(base64Encoded: encoded), sha256Hex(data) == hash {
                artwork = artworkCache.store(data, hash: hash)
            } else {
                artwork = artworkCache.image(for: hash)
            }
        }
        state = MediaRemoteState(
            hasSession: payload.hasSession,
            generation: payload.generation,
            packageName: payload.packageName,
            appLabel: payload.appLabel,
            title: payload.title,
            artist: payload.artist,
            album: payload.album,
            playing: payload.playing,
            position: payload.position,
            duration: payload.duration,
            playbackSpeed: payload.playbackSpeed,
            positionTimestamp: payload.positionTimestamp,
            capabilities: payload.capabilities,
            artwork: artwork,
            volume: payload.volume
        )
        NSLog(
            "MEDIA remote state received hasSession=%@ package=%@ title=%@ artist=%@ playing=%@ position=%lld duration=%lld generation=%lld sequence=%lld artworkHash=%@ volume=%@",
            String(payload.hasSession), payload.packageName ?? "-", payload.title ?? "-", payload.artist ?? "-",
            String(payload.playing), payload.position, payload.duration, payload.generation, payload.sequence,
            payload.artworkHash ?? "-", payload.volume.map(String.init) ?? "-"
        )
    }

    func command(_ action: String, value: Int64? = nil) {
        guard allowed(), state.hasSession else { return }
        NSLog("MEDIA command sent action=%@ generation=%lld", action, state.generation)
        sendAction(action, value, state.generation)
    }

    func receiveAck(_ payload: MediaRemoteActionAckPayload) {
        NSLog("MEDIA action ack requestId=%@ accepted=%@ reason=%@", payload.requestId, String(payload.accepted), payload.reason ?? "-")
        lastActionRejectedReason = payload.accepted ? nil : (payload.reason ?? "rejected")
    }

    func reset() {
        NSLog("MEDIA remote state cleared (disconnect/reset)")
        ordering.reset()
        artworkCache.reset()
        state = MediaRemoteState()
        lastActionRejectedReason = nil
    }
}
