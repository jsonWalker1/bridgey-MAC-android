# Clipboard

**Answers:** how does copied text get to the other device?

**Owns:** the clipboard payload (UTF-8 text plus optional HTML with a mandatory plain-text fallback,
32 KiB limit, `ClipboardPayload`), sending and receiving with delivery acknowledgement, the Android
Quick Settings tile, and the send status shown in the UI.
**Does not own:** the connection; when KVM paste needs the clipboard it calls this feature
(kvm → clipboard dependency).

## Code
| | |
|---|---|
| `macos/` | `ClipboardPayload` |
| `android/` | `ClipboardPayload`, `ClipboardTileService`, `ClipboardCaptureActivity` (foreground read for the tile and the notification action) |
| still elsewhere | `sendClipboard` / `clipboard.*` handlers in the coordinators |

Payloads: `clipboard.v1` in [docs/protocol.md](../../docs/protocol.md).

## Per peer (MD-5)
Clipboard is an **explicit send to one peer**, never a synchronization. The card's Clipboard
button sends to the selected peer (`sendClipboard(to:)`), whichever peer legacy features are
routed through. Sends are tracked per (deviceId, messageId) in `ClipboardSends`: an ack or
rejection completes only the send to the peer that answered; a timeout or that peer's disconnect
ends only its sends, which never move to another peer. Incoming clipboard is accepted from any
connected peer with its own grant; a retransmitted message id is acknowledged again instead of
being applied twice or closing the session. Target-less entry points (Mac shortcut, Android tile /
notification action / share) send only when exactly one peer can receive it, otherwise ask the
user to choose. KVM paste keeps syncing to the KVM channel's (routed) peer. Wire unchanged.
Only connected (authenticated) sessions get any clipboard reply, including `clipboard.rejected`.
Receiving applies the same direction rule as offering: clipboard from another Mac is rejected
(`clipboard.rejected`); a peer whose platform was never recorded is still accepted.
The Android tile is ready only with exactly one eligible peer. Known limitation: with several
eligible peers, Android share only reports "choose a device"; there is no picker in the share flow.

## Rules
Android never reads the clipboard in the background: sending is an explicit user action (tile,
share, UI). Every update carries a unique message id and needs an acknowledgement.

## Tests
`tests/macos/ClipboardPayloadTests.swift`, `tests/android/ClipboardPayloadTest.kt`,
`tests/macos/ClipboardSendsTests.swift`, `tests/android/ClipboardSendsTest.kt`.
