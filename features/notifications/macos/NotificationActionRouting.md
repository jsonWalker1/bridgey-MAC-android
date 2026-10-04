# NotificationActionRouting

**Feature:** notifications (macOS) · **Status:** production.

## Purpose
Decides what clicking a mirrored Android notification on the Mac does: ask, open a native Mac app,
open a URL (optionally with the conversation id), open it on the phone, or nothing — then clear or
keep the notification. Configured per Android app in Settings.

## Why it exists
A notification from WhatsApp or e-mail is often better handled by a Mac app or web app than by the
phone. The rule set is user configuration with fallbacks, so the decision is a pure function
(`routeNotificationClick`) that can be tested exhaustively, separated from opening targets.

## Ownership / non-responsibilities
Owns the click configuration (`NotificationActionSettings`, persisted as versioned JSON in
UserDefaults), the routing decision and opening targets with public `NSWorkspace` API.
Does **not** own notification state: "Clear" dismisses exactly like the user closing the
notification (Mac and phone via the Notification++ path), "Keep" leaves both untouched.

## Routing
Per-app rule → global default → safe fallback (`ask`, which never needs a target). A rule whose
target is unusable (app not installed, invalid URL, phone not connected) falls through to the
global default; an unusable default falls back to `ask`. `{conversationId}` is URL-encoded into
URL templates, and the result must parse and carry a scheme.

## Security
Only public API; no automation of other apps. URLs come from the user's own configuration, not from
the phone; the phone only supplies the conversation id, which is URL-encoded.

## Tests
`NotificationActionRoutingTests.swift`.

## Related
[NotificationIdentity](NotificationIdentity.md) · `BRIDGEY_NOTIFICATIONS_STATE.md`
