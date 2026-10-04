# ChannelSecurity

**Domain:** core/connection — auxiliary secure channels · **Status:** production, frozen M1 spec.
The Kotlin file (`android/.../ChannelSecurity.kt`) is a byte-identical port; this document covers both.

## Purpose
Pure crypto for the video/input channel handshake: derives a per-channel key and proves both ends hold
it before any frame flows.

## Why it exists
The dedicated channels are separate TCP sockets, so they cannot reuse the control session's envelope.
Their key is derived from the already-authenticated session instead of a new key exchange:
`HKDF-SHA256(pairingKey; salt = SHA-256(sessionId, purpose, direction); info = "bridgey-channel-v1")`
(manual extract-then-expand, one 32-byte block). A 16-byte open nonce and an HMAC-SHA256 proof bind the
channel to the session.

## Invariants
- No new primitive: HKDF/HMAC/SHA-256 only; the Kotlin implementation mirrors this one step by step.
  There are no shared cross-platform vectors for channel keys yet (the session crypto has them in
  `protocol/test-vectors`).
- Keys differ per purpose (video/input) and direction; a channel cannot be replayed into another session.
- Channels die with the main session (hard reset invariant).

## Tests
`ChannelSecurityTests.swift` / `ChannelSecurityTest.kt`: determinism, distinct keys per purpose,
direction and session, token/ack proofs, seal/open round trip.

## Related
`VideoChannelController.swift` · Android [VideoChannelManager](../../../android/app/src/main/java/dev/bridgey/android/VideoChannelManager.md) ·
[core/connection](../../../core/connection/README.md)
