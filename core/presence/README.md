# Presence

**Answers:** which devices *claim* to be around, on which endpoints, and when should I try to
reach them?

**Owns:** grouping discovery adverts by their claimed `deviceId` (one device may have several
service names / endpoints), lastSeen, reconnect planning (who dials, which endpoint, backoff),
executing the advertising policy (Visibility) it is given.
**Does not own:** identity (an advert is an unauthenticated claim), trust, the decision what to
advertise (Mode/Visibility decides).

## Code today
| | Where |
|---|---|
| Grouping, planning | `DevicePresence`, `ReconnectPlanner` in `DeviceCore.swift` / `DeviceCore.kt` |
| TXT parsing | `DiscoveryTXTRecord` (`macos/.../Discovery.swift`), `DiscoveryTxtRecord` (`platform/android/discovery/.../DiscoveredPeer.kt`) |
| Adapters | `BonjourDiscovery` (`Discovery.swift`), `NsdDiscoveryService` (`platform/android/discovery`) — see [platform](../../platform/README.md) |
| Backoff | `reconnectDelay` / `reconnectDelayMillis` in `Reliability.*` |

Advert (`_bridgey._tcp`, TXT): `id`, `name`, `version`, `platform`, optional `type` (descriptive
hint, never identity). Own adverts are filtered by `id`, also after an mDNS rename.

## Invariants
- `deviceId` ≠ service name ≠ hostname ≠ IP. Endpoint changes update presence, never identity.
- Per pair, the lower `deviceId` dials from discovery; retries rotate through a device's endpoints.
- Presence never decides trust.

## Known limitations
- No grace period yet: presence is dropped as soon as Bonjour/NSD reports the service lost.
- The advert always carries a stable `id` and the device name (privacy; see Visibility in
  [ARCHITECTURE](../../ARCHITECTURE.md)).
- On the Galaxy S23, Mac → phone dials do not get through; the phone always initiates (HW1).

## Tests
`DiscoveryTXTRecordTests.swift`, `MultiDeviceCoreTests.swift`; Android `DiscoveryTxtRecordTest.kt`,
`DiscoveryRetryTest.kt`, `MultiDeviceCoreTest.kt`.

Related: [connection](../connection/README.md) · [platform](../../platform/README.md)
