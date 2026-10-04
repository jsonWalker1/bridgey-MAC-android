# Features (addons)

A feature is a **package + platform-independent contract**: its protocol payloads, its state
(including its Peer State), its platform integrations and its UI. It depends on Core APIs only —
never on sockets, session keys, framing or reconnect — and on other features only through an
explicit, documented API. Core never knows a feature exists. All features are compiled in and
composed at startup (no plugin loader, no DI framework).

Layout per feature: `README.md`, `PROTOCOL.md` (payloads), `android/` and `macos/` (sources),
`tests/android/` and `tests/macos/` (unit tests). The builds pick these folders up automatically:
the root `Package.swift` adds every `features/*/macos` and `features/*/tests/macos`, and
`android/app/build.gradle.kts` adds every `features/*/android` and `features/*/tests/android`.
Kotlin files keep the package `dev.bridgey.android` while they move, so the move changes no visibility.

## Adding a feature
1. Define its message kinds and payloads (`PROTOCOL.md`); register a permission key.
2. Use Core messaging (`deviceId`-addressed), never a session object.
3. Keep remote state keyed by `deviceId`; drop it on Lifecycle events.
4. Put Android/macOS integrations in the feature's own folders.

## Features and where they live today

Moved features live in `features/<name>/`; the others are still in the platform folders.
| Feature | Android | macOS | Notes |
|---|---|---|---|
| [clipboard](clipboard/README.md) | `clipboard/android/` | `clipboard/macos/` | moved; handlers still in the coordinators; KVM paste depends on it |
| [files](files/README.md) | `files/android/` | `files/macos/` | moved (UI and notifier); transfer engines still in the coordinators |
| [photos](photos/README.md) | `photos/android/` | `photos/macos/` | moved; uses the Files API (`sendSyncAsset`) |
| [notifications](notifications/README.md) | `notifications/android/` | `notifications/macos/` | moved; presenter and handlers still in the coordinators; state: [BRIDGEY_NOTIFICATIONS_STATE.md](../BRIDGEY_NOTIFICATIONS_STATE.md) |
| [calls](calls/README.md) | `calls/android/` | `calls/macos/` | moved; handlers still in the coordinators; detects calls from notifications (dependency) |
| [media](media/README.md) | `media/android/` | `media/macos/` | moved; media quick actions still in `QuickActions.*` |
| [telemetry](telemetry/README.md) | `telemetry/android/` | `telemetry/macos/` | moved; samples only while the panel is open |
| find, ping | handlers in `PairingCoordinator.kt` | handlers in `Pairing.swift` | |
| [screen-share](screen-share/README.md) | `screen-share/android/` (`VideoFrameFraming.kt` pending) | `screen-share/macos/` (`ScreenShareWindow.swift`, `VideoFrameFraming.swift` pending) | partly moved |
| kvm | `KvmInputInjector.kt`, `KvmCoordinateMapper.kt`, `KvmCursorOverlay.kt`, `KvmKeyboardSwitcher.kt`, `ScrollGestureAccumulator.kt`, `BridgeyAccessibilityService.kt`, `BridgeyInputMethodService.kt`, codec in `InputTransport.kt` | `KvmGestureRecognizer.swift`, `KvmKeyMapping.swift`, `KvmPointerCalibration.swift`, codec in `InputTransport.swift` | **frozen**; state: [BRIDGEY_KVM_STATE.md](../BRIDGEY_KVM_STATE.md) |
| [handoff](handoff/README.md) | `handoff/android/` (web + books) | `handoff/macos/` (`BooksAutomation`) | moved; not yet split into web/books; state: [BRIDGEY_WEB_HANDOFF_STATE.md](../BRIDGEY_WEB_HANDOFF_STATE.md) |
| quick actions (links, book cards, media requests) | `QuickActions.kt`, `QuickActionsView.kt` | `QuickActions.swift`, `QuickActionsView.swift` | not moved: shared quick-action transport, ownership undecided |
| sharing | — | — | nearby/ephemeral sharing, designed, not implemented |

Feature dependencies today (must stay explicit):
- photos → files
- notifications ↔ calls (two-way on Android; calls → notifications only on macOS)
- media → notifications (Android: notification listener as the MediaSession permission token)
- kvm → clipboard (paste)
- kvm → screen-share (`VideoContentGeometry` for click mapping)
- quick actions ↔ handoff / media (`QuickActions.*` not moved; ownership undecided)
- handoff → app (Android FGS workaround, see [handoff](handoff/README.md))

Related: [ARCHITECTURE](../ARCHITECTURE.md) · [core](../core/README.md)
