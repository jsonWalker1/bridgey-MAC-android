# Protocol

The wire contract between Bridgey devices: framing, envelope, handshake, discovery advert,
compatibility/versioning and test vectors. **No runtime code** lives here; the implementation is
in [core/connection](../core/connection/README.md), payload semantics in each feature.

## Contents today
| | |
|---|---|
| Wire specification | [docs/protocol.md](../docs/protocol.md) — current authoritative spec (wire + all feature payloads); feature sections will move to `features/*/PROTOCOL.md` |
| `test-vectors/crypto-v1.properties` | cross-platform crypto vectors used by `ProtocolCryptoIntegrationTests` on both platforms |
| `schema/envelope-v1.schema.json` | **stale**: describes an earlier WebSocket envelope that was never implemented; to be archived |

## Rules
- The wire format is frozen unless a protocol change is explicitly approved.
- Additive, ignorable fields only (e.g. the optional TXT `type` hint).
- Every change to framing, handshake or envelope needs golden wire tests on both platforms.
