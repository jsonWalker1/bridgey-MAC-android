import CoreGraphics
import Foundation

/// BRIDGEY KVM TOUCHPAD GESTURES V1: centralized, named thresholds - see the type's doc comment for
/// why each exists. All distances are in NSTouch's own normalized (0...1) trackpad-surface space, so
/// they mean the same physical fraction of a swipe regardless of the specific trackpad's hardware size.
enum KvmGestureThresholds {
    /// BRIDGEY 2F REVERT TO ORIGINAL CONCEPT (2026-09-23, third round of real-hardware feedback): the
    /// anchor+zone technique (one finger held still, the other swipes) was tried and tuned extensively
    /// but real-hardware captures kept finding new ways real hands don't fit it - both fingers moving
    /// together (the natural instinct), the mover's NSTouch identity changing mid-swipe (tolerated,
    /// see git history), and finally the finger count briefly dropping to 1 mid-gesture (a momentary
    /// lift the strict same-count invariant can't survive). Reverted to the ORIGINAL, simpler concept:
    /// a plain 2-finger swipe, both fingers moving together, judged purely by distance and straightness
    /// on the aggregate centroid - exactly the same technique already used for 3F/4F below. This was
    /// the very first discriminator tried (see prior git history) and was rejected then for occasionally
    /// misfiring against ordinary scrolling; `twoFingerMinDistance` is set deliberately large (larger
    /// than the 3F/4F equivalents) specifically to make that misfire rarer, since horizontal 2-finger
    /// motion is far more commonly just scrolling than vertical 3/4-finger motion is anything else.
    ///
    /// BRIDGEY 2F DISTANCE TUNING (2026-09-23, real-hardware feedback): 0.25 rejected the user's actual
    /// natural BACK swipe (roughly trackpad's right third to its middle, ~0.17). Lowered to 0.17 to
    /// match. If real usage shows this now misfires against scroll, tighten back up (or add
    /// `twoFingerMaxPerpendicularDeviation` strictness) before reaching for a different technique.
    static let twoFingerMinDistance: CGFloat = 0.17
    /// How far the 2-finger centroid may drift on the vertical axis before a swipe is rejected as too
    /// diagonal to confidently call a clean horizontal BACK/FORWARD swipe.
    static let twoFingerMaxPerpendicularDeviation: CGFloat = 0.05
    /// Minimum straight-line centroid displacement (dominant axis) for a 3-finger gesture to count as
    /// a deliberate swipe rather than incidental drift while resting fingers on the trackpad.
    static let threeFingerMinDistance: CGFloat = 0.15
    /// Same idea as `threeFingerMinDistance`, kept as its own constant (not shared) since 4-finger
    /// gestures may need independent tuning later without touching 3-finger behavior.
    static let fourFingerMinDistance: CGFloat = 0.15
    /// A gesture (from first finger down to last finger up) taking longer than this is treated as
    /// deliberately NOT a quick swipe (e.g. fingers just resting/repositioning) and is cancelled
    /// rather than evaluated - "ambiguous" per the fail-safe policy, not forced into a direction.
    static let maxGestureDuration: TimeInterval = 0.6
    /// How far the centroid may drift on the NON-dominant axis before a gesture is rejected as too
    /// diagonal to confidently call horizontal-vs-vertical. Used by the 3-finger and 4-finger (vertical)
    /// cases; 2-finger has its own constant above.
    static let maxPerpendicularDeviation: CGFloat = 0.08
}

/// BRIDGEY KVM TOUCHPAD GESTURES V1. The semantic action a recognized gesture maps to - this is
/// exactly the wire's `GestureAction` (InputTransport.swift), reused directly rather than duplicated,
/// so there is only ever one enum to keep in sync between the recognizer and the transport.
///
/// (See InputTransport.swift for `GestureAction` itself - it lives there, not here, since it is
/// primarily a wire-protocol type that the recognizer happens to also use as its output.)

/// BRIDGEY KVM TOUCHPAD GESTURES V1: explicit state machine, exactly as specified - IDLE (no gesture
/// in progress) -> TRACKING (a 2/3/4-finger touch is being watched) -> TRIGGERED (a semantic gesture
/// was emitted, terminal until the next touch session) or CANCELLED (ruled out, terminal until the
/// next touch session).
enum KvmGestureState: Equatable {
    case idle
    case tracking
    case triggered
    case cancelled
}

/// One currently-touching finger: a stable identity for correlating the SAME physical finger across
/// callbacks (mirrors NSTouch.identity - see KvmMouseCaptureView.forwardTouches, the only caller that
/// constructs these), plus its current normalized trackpad position. `id` is currently unused by this
/// recognizer (2/3/4-finger gestures are all judged by aggregate centroid, not per-finger identity) but
/// is kept on the type since `forwardTouches` naturally has it available from real NSTouch data, and a
/// future per-finger technique may need it again.
struct KvmTouchPoint {
    let id: AnyHashable
    let position: CGPoint

    init(id: AnyHashable, position: CGPoint) {
        self.id = id
        self.position = position
    }
}

/// BRIDGEY KVM TOUCHPAD GESTURES V1. Isolated gesture-recognition layer - deliberately NOT part of
/// KvmMouseCaptureView.report()/the existing pointer handler (see the file's own doc comment on how
/// it's wired in). This class's public surface takes only primitive values (a list of touch id/position
/// pairs, a timestamp) rather than NSTouch/NSEvent directly, so its logic is fully unit-testable without
/// any AppKit/hardware dependency - the same rationale KvmCoordinateMapper and ScrollGestureAccumulator
/// already use elsewhere in this codebase.
///
/// Recognizes 2-, 3-, and 4-finger touch sessions; 1-finger touches are not this class's concern at
/// all. 2-finger tracking is a genuine overlap with `scrollWheel` (AppKit delivers touchesBegan/Moved/
/// Ended and scrollWheel as separate, parallel callback streams for the SAME 2-finger contact, so
/// `scrollWheel` keeps firing completely unmodified regardless of what this class does - but nothing
/// stops both from reacting to the same physical motion).
///
/// BRIDGEY 2F REVERT (2026-09-23): a more elaborate "one finger anchored still, the other swipes"
/// technique was tried and tuned across several rounds of real-hardware feedback and consistently
/// failed for reasons rooted in hand mechanics, not tuning - see `KvmGestureThresholds`'s doc comment.
/// All three of 2F/3F/4F now use the exact same technique: a plain aggregate-centroid swipe, judged by
/// distance and straightness, dominant-axis direction decides the outcome. Only the per-count
/// thresholds (and which axis is "dominant" - horizontal for 2F, vertical for 3F/4F) differ.
///
/// A touch session's finger count is fixed at its start: if the number of touching fingers changes
/// mid-gesture, the whole gesture is cancelled outright (not re-classified) - the fail-safe policy is
/// "ambiguous = ignore", never "guess which of two gestures was intended".
final class KvmGestureRecognizer {
    private(set) var state: KvmGestureState = .idle

    /// Fires at most once per completed touch session (see `touchesEnded`), carrying a fully resolved
    /// semantic action. Never fires for a session that ends up `.cancelled`.
    var onGesture: ((GestureAction) -> Void)?

    private var trackingFingerCount = 0
    private var startTimestamp: TimeInterval = 0
    private var startCentroid = CGPoint.zero
    private var lastCentroid = CGPoint.zero

    /// Call whenever the set of currently-touching fingers is known (on touchesBegan/Moved, and any
    /// other point where a fresh reading is available). `touches` must reflect every finger touching
    /// the trackpad RIGHT NOW (not a delta) - this is how the state machine can tell a stable session
    /// apart from one that just gained or lost a finger.
    func touchesChanged(touches: [KvmTouchPoint], timestamp: TimeInterval) {
        let fingerCount = touches.count
        switch state {
        case .idle:
            // 0/1-finger touches are simply not this recognizer's concern.
            guard fingerCount == 2 || fingerCount == 3 || fingerCount == 4 else { return }
            state = .tracking
            trackingFingerCount = fingerCount
            startTimestamp = timestamp
            let centroid = KvmGestureRecognizer.centroid(of: touches)
            startCentroid = centroid
            lastCentroid = centroid
        case .tracking:
            guard fingerCount == trackingFingerCount else {
                state = .cancelled
                return
            }
            guard timestamp - startTimestamp <= KvmGestureThresholds.maxGestureDuration else {
                state = .cancelled
                return
            }
            lastCentroid = KvmGestureRecognizer.centroid(of: touches)
        case .triggered, .cancelled:
            // Terminal until touchesEnded/touchesCancelled resets back to .idle for the next session -
            // deliberately ignores any further touch data for a session already resolved one way or
            // the other, so a gesture can never fire twice for the same physical touch-down.
            break
        }
    }

    /// Call once every finger has been lifted from a tracked session (finger count reaches 0 via
    /// normal `.ended` phases). Evaluates the completed gesture exactly once, emits `onGesture` if (and
    /// only if) it resolves to a clear, unambiguous direction, then resets to `.idle` for the next
    /// session. A no-op (besides resetting to `.idle`) if no session was being tracked.
    func touchesEnded() {
        guard state == .tracking else {
            state = .idle
            return
        }
        if let action = resolveGesture() {
            state = .triggered
            onGesture?(action)
        } else {
            state = .cancelled
        }
        state = .idle
    }

    /// Call when the system cancels an in-progress touch session (e.g. the app loses focus mid-touch).
    /// Never evaluates or emits - a cancelled touch session carries no reliable gesture information.
    func touchesCancelled() {
        state = .idle
    }

    private static func centroid(of touches: [KvmTouchPoint]) -> CGPoint {
        let sum = touches.reduce(CGPoint.zero) { partial, touch in
            CGPoint(x: partial.x + touch.position.x, y: partial.y + touch.position.y)
        }
        let count = CGFloat(touches.count)
        return CGPoint(x: sum.x / count, y: sum.y / count)
    }

    /// BRIDGEY FINAL MAPPING (2026-09-23): exactly four gestures, nothing else -
    ///   2F swipe LEFT -> BACK, 2F swipe RIGHT -> FORWARD, 3F DOWN -> NOTIFICATIONS, 4F DOWN -> RECENTS.
    /// Explicitly NOT mapped, on purpose: 3F UP/LEFT/RIGHT, 4F UP/LEFT/RIGHT - the vertical "up"
    /// directions were deliberately dropped because they risk colliding with native macOS trackpad
    /// gestures (Mission Control on 3F up, App Exposé on 4F up). `.home` (GestureAction) is therefore
    /// never produced by this recognizer any more - the wire case still exists (removing it would be an
    /// unrelated protocol change) but nothing here emits it.
    private func resolveGesture() -> GestureAction? {
        let dx = lastCentroid.x - startCentroid.x
        let dy = lastCentroid.y - startCentroid.y
        let absDx = abs(dx)
        let absDy = abs(dy)
        if trackingFingerCount == 2 {
            guard absDx > absDy else { return nil } // 2F: only a horizontal swipe is ever a gesture.
            guard absDx >= KvmGestureThresholds.twoFingerMinDistance,
                  absDy <= KvmGestureThresholds.twoFingerMaxPerpendicularDeviation else { return nil }
            return dx < 0 ? .back : .forward
        }

        guard absDy > absDx else { return nil } // 3F/4F: only a vertical swipe is ever a gesture.
        let minDistance = trackingFingerCount == 4
            ? KvmGestureThresholds.fourFingerMinDistance
            : KvmGestureThresholds.threeFingerMinDistance
        guard absDy >= minDistance, absDx <= KvmGestureThresholds.maxPerpendicularDeviation else { return nil }
        // NSTouch.normalizedPosition: origin bottom-left, Y increases upward - a physical downward
        // swipe is therefore a NEGATIVE dy.
        guard dy < 0 else { return nil } // swipe UP: not in scope, deliberately.
        return trackingFingerCount == 3 ? .notifications : .recents
    }
}
