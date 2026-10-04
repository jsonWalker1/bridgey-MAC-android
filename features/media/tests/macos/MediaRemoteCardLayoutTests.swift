import AppKit
import SwiftUI
import XCTest
@testable import BridgeyMac

/// Renders the actual MediaRemoteCard view (same technique as MenuBarPanelLayoutTests) to verify
/// the hasSession visibility rule for real, not just at the state-model level: no placeholder view
/// ("No media", "Waiting for media"...) may ever occupy space in the panel.
final class MediaRemoteCardLayoutTests: XCTestCase {
    @MainActor
    func testCardOccupiesNoSpaceWhenThereIsNoActiveSession() {
        let controller = MediaRemoteController()
        controller.allowed = { true }
        let host = NSHostingController(rootView: MediaRemoteCard(media: controller))
        let size = host.sizeThatFits(in: NSSize(width: 340, height: 400))
        XCTAssertEqual(size.height, 0, accuracy: 0.5)
    }

    @MainActor
    func testCardBecomesVisibleAssoonAsASessionArrivesAndDisappearsWhenItEnds() {
        let controller = MediaRemoteController()
        controller.allowed = { true }
        let host = NSHostingController(rootView: MediaRemoteCard(media: controller))
        controller.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 1, hasSession: true, title: "Track A", artist: "Artist"
        ))
        host.rootView = MediaRemoteCard(media: controller)
        let withSession = host.sizeThatFits(in: NSSize(width: 340, height: 400))
        XCTAssertGreaterThan(withSession.height, 40)

        controller.receive(MediaRemoteStatePayload(version: 1, generation: 2, sequence: 1, hasSession: false))
        host.rootView = MediaRemoteCard(media: controller)
        let afterSessionEnds = host.sizeThatFits(in: NSSize(width: 340, height: 400))
        XCTAssertEqual(afterSessionEnds.height, 0, accuracy: 0.5)
    }

    @MainActor
    func testCardGrowsToFitASeekBarWhenDurationIsKnown() {
        let controller = MediaRemoteController()
        controller.allowed = { true }
        let host = NSHostingController(rootView: MediaRemoteCard(media: controller))
        controller.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 1, hasSession: true, title: "No duration"
        ))
        host.rootView = MediaRemoteCard(media: controller)
        let withoutDuration = host.sizeThatFits(in: NSSize(width: 340, height: 400))

        controller.receive(MediaRemoteStatePayload(
            version: 1, generation: 1, sequence: 2, hasSession: true, title: "Has duration", duration: 180_000
        ))
        host.rootView = MediaRemoteCard(media: controller)
        let withDuration = host.sizeThatFits(in: NSSize(width: 340, height: 400))
        XCTAssertGreaterThan(withDuration.height, withoutDuration.height)
    }
}
