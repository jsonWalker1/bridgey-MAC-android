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

## Rules
Only the continuation URL or the book reference leaves the phone — never passwords, tokens, cookies
or session data. The Mac never opens a link or drives Books on its own: the user clicks
"Open in browser" / "Find in Books". History: [BRIDGEY_WEB_HANDOFF_STATE.md](../../BRIDGEY_WEB_HANDOFF_STATE.md).

## Tests
`tests/android/WebHandoffAlphaTest.kt`, `WebHandoffPocTest.kt`, `BooksHandoffAlphaTest.kt`.
