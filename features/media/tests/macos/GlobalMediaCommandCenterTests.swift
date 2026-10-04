import AppKit
import XCTest
@testable import BridgeyMac

/// Constructs real NSEvent.systemDefined hardware-media-key events (the same technique the OS uses
/// to deliver Play/Pause/Next/Previous key presses) to verify GlobalMediaCommandCenter's routing
/// end to end, independent of MPRemoteCommandCenter/Now Playing election - this is the mechanism
/// the Definition of Done ("no Mac-native media app needed") actually depends on.
final class GlobalMediaCommandCenterTests: XCTestCase {
    private func systemDefinedEvent(keyCode: Int32, isKeyDown: Bool) -> NSEvent {
        let keyState = isKeyDown ? 0x0A : 0x0B
        let data1 = (Int(keyCode) << 16) | (keyState << 8)
        return NSEvent.otherEvent(
            with: .systemDefined, location: .zero, modifierFlags: [], timestamp: 0,
            windowNumber: 0, context: nil, subtype: 8, data1: data1, data2: -1
        )!
    }

    @MainActor
    private func makeCenter() -> (GlobalMediaCommandCenter, MediaRemoteController, MediaController) {
        let mediaRemote = MediaRemoteController()
        mediaRemote.allowed = { true }
        let mediaController = MediaController()
        mediaController.allowed = { true }
        let center = GlobalMediaCommandCenter(mediaRemote: mediaRemote, mediaController: mediaController)
        return (center, mediaRemote, mediaController)
    }

    @MainActor
    func testPlayPauseKeyRoutesToAndroidWhenNoMacNativePlayerIsLoaded() {
        let (center, mediaRemote, _) = makeCenter()
        mediaRemote.receive(MediaRemoteStatePayload(
            version: 1, generation: 3, sequence: 1, hasSession: true, title: "YouTube video",
            capabilities: ["play", "pause"]
        ))
        var sentAction: String?
        mediaRemote.sendAction = { action, _, _ in sentAction = action }

        center.handle(systemDefinedEvent(keyCode: 16, isKeyDown: true))

        XCTAssertEqual(sentAction, "toggle")
    }

    /// Regression: a single physical key press redelivered in quick succession (observed on real
    /// hardware as an immediate play-then-pause flicker, and as a double skipToNext that YouTube
    /// surfaced as a "can't play this content" error) must dispatch exactly once, not twice.
    @MainActor
    func testTheSameKeyDeliveredTwiceInQuickSuccessionDispatchesOnlyOnce() {
        let (center, mediaRemote, _) = makeCenter()
        mediaRemote.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 1, hasSession: true, title: "Video", capabilities: ["next"]
        ))
        var actions: [String] = []
        mediaRemote.sendAction = { action, _, _ in actions.append(action) }

        center.handle(systemDefinedEvent(keyCode: 17, isKeyDown: true))
        center.handle(systemDefinedEvent(keyCode: 17, isKeyDown: true))

        XCTAssertEqual(actions, ["next"])
    }

    @MainActor
    func testNextAndPreviousKeysRouteToAndroidWhenCapabilitySupportsThem() {
        let (center, mediaRemote, _) = makeCenter()
        mediaRemote.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 1, hasSession: true, title: "Video",
            capabilities: ["next", "previous"]
        ))
        var actions: [String] = []
        mediaRemote.sendAction = { action, _, _ in actions.append(action) }

        center.handle(systemDefinedEvent(keyCode: 17, isKeyDown: true))
        center.handle(systemDefinedEvent(keyCode: 18, isKeyDown: true))

        XCTAssertEqual(actions, ["next", "previous"])
    }

    @MainActor
    func testKeyUpEventsAreIgnored() {
        let (center, mediaRemote, _) = makeCenter()
        mediaRemote.receive(MediaRemoteStatePayload(version: 1, generation: 1, sequence: 1, hasSession: true, title: "Video"))
        var called = false
        mediaRemote.sendAction = { _, _, _ in called = true }

        center.handle(systemDefinedEvent(keyCode: 16, isKeyDown: false))

        XCTAssertFalse(called)
    }

    @MainActor
    func testNonMediaSystemDefinedSubtypesAreIgnored() {
        let (center, mediaRemote, _) = makeCenter()
        mediaRemote.receive(MediaRemoteStatePayload(version: 1, generation: 1, sequence: 1, hasSession: true, title: "Video"))
        var called = false
        mediaRemote.sendAction = { _, _, _ in called = true }
        let unrelated = NSEvent.otherEvent(
            with: .systemDefined, location: .zero, modifierFlags: [], timestamp: 0,
            windowNumber: 0, context: nil, subtype: 1, data1: (16 << 16) | (0x0A << 8), data2: -1
        )!

        center.handle(unrelated)

        XCTAssertFalse(called)
    }

    // Mac-native-vs-Android precedence itself is covered by preferMacNativeMediaSource's own tests
    // in MediaRemoteControllerTests.swift - MediaController's snapshot has no test-only setter (it's
    // only ever populated by real osascript execution), so it can't be driven here without a live
    // Music/Spotify process.

    @MainActor
    func testSeekKeyIsIgnoredWhenAndroidSessionDoesNotSupportSeeking() {
        let (center, mediaRemote, _) = makeCenter()
        mediaRemote.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 1, hasSession: true, title: "Video", capabilities: ["play", "pause"]
        ))
        var called = false
        mediaRemote.sendAction = { _, _, _ in called = true }

        center.dispatchSeek(forwardBy: 15)

        XCTAssertFalse(called)
    }

    @MainActor
    func testSeekKeyAdvancesAndroidPositionWhenSeekIsSupported() {
        let (center, mediaRemote, _) = makeCenter()
        mediaRemote.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 1, hasSession: true, title: "Video",
            position: 10_000, duration: 60_000, positionTimestamp: Int64(Date().timeIntervalSince1970 * 1000),
            capabilities: ["seek"]
        ))
        var sentValue: Int64?
        mediaRemote.sendAction = { _, value, _ in sentValue = value }

        center.dispatchSeek(forwardBy: 15)

        XCTAssertEqual(sentValue, 25_000, accuracy: 1_500)
    }

    @MainActor
    func testUnsupportedCommandsDoNothingWhenThereIsNoActiveSessionAnywhere() {
        let (center, mediaRemote, _) = makeCenter()
        var called = false
        mediaRemote.sendAction = { _, _, _ in called = true }

        center.handle(systemDefinedEvent(keyCode: 16, isKeyDown: true))

        XCTAssertFalse(called)
    }
}

private func XCTAssertEqual(_ expression1: Int64?, _ expression2: Int64, accuracy: Int64, file: StaticString = #filePath, line: UInt = #line) {
    guard let value = expression1 else {
        XCTFail("value was nil", file: file, line: line)
        return
    }
    XCTAssertTrue(abs(value - expression2) <= accuracy, "\(value) is not within \(accuracy) of \(expression2)", file: file, line: line)
}
