import AppKit
import Combine
import Foundation
import IOKit.hid
import MediaPlayer

/// Precedence when both sides have something loaded: whichever is actually playing wins, Mac-native
/// breaking ties (it's what's audibly coming out of this Mac); otherwise whichever has *any* loaded
/// session, Mac-native first. Deliberately dumb and stated up front, not "smart" auto-switching.
///
/// A free function rather than a method so the precedence rule is testable without constructing
/// real MediaController/MediaRemoteController instances.
func preferMacNativeMediaSource(macPlaying: Bool, macHasSession: Bool, androidPlaying: Bool, androidHasSession: Bool) -> Bool {
    if macPlaying { return true }
    if androidPlaying { return false }
    if macHasSession { return true }
    if androidHasSession { return false }
    return true
}

/// Real hardware media keys (a keyboard's dedicated Play/Pause/Next/Previous, or the Touch Bar
/// equivalents) arrive as NSEvent.systemDefined events with subtype 8 (NX_SUBTYPE_AUX_CONTROL_BUTTONS)
/// - not as MPRemoteCommandCenter commands. MPRemoteCommandCenter/MPNowPlayingInfoCenter is macOS's
/// "Now Playing" *election* system: the OS routes hardware keys to whichever app it currently
/// considers Now Playing, and a menu-bar-only (LSUIElement) app with no other media app around was
/// never reliably elected - confirmed on real hardware: it only worked when Music.app/Spotify was
/// also running, i.e. Bridgey was piggybacking on *their* eligibility, not receiving keys in its own
/// right. Reading the raw systemDefined event sidesteps that election entirely: it is delivered to
/// every process with a global monitor active, regardless of what else is running.
///
/// This does require the user to grant Input Monitoring (Privacy & Security > Input Monitoring) -
/// a real, new permission, not a private API or workaround. There is no lighter-weight standard
/// mechanism that actually satisfies "works with zero Mac-native media app present"; this project's
/// own `docs/architecture.md` should note the trade-off. `MPNowPlayingInfoCenter.nowPlayingInfo` is
/// still published (see `updateNowPlayingInfo`) purely so Control Center's Now Playing widget shows
/// something - no commands are registered against it, so it cannot double-dispatch alongside the
/// event tap below.
private let mediaKeySystemDefinedSubtype: Int16 = 8
private let mediaKeyCodePlayPause: Int32 = 16
private let mediaKeyCodeNext: Int32 = 17
private let mediaKeyCodePrevious: Int32 = 18
private let mediaKeyCodeFastForward: Int32 = 19
private let mediaKeyCodeRewind: Int32 = 20
private let mediaKeyStateDown: Int = 0x0A

@MainActor
final class GlobalMediaCommandCenter {
    private let mediaRemote: MediaRemoteController
    private let mediaController: MediaController
    private var cancellables: Set<AnyCancellable> = []
    private var globalMonitor: Any?
    private var lastHandledKeyCode: Int32?
    private var lastHandledAt: Date?

    init(mediaRemote: MediaRemoteController, mediaController: MediaController) {
        self.mediaRemote = mediaRemote
        self.mediaController = mediaController
        startHardwareKeyTap()
        mediaRemote.$state
            .combineLatest(mediaController.$snapshot)
            .sink { [weak self] _, _ in self?.updateNowPlayingInfo() }
            .store(in: &cancellables)
        updateNowPlayingInfo()
    }

    deinit {
        if let globalMonitor { NSEvent.removeMonitor(globalMonitor) }
    }

    /// Whether macOS currently grants the Input Monitoring access the hardware-key tap needs.
    static var hardwareKeyAccessGranted: Bool {
        IOHIDCheckAccess(kIOHIDRequestTypeListenEvent) == kIOHIDAccessTypeGranted
    }

    /// Triggers the system consent prompt (only has an effect the first time; once denied, the user
    /// must flip it on in System Settings themselves - `openInputMonitoringSettings()` gets them there).
    static func requestHardwareKeyAccess() {
        IOHIDRequestAccess(kIOHIDRequestTypeListenEvent)
    }

    static func openInputMonitoringSettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ListenEvent") else { return }
        NSWorkspace.shared.open(url)
    }

    /// Global-monitor-only, deliberately: an earlier version also registered a *local* monitor
    /// (for the case where Bridgey's own popover happened to be key), but systemDefined media-key
    /// events are broadcast rather than routed to a single "key" window the way normal keystrokes
    /// are - with the popover open, both monitors fired for the same physical key press, causing a
    /// double toggle (start, then immediately pause again) and a double skipToNext (which some
    /// players, including YouTube, surface as a transient "can't play this content" error rather
    /// than silently landing two tracks over). The global monitor alone reliably receives these
    /// events regardless of what's frontmost, so the local monitor was redundant, not additive.
    private func startHardwareKeyTap() {
        globalMonitor = NSEvent.addGlobalMonitorForEvents(matching: .systemDefined) { [weak self] event in
            self?.handle(event)
        }
    }

    func handle(_ event: NSEvent) {
        guard event.subtype.rawValue == mediaKeySystemDefinedSubtype else { return }
        let data1 = event.data1
        let keyCode = Int32((data1 & 0xFFFF_0000) >> 16)
        let keyState = (data1 & 0x0000_FF00) >> 8
        guard keyState == mediaKeyStateDown else { return }
        // Belt-and-suspenders against any other source of duplicate delivery (some keyboard/macOS
        // combinations are known to occasionally redeliver the same physical press): the same key
        // code within 250ms is treated as one press, not two.
        let now = Date()
        if let lastHandledKeyCode, let lastHandledAt, lastHandledKeyCode == keyCode, now.timeIntervalSince(lastHandledAt) < 0.25 {
            return
        }
        lastHandledKeyCode = keyCode
        lastHandledAt = now
        switch keyCode {
        case mediaKeyCodePlayPause: dispatch("toggle")
        case mediaKeyCodeNext: dispatch("next")
        case mediaKeyCodePrevious: dispatch("previous")
        case mediaKeyCodeFastForward: dispatchSeek(forwardBy: 15)
        case mediaKeyCodeRewind: dispatchSeek(forwardBy: -15)
        default: break
        }
    }

    /// True when Mac-native playback should receive the command. Relies on an existing invariant
    /// rather than re-checking feature flags here: both `mediaRemote.state` and
    /// `mediaController.snapshot` already reset to empty/no-session whenever Media is turned off or
    /// disconnected (see MediaRemoteController.reset()/MediaController.reset()), so the emptiness
    /// checks below already make every command a safe no-op in that case.
    private var preferMacNative: Bool {
        preferMacNativeMediaSource(
            macPlaying: mediaController.snapshot.playing,
            macHasSession: mediaController.player != .off && !mediaController.snapshot.title.isEmpty,
            androidPlaying: mediaRemote.state.playing,
            androidHasSession: mediaRemote.state.hasSession
        )
    }

    func dispatch(_ action: String) {
        if preferMacNative {
            guard mediaController.player != .off, !mediaController.snapshot.title.isEmpty else { return }
            mediaController.command(action: action, value: "", completion: { _ in })
        } else {
            guard mediaRemote.state.hasSession else { return }
            mediaRemote.command(action)
        }
    }

    func dispatchSeek(forwardBy seconds: Int) {
        if preferMacNative {
            guard mediaController.player != .off, !mediaController.snapshot.title.isEmpty else { return }
            let target = max(0, mediaController.snapshot.position + seconds)
            mediaController.command(action: "seek", value: String(target), completion: { _ in })
            return
        }
        guard mediaRemote.state.hasSession, mediaRemote.state.capabilities.contains("seek") else { return }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let currentMs = extrapolatedPosition(mediaRemote.state, nowMs: nowMs)
        let targetMs = max(0, currentMs + Int64(seconds) * 1000)
        mediaRemote.command("seek", value: targetMs)
    }

    /// Publishes to Control Center's Now Playing widget for visual display only - no
    /// MPRemoteCommandCenter targets are registered, so this can never double-dispatch alongside
    /// the hardware-key tap above.
    private func updateNowPlayingInfo() {
        var info: [String: Any] = [:]
        var playing = false
        if preferMacNative, mediaController.player != .off, !mediaController.snapshot.title.isEmpty {
            let snapshot = mediaController.snapshot
            playing = snapshot.playing
            info[MPMediaItemPropertyTitle] = snapshot.title
            info[MPMediaItemPropertyArtist] = snapshot.artist
            info[MPMediaItemPropertyPlaybackDuration] = Double(snapshot.duration)
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(snapshot.position)
            info[MPNowPlayingInfoPropertyPlaybackRate] = snapshot.playing ? 1.0 : 0.0
            if let data = snapshot.artwork.flatMap({ Data(base64Encoded: $0) }), let image = NSImage(data: data) {
                info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            }
        } else if mediaRemote.state.hasSession {
            let snapshot = mediaRemote.state
            playing = snapshot.playing
            info[MPMediaItemPropertyTitle] = snapshot.title ?? ""
            info[MPMediaItemPropertyArtist] = snapshot.artist ?? ""
            info[MPMediaItemPropertyPlaybackDuration] = Double(snapshot.duration) / 1000
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(extrapolatedPosition(snapshot, nowMs: nowMs)) / 1000
            info[MPNowPlayingInfoPropertyPlaybackRate] = snapshot.playing ? snapshot.playbackSpeed : 0.0
            if let image = snapshot.artwork {
                info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info.isEmpty ? nil : info
        MPNowPlayingInfoCenter.default().playbackState = info.isEmpty ? .stopped : (playing ? .playing : .paused)
    }
}
