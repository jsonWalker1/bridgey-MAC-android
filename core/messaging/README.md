# Messaging

**Answers:** how does a feature send a message to a device, and how does an incoming message
reach the feature that owns it?

**Owns (target):** delivery addressed by `deviceId` with three primitives — *fire*,
*request → response* (correlation, timeout) and *stream with backpressure* (file chunks);
routing of incoming messages to the feature that registered the message kind; the authorization
check at the boundary (with [authorization](../authorization/README.md)).
**Does not own:** feature names, payload schemas or semantics, subscriptions or state sync (those
are feature patterns built on the primitives), routing to the "active" device (app seam).

## Code today
There is no messaging component yet. Incoming messages are dispatched by a `switch` (macOS,
`PairingCoordinator.receive` in `Pairing.swift`) / `when` (Android, `PairingCoordinator.kt`) over
~41 message kinds, and handlers decrypt payloads themselves with the session key. Outgoing
messages are built by each feature function against `activeSession`.

## Invariants (target)
- Features never see sockets, session keys, framing or reconnect.
- A message from a device is delivered with its `deviceId`; there is no "the remote device".
- Unknown message kinds are ignored, as today.

## Known limitations
Everything above is the target; today's dispatch is the main reason the coordinators are god
objects.

Related: [connection](../connection/README.md) · [features](../../features/README.md)
