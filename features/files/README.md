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
| `macos/` | `FileTransferWindow`, `FileDropWindow` |
| `android/` | `ReceivedFileNotifier` |
| still elsewhere | the transfer engines (`OutgoingFileTransfer`, `IncomingFileTransfer`, progress) and `files.*` handlers in `Pairing.swift` / `PairingCoordinator.kt`; `recoverInterruptedTransfers` in `Reliability.*` |

Payloads: `files.v1` in [docs/protocol.md](../../docs/protocol.md).

## Rules
A transfer is only complete after the receiver verified the hash; partial files are removed on
cancel or failure. Interrupted transfers are marked, never silently retried.
