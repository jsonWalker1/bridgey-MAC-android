import XCTest
@testable import BridgeyMac

/// MD-3 Find Device isolation: ringing state per device, on both sides of the request.
final class FindDeviceStateTests: XCTestCase {
    private let a = "10000000-0000-4000-8000-00000000000a"
    private let b = "20000000-0000-4000-8000-00000000000b"

    func testStartingAndStoppingFindIsPerDevice() {
        var find = FindDeviceState()
        find.remoteReported(a, ringing: true)
        XCTAssertTrue(find.isRinging(a))
        XCTAssertFalse(find.isRinging(b))

        find.remoteReported(b, ringing: true)
        XCTAssertTrue(find.isRinging(a))
        XCTAssertTrue(find.isRinging(b))

        find.remoteReported(a, ringing: false) // A confirms it stopped
        XCTAssertFalse(find.isRinging(a))
        XCTAssertTrue(find.isRinging(b))
    }

    func testDisconnectOfALeavesBUntouchedAndReconnectInheritsNothing() {
        var find = FindDeviceState()
        find.remoteReported(a, ringing: true)
        find.remoteReported(b, ringing: true)
        find.localRingRequested(by: b)
        let before = find

        find.deviceEnded(a)
        XCTAssertFalse(find.isRinging(a))
        XCTAssertTrue(find.isRinging(b))
        XCTAssertEqual(find.localRequesters, before.localRequesters, "B's request to ring this device stays")

        // A reconnects: nothing about A is restored, and it does not take over B's state.
        XCTAssertFalse(find.isRinging(a))
        XCTAssertFalse(find.localRequesters.contains(a))
        XCTAssertTrue(find.isRinging(b))
    }

    func testThisDeviceRingsWhileAnyRequesterRemains() {
        var find = FindDeviceState()
        XCTAssertTrue(find.localRingRequested(by: a), "first request starts the sound")
        XCTAssertFalse(find.localRingRequested(by: b), "second request keeps it")
        XCTAssertFalse(find.peerStopped(a), "B still wants it to ring")
        XCTAssertTrue(find.isRingingLocally)
        XCTAssertTrue(find.peerStopped(b), "last requester stops the sound")
        XCTAssertFalse(find.isRingingLocally)
    }

    func testDisconnectOfTheOnlyRequesterStopsTheSoundButNotAnothersRequest() {
        var find = FindDeviceState()
        find.localRingRequested(by: a)
        find.localRingRequested(by: b)
        XCTAssertFalse(find.deviceEnded(a))
        XCTAssertTrue(find.isRingingLocally)
        XCTAssertTrue(find.deviceEnded(b))
        XCTAssertFalse(find.isRingingLocally)
    }

    func testAPeerStoppingUsDoesNotChangeWhatWeKnowAboutItsRinging() {
        var find = FindDeviceState()
        find.remoteReported(a, ringing: true)
        find.localRingRequested(by: a)
        XCTAssertTrue(find.peerStopped(a), "A no longer asks us to ring")
        XCTAssertTrue(find.isRinging(a), "only A's own find.stopped says A stopped ringing")
        find.remoteReported(a, ringing: false)
        XCTAssertFalse(find.isRinging(a))
    }

    func testSilencingThisDeviceReportsEveryRequester() {
        var find = FindDeviceState()
        find.localRingRequested(by: a)
        find.localRingRequested(by: b)
        find.remoteReported(b, ringing: true)
        XCTAssertEqual(find.stopLocalRinging(), [a, b])
        XCTAssertFalse(find.isRingingLocally)
        XCTAssertTrue(find.isRinging(b), "silencing this device does not stop a peer we made ring")
    }

    func testSingleDeviceBehaviourIsUnchanged() {
        var find = FindDeviceState()
        find.remoteReported(a, ringing: true)
        XCTAssertTrue(find.isRinging(a))
        find.remoteReported(a, ringing: false)
        XCTAssertFalse(find.isRinging(a))
        XCTAssertTrue(find.localRingRequested(by: a))
        XCTAssertTrue(find.peerStopped(a))
        XCTAssertFalse(find.isRingingLocally)
    }
}
