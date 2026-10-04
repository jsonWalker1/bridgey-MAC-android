# Telemetry

**Answers:** what is the state of the other device — battery, storage, memory, CPU, temperature?

**Owns:** sampling this device's values (`macos/`, `android/`), the wire payloads
(`battery.*`, `telemetry.update`, `telemetry.subscribe` / `unsubscribe`) and the remote values shown
in the UI (Peer State of this feature).
**Does not own:** the connection, the panel UI, or when the panel is open (Context).

## Rules
- **Battery-conscious:** storage, memory, CPU and temperature are sampled only while the other
  device's panel is open (`telemetry.subscribe`), never continuously in the background. Battery is
  sent on change.
- Remote values are valid only while the session to that device is valid; they are cleared on
  disconnect and when the feature is turned off on either side.

## Code
| | |
|---|---|
| `macos/` | `MacBattery`, `MacCpu`, `MacMemory`, `MacStorage`, `MacTemperature` — local sampling |
| `android/` | `AndroidCpu`, `AndroidMemory`, `AndroidStorage`, `AndroidTemperature` — local sampling |
| still in the coordinators | publish/subscribe handlers, remote values (`remoteBattery`, `remoteStorage`, …) — move with Core messaging extraction |
| Android battery | published from `BridgeyApplication` (system battery broadcast) |

Payloads: [docs/protocol.md](../../docs/protocol.md) (`battery.send.v1`, `telemetry.update`).
History and measurements: [BRIDGEY_TELEMETRY_STATE.md](../../BRIDGEY_TELEMETRY_STATE.md).

## Tests
`tests/macos/Mac*Tests.swift`, `tests/android/Android*Test.kt`.
