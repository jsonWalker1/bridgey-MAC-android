import XCTest
@testable import BridgeyMac

/// Finder "Send to Bridgey…": what the user is asked, and that the existing MD-6 transfer path is
/// called with the chosen deviceId only - never another peer.
final class FileSendRequestTests: XCTestCase {
    private let phone = FileSendRequest.Destination(deviceID: "p", name: "S23 Ultra", detail: "Android · Connected")
    private let mac = FileSendRequest.Destination(deviceID: "m", name: "MacBook Air – pracovní", detail: "macOS · Connected")
    private let a = URL(fileURLWithPath: "/tmp/a.pdf")
    private let b = URL(fileURLWithPath: "/tmp/b.jpg")

    private func peer(_ id: String, _ name: String, _ platform: DevicePlatform) -> DeviceDirectoryEntry {
        DeviceDirectoryEntry(deviceID: id, name: name, isTrusted: true, connection: .connected, capabilities: ["files": true],
                             platform: platform, kind: platform == .android ? .phone : .computer, isRouted: false)
    }

    func testMultipleSelectedFilesAreKeptFoldersAndOtherURLsAreNot() {
        let folder = URL(fileURLWithPath: "/tmp/folder", isDirectory: true)
        let web = URL(string: "https://example.com/x")!
        let request = FileSendRequest.partition([a, folder, b, web]) { $0 != folder }
        XCTAssertEqual(request.files, [a, b])
        XCTAssertEqual(request.rejected, [folder, web])
    }

    func testDestinationsAreExactlyTheEligiblePeers() {
        let destinations = FileSendRequest.destinations([peer("p", "S23 Ultra", .android), peer("m", "MacBook Air – pracovní", .macos)])
        XCTAssertEqual(destinations, [phone, mac])
    }

    func testPlan() {
        XCTAssertEqual(FileSendRequest.plan(files: [], destinations: [phone]), .nothingToSend)
        XCTAssertEqual(FileSendRequest.plan(files: [a, b], destinations: []), .noDestination)
        XCTAssertEqual(FileSendRequest.plan(files: [a, b], destinations: [phone]), .confirm(phone), "one peer: one confirmation")
        XCTAssertEqual(FileSendRequest.plan(files: [a], destinations: [phone, mac]), .choose([phone, mac]), "several: explicit choice")
    }

    func testEveryFileGoesThroughTheTransferPathToTheChosenPeerOnly() {
        var calls: [(String, String)] = []
        let outcome = FileSendRequest.send([a, b], to: mac, eligibleNow: ["p", "m"]) { url, deviceID in
            calls.append((url.lastPathComponent, deviceID))
            return true
        }
        XCTAssertEqual(outcome, .sent(deviceID: "m", count: 2, refused: []))
        XCTAssertEqual(calls.map(\.0), ["a.pdf", "b.jpg"])
        XCTAssertEqual(Set(calls.map(\.1)), ["m"], "never the routed or another peer")
    }

    func testADisconnectedOrNoLongerAuthorizedPeerGetsNothingAndNoFallback() {
        var calls = 0
        let outcome = FileSendRequest.send([a, b], to: mac, eligibleNow: ["p"]) { _, _ in calls += 1; return true }
        XCTAssertEqual(outcome, .targetUnavailable(name: "MacBook Air – pracovní"))
        XCTAssertEqual(calls, 0, "nothing is sent, not even to the remaining peer")
    }

    func testFilesTheTransferPathRefusesAreReported() {
        // e.g. the peer's grant was revoked between choosing and sending: sendFile refuses.
        let outcome = FileSendRequest.send([a, b], to: phone, eligibleNow: ["p"]) { url, _ in url != b }
        XCTAssertEqual(outcome, .sent(deviceID: "p", count: 1, refused: ["b.jpg"]))
    }
}
