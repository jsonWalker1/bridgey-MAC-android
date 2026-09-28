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

/// KVM Mouse V2, phase 1/2 (see KVM_MOUSE_V2_PHASE1.md): `button` only has meaning for
/// down/up/move; `scrollDx`/`scrollDy` only have meaning for `.scroll` (mirrors
/// InputEvent.pointer's own shape in InputTransport.swift, which this is wired directly into).
typealias PointerEventHandler = (PointerAction, Float, Float, PointerButton, Float, Float) -> Void

@MainActor
final class ScreenShareWindowController: NSWindowController, NSWindowDelegate {
    /// Advanced Screen Continuity - Remote Start: the user closing this window is treated as an
    /// explicit "Stop Screen Share" request, mirrored to the phone as screenshare.remoteStop.
    private let onUserClosedWindow: () -> Void
    /// BRIDGEY KVM KEYBOARD SHORTCUTS (2026-09-23): touchpad gestures for BACK/FORWARD/NOTIFICATIONS/
    /// RECENTS turned out too fragile on real hardware across several rounds of tuning (see
    /// KvmGestureRecognizer's doc comment) - kept as-is for anyone who wants to try, but the primary,
    /// reliable way to trigger these four actions is now Command+Arrow while this window is key,
    /// handled by `installFullScreenKeyMonitorIfNeeded`'s key monitor alongside the existing Control+
    /// Command+F fullscreen toggle. Stored here (not just passed to ScreenShareView) because the key
    /// monitor lives on the controller, not the SwiftUI view tree.
    private let onGestureEvent: (GestureAction) -> Void
    /// BRIDGEY KVM KEYBOARD V1: general macOS->Android keyboard forwarding, captured ONLY while this
    /// window is key (see `installFullScreenKeyMonitorIfNeeded`'s `event.window === window` scoping) -
    /// never a global keyboard intercept. Stored here for the same reason `onGestureEvent` is: the key
    /// monitor lives on the controller, not the SwiftUI view tree.
    private let onKeyboardEvent: (InputEvent) -> Void
    /// BRIDGEY KVM KEYBOARD V1 (switch shortcut): Command+K asks Android to toggle its active keyboard
    /// to/from Bridgey's KVM Keyboard - see PairingCoordinator.receiveSwitchKeyboard on the phone side.
    /// A plain control request, not an InputEvent, so it's a separate closure from `onKeyboardEvent`.
    private let onSwitchKeyboardRequested: () -> Void
    /// BRIDGEY KVM COPY/PASTE INTEGRATION: Command+V during KVM needs Bridgey's existing (unmodified)
    /// Clipboard Continuity `sendClipboard()` to sync the Mac's current pasteboard to Android BEFORE
    /// forwarding a Ctrl+V key - see the Pairing.swift call site for why. A separate closure from
    /// `onKeyboardEvent` since it isn't itself a raw InputEvent, same reasoning as
    /// `onSwitchKeyboardRequested` above.
    private let onPasteRequested: () -> Void

    /// KVM Mouse: `onPointerEvent` receives (action, normalizedX, normalizedY, button, scrollDx,
    /// scrollDy) already mapped through VideoContentGeometry - see DisplayLayerView/
    /// KvmMouseCaptureView below - wired from Pairing.showScreenShareWindow() into the existing
    /// "input" channel (InputTransport.swift/InputEvent.pointer). BRIDGEY KVM TOUCHPAD GESTURES V1:
    /// `onGestureEvent` is threaded through the exact same path, one step behind `onPointerEvent`, for
    /// a fully resolved semantic GestureAction - see KvmGestureRecognizer for where it's recognized.
    init(
        decoder: ScreenStreamDecoder,
        onUserClosedWindow: @escaping () -> Void,
        onPointerEvent: @escaping PointerEventHandler,
        onGestureEvent: @escaping (GestureAction) -> Void,
        onKeyboardEvent: @escaping (InputEvent) -> Void,
        onSwitchKeyboardRequested: @escaping () -> Void,
        onPasteRequested: @escaping () -> Void
    ) {
        self.onUserClosedWindow = onUserClosedWindow
        self.onGestureEvent = onGestureEvent
        self.onKeyboardEvent = onKeyboardEvent
        self.onSwitchKeyboardRequested = onSwitchKeyboardRequested
        self.onPasteRequested = onPasteRequested
        let captureViewBox = CaptureViewBox()
        self.captureViewBox = captureViewBox
        let root = ScreenShareView(decoder: decoder, onPointerEvent: onPointerEvent, onGestureEvent: onGestureEvent) { view in
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
    /// BRIDGEY KVM KEYBOARD V1: resolved (Android keycode, wire modifiers) for a still-held key,
    /// keyed by its Mac physical `NSEvent.keyCode` - not recomputed at keyUp time. Modifiers can be
    /// released in a different order than the key itself (e.g. releasing Control before releasing
    /// 'C' on a Ctrl+C chord); recomputing from `event.modifierFlags`/`charactersIgnoringModifiers` at
    /// keyUp could then resolve to a different mapping than the keyDown actually sent, or none at all.
    /// Looking up what keyDown already resolved guarantees every forwarded down is matched by the
    /// exact same up.
    private var pendingKeyDowns: [UInt16: (androidKeyCode: Int32, modifiers: UInt8)] = [:]
    /// BRIDGEY KVM KEYBOARD V1: only the "real" modifier keys (see the `.flagsChanged` handler for why
    /// arrow/function-row `.function`/`.numericPad` bits must be excluded), used to detect standalone
    /// Shift/Control/Option/Command press-and-release (no other key involved).
    private var previousRealModifierFlags: NSEvent.ModifierFlags = []

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

    /// Standard macOS virtual keycodes for the arrow keys (no public named constant exists for these -
    /// this is the documented, stable mapping used throughout AppKit).
    private enum ArrowKeyCode {
        static let left: UInt16 = 123
        static let right: UInt16 = 124
        static let down: UInt16 = 125
        static let up: UInt16 = 126
    }

    /// BRIDGEY KVM KEYBOARD SHORTCUTS: Command+Down is overloaded - a quick tap means HOME, holding it
    /// means RECENTS (mirrors the physical gesture it replaces: a short tap vs. a held app-switcher
    /// gesture). Held across keyDown (records when the press started) and keyUp (measures how long it
    /// was held) on the monitor below; nil whenever Command+Down isn't currently being held.
    private var commandDownArrowPressedAt: TimeInterval?
    private static let longPressMinDuration: TimeInterval = 0.4

    private func installFullScreenKeyMonitorIfNeeded() {
        guard fullScreenKeyMonitor == nil else { return }
        previousRealModifierFlags = NSEvent.modifierFlags.intersection([.command, .shift, .option, .control])
        fullScreenKeyMonitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .keyUp, .flagsChanged]) { [weak self] event in
            guard let self, let window = self.window, event.window === window else { return event }

            if event.type == .flagsChanged {
                return self.handleStandaloneModifierChange(event) ? nil : event
            }
            if event.type == .keyDown {
                let isFullScreenShortcut = event.charactersIgnoringModifiers?.lowercased() == "f"
                    && event.modifierFlags.intersection(.deviceIndependentFlagsMask) == [.control, .command]
                if isFullScreenShortcut {
                    window.toggleFullScreen(nil)
                    return nil
                }
                // BRIDGEY KVM KEYBOARD V1 (switch shortcut): Command+K toggles the phone's active
                // keyboard to/from Bridgey's KVM Keyboard - see PairingCoordinator.receiveSwitchKeyboard.
                let isSwitchKeyboardShortcut = event.charactersIgnoringModifiers?.lowercased() == "k"
                    && event.modifierFlags.intersection([.command, .shift, .option, .control]) == [.command]
                if isSwitchKeyboardShortcut {
                    self.onSwitchKeyboardRequested()
                    return nil
                }
            }
            if self.handleCommandArrowShortcut(event) {
                return nil
            }
            return self.forwardKeyboardEvent(event) ? nil : event
        }
    }

    /// BRIDGEY KVM KEYBOARD SHORTCUTS: Command+Arrow, chosen as the reliable replacement for the
    /// fragile touchpad gestures - Left/Right mirror Safari/Finder's own Back/Forward shortcut, Up was
    /// picked to match this app's own gesture design (swipe up->Notifications); Down is HOME on a tap,
    /// RECENTS on a hold (see `commandDownArrowPressedAt`). Arrow-key NSEvents carry `.function` (and
    /// often `.numericPad`) in `modifierFlags` automatically, even with no Fn key involved - AppKit
    /// treats the arrow keys as part of the function-key row. Matching only the "real" modifier keys
    /// (not the full deviceIndependentFlagsMask, which includes .function) is what actually isolates
    /// "Command and nothing else" - the exact-mask version silently never matched, letting every
    /// Command+Arrow press fall through unhandled to the standard beep.
    ///
    /// Returns `true` whenever this WAS a Command+Arrow chord (always fully consumed, exactly as
    /// before BRIDGEY KVM KEYBOARD V1 introduced general forwarding) so the caller stops here; `false`
    /// for anything else (a plain arrow with no Command, a different Command+key combo, ...) so
    /// `forwardKeyboardEvent` gets a chance at it instead.
    private func handleCommandArrowShortcut(_ event: NSEvent) -> Bool {
        guard event.modifierFlags.intersection([.command, .shift, .option, .control]) == [.command] else { return false }
        guard [ArrowKeyCode.left, ArrowKeyCode.right, ArrowKeyCode.up, ArrowKeyCode.down].contains(event.keyCode) else { return false }

        if event.keyCode == ArrowKeyCode.down {
            switch event.type {
            case .keyDown:
                // isARepeat: the OS's own key-repeat firing keyDown again and again while held - only
                // the very first keyDown starts the hold timer.
                if !event.isARepeat { self.commandDownArrowPressedAt = event.timestamp }
            case .keyUp:
                guard let pressedAt = self.commandDownArrowPressedAt else { break }
                self.commandDownArrowPressedAt = nil
                let held = event.timestamp - pressedAt
                self.onGestureEvent(held >= ScreenShareWindowController.longPressMinDuration ? .recents : .home)
            default:
                break
            }
            return true
        }

        guard event.type == .keyDown, !event.isARepeat else { return true }
        let action: GestureAction
        switch event.keyCode {
        case ArrowKeyCode.left: action = .back
        case ArrowKeyCode.right: action = .forward
        case ArrowKeyCode.up: action = .notifications
        default: return true // unreachable given the guard above
        }
        self.onGestureEvent(action)
        return true
    }

    /// BRIDGEY KVM KEYBOARD V1. Routes one keyDown/keyUp NSEvent to the right existing Android
    /// injection mechanism (never both) - special keys and Ctrl-chords go via the KEY wire event
    /// (real key semantics), everything else goes via TEXT (the existing IME commitText path).
    /// Returns `true` if the event was handled/forwarded (caller should consume it, i.e. return nil
    /// from the monitor), `false` to let it fall through to normal macOS handling untouched.
    private func forwardKeyboardEvent(_ event: NSEvent) -> Bool {
        // keyUp: prefer whatever the matching keyDown already resolved (see `pendingKeyDowns`'s doc
        // comment) rather than recomputing from this event's (possibly different, if a modifier was
        // released first) current modifier state.
        if event.type == .keyUp, let pending = pendingKeyDowns.removeValue(forKey: event.keyCode) {
            onKeyboardEvent(.key(keyCode: pending.androidKeyCode, action: .up, modifiers: pending.modifiers))
            return true
        }

        let realModifiers = event.modifierFlags.intersection([.command, .shift, .option, .control])

        // BRIDGEY KVM COPY/PASTE INTEGRATION: exactly Command+A/C/V/X, and ONLY when Command is the
        // sole modifier, are the one exception to the "Command combos pass through untouched" rule
        // below - Android's own EditText/TextView already implements select-all/copy/paste/cut for
        // CONTROL+A/C/V/X on a physical keyboard, so translating Mac's Command+letter into that is the
        // whole integration. Command+V additionally reuses the EXISTING, unmodified Clipboard
        // Continuity `sendClipboard()` first via `onPasteRequested` (see its Pairing.swift call site) -
        // Android's clipboard has no reason to already hold whatever the Mac just copied, since
        // Clipboard Continuity is manually-triggered on both platforms, not an automatic watcher.
        if realModifiers == [.command],
           let character = event.charactersIgnoringModifiers?.lowercased().first,
           ["a", "c", "v", "x"].contains(character) {
            guard event.type == .keyDown else { return false }
            if character == "v" {
                self.onPasteRequested()
                return true
            }
            guard let androidKeyCode = KvmKeyMapping.androidKeyCode(forLetterOrDigit: character) else { return false }
            let modifiers = KvmKeyMapping.modifierBits(shift: false, control: true, option: false, command: false)
            pendingKeyDowns[event.keyCode] = (androidKeyCode, modifiers)
            onKeyboardEvent(.key(keyCode: androidKeyCode, action: .down, modifiers: modifiers))
            return true
        }

        // Command+key (other than the Command+Arrow shortcuts already handled above, and Command+
        // A/C/V/X just above): deliberately passed through untouched, NOT forwarded - blindly
        // forwarding every Command combo risks swallowing native macOS window/app shortcuts (Command+W,
        // Command+Q, Command+Tab, ...) while Screen Share happens to be focused, which would be a
        // serious usability regression. See the Keyboard V1 report for this explicitly-documented
        // scope decision.
        guard !realModifiers.contains(.command) else { return false }

        // BRIDGEY KVM KEYBOARD V1 (arrow-key scroll, added on request): plain (no modifier) Up/Down
        // scrolls the Android view instead of navigating (DPAD) - most Android content (feeds,
        // articles, chat) only responds to DPAD_UP/DOWN if some specific widget happens to have focus,
        // whereas SCROLL (the same pipeline scrollWheel already uses) reliably scrolls whatever is on
        // screen. Left/Right are untouched (always DPAD, for text-cursor navigation). Shift/Ctrl/
        // Option+Up/Down fall through to the DPAD table below unchanged, preserving Shift+arrow
        // selection semantics.
        if realModifiers.isEmpty, event.keyCode == MacKeyCode.upArrow || event.keyCode == MacKeyCode.downArrow {
            guard event.type == .keyDown else { return true }
            captureViewBox.view?.synthesizeArrowKeyScroll(direction: event.keyCode == MacKeyCode.upArrow ? 1 : -1)
            return true
        }

        let modifiers = KvmKeyMapping.modifierBits(
            shift: realModifiers.contains(.shift),
            control: realModifiers.contains(.control),
            option: realModifiers.contains(.option),
            command: false
        )

        if let androidKeyCode = KvmKeyMapping.androidKeyCode(forSpecialMacKeyCode: event.keyCode) {
            guard event.type == .keyDown else { return false }
            pendingKeyDowns[event.keyCode] = (androidKeyCode, modifiers)
            onKeyboardEvent(.key(keyCode: androidKeyCode, action: .down, modifiers: modifiers))
            return true
        }

        if realModifiers.contains(.control) {
            // Control transforms `.characters` into ASCII control codes (Control+C -> "\u{3}"), so the
            // base letter/digit must come from `.charactersIgnoringModifiers` instead; `.lowercased()`
            // neutralizes Shift's case effect since the Android keycode is case-insensitive.
            guard event.type == .keyDown,
                  let character = event.charactersIgnoringModifiers?.lowercased().first,
                  let androidKeyCode = KvmKeyMapping.androidKeyCode(forLetterOrDigit: character) else {
                // No meaningful Android equivalent (Control+Space, Control+[, ...) - explicitly don't
                // forward or invent behavior, per this feature's own design directive. Consumed rather
                // than passed through so an unresolvable Control-chord doesn't fall through to a stray
                // native action or beep while Screen Share is focused.
                return true
            }
            pendingKeyDowns[event.keyCode] = (androidKeyCode, modifiers)
            onKeyboardEvent(.key(keyCode: androidKeyCode, action: .down, modifiers: modifiers))
            return true
        }

        // Plain typing / Shift+char / Option+char (no Control, no Command): the existing IME text path
        // - `.characters` (not `.charactersIgnoringModifiers`) so Shift/Option composition is already
        // reflected (e.g. Option+e then e -> "é"). keyDown only; keyUp has no commitText equivalent, so
        // it is silently consumed here rather than falling through.
        switch event.type {
        case .keyDown:
            guard let characters = event.characters, !characters.isEmpty else { return false }
            onKeyboardEvent(.text(characters))
            return true
        case .keyUp:
            return true
        default:
            return false
        }
    }

    /// BRIDGEY KVM KEYBOARD V1: standalone Shift/Control/Option/Command press-and-release (no other
    /// key held) arrives as `.flagsChanged`, a different NSEvent type from `.keyDown`/`.keyUp` - a bare
    /// modifier key has no `.characters`/`.keyCode`-driven "key" of its own. Detected by diffing
    /// against `previousRealModifierFlags`. Deliberate simplification: always sent as the LEFT-variant
    /// Android keycode (no left/right distinction), since NSEvent's `keyCode` for a physical modifier
    /// key is not part of the "real modifier flags" comparison this file otherwise uses everywhere
    /// else - documented as a known limitation, not a bug.
    private func handleStandaloneModifierChange(_ event: NSEvent) -> Bool {
        let real: NSEvent.ModifierFlags = [.command, .shift, .option, .control]
        let current = event.modifierFlags.intersection(real)
        let changed = current.symmetricDifference(previousRealModifierFlags)
        previousRealModifierFlags = current
        guard !changed.isEmpty else { return false }

        let modifiers = KvmKeyMapping.modifierBits(
            shift: current.contains(.shift),
            control: current.contains(.control),
            option: current.contains(.option),
            command: current.contains(.command)
        )

        var handled = false
        let flagsToAndroidKeyCode: [(NSEvent.ModifierFlags, Int32)] = [
            (.shift, AndroidKeyCode.shiftLeft),
            (.control, AndroidKeyCode.ctrlLeft),
            (.option, AndroidKeyCode.altLeft),
            (.command, AndroidKeyCode.metaLeft),
        ]
        for (flag, androidKeyCode) in flagsToAndroidKeyCode where changed.contains(flag) {
            let isDown = current.contains(flag)
            onKeyboardEvent(.key(keyCode: androidKeyCode, action: isDown ? .down : .up, modifiers: modifiers))
            handled = true
        }
        return handled
    }

    func windowWillClose(_ notification: Notification) {
        if let fullScreenKeyMonitor {
            NSEvent.removeMonitor(fullScreenKeyMonitor)
        }
        fullScreenKeyMonitor = nil
        pendingKeyDowns.removeAll()
        windowLifecycleObservers.forEach(NotificationCenter.default.removeObserver)
        windowLifecycleObservers.removeAll()
        onUserClosedWindow()
    }
}

private struct ScreenShareView: View {
    @ObservedObject var decoder: ScreenStreamDecoder
    let onPointerEvent: PointerEventHandler
    let onGestureEvent: (GestureAction) -> Void
    let onCaptureViewCreated: (KvmMouseCaptureView) -> Void

    var body: some View {
        ZStack {
            Color.black
            DisplayLayerView(
                layer: decoder.displayLayer,
                presentationMode: decoder.presentationMode,
                sourceSize: decoder.sourceSize,
                onPointerEvent: onPointerEvent,
                onGestureEvent: onGestureEvent,
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
    let onPointerEvent: PointerEventHandler
    let onGestureEvent: (GestureAction) -> Void
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
        view.onGestureEvent = onGestureEvent
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
        nsView.onGestureEvent = onGestureEvent
        nsView.needsLayout = true
    }
}

/// KVM Mouse - the real, production mouse-capture surface (not a debug/test view). First nudges the
/// raw captured point through KvmPointerCalibration.apply (the live-verified offset - deliberately
/// applied before normalization so it scales correctly with the current contentRect if the window is
/// resized, see that type's doc comment), then maps it through VideoContentGeometry.normalizedPoint
/// (the existing, already-tested inverse of contentRect) before handing it to `onPointerEvent`, which
/// the caller wires into the "input" channel (InputTransport.swift/InputEvent.pointer).
///
/// KVM Mouse V2 (see KVM_MOUSE_V2_PHASE1.md): captures right/middle button and scroll wheel, in
/// addition to Mouse v1's left button and movement. AppKit only calls mouseDown/mouseDragged/mouseUp
/// for the left/primary button - right and "other" (middle, and any further buttons) buttons have
/// their own dedicated override points, which is why these are separate methods rather than a single
/// handler branching on `event.buttonNumber`.
final class KvmMouseCaptureView: NSView {
    var sourceSize: CGSize?
    var presentationMode: VideoPresentationMode = .fit
    var onPointerEvent: PointerEventHandler?
    /// BRIDGEY KVM TOUCHPAD GESTURES V1.
    var onGestureEvent: ((GestureAction) -> Void)?
    /// Set once in makeNSView. Repositioned in `layout()`, not in SwiftUI's `updateNSView`, so the
    /// video's on-screen position and this view's own `bounds` (what pointer mapping reads) can never
    /// diverge during a resize/fullscreen transition - see DisplayLayerView's doc comment.
    var videoLayer: AVSampleBufferDisplayLayer?

    /// Top-left-origin, matching the same axis convention InputEvent.pointer's (x, y) already use
    /// elsewhere in Bridgey (0,0 = top-left) - avoids a manual Y-flip in the geometry math.
    override var isFlipped: Bool { true }

    private var trackingArea: NSTrackingArea?

    /// BRIDGEY KVM TOUCHPAD GESTURES V1: isolated from report()/mouseDown/scrollWheel etc entirely -
    /// see KvmGestureRecognizer's own doc comment for why (raw NSTouch data in, semantic action out,
    /// no pointer/coordinate logic involved at all). Owned by this view because raw multitouch data
    /// can only be delivered to a view that opts in via `allowedTouchTypes` (set in `init` below) and
    /// overrides `touchesBegan/Moved/Ended/Cancelled` - there is no window- or app-level monitor for
    /// indirect (trackpad) touches, unlike mouse/keyboard events.
    private let gestureRecognizer = KvmGestureRecognizer()

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        setUpGestureRecognition()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setUpGestureRecognition()
    }

    private func setUpGestureRecognition() {
        // .indirect = trackpad (as opposed to .direct, a touchscreen - Macs don't have one). This is
        // strictly ADDITIVE: AppKit delivers touchesBegan/Moved/Ended/Cancelled as a completely
        // separate, parallel callback stream from mouseDown/Dragged/Up and scrollWheel - enabling it
        // does not change how, or whether, any of those existing callbacks fire (verified against
        // Apple's documented touch-handling contract; still explicitly runtime-verified for 2-finger
        // scroll specifically, since that's the one existing behavior a regression here would be worst
        // for - see the KVM_TOUCHPAD_GESTURES_V1 runtime report).
        allowedTouchTypes = [.indirect]
        gestureRecognizer.onGesture = { [weak self] action in
            self?.onGestureEvent?(action)
        }
    }

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

    /// KVM Mouse V2: right button -> PointerButton.right. RIGHT CLICK -> LONG PRESS / CONTEXT
    /// GESTURE is an experimental Android-side interpretation (see BridgeyAccessibilityService) -
    /// AccessibilityService's dispatchGesture has no real mouse-button semantic, only touch. This
    /// side only captures and transports the fact that the RIGHT button was pressed/dragged/released;
    /// what Android does with that identity is a separate, explicitly-labeled decision.
    override func rightMouseDown(with event: NSEvent) { report(.down, event, button: .right) }
    override func rightMouseDragged(with event: NSEvent) { report(.move, event, button: .right) }
    override func rightMouseUp(with event: NSEvent) { report(.up, event, button: .right) }

    /// KVM Mouse V2: middle button only (buttonNumber == 2) - AppKit's otherMouseDown/Dragged/Up fire
    /// for every button beyond left/right (2, 3, 4, ...), and only button 2 is the conventional
    /// "middle" button this phase models. Transport/model support only: Android has no scheduled
    /// behavior for this yet (see KvmInputInjector - logs and no-ops, exactly like SCROLL previously
    /// did in Mouse v1).
    override func otherMouseDown(with event: NSEvent) {
        guard event.buttonNumber == 2 else { return }
        report(.down, event, button: .middle)
    }
    override func otherMouseDragged(with event: NSEvent) {
        guard event.buttonNumber == 2 else { return }
        report(.move, event, button: .middle)
    }
    override func otherMouseUp(with event: NSEvent) {
        guard event.buttonNumber == 2 else { return }
        report(.up, event, button: .middle)
    }

    /// KVM Mouse V2 scroll data path (KVM_MOUSE_V2_PHASE1.md phase 4 - transport only, no Android
    /// scroll synthesis yet). Uses `scrollingDeltaX`/`scrollingDeltaY` (the modern, precise-scroll
    /// API), not the legacy line-based `deltaX`/`deltaY` - and passes them through with NO sign
    /// flip. Per Apple's own documented contract for `isDirectionInvertedFromDevice` (AppKit/NSEvent):
    /// "the user may choose to change the scrolling behavior such that it feels like they are moving
    /// the content instead of the scroll bar. To accomplish this, deltaX/Y and scrollingDeltaX/Y are
    /// automatically inverted for [scroll wheel] events according to the user's preferences." AppKit
    /// has therefore already applied the user's natural-vs-traditional scrolling preference to this
    /// value before this method ever sees it - re-flipping it here would silently fight that system
    /// preference. Not applied: the "when hasPreciseScrollingDeltas is NO, multiply by line/row
    /// height" normalization Apple also documents for scrollingDeltaX/Y - deliberately deferred, see
    /// the phase 4 report (the correct multiplier depends on how Android ultimately consumes the
    /// delta, which is explicitly not decided yet).
    override func scrollWheel(with event: NSEvent) {
        let point = convert(event.locationInWindow, from: nil)
        let calibrated = KvmPointerCalibration.apply(point, sourceSize: sourceSize)
        guard let normalized = VideoContentGeometry.normalizedPoint(calibrated, sourceSize: sourceSize, mode: presentationMode, in: bounds) else {
            return
        }
        onPointerEvent?(.scroll, Float(normalized.x), Float(normalized.y), .left, Float(event.scrollingDeltaX), Float(event.scrollingDeltaY))
    }

    /// BRIDGEY KVM KEYBOARD V1 (arrow-key scroll): a Mac trackpad's `scrollingDeltaY` per real
    /// two-finger "tick" is roughly this size - reused here so a single arrow-key press feels like one
    /// natural scroll tick, and holding the key (which macOS auto-repeats as keyDown) feels like a
    /// continuous two-finger scroll. Sign is a best guess (untested on real hardware, unlike the rest
    /// of this file's scroll code) - flip this constant's sign if Up ends up scrolling the wrong way.
    private static let arrowKeyScrollTickPoints: Float = 10

    /// BRIDGEY KVM KEYBOARD V1: called by ScreenShareWindowController when a plain (no modifier)
    /// Up/Down arrow is pressed - anchored at the CURRENT mouse position, exactly like `scrollWheel`
    /// above, so it feeds the identical SCROLL pointer pipeline (KvmInputInjector's coalescing/lift
    /// logic) real trackpad scrolling already uses, rather than inventing a second scroll mechanism.
    /// `direction`: `+1` for Up, `-1` for Down.
    func synthesizeArrowKeyScroll(direction: Float) {
        let mouseLocation = window?.mouseLocationOutsideOfEventStream ?? NSPoint(x: bounds.midX, y: bounds.midY)
        let point = convert(mouseLocation, from: nil)
        let calibrated = KvmPointerCalibration.apply(point, sourceSize: sourceSize)
        let normalized = VideoContentGeometry.normalizedPoint(calibrated, sourceSize: sourceSize, mode: presentationMode, in: bounds)
            ?? CGPoint(x: 0.5, y: 0.5)
        onPointerEvent?(.scroll, Float(normalized.x), Float(normalized.y), .left, 0, direction * Self.arrowKeyScrollTickPoints)
    }

    private func report(_ action: PointerAction, _ event: NSEvent, button: PointerButton = .left) {
        let point = convert(event.locationInWindow, from: nil)
        let calibrated = KvmPointerCalibration.apply(point, sourceSize: sourceSize)
        guard let normalized = VideoContentGeometry.normalizedPoint(calibrated, sourceSize: sourceSize, mode: presentationMode, in: bounds) else {
            return
        }
        onPointerEvent?(action, Float(normalized.x), Float(normalized.y), button, 0, 0)
    }

    // BRIDGEY KVM TOUCHPAD GESTURES V1: purely a thin forwarding layer to gestureRecognizer - no
    // gesture-recognition logic lives here, and nothing above this point (report/scrollWheel/mouseDown
    // etc) is touched by or aware of these overrides. `.touching` is NSTouch's phase mask for "still in
    // contact right now" (began+moved+stationary, excludes ended/cancelled) - querying it fresh on
    // every callback, rather than trying to incrementally track individual NSTouch identities, is what
    // gives a simple, always-correct "how many fingers right now" count for the recognizer.
    override func touchesBegan(with event: NSEvent) { forwardTouches(event) }
    override func touchesMoved(with event: NSEvent) { forwardTouches(event) }

    override func touchesEnded(with event: NSEvent) {
        forwardTouches(event)
        if event.touches(matching: .touching, in: self).isEmpty {
            gestureRecognizer.touchesEnded()
        }
    }

    override func touchesCancelled(with event: NSEvent) {
        gestureRecognizer.touchesCancelled()
    }

    private func forwardTouches(_ event: NSEvent) {
        let touching = event.touches(matching: .touching, in: self)
        guard !touching.isEmpty else { return }
        // KvmGestureRecognizer currently judges 2/3/4-finger gestures purely by aggregate centroid, so
        // the per-touch id isn't read for gesture recognition - it's still attached (via NSTouch's
        // stable `identity`, wrapped as an ObjectIdentifier) since KvmTouchPoint carries it regardless.
        let touches = touching.map { touch in
            KvmTouchPoint(id: AnyHashable(ObjectIdentifier(touch.identity as AnyObject)), position: touch.normalizedPosition)
        }
        gestureRecognizer.touchesChanged(touches: touches, timestamp: event.timestamp)
    }
}
