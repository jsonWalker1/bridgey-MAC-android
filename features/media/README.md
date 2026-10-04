# Media

**Answers:** what is playing on the other device, and how do I control it from here?

Two directions, two message families that never share vocabulary:
- **Android → Mac (Media Continuity, `media.remote.*`):** Android's primary `MediaSession`
  (Spotify, YouTube, …) shown live in the Mac menu bar, controllable from it and from the Mac's
  hardware media keys.
- **Mac → Android (`media.state` via quick actions):** the Mac's Music/Spotify player controlled
  from Android, optionally paused during phone calls.

**Owns:** session observation and command execution on each side, the Mac player UI, global
media-key routing, and the remote player state (this feature's Peer State).
**Does not own:** the connection, the quick-action transport (`QuickActions.*`, handoff/links).

## Code
| | |
|---|---|
| `android/` | [MediaContinuityManager](android/MediaContinuityManager.md) — Android → Mac |
| `macos/` | `MediaRemoteController`, `MediaRemoteView` (Android player on the Mac), `GlobalMediaCommandCenter` (media keys), `MediaController` (Mac player for Android) |
| still elsewhere | `media` quick-action requests in `QuickActions.kt`; `media.remote.action` sending and resets in the coordinators |

Payloads: `media.remote.v1` in [docs/protocol.md](../../docs/protocol.md).

## Rules
Commands carry the session generation (stale commands are ignored); track changes are throttled
(900 ms); controls are gated by what the active session supports. Sends go to the routed (active)
device until media routes by `deviceId`.

## Tests
`tests/android/MediaContinuityManagerTest.kt`, `tests/macos/MediaRemoteControllerTests.swift`,
`MediaRemoteCardLayoutTests.swift`, `GlobalMediaCommandCenterTests.swift`.
