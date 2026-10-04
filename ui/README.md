# UI (shell)

**Answers:** how is state presented and how does the user act on it?

Owns the application shell: Android dashboard, macOS menu bar panel and settings window,
localisation. Feature UI (cards, windows) lives with its feature when that keeps ownership clear.
UI consumes feature APIs and read-only device state; it never talks to sessions, transport or
messaging.

## Code today
| Platform | Where |
|---|---|
| Android | `MainActivity.kt` (dashboard incl. feature cards), `res/values*/strings.xml` |
| macOS | panel and settings in `BridgeyApp.swift`, `MenuBarPanelSurface.swift`, `Localization.swift`, `Resources/*.lproj` |

Known limitation: UI derives per-device status from the single `PairingState` (the active
device); a read-only device directory from Core will replace that.

Related: [app](../app/README.md) · [features](../features/README.md)
