# BooksAutomation

**Feature:** handoff/books (macOS) · **Status:** alpha (on `feature/handoff-books`); graduated from
the Books automation POC (`experiments/books-automation-poc`, kept on `feature/handoff-books`).

## Purpose
"Find in Books": after an explicit click on the book card, opens Apple Books, searches the quote (or
the chapter heading) received from Android and lands on it — without the user pressing ⌘F / ⌘V.

## Why it exists
Apple Books has no API or URL scheme for "open book X at text Y". The only public path is driving its
UI through the Accessibility API — fragile enough that it is a strict state machine that verifies every
step and **aborts** on anything unexpected rather than guessing.

## Ownership / non-responsibilities
Owns the automation state machine and its result (`completed`, `notFound`, `ambiguous(n)`,
`abortedFocus`, `failed`). Does **not** own the book card UI or the payload (`QuickActions.swift`),
and runs only from an explicit user click, never automatically.

## How it works (each step verifies its expected state; mismatch = stop)
1. Launch/activate Books (Apple Event `activate`, LaunchServices open as fallback when Books has no
   window); verify it is frontmost. `reopen` only while Books is verifiably frontmost.
2. Open the book window (press its library card) → make it key → verify focus is stable.
3. ⌘F via System Events, which re-checks the frontmost process **in the same script**; verify the search
   field is focused; clear, type, verify the value scalar-for-scalar (NFC/NFD safe).
4. Read the virtualised result list (`AXScrollToVisible`); strategies in order: full quote →
   typographic punctuation → longest punctuation-free segment.
5. Exactly one result (or exactly one in the chapter) → wait until the list settles → `AXPress` it,
   re-press the **same verified row** at most once → verify the search closed and the quote is on the
   visible page.

## Invariants (learned on hardware)
- Never write text via `AXValue` (broke Books' search until restart); never press Escape (closes the
  book window); never send keys while another app is in front; never click coordinates.
- Never pick the first of several matches → `ambiguous`.
- A user switching apps mid-run → `abortedFocus`, and no key reaches the other app.

## Known limitations
Repeated text cannot be disambiguated (Books' result labels hold only snippet + page). The quote must
come from the same edition. From the Play Books tile only chapter/page are known (no on-screen text),
so it searches the chapter heading.

## Tests
`QuickActionsTests.swift` (Find outcomes via an injected automation runner); the POC README records
the hardware runs.

## Related
`QuickActions.swift` · Android `BooksHandoff.kt` · [BRIDGEY_WEB_HANDOFF_STATE.md](../../../BRIDGEY_WEB_HANDOFF_STATE.md)
