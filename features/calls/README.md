# Calls

**Answers:** what is happening with the phone's calls, and can I start or control a call from the Mac?

**Owns:** call-state resolution on Android ([CallsController](android/CallsController.md)), answer /
decline / hang up and audio routing through public telephony APIs, starting a phone call from the Mac
(selected text, clipboard, `tel:` links, Services menu), the Mac call card/overlay, and the remote
call state shown on the Mac (this feature's Peer State).
**Does not own:** notification forwarding — incoming calls are detected from the dialer's ongoing
`CATEGORY_CALL` notification, so calls **depend on the notifications feature**; the connection.
**Dependencies:** on Android the dependency is two-way (notifications ↔ calls): the notification
listener uses `CallsController` and call-type resolution, and the notification-generic
`notificationActionCandidates` / `NotificationActionCandidate` live in `CallsController.kt` (not
moved yet). On macOS only calls → notifications (`remoteNotificationRequestIdentifier`).

## Code
| | |
|---|---|
| `android/` | `CallsController` (state, controls, audio routes), `RemoteCallRequest` (executes a call the Mac asked for) |
| `macos/` | `Calls` (remote call state), `CallRequest` (number validation), `PhoneURLHandler` (`tel:`), `CallOverlayWindow` |
| still elsewhere | call handlers and `sendCall*` in the coordinators; detection hook in `BridgeyNotificationListenerService.kt` |

Policy: [docs/sms-call-policy.md](../../docs/sms-call-policy.md). Payloads: `calls.v1` / `calls.v2`
in [docs/protocol.md](../../docs/protocol.md) (`calls.v2` is defined but not sent).

## Rules
Call controls run only with call integration enabled and for authenticated action tokens. A
non-UI `InCallService` is not possible for a sideloaded app (`CONTROL_INCALL_EXPERIENCE`).

## Tests
`tests/android/CallsControllerTest.kt`, `RemoteCallRequestTest.kt`; `tests/macos/CallsTests.swift`,
`CallRequestTests.swift`, `PhoneURLHandlerTests.swift`.
