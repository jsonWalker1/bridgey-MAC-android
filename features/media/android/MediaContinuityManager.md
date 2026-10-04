# MediaContinuityManager

**Feature:** media (Android → Mac direction) · **Status:** production; hardware-validated with
Spotify and YouTube on a Galaxy S23 Ultra.

## Purpose
Publishes Android's primary active `MediaSession` (title, artist, artwork, progress, playback state,
supported actions) to the Mac and executes play/pause/next/previous/seek/volume commands the Mac
sends back (`media.remote.*`).

## Why it exists
Any app exposing a `MediaSession` becomes controllable from the Mac menu bar and global media keys,
without per-app integrations. It is a separate message family from the Mac → Android direction
(`media.state` via quick actions) so the two never collide on the same vocabulary.

## Ownership / non-responsibilities
Owns session observation, primary-session choice, generation tracking and command execution.
Does **not** own the Mac player UI (`MediaRemoteController.swift`, `MediaRemoteView.swift`),
global media keys (`GlobalMediaCommandCenter.swift`) or the connection.

## Non-obvious decisions
- **One primary session, deterministic:** prefer what is playing (ties → most recently started);
  otherwise keep the previous primary if it still exists (pausing must not blank the mini-player).
- **Generations:** a Mac command must target the generation Android is on, otherwise it is stale
  and ignored.
- **Track-change throttle (900 ms):** skipping again before the player loaded the next item desyncs
  its queue — on YouTube this surfaced as "this content can't be played" (reproduced on hardware
  with clicks 300–700 ms apart and held media keys).
- **Position is re-anchored to wall clock:** `PlaybackState` is anchored to
  `elapsedRealtime()`, meaningless on the Mac; implemented as plain functions so it is JVM-testable.
- Volume is normalised to 0–100 from whatever scale the session uses.
- Observation runs for the app's lifetime (cheap, event-driven); only sending is gated by
  availability. After a fresh connection everything is resent, artwork included.

## Multi-device note
Sends go to the routed (active) Mac only; this is the compatibility seam until media routes by
`deviceId`.

## Tests
`MediaContinuityManagerTest.kt`; Mac side `MediaRemoteControllerTests.swift`.

## Related
`QuickActions.kt` (Mac → Android media) · macOS `MediaRemoteController.swift`
