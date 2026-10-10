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
    /// MD-7: received link and book cards, per sending peer (see `HandoffCards`).
    @Published private(set) var handoff = HandoffCards()
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
    /// Whether handoff from (receiving) or a link to (sending) this peer is offered now.
    var receivable: (_ deviceID: String) -> Bool = { _ in false }
    var sendable: (_ deviceID: String) -> Bool = { _ in false }
    /// Whether the local grant still allows this peer's cards to be used (also when it is offline).
    var granted: (_ deviceID: String) -> Bool = { _ in false }
    var peerName: (_ deviceID: String) -> String = { _ in "Android" }
    /// Sends one quick-action message on exactly this peer's session.
    var send: (_ kind: String, _ payload: [String: Any], _ deviceID: String) -> Bool = { _, _, _ in false }
    /// The link sent to each peer and not confirmed yet: deviceID -> requestId.
    private var pending: [String: String] = [:]
    private var timeouts: [String: Task<Void, Never>] = [:]
    private var sequence = 0

    var cards: [HandoffCard] { handoff.cards }

    /// Mac -> Android "Link": the copied link to `deviceID` (the peer's card, or the only eligible
    /// peer for the keyboard shortcut). Never another peer.
    func sendClipboardLink(to deviceID: String) {
        let name = peerName(deviceID)
        guard let link = NSPasteboard.general.string(forType: .string).flatMap(validatedWebLink) else {
            status = "Copy a valid http or https link first"; return
        }
        guard sendable(deviceID) else { status = "Web links are unavailable on \(name)"; return }
        guard pending[deviceID] == nil else { status = "The previous link to \(name) is still waiting for confirmation"; return }
        let id = UUID().uuidString.lowercased()
        pending[deviceID] = id
        sequence += 1
        status = "Sending link to \(name)…"
        if !send("quick.request", ["version": 1, "requestId": id, "feature": "links", "action": "offer", "value": link, "sequence": sequence], deviceID) {
            pending[deviceID] = nil; status = "\(name) is not connected — link not sent"; return
        }
        timeouts[deviceID]?.cancel()
        timeouts[deviceID] = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            guard !Task.isCancelled, self?.pending[deviceID] == id else { return }
            self?.pending[deviceID] = nil
            self?.status = "\(name) did not confirm delivery"
        }
    }

    /// A quick-action message from `deviceID` (the authenticated sender).
    func receive(_ kind: String, payload: [String: Any], from deviceID: String) {
        guard payload["version"] as? Int == 1,
              let id = payload["requestId"] as? String, UUID(uuidString: id) != nil else { return }
        let name = peerName(deviceID)
        if kind == "quick.result" {
            // Only this peer's own pending link completes; another peer's result never does.
            guard pending[deviceID] == id, payload["feature"] as? String == "links" else { return }
            pending[deviceID] = nil; timeouts.removeValue(forKey: deviceID)?.cancel()
            status = payload["accepted"] as? Bool == true
                ? "Link delivered to \(name) — waiting for the user to open it"
                : "Link declined by \(name) — check settings or dismiss the previous link"
        } else if kind == "quick.request", payload["action"] as? String == "book" {
            let book = (payload["value"] as? String).flatMap(validatedBookHandoff)
            var accepted = false
            if receivable(deviceID), payload["feature"] as? String == "links", let book {
                accepted = handoff.add(HandoffCard(deviceID: deviceID, peerName: name, content: .book(book), receivedAt: Date()))
            }
            if accepted {
                status = "Continue reading received from \(name) — see below"
                NSSound(named: NSSound.Name("Glass"))?.play()
            }
            _ = send("quick.result", ["version": 1, "requestId": id, "feature": "links", "accepted": accepted], deviceID)
        } else if kind == "quick.request" {
            let link = (payload["value"] as? String).flatMap(validatedWebLink)
            var accepted = false
            if receivable(deviceID), payload["feature"] as? String == "links", payload["action"] as? String == "offer", let link {
                accepted = handoff.add(HandoffCard(deviceID: deviceID, peerName: name, content: .link(link), receivedAt: Date()))
            }
            if accepted {
                status = "Link received from \(name) — open it below"
                NSSound(named: NSSound.Name("Glass"))?.play()
            }
            _ = send("quick.result", ["version": 1, "requestId": id, "feature": "links", "accepted": accepted], deviceID)
        }
    }

    /// The card's book, while the local grant still allows its sender.
    private func book(_ cardID: String) -> BookHandoff? {
        guard let card = handoff.card(cardID), granted(card.deviceID), case let .book(book) = card.content else { return nil }
        return book
    }
    /// A grant change while Find in Books was running, applied when it finishes.
    private var policyChangedDuringFind = false

    func openLink(_ cardID: String) {
        guard let card = handoff.card(cardID), granted(card.deviceID), case let .link(value) = card.content,
              let link = validatedWebLink(value), let url = URL(string: link) else { return }
        if NSWorkspace.shared.open(url) { handoff.remove(cardID) } else { status = "Could not open your browser" }
    }

    func dismiss(_ cardID: String) {
        guard !(findingInBooks && handoff.card(cardID)?.isLink == false) else { return }
        handoff.remove(cardID)
    }

    /// Copies the quote (to find the place with ⌘F in Books) and opens Apple Books.
    func continueInBooks(_ cardID: String) {
        guard let book = book(cardID) else { return }
        if let quote = book.quote { copyText(quote) }
        if openBooks() {
            handoff.remove(cardID)
            status = book.quote != nil ? "Quote copied — in Books open the book, press ⌘F and ⌘V" : "Books opened — open the book and go to the page shown"
        } else {
            status = "Could not open Books"
        }
    }

    /// Opens Apple Books, searches the quote and lands on it (explicit click only). Anything the
    /// automation cannot verify ends in a message, with the quote copied for the manual way.
    func findInBooks(_ cardID: String) {
        guard let book = book(cardID), book.title != nil, book.quote != nil || book.chapter != nil, !findingInBooks else { return }
        guard accessibilityTrusted(true) else {
            status = "Allow Bridgey in System Settings → Privacy & Security → Accessibility, then try again"
            return
        }
        findingInBooks = true
        status = "Opening Books…"
        let run = runBooksAutomation
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let result = run(book) { text in DispatchQueue.main.async { if self?.findingInBooks == true { self?.status = text } } }
            DispatchQueue.main.async { self?.finishFind(result, book: book, cardID: cardID) }
        }
    }

    private func finishFind(_ result: BooksAutomationResult, book: BookHandoff, cardID: String) {
        findingInBooks = false
        if policyChangedDuringFind {
            policyChangedDuringFind = false
            handoff.keep(where: granted)
        }
        switch result {
        case .completed:
            if case .book(book)? = handoff.card(cardID)?.content { handoff.remove(cardID) }
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

    func report(_ message: String) { status = message }

    /// The peer's session ended: its unconfirmed link is over; its cards stay (MD-7, D2).
    func deviceEnded(_ deviceID: String) {
        timeouts.removeValue(forKey: deviceID)?.cancel()
        if pending.removeValue(forKey: deviceID) != nil {
            status = "\(peerName(deviceID)) disconnected — link not confirmed"
        }
    }

    /// The local grant changed: cards of peers no longer allowed are dropped (never while Find in
    /// Books is running on one of them).
    func policyChanged() {
        guard !findingInBooks else { policyChangedDuringFind = true; return }
        handoff.keep(where: granted)
    }
}
