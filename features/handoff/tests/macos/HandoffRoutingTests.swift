import XCTest
@testable import BridgeyMac

/// MD-7 handoff per peer: links from any connected peer, media only from the routed one, cards
/// per sender, explicit targets with no fallback.
private let a = "10000000-0000-4000-8000-00000000000a"
private let b = "20000000-0000-4000-8000-00000000000b"

@MainActor
final class HandoffRoutingTests: XCTestCase {

    private func actions(receivable: Set<String>? = nil) -> (QuickActions, [(String, [String: Any])]) {
        let actions = QuickActions()
        actions.receivable = { receivable?.contains($0) ?? true }
        actions.sendable = { _ in true }
        actions.granted = { _ in true }
        actions.peerName = { $0 == a ? "S23 Ultra" : "Pixel" }
        return (actions, [])
    }

    private func link(_ url: String, id: String = UUID().uuidString) -> [String: Any] {
        ["version": 1, "requestId": id, "feature": "links", "action": "offer", "value": url]
    }

    func testLinksComeFromAnyPeerMediaOnlyFromTheRoutedOne() {
        XCTAssertTrue(QuickPayloadGate.accepts(feature: "links", fromRoutedPeer: false))
        XCTAssertTrue(QuickPayloadGate.accepts(feature: "links", fromRoutedPeer: true))
        XCTAssertFalse(QuickPayloadGate.accepts(feature: "media", fromRoutedPeer: false), "a non-routed peer never controls media")
        XCTAssertTrue(QuickPayloadGate.accepts(feature: "media", fromRoutedPeer: true))
        XCTAssertFalse(QuickPayloadGate.accepts(feature: nil, fromRoutedPeer: true))
        XCTAssertFalse(QuickPayloadGate.accepts(feature: "files", fromRoutedPeer: true))
    }

    func testCardsArePerSenderAndNameIt() {
        let (actions, _) = actions()
        var replies: [(String, Bool)] = []
        actions.send = { _, payload, deviceID in replies.append((deviceID, payload["accepted"] as? Bool ?? false)); return true }
        actions.receive("quick.request", payload: link("https://a.example"), from: a)
        actions.receive("quick.request", payload: link("https://b.example"), from: b)
        XCTAssertEqual(actions.cards.map(\.peerName), ["S23 Ultra", "Pixel"])
        XCTAssertEqual(actions.cards.map(\.deviceID), [a, b], "B's link is not declined because A has one")
        // A second link from the same peer waits for the first; the result goes back to that peer only.
        actions.receive("quick.request", payload: link("https://a2.example"), from: a)
        XCTAssertEqual(replies.map(\.0), [a, b, a])
        XCTAssertEqual(replies.map(\.1), [true, true, false])
        actions.dismiss("\(a)|link")
        XCTAssertEqual(actions.cards.map(\.deviceID), [b], "dismissing A's card leaves B's")
    }

    func testWebAndBookCardsOfOnePeerAreSeparate() {
        let (actions, _) = actions()
        actions.send = { _, _, _ in true }
        actions.receive("quick.request", payload: link("https://a.example"), from: a)
        actions.receive("quick.request", payload: ["version": 1, "requestId": UUID().uuidString, "feature": "links",
                                                   "action": "book", "value": #"{"version":1,"title":"Alice"}"#], from: a)
        XCTAssertEqual(Set(actions.cards.map(\.id)), ["\(a)|link", "\(a)|book"])
    }

    func testAPeerThatMayNotHandOffIsDeclined() {
        let (actions, _) = actions(receivable: [a])
        var accepted: [Bool] = []
        actions.send = { _, payload, _ in accepted.append(payload["accepted"] as? Bool ?? false); return true }
        actions.receive("quick.request", payload: link("https://b.example"), from: b)
        XCTAssertTrue(actions.cards.isEmpty)
        XCTAssertEqual(accepted, [false])
    }

    func testCardsStayAfterTheSenderDisconnects() {
        let (actions, _) = actions()
        actions.send = { _, _, _ in true }
        actions.receive("quick.request", payload: link("https://a.example"), from: a)
        actions.deviceEnded(a)
        XCTAssertEqual(actions.cards.count, 1, "D2: a received card is kept until opened or dismissed")
        actions.granted = { $0 != a }
        actions.policyChanged()
        XCTAssertTrue(actions.cards.isEmpty, "turning the local grant off for A drops A's cards")
    }

    func testALinkToThePhoneGoesToItsPeerAndOnlyThatPeerConfirmsIt() {
        let (actions, _) = actions()
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString("https://example.com/page", forType: .string)
        var sent: [(String, String)] = []
        actions.send = { kind, payload, deviceID in sent.append((deviceID, payload["requestId"] as? String ?? "")); return kind == "quick.request" }
        actions.sendClipboardLink(to: a)
        XCTAssertEqual(sent.map(\.0), [a])
        let id = sent[0].1
        // B answering with A's request id does not complete A's link.
        actions.receive("quick.result", payload: ["version": 1, "requestId": id, "feature": "links", "accepted": true], from: b)
        XCTAssertEqual(actions.status, "Sending link to S23 Ultra…")
        actions.receive("quick.result", payload: ["version": 1, "requestId": id, "feature": "links", "accepted": true], from: a)
        XCTAssertTrue(actions.status?.hasPrefix("Link delivered to S23 Ultra") == true)
    }

    func testADisconnectedTargetEndsItsLinkWithoutFallback() {
        let (actions, _) = actions()
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString("https://example.com/page", forType: .string)
        var targets: [String] = []
        actions.send = { _, _, deviceID in targets.append(deviceID); return deviceID != a }
        actions.sendClipboardLink(to: a)
        XCTAssertEqual(targets, [a], "not sent to B instead")
        XCTAssertEqual(actions.status, "S23 Ultra is not connected — link not sent")
    }

    func testARevokedGrantDuringFindInBooksIsAppliedAfterwardsAndBlocksTheBook() {
        let (actions, _) = actions()
        actions.send = { _, _, _ in true }
        actions.copyText = { _ in }
        actions.accessibilityTrusted = { _ in true }
        actions.receive("quick.request", payload: ["version": 1, "requestId": UUID().uuidString, "feature": "links",
                                                   "action": "book", "value": #"{"version":1,"title":"Alice","quote":"Will you"}"#], from: a)
        actions.receive("quick.request", payload: link("https://b.example"), from: b)
        let gate = DispatchSemaphore(value: 0)
        actions.runBooksAutomation = { _, _ in gate.wait(); return .notFound }
        actions.findInBooks("\(a)|book")
        actions.granted = { $0 != a }
        actions.policyChanged() // while Find in Books runs: deferred
        XCTAssertEqual(actions.cards.count, 2)
        var opened = false
        actions.openBooks = { opened = true; return true }
        actions.continueInBooks("\(a)|book")
        XCTAssertFalse(opened, "a revoked peer's book card cannot be used")
        gate.signal()
        let end = Date().addingTimeInterval(3)
        while actions.findingInBooks && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        XCTAssertEqual(actions.cards.map(\.deviceID), [b], "the deferred grant change is applied when Find finishes")
    }

    func testTargetResolution() {
        XCTAssertEqual(HandoffTarget.resolve(eligible: []), .none)
        XCTAssertEqual(HandoffTarget.resolve(eligible: [a]), .send(a))
        XCTAssertEqual(HandoffTarget.resolve(eligible: [a, b]), .choose([a, b]))
    }
}
