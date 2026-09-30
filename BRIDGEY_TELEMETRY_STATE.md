# BRIDGEY — DEVICE TELEMETRY STATE

Status as of 2026-09-30. Read this file + `CLAUDE.md` first; do not re-read the
full repo. Phase 1/2 committed in `7cd621e` (pushed to `origin/main`). Phase
3/4 + settings split (§12) is the newest work — see that section first.

## 1. WHAT THIS FEATURE IS

Phase 1 ("storage only") of a planned "device telemetry" feature: Android and
macOS each report their own storage usage to the paired peer, displayed on the
other device. Modeled directly on the existing `battery.send.v1` feature/message.
RAM and CPU are explicitly future phases, NOT started.

## 2. PROTOCOL (see `docs/protocol.md`, "Device telemetry" section)

- New message kind: `telemetry.update`.
- Payload: `{ "version": 1, "storageUsedBytes": Int64, "storageTotalBytes": Int64 }`.
  No `available`/free field on the wire (computed client-side as total−used).
  No sequence number, no timestamp (mirrors battery's minimalism — TCP already
  orders within a session; reconnect always forces a fresh resend).
- Capability: `BridgeyFeature.TELEMETRY` (Android) / `.telemetry` (macOS).
  Default ON, but OFF-for-legacy-peers (same bucket as CALLS/PING/KVM_INPUT).
- Malformed/out-of-range payload still kills the whole session (`fail()`/`throw`)
  — inherited from `battery.update`'s existing convention, not a new regression,
  deliberately not changed (would be a cross-cutting protocol change).

## 3. FILES TOUCHED (all in commit 7cd621e)

```
docs/protocol.md                                   new "Device telemetry" section
android/.../BridgeySettings.kt                      +TELEMETRY enum case + legacy default
android/.../PairingCoordinator.kt                   send/receive/state/lifecycle (see §6 grep)
android/.../AndroidStorage.kt              [NEW]    StatFs read + pure normalizedStorageStatus()
android/.../MainActivity.kt                         chevron + ModalBottomSheet + compact free-space row
android/app/src/test/.../BridgeySettingsTest.kt     +1 assert (TELEMETRY off-for-legacy)
android/app/src/test/.../AndroidStorageTest.kt [NEW] 3 tests
macos/.../BridgeySettings.swift                     +.telemetry case + legacy default
macos/.../Pairing.swift                             send/receive/state/lifecycle (see §6 grep)
macos/.../MacStorage.swift                 [NEW]    URLResourceValues read + pure normalizedStorageStatus()
macos/.../BridgyApp.swift → BridgeyApp.swift        chevron + deviceDetailsView + compact free-space row
macos/Tests/.../BridgeySettingsTests.swift          +1 assert
macos/Tests/.../MacStorageTests.swift      [NEW]    3 tests
```

## 4. UI (current, as of the LATEST user request)

Connected-device card, directly under the battery line: **free space only**
("170 GB free" / "▣ 170 GB free"), no total shown at this glance level. Total /
used / available all live one level deeper:
- macOS: chevron → `deviceDetailsView(name:)` (swap-in-place in the same
  `BridgeyPanel`, `@State private var showingDeviceDetails`).
- Android: chevron "›" → `ModalBottomSheet` (`showingStorageDetails` state in
  `ConnectedDeviceCard`).
No progress bar on the compact row (would enlarge the card — user explicitly
didn't want that). No new window/panel.

## 5. TESTS & BUILDS (all green at last check)

- `cd macos && swift test` → **233/233 pass**.
- `cd android && ./gradlew testDebugUnitTest assembleDebug` → **207/207 pass**, APK builds.
- Real device (Samsung S23 Ultra + Mac): confirmed bidirectional, twice, across
  two independent Mac-process reconnects, real byte values both directions, no
  duplicate/rapid-fire sends. UI itself was never visually screenshotted (phone
  was locked; Mac menu-bar popover needs a manual click, no accessibility
  permission to script it) — a quick visual sanity check is still worthwhile.

## 6. TWO BUGS FOUND LIVE THIS SESSION (both fixed + reverified on hardware)

1. **macOS dead-band bug (fixed)**: `publishLocalStorage` originally compared
   exact equality (`status != lastSentStorage`), so it resent every 10s
   heartbeat tick even on KB-level noise. Fixed with a 100 MiB threshold
   (`shouldResendStorage(_:previous:)` in `Pairing.swift`).
2. **Android reconnect bug (fixed)**: `lastSentStorage` was never reset across
   sessions. After the Mac process restarted, the dead-band kept comparing
   against the *previous session's* last-sent value — since phone storage
   barely changes in minutes, every resend got silently swallowed forever.
   Fixed by resetting `lastSentStorage = null` at the same 5 lifecycle sites
   where `mutableRemoteStorage.value = null` already resets: `cancel()`,
   `dismiss()`, `handle()` new-session branch, `handle()` clean-disconnect
   branch, `fail()`. Reverified on hardware across 2 reconnect cycles
   (resend now arrives ~23–27s after reconnect instead of never).

Grep entry points instead of re-reading whole files:
`grep -n "telemetry\|TELEMETRY\|Storage" android/.../PairingCoordinator.kt macos/.../Pairing.swift`

## 7. KNOWN GAPS

- **6th lifecycle site (`pause()`) — FIXED in `abc2270`**: `pause()`
  (`PairingCoordinator.kt` line 1178, called from `stop()`) nulled
  `mutableRemoteBattery.value` but was missing `mutableRemoteStorage.value = null`
  and `lastSentStorage = null`. Same bug class as §6.2, different call site.
  Fixed with the same 2-line pattern used at the other 5 sites. Verified: all
  8 occurrences of `mutableRemoteBattery.value = null` in the file now have a
  matching storage reset (grep + Local AI cross-check, both agree). Android
  unit tests green (`testDebugUnitTest`, BUILD SUCCESSFUL). Not re-verified on
  real hardware — pure lifecycle reset, diff is exactly 2 lines.
- **`/Applications/Bridgey.app` is stale** (different MD5/older timestamp than
  the dev build in `.build/debug/Bridgey.app`) — if Bridgey is opened via
  Dock/Spotlight it may resolve to the old copy with none of this feature.
  Not updated (production-app decision, out of scope). See also
  `reference_bridgey_dev_commands.md`.

## 8. DELIBERATE SCOPE DECISIONS (don't re-litigate without new input)

- Capability named `TELEMETRY` (not `storage`) — explicit user instruction,
  overriding a PM-research suggestion to name it `storage` for scope-safety.
  Payload kept strictly storage-only fields anyway, so the scope-safety concern
  is satisfied regardless of the name.
- RAM (phase 2): **implemented 2026-09-29, see §11**. CPU (phase 3): not started.

## 9. NEXT STEP (pick up here)

1. ~~Fix the `pause()` gap~~ — done, `abc2270`.
2. `/Applications/Bridgey.app` update — user decided: leave as dev-only for now.
3. ~~Phase 2 (RAM)~~ — done, see §11. **Not yet committed** — working tree has
   the implementation, awaiting explicit commit instruction.
4. Consider Phase 3 (CPU) — not started, not scoped.
5. Optional: get an actual visual screenshot of both UIs (never captured this
   session; RAM verification below used adb screenshots instead).

## 10. USEFUL COMMANDS

```sh
# macOS
cd macos && swift build && swift test
./build-app.sh && pkill -f "BridgeyMac$"; sleep 1; open .build/debug/Bridgey.app

# Android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Live verification
adb logcat -d -s "Bridgey:*" | grep -E "PAIRING verified|storage sent|storage received"
```
See `reference_bridgey_dev_commands.md` (Claude memory) for the Samsung
Wi-Fi/accessibility-service gotchas — unrelated to this feature but still real.

## 11. PHASE 2 (RAM) — IMPLEMENTED 2026-09-29, NOT YET COMMITTED

**Single mechanism, identical principle to storage** — no subscribe/unsubscribe,
no separate fast loop (an earlier iteration this session had one; explicitly
reverted per user instruction to keep RAM and storage on one shared
mechanism). RAM is sampled in the same always-on background loop as storage,
at the same cadence, with the same dead-band, sent on the same message kind.

- **Android**: `publishLocalMemory(force: Boolean = false)` mirrors
  `publishLocalStorage` exactly — called from the same 60s
  `while(true) { delay(...); publishLocalStorage(); publishLocalMemory() }`
  loop, and from the same two `force = true` sites (settings-feature-toggle
  collect, `completeIfConfirmed()` on reconnect). Dead-band:
  `MEMORY_CHANGE_THRESHOLD_BYTES = 100 MiB` (same constant value as storage's,
  own name), tracked via `lastSentMemory`.
- **macOS**: `publishLocalMemory(force: Bool = false)` mirrors
  `publishLocalStorage` exactly — called from the same 10s heartbeat
  `DispatchWorkItem`, and the same `force: true` sites (settings-collect,
  `receiveFeatureState`'s resync, `completeIfConfirmed()`). Dead-band:
  `memoryChangeThresholdBytes = 100 MiB`, tracked via `lastSentMemory`.
- **Payload/message kind**: unchanged from the original design —
  `telemetry.update` with `storageUsedBytes`/`storageTotalBytes` and
  `memoryUsedBytes`/`memoryTotalBytes` all optional on the wire, so one
  message carries either or both pairs. No new message kinds, no new
  transport.
- **Samplers unchanged**: Android `AndroidMemory.kt`
  (`ActivityManager.getMemoryInfo()`, no permission needed); macOS
  `MacMemory.swift` (`ProcessInfo.physicalMemory` + `host_statistics64` VM
  page counts, active+wired+compressed).
- **Lifecycle**: `remoteMemory` + `lastSentMemory` reset together at every
  site `remoteStorage`/`lastSentStorage` already reset at, both platforms —
  `resetRemoteMemoryState()` (Android) / inline (macOS) now just does that,
  nothing session/subscription-related left to tear down.
- **UI**: "Memory" section in both device-details views (unchanged from
  before). **New**: compact connected-device-card row now also shows
  `"🧠 <free> free"` directly under the existing `"▣ <free> free"` storage
  line, on both platforms — reads the always-populated `remoteMemory`, no
  "waiting" placeholder (mirrors storage's placeholder only in the details
  view, not the compact card).
- **Bug found + fixed during real-device testing of the earlier
  subscribe-based iteration** (now moot since that code is gone, but the
  general lesson stands): a `Session.send()` call made directly from a
  Compose UI callback (main thread) throws `NetworkOnMainThreadException`,
  silently swallowed by `Session.send()`'s own `runCatching` — invisible to
  unit tests/`assembleDebug`, only surfaces on real hardware. Now moot since
  all sends happen from the existing `scope.launch`-wrapped
  `publishLocalStorage`/`publishLocalMemory` pattern, same as before.
- **Tests**: `AndroidMemoryTest.kt` (5 tests), `MacMemoryTests.swift` (4 tests,
  incl. one live-host smoke test) — unchanged, still test the pure
  normalization function, not the new send/schedule wiring. Full suites
  green: Android `testDebugUnitTest` BUILD SUCCESSFUL, macOS `swift test`
  237/237.
- **Real-device validation (Samsung S23 Ultra ↔ this Mac)**: ✅ Mac's RAM
  arrives on Android via the background loop (confirmed via `adb logcat`:
  `PLUGIN memory received`, same cadence/dead-band behavior as storage's own
  log lines in the same window), ✅ main connected-device-card shows
  `"🧠 4,5 GB free"` under the storage line (screenshot), ✅ device-details
  sheet still shows the full Storage + Memory sections with matching values
  (screenshot), ✅ storage telemetry unaffected throughout. **Not verified**:
  the reverse direction (Mac viewing Android's RAM) — no Accessibility
  permission available to script the Mac menu-bar click; code path is fully
  symmetric with the verified direction. Also not separately verified: RAM
  behavior across a mid-session disconnect/reconnect.

## 12. PHASE 3 (CPU) + PHASE 4 (TEMPERATURE) + PER-METRIC SETTINGS — 2026-09-30

**Battery-conscious lifecycle reaffirmed and generalized**: ALL telemetry
(storage/memory/CPU/temperature) is now on-demand only, driven by one shared
`telemetry.subscribe`/`telemetry.unsubscribe` pair tied to the app being in
the *foreground* (Android: `LifecycleStartEffect` on the top-level screen)
or the panel being *open* (macOS: `onAppear`/`onDisappear` on `BridgeyPanel`'s
body, not just the deeper device-details drill-down). Nothing is sampled or
sent while backgrounded/closed. While subscribed, one shared ~3s loop calls
all four publish functions; each is independently gated by its own Settings
feature, so no per-metric subscription bookkeeping was needed.

- **Settings split**: `BridgeyFeature.TELEMETRY` (one shared toggle) is now
  four independent cases — `STORAGE`/`MEMORY`/`CPU`/`TEMPERATURE` (Android),
  `.storage`/`.memory`/`.cpu`/`.temperature` (macOS). This reused the
  existing generic capability-negotiation/Settings-UI architecture
  unchanged (`BridgeyFeature.entries`/`.allCases` iteration already renders
  one toggle row per case and negotiates each independently over the
  existing `features.update` message) — **zero new protocol or UI
  plumbing**, confirmed on real hardware: Settings now shows "Storage
  information" / "Memory (RAM) information" / "CPU usage" / "Temperature /
  thermal status" as four separate switches, each independently
  persisted/negotiated.
- **"Disabled = hidden"**: every telemetry UI section (compact card line +
  device-details block) is now wrapped in `if (metricEnabled)` rather than
  showing an "Unavailable" placeholder — confirmed on hardware: turning off
  "CPU usage" made the ⚙ line vanish from the compact card and the whole CPU
  block vanish from details, while Storage/Memory/Temperature kept updating
  live, unaffected. `adb logcat` confirmed CPU sampling/transmission
  actually stopped (no more `PLUGIN cpu sent/received` lines) while
  temperature kept flowing normally in the same window.
- **CPU (Phase 3)** — Android: `/proc/stat` delta between two samples
  (`AndroidCpu.kt`). Semantics: aggregate all-core utilization, 0-100%,
  first-sample-seeds-silently, rollover/zero-delta → explicit unavailable.
  macOS: `host_statistics(HOST_CPU_LOAD_INFO)` (`MacCpu.swift`), same
  delta-ratio algorithm. `cpuPercent`/`cpuUnavailable` fields on
  `telemetry.update`.
- **Temperature (Phase 4)** — researched before writing any code (see
  `AndroidTemperature.kt`/`MacTemperature.swift` doc comments for the full
  reasoning): neither platform exposes real Celsius through a normal,
  permission-free *public* API. Both send `thermalState` (Android:
  `PowerManager.getCurrentThermalStatus()`, 7 levels, API 29+; macOS:
  `ProcessInfo.thermalState`, 4 levels) as the reliable, honest baseline.
  **Android bonus**: real CPU-area °C from raw `/sys/class/thermal` sysfs
  zones (confirmed empirically readable *without any permission* on this
  project's Samsung S23 Ultra, Android 16 — NOT a documented/guaranteed API,
  commonly root-locked on other OEMs/versions) — max across zones whose
  `type` starts with `cpu` (`cpuss-*`, `cpu-<cluster>-<core>`), sent as
  `temperatureCelsius` alongside `thermalState`. **macOS deliberately gets no
  Celsius** — real temperature there requires the private/undocumented SMC,
  explicitly out of scope without separate approval. A shared
  `thermalDisplayLabel()` (implemented natively on each platform, same
  logic) buckets either platform's raw state string into one understandable
  4-tier label (Normal/Fair/Warm/Serious/Critical) for UI color/text — the
  real platform-native state is still what's stored/sent, this is display-
  only.
- **Real bug found + fixed via live-device testing (not caught by unit
  tests)**: `currentAndroidCpuTemperatureCelsius()`'s first version wrapped
  the *entire* zone scan in one try/catch — one specific zone
  (`thermal_zone75-81`/`89-91` on this device, likely camera/modem sub-zones
  that go briefly unreadable) threw `EINVAL` on read, which aborted the
  whole scan via exception propagation and silently discarded every OTHER
  zone's perfectly good reading, permanently reporting `celsius=null` even
  though `cpuss-*`/`cpu-*` zones were fine. Fixed by wrapping each zone's
  read in its own try/catch so one bad zone no longer poisons the rest.
  Confirmed via `adb logcat` before/after: `celsius=null` → `celsius=50`,
  `celsius=46` (real, plausible values). **Lesson for next session**: this
  exact class of bug (per-item exception scoping in a scan/aggregate loop)
  is easy to write and easy to miss without literally reading live log
  output — the pure unit tests for `maxCpuTemperatureCelsius()` all passed
  throughout, since they don't exercise the file-scan/exception-handling
  layer at all.
- **Tests**: `AndroidCpuTest.kt` (10), `MacCpuTests.swift` (8),
  `AndroidTemperatureTest.kt` (9), `MacTemperatureTests.swift` (4, incl. one
  live-host smoke test), plus settings-independence assertions added to both
  `BridgeySettingsTest.kt`/`BridgeySettingsTests.swift`. All pure
  calculation/mapping functions — deliberately no new mock Session/network
  harness (matches the task's own "don't build a large mock harness"
  instruction); the settings×lifecycle interaction claims (disabled →
  not sampled/transmitted/subscribed, re-enable resumes, reconnect respects
  setting) are verified by the real-device pass below instead. Full suites
  green: Android `testDebugUnitTest` + `assembleDebug`, macOS `swift test`
  (250/250) + `swift build`.
- **Real-device validation (Samsung S23 Ultra ↔ this Mac)**: ✅ connect, ✅
  compact card shows all 4 metrics (▣ storage, 🧠 memory, ⚙ CPU%, 🌡
  temperature) live-updating while app foregrounded, ✅ device-details shows
  all 4 sections with matching values, ✅ Settings shows 4 independent
  toggles (screenshot), ✅ disabling CPU hides it everywhere immediately and
  stops its traffic while Storage/Memory/Temperature keep working
  (screenshot + logcat), ✅ Android's own CPU-unavailable path confirmed
  live (`/proc/stat` permission denied on this device, exactly as found in
  Phase 3's own research — sends explicit `cpuUnavailable: true`, never a
  fake number). **Not verified this pass**: re-enabling CPU after disable
  (session ended before that step — user chose to toggle it back on
  manually rather than via further scripted taps); Mac-side temperature
  display (no Accessibility permission to script the Mac menu-bar open, same
  known limitation as Phase 2); mid-session disconnect/reconnect specifically
  for CPU/temperature state.
- Committed together with this state update — see git log for the exact hash.
