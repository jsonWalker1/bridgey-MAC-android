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
}
