# Lifecycle (mechanism)

**Answers:** when does what I know about a device stop being valid?

Lifecycle is a small **event mechanism**, not a domain. It emits:
session up · session down · trust revoked · relationship ended · grant revoked / expired.

**Owns:** the events and their ordering guarantees. **Nothing else** — no feature state, no Peer
State, no UI state, no routing state, no application state.

Features keep their own Peer State (remote battery, media, notifications, call state, …) keyed by
`deviceId` and invalidate it when an event for that device arrives.

## Code today
`PeerLifecycle` (`DeviceCore.swift` / `DeviceCore.kt`, MD-1) emits per-device events to registered
`PeerLifecycleObserver`s through explicit methods (no event bus):
- `sessionStarted(deviceId)` when a session becomes connected (authenticated);
- `sessionEnded(deviceId)` when that session ends, exactly once per started session and only for
  that device;
- `authorizationChanged(deviceId)` when the local grant for that device changes (a global switch
  reports every trusted or connected device) or the peer's `features.update` changes.

Events are bound to the session object, so an older session ending never ends a newer session
of the same device; a start for a session that is no longer the device's connected session is
ignored. Android serializes all emissions under one lock (sessions start and end on different
threads) and isolates throwing observers. Every new session's first `features.update` also
produces `authorizationChanged` for that device. Devices that are neither trusted nor connected
(e.g. just forgotten) never receive `authorizationChanged`.

No feature observes it yet. Today's features are still reset implicitly: `activePeerChanged`,
`endSession` and `interruptFeatureTransfers` in both coordinators reset each feature's state by
name. Migrated features replace that with an observer keyed by `deviceId`.

## Known limitations
`forget` does not yet invalidate feature state of the forgotten device (e.g. Mac notifications
and notification history stay).

Related: [connection](../connection/README.md) · [features](../../features/README.md)
