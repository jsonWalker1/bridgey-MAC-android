# Core

The platform every feature runs on. Core answers five questions for any number of peer devices
and knows nothing about features, Context, Mode or UI.

| Domain | Question |
|---|---|
| [identity](identity/README.md) | Who am I? |
| [trust](trust/README.md) | Do I recognise this identity, and in which relationship? |
| [presence](presence/README.md) | Who claims to be around, and where? |
| [connection](connection/README.md) | Do we have an authenticated session? |
| [messaging](messaging/README.md) | How does a message reach a device? |
| [authorization](authorization/README.md) | What may this device do? |
| [lifecycle](lifecycle/README.md) | When does knowledge about a device stop being valid? (mechanism) |

**Rules**
- No coordinator object, no `DeviceFabric`, no event bus, no feature registry that knows features.
- Dependency direction: Messaging/Authorization → Connection → Identity/Trust; Connection →
  Presence; Identity depends on nothing in Core.
- Platform specifics live behind small ports implemented in [`platform/`](../platform/README.md).
- Core changes only for a new primitive (transport, channel type, identity/authentication,
  relationship, authorization or messaging primitive) — never for a new feature.

**Status:** the Core *policy* exists (`DeviceCore.swift`, `DeviceCore.kt`); the session engine
still lives inside `Pairing.swift` / `PairingCoordinator.kt` and is extracted step by step. Each
domain README lists where its code lives today. System contract: [ARCHITECTURE.md](../ARCHITECTURE.md).
