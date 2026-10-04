# PhotoSyncManager

**Feature:** photos (Android) · **Status:** production.

## Purpose
Sends new photos and videos from the phone to the Mac automatically (into a folder and/or Photos),
over the existing Files transfer path.

## Why it exists
"New media appears on the Mac" without a cloud. It watches MediaStore, decides what has not been
synced yet, and hands each asset to Files — it never implements its own transfer.

## Ownership / non-responsibilities
Owns: the MediaStore observer, the baseline, the **ledger** of synced assets
(SharedPreferences `bridgey.photosync`, key `type:id:dateModified:size`) and the send loop.
Does **not** own the transfer (Files: `PairingCoordinator.sendSyncAsset` → `files.v1` with an
`assetKey`), or importing into Photos on the Mac (`PhotosImport.swift`).

## Non-obvious decisions
- **Baseline on first enable:** every asset that already exists is marked synced without being sent,
  so enabling the feature never dumps the whole library — unless the user explicitly opted into a
  one-time full-library sync.
- An asset is added to the ledger only after the Mac confirmed the transfer; an interrupted
  transfer is retried on the next scan.
- Scans are debounced (1.5 s) and re-run on MediaStore changes, settings changes and on connect.
- Requires both `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` (Android 13+).

## Multi-device note
Sends to the routed (active) Mac only. HW1: photos arrived in the Mac's sync folder; the Photos
import failed only because the test run had started the Mac app from a terminal (permission
attributed to the terminal).

## Tests
`PhotoSyncTest.kt` (ledger, pending assets).

## Related
Files feature (transfer engine in `PairingCoordinator.kt`) · macOS `PhotosImport.swift`
