# NotificationClearAllDetector

**Feature:** notifications (macOS) · **Status:** production, hardware-validated (see
`BRIDGEY_NOTIFICATIONS_STATE.md`).

## Purpose
Turns "the user cleared the whole Bridgey stack in macOS Notification Center" into
`notifications.dismissMany`, so the same notifications disappear on the phone.

## Why it exists
macOS gives **no event** for a stack clear (measured 2026-09-30: a single dismiss reports
`UNNotificationDismissActionIdentifier`, a stack clear reports nothing). The only way to notice it
is to infer it from the list of delivered notifications. That inference is subtle enough — lagging
system APIs, silent eviction, Bridgey's own removals — that it lives in one pure, tested state
machine instead of inside the coordinator.

## Ownership / non-responsibilities
Owns the decision "was this a user Clear All?" from snapshots it is given. Does **not** read
Notification Center itself, send messages, know about sessions, or decide which notifications
exist (Android's `notifications.sync` stays the source of truth).

## How it works
`observe(current:active:settling:now:)` is called on the existing 10 s heartbeat (no new timer)
with the delivered Bridgey notifications keyed by request identifier.
- **inactive** — not connected, forwarding off or notifications not authorized: all state reset.
- **settling** — within 60 s of a new session, a post or a permission change: the snapshot only
  grows. `getDeliveredNotifications` was measured to lag up to ~25 s behind `add()`, and replaced
  notifications briefly vanish from it.
- **N > 0 → 0** must be seen on **two consecutive** checks (`zeroPending` first).
- Vanished ids that Bridgey removed itself, or that the user dismissed one by one (already sent as
  `notifications.dismiss`), are *explained* for 120 s and never counted.
- Result `dismissMany(entries)` → split into messages of ≤ 256 ids (`notificationDismissManyParts`).

## Invariants
- macOS silently evicts beyond 100 notifications per app, but that never empties the list, so it
  can never look like a clear.
- Never fires during settling, never fires on explained removals, never on a single zero reading.
- Entries carry `deviceID`; the result only ever dismisses notifications of the device they came from.

## Failure modes
A user who clears the stack within 60 s of a new session or post is not detected (by design: false
positives would delete live phone notifications). A clear followed by a new notification before the
second check is treated as an update.

## Tests
`macos/Tests/BridgeyMacTests/NotificationClearAllDetectorTests.swift`.

## Related
[NotificationIdentity](NotificationIdentity.md) · [NotificationActionRouting](NotificationActionRouting.md) ·
`notifications.dismissMany` in [docs/protocol.md](../../../docs/protocol.md)
