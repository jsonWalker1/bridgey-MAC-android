# BridgeyConnectionService

**Domain:** app (Android OS lifecycle) · **Status:** production.

## Purpose
The foreground service that keeps Bridgey's connections alive in the background and shows the
persistent status notification (connected device, "No Wi-Fi", transfers, Find device, Remote Start).

## Why it exists
Without a running foreground service Samsung's Freecess freezes the app and the Mac connection drops
every few seconds (observed on a Galaxy S23 Ultra). The service is the app's "keep alive" contract;
the battery exemption ([BackgroundRunning](BackgroundRunning.md)) only covers the gaps.

## Ownership / non-responsibilities
Owns the foreground notification and its actions (Stop, cancel transfer, stop finding, Remote Start
tap), the Wi-Fi watch for the status line, and channel setup. Does **not** own sessions or reconnect
(Core), or the features behind the actions.

## Who starts it
`MainActivity`, `BootCompletedReceiver` (after reboot, if enabled) and the Web Handoff accessibility
service (when Android rebinds it in a fresh process after an update). All must check
`shouldStartConnectionService(isPrimaryUser, isBridgeyEnabled)` first.

## Invariants
- A service started with `startForegroundService()` must call `startForeground()` before it stops;
  otherwise Android crashes the app (`ForegroundServiceDidNotStartInTimeException`, HW1 finding,
  fixed in `4db1c30`). The service stops itself when Bridgey is off, so callers must not start it then.
- `startForeground` is re-posted on every start command: Android 13+ / some Samsung builds let users
  dismiss foreground-service cards.
- Remote Start never bypasses the system MediaProjection consent dialog; DND bypass only applies after
  the user granted DND access explicitly.

## Tests
`ConnectionServiceStartTest.kt`, `ConnectionStatusTextTest.kt`.

## Related
[BackgroundRunning](BackgroundRunning.md) · `BridgeyApplication.kt` · [app](../../../../../../../../app/README.md)
