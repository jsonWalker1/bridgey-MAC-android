# WebHandoffToolbarChip

**Feature:** handoff/web (Android) · **Status:** alpha (the source header still says POC).

## Purpose
A small Bridgey icon drawn as an accessibility overlay at the trailing end of the browser's own
address bar; tapping it hands the page off to the Mac immediately.

## Why it exists
Share → "Continue on Mac" takes four taps; the chip makes handoff a single tap exactly where the user
looks, without a browser extension (Brave/Chrome on Android have none).

## Ownership / non-responsibilities
Owns placement, visibility and the tap. Does **not** decide what to send ([WebHandoff](WebHandoff.md))
or read page content.

## Non-obvious decisions
- Position comes from the `url_bar` accessibility node, never hard-coded coordinates.
- Shown only while a supported browser is the focused window, its address bar is visible and not
  being edited, **and a Mac is connected** (no misleading "send" without one).
- **Settle logic:** window/rotation animations report transient, distorted bounds, so the chip only
  appears or moves after the same position is measured twice in a row; an address field must look
  like a wide strip near an edge. A hidden chip waits out the ~0.5 s app-open animation.
- The service only receives events from browser packages (privacy), so leaving the browser produces
  no event: while the chip is visible a 500 ms watchdog re-checks the foreground window.
- Keyguard, notification shade or IME in front hide it. One gentle pulse per new page.

## Failure modes
Browsers that rename the `url_bar` node lose the chip (Share still works).

## Related
[WebHandoff](WebHandoff.md) · `WebHandoffPocService.kt` · [BRIDGEY_WEB_HANDOFF_STATE.md](../../../../../../../../BRIDGEY_WEB_HANDOFF_STATE.md)
