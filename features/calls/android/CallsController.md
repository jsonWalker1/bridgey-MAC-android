# CallsController

**Feature:** calls (Android) · **Status:** production; incoming-call state hardware-validated on a
Galaxy S23 Ultra.

## Purpose
Resolves the state of a phone call from what Android actually exposes, and executes the call
controls the Mac requests (answer, decline/hang up, audio route) through public system APIs.

## Why it exists
A sideloaded app cannot get real Telecom call state: a non-UI `InCallService` needs
`CONTROL_INCALL_EXPERIENCE`, a signature/privileged permission (prototyped and confirmed on a real
device — see `docs/architecture.md`). Bridgey therefore infers call state from the dialer's
ongoing `CATEGORY_CALL` notification, corrected by telephony state when the user opted into call
integration. The inference has OEM quirks, so it was extracted from the notification listener into
its own component with pure, testable functions.

## Ownership / non-responsibilities
Owns call-type resolution (`resolvedNotificationCallType`), telephony observation, answer/hang-up
via `TelecomManager`, audio routing and the routes actually available.
Does **not** own notification forwarding (the listener service does — calls currently *depend* on
notifications), call UI on the Mac (`Calls.swift`, `CallOverlayWindow.swift`), or the unused
`calls.v2` wire family.

## Non-obvious decisions
- Samsung's dialer reports `CALL_TYPE_ONGOING` while still ringing; its full-screen intent is the
  stable, language-independent "incoming" signal.
- Audio routes: `AudioManager.setCommunicationDevice` on S+, deprecated speaker/SCO toggles below.
  Never throws — a route that disappears between the Mac's choice and the call just fails to apply.
  Routing is cleared when the call ends so Android never stays in speaker/SCO mode.
- The Mac is only offered routes Android reports as available; the Bluetooth label is the device's
  own name (S+) or generic — never hardcoded.
- A generic "Open" action (the notification's `contentIntent`) is appended last so app-declared
  actions keep priority within the 4-action cap.

## Security
Call controls are executed only when the user enabled call integration
(`isCallIntegrationEnabled`) and only for authenticated, authorised action tokens. Policy:
[docs/sms-call-policy.md](../../../docs/sms-call-policy.md).

## Tests
`android/app/src/test/java/dev/bridgey/android/CallsControllerTest.kt`.

## Related
`BridgeyNotificationListenerService.kt` · macOS `Calls.swift` · `BRIDGEY_NOTIFICATIONS_STATE.md`
