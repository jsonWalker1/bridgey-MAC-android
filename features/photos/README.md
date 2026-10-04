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

## Rules
Enabling the feature never dumps the existing library (baseline) unless the user opts in. The Mac
needs Photos "add" permission for its own app identity — starting the app from a terminal attributes
it to the terminal instead (HW1).

## Tests
`tests/android/PhotoSyncTest.kt`.
