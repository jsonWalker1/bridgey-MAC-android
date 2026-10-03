# Apple Books automation POC (experiment, not part of Bridgey)

Question: after an explicit user action, can the Mac open Apple Books, search a quote received
from Android and land on it — without the user pressing ⌘F / ⌘V?

**Answer: yes, for a unique quote, with verification at every step. 2026-10-03.**

```
swift build -c release
.build/release/BooksAutomationPOC --title "<book title>" --quote "<quote>" [--chapter "CHAPTER X."] [--input paste]
```

Output: JSON `result` = `COMPLETED | NOT_FOUND | AMBIGUOUS | ABORTED_FOCUS | FAILED`, `reason`,
`attempts` (each search strategy, how the text was entered, result count), `chosen`, `presses`,
step trace with timestamps. The process running it needs the Accessibility permission (here:
the terminal) and Automation access to System Events.

## State machine (each step has an expected state; mismatch = stop)

1. Launch Books if not running → activate (Apple Event `activate`; LaunchServices open if Books
   has no window and does not come to front) → **verify frontmost**.
2. No windows exposed → `reopen` — only while Books is verifiably frontmost.
3. Book window open? → else press its library card (home view, then "All") → **verify window**.
4. Make it key (`AXRaise` + `AXMain` + app `AXFocusedWindow`) → **verify focus stable 0.6 s**.
5. ⌘F (System Events, which re-checks the frontmost process in the same script) → **verify
   search field exists and is focused**.
6. Clear (⌘A, Delete) → **verify empty** → type → **verify field value scalar-identical**
   (paste via clipboard as fallback, clipboard text restored).
7. Return → wait for result rows (buttons inside the search popover) or "no results"; read the
   whole virtualized list (`AXScrollToVisible`).
8. Strategies, in order, until something is found: full quote → typographic punctuation (’ “ ”)
   → longest punctuation-free run of ≤ 8 words.
9. Exactly one result (or exactly one in `--chapter`) → else `AMBIGUOUS`, nothing pressed.
10. Wait for the list to settle (2 identical reads) → `AXPress` the row → if nothing changed,
    press **the same verified row** once more → **verify the search closed**.
11. **Verify the quote is in the visible page text** of the book window (AX, retried ≤ 6 s).

Never: writing text through `AXValue` (broke Books' search until restart), Escape (closes the
book window), keys while another app or window is in front, coordinates.

## Results (MacBook, macOS 27, Apple Books)

| # | Scenario | Result | Time |
| --- | --- | --- | --- |
| 1 | Books not running | COMPLETED | 7.5–13 s |
| 2 | Books running without a window, other app in front | COMPLETED | 5.1–8.5 s |
| 3 | Book already open | COMPLETED (continues in the open book window) | 5.2–5.5 s |
| 4 | Quote found → landed, text on the visible page, highlighted by Books | COMPLETED | — |
| 5 | Quote not in the book | NOT_FOUND | 2.6 s |
| 6 | Czech quote with diacritics ("výkony vznešeného rytíře"), also from cold start | COMPLETED (typed directly; NFC verified) | 6.9–8.4 s |
| 7 | Long quote (225 chars incl. `:` and `,`) | COMPLETED (no shortening needed) | 5.7–7.5 s |
| 7b | Straight apostrophe vs book's ’ ("It isn't mine") | COMPLETED via typographic variant | 6.0 s |
| 8 | Repeated text ("said the Hatter", 20×; 5× in chapter XI) | AMBIGUOUS, nothing chosen | 3.3–4.1 s |
| 8b | Song line that occurs twice | AMBIGUOUS | 4.9 s |
| 9 | User switches to Finder at 0.3 / 0.6 / 1.0 / 1.6 / 2.4 s | ABORTED_FOCUS every time, Finder stays in front, no key reached Finder | ≤ 0.9 s after the switch |

## Failure modes found (and how the POC handles them)

- Windowless Books sometimes ignores `activate` → LaunchServices open; nondeterministic.
- Windows are invisible to AX while Books is in the background → "no window" is only trusted while
  Books is frontmost (an earlier version re-activated Books after the user switched away).
- Search result labels differ by book: "<chapter>, <page>, <snippet>" vs "<page>, <snippet>"
  (books without chapters) → rows are taken structurally from the search popover.
- First result press ignored right after a book opened (layout still changing) → settle, then at
  most one re-press of the same verified row.
- After a search the focus sits in the result list → refocus and verify the field before the next
  query.
- Literal search: straight vs typographic quotes/apostrophes, punctuation inside the quote → extra
  strategies; quotes with punctuation in the middle may only be findable as an ambiguous segment.
- Repeated text cannot be disambiguated from Books' result labels (snippet + page only; pages differ
  between devices) → AMBIGUOUS. A chapter hint helps only when the text is unique in that chapter.
- The quote must come from the same edition; a different edition/translation → NOT_FOUND.

## What it would need inside Bridgey

- Accessibility permission for Bridgey (macOS Privacy → Accessibility) and Automation → System
  Events (keyboard via System Events; Books ignores CGEvents posted to its pid).
- Run only from an explicit click ("Find in Books" on the book card), show live status, and show
  AMBIGUOUS / NOT_FOUND / ABORTED as clear messages with the existing manual fallback (quote copied).
