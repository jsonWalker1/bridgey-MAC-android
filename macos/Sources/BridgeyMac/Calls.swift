import AppKit
import Foundation
import UserNotifications

// Dedicated Calls module: consolidates the call-domain types and pure logic that were
// previously split across Pairing.swift, NotificationCall.swift and CallOverlayWindow.swift,
// plus the incoming-call presentation state machine (turning a forwarded Android call
// notification into `remoteCall` and driving the floating call overlay).
//
// The outbound "place a call from Mac" request pipeline (sendCall/sendCallWhenConnected/
// flushPendingCallIfPossible/clearCallStatus/setTransientCallStatus) stays in Pairing.swift:
// it needs direct access to the private transport Session, which is not exposed outside
// that file. Behavior here is unchanged from the pre-extraction implementation.

// MARK: - Call domain types

struct RemoteCallAction: Identifiable, Equatable {
    let id: String
    let title: String
}

/// Distinguishes how a displayed call was surfaced, so performRemoteCallAction knows whether to
/// replay a notification action token (legacy fallback) or send a calls.action command (Telecom
/// v2 path). For .telecom calls, `notificationID` holds the Android-generated stable call ID.
enum RemoteCallSource: Equatable {
    case notification
    case telecom
}

struct RemoteCallStatus: Equatable {
    let notificationID: String
    let deviceID: String
    let applicationName: String
    let caller: String
    let detail: String
    let type: String
    let actions: [RemoteCallAction]
    let source: RemoteCallSource
}

struct CallRequestPayload: Codable {
    let number: String
}

struct CallStatePayload: Codable {
    let version: Int
    let callId: String
    let state: String
    let callerName: String
    let callerNumber: String
}

struct CallActionPayload: Codable {
    let version: Int
    let callId: String
    let action: String
}

struct CallActionAckPayload: Codable {
    let version: Int
    let callId: String
    let action: String
    let accepted: Bool
}

// MARK: - Pure call-state helpers

private let supportedRemoteCallTypes: Set<String> = ["incoming", "ongoing", "screening", "unknown"]

func normalizedRemoteCallType(_ value: String?) -> String? {
    guard let value, supportedRemoteCallTypes.contains(value) else { return nil }
    return value
}

func remoteCallStatusTitle(_ type: String?) -> String {
    switch type {
    case "incoming": return "Incoming call"
    case "ongoing": return "Call in progress"
    case "screening": return "Call screening"
    default: return "Phone call"
    }
}

func remoteCallDetail(_ detail: String, type: String?) -> String {
    let trimmed = detail.trimmingCharacters(in: .whitespacesAndNewlines)
    let genericCallDescriptions: Set<String> = [
        "incoming call", "ongoing call", "call in progress", "call screening", "screening call", "phone call",
    ]
    return genericCallDescriptions.contains(trimmed.lowercased()) ? "" : trimmed
}

func shouldUseSystemNotification(callType: String?) -> Bool {
    callType == nil
}

// MARK: - Call action ordering (moved from CallOverlayWindow.swift)

func orderedCallActions(_ actions: [RemoteCallAction]) -> [RemoteCallAction] {
    actions.enumerated().sorted { left, right in
        let leftRank = callActionRank(left.element.title)
        let rightRank = callActionRank(right.element.title)
        return leftRank == rightRank ? left.offset < right.offset : leftRank < rightRank
    }.map(\.element)
}

private func callActionRank(_ title: String) -> Int {
    if title.localizedCaseInsensitiveContains("answer") { return 0 }
    if title.localizedCaseInsensitiveContains("decline") { return 2 }
    return 1
}

// MARK: - calls.state / calls.action wire validation

private let knownRemoteCallStates: Set<String> = ["ringing", "active", "ended", "missed"]
private let knownCallActions: Set<String> = ["answer", "decline", "hangup"]

func isKnownRemoteCallState(_ value: String) -> Bool { knownRemoteCallStates.contains(value) }

func isKnownCallAction(_ value: String) -> Bool { knownCallActions.contains(value) }

/// The overlay actions offered for a live (ringing/active) Telecom-sourced call. Titles match
/// the notification-fallback path's systemCallActionTitles exactly, so orderedCallActions and
/// the overlay's answer/decline color-coding need no Telecom-specific handling.
func telecomCallActions(for state: String) -> [RemoteCallAction] {
    switch state {
    case "ringing": return [RemoteCallAction(id: "answer", title: "Answer"), RemoteCallAction(id: "decline", title: "Decline")]
    case "active": return [RemoteCallAction(id: "hangup", title: "Hang Up")]
    default: return []
    }
}

// MARK: - Incoming-call presentation lifecycle (moved from Pairing.swift)

extension PairingCoordinator {
    func performRemoteCallAction(_ action: RemoteCallAction) {
        guard let call = remoteCall else { return }
        switch call.source {
        case .notification:
            performAndroidNotificationAction(
                call.notificationID,
                deviceID: call.deviceID,
                actionToken: action.id,
                replyText: nil
            )
        case .telecom:
            sendCallControl(callID: call.notificationID, action: action.id, deviceID: call.deviceID)
        }
    }

    func hideCallOverlay() {
        if let call = remoteCall {
            hiddenCallOverlayIdentity = callOverlayIdentity(call.notificationID, deviceID: call.deviceID)
        }
        callOverlayWindow.hide()
    }

    func updateRemoteCall(_ payload: RemoteNotificationPayload, deviceID: String) {
        guard let callType = normalizedRemoteCallType(payload.callType) else { return }
        let identity = callOverlayIdentity(payload.notificationId, deviceID: deviceID)
        if let existing = remoteCall,
           existing.notificationID != payload.notificationId || existing.deviceID != deviceID {
            clearRemoteCall()
        }
        clearCallStatus()
        let actions = (payload.actions ?? []).filter { !$0.allowsReply }.prefix(4).map {
            RemoteCallAction(id: $0.actionToken, title: $0.title)
        }
        remoteCall = RemoteCallStatus(
            notificationID: payload.notificationId,
            deviceID: deviceID,
            applicationName: payload.applicationName,
            caller: payload.title,
            detail: remoteCallDetail(payload.text, type: callType),
            type: callType,
            actions: actions,
            source: .notification
        )
        mediaController.callChanged(active: ["incoming", "ongoing"].contains(callType))
        if hiddenCallOverlayIdentity != identity {
            callOverlayWindow.show()
        }
        if callType == "incoming", audibleCallIdentity != identity {
            NSSound.beep()
            audibleCallIdentity = identity
        }
    }

    /// Primary incoming-call path: driven by Android's Telecom-sourced `calls.state` messages
    /// rather than the notification-scraping fallback above. Reuses the same `remoteCall`
    /// state and floating overlay, so CallOverlayWindow.swift needs no changes.
    func updateRemoteCallFromTelecom(_ payload: CallStatePayload, deviceID: String) {
        switch payload.state {
        case "ringing", "active":
            let type = payload.state == "ringing" ? "incoming" : "ongoing"
            let identity = callOverlayIdentity(payload.callId, deviceID: deviceID)
            if let existing = remoteCall,
               existing.notificationID != payload.callId || existing.deviceID != deviceID {
                clearRemoteCall()
            }
            clearCallStatus()
            let caller = payload.callerName.isEmpty ? payload.callerNumber : payload.callerName
            let detail = payload.callerName.isEmpty ? "" : payload.callerNumber
            remoteCall = RemoteCallStatus(
                notificationID: payload.callId,
                deviceID: deviceID,
                applicationName: "Phone",
                caller: caller,
                detail: detail,
                type: type,
                actions: telecomCallActions(for: payload.state),
                source: .telecom
            )
            mediaController.callChanged(active: true)
            if hiddenCallOverlayIdentity != identity {
                callOverlayWindow.show()
            }
            if type == "incoming", audibleCallIdentity != identity {
                NSSound.beep()
                audibleCallIdentity = identity
            }
        case "missed":
            let isCurrentCall = remoteCall?.notificationID == payload.callId && remoteCall?.deviceID == deviceID
            if isCurrentCall { clearRemoteCall() }
            let who = payload.callerName.isEmpty ? payload.callerNumber : payload.callerName
            setTransientCallStatus(who.isEmpty ? "Missed call" : "Missed call from \(who)")
        case "ended":
            if remoteCall?.notificationID == payload.callId && remoteCall?.deviceID == deviceID {
                clearRemoteCall()
            }
        default:
            break
        }
    }

    func clearRemoteCall() {
        mediaController.callChanged(active: false)
        guard let call = remoteCall else { return }
        remoteCall = nil
        callOverlayWindow.hide()
        hiddenCallOverlayIdentity = nil
        audibleCallIdentity = nil
        let identifier = remoteNotificationRequestIdentifier(
            deviceID: call.deviceID,
            notificationID: call.notificationID
        )
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
    }

    func callOverlayIdentity(_ notificationID: String, deviceID: String) -> String {
        "\(deviceID)\u{0}\(notificationID)"
    }
}
