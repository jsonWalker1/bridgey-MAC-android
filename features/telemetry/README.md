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

## Per device (MD-4c)
Telemetry is device state, kept per peer in both directions (`DeviceTelemetry.swift` / `.kt`):
- **Display side:** `DeviceTelemetryStore` holds deviceId → that peer's latest values; a value is
  only ever stored under the peer whose session delivered it. `TelemetrySubscription` subscribes
  exactly the peer the UI shows (`showTelemetry(for:)`): the selected peer while the panel /
  dashboard is visible, nothing otherwise. Changing the selection unsubscribes the old peer;
  a reconnecting shown peer is resubscribed (`sessionStarted`). Routing is never involved.
- **Publish side:** `TelemetrySubscribers` is the set of peers displaying this device; the
  sampling loop runs while it is non-empty and each subscriber has its own storage/memory
  dead-band. Battery goes on change to every connected peer that grants it (and right after its
  features.update), so it is visible without a subscription, as before.
- A peer's session end removes only its values, subscription and dead-band; a revoked grant or
  capability clears only that peer's metric.
- Wire unchanged: `battery.update`, `telemetry.update`, `telemetry.subscribe` / `unsubscribe`.
- A re-enabled grant or capability resets that subscriber's dead-band and re-sends battery, so
  values never wait for a 100 MiB change. On Android the "first/last subscriber" decision and the
  sampling loop start/stop are one locked step.
- Known limitation: a peer still running a pre-MD-4c build answers subscriptions and sends battery
  only to its own routed peer, so another device may show "Waiting for …" for it.

## Code
| | |
|---|---|
| `macos/` | `MacBattery`, `MacCpu`, `MacMemory`, `MacStorage`, `MacTemperature` — local sampling; `DeviceTelemetry` — per-device state |
| `android/` | `AndroidCpu`, `AndroidMemory`, `AndroidStorage`, `AndroidTemperature` — local sampling; `DeviceTelemetry` — per-device state |
| still in the coordinators | the publish/subscribe handlers (addressed by deviceId) |
| Android battery | read from `BridgeyApplication` (system battery broadcast), sent per peer by the coordinator |

Payloads: [docs/protocol.md](../../docs/protocol.md) (`battery.send.v1`, `telemetry.update`).
History and measurements: [BRIDGEY_TELEMETRY_STATE.md](../../BRIDGEY_TELEMETRY_STATE.md).

## Tests
`tests/macos/Mac*Tests.swift`, `tests/android/Android*Test.kt`,
`tests/macos/DeviceTelemetryTests.swift`, `tests/android/DeviceTelemetryTest.kt`.
