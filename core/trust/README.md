# Trust / Relationship

**Answers:** do I recognise this identity long-term, and what is my relationship with it?

**Owns:** durable, pairwise trust records — `deviceId` → pinned identity key (+ display name and
descriptive metadata); the decision `evaluate(deviceId, key)` → trusted / unknown / mismatch;
(future) in-memory ephemeral relationships with their verification level.
**Does not own:** what a trusted device may do (→ [authorization](../authorization/README.md)),
contacts (an application-level policy input), presence or sessions.

## Code today
| Platform | Where |
|---|---|
| macOS | `MacTrustRegistry.swift` (Keychain `dev.bridgey.mac.trust` / `trusted-devices-v1`, JSON), `DeviceRegistry` in `DeviceCore.swift` |
| Android | `AndroidTrustRegistry` and `DeviceRegistry` in `DeviceCore.kt` (SharedPreferences `bridgey.trust`: `peer.<id>.name`, `peer.<id>.identityKey`, optional metadata keys) |

Trust is created only after both users confirmed the same SAS code during pairing; reconnects of a
trusted device are confirmed automatically by verifying the pinned key.

## Invariants
- Trust is **pairwise** and never propagated (A trusts B and B trusts C ⇏ A trusts C).
- A known `deviceId` presenting a different key is rejected and **never rebound**.
- Trust ≠ authorization; trust ≠ connection; consent to one action ≠ trust.
- Records from earlier releases decode unchanged (metadata fields are optional).

## Known limitations
- Only one relationship kind exists (paired = "my device"); `ephemeral` relationships for nearby
  sharing are designed, not implemented.
- `lastSeen` and `protocolVersion` are currently written into the trust record; they belong to
  Presence/Connection and will move.
- `forget` removes trust but does not yet purge feature Peer State (Mac notifications/history).

## Tests
`MacTrustRegistryTests.swift`, `MultiDeviceCoreTests.swift` (identity mismatch, pairwise trust,
legacy record migration, identical display names) and Android `MultiDeviceCoreTest.kt`.

Related: [identity](../identity/README.md) · [authorization](../authorization/README.md)
