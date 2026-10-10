# Handoff

**Answers:** how do I continue on the Mac what I was doing on the phone (a web page, a book)?

**Owns:** reading the source on Android through an accessibility service limited to supported
browsers and Play Books (`WebHandoffPocService` — production despite the historical name), the web
handoff planner ([WebHandoff](android/WebHandoff.md): selection > browser text fragment > reading
position > URL), the browser toolbar chip ([WebHandoffToolbarChip](android/WebHandoffToolbarChip.md)),
Share → "Continue on Mac", the Play Books tile and book payload (`BooksHandoff`), and on the Mac
"Find in Books" ([BooksAutomation](macos/BooksAutomation.md)).
**Does not own:** the quick-action transport and the Mac link/book cards (`QuickActions.*`, still in
the platform folders — ownership undecided), the connection.
**Dependencies:** handoff → app on Android: `WebHandoffPocService` starts `BridgeyConnectionService`
(guarded by `shouldStartConnectionService`) as an Android lifecycle / foreground-service
workaround — after an update or reboot the accessibility service is the only part Android rebinds,
and without the connection's foreground service the app is frozen in the background. Quick actions
↔ handoff: `QuickActions.*` use `validatedWebLink`, `BOOK_PAYLOAD_MAX` and `BooksAutomation`.

## Code
| | |
|---|---|
| `android/` | `WebHandoffPocService`, `WebHandoff`, `WebHandoffShareActivity`, `WebHandoffToolbarChip`, `BooksHandoff`, `BooksHandoffTileService` |
| `macos/` | `BooksAutomation` |
| still elsewhere | `QuickActions.kt`, `QuickActionsView.kt`, `QuickActions.swift`, `QuickActionsView.swift` (+ `QuickActionsTests.swift`, which also covers `BooksAutomation` helpers); resource `web_handoff_poc_service_config.xml` stays in the app shell |

Web and books are not split into sub-folders yet; that happens only for files that are clearly
specific to one of them.

## Per peer (MD-7)
Web links and book handoff go to and come from an explicit peer (`HandoffRouting.*` in `macos/`
and `android/`); the wire format is unchanged (destination = the session, sender = the
authenticated session).
- **Android → Mac:** browser chip, Share → "Continue on Mac", the Play Books tile and the POC path
  resolve the target once: the only eligible Mac gets it directly; with several the user picks one
  in `HandoffTargetActivity`; none → nothing is sent. Share → "Send as link" uses the share
  dialog's recipient. The chip/tile show when any Mac can take a handoff.
- **Mac → Android:** "Link" on a peer's card; the keyboard shortcut sends only when exactly one
  peer is eligible.
- **Receiving:** `quick.request` / `quick.result` with feature `links` are accepted from any
  connected peer; feature `media` (and `media.state`) stay with the routed peer
  (`QuickPayloadGate`). Cards are kept per sender (one link + one book per peer) in one list that
  names the sender, whichever peer is selected or routed; they stay after the sender disconnects
  until opened or dismissed, and go when the local `links` grant for that peer is turned off.
  A routing change no longer clears them. Results complete only the sender's own request.

## Rules
Only the continuation URL or the book reference leaves the phone — never passwords, tokens, cookies
or session data. The Mac never opens a link or drives Books on its own: the user clicks
"Open in browser" / "Find in Books". History: [BRIDGEY_WEB_HANDOFF_STATE.md](../../BRIDGEY_WEB_HANDOFF_STATE.md).

## Tests
`tests/android/WebHandoffAlphaTest.kt`, `WebHandoffPocTest.kt`, `BooksHandoffAlphaTest.kt`.
