import SwiftUI

/// Deliberately never falls back to a raw package name ("com.google.android.youtube") - that's a
/// debug detail, not a user-facing label. When Android couldn't resolve a real app name (see
/// MediaContinuityManager.kt's humanReadableAppLabel), "Android" alone is the honest fallback.
/// A free function so this is unit-testable without rendering the view.
func mediaSourceLabel(appLabel: String?) -> String {
    guard let appLabel, !appLabel.isEmpty else { return "Android" }
    return "\(appLabel) · Android"
}

/// Live "what's playing on Android" card for the main Bridgey menu-bar panel - the Mac-side UI for
/// the media.remote.* direction. Deliberately only appears when Android reports an actual session
/// (hasSession == true): the panel already conveys "connected, nothing playing" by this card's
/// absence, so there is no separate placeholder state ("No media", "Waiting for media"...) that
/// could be mistaken for "Android is playing something" when it isn't. This is a live player, not a
/// settings surface - MediaSettingsView (player choice, Automation access) stays separate.
struct MediaRemoteCard: View {
    @ObservedObject var media: MediaRemoteController
    @State private var now = Date()
    @State private var isSeeking = false
    @State private var seekPreviewMs: Double = 0
    @State private var isAdjustingVolume = false
    @State private var volumePreview: Double = 0
    /// Play/Pause must feel instant even though the real state only echoes back once Android's next
    /// media.remote.state arrives. This shows the tapped state immediately and is cleared the moment
    /// a real `playing` value comes in - Android's state is authoritative the instant it lands.
    @State private var optimisticPlaying: Bool?
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        if media.state.hasSession {
            content
        }
    }

    private var content: some View {
        let snapshot = media.state
        let currentMs = extrapolatedPosition(snapshot, nowMs: Int64(now.timeIntervalSince1970 * 1000))
        let hasSeek = snapshot.capabilities.contains("seek")
        let hasVolume = snapshot.capabilities.contains("volume")
        let playing = optimisticPlaying ?? snapshot.playing
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                artwork(snapshot)
                VStack(alignment: .leading, spacing: 3) {
                    Text(sourceLabel(snapshot))
                        .font(.system(size: 10, weight: .semibold))
                        .tracking(0.4)
                        .textCase(.uppercase)
                        .foregroundStyle(Color.accentColor)
                    Text((snapshot.title?.isEmpty == false ? snapshot.title : nil) ?? "Unknown track")
                        .font(.system(size: 15, weight: .semibold))
                        .lineLimit(2)
                    if let artist = snapshot.artist, !artist.isEmpty {
                        Text(artist).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }

            if snapshot.duration > 0 {
                VStack(spacing: 3) {
                    Slider(
                        value: Binding(
                            get: { isSeeking ? seekPreviewMs : Double(currentMs) },
                            set: { seekPreviewMs = $0 }
                        ),
                        in: 0...Double(max(snapshot.duration, 1)),
                        onEditingChanged: { editing in
                            isSeeking = editing
                            if !editing, hasSeek { media.command("seek", value: Int64(seekPreviewMs)) }
                        }
                    )
                    .disabled(!hasSeek)
                    .controlSize(.small)
                    HStack {
                        Text(formatDuration(isSeeking ? Int64(seekPreviewMs) : currentMs))
                        Spacer()
                        Text(formatDuration(snapshot.duration))
                    }
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(.secondary)
                }
            }

            HStack(spacing: 22) {
                Spacer(minLength: 0)
                Button {
                    media.command("previous")
                } label: {
                    Image(systemName: "backward.fill").font(.system(size: 15))
                }
                .disabled(!snapshot.capabilities.contains("previous"))

                Button {
                    optimisticPlaying = !playing
                    media.command("toggle")
                } label: {
                    Image(systemName: playing ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 32))
                }
                .disabled(!(snapshot.capabilities.contains("play") || snapshot.capabilities.contains("pause")))

                Button {
                    media.command("next")
                } label: {
                    Image(systemName: "forward.fill").font(.system(size: 15))
                }
                .disabled(!snapshot.capabilities.contains("next"))
                Spacer(minLength: 0)
            }
            .buttonStyle(.plain)
            .foregroundStyle(Color.primary)

            if hasVolume {
                HStack(spacing: 8) {
                    Image(systemName: "speaker.fill").font(.caption2).foregroundStyle(.secondary)
                    Slider(
                        value: Binding(
                            get: { isAdjustingVolume ? volumePreview : Double(snapshot.volume ?? 0) },
                            set: { volumePreview = $0 }
                        ),
                        in: 0...100,
                        onEditingChanged: { editing in
                            isAdjustingVolume = editing
                            if !editing { media.command("volume", value: Int64(volumePreview)) }
                        }
                    )
                    .controlSize(.small)
                    Image(systemName: "speaker.wave.3.fill").font(.caption2).foregroundStyle(.secondary)
                }
            }

            if let reason = media.lastActionRejectedReason {
                Text(rejectionMessage(reason)).font(.caption2).foregroundStyle(.orange)
            }
        }
        .padding(12)
        .background(Color.accentColor.opacity(0.08), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .onReceive(ticker) { now = $0 }
        .onChange(of: media.state.playing) { _ in optimisticPlaying = nil }
    }

    @ViewBuilder
    private func artwork(_ snapshot: MediaRemoteState) -> some View {
        Group {
            if let image = snapshot.artwork {
                Image(nsImage: image).resizable().aspectRatio(contentMode: .fill)
            } else {
                Rectangle()
                    .fill(Color.secondary.opacity(0.2))
                    .overlay(Image(systemName: "music.note").font(.system(size: 20)).foregroundStyle(.secondary))
            }
        }
        .frame(width: 60, height: 60)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        .shadow(color: .black.opacity(0.15), radius: 3, y: 1)
    }

    private func sourceLabel(_ snapshot: MediaRemoteState) -> String {
        mediaSourceLabel(appLabel: snapshot.appLabel)
    }

    private func rejectionMessage(_ reason: String) -> String {
        switch reason {
        case "stale_generation": return "The session changed on Android before that command arrived."
        case "no_session": return "Android has no active session to control."
        default: return "Android couldn't complete that command."
        }
    }

    private func formatDuration(_ ms: Int64) -> String {
        let totalSeconds = max(0, ms / 1000)
        return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
