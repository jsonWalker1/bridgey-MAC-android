# Notifications (Notification++)

**Answers:** which phone notifications exist right now, and how do they appear and behave on the Mac?

Notifications are synchronised as **state**, not as a stream of events: Android owns the set of live
notifications, the Mac mirrors it. `notifications.post` / `.remove` are the fast path;
`notifications.sync` is the authoritative repair path (every connect/reconnect, listener rebind,
feature re-enable, after an Android Clear All). A Mac stack clear is inferred and sent as
`notifications.dismissMany`.

**Owns:** the Android notification listener (eligibility, identity, forwarding, actions, replies,
reconciliation), Mac presentation in Notification Center, Clear-All inference, click routing,
optional local history, and the mirrored notifications (this feature's Peer State).
**Does not own:** the connection; call state (calls consumes the dialer's call notifications from here).
**Dependencies:** notifications ↔ calls on Android — the listener calls back into the calls feature
(`CallsController`, call-type resolution, `shouldDelayCallPost`) and builds the generic "Open"
action with `notificationActionCandidates` / `NotificationActionCandidate`, which are defined in
`features/calls/android/CallsController.kt` and stay there for now. On macOS only calls → notifications.
Media (Android) uses this feature's listener component as its MediaSession permission token.

## Code
| | |
|---|---|
| `android/` | `BridgeyNotificationListenerService` (listener, `ForwardedNotificationRegistry`, eligibility, sync parts, action tokens) |
| `macos/` | [NotificationIdentity](macos/NotificationIdentity.md) (ids, sync assembly, reconciliation), [NotificationClearAllDetector](macos/NotificationClearAllDetector.md), [NotificationActionRouting](macos/NotificationActionRouting.md), `NotificationHistory` |
| still elsewhere | `NotificationPresenter` and the `notifications.*` handlers in `Pairing.swift`; `sendNotification*` in `PairingCoordinator.kt` |

Payloads: `notifications.*` in [docs/protocol.md](../../docs/protocol.md).
History and measurements: [BRIDGEY_NOTIFICATIONS_STATE.md](../../BRIDGEY_NOTIFICATIONS_STATE.md).

## Rules
- Identifiers include the Android `deviceID`; one device's snapshot never touches another device's
  notifications. Notification content is never logged off the device.
- Known gap: `forget` does not yet remove a forgotten device's delivered notifications or history
  (history items carry no `deviceId` yet).

## Tests
`tests/android/ForwardedNotificationRegistryTest.kt`, `NotificationReconciliationTest.kt`;
`tests/macos/Notification*Tests.swift`, `NotificationSyncTests.swift`.
