import SwiftUI

struct QuickActionsPanel: View {
    @ObservedObject var actions: QuickActions
    var body: some View {
        if let link = actions.receivedLink {
            VStack(alignment: .leading, spacing: 8) {
                Text("Link from Android").font(.headline)
                Text(link).font(.caption).lineLimit(3).textSelection(.enabled)
                HStack {
                    Button("Open in browser", action: actions.openLink)
                    Button("Dismiss", action: actions.dismissLink)
                }
            }
        }
        if let book = actions.receivedBook {
            VStack(alignment: .leading, spacing: 6) {
                Text("Continue reading from Android").font(.headline)
                if let title = book.title { Text(title).font(.body.weight(.semibold)).lineLimit(2) }
                if let chapter = book.chapter { Text(chapter).font(.caption).lineLimit(2) }
                if let page = book.page {
                    Text(book.pages.map { "Page \(page) of \($0) on the phone (\(page * 100 / $0) %)" } ?? "Page \(page) on the phone")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if let quote = book.quote { Text("“\(quote)”").font(.caption).italic().lineLimit(4).textSelection(.enabled) }
                HStack {
                    Button("Continue in Books", action: actions.continueInBooks)
                    Button("Dismiss", action: actions.dismissBook)
                }
            }
        }
        if let status = actions.status { Text(status).font(.caption).foregroundStyle(.secondary) }
    }
}

struct MediaSettingsView: View {
    @ObservedObject var media: MediaController
    @State private var hardwareKeyAccessGranted = GlobalMediaCommandCenter.hardwareKeyAccessGranted
    var body: some View {
        Section("Media") {
            Text("Enable Media controls above, then choose a player. macOS asks for Automation access to that app. Open the player on your Mac first.")
                .font(.caption).foregroundStyle(.secondary)
            Picker("Player", selection: $media.player) {
                ForEach(MediaPlayer.allCases) { Text($0.title).tag($0) }
            }
            Toggle("Pause media during phone calls", isOn: $media.pauseForCalls)
            Text("Pauses the selected player when a call rings or becomes active. Playback stays paused after the call; resume it when ready.")
                .font(.caption).foregroundStyle(.secondary)
            if !media.snapshot.detail.isEmpty { Text(media.snapshot.detail).font(.caption).foregroundStyle(.secondary) }
            Button("Refresh player", action: media.refresh)
            Text("Your keyboard's Play/Pause, Next, and Previous keys control whichever media - this Mac's or your phone's - is currently active.")
                .font(.caption).foregroundStyle(.secondary)
            if !hardwareKeyAccessGranted {
                Button("Enable hardware media keys…") {
                    GlobalMediaCommandCenter.requestHardwareKeyAccess()
                    GlobalMediaCommandCenter.openInputMonitoringSettings()
                }
                Text("Needs Input Monitoring access (Privacy & Security) so Bridgey can see your keyboard's media keys. Bridgey never reads anything you type.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .onAppear { hardwareKeyAccessGranted = GlobalMediaCommandCenter.hardwareKeyAccessGranted }
    }
}
