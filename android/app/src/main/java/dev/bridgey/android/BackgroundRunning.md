# BackgroundRunning

**Domain:** context (Android OS permission) · **Status:** production.

## Purpose
Asks for and reports the battery-optimisation exemption ("let the app always run in background").

## Why it exists
Samsung's Freecess freezes background apps that have no running foreground service. Bridgey keeps a
foreground service for the connection, but there are moments without one (right after an update,
before Android rebinds the app). Without the exemption the app froze, and the Mac connection dropped
every 10–20 s and never settled (observed on a Galaxy S23 Ultra, 2026-10-03).

## Ownership / non-responsibilities
Owns: `isUnrestricted()`, the system request (falls back to the settings list), and asking at most
once (`bridgey_background` / `asked_ignore_battery_optimizations`); afterwards only the
"Background running" card in `MainActivity` offers it again.
Does **not** start or keep the foreground service (`BridgeyConnectionService`, started by the app
and by the accessibility service — only while Bridgey is on).

## Related
`BridgeyConnectionService.kt` · [context](../../../../../../../../context/README.md)
