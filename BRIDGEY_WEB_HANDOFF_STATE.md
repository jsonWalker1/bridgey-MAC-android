# BRIDGEY — WEB HANDOFF STATE

Status as of 2026-10-02. Read this file + `CLAUDE.md` first. Covers the whole
Web Handoff track (Web Anchor POC → browser toolbar chip POC → dogfood Alpha)
and the two Apple Books handoff experiments run alongside it.

| Stage | State | Commit |
| --- | --- | --- |
| Web Anchor POC (context capture + continuation URL) | done, pushed | `ad17aa8` |
| Browser toolbar chip UX POC | done, committed (not pushed) | `1a928ce` |
| **Web Handoff Alpha** (chip + Share → "Continue on Mac") | done, device-verified, **uncommitted** | — |
| Apple Books handoff (feasibility + 50-run reliability) | experiment only, scratchpad | — |
| **Books Handoff Alpha** (tile + Share quote → Mac card) | done, Android device-checked, Mac unit-tested, **uncommitted** | — |

Detailed reports (Claude Docs): Web Handoff POC report, Handoff chip UX POC,
Books Handoff POC, Books Handoff reliability test (50 runs), modularity audit.

## 1. WHAT THIS FEATURE IS

"Continue on Mac" for the web: while reading a page in Brave on Android, one
explicit action sends the page to the paired Mac, including *where* the user was
(selected text or reading position) as a W3C Text Fragment
(`#:~:text=prefix-,start,end,-suffix`). Safari scrolls to and highlights the
fragment by itself — no browser automation, no Mac permissions.

Direction: Android → Mac only. Browser: Brave (Chromium) on Android, Safari on
macOS. The Mac side is the existing **Web links** quick action
(`quick.request` feature `links`), unchanged.

## 2. WEB ANCHOR POC (`ad17aa8`)

Separate opt-in accessibility service `WebHandoffPocService`, limited to browser
packages (`packageNames` = Brave, Chrome, Samsung Internet). The KVM service
keeps `canRetrieveWindowContent=false` and is untouched.

What Chromium exposes through Android accessibility:
- page URL: `url_bar` text (scheme and `www.` elided); hidden while text is
  selected, so the last URL is cached per browser;
- HTML `id` as `viewIdResourceName`; extras `chromeRole`, `targetUrl` (href),
  `unclippedTop/…`, `isHeading`; visible text per node;
- selection: `TYPE_VIEW_TEXT_SELECTION_CHANGED` with node text + from/to;
- page title: not reliably (only the first heading).

Measured on 10 real sites (84 Mac measurements, Brave Android → Mac):
- Text Fragment reached the target: **Safari 6/10, Brave 8/10**; long pages
  (~2,000–3,000 px down) worked in both.
- `#id` anchors: Brave only (Safari ignored them in these tests).
- Failure modes: text differs between mobile and desktop page variants (e.g.
  Wikipedia mobile short descriptions, collapsed mobile sections), repeated text
  (first occurrence wins without prefix/suffix), flaky sites in Safari.
- Highest level reached: **Level 5 "context handoff"** (same page + same place).
  True state handoff (forms, login, app state, exact scroll) is impossible.

Pure helpers (unit-tested in `WebHandoffPocTest`): `continuationUrl()`,
`encodeTextFragment()`.

## 3. BROWSER TOOLBAR CHIP POC (`1a928ce`)

`WebHandoffToolbarChip`: a `TYPE_ACCESSIBILITY_OVERLAY` Bridgey icon anchored
to the trailing end of the browser's address field node (`url_bar`, Samsung
`location_bar_edit_text`) — no hard-coded coordinates. Public APIs only; no
"draw over other apps" permission.

Verified on S23 Ultra / Brave: portrait, landscape, page scroll (chip hides with
the collapsing toolbar), navigation (pulse once per page), app switch, lock
screen, URL editing (hidden), Shields popup (hidden), Bridgey restart, KVM
running alongside.

Fixes found on the device:
- packageNames-limited services get no event when another app comes to front →
  500 ms foreground re-check **only while the chip is visible**;
- rotation/app-open animations report distorted bounds → the chip only shows or
  moves after two identical measurements and only for a wide, low strip inside
  the window/display; transient hides re-check (≤ 1.8 s).

Alternatives investigated: browser APIs (none for third-party toolbar actions),
Custom Tabs (only for Bridgey's own tab), bubbles, PiP, shortcuts, notification,
Quick Settings tile, accessibility button — none can live in the toolbar.
Samsung Internet: the chip fits, but its address field shows only the domain and
page content is not exposed → not useful there.

## 4. WEB HANDOFF ALPHA (uncommitted, device-verified)

Goal: dogfoodable everyday use. Decision by the user: **the Mac keeps the
explicit Open** — a handoff is queued in the Bridgey menu-bar panel and opened
with "Open in browser" (security model in `docs/protocol.md` unchanged; no Mac
code changed).

### 4.1 Architecture

```
Brave ──(chip tap)──────────────┐
                                ├─> WebHandoff.perform(source) ─> QuickActions.sendLink ─> Mac panel ─> Open ─> Safari
Share → "Continue on Mac" ──────┘        (planWebHandoff)
```

- `WebHandoff.kt` — the single operation for both entry points:
  `WebHandoffSource(pageUrl, selectedText, selectionPrefix/Suffix, readingText)`
  → `planWebHandoff()` → send → user feedback (toast).
- Priority (`WebHandoffKind`):
  1. `SELECTION` — explicit selection + up to 3 words of context on each side;
  2. `BROWSER_TEXT_FRAGMENT` — a fragment the browser already put in a shared
     link (Brave's "share with highlight");
  3. `READING_POSITION` — first sentence of the first fully visible paragraph;
  4. `URL_ONLY`.
  Context never blocks: any fragment that does not validate as a link (e.g.
  > 4,096 bytes) falls back to the plain URL. Only `validatedWebLink` http(s)
  links are ever sent.
- `WebHandoffShareActivity` — transparent, no UI, `SEND text/plain` only
  (exported because the system share sheet starts it). Extracts the URL from
  the shared text; if the accessibility service runs and shows the same page
  (`samePage()` ignores scheme, `www.`, fragment, trailing slash) it adds context;
  shared text without a URL is treated as a selection on the current page.
  **Works without accessibility** (URL only).
- `WebHandoffPocService` — now provides `chipSource()` / `shareSource()`,
  `freshSelection()` (≤ 2 min, survives the browser clearing the selection when
  the share sheet opens), `readingText()`; static `current` WeakReference for the
  share activity; watches `pairing.state` + `pairing.remoteFeatures` so the chip
  appears/disappears with the connection.
- `WebHandoffToolbarChip` — tap = immediate handoff (the POC's confirmation card
  removed); hidden unless a Mac is connected **and** Web links are available.

### 4.2 User feedback (toasts, also logged as `BridgeyWebHandoff: FEEDBACK …`)

| Situation | Message |
| --- | --- |
| delivered | "Sent page + selected text / highlighted text / reading position — open it from Bridgey on your Mac" (or "Sent page") |
| Mac has an unopened link | "Mac declined — dismiss the previous link in Bridgey on the Mac first" |
| an earlier handoff still unconfirmed | "Not sent — the previous handoff is still in progress" |
| no Mac | "Mac not connected — nothing sent" |
| no web address | "Nothing to continue on Mac — no web page address" |
| no confirmation in 9 s | "Mac did not confirm — try again" |

A stale status is never reported as the outcome of a new handoff
(`notSentMessage`).

### 4.3 Device verification (S23 Ultra, Brave → MacBook, Safari)

| Test | Result |
| --- | --- |
| Share URL (MDN) → Open → Safari at the reading position | ✅ confirmed by user |
| Select word → Share → Continue on Mac → Safari highlights it | ✅ confirmed |
| Select text → chip → Safari highlights "Clients" | ✅ confirmed |
| URL only (shared page ≠ page in Brave) → `example.com` | ✅ (Safari front tab checked) |
| No Mac (Wi-Fi off) | ✅ chip hidden; share → "Mac not connected — nothing sent" |
| Mac still holds a link | ✅ honest "declined", no false "sent" |
| Leave Brave / return, rotate, lock/unlock | ✅ |
| Reconnect after Wi-Fi drop | ✅ chip back 0.2 s after pairing |

Bugs found and fixed during Alpha testing: stale-status false success (found by
the local coworker review), selection lost when the share sheet opens, browser
fragment overwritten by our reading position, chip used the selection popup
window instead of the page window, chip not restored after reconnect (features
arrive after `Connected`).

### 4.4 Files (Alpha, uncommitted)

- new: `WebHandoff.kt`, `WebHandoffShareActivity.kt`, test `WebHandoffAlphaTest.kt` (10 tests)
- changed: `WebHandoffPocService.kt`, `WebHandoffToolbarChip.kt`,
  `AndroidManifest.xml` (share activity), `res/values/strings.xml` (`web_handoff_share_label`)
- Android unit tests: 272/272 green; `assembleDebug` OK. macOS unchanged.

### 4.5 How to use (dogfood)

1. Settings → Accessibility → Installed apps → enable **Bridgey Web Handoff (POC)**
   (optional; without it Share sends the URL only).
2. In Brave: tap the Bridgey icon in the address bar, or select text → Share →
   Bridgey → **Continue on Mac** (Samsung groups Bridgey's two share targets).
3. On the Mac: Bridgey menu-bar icon → **Open in browser**.

### 4.6 Known limitations

- A queued link is **discarded when the connection drops** before it is opened
  (`QuickActions.reset()` on disconnect) — existing behavior.
- The Mac holds **one** pending link; the next handoff is declined until it is
  opened or dismissed.
- Samsung's share sheet hides "Continue on Mac" under More → Bridgey.
- Context needs Brave + the accessibility service; Chrome/Samsung Internet not
  in Alpha scope.
- No sensitive-domain blocking (was not in the POC either). The fragment carries
  page text into the Mac's browser history.
- After an APK reinstall Android sometimes keeps Bridgey's accessibility
  services enabled but unbound → toggle them off/on.

## 5. APPLE BOOKS HANDOFF (experiments, scratchpad only)

No Bridgey code. Tools in the session scratchpad (`books/`, `books/rel/`).

**Feasibility:** Apple Books has no AppleScript dictionary; everything goes
through macOS Accessibility (needs the Accessibility permission for the driving
process). Books exposes the visible page text (`AXStaticText`), page footer
("Stránka 138"), library cards with progress ("Kniha • 31 %"), and an in-book
search whose results are `AXButton`s "<chapter>, <page>, <snippet>". Google Play
Books exposes chapter + page/total, a search with a result list (chapter + page),
but not which paragraph is on screen; selected text is only reachable via the
selection menu's "Search". Level C (same paragraph, highlighted) both ways.

**Reliability (25 + 25 real runs, independent OCR + EPUB verification):**

| Direction | C | B | FAIL |
| --- | --- | --- | --- |
| Android → Mac | 15 | 5 | 5 |
| — with explicit selection (15) | **15** | 0 | 0 |
| Mac → Android | 8 | 4 | 13 (5 = safety aborts while the user used the Mac) |

No false successes found, no keystrokes into another app, no Books corruption in
the official runs. Failure causes: verse (text across line breaks), Mac reader
mixing in the window header, Play Books not showing the result bar after a tap,
headings with typographic apostrophes producing over-generic queries.

**Hard rules learned (Books automation):** never write search text via
`AXValue` (it broke Books' search until restart); never send Escape (closes the
book window); keyboard input only after verifying Books is frontmost and the
book window is focused; Books can run with no window (`reopen`); pressing the
library card while the book is open gives focus back to the library window;
typing is asynchronous; search is literal (curly quotes/dashes).

**Verdict:** worth another POC around "select a line → continue on Mac"; not
ready for integration. Fix first: run only on an explicit user click, Mac
position reader (header, verse, chapter), Play Books result navigation.

## 5a. BOOKS HANDOFF ALPHA (uncommitted)

"Continue reading on Mac" with the best available context, app-agnostic where
Android allows it. Decisions by the user: the Mac gets a small **book card** in
the existing Bridgey panel (no Apple Books automation); accessibility may read
**Google Play Books only**, on an explicit tap.

What Android really gives (checked on the S23): no universal "what/where am I
reading" API. Play Books has **no Share** in the reader menu or the selection
menu (only Copy/Define/Translate/Search), so Share → Continue on Mac cannot start
from it. Via accessibility Play Books exposes the book title (the window title)
and the position ("CHAPTER X. …, stránka 64 z 91"; scrub label "64 / 91" when the
toolbar is visible; the description can be stale). Other readers usually share a
quote as text — that path is app-agnostic.

- `BooksHandoff.kt` — one operation for both entry points:
  `BookSource(title, chapter, page, pages, quote, app)` → `planBooksHandoff()`
  (missing fields left out, never invented; nothing sent without a title or a
  quote; invalid pages dropped; title/chapter ≤ 200, quote ≤ 500, payload ≤ 4096
  bytes) → `QuickActions.sendBook()` → feedback. Also `parseReaderPosition()`
  (Play Books description + scrub label, generic "N / M") and `shareRoute()`.
- Entry points: **Quick Settings tile "Continue reading on Mac"**
  (`BooksHandoffTileService`; active only with a connected Mac; reads the reader
  behind the shade via `WebHandoffPocService.readerSource()`), and **Share →
  Continue on Mac** with text that has no URL from a non-browser app → quote
  (title/position added when Play Books is open; sender from `referrer`).
- Transport: the existing Web links quick action, new action **`book`**
  (`value` = JSON). Documented in `docs/protocol.md`. Older Mac apps decline it
  ("…or update Bridgey there").
- Mac: `validatedBookHandoff()`, one pending `receivedBook` (separate slot from
  the pending link), card "Continue reading from Android" (title, chapter,
  page/%, quote) with **Continue in Books** (copies the quote, opens Apple Books)
  and **Dismiss**; cleared on disconnect like links.
- Fixed in both Web and Books Handoff: `sendLink`/`sendBook` now return whether
  they really sent — a second handoff while the first is unconfirmed used to keep
  the old "Sending…" status and report the first handoff's ACK as its own.
- Tests: Android `BooksHandoffAlphaTest` (14: resolver, fallbacks, truncation,
  position parsing, share routing, outcome messages, and on the real
  `QuickActions`: book action wire format, offline/disabled, pending second
  handoff, stale + duplicate ACK, disconnect reset) — Android 286/286; macOS
  `QuickActionsTests` +4 (validation, one pending + separate slot, disabled /
  invalid, Continue in Books) — macOS 289/289.
- Device: share of a quote while Play Books was open → `route=BOOK`, title +
  chapter + page read, sent; the not-yet-updated Mac app declined it with the
  expected message. Full Android → Mac card check needs the new Mac build.
- Limitations: Apple Books cannot be opened at a book or position without
  automation, so the user opens the book and uses ⌘F/⌘V; Play Books position can
  be stale while the toolbar is hidden; only Play Books for the tile; the tile has
  to be added to Quick Settings by the user.

## 6. SIDE FINDINGS

- **Foreground service after reinstall:** when the process is started by the
  accessibility binding, `BridgeyConnectionService` may not run and Samsung
  Freecess freezes networking (connection flapping) — start MainActivity once;
  real fix not done.
- **"No Wi-Fi" status** in the persistent notification with a Wi-Fi action
  (`BridgeyConnectionService.kt`, `ConnectionStatusTextTest.kt`) — implemented
  and device-verified, **uncommitted**.
- Samsung: `svc wifi enable` often does not stick on the first try.
- macOS Do Not Disturb delivers Bridgey notifications silently (`delay
  delivery`) — not a Bridgey bug.

## 7. NEXT STEPS

1. Commit the Alpha (and separately the "No Wi-Fi" status).
2. Dogfood; decide whether the Mac should auto-open handoff links (would change
   the documented security model) or keep a queue of more than one link.
3. Keep pending links across reconnects.
4. Optional sensitive-domain blocklist before wider use.
