# Authorization

**Answers:** what may this device do with me (and I with it), in which direction, and for how long?

**Owns (target):** grants per device and permission — standing, one-shot or expiring; the
permission catalogue (each feature registers its own keys and defaults, so Core never names a
feature); inbound enforcement in one place; "pending consent" for operations that need the user.
**Does not own:** trust ([trust](../trust/README.md)), Mode (may only restrict), Context
(changes availability, not permission), consent UI (app).

## Code today
- Global and per-device feature booleans in `BridgeySettings.swift` / `BridgeySettings.kt`
  (`settings.global.<feature>`, `settings.device.<id>.<feature>`).
- Each handler checks `featureEnabled(...)` itself.
- `features.update` sends the local grant per device; a feature is available only when both sides
  grant it (`effectiveFeatureAvailable`).

## Invariants
- Trust never implies a grant; capability never implies a grant; Mode never widens a grant.
- Grants are per device and never shared between devices.

## Known limitations
- Grants are symmetric booleans; direction ("Mac may control the phone" ≠ the reverse), scope and
  expiry do not exist yet.
- A newly paired device gets the global defaults (most features on; screen share and KVM opt-in).
- Enforcement is spread across ~20 handlers.

Related: [lifecycle](../lifecycle/README.md) · [modes](../../modes/README.md) · [ARCHITECTURE](../../ARCHITECTURE.md)
