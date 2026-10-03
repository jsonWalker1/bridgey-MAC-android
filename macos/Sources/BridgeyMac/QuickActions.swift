import AppKit
import Foundation

struct QuickRequestSequence {
    private var last: [String: Int] = [:]
    mutating func accept(feature: String, sequence: Int) -> Bool {
        guard ["links", "media"].contains(feature), (1...9_007_199_254_740_991).contains(sequence),
              sequence > last[feature, default: 0] else { return false }
        last[feature] = sequence
        return true
    }
}

func validatedWebLink(_ value: String) -> String? {
    let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard text.utf8.count <= 4096,
          !text.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) || $0.value < 32 || $0 == "\\" }),
          let parts = URLComponents(string: text),
          ["http", "https"].contains(parts.scheme?.lowercased() ?? ""),
          let host = parts.host, !host.isEmpty, parts.user == nil, parts.password == nil,
          parts.port == nil || (1...65535).contains(parts.port!) else { return nil }
    return text
}

/// Books Handoff Alpha: "continue reading" details sent by Android as the "book" action of Web links.
/// Display-only; Apple Books is never automated.
struct BookHandoff: Equatable {
    let title: String?
    let chapter: String?
    let page: Int?
    let pages: Int?
    let quote: String?
    var app: String? = nil

    /// Google Play Books syncs the reading position through the Google account, so its web reader
    /// continues where the phone was.
    var fromGooglePlayBooks: Bool { app == "com.google.android.apps.books" }
}

func validatedBookHandoff(_ value: String) -> BookHandoff? {
    guard value.utf8.count <= 4096, let data = value.data(using: .utf8),
          let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
          json["version"] as? Int == 1 else { return nil }
    func text(_ key: String, _ max: Int) -> String? {
        guard let raw = json[key] as? String else { return nil }
        let cleaned = raw.unicodeScalars.filter { $0.value >= 32 || $0 == "\n" }.map(String.init).joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? nil : String(cleaned.prefix(max))
    }
    let title = text("title", 200), quote = text("quote", 500)
    guard title != nil || quote != nil else { return nil }
    let pages = (json["pages"] as? Int).flatMap { (1...100_000).contains($0) ? $0 : nil }
    let page = (json["page"] as? Int).flatMap { $0 >= 1 && (pages == nil || $0 <= pages!) ? $0 : nil }
    return BookHandoff(title: title, chapter: text("chapter", 200), page: page, pages: page == nil ? nil : pages, quote: quote,
                       app: text("app", 64))
}

@MainActor
final class QuickActions: ObservableObject {
    @Published private(set) var receivedLink: String?
    @Published private(set) var receivedBook: BookHandoff?
    /// Opening Apple Books and copying the quote; replaceable in tests.
    var openBooks: () -> Bool = {
        NSWorkspace.shared.open(URL(fileURLWithPath: "/System/Applications/Books.app"))
    }
    @Published private(set) var findingInBooks = false
    /// "Find in Books" automation; replaceable in tests. Called off the main thread.
    var runBooksAutomation: (BookHandoff, @escaping (String) -> Void) -> BooksAutomationResult = { book, status in
        BooksAutomation(title: book.title ?? "", quote: book.quote ?? "", chapter: book.chapter, status: status).run()
    }
    var accessibilityTrusted: (_ prompt: Bool) -> Bool = { prompt in
        AXIsProcessTrustedWithOptions([kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: prompt] as CFDictionary)
    }
    var openURL: (URL) -> Bool = { NSWorkspace.shared.open($0) }
    var copyText: (String) -> Void = { text in
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }
    @Published private(set) var status: String?
    var available: (BridgeyFeature) -> Bool = { _ in false }
    var send: (String, [String: Any]) -> Bool = { _, _ in false }
    private var pending: String?
    private var timeout: Task<Void, Never>?
    private var sequence = 0

    func sendClipboardLink() {
        guard let link = NSPasteboard.general.string(forType: .string).flatMap(validatedWebLink) else {
            status = "Copy a valid http or https link first"; return
        }
        guard available(.links) else { status = "Web links are unavailable on one of your devices"; return }
        guard pending == nil else { return }
        let id = UUID().uuidString.lowercased()
        pending = id
        sequence += 1
        status = "Sending link…"
        if !send("quick.request", ["version": 1, "requestId": id, "feature": "links", "action": "offer", "value": link, "sequence": sequence]) {
            pending = nil; status = "Not connected — link not sent"; return
        }
        timeout?.cancel()
        timeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            guard !Task.isCancelled, self?.pending == id else { return }
            self?.pending = nil
            self?.status = "Android did not confirm delivery"
        }
    }

    func receive(_ kind: String, payload: [String: Any]) {
        guard payload["version"] as? Int == 1,
              let id = payload["requestId"] as? String, UUID(uuidString: id) != nil else { return }
        if kind == "quick.result" {
            guard id == pending, payload["feature"] as? String == "links" else { return }
            pending = nil; timeout?.cancel()
            status = payload["accepted"] as? Bool == true
                ? "Link delivered — waiting for the user to open it"
                : "Link declined — check settings or dismiss the previous link"
        } else if kind == "quick.request", payload["action"] as? String == "book" {
            let book = (payload["value"] as? String).flatMap(validatedBookHandoff)
            let accepted = available(.links) && payload["feature"] as? String == "links" && book != nil && receivedBook == nil
            if accepted {
                receivedBook = book
                status = "Continue reading received — see below"
                NSSound(named: NSSound.Name("Glass"))?.play()
            }
            _ = send("quick.result", ["version": 1, "requestId": id, "feature": "links", "accepted": accepted])
        } else if kind == "quick.request" {
            let link = (payload["value"] as? String).flatMap(validatedWebLink)
            let accepted = available(.links) && payload["feature"] as? String == "links" &&
                payload["action"] as? String == "offer" && link != nil && receivedLink == nil
            if accepted {
                receivedLink = link
                status = "Link received — open it below"
                NSSound(named: NSSound.Name("Glass"))?.play()
            }
            _ = send("quick.result", ["version": 1, "requestId": id, "feature": "links", "accepted": accepted])
        }
    }

    func openLink() {
        guard available(.links), let link = receivedLink.flatMap(validatedWebLink), let url = URL(string: link) else { return }
        if NSWorkspace.shared.open(url) { receivedLink = nil } else { status = "Could not open your browser" }
    }
    func dismissLink() { receivedLink = nil }

    /// Copies the quote (to find the place with ⌘F in Books) and opens Apple Books.
    func continueInBooks() {
        guard let book = receivedBook else { return }
        if let quote = book.quote { copyText(quote) }
        if openBooks() {
            receivedBook = nil
            status = book.quote != nil ? "Quote copied — in Books open the book, press ⌘F and ⌘V" : "Books opened — open the book and go to the page shown"
        } else {
            status = "Could not open Books"
        }
    }
    func dismissBook() { receivedBook = nil }

    /// Opens Apple Books, searches the quote and lands on it (explicit click only). Anything the
    /// automation cannot verify ends in a message, with the quote copied for the manual way.
    func findInBooks() {
        guard let book = receivedBook, book.title != nil, book.quote != nil || book.chapter != nil, !findingInBooks else { return }
        guard accessibilityTrusted(true) else {
            status = "Allow Bridgey in System Settings → Privacy & Security → Accessibility, then try again"
            return
        }
        findingInBooks = true
        status = "Opening Books…"
        let run = runBooksAutomation
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let result = run(book) { text in DispatchQueue.main.async { if self?.findingInBooks == true { self?.status = text } } }
            DispatchQueue.main.async { self?.finishFind(result, book: book) }
        }
    }

    private func finishFind(_ result: BooksAutomationResult, book: BookHandoff) {
        findingInBooks = false
        switch result {
        case .completed:
            if receivedBook == book { receivedBook = nil }
            status = book.quote != nil ? "Found in Books — you are at the quote"
                : "Books is at the start of \(book.chapter ?? "the chapter")" + (book.page.map { " (phone: page \($0))" } ?? "")
        case .notFound:
            status = "Books did not find this quote (different edition?)"
        case .ambiguous(let count):
            book.quote.map(copyText)
            status = "The quote appears \(count)× — quote copied; pick the right place with ⌘F, ⌘V"
        case .abortedFocus:
            status = "Stopped: another app came to the front. Try again without switching apps"
        case .failed(let reason):
            book.quote.map(copyText)
            status = "Could not finish in Books (\(reason)) — quote copied for ⌘F, ⌘V"
        }
    }

    /// Experiment: Google Play Books on the web continues at the position synced from the phone.
    func openInGooglePlayBooks() {
        if openURL(URL(string: "https://play.google.com/books")!) {
            status = "Google Play Books opened — open the book there, it continues at the synced page"
        } else {
            status = "Could not open your browser"
        }
    }
    func reset() { timeout?.cancel(); pending = nil; receivedLink = nil; if !findingInBooks { receivedBook = nil }; status = nil }
}
