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
| Device directory | `PairingCoordinator.deviceDirectory`, `device(_:)` | `deviceDirectory()`, `device(id)` | read-only projection (`DeviceDirectoryEntry`: deviceId, name, trusted, connection, capabilities, platform + kind hints → `profile`, routed); not a source of identity or trust |
| Lifecycle | `peerLifecycle` (`PeerLifecycle`, `PeerLifecycleObserver`) | same | see [lifecycle](../lifecycle/README.md) |
| Applicability (app layer) | `applicability(of:with:localIsSource:)`, `FeatureApplicability` | `applicability(feature, deviceId, localIsSource)` | see below |

**Device profile (MD-2).** `DeviceProfile` = `DevicePlatform` (android / macos / unknown) +
`DeviceKind` (phone / tablet / computer / unknown), taken from discovery TXT or the trust record's
metadata (recorded metadata wins over live hints). Roles derive from it, e.g. `ownsCellularLine`
(an Android phone). These are unverified **hints**: never identity, trust or authorization.

**Applicability** (`FeatureApplicability.swift` / `.kt`, app layer, not Core) answers "should
Bridgey offer this product feature from this source device to this target device?". It is
decided per **product feature** of the readiness audit (Web Handoff, Books Handoff, link to
phone, notification mirror / actions, call state / control, media remote, Mac player control,
telemetry, screen share, Remote Start, KVM, clipboard, files, photo sync, find, ping), because one
capability key can carry several of them (`links`, `media`, `notifications`, `calls`). Each rule
names its allowed `source → target` platforms, its capability keys, an optional role requirement
(which end must own the cellular line) and whether it needs explicit (opt-in) authorization.
- `isApplicable(feature, source, target)` — product, platform and role only. An unknown platform
  or kind matches nothing, so it never makes a feature available; only deliberately
  platform-independent features (files, find, ping) ignore the platform.
- `evaluate(feature, local, peer, localIsSource, isLocallyAuthorized)` — then the peer's
  capability (its `features.update` value, which on the wire also carries the peer's grant to us)
  and the local grant for that device, each reported separately: `offered` / `notApplicable` /
  `peerLacksCapability` / `notAuthorized`. A key the local catalog does not have (KVM on macOS)
  has no local grant; the peer's opt-in grant still decides.

Mac → Mac is not offered for Web/Books Handoff, links, clipboard (Universal Clipboard), calls,
notifications, media, screen share, Remote Start or KVM; files, find and ping are offered in every
direction (the audit's open product decision). Mode and Context are not part of applicability.
Nothing uses it yet; no feature changes behaviour.

`send(to:)` is a raw Core primitive: it does **no** authorization or capability check — a migrated
feature must check `applicability` (or its existing `featureEnabled` / `isFeatureAvailable`) before
sending. On Android `true` means *queued on that session's outbox*; a session that closes before
the outbox runs drops the write silently.

**Migrating a feature (MD-2+):** send with `send(to:)` instead of `activeSession`, read the sender
with `connectedDeviceID(of:)` and let it through the inactive-peer gate in `receive`, keep its state
keyed by `deviceId` and reset it from `PeerLifecycleObserver`, and offer it per device through
`applicability`.

**Migrated (MD-3):** [ping](../../features/ping/README.md) and
[find](../../features/find/README.md) address devices explicitly; their message kinds pass the
inactive-peer gate from every connected session (`deviceAddressedMessageKinds`). Their
target-less entry points (`sendPing()` from the keyboard shortcut, `findAndroid()` / `findMac()`)
remain as compatibility paths that name the routed device explicitly.

**Still on `activeSession`:** clipboard, files, photo sync, notifications (forwarding, actions,
dismiss, Clear All), calls, media (both directions), quick actions / links / Web and Books Handoff,
telemetry and battery, screen share / Remote Start (video channel providers) and KVM (input
channel, frozen). The inactive-peer gate in `receive` still drops their messages from non-routed
devices.

## Invariants (target)
- Features never see sockets, session keys, framing or reconnect.
- A message from a device is delivered with its `deviceId`; there is no "the remote device".
- Unknown message kinds are ignored, as today.

## Known limitations
Everything above is the target; today's dispatch is the main reason the coordinators are god
objects.

Related: [connection](../connection/README.md) · [features](../../features/README.md)
