// APPLE BOOKS AUTOMATION POC (experiment, not part of Bridgey).
//
// Question: after an explicit user action, can the Mac open Apple Books, find a quote received
// from Android and land on it - without the user pressing ⌘F / ⌘V?
//
// Usage: BooksAutomationPOC --title "<book title>" --quote "<quote>" [--chapter "CHAPTER X."]
//
// State machine; every step has an expected state, verified through the macOS Accessibility API.
// If the expected state does not appear the run STOPS and returns a failure - it never "tries
// another click". Rules carried over from the earlier Books experiments:
//   - never write text into Books through AXValue (it broke Books' search until restart),
//   - never send Escape (it closes the book window),
//   - keyboard input only while Books is frontmost AND the book window is the focused window,
//   - Books can run without any window ("reopen" brings the library back),
//   - pressing the library card of an already open book hands focus to the library window.
import AppKit
import ApplicationServices

// MARK: - Output

let started = Date()
var trace: [String] = []
func ms() -> Int { Int(Date().timeIntervalSince(started) * 1000) }
func state(_ s: String) { let line = "\(ms())ms \(s)"; trace.append(line); FileHandle.standardError.write((line + "\n").data(using: .utf8)!) }

enum Outcome: String { case completed = "COMPLETED", notFound = "NOT_FOUND", ambiguous = "AMBIGUOUS", abortedFocus = "ABORTED_FOCUS", failed = "FAILED" }
var report: [String: Any] = [:]
func finish(_ outcome: Outcome, _ reason: String = "") -> Never {
    // A failure while Books is no longer in front is reported as the focus loss it most likely is.
    let booksFront = NSWorkspace.shared.frontmostApplication?.bundleIdentifier == "com.apple.iBooksX"
    let final = (outcome == .failed && !booksFront) ? Outcome.abortedFocus : outcome
    report["result"] = final.rawValue
    report["reason"] = final == outcome ? reason : "focus left Books (\(reason))"
    report["ms"] = ms()
    report["trace"] = trace
    print(String(data: try! JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]), encoding: .utf8)!)
    exit(final == .completed ? 0 : 1)
}

// MARK: - Input

func arg(_ name: String) -> String? {
    guard let i = CommandLine.arguments.firstIndex(of: name), i + 1 < CommandLine.arguments.count else { return nil }
    return CommandLine.arguments[i + 1]
}
guard let title = arg("--title"), let rawQuote = arg("--quote") else {
    print("usage: BooksAutomationPOC --title <book> --quote <quote> [--chapter <CHAPTER X.>]"); exit(64)
}
let chapterHint = arg("--chapter")
let forcePaste = arg("--input") == "paste"
let quote = rawQuote.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
report["title"] = title; report["quote"] = quote
guard AXIsProcessTrusted() else { finish(.failed, "this process has no Accessibility permission") }

// MARK: - AX helpers

let bundleID = "com.apple.iBooksX"
func booksApp() -> NSRunningApplication? { NSRunningApplication.runningApplications(withBundleIdentifier: bundleID).first }
func attr(_ e: AXUIElement, _ n: String) -> AnyObject? { var v: AnyObject?; return AXUIElementCopyAttributeValue(e, n as CFString, &v) == .success ? v : nil }
func kids(_ e: AXUIElement) -> [AXUIElement] { (attr(e, "AXChildren") as? [AXUIElement]) ?? [] }
func role(_ e: AXUIElement) -> String { (attr(e, "AXRole") as? String) ?? "" }
func labels(_ e: AXUIElement) -> String { ((attr(e, "AXUserInputLabels") as? [String]) ?? []).joined(separator: " ") }
func frame(_ e: AXUIElement) -> CGRect? {
    guard let p = attr(e, "AXPosition"), let z = attr(e, "AXSize") else { return nil }
    var pt = CGPoint.zero, sz = CGSize.zero
    AXValueGetValue(p as! AXValue, .cgPoint, &pt); AXValueGetValue(z as! AXValue, .cgSize, &sz)
    return CGRect(origin: pt, size: sz)
}
func waitFor<T>(_ timeout: Double, _ f: () -> T?) -> T? {
    let end = Date().addingTimeInterval(timeout)
    while Date() < end { if let v = f() { return v }; usleep(200_000) }
    return nil
}
/// Normalized for comparison: lowercase letters/digits only (Books' typography differs from the
/// sent quote: curly quotes, dashes, line breaks).
func norm(_ s: String) -> String {
    s.lowercased().folding(options: [.widthInsensitive], locale: nil)
        .map { $0.isLetter || $0.isNumber ? String($0) : " " }.joined()
        .split(separator: " ").joined(separator: " ")
}

// MARK: 1. Launch / activate Books

var cold = false
if booksApp() == nil {
    cold = true
    state("LAUNCHING Books (was not running)")
    NSWorkspace.shared.openApplication(at: URL(fileURLWithPath: "/System/Applications/Books.app"), configuration: .init())
    guard waitFor(15, { booksApp() }) != nil else { finish(.failed, "Books did not start") }
}
let app = booksApp()!
let ax = AXUIElementCreateApplication(app.processIdentifier)
report["cold"] = cold
func all(_ e: AXUIElement, _ d: Int = 0, _ out: inout [AXUIElement]) { out.append(e); if d < 45 { kids(e).forEach { all($0, d + 1, &out) } } }
func elements() -> [AXUIElement] { var o: [AXUIElement] = []; all(ax, 0, &o); return o }
func windows() -> [AXUIElement] { (attr(ax, "AXWindows") as? [AXUIElement]) ?? [] }
func frontmost() -> Bool { NSWorkspace.shared.frontmostApplication?.processIdentifier == app.processIdentifier }
func bookWindow() -> AXUIElement? { windows().first { (attr($0, "AXTitle") as? String) == title } }
func focusedWindowTitle() -> String? { attr(ax, "AXFocusedWindow").flatMap { attr($0 as! AXUIElement, "AXTitle") as? String } }

state("ACTIVATING Books")
// A background process may not steal focus with NSRunningApplication.activate (cooperative
// activation in macOS 14+); an Apple Event "activate" is allowed.
var activation = "already frontmost"
if !frontmost() {
    NSAppleScript(source: "tell application id \"\(bundleID)\" to activate")?.executeAndReturnError(nil)
    activation = "apple event activate"
    // Books with no window at all does not come to the front on "activate"; a LaunchServices
    // open (= clicking the Dock icon) reopens the library window and activates it.
    if waitFor(2.5, { frontmost() ? true : nil }) == nil {
        state("activate did not bring Books to front -> LaunchServices open (reopen)")
        let config = NSWorkspace.OpenConfiguration(); config.activates = true
        NSWorkspace.shared.openApplication(at: URL(fileURLWithPath: "/System/Applications/Books.app"), configuration: config)
        activation = "launchservices open"
    }
}
report["activation"] = activation
guard waitFor(6, { frontmost() ? true : nil }) != nil else { finish(.abortedFocus, "Books did not become frontmost") }
state("VERIFIED Books frontmost (\(activation))")

func ensureFrontmost(_ step: String) {
    guard frontmost() else { finish(.abortedFocus, "focus left Books before: \(step)") }
}
// Books may run without any window; "reopen" = clicking the Dock icon. Windows are only exposed
// while Books is frontmost, so "no window" is believed only while Books is verifiably in front -
// otherwise reopen would pull Books back in front of whatever the user switched to.
if waitFor(2, { windows().isEmpty ? nil : true }) == nil {
    ensureFrontmost("reopen")
    state("NO WINDOWS -> reopen")
    NSAppleScript(source: "tell application id \"\(bundleID)\" to reopen")?.executeAndReturnError(nil)
    guard waitFor(8, { windows().isEmpty ? nil : true }) != nil else { finish(.failed, "Books shows no window even after reopen") }
}

// MARK: 2. The book window (open from the library only if it is not open yet)

func libraryCard() -> AXUIElement? { elements().first { role($0) == "AXButton" && labels($0).contains(title) } }
var openedFromLibrary = false
if bookWindow() == nil {
    state("BOOK NOT OPEN -> looking for its library card")
    var card = waitFor(4) { libraryCard() }
    if card == nil {
        // The home view lists only recent books; the "All" list has every book.
        if let all = elements().first(where: { ["Vše", "All"].contains(labels($0)) || ["Vše", "All"].contains((attr($0, "AXTitle") as? String) ?? "") }) {
            AXUIElementPerformAction(all, kAXPressAction as CFString)
            state("opened library 'All'")
            card = waitFor(5) { libraryCard() }
        }
    }
    guard let found = card else { finish(.failed, "book '\(title)' not found in the visible library") }
    ensureFrontmost("open book")
    AXUIElementPerformAction(found, kAXPressAction as CFString)
    openedFromLibrary = true
    guard waitFor(10, { bookWindow() }) != nil else { finish(.failed, "book window did not open") }
    state("BOOK WINDOW OPENED from library")
    usleep(1_200_000) // the reader renders its first page after the window appears
} else {
    state("BOOK ALREADY OPEN")
}
report["openedFromLibrary"] = openedFromLibrary

// Make the book window the key window and require it to stay key (raise + main + focused window;
// no text values are written).
func focusBookWindow() -> Bool {
    ensureFrontmost("focus book window")
    guard let w = bookWindow() else { return false }
    AXUIElementPerformAction(w, kAXRaiseAction as CFString)
    AXUIElementSetAttributeValue(w, "AXMain" as CFString, kCFBooleanTrue)
    AXUIElementSetAttributeValue(ax, "AXFocusedWindow" as CFString, w)
    var stable = 0
    for _ in 0..<15 {
        usleep(150_000)
        ensureFrontmost("focus book window (waiting)")
        stable = focusedWindowTitle() == title ? stable + 1 : 0
        if stable >= 4 { return true }
    }
    return false
}
ensureFrontmost("focus book window")
guard focusBookWindow() else {
    finish(frontmost() ? .failed : .abortedFocus, "book window did not stay focused (focused: \(focusedWindowTitle() ?? "none"))")
}
state("VERIFIED book window focused")

// MARK: 3. Keyboard input (only into the verified book window)

func keys(_ script: String, _ what: String) {
    guard frontmost() else { finish(.abortedFocus, "focus left Books before: \(what)") }
    guard focusedWindowTitle() == title else { finish(.abortedFocus, "book window lost focus before: \(what)") }
    // System Events re-checks the frontmost process in the same script, right before the key.
    let s = """
    tell application "System Events"
      set fp to name of first process whose frontmost is true
      if fp is not "Books" then error "frontmost is " & fp
      \(script)
    end tell
    """
    var err: NSDictionary?
    NSAppleScript(source: s)?.executeAndReturnError(&err)
    if let err { finish(.abortedFocus, "keyboard refused (\(what)): \(err[NSAppleScript.errorMessage] ?? err)") }
    state("KEYS \(what)")
    usleep(150_000)
}
func searchField() -> AXUIElement? { elements().first { (attr($0, "AXSubrole") as? String) == "AXSearchField" } }

// MARK: 4. Open the search (verified)

state("OPENING SEARCH")
var field = searchField()
if field == nil {
    keys("keystroke \"f\" using command down", "⌘F")
    field = waitFor(3) { searchField() }
}
guard let sf = field else { finish(.failed, "search field did not appear after ⌘F") }
AXUIElementPerformAction(sf, kAXPressAction as CFString)
guard waitFor(2, { (attr(sf, "AXFocused") as? Bool) == true ? true : nil }) != nil else { finish(.failed, "search field is not focused") }
state("VERIFIED search field focused")

// MARK: 5. Enter the query (typed; pasted only for characters the keyboard cannot type), verified

func fieldValue() -> String { (attr(sf, "AXValue") as? String) ?? "" }
func clearField() {
    if !fieldValue().isEmpty {
        keys("keystroke \"a\" using command down", "select previous query")
        keys("key code 51", "delete previous query")
    }
}
/// Types `text`; falls back to a paste (restoring the clipboard's text afterwards) when the typed
/// result differs, e.g. for characters the current keyboard layout cannot produce.
/// Exact (scalar-level) comparison: Swift's String == treats NFC and NFD as equal, Books' search
/// does not.
func sameScalars(_ a: String, _ b: String) -> Bool { Array(a.unicodeScalars) == Array(b.unicodeScalars) }
func enter(_ text: String) -> String? {
    // After a search the focus moves to the result list: focus the field again and verify it,
    // otherwise ⌘A / Delete would act on something else.
    ensureFrontmost("focus search field")
    AXUIElementPerformAction(sf, kAXPressAction as CFString)
    guard waitFor(2, { (attr(sf, "AXFocused") as? Bool) == true ? true : nil }) != nil else { return nil }
    clearField()
    guard fieldValue().isEmpty else { return nil }
    if forcePaste { return paste(text) }
    let escaped = text.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
    keys("keystroke \"\(escaped)\"", "type query (\(text.count) chars)")
    if waitFor(3, { fieldValue() == text ? true : nil }) != nil {
        let v = fieldValue()
        report["typedScalars"] = v.unicodeScalars.count; report["queryScalars"] = text.unicodeScalars.count
        if sameScalars(v, text) { return "typed" }
        state("typed text is canonically equal but not scalar-identical (\(v.unicodeScalars.count) vs \(text.unicodeScalars.count) scalars) -> paste")
    }
    state("typed value differs ('\(fieldValue().prefix(60))') -> paste")
    return paste(text)
}
func paste(_ text: String) -> String? {
    clearField()
    let pb = NSPasteboard.general
    let saved = pb.string(forType: .string)
    pb.clearContents(); pb.setString(text, forType: .string)
    keys("keystroke \"v\" using command down", "paste query")
    let ok = waitFor(3, { sameScalars(fieldValue(), text) ? true : nil }) != nil
    pb.clearContents(); if let saved { pb.setString(saved, forType: .string) }
    return ok ? "pasted" : nil
}

// MARK: 6. Search, read results (virtualized list), decide

struct Result { let element: AXUIElement; let label: String; let chapter: String?; let page: Int? }
/// Result rows are the buttons inside the search popover. Their label is
/// "<chapter>, <page>, <snippet>" for books with chapters and "<page>, <snippet>" without
/// (e.g. fixed-layout/PDF-like books) - so rows are found structurally, not by label shape.
let resultLabel = try! NSRegularExpression(pattern: #"^(?:(.*?), )?(\d+), (.*)$"#, options: [.dotMatchesLineSeparators])
func results() -> [Result] {
    guard let popover = elements().first(where: { role($0) == "AXPopover" }) else { return [] }
    var nodes: [AXUIElement] = []; all(popover, 0, &nodes)
    return nodes.compactMap { e in
        guard role(e) == "AXButton" else { return nil }
        let l = labels(e)
        let ns = l as NSString
        guard let m = resultLabel.firstMatch(in: l, range: NSRange(location: 0, length: ns.length)) else { return nil }
        let chapter = m.range(at: 1).location == NSNotFound ? nil : ns.substring(with: m.range(at: 1))
        return Result(element: e, label: l, chapter: chapter, page: Int(ns.substring(with: m.range(at: 2))))
    }
}
func noResults() -> Bool {
    elements().contains { e in ["Žádné výsledky", "No Results", "Nenalezeny žádné výsledky"].contains { labels(e).contains($0) || ((attr(e, "AXValue") as? String) ?? "").contains($0) } }
}
/// All results (scrolling the virtualized list with AXScrollToVisible) - so ambiguity is judged on
/// the complete list, not on the rows that happen to be on screen.
func allResults() -> [Result] {
    var rows = results(), seen = Set(rows.map(\.label)), collected = rows
    var scrolls = 0
    while let last = rows.last, scrolls < 40 {
        AXUIElementPerformAction(last.element, "AXScrollToVisible" as CFString); usleep(350_000); scrolls += 1
        rows = results()
        let fresh = rows.filter { !seen.contains($0.label) }
        if fresh.isEmpty { break }
        fresh.forEach { seen.insert($0.label) }; collected += fresh
    }
    return collected
}

/// Books' search is literal (straight vs curly quotes, dashes and commas make a sentence miss):
/// try the whole quote first, then its longest punctuation-free run of words.
func fallbackQuery(_ q: String) -> String {
    let parts = q.components(separatedBy: CharacterSet(charactersIn: ",.;:!?\"“”‘’'—–-()[]…"))
        .map { $0.split(whereSeparator: { $0.isWhitespace }) }
    return (parts.max { $0.count < $1.count } ?? []).prefix(8).joined(separator: " ")
}
var attempts: [[String: Any]] = []
var found: [Result] = []
var usedQuery = ""
/// Books keeps typographic punctuation (’ “ ”) and its search is literal; quotes from other apps
/// or typed by hand often carry straight ones.
func typographic(_ q: String) -> String {
    var out = "", openQuote = true
    for ch in q {
        switch ch {
        case "'": out.append("’")
        case "\"": out.append(openQuote ? "“" : "”"); openQuote.toggle()
        default: out.append(ch)
        }
    }
    return out
}
for (strategy, q) in [("full quote", quote), ("typographic punctuation", typographic(quote)), ("longest segment", fallbackQuery(quote))] where found.isEmpty && !q.isEmpty {
    if q == usedQuery { continue }
    guard let how = enter(q) else { finish(.failed, "could not enter the query into the search field") }
    keys("key code 36", "Return")
    state("SEARCHING (\(strategy))")
    let outcome = waitFor(8) { () -> String? in
        if !results().isEmpty { return "results" }
        return noResults() ? "none" : nil
    }
    found = outcome == "results" ? allResults() : []
    attempts.append(["strategy": strategy, "query": q, "input": how, "results": found.count, "state": outcome ?? "timeout"])
    usedQuery = q
}
report["attempts"] = attempts
report["query"] = usedQuery
if found.isEmpty { finish(.notFound, "Books found no result for the quote (searched as in 'attempts')") }

// Choose: exactly one result, or exactly one in the hinted chapter. Never "the first of many".
let candidates = chapterHint.map { hint in found.filter { ($0.chapter ?? "").hasPrefix(hint) } } ?? found
report["candidates"] = candidates.map { String($0.label.prefix(120)) }
guard candidates.count == 1 else {
    finish(.ambiguous, candidates.isEmpty ? "no result in chapter \(chapterHint ?? "")" : "\(candidates.count) matching results — not choosing one")
}
let chosen = candidates[0]
report["chosen"] = String(chosen.label.prefix(160))
// The list must be settled before a press is reliable (a press right after the first rows appear
// was ignored, e.g. while a freshly opened book was still laying out): two identical reads.
var settledReads = 0, lastLabels: [String] = []
for _ in 0..<10 where settledReads < 2 {
    usleep(250_000)
    let now = results().map(\.label)
    settledReads = now == lastLabels ? settledReads + 1 : 0
    lastLabels = now
}
// Press the verified row; if nothing changed (search still open and the very same row still
// there) press that same row once more - never anything else.
var presses = 0
var closed = false
while presses < 2 && !closed {
    ensureFrontmost("select result")
    guard let row = results().first(where: { $0.label == chosen.label }) else { break }
    AXUIElementPerformAction(row.element, kAXPressAction as CFString)
    presses += 1
    state("SELECTED result (press \(presses))")
    closed = waitFor(4, { searchField() == nil ? true : nil }) != nil
}
report["presses"] = presses
guard closed else { finish(.failed, "search did not close after selecting the result (\(presses) presses)") }

// MARK: 7. Verify the landing: the quote is on the visible page of the book window

let probe = norm(usedQuery)
func visiblePageText() -> String {
    guard let w = bookWindow(), let wf = frame(w) else { return "" }
    var nodes: [AXUIElement] = []; all(w, 0, &nodes)
    return nodes.filter { role($0) == "AXStaticText" }
        .filter { e in frame(e).map { wf.contains(CGPoint(x: $0.midX, y: $0.midY)) } ?? false }
        .map { ((attr($0, "AXValue") as? String) ?? labels($0)) }.joined(separator: " ")
}
// Books exposes the new page's text to Accessibility with a delay.
guard waitFor(6, { norm(visiblePageText()).contains(probe) ? true : nil }) != nil else {
    finish(.failed, "selected a result but the quote is not on the visible page")
}
state("VERIFIED quote on the visible page")
finish(.completed, "landed on the quote")
