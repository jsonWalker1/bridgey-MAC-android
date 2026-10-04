# Bridgey architecture

This is the authoritative map of how Bridgey is built and where code belongs. It describes the
**target architecture** that the code is being migrated to, and for every part it says where
the code lives **today**. Domain READMEs go one level deeper; component `.md` files next to the
source explain individual components.

```
README.md            what Bridgey is, where to start
 └ ARCHITECTURE.md   this file: the system contract
    └ <domain>/README.md     the public contract of one domain
       └ <Component>.md      the internal design of one component (the WHY)
          └ source code
```

## Mental model

Bridgey is a **local-first fabric of peer devices** owned or used by one person. Every Bridgey
installation — a Mac, an Android phone or tablet, a future platform — is an equal peer. There is
no controller and no server; a device can dial, accept, discover, advertise and host features.

The platform answers five questions, and everything a user sees is a feature on top of them:

| Question | Owner |
|---|---|
| Who is this device, and do I trust it? | Identity, Trust |
| Where is it right now? | Presence |
| How do I talk to it securely? | Connection, Messaging |
| What may it do? | Authorization |
| When does what I know about it stop being valid? | Lifecycle (a mechanism) |

## Layers

```
UI ─────────────────────────► presentation only (shell UI + feature UI)
App ────────────────────────► composition, OS lifecycle, Context, Mode, availability, routing seam
Features (addons) ──────────► clipboard · files · photos · notifications · calls · media ·
                              telemetry · find · ping · screen-share · kvm · handoff · (sharing)
Core ───────────────────────► Identity · Trust/Relationship · Presence · Connection ·
                              Messaging · Authorization · Lifecycle (mechanism)
Platform adapters ──────────► transport (TCP), discovery (Bonjour/NSD), Keychain/KeyStore, storage
```

Dependencies point **down only**. Features depend on Core APIs (and on other features only
through explicit, documented APIs). Core never depends on a feature, on Context, on Mode or on UI.

## Core

| Domain | Answers | Owns | Does not own | Today |
|---|---|---|---|---|
| [Identity](core/identity/README.md) | who am I? | `deviceId`, identity key, signing, identity load (NOT_FOUND vs UNAVAILABLE) | network, trust decisions | `MacIdentity` (`Pairing.swift`), `LocalDevice` (`DeviceCore.swift`); `AndroidIdentity` (`PairingCoordinator.kt`), `device_id` (`BridgeyApplication.kt`) |
| [Trust / Relationship](core/trust/README.md) | do I recognise this identity, and how? | durable pinned identity keys; (future) ephemeral relationships | authorization, contacts | `MacTrustRegistry.swift`, `DeviceRegistry` (`DeviceCore.*`), `AndroidTrustRegistry` (`DeviceCore.kt`) |
| [Presence](core/presence/README.md) | who claims to be around, where? | claimed `deviceId` → endpoints, TTL/grace, lastSeen, reconnect planning | identity, trust | `DevicePresence`, `ReconnectPlanner` (`DeviceCore.*`), discovery adapters |
| [Connection](core/connection/README.md) | do we have an authenticated session? | handshake, envelope crypto, framing, replay window, heartbeat, per-session outbox, auxiliary channels, capability facts | trust decisions, payload meaning | `Session` + handshake in `Pairing.swift` / `PairingCoordinator.kt`; `PeerSessionManager`, `SessionWriter` (`DeviceCore.*`); `ChannelSecurity.*` |
| [Messaging](core/messaging/README.md) | how is a message delivered to a device? | delivery by `deviceId`: fire, request→response, stream with backpressure | feature names and semantics | today a `switch`/`when` over message kinds inside the coordinators |
| [Authorization](core/authorization/README.md) | what may this device do, in which direction, for how long? | grants (standing / one-shot / expiring), feature-registered permission keys, inbound enforcement, pending consent | Mode, Context, consent UI | per-feature booleans in `BridgeySettings.*`, checked by each handler (`featureEnabled`) |
| [Lifecycle](core/lifecycle/README.md) | when does knowledge about a device stop being valid? | events: session up/down, trust revoked, relationship ended, grant revoked/expired | **any state** — feature, Peer State, UI, routing | today implicit: `activePeerChanged` / `endSession` reset feature state directly |

**Lifecycle is a mechanism, not a domain.** It only emits events. It owns no feature state, Peer
State, UI state or routing state.

Core must never know: Notifications, Clipboard, Files, KVM, Media, Calls, Handoff, Books, Web,
Modes, Context, UI, third-party apps (e.g. WhatsApp) or any feature payload. Core changes only
for a genuinely new primitive: transport, secure channel type, identity/authentication,
relationship, authorization or messaging primitive.

## Security distinctions

These are separate concepts and must never be collapsed into one class, setting or message:

| | is not | why |
|---|---|---|
| Presence | Identity | an advert is an unauthenticated claim |
| Identity | Trust | proving a key is not deciding to accept it |
| Trust | Authorization | a trusted device is not allowed everything |
| Capability | Authorization | "the peer supports X" never grants X |
| Connection | Trust | a socket or session is not a relationship |
| Mode | Authorization | Mode can only **restrict**, never widen |
| Context | Authorization | an OS fact changes availability, not permission |
| Consent | Trust | approving one action does not pair a device |
| Ephemeral relationship | Trusted device | a temporary interaction is never persisted as trust |

**Availability is derived, never stored:**
`available = valid relationship ∧ connected ∧ capability ∧ local grant ∧ remote grant ∧ Context permits ∧ Mode permits`

Identity invariants: a `deviceId` is bound to exactly one identity key and is never rebound; the
identity is never replaced because of a storage error (only a confirmed "not found" creates one);
Bonjour service name, hostname, IP address, port, platform and device type are never identity.

## Communication model

- One authenticated session per peer device (`sessions[deviceId]`), any number of devices.
  Sockets that have not identified themselves are *pending*; duplicate protection is per
  `deviceId`; on a simultaneous dial both sides keep the connection initiated by the lower id.
- Each session owns its transport, heartbeat and **outbox**: a blocked peer can only block its
  own queue.
- Wire protocol: newline-delimited JSON, ephemeral ECDH per session, application-layer AES-GCM,
  P-256 identity signatures over the session transcript. See [protocol](protocol/README.md).
- Capabilities and grants travel in `features.update`: the presence of a key means "my software
  knows this feature" (capability), the value means "I grant it to you" (authorization).

## Unknown devices and ephemeral sharing

Bridgey supports long-term trusted relationships **and** short-lived interactions with unknown
devices (AirDrop-like sharing). Ephemeral sharing is a **feature composed from Core primitives**,
not a domain:

```
Presence (unknown device advertises)
 → Connection bootstrap (same handshake, no wire change; key proven; SAS compared or not)
 → explicit Consent (App UI; the peer is shown as unverified unless a SAS was compared)
 → ephemeral Relationship (memory only)
 → scoped Authorization (e.g. share.receive, one transfer, until T)
 → feature operation (Files stream)
 → expiry / completion → Lifecycle event → grant removed, session closed
```

The device never becomes trusted automatically. Unverified identity is shown as unverified.
*Status: not implemented yet; the Core model must leave room for it.*

## Visibility

Who can discover this device is a **user policy owned by Mode**, executed by Presence:
Hidden · Known devices · Contacts · Everyone nearby · temporary Everyone.

- Possible without protocol changes: Hidden (do not advertise; still browse and dial known
  endpoints) and minimal adverts (omit `name`/`platform`/`type`; parsers fall back to the
  service name).
- Known problem: today's Bonjour TXT always carries a stable `id` and the device name, which
  allows tracking across networks. Fixing that needs a future protocol revision (rotating or
  derived identifiers). Identity is proven only by the handshake, so the architecture does not
  depend on the stable `id`.
- Contacts are an **application-level policy input** (who I show to / accept from). A contact is
  never cryptographic trust and is never part of Core.

## Context and Mode (application inputs)

| | Context | Mode |
|---|---|---|
| What | observed facts | user intent |
| Examples | Wi-Fi, screen lock, thermal state, OS permissions (notifications, Accessibility, battery optimisation, Local Network, MediaProjection), Bridgey panel open | routing preference, Pocket Mode, Visibility; future Work / Home / Quiet profiles |
| Stored | never | yes |
| May widen authorization | no | **no — only restrict** |
| Sent to peers | never raw | no |

External automation (Tasker, Shortcuts) may set a Mode through one entry point. There is no
rule engine. See [context](context/README.md) and [modes](modes/README.md).

**Routing seam (CONNECTED ≠ ACTIVE):** today's features are single-peer, so the app selects one
connected device (`activePeer`: preferred if connected, else the earliest connected) and features
use `activeSession`. Inactive sessions stay connected and keep their real capabilities; their
feature messages are not consumed yet. Features migrate one by one to address devices directly.

## Features (addons)

"Addon" means **feature package + platform-independent contract** — not a plugin loader, not a
marketplace, not reflection, not a DI framework. All features are compiled in and composed at
startup. A feature owns its protocol payloads, state (including its Peer State), platform
integrations (NotificationListener, Accessibility, MediaProjection, IME, Notification Center,
VideoToolbox, …) and UI. See [features](features/README.md).

**Peer State** (a feature's local copy of remote state — battery, media, notifications, call
state) is a convention, not a framework: keyed by `deviceId`, valid only while the session,
relationship and grant are valid, invalidated on Lifecycle events. Core does not know which Peer
State exists.

## Platform, App, UI, Protocol, Diagnostics, Experiments

- [platform](platform/README.md) — reusable OS adapters for Core only. Feature-specific OS code
  belongs to the feature, never to `platform/`.
- [app](app/README.md) — entry points, composition root, OS lifecycle (FGS, launch at login).
  The final runtime composes the system; it does not own it.
- [ui](ui/README.md) — shell UI. UI is never a second networking layer.
- [protocol](protocol/README.md) — wire definitions, compatibility, test vectors. No runtime code.
- [diagnostics](diagnostics/README.md) — exportable diagnostics without private content.
- [experiments](experiments/README.md) — never part of a production build.

## Transports

Normal traffic is TCP and stays TCP. UDP/QUIC is a **future KVM / Gaming optimisation**, to be
added as a new auxiliary channel type with its own transport implementation (channel keys are
derived as today by `ChannelSecurity`). Core does not require UDP/QUIC and there is no generic
transport framework.

## Where new code belongs

| I want to… | Go to |
|---|---|
| change how devices identify, trust or find each other | `core/identity`, `core/trust`, `core/presence` |
| change handshake, encryption, heartbeat, reconnect | `core/connection` (+ `core/presence` for planning) |
| add a user-facing feature | `features/<feature>/` (README + PROTOCOL + `android/` + `macos/`) |
| add an OS permission or environment signal | `context/` |
| add a user preference about behaviour | `modes/` (or the owning feature for feature preferences) |
| add an Android/macOS adapter used by Core | `platform/android`, `platform/macos` |
| add a feature's Android/macOS integration | that feature's `android/` / `macos/` folder |
| change the wire format | `protocol/` first, then Core/feature — requires an approved protocol change |

## Migration status

The tree is being moved from a platform-centric layout (`android/app/src/main/java/dev/bridgey/android/`,
`macos/Sources/BridgeyMac/`) to the domain layout above, incrementally and test-gated. Until a
domain's code has moved, its README lists where the code lives today.

| Step | State |
|---|---|
| Multi-device Core (sessions per device, routing seam) | done — [HW1 report](docs/status/hw1-multi-device-2026-10-04.md) |
| P0 #1 per-session outbox (Android), R1 identity on Keychain errors (Mac) | done |
| Documentation (this file, domain READMEs, component docs) | in progress |
| Domain source tree, feature moves | planned |
| Core extraction: identity/trust/presence → connection → messaging + authorization → lifecycle | planned |
| `Pairing.swift` / `PairingCoordinator.kt` reduced to a thin runtime | planned |
| `DeviceCore`, `Reliability`, `BridgeySettings` split by ownership | planned |

Rules for the migration: file moves use `git mv` in commits without content changes; moves,
behaviour changes and refactors are never mixed; the wire protocol does not change; KVM files
move only with explicit approval and KVM behaviour does not change.
