# Identity

**Answers:** who am I? Identity is valid with no network, no peer and no discovery.

**Owns:** this device's `deviceId`, its long-term P-256 identity key, signing with that key,
and loading the identity from secure storage.
**Does not own:** trust decisions (→ [trust](../trust/README.md)), endpoints or sessions
(→ [presence](../presence/README.md), [connection](../connection/README.md)), the advert
(Presence builds it from Identity's public parts).

## Code today
| Platform | Where |
|---|---|
| macOS | `MacIdentity` (end of `macos/Sources/BridgeyMac/Pairing.swift`), `LocalDevice` (`DeviceCore.swift`), legacy `deviceID` migration `LegacyPreferences` (`Discovery.swift`) |
| Android | `AndroidIdentity` (end of `PairingCoordinator.kt`, AndroidKeyStore alias `bridgey.identity.p256.v1`), `device_id` created in `BridgeyApplication.onCreate` |

Storage: Mac UserDefaults `deviceID` + Keychain `dev.bridgey.identity` / `p256-signing-v1`
(this-device-only); Android SharedPreferences `bridgey` / `device_id` + AndroidKeyStore.

## Invariants
- A `deviceId` is bound to exactly one identity key, forever.
- **R1:** only a confirmed "not found" creates an identity. Any other storage error leaves the
  identity *unavailable*: nothing is signed or presented, the handshake closes the session and a
  later load retries. A stored but unreadable key is never overwritten.
- The private key never leaves Identity; Connection asks Identity to sign the session transcript.

## Lifecycle
Loaded at app start (`LocalDevice.load` on macOS). If unavailable (e.g. Keychain locked, or the
app started from an SSH session), it is reloaded on the next handshake.

## Known limitations
- `deviceId` and key live in different stores; losing only the key (Migration Assistant does not
  copy this-device-only Keychain items) is detected as "unavailable/new", not yet explained to the
  user. A stored public-key fingerprint next to `deviceId` is the planned detection.
- Android identity is not yet a separate component (`LocalDevice` on Android has no key).

## Tests
`macos/Tests/BridgeyMacTests/MacIdentityTests.swift` (R1: existing key, first creation, transient
errors, unreadable key, failed first save, reload, real Keychain round trip).

Related: [trust](../trust/README.md) · [connection](../connection/README.md) · [ARCHITECTURE](../../ARCHITECTURE.md)
