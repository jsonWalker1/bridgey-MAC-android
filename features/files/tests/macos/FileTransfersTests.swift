import XCTest
@testable import BridgeyMac

/// MD-6 files: every transfer belongs to one peer and is identified by (deviceId, transferId).
final class FileTransfersTests: XCTestCase {
    private let a = "10000000-0000-4000-8000-00000000000a"
    private let b = "20000000-0000-4000-8000-00000000000b"
    private let x = "30000000-0000-4000-8000-0000000000aa"
    private let y = "40000000-0000-4000-8000-0000000000bb"

    private final class Transfer { var acknowledged: Int64 = -1; var cancelled = false }

    // MARK: - Targeting

    func testTheSelectedPeerIsTheTargetWhateverIsRouted() {
        // Routing is not an input at all: the selected peer wins when it is eligible.
        XCTAssertEqual(FileTransferTarget.initial(selected: a, eligible: [a, b]), a)
        XCTAssertEqual(FileTransferTarget.initial(selected: b, eligible: [a, b]), b)
    }

    func testOneEligiblePeerIsChosenAutomatically() {
        XCTAssertEqual(FileTransferTarget.initial(selected: nil, eligible: [a]), a)
        XCTAssertEqual(FileTransferTarget.initial(selected: b, eligible: [a]), a, "B is not eligible, A is the only choice")
    }

    func testNoTargetMeansNoSendAndNoFallback() {
        XCTAssertNil(FileTransferTarget.initial(selected: nil, eligible: []))
        XCTAssertNil(FileTransferTarget.initial(selected: a, eligible: []), "a disconnected selection falls back to nothing")
        XCTAssertNil(FileTransferTarget.initial(selected: nil, eligible: [a, b]), "several peers: the user must choose")
    }

    func testAnOutgoingTransferGoesOnlyToItsPeersSession() {
        let manager = PeerSessionManager<FakeSession>(localDeviceID: "50000000-0000-4000-8000-000000000000")
        for id in [a, b] {
            let session = FakeSession()
            manager.addPending(session, initiatedLocally: false, expectedDeviceID: nil)
            _ = manager.identify(session, as: id)
            manager.markConnected(session)
        }
        let key = FileTransferKey(deviceID: a, transferID: x)
        XCTAssertTrue(manager.deliver(to: key.deviceID) { $0.received.append("files.offer"); return true })
        XCTAssertEqual(manager.connectedSession(for: a)?.received, ["files.offer"])
        XCTAssertEqual(manager.connectedSession(for: b)?.received, [], "the other peer receives nothing")
    }

    private final class FakeSession { var received: [String] = [] }

    // MARK: - Per-peer state

    func testSimultaneousTransfersToTwoPeersAreIndependent() {
        var outgoing = FileTransferTable<Transfer>()
        let toA = Transfer(), toB = Transfer()
        XCTAssertTrue(outgoing.insert(toA, for: FileTransferKey(deviceID: a, transferID: x)))
        XCTAssertTrue(outgoing.insert(toB, for: FileTransferKey(deviceID: b, transferID: y)))
        outgoing[FileTransferKey(deviceID: a, transferID: x)]?.acknowledged = 5
        XCTAssertEqual(toA.acknowledged, 5)
        XCTAssertEqual(toB.acknowledged, -1, "progress of A never touches B")
        XCTAssertTrue(outgoing.remove(FileTransferKey(deviceID: a, transferID: x)) === toA)
        XCTAssertTrue(outgoing[FileTransferKey(deviceID: b, transferID: y)] === toB, "A completing leaves B running")
    }

    func testAnAcknowledgementFromOnePeerNeverCompletesAnotherPeersTransfer() {
        var outgoing = FileTransferTable<Transfer>()
        let toB = Transfer()
        outgoing.insert(toB, for: FileTransferKey(deviceID: b, transferID: x))
        // files.complete.ack / files.accept / files.cancel from A carrying B's transfer id:
        XCTAssertNil(outgoing.remove(FileTransferKey(deviceID: a, transferID: x)))
        XCTAssertTrue(outgoing[FileTransferKey(deviceID: b, transferID: x)] === toB)
    }

    func testTheSameTransferIDFromTwoPeersStaysTwoTransfers() {
        var incoming = FileTransferTable<Transfer>()
        let fromA = Transfer(), fromB = Transfer()
        XCTAssertTrue(incoming.insert(fromA, for: FileTransferKey(deviceID: a, transferID: x)))
        XCTAssertTrue(incoming.insert(fromB, for: FileTransferKey(deviceID: b, transferID: x)), "no collision across peers")
        XCTAssertFalse(incoming.insert(Transfer(), for: FileTransferKey(deviceID: a, transferID: x)), "a duplicate offer of the same peer is refused")
        incoming.remove(FileTransferKey(deviceID: a, transferID: x))?.cancelled = true
        XCTAssertTrue(fromA.cancelled)
        XCTAssertFalse(fromB.cancelled)
    }

    func testDisconnectingOnePeerEndsOnlyItsTransfers() {
        var outgoing = FileTransferTable<Transfer>()
        outgoing.insert(Transfer(), for: FileTransferKey(deviceID: a, transferID: x))
        outgoing.insert(Transfer(), for: FileTransferKey(deviceID: a, transferID: y))
        let toB = Transfer()
        outgoing.insert(toB, for: FileTransferKey(deviceID: b, transferID: x))
        let ended = outgoing.removeAll(deviceID: a)
        XCTAssertEqual(Set(ended.map(\.key.transferID)), [x, y])
        XCTAssertEqual(outgoing.keys, [FileTransferKey(deviceID: b, transferID: x)], "B's transfer continues")
    }

    func testAReconnectedPeerStartsWithoutStaleTransfers() {
        var incoming = FileTransferTable<Transfer>()
        var cancelled = CancelledFileTransfers()
        let old = FileTransferKey(deviceID: a, transferID: x)
        incoming.insert(Transfer(), for: old)
        for ended in incoming.removeAll(deviceID: a) { cancelled.insert(ended.key) }
        XCTAssertFalse(incoming.contains(old), "the interrupted transfer is gone, not resurrected")
        XCTAssertTrue(cancelled.contains(old), "late messages of the old transfer are ignored")
        let new = FileTransferKey(deviceID: a, transferID: y)
        XCTAssertTrue(incoming.insert(Transfer(), for: new))
        XCTAssertFalse(cancelled.contains(new))
    }

    func testOwnershipIsFixedWhenTheTransferStarts() {
        // The key is captured at the start; a later selection change is not part of it.
        var selected = a
        let key = FileTransferKey(deviceID: FileTransferTarget.initial(selected: selected, eligible: [a, b])!, transferID: x)
        selected = b
        XCTAssertEqual(key.deviceID, a)
        XCTAssertNotEqual(key.deviceID, selected)
    }

    func testCancelledTransfersArePerPeerAndBounded() {
        var cancelled = CancelledFileTransfers()
        cancelled.insert(FileTransferKey(deviceID: a, transferID: x))
        XCTAssertFalse(cancelled.contains(FileTransferKey(deviceID: b, transferID: x)))
        for index in 0..<(CancelledFileTransfers.capacity + 5) {
            cancelled.insert(FileTransferKey(deviceID: b, transferID: "\(index)"))
        }
        XCTAssertEqual(cancelled.order.count, CancelledFileTransfers.capacity)
        XCTAssertFalse(cancelled.contains(FileTransferKey(deviceID: a, transferID: x)), "oldest dropped first")
    }

    func testRowIDRoundTrips() {
        let key = FileTransferKey(deviceID: a, transferID: x)
        XCTAssertEqual(FileTransferKey(rowID: key.rowID), key)
        XCTAssertNil(FileTransferKey(rowID: "no-separator"))
        XCTAssertNil(FileTransferKey(rowID: "|\(x)"))
        // A peer's deviceId is not validated: the split is at the last "|" (transfer ids are UUIDs).
        let odd = FileTransferKey(deviceID: "a|b", transferID: x)
        XCTAssertEqual(FileTransferKey(rowID: odd.rowID), odd)
    }

    // MARK: - Mac <-> Mac policy

    func testMacToMacNeedsTheLocalOptIn() {
        XCTAssertFalse(FileTransferPolicy.allows(local: .macos, peer: .macos, macToMacEnabled: false))
        XCTAssertTrue(FileTransferPolicy.allows(local: .macos, peer: .macos, macToMacEnabled: true))
        XCTAssertTrue(FileTransferPolicy.allows(local: .macos, peer: .android, macToMacEnabled: false))
        XCTAssertTrue(FileTransferPolicy.allows(local: .android, peer: .macos, macToMacEnabled: false))
    }

    // MARK: - Chunk acknowledgements

    func testChunkAcknowledgementsOnlyMoveForwardToSentChunks() {
        XCTAssertEqual(FileChunkAcknowledgement.advance(current: -1, acknowledged: 3, highestSent: 10), 3)
        XCTAssertEqual(FileChunkAcknowledgement.advance(current: 7, acknowledged: 3, highestSent: 10), 7, "late/out-of-order")
        XCTAssertEqual(FileChunkAcknowledgement.advance(current: 7, acknowledged: 7, highestSent: 10), 7, "duplicate")
        XCTAssertEqual(FileChunkAcknowledgement.advance(current: 7, acknowledged: 11, highestSent: 10), 7, "ahead of the data")
        XCTAssertEqual(FileChunkAcknowledgement.advance(current: 7, acknowledged: -2, highestSent: 10), 7)
    }

    // MARK: - Attribution

    func testStatusTextNamesThePeer() {
        XCTAssertEqual(FileTransferText.sending("photo.jpg", to: "MacBook Air – pracovní"), "Sending photo.jpg → MacBook Air – pracovní…")
        XCTAssertTrue(FileTransferText.receiving("document.pdf", from: "Galaxy S23 Ultra", progress: "50%").hasPrefix("Receiving document.pdf ← Galaxy S23 Ultra"))
        XCTAssertEqual(FileTransferText.turnedOff(on: "M4"), "File transfer is turned off on M4")
    }
}
