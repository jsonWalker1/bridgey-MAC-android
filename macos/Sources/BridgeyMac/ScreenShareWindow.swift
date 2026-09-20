import AppKit
import AVFoundation
import Combine
import SwiftUI

/// Plain reference-type holder for the KvmMouseCaptureView instance SwiftUI creates - see
/// ScreenShareWindowController.captureViewBox for why this indirection exists.
@MainActor
private final class CaptureViewBox {
    weak var view: KvmMouseCaptureView?
}

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
        let captureViewBox = CaptureViewBox()
        self.captureViewBox = captureViewBox
        let root = ScreenShareView(decoder: decoder, onPointerEvent: onPointerEvent) { view in
            captureViewBox.view = view
        }
        let window = NSWindow(contentViewController: NSHostingController(rootView: root))
        window.title = "Bridgey Screen Mirror"
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.setContentSize(ScreenShareWindowController.windowSize(forLandscape: false))
        window.minSize = NSSize(width: 240, height: 420)
        window.isReleasedWhenClosed = false
        window.collectionBehavior.insert(.fullScreenPrimary)
        window.center()
        super.init(window: window)
        window.delegate = self

        // KVM pointer calibration is a single constant offset, correct only near the window size it
        // was tuned at - the further a resize pushes the content rect away from that size, the more
        // the effective correction drifts (this is a hard mathematical limit of any single-constant
        // model, not a bug - see KvmPointerCalibration). The window defaults to a portrait shape
        // (480x900) matched to a portrait phone video; showing landscape video in that shape forces
        // extreme letterboxing (a ~2:1 video width-constrained into a narrow window), which makes
        // ordinary resizes swing the content rect size - and therefore the calibration drift -
        // dramatically more than the portrait case ever did. Switching to a landscape-shaped default
        // as soon as the video's orientation is known keeps everyday resizes closer to the tuned
        // reference size instead of starting from the worst-case mismatch. This does not fully solve
        // resize sensitivity (no single constant can); see KVM_CALIBRATION_MODEL_ANALYSIS.md follow-up
        // for a proper multi-size measurement pass if tighter behavior is needed later.
        //
        // ONLY while windowed, though (the `!window.styleMask.contains(.fullScreen)` guard): this is
        // the actual root cause of the "fullscreen but video stays tiny" bug. `sourceSize` only becomes
        // known once the first video frame decodes, which typically lands AFTER the fullscreen
        // animation this window opens with (see show()) has already completed. Without the guard,
        // setContentSize() here would fire on an already-fullscreen window and forcibly shrink its
        // CONTENT VIEW down to the small windowed default size - the window frame stays fullscreen
        // (hence the full black backdrop), but the actual content area - and the video inside it - gets
        // pinned to 480x900/900x480 regardless. The exact same thing happened on every later portrait
        // <-> landscape rotation while sharing, for the same reason (a fresh orientation value re-fires
        // this sink). A manual resize-then-fullscreen "fixed" it only because that sequence doesn't run
        // through this sink again (the orientation hasn't changed), so nothing re-shrinks the content.
        sourceOrientationObservation = decoder.$sourceSize
            .compactMap { sourceSize -> Bool? in
                guard let sourceSize, sourceSize.width > 0, sourceSize.height > 0 else { return nil }
                return sourceSize.width > sourceSize.height
            }
            .removeDuplicates()
            .sink { [weak window] isLandscape in
                guard let window, !window.styleMask.contains(.fullScreen) else { return }
                window.setContentSize(ScreenShareWindowController.windowSize(forLandscape: isLandscape))
                window.center()
            }

        // Video/KVM geometry lifecycle fix: KvmMouseCaptureView.layout() is the single source of truth
        // for both the video layer's frame and pointer mapping (see its doc comment), and relies on
        // AppKit invoking it whenever the view's own bounds change. That is NOT guaranteed to happen
        // promptly for every relevant transition when the view is hosted via NSViewRepresentable inside
        // NSHostingController - observed in practice: starting screen sharing already in fullscreen, or
        // rotating the phone's orientation while sharing, could leave the video at its stale/initial
        // size until an unrelated later resize happened to trigger a fresh layout pass. Rather than
        // trust that implicit propagation, explicitly force a synchronous layout pass on the capture
        // view for every OS-level event that can invalidate its geometry: window resize (including the
        // live-resize case), and fullscreen enter/exit. `updateNSView` already covers sourceSize/
        // orientation changes (a SwiftUI state update, not a raw AppKit resize) by setting needsLayout.
        for name in [
            NSWindow.didResizeNotification,
            NSWindow.didEndLiveResizeNotification,
            NSWindow.didEnterFullScreenNotification,
            NSWindow.didExitFullScreenNotification,
        ] {
            let token = NotificationCenter.default.addObserver(forName: name, object: window, queue: .main) { [weak window, captureViewBox] _ in
                MainActor.assumeIsolated {
                    // Force the layout pass from the TOP of the hierarchy (the window's content view,
                    // i.e. the NSHostingController's own view), not just the leaf capture view: SwiftUI's
                    // own internal relayout of its hosted content in response to the window's new frame
                    // is a separate step from the window itself having already resized, and isn't
                    // guaranteed to have already happened by the time this notification fires. Calling
                    // layoutSubtreeIfNeeded() on the leaf view alone risks reading its `bounds` before
                    // SwiftUI has actually resized it to match, silently reusing the previous value.
                    // Starting from contentView forces SwiftUI to reconcile against the window's current,
                    // already-final frame first, cascading down correctly into KvmMouseCaptureView.
                    window?.contentView?.needsLayout = true
                    window?.contentView?.layoutSubtreeIfNeeded()
                    guard let captureView = captureViewBox.view else { return }
                    captureView.needsLayout = true
                    captureView.layoutSubtreeIfNeeded()
                }
            }
            windowLifecycleObservers.append(token)
        }
    }

    private var sourceOrientationObservation: AnyCancellable?
    private var windowLifecycleObservers: [NSObjectProtocol] = []
    /// Set once by DisplayLayerView.makeNSView (via the onCaptureViewCreated callback passed down
    /// through ScreenShareView), so the window-lifecycle observers above can force a synchronous
    /// relayout directly on the actual view that owns the video layer and pointer mapping geometry,
    /// instead of hoping a generic "needsLayout" on some ancestor cascades down to it. A plain
    /// reference-type box (rather than a `weak var` property on self) because it must be captured by
    /// the `root` view's callback closure before `super.init()` makes `self` available.
    private let captureViewBox: CaptureViewBox
    /// This app is a MenuBarExtra scene (see BridgeyApp.swift), not a WindowGroup - so it never gets
    /// AppKit's usual auto-generated "View > Enter Full Screen" menu item, and with it the standard
    /// system Control+Command+F shortcut. That binding simply does not exist anywhere in this app by
    /// default; it has to be installed by hand for this specific window.
    private var fullScreenKeyMonitor: Any?

    private static func windowSize(forLandscape isLandscape: Bool) -> NSSize {
        isLandscape ? NSSize(width: 900, height: 480) : NSSize(width: 480, height: 900)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        showWindow(nil)
        window?.makeKeyAndOrderFront(nil)
        // Dispatched, not called inline: calling toggleFullScreen() synchronously in the same run-loop
        // pass as makeKeyAndOrderFront() - before AppKit has actually finished presenting the window -
        // could enter fullscreen without the video layer's layout() pass ever running against the
        // final fullscreen bounds, leaving the phone's picture stuck at its pre-fullscreen size even
        // though the window itself is genuinely fullscreen.
        DispatchQueue.main.async { [weak self] in
            guard let window = self?.window, !window.styleMask.contains(.fullScreen) else { return }
            window.toggleFullScreen(nil)
        }
        installFullScreenKeyMonitorIfNeeded()
    }

    private func installFullScreenKeyMonitorIfNeeded() {
        guard fullScreenKeyMonitor == nil else { return }
        fullScreenKeyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self, let window = self.window, event.window === window else { return event }
            let isFullScreenShortcut = event.charactersIgnoringModifiers?.lowercased() == "f"
                && event.modifierFlags.intersection(.deviceIndependentFlagsMask) == [.control, .command]
            guard isFullScreenShortcut else { return event }
            window.toggleFullScreen(nil)
            return nil
        }
    }

    func windowWillClose(_ notification: Notification) {
        if let fullScreenKeyMonitor {
            NSEvent.removeMonitor(fullScreenKeyMonitor)
        }
        fullScreenKeyMonitor = nil
        windowLifecycleObservers.forEach(NotificationCenter.default.removeObserver)
        windowLifecycleObservers.removeAll()
        onUserClosedWindow()
    }
}

private struct ScreenShareView: View {
    @ObservedObject var decoder: ScreenStreamDecoder
    let onPointerEvent: (PointerAction, Float, Float) -> Void
    let onCaptureViewCreated: (KvmMouseCaptureView) -> Void

    var body: some View {
        ZStack {
            Color.black
            DisplayLayerView(
                layer: decoder.displayLayer,
                presentationMode: decoder.presentationMode,
                sourceSize: decoder.sourceSize,
                onPointerEvent: onPointerEvent,
                onCaptureViewCreated: onCaptureViewCreated
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
    let onCaptureViewCreated: (KvmMouseCaptureView) -> Void

    func makeNSView(context: Context) -> KvmMouseCaptureView {
        let view = KvmMouseCaptureView()
        view.wantsLayer = true
        view.layer?.masksToBounds = true
        // Deliberately NO autoresizingMask: `.layerWidthSizable`/`.layerHeightSizable` would tell
        // Core Animation to continuously keep this sublayer's size matching the FULL superlayer
        // bounds on every layout pass - which fights and overrides the explicit, smaller, centered
        // `contentRect` frame this view's own layout() override assigns instead.
        layer.videoGravity = .resize
        view.videoLayer = layer
        view.layer?.addSublayer(layer)
        view.sourceSize = sourceSize
        view.presentationMode = presentationMode
        view.onPointerEvent = onPointerEvent
        view.needsLayout = true
        onCaptureViewCreated(view)
        return view
    }

    func updateNSView(_ nsView: KvmMouseCaptureView, context: Context) {
        // Repositioning the layer here (driven by SwiftUI's updateNSView cadence) used to be able to
        // lag behind an AppKit-driven resize - most visibly entering/leaving fullscreen, where the
        // window's animated resize is not guaranteed to invoke updateNSView promptly for every frame.
        // That produced two symptoms from one cause: the video visibly failing to recenter right
        // away, AND KVM pointer mapping (which reads this view's own `bounds` - always fresh, since
        // AppKit updates it directly) landing on the wrong on-screen spot relative to where the video
        // was still actually drawn. Recomputing the layer frame in `layout()` instead ties both the
        // video's position and the pointer mapping to the exact same live `bounds` at the exact same
        // AppKit layout pass, so they can't diverge by construction - the "one geometry calculation"
        // principle this file already documents, now enforced for resize/fullscreen too.
        nsView.sourceSize = sourceSize
        nsView.presentationMode = presentationMode
        nsView.onPointerEvent = onPointerEvent
        nsView.needsLayout = true
    }
}

/// KVM Mouse Input v1 - the real, production mouse-capture surface (not a debug/test view). Captures
/// left-button down/drag/up and plain movement only, first nudging the raw captured point through
/// KvmPointerCalibration.apply (the live-verified offset - deliberately applied before normalization
/// so it scales correctly with the current contentRect if the window is resized, see that type's
/// doc comment), then mapping it through VideoContentGeometry.normalizedPoint (the existing,
/// already-tested inverse of contentRect) before handing it to `onPointerEvent`, which the caller
/// wires straight into the existing, frozen videoChannel.offerInput/sendInputEvent - the exact same
/// "input" channel/InputEvent.pointer wire format already proven end to end by the KVM Input
/// Foundation checkpoint (commit 1facc3d).
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
    /// Set once in makeNSView. Repositioned in `layout()`, not in SwiftUI's `updateNSView`, so the
    /// video's on-screen position and this view's own `bounds` (what pointer mapping reads) can never
    /// diverge during a resize/fullscreen transition - see DisplayLayerView's doc comment.
    var videoLayer: AVSampleBufferDisplayLayer?

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

    override func layout() {
        super.layout()
        videoLayer?.frame = VideoContentGeometry.contentRect(sourceSize: sourceSize, mode: presentationMode, in: bounds)
    }

    override func mouseDown(with event: NSEvent) { report(.down, event) }
    override func mouseDragged(with event: NSEvent) { report(.move, event) }
    override func mouseUp(with event: NSEvent) { report(.up, event) }
    override func mouseMoved(with event: NSEvent) { report(.move, event) }

    private func report(_ action: PointerAction, _ event: NSEvent) {
        let point = convert(event.locationInWindow, from: nil)
        let calibrated = KvmPointerCalibration.apply(point, sourceSize: sourceSize)
        guard let normalized = VideoContentGeometry.normalizedPoint(calibrated, sourceSize: sourceSize, mode: presentationMode, in: bounds) else {
            return
        }
        onPointerEvent?(action, Float(normalized.x), Float(normalized.y))
    }
}
