# Context (application input)

**Answers:** what is currently true about this device and its environment?

Observed facts only — never user choices, never stored as preferences, never sent to peers raw.
Context changes **availability**, never authorization.

Examples: Wi-Fi/network state, screen lock, thermal state, OS permissions (notification access,
Accessibility, battery-optimisation exemption, Local Network, MediaProjection), whether the
Bridgey panel is open.

**Not here:** facts only one feature needs (MediaProjection session details, KVM calibration) stay
in that feature. No global event bus — consumers observe the values they need.

## Code today
| Fact | Where |
|---|---|
| Battery-optimisation exemption (Android) | [`android/BackgroundRunning.kt`](android/BackgroundRunning.md) (fact + the one-time system dialog) |
| Notification permission (macOS) | `refreshNotificationAuthorization` in `Pairing.swift` |
| Local Network denied (macOS) | `localNetworkPermissionDenied` in `Reliability.swift` |
| Network lost / Wi-Fi available (Android) | `NetworkCallback` in `PairingCoordinator.kt`, Wi-Fi watch in `BridgeyConnectionService.kt` |
| Panel open (telemetry on demand) | `request/stopRequestingRemoteTelemetryUpdates` in the coordinators |

Related: [modes](../modes/README.md) · [ARCHITECTURE](../ARCHITECTURE.md)
