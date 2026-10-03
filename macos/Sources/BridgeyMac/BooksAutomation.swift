import AppKit
import ApplicationServices

/// BOOKS HANDOFF ALPHA - "Find in Books": opens Apple Books, searches the quote received from
/// Android and lands on it. Ported from the Apple Books automation POC
/// (experiments/books-automation-poc, see its README for the measured scenarios).
///
/// A state machine: every step has an expected state verified through the Accessibility API and
/// anything unexpected stops the run. It never clicks coordinates and never "tries something else".
///   - never writes text into Books through AXValue (it broke Books' search until restart),
///   - never sends Escape (it closes the book window),
///   - keyboard input (via System Events, which re-checks the frontmost process in the same
///     script) only while Books is frontmost and the book window is focused,
///   - nothing that could re-activate Books is done unless Books is verifiably in front.
/// Runs synchronously; call it off the main thread. Requires the Accessibility permission and
/// Automation access to System Events.
enum BooksAutomationResult: Equatable {
    case completed
    case notFound
    case ambiguous(Int)
    case abortedFocus(String)
    case failed(String)
}

struct BooksAutomation {
    let title: String
    let quote: String
    let chapter: String?
    var status: (String) -> Void = { _ in }

    private static let bundleID = "com.apple.iBooksX"
    private static let booksURL = URL(fileURLWithPath: "/System/Applications/Books.app")

    private struct Stop: Error { let result: BooksAutomationResult }
    private struct Row { let element: AXUIElement; let label: String; let chapter: String? }

    func run() -> BooksAutomationResult {
        do { try perform(); return .completed } catch let stop as Stop {
            // A failure while Books is no longer in front is the focus loss it most likely is.
            if case .failed(let reason) = stop.result,
               NSWorkspace.shared.frontmostApplication?.bundleIdentifier != Self.bundleID {
                return .abortedFocus(reason)
            }
            return stop.result
        } catch { return .failed("\(error)") }
    }

    // MARK: - Steps

    private func perform() throws {
        let quote = self.quote.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        // Without a quote (e.g. the Play Books tile) the chapter heading is searched instead: the
        // result whose snippet is the heading itself is the start of that chapter.
        let heading = quote.isEmpty ? chapter.flatMap(Self.chapterName) : nil
        guard !quote.isEmpty || heading != nil else { throw Stop(result: .failed("nothing to search: no quote and no chapter")) }
        guard AXIsProcessTrusted() else { throw Stop(result: .failed("Bridgey has no Accessibility permission")) }

        status("Opening Books…")
        if booksApp() == nil {
            NSWorkspace.shared.openApplication(at: Self.booksURL, configuration: .init())
            guard waitFor(15, { booksApp() }) != nil else { throw Stop(result: .failed("Books did not start")) }
        }
        let app = booksApp()!
        let ctx = Context(app: app, title: title)

        if !ctx.frontmost() {
            NSAppleScript(source: "tell application id \"\(Self.bundleID)\" to activate")?.executeAndReturnError(nil)
            // Books with no window ignores "activate"; a LaunchServices open (= Dock click) works.
            if waitFor(2.5, { ctx.frontmost() ? true : nil }) == nil {
                let config = NSWorkspace.OpenConfiguration(); config.activates = true
                NSWorkspace.shared.openApplication(at: Self.booksURL, configuration: config)
            }
        }
        guard waitFor(6, { ctx.frontmost() ? true : nil }) != nil else { throw Stop(result: .abortedFocus("Books did not come to the front")) }

        // Windows are only exposed while Books is in front: "no window" is trusted only then.
        if waitFor(2, { ctx.windows().isEmpty ? nil : true }) == nil {
            try ctx.ensureFrontmost("reopen")
            NSAppleScript(source: "tell application id \"\(Self.bundleID)\" to reopen")?.executeAndReturnError(nil)
            guard waitFor(8, { ctx.windows().isEmpty ? nil : true }) != nil else { throw Stop(result: .failed("Books shows no window")) }
        }

        if ctx.bookWindow() == nil {
            status("Opening the book…")
            var card = waitFor(4) { ctx.libraryCard() }
            if card == nil, let all = ctx.elements().first(where: { ["Vše", "All"].contains(ctx.labels($0)) }) {
                AXUIElementPerformAction(all, kAXPressAction as CFString)
                card = waitFor(5) { ctx.libraryCard() }
            }
            guard let found = card else { throw Stop(result: .failed("“\(title)” is not in your Books library")) }
            try ctx.ensureFrontmost("open book")
            AXUIElementPerformAction(found, kAXPressAction as CFString)
            guard waitFor(10, { ctx.bookWindow() }) != nil else { throw Stop(result: .failed("the book did not open")) }
            usleep(1_200_000)
        }
        try ctx.focusBookWindow()

        status("Searching…")
        var field = ctx.searchField()
        if field == nil {
            try ctx.keys("keystroke \"f\" using command down", "⌘F")
            field = waitFor(3) { ctx.searchField() }
        }
        guard let sf = field else { throw Stop(result: .failed("the search field did not open")) }

        var found: [Row] = []
        var lastQuery = ""
        let queries = heading.map { [$0, Self.longestSegment($0)] } ?? [quote, Self.typographic(quote), Self.longestSegment(quote)]
        for q in queries where found.isEmpty && !q.isEmpty && q != lastQuery {
            try ctx.enter(q, into: sf)
            try ctx.keys("key code 36", "Return")
            lastQuery = q
            let outcome = waitFor(8) { () -> String? in
                if !ctx.results().isEmpty { return "rows" }
                return ctx.noResults() ? "none" : nil
            }
            found = outcome == "rows" ? ctx.allResults() : []
        }
        if found.isEmpty { throw Stop(result: .notFound) }

        // Exactly one result, or exactly one in the chapter received from the phone. Never the
        // first of many.
        var candidates = found
        if let heading {
            // The heading row: its snippet is the chapter name (the contents page and mentions in
            // the text are other rows).
            let name = Self.norm(heading)
            candidates = found.filter { Self.norm(Self.snippet($0.label)) == name }
            if candidates.count > 1, let hint = chapter.map(Self.norm) {
                candidates = candidates.filter { row in row.chapter.map { Self.norm($0).hasPrefix(hint) || hint.hasPrefix(Self.norm($0)) } ?? false }
            }
        } else if candidates.count > 1, let hint = chapter.map(Self.norm), !hint.isEmpty {
            candidates = found.filter { row in
                guard let c = row.chapter.map(Self.norm), !c.isEmpty else { return false }
                return c.hasPrefix(hint) || hint.hasPrefix(c)
            }
        }
        guard candidates.count == 1 else { throw Stop(result: .ambiguous(candidates.isEmpty ? found.count : candidates.count)) }
        let chosen = candidates[0]

        // Press only once the list has settled; if nothing happened, press the same row once more.
        var settled = 0, previous: [String] = []
        for _ in 0..<10 where settled < 2 {
            usleep(250_000)
            let now = ctx.results().map(\.label)
            settled = now == previous ? settled + 1 : 0
            previous = now
        }
        var closed = false
        for _ in 0..<2 where !closed {
            try ctx.ensureFrontmost("select result")
            guard let row = ctx.results().first(where: { $0.label == chosen.label }) else { break }
            AXUIElementPerformAction(row.element, kAXPressAction as CFString)
            closed = waitFor(4, { ctx.searchField() == nil ? true : nil }) != nil
        }
        guard closed else { throw Stop(result: .failed("Books did not jump to the result")) }

        let probe = Self.norm(lastQuery)
        guard waitFor(6, { Self.norm(ctx.visiblePageText()).contains(probe) ? true : nil }) != nil else {
            throw Stop(result: .failed("the quote is not on the page Books shows"))
        }
    }

    // MARK: - Query helpers (pure)

    /// Lowercase letters/digits only: Books' typography differs from the sent quote.
    static func norm(_ s: String) -> String {
        s.lowercased().map { $0.isLetter || $0.isNumber ? String($0) : " " }.joined()
            .split(separator: " ").joined(separator: " ")
    }

    /// Books keeps ’ “ ” and searches literally; quotes often arrive with straight ones.
    static func typographic(_ q: String) -> String {
        var out = "", opening = true
        for ch in q {
            switch ch {
            case "'": out.append("’")
            case "\"": out.append(opening ? "“" : "”"); opening.toggle()
            default: out.append(ch)
            }
        }
        return out
    }

    /// The longest punctuation-free run of at most eight words.
    static func longestSegment(_ q: String) -> String {
        let parts = q.components(separatedBy: CharacterSet(charactersIn: ",.;:!?\"“”‘’'—–-()[]…"))
            .map { $0.split(whereSeparator: { $0.isWhitespace }) }
        return (parts.max { $0.count < $1.count } ?? []).prefix(8).joined(separator: " ")
    }

    /// "CHAPTER X. The Lobster Quadrille" -> "The Lobster Quadrille"; a bare name stays as it is.
    static func chapterName(_ chapter: String) -> String? {
        let name = chapter.replacingOccurrences(of: #"^\s*(CHAPTER|Chapter|KAPITOLA|Kapitola)\s+[IVXLCDM0-9]+\.?\s*"#, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? nil : name
    }

    static func snippet(_ label: String) -> String {
        let ns = label as NSString
        guard let m = resultLabel.firstMatch(in: label, range: NSRange(location: 0, length: ns.length)) else { return label }
        return ns.substring(with: m.range(at: 3))
    }

    /// "<chapter>, <page>, <snippet>" (books with chapters) or "<page>, <snippet>".
    static func parseResultLabel(_ label: String) -> (chapter: String?, page: Int)? {
        let ns = label as NSString
        guard let m = resultLabel.firstMatch(in: label, range: NSRange(location: 0, length: ns.length)),
              let page = Int(ns.substring(with: m.range(at: 2))) else { return nil }
        let chapter = m.range(at: 1).location == NSNotFound ? nil : ns.substring(with: m.range(at: 1))
        return (chapter, page)
    }
    private static let resultLabel = try! NSRegularExpression(pattern: #"^(?:(.*?), )?(\d+), (.*)$"#, options: [.dotMatchesLineSeparators])

    private func booksApp() -> NSRunningApplication? {
        NSRunningApplication.runningApplications(withBundleIdentifier: Self.bundleID).first
    }

    private func waitFor<T>(_ timeout: Double, _ f: () -> T?) -> T? {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end { if let v = f() { return v }; usleep(200_000) }
        return nil
    }

    // MARK: - Accessibility context

    private struct Context {
        let app: NSRunningApplication
        let ax: AXUIElement
        let title: String
        init(app: NSRunningApplication, title: String) {
            self.app = app; self.title = title; ax = AXUIElementCreateApplication(app.processIdentifier)
        }

        func attr(_ e: AXUIElement, _ n: String) -> AnyObject? { var v: AnyObject?; return AXUIElementCopyAttributeValue(e, n as CFString, &v) == .success ? v : nil }
        func kids(_ e: AXUIElement) -> [AXUIElement] { (attr(e, "AXChildren") as? [AXUIElement]) ?? [] }
        func role(_ e: AXUIElement) -> String { (attr(e, "AXRole") as? String) ?? "" }
        func labels(_ e: AXUIElement) -> String { ((attr(e, "AXUserInputLabels") as? [String]) ?? []).joined(separator: " ") }
        func all(_ e: AXUIElement, _ d: Int, _ out: inout [AXUIElement]) { out.append(e); if d < 45 { kids(e).forEach { all($0, d + 1, &out) } } }
        func elements() -> [AXUIElement] { var o: [AXUIElement] = []; all(ax, 0, &o); return o }
        func windows() -> [AXUIElement] { (attr(ax, "AXWindows") as? [AXUIElement]) ?? [] }
        func frontmost() -> Bool { NSWorkspace.shared.frontmostApplication?.processIdentifier == app.processIdentifier }
        func bookWindow() -> AXUIElement? { windows().first { (attr($0, "AXTitle") as? String) == title } }
        func focusedWindowTitle() -> String? { attr(ax, "AXFocusedWindow").flatMap { attr($0 as! AXUIElement, "AXTitle") as? String } }
        func libraryCard() -> AXUIElement? { elements().first { role($0) == "AXButton" && labels($0).contains(title) } }
        func searchField() -> AXUIElement? { elements().first { (attr($0, "AXSubrole") as? String) == "AXSearchField" } }

        func ensureFrontmost(_ step: String) throws {
            guard frontmost() else { throw Stop(result: .abortedFocus("focus left Books before: \(step)")) }
        }

        /// Raise + main + focused window (no text values written); focus must stay for 0.6 s.
        func focusBookWindow() throws {
            try ensureFrontmost("focus the book")
            guard let w = bookWindow() else { throw Stop(result: .failed("the book window is gone")) }
            AXUIElementPerformAction(w, kAXRaiseAction as CFString)
            AXUIElementSetAttributeValue(w, "AXMain" as CFString, kCFBooleanTrue)
            AXUIElementSetAttributeValue(ax, "AXFocusedWindow" as CFString, w)
            var stable = 0
            for _ in 0..<15 {
                usleep(150_000)
                try ensureFrontmost("focus the book (waiting)")
                stable = focusedWindowTitle() == title ? stable + 1 : 0
                if stable >= 4 { return }
            }
            throw Stop(result: .failed("the book window did not stay focused"))
        }

        func keys(_ script: String, _ what: String) throws {
            try ensureFrontmost(what)
            guard focusedWindowTitle() == title else { throw Stop(result: .abortedFocus("the book window lost focus before: \(what)")) }
            let source = """
            tell application "System Events"
              set fp to bundle identifier of first process whose frontmost is true
              if fp is not "\(BooksAutomation.bundleID)" then error "frontmost is " & fp
              \(script)
            end tell
            """
            var error: NSDictionary?
            NSAppleScript(source: source)?.executeAndReturnError(&error)
            if let error { throw Stop(result: .abortedFocus("keyboard refused (\(what)): \(error[NSAppleScript.errorMessage] ?? "")")) }
            usleep(150_000)
        }

        func fieldValue(_ field: AXUIElement) -> String { (attr(field, "AXValue") as? String) ?? "" }

        /// Focus the field (after a search the focus is in the result list), clear it with ⌘A +
        /// Delete, type; paste (clipboard text restored) if typing produced a different string.
        /// The value is compared scalar by scalar - Books' search does not treat NFC/NFD as equal.
        func enter(_ text: String, into field: AXUIElement) throws {
            try ensureFrontmost("focus search field")
            AXUIElementPerformAction(field, kAXPressAction as CFString)
            guard waitForValue(2, { (attr(field, "AXFocused") as? Bool) == true }) else { throw Stop(result: .failed("the search field is not focused")) }
            func clear() throws {
                if !fieldValue(field).isEmpty {
                    try keys("keystroke \"a\" using command down", "select previous search")
                    try keys("key code 51", "delete previous search")
                }
                guard fieldValue(field).isEmpty else { throw Stop(result: .failed("could not clear the search field")) }
            }
            func exact() -> Bool { Array(fieldValue(field).unicodeScalars) == Array(text.unicodeScalars) }
            try clear()
            let escaped = text.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
            try keys("keystroke \"\(escaped)\"", "type the quote")
            if waitForValue(3, exact) { return }
            try clear()
            let pasteboard = NSPasteboard.general
            let saved = pasteboard.string(forType: .string)
            pasteboard.clearContents(); pasteboard.setString(text, forType: .string)
            defer { pasteboard.clearContents(); if let saved { pasteboard.setString(saved, forType: .string) } }
            try keys("keystroke \"v\" using command down", "paste the quote")
            guard waitForValue(3, exact) else { throw Stop(result: .failed("could not enter the quote")) }
        }

        func waitForValue(_ timeout: Double, _ f: () -> Bool) -> Bool {
            let end = Date().addingTimeInterval(timeout)
            while Date() < end { if f() { return true }; usleep(200_000) }
            return false
        }

        /// Result rows = the buttons inside the search popover whose label parses as a result.
        func results() -> [Row] {
            guard let popover = elements().first(where: { role($0) == "AXPopover" }) else { return [] }
            var nodes: [AXUIElement] = []; all(popover, 0, &nodes)
            return nodes.compactMap { e in
                // Books repeats the label as a second user-input label; the first one is the row.
                guard role(e) == "AXButton", let label = (attr(e, "AXUserInputLabels") as? [String])?.first,
                      let parsed = BooksAutomation.parseResultLabel(label) else { return nil }
                return Row(element: e, label: label, chapter: parsed.chapter)
            }
        }

        func noResults() -> Bool {
            elements().contains { e in
                let text = labels(e) + " " + ((attr(e, "AXValue") as? String) ?? "")
                return ["Žádné výsledky", "No Results", "No results"].contains { text.contains($0) }
            }
        }

        /// The whole (virtualized) list, scrolled with AXScrollToVisible.
        func allResults() -> [Row] {
            var rows = results(), seen = Set(rows.map(\.label)), collected = rows
            for _ in 0..<40 {
                guard let last = rows.last else { break }
                AXUIElementPerformAction(last.element, "AXScrollToVisible" as CFString); usleep(350_000)
                rows = results()
                let fresh = rows.filter { !seen.contains($0.label) }
                if fresh.isEmpty { break }
                fresh.forEach { seen.insert($0.label) }; collected += fresh
            }
            return collected
        }

        func visiblePageText() -> String {
            guard let w = bookWindow(), let wp = attr(w, "AXPosition"), let wz = attr(w, "AXSize") else { return "" }
            var p = CGPoint.zero, z = CGSize.zero
            AXValueGetValue(wp as! AXValue, .cgPoint, &p); AXValueGetValue(wz as! AXValue, .cgSize, &z)
            let frame = CGRect(origin: p, size: z)
            var nodes: [AXUIElement] = []; all(w, 0, &nodes)
            return nodes.filter { role($0) == "AXStaticText" }.filter { e in
                guard let ep = attr(e, "AXPosition"), let ez = attr(e, "AXSize") else { return false }
                var q = CGPoint.zero, s = CGSize.zero
                AXValueGetValue(ep as! AXValue, .cgPoint, &q); AXValueGetValue(ez as! AXValue, .cgSize, &s)
                return frame.contains(CGPoint(x: q.x + s.width / 2, y: q.y + s.height / 2))
            }.map { (attr($0, "AXValue") as? String) ?? labels($0) }.joined(separator: " ")
        }
    }
}
