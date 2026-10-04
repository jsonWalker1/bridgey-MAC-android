# Find Device

**Answers:** where is that device? (makes it ring until someone stops it)

**Owns:** Find state per device (`FindDeviceState`): which peers we asked to ring confirmed they
are ringing (`remoteRinging`), and which peers asked *this* device to ring (`localRequesters`).
This device rings while at least one requester remains, so stopping, timing out or disconnecting
one device never changes another device's state, and a reconnecting device inherits nothing.
**Does not own:** the connection, the sound itself (played by the coordinator), target selection
UI.

Migrated to the multi-device routing foundation (MD-3): `startFinding` / `stopFinding` name the
target device and use `send(to:)`; `find.started` / `find.stopped` replies go back to the device
that asked (not to the routed device); a device's state is dropped from its own `sessionEnded`
lifecycle event, and revoking the local Find grant for a device ends that device's request to ring
this one (`authorizationChanged`). On Android every Find decision, the ring sound and the published
state run as one serialized step (sessions read in parallel). Offered per device through applicability (`.findDevice`: every platform
combination, capability `find_device`, local per-device grant for being made to ring).

Wire format unchanged: `find.start`, `find.stop`, `find.started`, `find.stopped`, each with the
encrypted `{alertId: "active"}`. See [docs/protocol.md](../../docs/protocol.md).

## Code
| | |
|---|---|
| `macos/` | `FindDeviceState` |
| `android/` | `FindDeviceState` |
| still in the coordinators | `startFinding`, `stopFinding`, `stopLocalRinging`, handlers, the ring sound |

## Tests
`tests/macos/FindDeviceStateTests.swift`, `tests/android/FindDeviceStateTest.kt`.
