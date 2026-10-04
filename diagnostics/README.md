# Diagnostics

Cross-cutting, exportable diagnostics **without private content**: categories, events and
outcomes (e.g. `transport/disconnected/reconnecting`), never message payloads, notification text
or clipboard content. Diagnostics may read everything and own nothing.

## Code today
`BridgeyDiagnostics.kt`, `BridgeyDiagnostics.swift` (export from the macOS menu / Android
settings). Tests: `BridgeyDiagnosticsTests.swift`, `BridgeyDiagnosticsTest.kt`.

Logging rules: device ids are logged as 8-character prefixes; notification and clipboard content
must not appear in logs that leave the device.
