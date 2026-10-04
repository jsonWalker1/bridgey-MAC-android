# Platform adapters

Reusable OS mechanisms that **Core** needs, behind small ports: transport, discovery, secure key
storage, preference storage, generic network wrappers.

**Not here:** feature-specific OS integrations. NotificationListener, Notification Center,
AccessibilityService, IME, MediaProjection, VideoToolbox, CoreGraphics capture and similar belong
to the owning feature (`features/<feature>/android|macos`). `platform/` is never a dumping ground.

## Code today
| Adapter | Android | macOS |
|---|---|---|
| Transport (TCP) | `ServerSocket`/`Socket` in `PairingCoordinator.kt`, `connectWithTimeout` (`Reliability.kt`), `acceptConnections` (`DeviceCore.kt`) | `NWListener`/`NWConnection` in `Pairing.swift` |
| Discovery | `android/core/discovery` (`NsdDiscoveryService`, Gradle module) | `BonjourDiscovery` (`Discovery.swift`) |
| Identity key storage | AndroidKeyStore (`AndroidIdentity`) | Keychain (`MacIdentity`) |
| Trust storage | SharedPreferences `bridgey.trust` | Keychain `dev.bridgey.mac.trust` |
| Network / permission wrappers | `ConnectivityManager` callbacks | `localNetworkPermissionDenied` (`Reliability.swift`) |

Related: [core](../core/README.md) · [context](../context/README.md)
