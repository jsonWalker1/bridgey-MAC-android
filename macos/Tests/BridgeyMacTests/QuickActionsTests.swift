import XCTest
@testable import BridgeyMac

final class QuickActionsTests: XCTestCase {
    func testEncryptedSequenceRejectsReplayIndependentlyOfOuterMessageID() {
        var sequence = QuickRequestSequence()
        XCTAssertTrue(sequence.accept(feature: "media", sequence: 4))
        XCTAssertFalse(sequence.accept(feature: "media", sequence: 4))
        XCTAssertFalse(sequence.accept(feature: "media", sequence: 3))
        XCTAssertTrue(sequence.accept(feature: "links", sequence: 1))
        XCTAssertTrue(sequence.accept(feature: "media", sequence: 5))
        XCTAssertFalse(sequence.accept(feature: "other", sequence: 6))
        XCTAssertFalse(sequence.accept(feature: "media", sequence: Int.max))
    }
    func testBrowserLinkValidation() {
        for link in ["https://example.com/path?q=one#two", "http://localhost:8080/", "https://[::1]/"] {
            XCTAssertEqual(validatedWebLink(link), link)
        }
        XCTAssertEqual(validatedWebLink(" https://example.com\n"), "https://example.com")
        for link in ["", "file:///etc/passwd", "javascript:alert(1)", "tel:+12345678", "bridgey://call", "https://",
                     "https://user:pass@example.com", "https://example.com:0", "https://example.com:65536",
                     "https://exa mple.com", "https://example.com/\nfoo", "https://example.com/\\evil",
                     "https://example.com/\u{0}", "https://example.com/" + String(repeating: "a", count: 4096)] {
            XCTAssertNil(validatedWebLink(link), link)
        }
    }

    func testMediaCommandsCannotContainRemoteCode() {
        XCTAssertEqual(mediaCommand("toggle", value: ""), "playpause")
        XCTAssertEqual(mediaCommand("pause", value: ""), "pause")
        XCTAssertEqual(mediaCommand("next", value: ""), "next track")
        XCTAssertEqual(mediaCommand("previous", value: ""), "previous track")
        XCTAssertEqual(mediaCommand("seek", value: "123"), "set player position to 123")
        XCTAssertEqual(mediaCommand("volume", value: "100"), "set sound volume to 100")
        XCTAssertNil(mediaCommand("volume", value: "101"))
        XCTAssertNil(mediaCommand("seek", value: "-1"))
        XCTAssertNil(mediaCommand("seek", value: "604801"))
        XCTAssertNil(mediaCommand("seek", value: "1\n do shell script \"open /tmp\""))
        XCTAssertNil(mediaCommand("do shell script", value: "anything"))
    }

    func testArtworkDescriptorBoundsAndHexValidation() {
        XCTAssertEqual(mediaArtworkData("«data PNGf89504E47»"), Data([0x89, 0x50, 0x4e, 0x47]))
        XCTAssertNil(mediaArtworkData("not data"))
        XCTAssertNil(mediaArtworkData("«data PNGfXX»"))
        XCTAssertNil(mediaArtworkData("«data PNGf123»"))
        XCTAssertNil(mediaArtworkData("«data PNGf»"))
        XCTAssertNil(mediaArtworkData("«data PNGf" + String(repeating: "F", count: 2_100_000) + "»"))
    }

    @MainActor func testDisabledLinksRejectWithoutOpeningOrRetaining() {
        let actions = QuickActions()
        var replies: [[String: Any]] = []
        actions.send = { _, payload in replies.append(payload); return true }
        actions.receive("quick.request", payload: ["version": 1, "requestId": UUID().uuidString,
            "feature": "links", "action": "offer", "value": "https://example.com"])
        XCTAssertNil(actions.receivedLink)
        XCTAssertEqual(replies.first?["accepted"] as? Bool, false)
        actions.reset()
        XCTAssertNil(actions.status)
    }

    func testNewCapabilitiesFailClosedForOldPeers() {
        XCTAssertFalse(featureEnabledByLegacyPeer(.links))
        XCTAssertFalse(featureEnabledByLegacyPeer(.media))
    }

    // MARK: Books Handoff Alpha

    func testBookHandoffValidation() {
        let full = validatedBookHandoff(#"{"version":1,"title":"Alice","chapter":"CHAPTER X.","page":64,"pages":91,"quote":"Will you"}"#)
        XCTAssertEqual(full, BookHandoff(title: "Alice", chapter: "CHAPTER X.", page: 64, pages: 91, quote: "Will you"))
        XCTAssertEqual(validatedBookHandoff(#"{"version":1,"quote":"only a quote"}"#)?.quote, "only a quote")
        XCTAssertNil(validatedBookHandoff(#"{"version":1,"chapter":"no title or quote","page":3}"#))
        XCTAssertNil(validatedBookHandoff(#"{"version":2,"title":"Alice"}"#))
        XCTAssertNil(validatedBookHandoff("https://example.com"))
        XCTAssertNil(validatedBookHandoff(#"{"version":1,"title":""# + String(repeating: "x", count: 5000) + #""}"#))
        let impossible = validatedBookHandoff(#"{"version":1,"title":"Alice","page":95,"pages":91}"#)
        XCTAssertNil(impossible?.page); XCTAssertNil(impossible?.pages)
        XCTAssertEqual(validatedBookHandoff(#"{"version":1,"title":"A\u0007lice"}"#)?.title, "Alice")
    }

    @MainActor private func bookRequest(_ value: String, id: String = UUID().uuidString) -> [String: Any] {
        ["version": 1, "requestId": id, "feature": "links", "action": "book", "value": value]
    }

    @MainActor func testBookIsQueuedOnceAndDeclinedWhilePending() {
        let actions = QuickActions()
        actions.available = { $0 == .links }
        var replies: [[String: Any]] = []
        actions.send = { _, payload in replies.append(payload); return true }
        actions.receive("quick.request", payload: bookRequest(#"{"version":1,"title":"Alice","page":64,"pages":91}"#))
        XCTAssertEqual(actions.receivedBook?.title, "Alice")
        XCTAssertEqual(replies.last?["accepted"] as? Bool, true)
        actions.receive("quick.request", payload: bookRequest(#"{"version":1,"title":"Second"}"#))
        XCTAssertEqual(actions.receivedBook?.title, "Alice")
        XCTAssertEqual(replies.last?["accepted"] as? Bool, false)
        // A pending book does not block web links (separate slot) and vice versa.
        actions.receive("quick.request", payload: ["version": 1, "requestId": UUID().uuidString,
            "feature": "links", "action": "offer", "value": "https://example.com"])
        XCTAssertEqual(actions.receivedLink, "https://example.com")
        actions.reset()
        XCTAssertNil(actions.receivedBook); XCTAssertNil(actions.receivedLink)
    }

    @MainActor func testBookRejectedWhenLinksAreOffOrPayloadInvalid() {
        let actions = QuickActions()
        var replies: [[String: Any]] = []
        actions.send = { _, payload in replies.append(payload); return true }
        actions.receive("quick.request", payload: bookRequest(#"{"version":1,"title":"Alice"}"#))
        XCTAssertNil(actions.receivedBook)
        XCTAssertEqual(replies.last?["accepted"] as? Bool, false)
        actions.available = { $0 == .links }
        actions.receive("quick.request", payload: bookRequest("not json"))
        XCTAssertNil(actions.receivedBook)
        XCTAssertEqual(replies.last?["accepted"] as? Bool, false)
    }

    @MainActor func testContinueInBooksCopiesQuoteAndClearsOnlyWhenBooksOpened() {
        let actions = QuickActions()
        actions.available = { $0 == .links }
        actions.send = { _, _ in true }
        var copied: [String] = []
        actions.copyText = { copied.append($0) }
        actions.openBooks = { false }
        actions.receive("quick.request", payload: bookRequest(#"{"version":1,"title":"Alice","quote":"Will you"}"#))
        actions.continueInBooks()
        XCTAssertNotNil(actions.receivedBook)
        XCTAssertEqual(actions.status, "Could not open Books")
        actions.openBooks = { true }
        actions.continueInBooks()
        XCTAssertNil(actions.receivedBook)
        XCTAssertEqual(copied, ["Will you", "Will you"])
    }

    // MARK: Find in Books / Google Play Books

    func testBooksAutomationQueryHelpers() {
        XCTAssertEqual(BooksAutomation.typographic("It isn't \"mine\""), "It isn’t “mine”")
        XCTAssertEqual(BooksAutomation.longestSegment("“It isn’t mine,” said the Hatter. “I keep them to sell”"), "I keep them to sell")
        XCTAssertEqual(BooksAutomation.norm("Výkony  vznešeného\nrytíře."), "výkony vznešeného rytíře")
        XCTAssertEqual(BooksAutomation.parseResultLabel("CHAPTER XI. Who Stole the Tarts?, 155, “It isn’t mine,” said the Hatter.")?.chapter,
                       "CHAPTER XI. Who Stole the Tarts?")
        XCTAssertEqual(BooksAutomation.parseResultLabel("5, výkony vznešeného rytíře.")?.page, 5)
        XCTAssertNil(BooksAutomation.parseResultLabel("5, výkony vznešeného rytíře.")?.chapter)
        XCTAssertNil(BooksAutomation.parseResultLabel("Vymazat text"))
        XCTAssertNil(BooksAutomation.parseResultLabel("Nalezen 1 výsledek"))
        XCTAssertEqual(BooksAutomation.chapterName("CHAPTER X. The Lobster Quadrille"), "The Lobster Quadrille")
        XCTAssertEqual(BooksAutomation.chapterName("Down the Rabbit-Hole"), "Down the Rabbit-Hole")
        XCTAssertNil(BooksAutomation.chapterName("CHAPTER XII."))
        XCTAssertEqual(BooksAutomation.snippet("CHAPTER I. Down the Rabbit-Hole, 8, Down the Rabbit-Hole"), "Down the Rabbit-Hole")
    }

    func testBookHandoffKeepsTheSourceApp() {
        let book = validatedBookHandoff(#"{"version":1,"title":"Alice","app":"com.google.android.apps.books"}"#)
        XCTAssertEqual(book?.app, "com.google.android.apps.books")
        XCTAssertTrue(book?.fromGooglePlayBooks == true)
        XCTAssertFalse(validatedBookHandoff(#"{"version":1,"title":"Alice","app":"com.amazon.kindle"}"#)?.fromGooglePlayBooks == true)
    }

    @MainActor private func actionsWithBook(_ json: String = #"{"version":1,"title":"Alice","quote":"Will you","app":"com.google.android.apps.books"}"#) -> QuickActions {
        let actions = QuickActions()
        actions.available = { $0 == .links }
        actions.send = { _, _ in true }
        actions.copyText = { _ in }
        actions.receive("quick.request", payload: ["version": 1, "requestId": UUID().uuidString,
            "feature": "links", "action": "book", "value": json])
        return actions
    }

    @MainActor private func waitUntilIdle(_ actions: QuickActions) {
        let end = Date().addingTimeInterval(3)
        while actions.findingInBooks && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        XCTAssertFalse(actions.findingInBooks)
    }

    @MainActor func testFindInBooksAsksForAccessibilityFirst() {
        let actions = actionsWithBook()
        var ran = false
        actions.accessibilityTrusted = { _ in false }
        actions.runBooksAutomation = { _, _ in ran = true; return .completed }
        actions.findInBooks()
        XCTAssertFalse(ran)
        XCTAssertTrue(actions.status?.contains("Accessibility") == true)
        XCTAssertNotNil(actions.receivedBook)
    }

    @MainActor func testFindInBooksOutcomes() {
        var copied: [String] = []
        for (result, keepsCard, copies, text) in [
            (BooksAutomationResult.completed, false, false, "Found in Books"),
            (.ambiguous(2), true, true, "appears 2×"),
            (.notFound, true, false, "did not find"),
            (.abortedFocus("x"), true, false, "another app"),
            (.failed("x"), true, true, "Could not finish"),
        ] {
            copied = []
            let actions = actionsWithBook()
            actions.copyText = { copied.append($0) }
            actions.accessibilityTrusted = { _ in true }
            actions.runBooksAutomation = { book, status in
                XCTAssertEqual(book.quote, "Will you"); status("Searching…"); return result
            }
            actions.findInBooks()
            XCTAssertTrue(actions.findingInBooks)
            waitUntilIdle(actions)
            XCTAssertEqual(actions.receivedBook != nil, keepsCard, "\(result)")
            XCTAssertEqual(!copied.isEmpty, copies, "\(result)")
            XCTAssertTrue(actions.status?.contains(text) == true, "\(result): \(actions.status ?? "nil")")
        }
    }

    @MainActor func testOpenInGooglePlayBooks() {
        let actions = actionsWithBook()
        var opened: [URL] = []
        actions.openURL = { opened.append($0); return true }
        actions.openInGooglePlayBooks()
        XCTAssertEqual(opened, [URL(string: "https://play.google.com/books")!])
        XCTAssertNotNil(actions.receivedBook)
    }
}
