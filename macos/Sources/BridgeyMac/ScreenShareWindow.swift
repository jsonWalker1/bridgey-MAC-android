import AppKit
import AVFoundation
import SwiftUI

@MainActor
final class ScreenShareWindowController: NSWindowController, NSWindowDelegate {
    /// Advanced Screen Continuity - Remote Start: the user closing this window is treated as an
    /// explicit "Stop Screen Share" request, mirrored to the phone as screenshare.remoteStop.
    private let onUserClosedWindow: () -> Void

    init(decoder: ScreenStreamDecoder, onUserClosedWindow: @escaping () -> Void) {
        self.onUserClosedWindow = onUserClosedWindow
        let root = ScreenShareView(decoder: decoder)
        let window = NSWindow(contentViewController: NSHostingController(rootView: root))
        window.title = "Bridgey Screen Mirror"
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.setContentSize(NSSize(width: 480, height: 900))
        window.minSize = NSSize(width: 240, height: 420)
        window.isReleasedWhenClosed = false
        window.center()
        super.init(window: window)
        window.delegate = self
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        onUserClosedWindow()
    }
}

private struct ScreenShareView: View {
    @ObservedObject var decoder: ScreenStreamDecoder

    var body: some View {
        ZStack {
            Color.black
            DisplayLayerView(layer: decoder.displayLayer, presentationMode: decoder.presentationMode)
            if !decoder.isReceivingVideo {
                VStack(spacing: 10) {
                    ProgressView()
                    Text("Waiting for the phone to start screen sharing…")
                        .foregroundStyle(.white.opacity(0.8))
                        .font(.callout)
                }
            }
        }
        // maxWidth/maxHeight (not just the minimums below) so this actually grows to fill the
        // window - including fullscreen - instead of SwiftUI sizing the stack to its smallest
        // acceptable size and leaving the rest of the window as plain background.
        .frame(minWidth: 240, maxWidth: .infinity, minHeight: 420, maxHeight: .infinity)
    }
}

/// Hosts an AVSampleBufferDisplayLayer (not SwiftUI-native) inside SwiftUI. The layer always spans
/// the full view; `videoGravity` (driven by `presentationMode`) is what actually letterboxes/crops
/// the video within it - the precise displayed sub-rectangle is computed separately on demand via
/// ScreenStreamDecoder.contentRect(in:), for a future KVM input handler to map coordinates against.
private struct DisplayLayerView: NSViewRepresentable {
    let layer: AVSampleBufferDisplayLayer
    let presentationMode: VideoPresentationMode

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        view.wantsLayer = true
        // Sublayers added via the CALayer API don't participate in AppKit's Auto Layout, so without
        // an explicit autoresizing mask the layer keeps whatever frame it was last given (often the
        // zero-size frame from before the hosting view's first layout pass) even as the window
        // resizes - CoreMedia then silently evicts every enqueued frame into that zero-size layer.
        layer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        layer.frame = view.bounds
        view.layer?.addSublayer(layer)
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        layer.frame = nsView.bounds
        layer.videoGravity = presentationMode == .fit ? .resizeAspect : .resizeAspectFill
    }
}
