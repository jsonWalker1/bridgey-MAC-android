# Modes (application input)

**Answers:** how does the user want Bridgey to behave right now?

User intent, stored, and **restrictive only**: a Mode can switch behaviour off or narrow it, it can
never grant a permission. External automation (Tasker, Shortcuts) may set a Mode through one entry
point; there is no rule engine.

Owns: device routing preference (which connected device today's single-peer features use),
Visibility (Hidden / Known / Contacts / Everyone / temporary), and future behaviour profiles
(Work, Home, Quiet, …). Feature-specific preferences stay in their feature.

## Code today
| | Where |
|---|---|
| Routing seam | `DeviceRouting` (`DeviceCore.*`), `deviceRoutingMode` + `preferredDeviceID` in `BridgeySettings.*`, `activePeerChanged` in the coordinators |
| Pocket Mode | `BridgeySettings.kt` (owned by screen-share) |
| Visibility | not implemented |

`preferredDeviceId` was migrated once from the single-device era to the only trusted device.

Related: [context](../context/README.md) · [authorization](../core/authorization/README.md)
