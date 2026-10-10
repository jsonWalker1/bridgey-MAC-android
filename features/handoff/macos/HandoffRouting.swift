import Foundation

// HANDOFF PER PEER (MD-7)
//
// Handoff and the Web links / book cards ride `quick.request` / `quick.result` (feature "links").
// The same kinds also carry feature "media", which is not migrated and stays with the routed peer.
// The wire format is unchanged: the destination is the session a message is sent on, the sender
// is the authenticated session it arrived on.

/// Which quick-action messages a session may deliver: "links" from any connected peer, "media"
/// only from the routed peer (media is still single-peer), anything else never.
enum QuickPayloadGate {
    static func accepts(feature: String?, fromRoutedPeer: Bool) -> Bool {
        switch feature {
        case "links": true
        case "media": fromRoutedPeer
        default: false
        }
    }
}

/// A received handoff card, kept per sending peer until the user opens or dismisses it - also
/// after that peer disconnects (opening a link or a book needs no connection).
struct HandoffCard: Identifiable, Equatable {
    enum Content: Equatable {
        case link(String)
        case book(BookHandoff)
    }

    let deviceID: String
    let peerName: String
    let content: Content
    let receivedAt: Date

    var isLink: Bool { if case .link = content { true } else { false } }
    /// One link card and one book card per peer.
    var id: String { "\(deviceID)|\(isLink ? "link" : "book")" }
}

/// The cards from all peers. Each peer has at most one pending link and one pending book; a
/// second one from the same peer is declined until the first is opened or dismissed, while other
/// peers are unaffected.
struct HandoffCards: Equatable {
    private(set) var cards: [HandoffCard] = []

    func card(_ id: String) -> HandoffCard? { cards.first { $0.id == id } }

    /// False (and unchanged) when this peer already has a card of this kind.
    mutating func add(_ card: HandoffCard) -> Bool {
        guard self.card(card.id) == nil else { return false }
        cards.append(card)
        return true
    }

    @discardableResult
    mutating func remove(_ id: String) -> HandoffCard? {
        guard let index = cards.firstIndex(where: { $0.id == id }) else { return nil }
        return cards.remove(at: index)
    }

    /// Drops the cards of peers that are no longer allowed (local grant turned off).
    mutating func keep(where allowed: (String) -> Bool) {
        cards.removeAll { !allowed($0.deviceID) }
    }
}

/// Where a target-less handoff goes: the only eligible peer, a choice among several, or nowhere.
/// Never the routed peer by default and never a fallback.
enum HandoffTarget: Equatable {
    case send(String)
    case choose([String])
    case none

    static func resolve(eligible: [String]) -> HandoffTarget {
        switch eligible.count {
        case 0: .none
        case 1: .send(eligible[0])
        default: .choose(eligible)
        }
    }
}
