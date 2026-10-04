# HW1 — Multi-device Core on real hardware (2026-10-04)

Gate before the repository migration: prove that the Core keeps **independent peer
relationships** on real devices. Not a proof that every feature is multi-device ready.

Builds: Mac and Android from `feature/multi-device` at `3eed362` (P0 #1 per-session writer)
+ `34335e9` (R1 Mac identity) + `4db1c30` (connection-service fix found during this test).

## Hardware

| Device | deviceId | Build |
|---|---|---|
| MacBook Air M4 ("Tomáš MacBook Air M4 můj") — local Mac | `ff185f1a…` | new, `/Applications/Bridgey.app` |
| MacBook Air ("MacBook Air - pracovní") — second Mac | `fdc4c4d5…` | new, copied via scp |
| Samsung Galaxy S23 Ultra (SM-S918B) | `25b15cab…` | new APK via adb |

All on the same Wi-Fi. Logs: Mac stdout/stderr captured to a file, Android via logcat (USB).

## Results

| # | Check | Expected | Observed | Result |
|---|---|---|---|---|
| 1 | Upgrade Mac + S23 in place | reconnect with existing trust, no pairing | `PAIRING verified` 1 s after launch; deviceIds and the pinned Mac key on the S23 unchanged | PASS |
| 2 | Single-peer features | work as before | notification sync, telemetry, media state and Photo Sync transfers all ran (Photo Sync files arrived in the sync folder) | PASS |
| 3 | Pair the second Mac | second session, S23 unaffected | paired via SAS; second Mac stayed connected through every later S23 event | PASS |
| 4 | Simultaneous sessions | both connected, independent heartbeats | S23 + second Mac connected together from 13:53 on | PASS |
| 5 | Drop one peer (S23 Wi-Fi off) | only S23 ends; second Mac stays | S23 heartbeat timeout after 32 s; second Mac session untouched and became the routed peer | PASS |
| 6 | Reconnect | S23 returns without pairing | S23 returned 80 s after Wi-Fi on (see note 1) | PASS |
| 7 | Restart the Mac app | same identity and trust, both return | S23 back in < 5 s, second Mac 25 s later, no pairing | PASS |
| 8 | One device, several endpoints | one device, one session | this Mac advertised on two interfaces; peers kept one session for it | PASS |
| 9 | No cross-peer delivery | inactive peer's feature messages not consumed | `ROUTING … inactive peer=fdc4c4d5 not consumed (media.state)` | PASS |
| 10 | Routing seam | preferred device reclaims routing, others stay connected | migration set S23 as preferred; on each S23 return routing moved back to it while the second Mac stayed connected | PASS (automatic; a manual switch in the UI was not exercised) |
| — | Identity stable (R1) | identity never replaced | second Mac launched from SSH hit `errSecInteractionNotAllowed`; identity kept, loaded normally once started in the GUI session (the old code would have generated a new identity here) | PASS |
| — | Duplicate protection per deviceId | no cross-device rejection | 0 rejections, 0 displaced sessions across 10 verified handshakes | PASS |

Second Mac session: connected once at 13:53:34 and never dropped during four S23 disconnects
(13:56, 14:00, 14:03 reinstall, 14:04 process kill).

**HW1: PASSED** for the Core criteria.

## Findings

1. **S23 reconnect delay is the phone's network stack, not Bridgey.** After Wi-Fi returns,
   Bridgey on the phone rediscovered the Mac within 2 s and retried with backoff, but Android
   returned `EHOSTUNREACH` for ~75 s (known S23 behaviour after a Wi-Fi change). Mac → phone
   dials never succeed on this phone (inbound blocked/unreachable); the phone always initiates.
   The 30-minute gap at 13:11 is most likely the same condition (phone logs for that window
   were no longer available).
2. **Fixed: Android crash on process restart while Bridgey is off** (`4db1c30`). The Web
   Handoff accessibility service started the connection foreground service unconditionally;
   it stopped itself without `startForeground()` → `ForegroundServiceDidNotStartInTimeException`.
   Verified on the S23 after the fix (disabled + rebind: no crash; enabled + rebind: service runs).
3. **Test-setup artefact: Photos import.** Running the Mac app from a terminal made macOS
   attribute Photos access to the terminal (denied), so synced photos landed in the folder but
   not in Photos. Relaunching through LaunchServices (`open --stdout/--stderr`) keeps logs and
   the app's own permissions.
4. **Known seam limitation (expected):** while a different device is routed, the S23's feature
   traffic is not consumed by the Mac's single-peer features.

## Not covered

Android ↔ Android, a second Android device, and simultaneous pairing of two new devices.
