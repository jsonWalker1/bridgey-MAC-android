# BRIDGEY — NOTIFICATION++ STATE

Status as of 2026-10-01. Read this file + `CLAUDE.md` first. Wire format:
`docs/protocol.md` → Notifications (`notifications.sync`, `resync`,
`notifications.dismissMany`). Architecture summary: `docs/architecture.md` →
Notification state.

## 1. PRODUCT RULE

The Android active notification set is the source of truth. "What isn't on the
phone shouldn't be on Bridgey." There must be no steady state where Android no
longer has a notification but the Mac still shows it. Synchronization is
event-driven: no polling, no new timers, no background scans.

## 2. ARCHITECTURE (EVENT → immediate UX, SNAPSHOT → authoritative consistency)

| Direction | Fast path (events) | Repair path |
| --- | --- | --- |
| Android → Mac | `notifications.post`, `notifications.remove` | `notifications.sync` (complete eligible ID set) + silent `resync: true` re-posts |
| Mac → Android | `notifications.dismiss` (macOS dismiss callback, one notification) | `notifications.dismissMany` (inferred Notification Center Clear All) |
| Mac → Android | `notifications.action` (buttons / replies) | — |

- **Android identity** (`BridgeyNotificationListenerService.kt`): one pure
  `eligibleNotificationId()` decides forwarding + logical ID for live posts,
  listener re-bind and snapshots. ID formula unchanged:
  `SHA-256("<pkg> conversation <shortcutId>")`, else `SHA-256(sbn.key)`.
- **Registry**: `notificationId → Set<systemKey>`. A logical notification lives
  until its last key is removed; Mac dismiss cancels every key.
- **Sync triggers** (Android): connect/reconnect and notification forwarding
  becoming available on both peers (`refreshNotificationForwardingAvailability`),
  listener (re)bind (`onListenerConnected`), and one sync debounced 500 ms after
  `REASON_CANCEL_ALL`. All notification messages go through one ordered send
  queue so a sync can never overtake a later post.
- **Mac sync apply** (`Pairing.swift`, `NotificationIdentity.swift`): assembles
  parts per `syncId`, then removes delivered notifications of that device that
  are absent from the snapshot (never other devices, never anything posted after
  the sync was applied). An incomplete sync deletes nothing.
- **Mac Clear All** (`NotificationClearAllDetector.swift`): see §4.
- **Feature off** (either peer): Mac removes that device's mirrored
  notifications and call card. **On**: fresh silent resync + sync, no history
  replay.

## 3. MEASURED PLATFORM FACTS (S23 Ultra, Android 16 ↔ MacBook, 2026-09-30)

- `getDeliveredNotifications()` lags a successful `add()` by up to ~25 s, and a
  replaced notification can briefly be absent from it.
- macOS keeps **at most 100 delivered notifications per app** and silently
  evicts the oldest (150 posted → indexes 50–149 kept). No callback.
- Closing one notification with × delivers `UNNotificationDismissActionIdentifier`.
  Clearing the whole Bridgey stack delivers **nothing** (matches
  developer.apple.com/forums/thread/692708 and /676178; no public API exists).
- No time-based eviction: 53 notifications unchanged for 5+ minutes.
- Android keeps at most 50 active notifications per app.
- `UNNotificationAttachment` **moves** its file into the system store; a shared
  icon file made concurrent posts fail ("Failed to move attachment file into
  data store") and a failed replacement dropped the old notification. Fixed:
  every post writes its own copy.

## 4. macOS CLEAR ALL STATE MACHINE

Evaluated only on the existing 10 s connected-session heartbeat.

| State | Condition | Result |
| --- | --- | --- |
| Inactive | not connected, forwarding unavailable on either peer, or notifications not authorized / Notification Center disabled | snapshot cleared |
| Settling | < 60 s since session start, last Bridgey post, or permission change | snapshot = (snapshot − explained) ∪ delivered, no evaluation |
| Tracking | delivered ≠ ∅ | snapshot = delivered |
| Tracking | delivered = ∅, everything vanished is explained | snapshot cleared, nothing sent |
| ZeroPending | first delivered = ∅ with unexplained IDs | wait one more check |
| Dispatch | second consecutive delivered = ∅ | `notifications.dismissMany` with the unexplained snapshot IDs, snapshot cleared |

"Explained" = removed by Bridgey itself (Android remove, sync, feature off) or
dismissed individually via the callback, within the last 120 s. Eviction at the
100 limit never empties the list, so it never dispatches. Android handles each
ID like a single dismiss (echo suppressed by `RemoteDismissTracker`), ignores
unknown IDs, and **skips IDs it forwarded within the last 60 s** (newer content
the Mac cannot have seen).

## 5. KNOWN LIMITATIONS (all fail safe: the phone keeps the notification)

1. The Mac only knows the logical (conversation) ID, not the Android instance.
   A message arriving on the phone in the tens of milliseconds between the bulk
   dismiss being sent and handled would be cancelled with its conversation.
2. A Mac Clear All while Bridgey is not running or the phone is disconnected is
   not propagated; the next resync shows those notifications on the Mac again.
3. A new Bridgey post within ~20 s after a Mac Clear All cancels propagation of
   that clear.
4. Mac Clear All reaches the phone ~10–20 s later (two heartbeat checks).
5. Temporary `NOTIF_DIAG` logging (Notification++ phase 2) is still in
   `BridgeyNotificationListenerService.kt`; remove once no longer needed.

## 6. VALIDATION

- Unit tests: `NotificationReconciliationTest.kt`,
  `ForwardedNotificationRegistryTest.kt` (Android);
  `NotificationSyncTests.swift`, `NotificationClearAllDetectorTests.swift`,
  `NotificationIdentityTests.swift` (macOS). Suites green: Android 253, macOS 275.
- Hardware (with `adb shell cmd notification post/snooze`,
  `disallow_listener`/`allow_listener`, `svc wifi disable/enable`, and manual
  Mac/phone Clear All):
  - Mac restart: 16 stale Mac notifications removed on connect.
  - Listener re-bind: removal missed while unbound repaired.
  - Wi-Fi drop: 2 removals made offline repaired on reconnect.
  - Android Clear All: 57 removals, one debounced sync, Mac at 0.
  - Bulk resync of 52 notifications: 52/52 delivered after the attachment fix
    (24 failed before).
  - Mac single dismiss → phone removal; Mac Clear All → 4/4 removed on phone;
    Android Clear All → no echo; reconnect → no false Clear All.
- Not yet validated on hardware: WhatsApp multi-message conversation, multiple
  conversations, Notification Sync OFF/ON toggle, phone reboot.

## 7. NOTIFICATION CLICK ACTIONS (per-app routing, macOS)

Replaces the tap-routing POC (`6d72ffb`). `NotificationActionRouting.swift`:

- **Model**: `NotificationActionRule` (Android package, display name, action,
  Mac app bundle identifier + name, URL, Clear/Keep) and a global
  `NotificationActionDefaults`. Actions: `ask`, `openNativeApp`, `openURL`
  (`{conversationId}` placeholder, URL-encoded), `openOnPhone` (the
  notification's own Android "Open" action), `doNothing`.
- **Routing**: per-app rule → global default (ask / open on phone / do nothing)
  → `ask`. A rule whose target is unusable (app not installed, invalid URL, phone
  not available) falls through to the default.
- **Clear** = exactly like closing the notification (removed on the Mac,
  `notifications.dismiss` to the phone; while disconnected Android keeps it and
  the next resync brings it back). **Keep** = stays on both; macOS removes a
  clicked notification, so Bridgey re-adds the same request silently (without
  the icon attachment). A failed open never clears. Every click is registered as
  an explained removal for the Clear All detector.
- **Ask**: native alert — choose a Mac app (`NSOpenPanel`, stored by bundle
  identifier), open on phone (if available), or cancel; optional "Always do
  this for <app>" creates the rule.
- **Settings → Notification click actions**: default action + Clear/Keep, one
  row per app rule (action, Mac app chooser or URL, Clear/Keep, remove), "Add app
  rule" from Android apps seen in forwarded notifications.
- **Persistence**: `UserDefaults` key `notificationActions.v1` (JSON). WhatsApp
  is seeded once as an example rule (open `net.whatsapp.WhatsApp` + Clear) only
  if WhatsApp for Mac is installed; removing it is permanent.
- Android unchanged (`conversationId` already on the wire). Not yet tested with a
  real WhatsApp click.

## 8. NOT IN SCOPE / DECIDED

- No Bridgey UI button for clearing phone notifications.
- No private API, Accessibility or UI automation for Notification Center.
- Duplicate "Bridgey" notification on Android: not fixed; capture
  `adb shell dumpsys notification --noredact | grep -A 30 'pkg=dev.bridgey.android'`
  when it appears. Candidates: Android auto-group summary
  (`g:Aggregate_NormalNotificationSection` observed), screen-share notification,
  orphaned ongoing file-transfer notification after process death.
- Call panel collapse (Mac): next small task, not started.
