# Bridgey Protocol v1

Status: mixed implementation reference and future design draft. The native
clients currently use newline-delimited JSON over TCP, **not TLS/WebSockets**.
The proposed WebSocket envelope and `core.hello` negotiation below are future
design, not the wire format shipped in 0.5/0.6. JSON is UTF-8 encoded.

## Current native transport (0.5/0.6)

`PairingMessage` / Android `Message` use `kind`, `sessionId`, optional
`messageId`, and message-specific fields. Encrypted payloads use base64 `nonce`
and `ciphertext` (AES-GCM ciphertext followed by its authentication tag).
Native frames have a 65,536-byte limit. The session ID salts HKDF-SHA-256 for
ephemeral P-256 ECDH; the shared info is `bridgey-pairing-v1`. The displayed code
is derived from the first four key bytes modulo 1,000,000. Confirmation HMACs
bind identity keys; P-256 signatures bind session ID, ordered device IDs and
ephemeral keys. Trusted reconnects verify the stored peer signing identity.

Payload encryption is application-layer protection, not TLS. Routing headers
and some acknowledgements remain outside the encrypted payload; traffic
metadata is visible. Replay caches are bounded and session-local, not persistent.
The future transport design must not be advertised as an implemented guarantee.

## Discovery

Browse and publish DNS-SD service type `_bridgey._tcp.local.`. TXT values are
UTF-8 and advisory:

| Key | Meaning | Limit |
| --- | --- | --- |
| `id` | Stable random UUID, used only as a hint before authentication | 36 bytes |
| `name` | User-visible device name | 64 bytes |
| `version` | Highest supported envelope major version | 8 bytes |
| `platform` | `android`, `macos`, or a future identifier | 16 bytes |

The SRV port identifies the TLS WebSocket listener. TXT, hostnames, addresses,
and ports are attacker-controlled until the peer authenticates. Implementations
must deduplicate discoveries by service instance and refresh endpoints on every
network change.

## Framing and envelope

After the TLS WebSocket opens, each text frame contains exactly one JSON object:

```json
{
  "version": 1,
  "id": "018f5228-76c7-7b37-a42c-3cfe6f78219a",
  "type": "clipboard.update",
  "timestamp": 1786550000000,
  "replyTo": null,
  "expectsReply": false,
  "payload": {}
}
```

`id` is unique per sender (UUIDv7 recommended). `timestamp` is Unix epoch
milliseconds and is used for diagnostics/expiry, not message ordering. `replyTo`
correlates a response or acknowledgement. Receivers keep a bounded, persistent
window of recently accepted IDs per peer; a repeated ID is acknowledged if
needed but its side effect is not executed again.

Limits before negotiation for the planned capability envelope are 256 KiB per
JSON frame, depth 32, string length 128 KiB, and 1,024 keys. The current native
newline transport intentionally applies a stricter 65,536-byte frame limit
before UTF-8/JSON decoding. A malformed, oversized, or unterminated frame closes
the session before plugin dispatch. File chunks remain bounded independently.

## Session negotiation

The first authenticated application message is `core.hello`:

```json
{
  "deviceId": "550e8400-e29b-41d4-a716-446655440000",
  "deviceName": "Semyon's MacBook Pro",
  "platform": "macos",
  "protocolVersions": [1],
  "capabilities": ["battery.send.v1", "clipboard.v1", "files.v1", "notifications.receive.v1"]
}
```

The selected version is the highest intersection. Capability negotiation is the
set intersection; absence means disabled. Device ID must match the identity
bound to the pinned key. No common version closes the connection with
`unsupported_version`.

Core types are `core.hello`, `core.ack`, `core.error`, `core.ping`, and
`core.pong`. An acknowledgement payload contains `status` (`accepted`,
`completed`, or `rejected`). Errors contain a stable `code`, safe `message`, and
optional details. Error text must not disclose secrets.

The current native clients also exchange an authenticated, encrypted
`features.update` message after pairing and whenever local policy changes. Its
version 1 payload contains a complete boolean map for `clipboard`, `files`,
`notifications`, `battery`, `find_device`, `ping`, `calls`, `links`, and `media`. A UI action is
available only when both peers report the corresponding feature as enabled. Clients
predating this message are treated as enabling the original v1 features for
compatibility; the later `calls`, `ping`, `links`, and `media` features are disabled unless a peer
advertises them explicitly.

## Pairing flow

Pairing runs only after a user selects a discovered peer:

1. Both peers create ephemeral P-256 ECDH key pairs and exchange
   `pairing.offer`/`pairing.answer` containing nonces and public keys.
2. Each validates all fields and derives the same secret with platform crypto.
3. HKDF-SHA-256 derives independent verification and session keys, binding the
   ordered public keys, nonces, device IDs, and protocol version into `info`.
4. A six-digit code derived from the verification key is displayed on both
   devices. It is never sent over the network.
5. Each user explicitly confirms; peers exchange authenticated
   `pairing.confirm` records containing their long-term public keys.
6. Trust is stored only after both confirmations verify. Any timeout, mismatch,
   rejection, or disconnect erases ephemeral state.

Pairing offers expire after two minutes and cannot be silently retried. The
short code is a human MITM check, not a password. Detailed primitive and storage
requirements are in `SECURITY.md`.

## Plugin messages

### Quick actions (native 0.6)

`quick.request`, `quick.result` and `media.state` are AES-GCM-encrypted payloads
accepted only in the current connected session. Requests/results are limited
to 8 KiB plaintext; media state to 32 KiB. The normal 65,536-byte frame bound
still applies. Unknown versions, malformed values and stale sessions are ignored.

Requests contain `version: 1`, UUID `requestId`, `feature`, `action`, string
`value`, and positive integer `sequence` (at most 2^53−1). Receivers retain a
per-feature sequence high-water mark for the entire session, independent of
the outer message ID/replay cache; old encrypted requests cannot re-execute by
changing that ID. Sender sequences increase and requests are not auto-retried.
An authenticated result echoes the inner requestId and feature with boolean
`accepted`. Pending operations time out after eight seconds and are cleared
on disconnect or feature disable. Timeout means “not confirmed”, not an assertion
that an operation definitely never reached the other peer.

- `links` / `offer`: 4,096 UTF-8 bytes maximum, HTTP(S) only, nonempty host,
  no user credentials, whitespace/control characters or backslashes, valid
  optional port. One pending received link per device; a second is declined.
  Acceptance means queued for explicit local Open/Dismiss, **not** opened.
- `media`: Android → Mac, only `toggle`, `pause`, `next`, `previous`, `seek`
  (integer seconds 0…604800), `volume` (integer 0…100). The Mac must locally
  opt in and select running Music or Spotify. Commands map to fixed player
  AppleScript instructions, never received source code or a remote shell.
  Automation failures and a busy player are reported as rejected. Accepted
  means the player command completed, not that a particular track must exist.
- `media.state`: Mac → Android; `version`, `player`, `title`/`artist` (256
  characters each), boolean `playing`, integer `position`/`duration`
  (0…604800), `volume` (0…100), bounded `detail`. Optional base64 `artwork`
  contains at most 16 KiB JPEG, at most 128×128 pixels. The receiver validates
  both encoded size and decoded dimensions before allocating a bitmap.

Music artwork comes from player automation, not filesystem browsing; it is
optional and may be unavailable. Spotify artwork is not downloaded. Metadata
refreshes after commands/settings changes and on the ten-second heartbeat.

### Media Continuity (`media.remote.v1`)

A separate, dedicated message family for the opposite direction from `media`/
`media.state` above: Android's own currently active `MediaSession` (Spotify,
YouTube, or any other app that exposes one), shown and controllable live on
macOS. Kept distinct from the `media` feature's wire vocabulary so the two
directions — "Mac's player, shown on Android" and "Android's player, shown on
Mac" — can never collide or be confused with each other.

- `media.remote.state`: Android → Mac, at most 32 KiB. `version`, monotonic
  `generation` (bumped only when the primary session itself changes — a
  different app, or a session appearing/disappearing — never on every
  playback tick), monotonic per-generation `sequence`, boolean `hasSession`.
  When `hasSession`, also `packageName`, human-readable `appLabel` (never the
  raw package identifier — see below), `title`/`artist`/`album` (256
  characters each), `playing`, `position`/`duration`/`playbackSpeed`,
  `positionTimestamp` (epoch milliseconds the position was sampled at, so the
  receiver can extrapolate a live progress bar without polling Android),
  `capabilities` (subset of `play`/`pause`/`next`/`previous`/`seek`/`volume`,
  reflecting what the active session actually supports), optional `volume`
  (0…100, only present when the session's volume is actually adjustable —
  local device-stream volume for ordinary phone playback, or a remote
  `VolumeProvider` otherwise), and optional `artworkHash`/`artwork` (SHA-256
  hex / base64 JPEG, at most 16 KiB, at most 128×128 — sent only when the
  hash changes from what the receiver already has cached).
- `media.remote.action`: Mac → Android. `version`, UUID `requestId`, `action`
  (`play`/`pause`/`toggle`/`next`/`previous`/`seek`/`volume`), integer
  `value` (seek position in milliseconds, or volume percent), and the
  `generation` the Mac last received — Android rejects the action outright if
  its current generation has since moved on, rather than applying a command
  to a session the Mac's UI no longer actually reflects.
- `media.remote.action.ack`: Android → Mac. `version`, `requestId`, boolean
  `accepted`, optional `reason` (`no_session`, `stale_generation`,
  `unsupported`, `invalid`, `execution_failed`, `rate_limited`).

`next`/`previous` are rate-limited to one accepted change per 900 ms
regardless of source (GUI or a physical media key): some players (YouTube in
particular) have to fetch and buffer the next item rather than switch
instantly, and issuing another skip before that settles desyncs the player's
own queue position badly enough to surface as a playback error.

Android's app name for the current session is resolved through
`PackageManager`, never shown as the raw reverse-DNS package name — a result
that equals the package name itself (the documented fallback when resolution
fails, most commonly a package-visibility restriction) is treated as "no
name resolved" and macOS falls back to the platform name alone.

macOS also intercepts real hardware media keys (a keyboard's dedicated
Play/Pause/Next/Previous, Touch Bar equivalents) globally, independent of
whether Bridgey is the frontmost app, and routes them through the same
action path as the on-screen controls — to Android's session or to the
existing Mac-native player (`media`/`media.state` above), whichever is
actually current. This requires the user to separately grant macOS Input
Monitoring; see the Permissions section in the README.

### Battery (`battery.send.v1`)

Either battery-powered peer sends `battery.update` after a secure session is
established and when its battery state changes. Android uses system battery
broadcasts; macOS checks its public IOKit power-source state on connection and
while the heartbeat is active:

```json
{
  "level": 79,
  "isCharging": true
}
```

`level` is an integer from 0 through 100. Receivers reject out-of-range or
malformed values. Battery updates contain no device identifier because the
authenticated session already binds them to the paired sender.

### Clipboard (`clipboard.v1`)

`clipboard.update` remains the compatible plain-text form: its decrypted payload
is UTF-8 text. `clipboard.rich` carries versioned UTF-8 JSON:

```json
{
  "version": 1,
  "text": "example",
  "html": "<p><strong>example</strong></p>"
}
```

The combined text and HTML content is limited to 32 KiB before encryption. The
text field is mandatory and is always written as a fallback; supported clients
also write the HTML representation. Receivers reject malformed, unsupported,
empty, or oversized payloads. Each encrypted update has a unique message ID and
the current transport replies with `clipboard.ack` after the write. If local
policy disabled clipboard handling while a peer still had stale capability
state, it replies with `clipboard.rejected` and immediately resends the
encrypted `features.update`; senders time out rather than displaying an
unbounded sending state.

### Files (`files.v1`)

`files.offer` carries transfer ID, display filename, MIME type, unsigned byte
size, and SHA-256. `files.accept` selects a binary stream; `files.progress` is
advisory; `files.cancel` is idempotent; `files.complete` confirms the final hash.
Paths from a sender are never accepted. Implementations stream through bounded
buffers, enforce negotiated size limits, and delete or clearly mark partials.

The current JSON transport streams encrypted 24 KiB chunks rather than a raw
binary substream. An encrypted `files.offer` contains `transferId`, `name`,
`mimeType`, `size`, and the base64-encoded SHA-256. After `files.accept`, each
`files.chunk` carries the transfer ID, a monotonically increasing sequence
number, and an independently AES-GCM-encrypted chunk. An encrypted
`files.complete` repeats the transfer ID and hash; the receiver replies with
`files.complete.ack` only after byte count and hash verification and the atomic
rename of the partial file. The macOS v1 receiver saves collision-safe names in
a user-selected directory (default `~/Downloads/Bridgey`) and removes partial
files when a transfer is interrupted.

Version 1 does not resume a partial file stream. If a connection is interrupted,
the receiver deletes its pending partial file and both clients mark the transfer
as interrupted. A user-initiated retry creates a new transfer identifier,
recomputes the digest, and sends the file again from byte zero.

The Android v1 receiver writes through `MediaStore` to `Download/Bridgey` with
`IS_PENDING` set until verification, so partial files are hidden and deleted on
failure without requiring broad storage access.
Either peer can send `files.cancel` with the transfer ID. The sender stops
reading and sending chunks, while the receiver closes and deletes its partial
file. Cancellation is idempotent and leaves the authenticated session usable.
For Mac-to-Android transfers, Android sends cumulative `files.chunk.ack`
messages and macOS keeps at most 64 chunks (1.5 MiB) unacknowledged. This bounds
memory and cancellation latency without limiting throughput to one network
round trip per chunk.
An offer repeats its transfer ID in the outer session message so a receiver
whose local file policy is disabled can return `files.rejected` without
decrypting or accepting the offer. It then resends `features.update` to repair
stale UI state on the sender.

#### Photo Sync (reuses `files.v1`)

Photo Sync (Android → Mac only, MVP) reuses `files.v1` wire messages as-is —
there are no new message kinds. The only protocol change is one optional field,
`assetKey`, added to `files.offer`. Its presence is the sync signal:

- `assetKey` absent: today's manual file share, gated by the `files` feature,
  written to the general receive folder.
- `assetKey` present: a Photo Sync asset, gated by the separate `photo_sync`
  feature instead, written to a dedicated sync folder (default
  `~/Pictures/Bridgey` on macOS), and checked against a dedup index before
  being accepted.

`assetKey` is the same base64 SHA-256 already computed and sent as `sha256` for
transfer verification — content hash doubles as the stable cross-device asset
identity, so no separate identity scheme was introduced. Android keeps a local
SharedPreferences-backed ledger keyed by `(MediaStore id, dateModified, size)`
so it never re-offers an asset it already sent successfully; macOS keeps a
flat, newline-delimited `.bridgey-sync-index` file inside the sync folder as a
backstop, rejecting (via the existing `files.rejected`) an offer whose
`assetKey` it already has even if Android's ledger were ever lost (reinstall,
data clear). Like general file transfers, Photo Sync has no resume: an
interrupted asset is simply retried from byte zero on the next scan, since it
was never marked synced.

### Notifications (`notifications.send.v1`, `notifications.dismiss.v1`)

`notifications.post` carries package, application name, opaque notification ID,
title, text, timestamp, and an optional size-bounded base64 PNG icon. Content is
sensitive and must not be logged. `source=bridgey` messages are never forwarded.
`notifications.dismiss` carries the opaque notification ID back to Android when
the user dismisses its mirrored macOS notification. `notifications.remove`
carries the same ID to macOS when the original notification disappears on
Android. Both reference payloads are encrypted, replay-protected, limited to a
512-character opaque ID, and valid only for the authenticated device session.
Clients that do not recognize these messages ignore them. Future
`notification-actions.v1` messages reference the same opaque ID and a separately
scoped, 64-character action token. A `notifications.post` may contain up to four
actions with a 64-character title and `allowsReply` flag. macOS returns
`notifications.action` with the notification ID, action token, and an optional
reply of at most 4,096 characters. Android executes only tokens retained for the
still-active source notification; arbitrary `PendingIntent` data never crosses
the transport.

Per-application forwarding filters are enforced on Android before payload
construction and are intentionally local settings rather than protocol state.
The optional macOS notification history is also local-only, off by default,
bounded to 200 items and seven days, and never synchronized to a peer.

In the current JSON transport, the notification payload is AES-GCM encrypted
and contains `packageName`, `applicationName`, `notificationId`, `title`, `text`,
and the Android post time in Unix milliseconds. Android excludes Bridgey's own
foreground notification, ongoing items, group summaries, secret notifications,
and empty content before encryption. A `CATEGORY_CALL` notification may include
`callType` with one of `incoming`, `ongoing`, `screening`, or `unknown`; calls
are the only ongoing notifications forwarded. Call controls reuse the same
scoped notification action tokens and therefore exist only when the Android
phone application supplies the corresponding `PendingIntent`.

### Find device (`find-device.v1`)

Either connected peer can send an encrypted `find.start` payload containing an
opaque `alertId`. The receiver plays a repeating local alert until either user
stops it. An encrypted `find.stop` with the same logical alert scope stops the
local sound. The receiver answers with encrypted `find.started` or
`find.stopped`, and only that acknowledgement changes the sender's displayed
state. All messages use unique message IDs and are accepted only inside an
authenticated paired session.

Android presents a separate ongoing find-device notification with a Stop
action while its alert is active. Stopping from that action, from either app,
or disconnecting stops the local alert; find-device state is never persisted
across sessions.

### Ping (`ping.v1`)

`ping.request` is a lightweight, one-shot presence alert and is intentionally
separate from Find Device. It carries an encrypted `{ "version": 1 }` payload,
uses a unique replay-protected message ID, and causes the receiving device to
play one short system sound. Android also shows a short local toast. The
receiver returns `ping.ack` with the request message ID, so the sender reports
delivery or a bounded five-second timeout instead of leaving an indefinite
status. Ping never starts a repeating sound and has no state to restore after a
disconnect.

### Calls from Mac (`calls.v1`)

macOS sends `calls.request` only when both peers advertise the `calls` feature.
Its AES-GCM encrypted JSON payload contains a single `number` string. The
receiver accepts only 3–15 decimal digits with an optional leading `+`; spaces,
parentheses, dots, and hyphens may be present for display formatting and are
removed before use. Letters, extensions, USSD strings, and other dial commands
are rejected. Each request has a unique replay-protected message ID and Android
accepts at most one request every three seconds.

Android answers with the same message ID using `calls.started`,
`calls.confirmation_required`, or `calls.rejected`. Confirmation mode is the
default and posts a local notification whose explicit action opens the system
dialer with `ACTION_DIAL`. Direct mode is a separate local opt-in that requires
`CALL_PHONE`; the same local call-integration opt-in requests
`READ_PHONE_STATE` and `ANSWER_PHONE_CALLS` to identify call state and execute
Answer, Decline, and Hang Up commands sent through authenticated notification
action tokens. Bridgey uses the platform telecom service and never starts a
number the platform identifies as an emergency number. It does not request
contacts or call-log access. Local feature policy is checked again immediately
before any side effect. Number content is not logged.

### Incoming call state (`calls.v2`) — defined, not currently sent

Designed in 0.7 alongside `calls.v1`; both use the same `calls` feature
toggle, no new capability string. **Status:** both clients implement and unit
test this format, but nothing in the shipped Android client currently sends
`calls.state` — it exists as a documented, ready wire format for a future
call-state source, not as an active second incoming-call path today. The
production incoming-call signal remains the `notifications.post` `callType`
annotation described under Notifications below.

The reason: this was originally designed around a dedicated non-UI
`InCallService`, which would have given Android a real Telecom `Call` object
per call (state, `DisconnectCause`, caller info) independent of notification
parsing. A real-device test showed Telecom will not bind a non-UI
`InCallService` for an app that lacks the `signature|privileged` permission
`android.permission.CONTROL_INCALL_EXPERIENCE`, which a normal
sideloaded/Play-distributed app cannot hold. See `docs/architecture.md` for
the confirming `dumpsys telecom` evidence. That approach was removed rather
than kept as more dead code; only the message shapes below remain, in case a
future call-state source (becoming the default dialer, an OEM allowlist,
etc.) can populate them without a protocol change.

`calls.state` (Android → Mac) would report one call's state:

```json
{
  "version": 1,
  "callId": "1b0d9e3e-6a63-4a4b-9d21-6e4d7e6a9b3f",
  "state": "ringing",
  "callerName": "",
  "callerNumber": "+15550100"
}
```

`callId` is a lowercase UUID meant to stay stable for one call's entire
lifetime; it is not a system call ID. `state` is one of `ringing`, `active`,
`ended`, or `missed`. `callerName`/`callerNumber` are bounded (128 and 64
UTF-8 bytes) and either may be empty.

`calls.action` (Mac → Android) requests `answer`, `decline`, or `hangup` for a
`callId` from the most recent `calls.state`:

```json
{ "version": 1, "callId": "1b0d9e3e-6a63-4a4b-9d21-6e4d7e6a9b3f", "action": "answer" }
```

Android does implement the receiving side of `calls.action`: it validates the
message, then executes the action via the same `TelecomManager`-backed control
already used for the notification-driven Answer/Decline/Hang Up actions
(`acceptRingingCall()` for `answer`, `endCall()` for `decline`/`hangup`).
Without a tracked `Call` object (no `InCallService`), this acts on whatever
call is currently ringing or active rather than looking up the specific
`callId` — the field is validated but not used for lookup. Android replies
with `calls.action.ack` echoing `callId` and `action` plus `accepted`; Mac
ignores an ack that does not match the call it currently displays.

### Video / Input dedicated channels (`video.channel.v1`) — M1 transport foundation, no encoder/decoder/UI yet

M1 of the Bridgey Video Transport + Digital KVM design (see `docs/architecture.md`
for the full audit/proposal). Establishes two independent, dedicated TCP
sockets — one for video frames, one for input events — alongside the existing
control-channel session, because both carry payloads (encoded video frames;
a continuous stream of pointer/key events) far too large or too frequent for
the 65,536-byte JSON-line control channel. **Status:** the transport,
security, framing, lifecycle, and backpressure machinery below is fully
implemented and unit/integration tested on both platforms; there is no
encoder/decoder, KVM input-injection, or user-facing UI wired up yet — later
milestones consume `VideoTransport`/`InputTransport` as already-solid
building blocks.

Negotiation happens over the existing encrypted control channel, reusing its
established pairing trust — no second bootstrap or key exchange. Video and
input negotiate **independently**: `video.offer`/`video.accept`/`video.reject`/
`video.stop` and `input.offer`/`input.accept`/`input.reject`/`input.stop` are
separate message families, each its own request/response pair, so one media
type can be active without implying anything about the other.

`video.offer` / `input.offer` (either peer → the other, whichever side is
initiating that particular channel):

```json
{ "version": 1, "channelId": "c1b0d9e3-...", "direction": "android_to_mac",
  "width": 1080, "height": 2400, "bitrateKbps": 4000, "fps": 30 }
```

`input.offer` omits `width`/`height`/`bitrateKbps`/`fps`. `direction` is
`android_to_mac` or `mac_to_android` and is decided once by whichever side
sends the offer; it is never re-sent by the acceptor, since both sides derive
the same channel key from the same locally-known value. The receiving side
replies `*.accept` with the ephemeral TCP port it just opened to listen on, or
`*.reject` with a `reason` (`busy` if that channel is already active,
`unavailable` otherwise) — `channelId` is echoed back in both:

```json
{ "version": 1, "channelId": "c1b0d9e3-...", "port": 54321 }
```

The offering side then dials that port on the same host its control session
is already talking to. `*.stop` (either direction, `{version, channelId}`)
requests a clean shutdown; a stop initiated by the peer is treated as a normal
close (not a failure) on the receiving side.

**Security.** The dedicated channel's `channelKey` is derived from the
already-established pairing key, binding in the session, the channel's
purpose, and its direction, so a video channel, an input channel, and the two
directions of either can never collide even if several are active at once:

```
channelKey = HKDF-SHA-256(
  IKM = pairingKey,
  salt = SHA-256(sessionId + " " + purpose + " " + direction),
  info = "bridgey-channel-v1", 32 bytes
)
```

Once connected, the socket runs a mutually-authenticated handshake before any
frame is trusted. The initiator sends its raw 16-byte session ID, a 1-byte
purpose tag, a 16-byte random `openNonce`, and a 32-byte `token`; the acceptor
verifies the claimed session ID/purpose match what it independently expects
before checking the token, then proves itself back with an `ackProof` the
initiator verifies before treating the channel as active — a bare
unauthenticated acknowledgement was deliberately rejected during design in
favor of this second HMAC proof:

```
token     = HMAC-SHA-256(channelKey, "bridgey-channel-open-v1 " + sessionId + " " + purpose + openNonce)
ackProof  = HMAC-SHA-256(channelKey, "bridgey-channel-ack-v1 "  + sessionId + " " + purpose + openNonce)
```

Every frame after the handshake is independently encrypted (AES-GCM, random
12-byte nonce, 16-byte tag) under `channelKey` — the channel does not rely on
TCP alone for confidentiality/integrity, matching the rest of the protocol's
application-layer encryption posture.

**Framing.** Both the video and input channels share one binary frame format:

```
[4B frameLength][1B version][1B type][8B streamId][8B sequence][8B captureTimestampMs][12B nonce][ciphertext + 16B tag]
```

`frameLength` counts everything after itself and is validated against the
transport's `maxFrameBytes` cap *before* the declared length is read, so a
malicious or corrupted length prefix cannot force an unbounded read. `type`
distinguishes `CONFIG`/`KEYFRAME`/`DELTA`/`KEYFRAME_REQUEST`/`STREAM_RESTART`
(video) from `POINTER`/`KEY`/`TEXT` (input) — the two ranges never overlap.
`sequence` is per-channel and strictly increasing; a frame at or below the
last accepted sequence is silently dropped (replay/reorder), without closing
the connection. A frame that fails to parse, claims an unknown type, or fails
AES-GCM verification closes the channel as a protocol violation.

Input event payloads (before encryption): `POINTER` is `[1B action][4B x
Float32][4B y Float32]` with `x`/`y` normalized 0.0–1.0 (never absolute
pixels, so either side's actual screen resolution is irrelevant to the wire
format); `KEY` is `[4B keyCode][1B action]`; `TEXT` is raw UTF-8 bytes.

**Lifecycle.** Each channel (video, input) is an independent state machine:
`idle → negotiating → connecting → handshaking → active → closing → idle`,
with `failed` reachable from a connect/handshake failure or an unexpected
socket close while active, and an unconditional `sessionReset` event forcing
`idle` from any state — fired whenever the main paired session itself resets,
tearing down any in-progress or active video/input channel alongside it. A
peer-initiated `*.stop` is routed as a normal `stopRequested` event, not a
socket-closed failure.

**Backpressure.** Each direction has its own bounded send queue. For video,
`CONFIG`/`KEYFRAME` frames are never silently dropped; `DELTA` frames may be,
evicting the oldest droppable entry first when the queue is full. For input,
only pointer `MOVE` is coalescible/droppable; pointer down/up, key down/up,
and text are never dropped — the policies differ because a lost video delta
just costs a frame until the next keyframe, while a lost key or click is a
correctness bug from the user's perspective.

## Compatibility

Adding optional fields or message types is backward compatible. Changing field
meaning, authentication, or required behavior requires a new capability or
major version. JSON is a codec behind the envelope model; a future protobuf
codec must preserve IDs, types, correlation, and negotiated semantics.
