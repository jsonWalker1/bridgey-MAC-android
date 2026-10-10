# Photos (Photo Sync)

**Answers:** how do new photos and videos from the phone reach the Mac automatically?

**Owns:** watching the phone's media library, the baseline and the ledger of synced assets
([PhotoSyncManager](android/PhotoSyncManager.md), `PhotoSync`), and importing received media into
Photos on the Mac (`PhotosImport`, add-only access).
**Does not own:** the transfer — it uses the **files** feature (`sendSyncAsset` → `files.v1` with an
`assetKey`); the sync folder choice lives in settings for now.

## Code
| | |
|---|---|
| `android/` | `PhotoSyncManager`, `PhotoSync` (ledger keys, pending assets) |
| `macos/` | `PhotosImport` |

## Per peer (MD-8)
Photo Sync goes to the **preferred Mac** — the user's "Use … for these features" choice
(`preferredDeviceId`) — and only to it: when it is offline or cannot take photos, nothing is sent,
even though routing itself falls back to another connected Mac. Only when no preferred Mac is
configured the routed peer is used (decision D1). The target is resolved **once per batch**
(`photoSyncTarget`, applicability `PHOTO_SYNC` for that device); every asset of the batch goes to it
(`sendSyncAsset(…, deviceId)`), and the batch stops as soon as the target is no longer eligible
and never continues on another connected peer. A scan requested while a batch runs (new photo,
settings, routing or directory change) waits for it (`PhotoSyncBatchGate`) instead of cancelling
it, so an in-flight asset is not sent again to another target. When the preferred Mac reconnects,
the next scan targets it again. The ledger stays global: an asset synced to one Mac is not sent to
another. After 3 consecutive failed assets a batch ends and gives way to the next scan. The Mac
side is unchanged (it accepts `files.*` from any connected peer since MD-6).

## Rules
Enabling the feature never dumps the existing library (baseline) unless the user opts in. The Mac
needs Photos "add" permission for its own app identity — starting the app from a terminal attributes
it to the terminal instead (HW1).

## Tests
`tests/android/PhotoSyncTest.kt` (ledger keys, MD-8 batch target and gate).
