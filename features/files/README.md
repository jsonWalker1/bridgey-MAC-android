# Files

**Answers:** how does a file get from one device to the other, reliably?

**Owns:** streamed transfers (`files.offer` / `accept` / `chunk` / `complete`, acknowledgements,
SHA-256 verification, cancel, retry, interrupted-transfer recovery), the receive folders, transfer
history and UI (Mac transfer and drop windows, Android "file received" notification), and the API the
photos feature uses to send media (`sendSyncAsset`).
**Does not own:** the connection (flow control uses the session's write completion), photo selection
(photos feature).

## Code
| | |
|---|---|
| `macos/` | `FileTransferWindow` |
| `android/` | `ReceivedFileNotifier` |
| `macos/FileTransfers.swift`, `android/FileTransfers.kt` | per-peer keys, tables, target resolution, Mac <-> Mac policy (MD-6) |
| `macos/FileSendService.swift` | Finder "Send to Bridgey…" service |
| still elsewhere | the transfer engines (`OutgoingFileTransfer`, `IncomingFileTransfer`, progress) and `files.*` handlers in `Pairing.swift` / `PairingCoordinator.kt`; `recoverInterruptedTransfers` in `Reliability.*` |

Payloads: `files.v1` in [docs/protocol.md](../../docs/protocol.md).

## Per peer (MD-6)
A transfer belongs to one peer and is keyed **(deviceId, transferId)** (`FileTransferKey`,
`FileTransferTable` in `macos/` and `android/`); the transfer id is sender-chosen, so it never
correlates a message from one peer with another peer's transfer. The sender of an incoming
`files.*` message is the authenticated session, never a payload field.
- **Target:** the selected peer's File action, the drop window opened for a peer, the Android
  share dialog's chosen recipient, or Retry (the same peer). Resolved once at the start;
  selection and routing changes never move a transfer. No target means no send, no fallback.
- **Lifecycle:** a peer's disconnect ends only its transfers (marked interrupted, outgoing ones
  retryable to that peer); a routing change ends none; a peer turning Files off (features.update)
  cancels only its transfers on macOS.
- **Mac <-> Mac** needs this Mac's opt-in ("Allow file transfers with other Macs", off by
  default), enforced when offering (applicability) and when accepting (`files.rejected`). It is a
  product preference keyed on the peer's recorded platform, not a security boundary: a trusted
  peer whose platform was never recorded is treated as not-a-Mac.
- **Flow control:** a macOS receiver sends cumulative `files.chunk.ack` like Android does, so a Mac
  sender (window 64) works towards another Mac. Acks only move forward and never past a chunk
  actually sent.
- **Photo Sync** is not migrated: it targets the routed peer, resolved when each asset starts.

## Sending files on macOS
- **Finder (primary):** select one or more files → right-click → **Services → "Send to Bridgey…"**.
  An `NSServices` entry in Info.plist (`NSSendFileTypes` `public.item`), handled inside the running
  app (`macos/FileSendService.swift`; macOS launches Bridgey if needed, which then waits a few
  seconds for peers). One eligible peer: a one-step "Send … to <peer>?" confirmation; several: the
  user must pick one; none: an explanation (nothing is sent). Folders/unreadable items are skipped
  and listed. Each file goes through `sendFile(_:to:)`; a chosen peer that disconnected or lost
  authorization meanwhile is reported, and no other peer is used. Services are registered by
  LaunchServices from the *installed* app (`/Applications`, keyed by bundle id), so a build run
  from elsewhere does not change what Finder shows. File services appear under **Services**, not
  under Finder's "Quick Actions" (that submenu lists Action extensions, Automator and Shortcuts).
- **Bridgey panel:** a peer's card has **Send Files…** (native `NSOpenPanel`, multiple selection).
- No drop window and no menu bar icon drop: the menu bar panel is transient (macOS closes it when
  Finder is clicked) and SwiftUI's `MenuBarExtra` offers no supported drop target.

## Rules
A transfer is only complete after the receiver verified the hash; partial files are removed on
cancel or failure. Interrupted transfers are marked, never silently retried.
