import AppKit
import AVFoundation
import SwiftUI

@MainActor
final class ScreenShareWindowController: NSWindowController, NSWindowDelegate {
    /// Advanced Screen Continuity - Remote Start: the user closing this window is treated as an
    /// explicit "Stop Screen Share" request, mirrored to the phone as screenshare.remoteStop.
    private let onUserClosedWindow: () -> Void

    /// KVM Mouse Input v1: `onPointerEvent` receives (action, normalizedX, normalizedY) already mapped
    /// through VideoContentGeometry - see DisplayLayerView/KvmMouseCaptureView below. Only .down/.up/
    /// .move are ever produced here (left button + movement/drag) - this is the real production input
    /// path, wired from Pairing.showScreenShareWindow() into the existing frozen "input" channel.
    init(
        decoder: ScreenStreamDecoder,
        onUserClosedWindow: @escaping () -> Void,
        onPointerEvent: @escaping (PointerAction, Float, Float) -> Void
    ) {
        self.onUserClosedWindow = onUserClosedWindow
        let root = ScreenShareView(decoder: decoder, onPointerEvent: onPointerEvent)
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
    let onPointerEvent: (PointerAction, Float, Float) -> Void

    var body: some View {
        ZStack {
            Color.black
            DisplayLayerView(
                layer: decoder.displayLayer,
                presentationMode: decoder.presentationMode,
                sourceSize: decoder.sourceSize,
                onPointerEvent: onPointerEvent
            )
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

/// Hosts an AVSampleBufferDisplayLayer (not SwiftUI-native) inside SwiftUI.
///
/// KVM Mouse Input v1 fix: this used to let the layer letterbox itself (`videoGravity = .resizeAspect`,
/// `layer.frame = view.bounds`) and separately, independently recompute where we THOUGHT that
/// letterboxing put the picture (`VideoContentGeometry.contentRect`) for coordinate mapping. Real-device
/// measurement proved those two calculations disagree - `.resizeAspect`'s actual internal positioning
/// does not match `AVMakeRect`'s prediction (confirmed: encoded/presentation dimensions are identical,
/// the view's own bounds and the layer's frame were both confirmed correct, so the divergence is
/// entirely inside AVFoundation's private `.resizeAspect` content placement, which has no way to
/// introspect or query - AVSampleBufferDisplayLayer, unlike AVPlayerLayer, exposes no `videoRect`).
///
/// The fix removes the second, independent calculation entirely instead of trying to reverse-engineer
/// or empirically match it: `layer.frame` is now set to exactly `VideoContentGeometry.contentRect(...)`
/// (the same call `normalizedPoint` already uses for input mapping) and `videoGravity = .resize`
/// (plain stretch-to-fill, no internal aspect logic) - so the layer's on-screen position *is* our
/// calculation, by construction. There is no longer a second geometry to disagree with the first.
/// `masksToBounds` on the hosting view's layer crops FILL mode's intentionally-oversized content rect
/// exactly the way `.resizeAspectFill` used to.
private struct DisplayLayerView: NSViewRepresentable {
    let layer: AVSampleBufferDisplayLayer
    let presentationMode: VideoPresentationMode
    let sourceSize: CGSize?
    let onPointerEvent: (PointerAction, Float, Float) -> Void

    func makeNSView(context: Context) -> KvmMouseCaptureView {
        let view = KvmMouseCaptureView()
        view.wantsLayer = true
        view.layer?.masksToBounds = true
        // Deliberately NO autoresizingMask: `.layerWidthSizable`/`.layerHeightSizable` would tell
        // Core Animation to continuously keep this sublayer's size matching the FULL superlayer
        // bounds on every layout pass - which fights and overrides the explicit, smaller, centered
        // `contentRect` frame this code assigns below/in updateNSView. SwiftUI's own layout cycle
        // calls updateNSView on every relevant resize, which is what keeps the frame correct instead.
        layer.videoGravity = .resize
        layer.frame = VideoContentGeometry.contentRect(sourceSize: sourceSize, mode: presentationMode, in: view.bounds)
        view.layer?.addSublayer(layer)
        view.sourceSize = sourceSize
        view.presentationMode = presentationMode
        view.onPointerEvent = onPointerEvent
        return view
    }

    func updateNSView(_ nsView: KvmMouseCaptureView, context: Context) {
        layer.frame = VideoContentGeometry.contentRect(sourceSize: sourceSize, mode: presentationMode, in: nsView.bounds)
        nsView.sourceSize = sourceSize
        nsView.presentationMode = presentationMode
        nsView.onPointerEvent = onPointerEvent
    }
}

/// KVM Mouse Input v1 - the real, production mouse-capture surface (not a debug/test view). Captures
/// left-button down/drag/up and plain movement only, mapping each point through
/// VideoContentGeometry.normalizedPoint (the existing, already-tested inverse of contentRect), then
/// KvmPointerCalibration.apply (the empirical offset from KVM_CALIBRATION_MODEL_ANALYSIS.md) before
/// handing it to `onPointerEvent`, which the caller wires straight into the existing, frozen
/// videoChannel.offerInput/sendInputEvent - the exact same "input" channel/InputEvent.pointer wire
/// format already proven end to end by the KVM Input Foundation checkpoint (commit 1facc3d).
///
/// Right button, middle button, and scroll are deliberately NOT captured here: the frozen
/// InputEvent.Pointer wire format has no field for button identity or scroll delta, and
/// BridgeyAccessibilityService's dispatchGesture has no native concept of a mouse button or a scroll
/// wheel at all (touchscreens don't have either) - supporting them would require extending the frozen
/// foundation, out of scope for this task pending explicit approval (see the KVM Mouse Input v1 report).
final class KvmMouseCaptureView: NSView {
    var sourceSize: CGSize?
    var presentationMode: VideoPresentationMode = .fit
    var onPointerEvent: ((PointerAction, Float, Float) -> Void)?

    /// Top-left-origin, matching the same axis convention InputEvent.pointer's (x, y) already use
    /// elsewhere in Bridgey (0,0 = top-left) - avoids a manual Y-flip in the geometry math.
    override var isFlipped: Bool { true }

    private var trackingArea: NSTrackingArea?

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let trackingArea { removeTrackingArea(trackingArea) }
        let area = NSTrackingArea(rect: bounds, options: [.activeInKeyWindow, .mouseMoved, .inVisibleRect], owner: self, userInfo: nil)
        addTrackingArea(area)
        trackingArea = area
    }

    override func mouseDown(with event: NSEvent) { report(.down, event) }
    override func mouseDragged(with event: NSEvent) { report(.move, event) }
    override func mouseUp(with event: NSEvent) { report(.up, event) }
    override func mouseMoved(with event: NSEvent) { report(.move, event) }

    private func report(_ action: PointerAction, _ event: NSEvent) {
        let point = convert(event.locationInWindow, from: nil)
        guard let normalized = VideoContentGeometry.normalizedPoint(point, sourceSize: sourceSize, mode: presentationMode, in: bounds) else {
            return
        }
        let calibrated = KvmPointerCalibration.apply(normalized)
        onPointerEvent?(action, Float(calibrated.x), Float(calibrated.y))
    }
}
