# VideoChannelManager

**Domain:** core/connection — auxiliary secure channels (Android) · **Status:** production
(transport of Screen Share and KVM); frozen M1 specification. macOS counterpart:
`VideoChannelController.swift`.

## Purpose
Negotiates, opens, secures and tears down the two dedicated TCP channels next to the main session:
**video** (screen frames) and **input** (KVM events).

## Why it exists
Video frames and input events must not share the control session's newline-JSON channel (latency,
backpressure, size). They get their own sockets, negotiated over the authenticated control session
and keyed from it (`ChannelSecurity`: per-channel keys derived from the session key, session id,
purpose and direction).

## Ownership / non-responsibilities
Owns channel negotiation over the control session, the channel state machine
(NEGOTIATING → CONNECTING → HANDSHAKING → ACTIVE / FAILED → IDLE), establishment, security and
backpressure. Does **not** encode/decode video (screen-share) or inject input (kvm), and does not own
the main session.

## Invariants
- **Hard reset (Decision 10):** whenever the main Bridgey session resets, both channels are torn down
  immediately and unconditionally.
- A peer-initiated stop is a normal shutdown (→ IDLE), never a failure; only an unexpected socket drop
  ends in FAILED.
- Direction is decided by the initiator when it sends the offer; the acceptor derives the identical
  channel key from the same value.

## Known limitations (multi-device)
Bound to the routed (active) session: the peer address comes from `activeSession.remoteHost` and keys
from its session key. Target: Core opens auxiliary channels for a given `deviceId`; a future UDP/QUIC
KVM-gaming channel would be a new channel type here.

## Tests
`VideoChannelManagerTest.kt`, `ChannelLifecycleTest.kt`, `ChannelSecurityTest.kt`,
`TcpInputTransportTest.kt`, `TcpVideoTransportTest.kt`.

## Related
`ChannelSecurity.kt` · `ChannelLifecycle.kt` · [core/connection](../../../../../../../../core/connection/README.md)
