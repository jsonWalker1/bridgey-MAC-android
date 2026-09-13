import XCTest
@testable import BridgeyMac

/// Tests for the call-domain logic consolidated into Calls.swift.
/// Moved verbatim from NotificationCallTests (pre-extraction) plus new coverage for
/// call-action ordering edge cases.
final class CallsTests: XCTestCase {
    func testAcceptsOnlyKnownCallTypes() {
        XCTAssertEqual(normalizedRemoteCallType("incoming"), "incoming")
        XCTAssertEqual(normalizedRemoteCallType("ongoing"), "ongoing")
        XCTAssertEqual(normalizedRemoteCallType("screening"), "screening")
        XCTAssertEqual(normalizedRemoteCallType("unknown"), "unknown")
        XCTAssertNil(normalizedRemoteCallType("decline"))
        XCTAssertNil(normalizedRemoteCallType(nil))
    }

    func testProvidesSafeCallTitles() {
        XCTAssertEqual(remoteCallStatusTitle("incoming"), "Incoming call")
        XCTAssertEqual(remoteCallStatusTitle("ongoing"), "Call in progress")
        XCTAssertEqual(remoteCallStatusTitle("screening"), "Call screening")
        XCTAssertEqual(remoteCallStatusTitle("unknown"), "Phone call")
    }

    func testRemovesStaleGenericAndroidCallDescriptionAfterStateTransition() {
        XCTAssertEqual(remoteCallDetail("Incoming call", type: "ongoing"), "")
        XCTAssertEqual(remoteCallDetail("Call in progress", type: "ongoing"), "")
        XCTAssertEqual(remoteCallDetail("  Verified caller  ", type: "incoming"), "Verified caller")
    }

    func testCallOverlayReplacesOnlySystemCallNotifications() {
        XCTAssertTrue(shouldUseSystemNotification(callType: nil))
        XCTAssertFalse(shouldUseSystemNotification(callType: "incoming"))
        XCTAssertFalse(shouldUseSystemNotification(callType: "ongoing"))
    }

    func testCallOverlayPlacesAnswerBeforeDecline() {
        let decline = RemoteCallAction(id: "decline", title: "Decline")
        let answer = RemoteCallAction(id: "answer", title: "Answer")
        XCTAssertEqual(orderedCallActions([decline, answer]), [answer, decline])
    }

    func testCallOverlayPlacesUnrecognizedActionsBetweenAnswerAndDecline() {
        let decline = RemoteCallAction(id: "decline", title: "Decline")
        let answer = RemoteCallAction(id: "answer", title: "Answer")
        let hangUp = RemoteCallAction(id: "hangup", title: "Hang Up")
        XCTAssertEqual(orderedCallActions([decline, hangUp, answer]), [answer, hangUp, decline])
    }

    func testCallOverlayOrderingIsStableForActionsOfEqualRank() {
        let first = RemoteCallAction(id: "first", title: "Hang Up")
        let second = RemoteCallAction(id: "second", title: "Mute")
        XCTAssertEqual(orderedCallActions([first, second]), [first, second])
        XCTAssertEqual(orderedCallActions([second, first]), [second, first])
    }

    // MARK: - calls.state / calls.action (v2 Telecom channel)

    func testOnlyTheFourDocumentedCallStatesAreAccepted() {
        XCTAssertTrue(isKnownRemoteCallState("ringing"))
        XCTAssertTrue(isKnownRemoteCallState("active"))
        XCTAssertTrue(isKnownRemoteCallState("ended"))
        XCTAssertTrue(isKnownRemoteCallState("missed"))
        XCTAssertFalse(isKnownRemoteCallState("incoming"))
        XCTAssertFalse(isKnownRemoteCallState(""))
        XCTAssertFalse(isKnownRemoteCallState("Ringing"))
    }

    func testOnlyTheThreeDocumentedCallActionsAreAccepted() {
        XCTAssertTrue(isKnownCallAction("answer"))
        XCTAssertTrue(isKnownCallAction("decline"))
        XCTAssertTrue(isKnownCallAction("hangup"))
        XCTAssertFalse(isKnownCallAction("hang_up"))
        XCTAssertFalse(isKnownCallAction(""))
    }

    func testRingingTelecomCallOffersAnswerAndDeclineInOverlayOrder() {
        let actions = telecomCallActions(for: "ringing")
        XCTAssertEqual(actions.map(\.title), ["Answer", "Decline"])
        XCTAssertEqual(orderedCallActions(actions).map(\.title), ["Answer", "Decline"])
        // Action ids double as the calls.action command Mac sends back.
        XCTAssertEqual(Set(actions.map(\.id)), ["answer", "decline"])
    }

    func testActiveTelecomCallOffersOnlyHangUp() {
        let actions = telecomCallActions(for: "active")
        XCTAssertEqual(actions.map(\.title), ["Hang Up"])
        XCTAssertEqual(actions.map(\.id), ["hangup"])
    }

    func testTerminalTelecomStatesOfferNoActions() {
        XCTAssertTrue(telecomCallActions(for: "ended").isEmpty)
        XCTAssertTrue(telecomCallActions(for: "missed").isEmpty)
    }

    func testRemoteCallStatusDistinguishesSourceForActionDispatch() {
        let telecomCall = RemoteCallStatus(
            notificationID: "call-id", deviceID: "device", applicationName: "Phone",
            caller: "+15550100", detail: "", type: "incoming",
            actions: telecomCallActions(for: "ringing"), source: .telecom
        )
        let notificationCall = RemoteCallStatus(
            notificationID: "call-id", deviceID: "device", applicationName: "Phone",
            caller: "+15550100", detail: "", type: "incoming",
            actions: telecomCallActions(for: "ringing"), source: .notification
        )
        XCTAssertNotEqual(telecomCall, notificationCall)
        XCTAssertEqual(telecomCall.source, .telecom)
        XCTAssertEqual(notificationCall.source, .notification)
    }
}
