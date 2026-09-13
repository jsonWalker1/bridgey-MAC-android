import CryptoKit
import XCTest
@testable import BridgeyMac

final class MediaRemoteControllerTests: XCTestCase {

    // MARK: - Ordering guard (newer state wins, older state cannot overwrite it)

    func testOrderingGuardAdvancesThroughIncreasingSequenceAndRejectsAReplay() {
        var guardValue = MediaRemoteOrderingGuard()
        XCTAssertTrue(guardValue.accept(generation: 1, sequence: 5))
        XCTAssertTrue(guardValue.accept(generation: 1, sequence: 6))
        XCTAssertFalse(guardValue.accept(generation: 1, sequence: 5))
    }

    func testOrderingGuardRejectsANonIncreasingSequenceWithinTheSameGeneration() {
        var guardValue = MediaRemoteOrderingGuard()
        XCTAssertTrue(guardValue.accept(generation: 1, sequence: 3))
        XCTAssertFalse(guardValue.accept(generation: 1, sequence: 3))
        XCTAssertFalse(guardValue.accept(generation: 1, sequence: 2))
    }

    func testOrderingGuardAcceptsAHigherGenerationEvenWithALowerSequence() {
        var guardValue = MediaRemoteOrderingGuard()
        XCTAssertTrue(guardValue.accept(generation: 5, sequence: 100))
        XCTAssertTrue(guardValue.accept(generation: 6, sequence: 1))
        XCTAssertFalse(guardValue.accept(generation: 6, sequence: 1))
    }

    func testOrderingGuardRejectsAStaleGeneration() {
        var guardValue = MediaRemoteOrderingGuard()
        XCTAssertTrue(guardValue.accept(generation: 6, sequence: 1))
        XCTAssertFalse(guardValue.accept(generation: 5, sequence: 999))
    }

    func testResetForgetsTheLastAcceptedStateSoAnyNextStateIsAcceptedAgain() {
        var guardValue = MediaRemoteOrderingGuard()
        XCTAssertTrue(guardValue.accept(generation: 9, sequence: 9))
        guardValue.reset()
        // Same (generation, sequence) as before the reset would normally be rejected as stale;
        // after a disconnect/reconnect this must not block Android's next real state.
        XCTAssertTrue(guardValue.accept(generation: 9, sequence: 9))
    }

    // MARK: - Position extrapolation

    func testExtrapolatedPositionHoldsStillWhenNotPlaying() {
        let state = MediaRemoteState(playing: false, position: 5_000, duration: 60_000, playbackSpeed: 1, positionTimestamp: 1_000)
        XCTAssertEqual(extrapolatedPosition(state, nowMs: 10_000), 5_000)
    }

    func testExtrapolatedPositionAdvancesWhilePlaying() {
        let state = MediaRemoteState(playing: true, position: 5_000, duration: 60_000, playbackSpeed: 1, positionTimestamp: 1_000)
        XCTAssertEqual(extrapolatedPosition(state, nowMs: 4_000), 8_000)
    }

    func testExtrapolatedPositionClampsToKnownDuration() {
        let state = MediaRemoteState(playing: true, position: 59_000, duration: 60_000, playbackSpeed: 1, positionTimestamp: 0)
        XCTAssertEqual(extrapolatedPosition(state, nowMs: 60_000), 60_000)
    }

    // MARK: - MediaRemoteController integration (state ordering + artwork dedup + reconnect)

    @MainActor
    private func makeController(allowed: Bool = true) -> MediaRemoteController {
        let controller = MediaRemoteController()
        controller.allowed = { allowed }
        return controller
    }

    @MainActor
    func testStaleStateDoesNotOverwriteTheCurrentlyDisplayedTrack() {
        let controller = makeController()
        controller.receive(MediaRemoteStatePayload(version: 1, generation: 1, sequence: 5, hasSession: true, title: "Track A"))
        controller.receive(MediaRemoteStatePayload(version: 1, generation: 1, sequence: 6, hasSession: true, title: "Track B"))
        controller.receive(MediaRemoteStatePayload(version: 1, generation: 1, sequence: 5, hasSession: true, title: "Track A (stale replay)"))
        XCTAssertEqual(controller.state.title, "Track B")
    }

    @MainActor
    func testNoSessionIsRepresentedWithoutStaleMetadataLingering() {
        let controller = makeController()
        controller.receive(MediaRemoteStatePayload(version: 1, generation: 1, sequence: 1, hasSession: true, title: "Track A", artist: "Artist"))
        controller.receive(MediaRemoteStatePayload(version: 1, generation: 2, sequence: 1, hasSession: false))
        XCTAssertFalse(controller.state.hasSession)
        XCTAssertNil(controller.state.title)
        XCTAssertNil(controller.state.artist)
    }

    @MainActor
    func testDisallowedFeatureIgnoresIncomingState() {
        let controller = makeController(allowed: false)
        controller.receive(MediaRemoteStatePayload(version: 1, generation: 1, sequence: 1, hasSession: true, title: "Track A"))
        XCTAssertFalse(controller.state.hasSession)
    }

    @MainActor
    func testResetClearsStateAndAllowsAFreshStateAfterReconnectEvenAtTheSameSequence() {
        let controller = makeController()
        controller.receive(MediaRemoteStatePayload(version: 1, generation: 3, sequence: 10, hasSession: true, title: "Track A"))
        controller.reset()
        XCTAssertFalse(controller.state.hasSession)
        // Android's counters do not reset on reconnect, so the "fresh" post-reconnect push can
        // legitimately carry the same (generation, sequence) as the last state before disconnect.
        controller.receive(MediaRemoteStatePayload(version: 1, generation: 3, sequence: 10, hasSession: true, title: "Track A"))
        XCTAssertEqual(controller.state.title, "Track A")
    }

    /// A tiny but genuinely decodable PNG, built via AppKit rather than hand-rolled bytes, so
    /// NSImage(data:) actually succeeds and the artwork-cache tests exercise real behavior.
    private func decodableArtworkPNG() -> Data {
        let image = NSImage(size: NSSize(width: 2, height: 2))
        image.lockFocus()
        NSColor.red.setFill()
        NSRect(x: 0, y: 0, width: 2, height: 2).fill()
        image.unlockFocus()
        guard let tiff = image.tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff),
              let png = bitmap.representation(using: .png, properties: [:]) else {
            XCTFail("could not synthesize test artwork")
            return Data()
        }
        return png
    }

    @MainActor
    func testIdenticalArtworkBytesAreNotDecodedASecondTime() {
        let controller = makeController()
        let pixel = decodableArtworkPNG()
        let hash = SHA256.hash(data: pixel).map { String(format: "%02x", $0) }.joined()
        controller.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 1, hasSession: true, title: "Track A",
            artworkHash: hash, artwork: pixel.base64EncodedString()
        ))
        let firstArtwork = controller.state.artwork
        XCTAssertNotNil(firstArtwork)
        controller.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 2, hasSession: true, title: "Track A still playing",
            artworkHash: hash, artwork: nil // Android omits bytes on unchanged artwork; hash alone must still resolve
        ))
        XCTAssertTrue(firstArtwork === controller.state.artwork)
    }

    @MainActor
    func testArtworkBytesThatDoNotMatchTheClaimedHashAreIgnored() {
        let controller = makeController()
        let pixel = decodableArtworkPNG()
        controller.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 1, hasSession: true, title: "Track A",
            artworkHash: "not-the-real-hash", artwork: pixel.base64EncodedString()
        ))
        XCTAssertNil(controller.state.artwork)
    }

    @MainActor
    func testCommandSendsTheCurrentlyKnownGenerationSoAndroidCanDetectStaleness() {
        let controller = makeController()
        controller.receive(MediaRemoteStatePayload(version: 1, generation: 7, sequence: 1, hasSession: true, title: "Track A"))
        var sent: (action: String, value: Int64?, generation: Int64)?
        controller.sendAction = { action, value, generation in sent = (action, value, generation) }
        controller.command("pause")
        XCTAssertEqual(sent?.action, "pause")
        XCTAssertEqual(sent?.generation, 7)
    }

    @MainActor
    func testCommandDoesNothingWithoutAnActiveSession() {
        let controller = makeController()
        var called = false
        controller.sendAction = { _, _, _ in called = true }
        controller.command("pause")
        XCTAssertFalse(called)
    }

    // MARK: - Volume (capability-aware)

    @MainActor
    func testVolumeIsCarriedThroughWhenTheStatePayloadReportsIt() {
        let controller = makeController()
        controller.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 1, hasSession: true, title: "Track A",
            capabilities: ["play", "pause", "volume"], volume: 42
        ))
        XCTAssertEqual(controller.state.volume, 42)
        XCTAssertTrue(controller.state.capabilities.contains("volume"))
    }

    @MainActor
    func testVolumeIsNilWhenTheSourceSessionDoesNotSupportIt() {
        let controller = makeController()
        controller.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 1, hasSession: true, title: "Track A", capabilities: ["play", "pause"]
        ))
        XCTAssertNil(controller.state.volume)
        XCTAssertFalse(controller.state.capabilities.contains("volume"))
    }

    @MainActor
    func testActionAckSurfacesARejectionReasonAndClearsOnTheNextAcceptedAck() {
        let controller = makeController()
        controller.receive(MediaRemoteStatePayload(version: 1, generation: 1, sequence: 1, hasSession: true, title: "Track A"))
        controller.receiveAck(MediaRemoteActionAckPayload(version: 1, requestId: "r1", accepted: false, reason: "stale_generation"))
        XCTAssertEqual(controller.lastActionRejectedReason, "stale_generation")
        controller.receiveAck(MediaRemoteActionAckPayload(version: 1, requestId: "r2", accepted: true))
        XCTAssertNil(controller.lastActionRejectedReason)
    }

    // MARK: - Global media shortcuts: Mac-native vs. Android precedence

    func testPlayingSourceTakesPrecedenceOverAMerelyLoadedOne() {
        XCTAssertTrue(preferMacNativeMediaSource(macPlaying: true, macHasSession: true, androidPlaying: false, androidHasSession: true))
        XCTAssertFalse(preferMacNativeMediaSource(macPlaying: false, macHasSession: true, androidPlaying: true, androidHasSession: true))
    }

    func testMacNativeBreaksTiesWhenBothSourcesArePlaying() {
        XCTAssertTrue(preferMacNativeMediaSource(macPlaying: true, macHasSession: true, androidPlaying: true, androidHasSession: true))
    }

    func testFallsBackToWhicheverHasASessionWhenNeitherIsPlaying() {
        XCTAssertTrue(preferMacNativeMediaSource(macPlaying: false, macHasSession: true, androidPlaying: false, androidHasSession: false))
        XCTAssertFalse(preferMacNativeMediaSource(macPlaying: false, macHasSession: false, androidPlaying: false, androidHasSession: true))
    }

    func testMacNativeIsTheHarmlessDefaultWhenNeitherSourceHasAnything() {
        XCTAssertTrue(preferMacNativeMediaSource(macPlaying: false, macHasSession: false, androidPlaying: false, androidHasSession: false))
    }

    // MARK: - Source label (never a raw package name)

    func testSourceLabelUsesTheResolvedAppName() {
        XCTAssertEqual(mediaSourceLabel(appLabel: "YouTube"), "YouTube · Android")
        XCTAssertEqual(mediaSourceLabel(appLabel: "Spotify"), "Spotify · Android")
    }

    func testSourceLabelFallsBackToAndroidAloneWhenNoNameWasResolved() {
        XCTAssertEqual(mediaSourceLabel(appLabel: nil), "Android")
        XCTAssertEqual(mediaSourceLabel(appLabel: ""), "Android")
    }

    // MARK: - Volume state sync (state is authoritative, no client-side hold)

    @MainActor
    func testVolumeReflectsWhateverTheLatestAuthoritativeStateReports() {
        let controller = makeController()
        controller.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 1, hasSession: true, title: "Track A",
            capabilities: ["volume"], volume: 30
        ))
        XCTAssertEqual(controller.state.volume, 30)
        // Android's authoritative republish after applying a volume change (not a client-held echo)
        // is what the Mac UI must reflect - simulated here as the next state update.
        controller.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 2, hasSession: true, title: "Track A",
            capabilities: ["volume"], volume: 70
        ))
        XCTAssertEqual(controller.state.volume, 70, "the UI must show the new authoritative value, not revert to the previous one")
    }
}
