# Bridgey KVM Touchpad Gestures — Status (2026-09-23)

## Status

**In progress, awaiting real-hardware confirmation of the current (3rd) 2-finger discriminator.**
Pointer move, left/right click, drag, and 2-finger scroll are all confirmed working end-to-end on
real hardware (S23 Ultra) after fixing an unrelated Android Accessibility Service permission issue
(see "Known gotcha" below). 3-finger DOWN and 4-finger DOWN gestures are implemented and unit-tested
but have **not yet been confirmed on real hardware** — no GESTURE frame of any kind besides BACK has
ever been observed reaching Android in this session's testing. The 2-finger BACK/FORWARD gesture has
gone through three different discriminator designs this session (see "Design history" below); the
current one (anchor+zone) has been built, unit-tested, and deployed, but **the user has not yet
reported back real-hardware results for it** — this is the next thing to verify.

Nothing in this feature has been committed (`git status` still shows it all as local changes/untracked
files — standing instruction from the user: do not commit).

## Pipeline

```text
macOS Screen Share window (KvmMouseCaptureView)
  -> NSTouch (touchesBegan/Moved/Ended/Cancelled) - parallel, independent of mouseDown/scrollWheel
  -> KvmGestureRecognizer (pure, unit-testable, no AppKit dependency)
  -> GestureAction (semantic action only - never coordinates/velocity/duration/raw touches)
  -> existing authenticated/encrypted "input" TCP channel (InputEvent.gesture, frame type 20)
  -> Android TcpInputTransport -> KvmInputInjector.handleGesture
  -> BridgeyAccessibilityService.handleGesture -> performGlobalAction(...)
```

Deliberately NOT touched by any of this: `KvmMouseCaptureView.report()`/`mouseDown`/`mouseDragged`/
`mouseUp`/`scrollWheel`, `KvmPointerCalibration`, `KvmCoordinateMapper`, `VideoContentGeometry`, the
frozen KVM channel-negotiation/security state machine (`ChannelLifecycle`), or the wire frame envelope
format. AppKit delivers touch tracking and mouse/scroll events as separate, parallel callback streams
for the same physical trackpad contact — enabling touch tracking does not change whether or how
`scrollWheel`/`mouseDown` fire.

## Current gesture mapping (final target, per explicit user spec)

| Gesture | Action | Android target |
|---|---|---|
| 2-finger: anchor finger held on the LEFT third of the trackpad, second finger swipes further left | BACK | `GLOBAL_ACTION_BACK` (1) |
| 2-finger: anchor finger held on the RIGHT third of the trackpad, second finger swipes further right | FORWARD | **No Android equivalent** — confirmed against the real `android.jar` (`javap -p -constants`), no `GLOBAL_ACTION_FORWARD` exists. Detected and logged as unsupported (`BridgeyA11y`), never invented/approximated. |
| 3-finger swipe DOWN | NOTIFICATIONS | `GLOBAL_ACTION_NOTIFICATIONS` (4) |
| 4-finger swipe DOWN | RECENTS | `GLOBAL_ACTION_RECENTS` (3) |

Explicitly and deliberately **not** mapped: 3F UP, 3F LEFT/RIGHT (moved to 2F), 4F UP, 4F LEFT/RIGHT.
UP directions were dropped specifically to avoid colliding with native macOS trackpad gestures
(Mission Control on 3F up, App Exposé on 4F up) — this was an explicit user requirement, not a
technical limitation. `GestureAction.home` still exists in the wire enum (removing it would be an
unrelated protocol change) but `KvmGestureRecognizer` never produces it any more.

## Design history for the 2-finger gesture (read before changing it again)

Three discriminators were tried, in order, each replacing the previous one after real-hardware
feedback showed it wasn't good enough. **Do not re-implement #1 or #2 without a very good reason** —
both were tried and rejected by the user based on actual trackpad testing, not simulation.

1. **Distance + straightness only** (large min-distance, tight perpendicular-deviation, on the
   aggregate 2-finger centroid). Rejected: real 2-finger scrolling could still satisfy "large, straight,
   horizontal" often enough to misfire BACK/FORWARD.
2. **Hand-posture gate** ("stacked" front-to-back fingers = gesture candidate, "side-by-side" = normal
   scroll posture, classified from the two individual touch points at touch-down). Implemented, unit
   tested, deployed. User feedback: still "zaměňuje se se scrollem" (still gets confused with scroll) —
   rejected on real hardware.
3. **Anchor + zone (current)**: one finger must stay almost still (the "anchor"), and the anchor's
   *starting* x-position must be in the trackpad's left third (BACK-eligible) or right third
   (FORWARD-eligible); the *other* finger (the "mover") must then swipe horizontally, in the matching
   direction, past a minimum distance. Ordinary 2-finger scrolling moves both fingers together and
   never produces a valid "almost still" anchor, so it's rejected before zone or direction are even
   checked. This requires tracking **individual finger identity** across callbacks (not just an
   aggregate centroid) — see `KvmTouchPoint` (id + position) and `KvmMouseCaptureView.forwardTouches`,
   which derives a stable per-finger id via `ObjectIdentifier(touch.identity as AnyObject)` (NSTouch's
   `identity` is documented to stay the same retained object for the life of one physical touch).
   **This is the version currently deployed and awaiting real-hardware confirmation.**

3-finger and 4-finger gestures were never affected by any of this — they always used (and still use)
the simple aggregate centroid, which has not shown any false-positive/false-negative problems (they
just haven't been exercised much yet on real hardware).

## Thresholds (`KvmGestureThresholds` in `KvmGestureRecognizer.swift`)

| Constant | Value | Meaning |
|---|---|---|
| `twoFingerAnchorMaxDrift` | 0.05 | Max an anchor finger may drift and still count as "held still". |
| `twoFingerLeftZoneMaxX` | 0.35 | Anchor start x ≤ this → BACK-eligible. |
| `twoFingerRightZoneMinX` | 0.65 | Anchor start x ≥ this → FORWARD-eligible. Between the two zones = ambiguous, ignored. |
| `twoFingerMoverMinDistance` | 0.15 | Min horizontal displacement for the mover finger. |
| `twoFingerMoverMaxPerpendicularDeviation` | 0.06 | Max vertical drift for the mover's swipe. |
| `threeFingerMinDistance` / `fourFingerMinDistance` | 0.15 | Min vertical displacement for 3F/4F swipes. |
| `maxGestureDuration` | 0.6s | Touch-down to touch-up longer than this cancels (not evaluated). |
| `maxPerpendicularDeviation` | 0.08 | 3F/4F swipe straightness gate. |

All distances are in `NSTouch.normalizedPosition` space (0...1, origin bottom-left, X right-positive,
Y up-positive — independent of `KvmMouseCaptureView.isFlipped`, which only affects mouse-pointer
mapping, not NSTouch's own coordinate convention). A physical downward swipe is therefore a **negative**
dy; a physical leftward swipe is a **negative** dx.

## Protocol

- New `InputFrameType.gesture` / `InputFrameType.GESTURE` = `20` (Mac `VideoFrameFraming.swift`,
  Android `VideoFrameFraming.kt`), added to both platforms' `all`/`ALL` accepted-type sets.
- New `InputEvent.gesture(action: GestureAction)` (Swift) / `InputEvent.Gesture(val action:
  GestureAction)` (Kotlin), payload = **1 byte**, the action's raw ordinal. `GestureAction` declaration
  order is frozen and must match exactly on both platforms (ordinal = wire byte): `BACK=0, FORWARD=1,
  HOME=2, NOTIFICATIONS=3, RECENTS=4`.
- Reuses the existing authenticated/encrypted "input" TCP channel — no new socket, no new trust
  mechanism. `TCPInputTransport`/`TcpInputTransport`'s MOVE-only coalescing check is a type/pattern
  match (`if case .pointer(_, ..., action: .move) = event`), so the new `.gesture` case is automatically
  never-coalesced without needing any change to either transport file.

## Files changed (all uncommitted)

- `macos/Sources/BridgeyMac/KvmGestureRecognizer.swift` — **new**. The isolated state machine.
- `macos/Tests/BridgeyMacTests/KvmGestureRecognizerTests.swift` — **new**. 24 tests.
- `macos/Sources/BridgeyMac/InputTransport.swift` — `GestureAction` enum, `.gesture` case, codec.
- `macos/Sources/BridgeyMac/VideoFrameFraming.swift` — frame type 20.
- `macos/Sources/BridgeyMac/ScreenShareWindow.swift` — `onGestureEvent` threaded through
  `ScreenShareWindowController` → `ScreenShareView` → `DisplayLayerView` → `KvmMouseCaptureView`;
  `touchesBegan/Moved/Ended/Cancelled` overrides + `forwardTouches` (thin forwarding only).
- `macos/Sources/BridgeyMac/Pairing.swift` — wires `onGestureEvent` to `videoChannel.offerInput` +
  `sendInputEvent(.gesture(...))`, mirroring the existing `onPointerEvent` pattern.
- `android/app/src/main/java/dev/bridgey/android/InputTransport.kt` — `GestureAction` enum, `.Gesture`
  case, codec.
- `android/app/src/main/java/dev/bridgey/android/VideoFrameFraming.kt` — frame type 20.
- `android/app/src/main/java/dev/bridgey/android/KvmInputInjector.kt` — `handleGesture` dispatch.
- `android/app/src/main/java/dev/bridgey/android/BridgeyAccessibilityService.kt` — `handleGesture`:
  maps `GestureAction` → `GLOBAL_ACTION_*`, logs FORWARD as unsupported.
- `android/app/src/test/java/dev/bridgey/android/InputEventCodecGestureTest.kt` — **new**. Wire
  round-trip tests.
- `macos/Sources/BridgeyMac/VideoChannelController.swift` — temporary debug logging only (see below).
- `macos/Sources/BridgeyMac/TCPInputTransport.swift` — temporary debug logging only (see below).
- `android/app/src/main/java/dev/bridgey/android/TcpInputTransport.kt` — temporary debug logging only
  (see below).

## ⚠️ Temporary debug logging still in place — remove before final commit

Added during a regression-debugging session (input appeared completely dead; root cause turned out to
be an Android Accessibility Service permission issue, not a code bug — see below). All lines are marked
`TEMPORARY BRIDGEY-INPUT-DEBUG LOGGING - DO NOT COMMIT` in-place:

- `macos/Sources/BridgeyMac/ScreenShareWindow.swift` — `NSLog` in `report()`/`scrollWheel()` when
  `normalizedPoint` returns nil.
- `macos/Sources/BridgeyMac/Pairing.swift` — `NSLog` of action/outcome in `onPointerEvent`/
  `onGestureEvent` (skips `.move` to avoid flooding).
- `macos/Sources/BridgeyMac/VideoChannelController.swift` — `NSLog` in `teardownInput` (shows the
  channel state transition).
- `macos/Sources/BridgeyMac/TCPInputTransport.swift` — `NSLog` in `fail()`.
- `android/app/src/main/java/dev/bridgey/android/TcpInputTransport.kt` — **`System.err.println`, not
  `android.util.Log`** in `readLoop` (logs every decoded event) and `fail()`. This is deliberate: this
  class's own unit tests (`TcpInputTransportTest`) run as plain JVM tests with no Android framework
  mock, and `android.util.Log.*` throws there (`Method d in android.util.Log not mocked`) — using
  `Log.*` here broke 3 previously-passing tests the first time; switched to `System.err.println`, which
  works in both a plain JVM test and on-device (still visible via `adb logcat`, tagged `System.err`).

**Known dead end, don't re-debug it:** the Mac-side `NSLog` calls above were confirmed, via `log show
--predicate 'eventMessage CONTAINS "BRIDGEY-INPUT-DEBUG"'` across the whole system log, to **never
appear at all** in the unified log for this running process — despite the exact same code path
demonstrably executing correctly (proven independently via the Android-side `System.err` receive log
showing hundreds of correctly-decoded events). This looks like some kind of unified-logging
capture/attribution quirk specific to how this app is launched (`open .../Bridgey.app`), not a real bug
in the gesture code. **The Android-side `System.err`/`adb logcat` log is the reliable diagnostic
channel for this pipeline** — don't rely on Mac-side `log stream`/`log show` for these frames.

## ⚠️ Known gotcha: reinstalling the Android APK silently disables Accessibility Service

This cost most of one debugging session. `adb install -r` on this device (Samsung S23 Ultra, One UI)
appears to silently drop Bridgey's entry from `Settings.Secure.enabled_accessibility_services` as a
security measure — the app still shows as "installed" and the toggle still exists, but
`BridgeyAccessibilityService.instance` goes `null` and **all** input injection (click, drag, scroll,
gestures alike) silently does nothing, even though the transport/decode pipeline works perfectly and
looks completely healthy in the logs. Confirmed symptom:
`KvmInputInjector` logs `"KVM input received but Bridgey's Accessibility Service is not enabled"`
(only once, due to a dedup flag).

**Check first, before assuming a code regression:**
```sh
adb shell settings get secure enabled_accessibility_services
# must contain: dev.bridgey.android/dev.bridgey.android.BridgeyAccessibilityService
```
If it's missing, re-enable it from the device: Settings → Accessibility → Installed apps → Bridgey →
toggle on. (An attempt to fix this via `adb shell settings put secure
enabled_accessibility_services ...` was blocked by this environment's permission classifier as a
sensitive device-settings write — expect the same and ask the user to toggle it manually rather than
trying to script around the block.)

**Practical implication:** during this kind of iterative debugging, prefer redeploying **only the Mac
app** (`./build-app.sh` + kill/relaunch) when the Android source hasn't actually changed — check
`git status`/the gradle output (`UP-TO-DATE` on `compileDebugKotlin` means nothing changed) before
running `adb install -r`, to avoid re-triggering this every cycle.

## Redeploy cheat sheet (this repo's actual working commands this session)

```sh
# Mac: JAVA_HOME is NOT needed for swift build/test, only for gradle.
cd macos && swift build && swift test          # build + full suite
./build-app.sh                                  # produces .build/debug/Bridgey.app
# .build/debug/Bridgey.app and .build/out/Products/Debug/Bridgey.app are the SAME underlying build
# output (confirmed via matching MD5) - either path can be used interchangeably.
pgrep -fl "BridgeyMac$"                          # find the running PID
kill <pid>; open .build/debug/Bridgey.app        # relaunch with the new build
# always verify: md5 the running process's binary path against .build/debug/.../BridgeyMac

# Android: JAVA_HOME must point at a real JDK - Android Studio's bundled JBR works when there's no
# system java: export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
# Only reinstall if the gradle output shows compileDebugKotlin actually ran (not UP-TO-DATE):
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Live diagnostics (both sides, simultaneously, into files so they can be grepped after a manual test):
adb logcat -c && adb logcat -v time > android_live.log 2>&1 &
log stream --predicate 'process == "BridgeyMac"' --style compact > mac_live.log 2>&1 &
# then ask the user to perform the test on real hardware, then grep:
grep "BRIDGEY-INPUT-DEBUG" android_live.log
```

## Verification so far this session

- Swift: `swift test` → **194/194 pass** (full suite, includes 24 gesture-recognizer tests for the
  current anchor+zone design).
- Kotlin: `./gradlew :app:testDebugUnitTest` → all pass (includes `InputEventCodecGestureTest`, 6 tests,
  wire round-trip + ordinal-pinning against the frozen Swift rawValue order).
- Real hardware (S23 Ultra) confirmed via live log capture: cursor move, left click/drag, 2-finger
  scroll all working correctly end-to-end (hundreds of correctly-decoded `Pointer` events, injected
  successfully, no Accessibility warnings) after the Accessibility Service permission issue above was
  fixed.
- Real hardware: 2-finger BACK gesture fired successfully 7/7 times under the **posture-gate** design
  (now superseded by anchor+zone) — proves the underlying touch-capture-to-injection pipeline works;
  does not by itself validate the current anchor+zone logic.
- **Not yet confirmed on real hardware:** FORWARD, 3F DOWN (NOTIFICATIONS), 4F DOWN (RECENTS), and the
  current anchor+zone BACK/FORWARD design specifically. This is the immediate next step — rebuild
  already deployed, waiting on the user's test pass.

## Open items / next steps

1. Get real-hardware confirmation of the anchor+zone 2-finger technique (BACK and FORWARD both), and
   of 3F DOWN / 4F DOWN, which have never been exercised on hardware yet.
2. If the anchor+zone tuning needs adjustment (zone width, anchor drift tolerance, mover distance),
   change only the named constants in `KvmGestureThresholds` — do not change the underlying technique
   again without new real-hardware evidence that anchor+zone itself (not just its tuning) is wrong.
3. Once the feature is confirmed stable, remove all the `TEMPORARY BRIDGEY-INPUT-DEBUG LOGGING - DO NOT
   COMMIT` lines listed above before any commit.
4. Standing instruction from the user: **do not commit** this work until explicitly told to.
