# Ping

**Answers:** is that device reachable right now? (plays a short alert there)

**Owns:** Ping requests per device (`PingRequests`): each request is the pair (deviceId,
requestId), so an acknowledgement, timeout or disconnect of one device never touches another
device's request. **Does not own:** the connection, target selection UI (the panel / dashboard
pick a connected device from the device directory).

First feature migrated to the multi-device routing foundation (MD-3): the caller names the target
device, the request is sent with `send(to:)`, the acknowledgement is matched by the sender of the
session it arrives on (`connectedDeviceID(of:)`), and a device's requests are dropped from its own
`sessionEnded` lifecycle event. Offered per device through applicability (`.ping`: every platform
combination, capability `ping`, local per-device grant).

Wire format unchanged: `ping.request` (encrypted `{version: 1}`) and `ping.ack` (echoes the
request's `messageId`). See [docs/protocol.md](../../docs/protocol.md).

## Code
| | |
|---|---|
| `macos/` | `PingRequests` |
| `android/` | `PingRequests` |
| still in the coordinators | `sendPing(to:)`, `receivePing`, `receivePingAcknowledgement`, status text |

## Tests
`tests/macos/PingRequestsTests.swift`, `tests/android/PingRequestsTest.kt`.
