# ScreenStreamDecoder

**Feature:** screen-share (macOS) · **Status:** production.

## Purpose
Decodes and displays the phone's H.264 screen stream received on the video channel (VideoToolbox),
and publishes the encoded screen size so the view can compute the real content rectangle (used by KVM
for click mapping).

## Why it exists
It adapts the verified screen POC decoder onto the frozen video channel: it receives authenticated,
framed `EncodedVideoFrame`s, converts Annex-B NAL units to AVCC and feeds the decoder.

## Non-obvious decisions
- **Self-healing:** if a frame arrives before SPS/PPS are known (a fresh connection racing the first
  CONFIG frame, or a reconnect while Android's encoder keeps running and never re-emits its config), it
  asks Android for a fresh CONFIG + keyframe (`KEYFRAME_REQUEST`) instead of staying undecodable.
- Android recreates its encoder on rotation; a genuinely new SPS/PPS pair forces the format description
  to be rebuilt, an unchanged pair is ignored. (Plain property access on purpose — an `inout` helper
  here was an overlapping-access runtime trap.)
- Reset at the same sites as the video channel, so a stale format description never meets a new
  connection's NAL units.

## Tests
`ScreenStreamDecoderTests.swift`, `VideoFrameFramingTests.swift`.

## Related
`ScreenShareWindow.swift` · `VideoChannelController.swift` · Android `ScreenCaptureManager.kt`
