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
Incoming messages are dispatched by a `switch` (macOS, `PairingCoordinator.receive` in
`Pairing.swift`) / `when` (Android, `PairingCoordinator.kt`) over ~41 message kinds, and handlers
decrypt payloads themselves with the session key. Outgoing messages of today's features are built
against `activeSession` (the routed device).

## Routing primitives (MD-1)
The Core can address a specific device without `activeSession`. Nothing migrated to them yet;
the product behaves exactly as before.

| Primitive | macOS | Android | Guarantee |
|---|---|---|---|
| Addressed send | `PairingCoordinator.send(to:kind:payload:)` | `PairingCoordinator.send(to, kind, payload)` | encrypted with that session's key, queued on that session only (Android: its outbox); false if the device is not connected; never falls back to another device |
| Addressed session (Core) | `PeerSessionManager.connectedSession(for:)`, `deliver(to:_:)` | `connectedSession(deviceId)`, `deliver(deviceId, …)` | only an authenticated (connected) session; independent of the routed peer |
| Receive identity (Core) | `PeerSessionManager.connectedDeviceID(of:)` | `connectedDeviceId(session)` | the sender of a message on that session; nil for pending/handshaking sockets |
| Device directory | `PairingCoordinator.deviceDirectory` | `deviceDirectory()` | read-only projection (`DeviceDirectoryEntry`: deviceId, name, trusted, connection, capabilities, platform/deviceType hints, routed); not a source of identity or trust |
| Lifecycle | `peerLifecycle` (`PeerLifecycle`, `PeerLifecycleObserver`) | same | see [lifecycle](../lifecycle/README.md) |
| Applicability (app layer) | `applicability(of:for:)`, `FeatureApplicability` | `applicability(feature, deviceId)` | see below |

**Applicability** (`FeatureApplicability.swift` / `.kt`, app layer, not Core) answers "can feature
X be offered from this device to that peer?" and keeps four inputs apart: platform/device-type
*hints* (discovery TXT or trust metadata; an unknown platform never hides a feature), direction
(a static per-feature table of `from → to` platforms), capability (the peer's `features.update`
for this session, which on the wire also carries the peer's grant) and the local per-device
authorization. Result: `offered` / `notApplicable` / `peerLacksCapability` / `notAuthorized`.
Security still rests on identity, trust and authorization only; the hints are not authenticated.
Mac → Mac is not offered for web links, clipboard, calls, notifications, media, Remote Start
(and KVM on Android); a known Android *tablet* is not offered calls.

`send(to:)` is a raw Core primitive: it does **no** authorization or capability check — a migrated
feature must check `applicability` (or its existing `featureEnabled` / `isFeatureAvailable`) before
sending. On Android `true` means *queued on that session's outbox*; a session that closes before
the outbox runs drops the write silently.

**Migrating a feature (MD-2+):** send with `send(to:)` instead of `activeSession`, read the sender
with `connectedDeviceID(of:)` and let it through the inactive-peer gate in `receive`, keep its state
keyed by `deviceId` and reset it from `PeerLifecycleObserver`, and offer it per device through
`applicability`.

**Still on `activeSession` (all production features):** clipboard, files, photo sync, notifications
(forwarding, actions, dismiss, Clear All), calls, media (both directions), quick actions / links /
Web and Books Handoff, telemetry and battery, find, ping, screen share / Remote Start (video
channel providers) and KVM (input channel, frozen). The inactive-peer gate in `receive` still
drops their messages from non-routed devices.

## Invariants (target)
- Features never see sockets, session keys, framing or reconnect.
- A message from a device is delivered with its `deviceId`; there is no "the remote device".
- Unknown message kinds are ignored, as today.

## Known limitations
Everything above is the target; today's dispatch is the main reason the coordinators are god
objects.

Related: [connection](../connection/README.md) · [features](../../features/README.md)
