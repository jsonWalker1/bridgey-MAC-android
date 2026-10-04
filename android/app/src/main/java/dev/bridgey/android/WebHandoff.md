# WebHandoff (planner)

**Feature:** handoff/web (Android) · **Status:** alpha, on `main` since 740b2c6.

## Purpose
The single operation behind both entry points — the browser toolbar chip and Share → "Continue on
Mac" — that decides *what* to send so the Mac opens the page at the place the user was reading.

## Why it exists
Entry points only describe the source (URL, selection, surrounding words, visible text); one
planner decides. Without it each entry point grew its own rules and they diverged.

## Decision (`planWebHandoff`)
Priority: explicit text selection (with prefix/suffix words, W3C Text Fragment
`#:~:text=prefix-,start,end,-suffix`) > a text fragment the browser already put into the shared link
> the reading position (first visible sentence) > the plain URL. Context never blocks a handoff:
anything that cannot become a valid link falls back to the page URL.

## Ownership / non-responsibilities
Owns the plan and reporting the outcome to the user. Does **not** read the screen (the accessibility
service supplies the source), open anything on the Mac (the Mac queues the link and the user clicks
"Open in browser" — an explicit decision, no auto-open), or own the transport (`QuickActions.sendLink`).

## Security / privacy
Only the continuation URL leaves the phone. Never passwords, tokens, cookies or session data; the
service reads accessibility text only from supported browsers (`packageNames`), and claims no DOM
access it does not have.

## Non-obvious decisions
- `sendLink` returns whether it actually sent; a still-pending earlier handoff is reported as such
  and never mistaken for this handoff's outcome (stale status bug found in review).
- "Same page" ignores scheme, `www.`, fragment and trailing slash (the URL bar elides them).

## Tests
`WebHandoffAlphaTest.kt`, `WebHandoffPocTest.kt`.

## Related
[WebHandoffToolbarChip](WebHandoffToolbarChip.md) · `WebHandoffPocService.kt` · `QuickActions.kt` ·
[BRIDGEY_WEB_HANDOFF_STATE.md](../../../../../../../../BRIDGEY_WEB_HANDOFF_STATE.md)
