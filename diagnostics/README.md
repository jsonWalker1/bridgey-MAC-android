# Diagnostics

Cross-cutting, exportable diagnostics **without private content**: categories, events and
outcomes (e.g. `transport/disconnected/reconnecting`), never message payloads, notification text
or clipboard content. Diagnostics may read everything and own nothing.

## Code today
`android/BridgeyDiagnostics.kt`, `macos/BridgeyDiagnostics.swift` (export from the macOS menu /
Android settings). Tests: `tests/macos/BridgeyDiagnosticsTests.swift`,
`tests/android/BridgeyDiagnosticsTest.kt`. Layout: `diagnostics/<platform>` and
`diagnostics/tests/<platform>`, compiled through the explicit domain lists in `Package.swift` and
`android/app/build.gradle.kts`. `BridgeyLog` (macOS discovery log) is still in `Discovery.swift`.

Logging rules: device ids are logged as 8-character prefixes; notification and clipboard content
must not appear in logs that leave the device.
