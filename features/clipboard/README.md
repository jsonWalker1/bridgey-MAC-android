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

## Rules
Android never reads the clipboard in the background: sending is an explicit user action (tile,
share, UI). Every update carries a unique message id and needs an acknowledgement.

## Tests
`tests/macos/ClipboardPayloadTests.swift`, `tests/android/ClipboardPayloadTest.kt`.
