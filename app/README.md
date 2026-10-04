# App (composition and OS lifecycle)

**Answers:** how is the system started, wired together and kept alive?

**Owns:** entry points, the composition root (create Identity/Trust/Presence/Connection,
features and the routing seam, then connect them), OS lifecycle (Android foreground service,
boot receiver, battery exemption flow; macOS launch at login, shortcuts), and commands from the
UI that manage devices (pair, confirm code, cancel, forget, select preferred device).
**Does not own:** protocol, crypto, handlers, feature state. The final runtime *composes* the
system; it does not own it.

## Code today
| Platform | Where |
|---|---|
| Android | `BridgeyApplication.kt` (startup, discovery → Core wiring), `BridgeyConnectionService.kt` (foreground service, status notification), `BootCompletedReceiver.kt` |
| macOS | `BridgeyApp.swift` (entry, composition), `ShortcutSettings.swift`, `GlobalHotKey.swift` |
| Both | `PairingCoordinator` (`Pairing.swift`, `PairingCoordinator.kt`) — today a god object holding Core engine, feature handlers and state; it will shrink to a thin runtime |

## Invariants
- The Android connection service is started only while Bridgey is on
  (`shouldStartConnectionService`); a foreground service that stops before `startForeground()`
  crashes the app (HW1 finding).
- Starting the Mac app from a terminal changes which app macOS attributes permissions to (Photos,
  Keychain); for logs use `open --stdout … --stderr …`.

Related: [ui](../ui/README.md) · [ARCHITECTURE](../ARCHITECTURE.md)
