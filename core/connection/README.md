# Connection

**Answers:** do I currently have an authenticated, encrypted session with this device?

**Owns:** one session per peer device and everything inside it — transport ownership, framing
(newline JSON, 64 KiB frame limit), the pairing/auth handshake (ephemeral ECDH, SAS code,
confirmation proof, identity signature over the transcript), the session key and AES-GCM envelope,
replay protection, heartbeat, handshake timeouts, the per-session **outbox**, auxiliary secure
channels (video/input), and the peer's capability facts.
**Does not own:** the trust decision (asks [trust](../trust/README.md)), signing keys (asks
[identity](../identity/README.md)), which device to dial ([presence](../presence/README.md)),
payload meaning (features), routing (app).

## Code today
| | Where |
|---|---|
| Session, handshake, heartbeat, listener/dial | `Session` + `configure/accept/dial/receive` in `Pairing.swift`; `Session` + `handle/receive` in `PairingCoordinator.kt` |
| Session bookkeeping | `PeerSessionManager` in `DeviceCore.*` (pending sockets, one session per `deviceId`, lower-id tie-break) |
| Outbox (Android) | `SessionWriter` in `DeviceCore.kt` |
| Crypto | `ProtocolCrypto.swift`, `Crypto` object in `PairingCoordinator.kt` |
| Framing helpers | `decodeProtocolMessage` / `readProtocolLine` in `Reliability.*` |
| Auxiliary channels | `ChannelSecurity.*`, `ChannelLifecycle.*`, `TCPChannelSupport.swift`, `TCP*Transport.*`, `VideoTransport.*`, `VideoChannelController.swift`, `VideoChannelManager.kt` |

## Invariants
- One session per `deviceId`; a connected session always wins over a new one; a simultaneous dial
  keeps the connection initiated by the lower id on both sides. Failure of one session never
  touches another.
- Each session owns its outbox: a peer that stops reading can only block its own queue (P0 #1).
- Heartbeat every 10 s; a session is closed after 30 s without traffic (once the peer is known to
  answer heartbeats). Unidentified sockets time out (Mac 8 s, Android 30 s from creation).
- The handshake and wire format are frozen; changes go through [protocol](../../protocol/README.md).

## Known limitations
- The engine still lives inside the coordinators; extraction is planned behind golden wire tests.
- Replay protection (`messageId` window) is applied per handler, not by the envelope.
- Android writes outside the fan-out still happen on the caller's thread.

## Tests
`ProtocolCryptoIntegrationTests.swift` / `ProtocolCryptoIntegrationTest.kt`, `ChannelSecurity*`,
`ChannelLifecycle*`, `TCPInputTransport*`, `TCPVideoTransport*`, `ReliabilityTests.swift` /
`ReliabilityTest.kt`, `MultiDeviceCore*` (sessions, duplicates, tie-break, isolation),
`SessionWriterIsolationTest.kt` (blocked peer vs healthy peer over real sockets).

Related: [messaging](../messaging/README.md) · [lifecycle](../lifecycle/README.md) · [protocol](../../protocol/README.md)
