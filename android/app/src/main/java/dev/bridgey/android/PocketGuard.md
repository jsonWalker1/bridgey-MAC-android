# PocketGuard

**Feature:** screen-share (Android, "Pocket Mode") · **Status:** production.

## Purpose
While the phone's screen is shared to the Mac (and controlled via KVM), prevents accidental touches
on the phone itself — e.g. in a pocket — without affecting what the Mac sees.

## Why it exists / how
A fully transparent, **non-rendering** touch-interceptor window above normal apps. Because it draws
nothing, it contributes nothing to the MediaProjection mirror (there is no Android API to exclude an
overlay from a capture, so not rendering at all is the only guarantee). It never touches MediaCodec,
VirtualDisplay, MediaProjection or the video/control channels.

## Ownership / non-responsibilities
Owns the guard window, the unlock gesture and optional dimming. Does **not** own the stream or the
setting store (Pocket Mode preferences are this feature's own policy).

## Non-obvious decisions
- Exit only by the unlock gesture or "Stop" on the Screen Sharing notification (the overlay sits below
  system UI, so the shade is always reachable as an escape hatch). Uncovering the sensor never
  auto-disengages.
- Dimming is a **per-window** brightness override (not a settings write): no permission needed and it
  reverts the instant the window goes away, even on process death. Never `0f` (some OEMs treat it as
  panel off). Manual "Enable now" never dims.
- Auto-detection uses a debounced proximity state machine. **Platform limitation:** on the Galaxy S23
  Ultra (One UI) real near/far transitions are never delivered to third-party listeners; manual
  Pocket Mode is the reliable path.
- The guard never outlives the stream it guards (`BridgeyApplication` disengages it).

## Related
`ScreenCaptureManager.kt` · `ScreenCaptureService.kt`
