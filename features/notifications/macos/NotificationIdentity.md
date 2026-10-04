# NotificationIdentity

**Feature:** notifications (macOS) · **Status:** production.

## Purpose
The pure functions that give every mirrored Android notification a stable identity on the Mac and
reconcile the Mac's delivered set with Android's authoritative snapshot (`notifications.sync`).

## Why it exists
Notification++ treats notifications as **state**, not a stream of events: Android owns the set of
live notifications and the Mac mirrors it. That only works if (1) identifiers are stable and scoped
per device, (2) a multi-part snapshot is applied only when complete, and (3) applying a snapshot is
idempotent. Keeping these rules pure and in one file makes them testable without Notification Center.

## Ownership / non-responsibilities
Owns: request/category identifiers (`deviceID` + Android notification id), the sync payload format
and validation, `NotificationSyncAssembler` (collects the parts of one `syncId` per device), and
`staleRemoteNotificationIdentifiers` (which delivered notifications to remove). Also small helpers
for sound throttling and icon files.
Does **not** post or remove notifications, talk to the session, or decide eligibility (Android's
listener decides what is forwarded).

## Invariants
- Identifiers always include the Android `deviceID`: one device's snapshot can never remove another
  device's notifications (already multi-device correct).
- Android notification ids are SHA-256 hex digests; anything else is invalid.
- Only the newest `syncId` per device is kept; an incomplete or superseded sync **deletes nothing**.
- Reconciliation is pure: applying the same snapshot twice removes nothing the second time.

## Data flow
Android listener → `notifications.post` (fast path) and `notifications.sync` parts (repair path on
every connect/reconnect, listener rebind, feature re-enable, after Android Clear All) → assembler →
complete snapshot → stale identifiers → removed from Notification Center.

## Tests
`NotificationIdentityTests.swift`, `NotificationSyncTests.swift`.

## Related
[NotificationClearAllDetector](NotificationClearAllDetector.md) · `BRIDGEY_NOTIFICATIONS_STATE.md` ·
`notifications.sync` in [docs/protocol.md](../../../docs/protocol.md)
