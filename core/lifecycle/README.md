# Lifecycle (mechanism)

**Answers:** when does what I know about a device stop being valid?

Lifecycle is a small **event mechanism**, not a domain. It emits:
session up · session down · trust revoked · relationship ended · grant revoked / expired.

**Owns:** the events and their ordering guarantees. **Nothing else** — no feature state, no Peer
State, no UI state, no routing state, no application state.

Features keep their own Peer State (remote battery, media, notifications, call state, …) keyed by
`deviceId` and invalidate it when an event for that device arrives.

## Code today
Implicit. `activePeerChanged`, `endSession` and `interruptFeatureTransfers` in both coordinators
reset each feature's state by name. That is the coupling this mechanism removes.

## Known limitations
`forget` does not yet invalidate feature state of the forgotten device (e.g. Mac notifications
and notification history stay).

Related: [connection](../connection/README.md) · [features](../../features/README.md)
