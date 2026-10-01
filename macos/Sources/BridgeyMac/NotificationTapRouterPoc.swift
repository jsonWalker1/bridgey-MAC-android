import AppKit
import Foundation

// BRIDGEY NOTIFICATION TAP ROUTING POC - NOT PRODUCTION, DO NOT SHIP WITHOUT REVIEW.
//
// Clicking a mirrored Android notification in macOS Notification Center (the default action)
// used to do nothing. This proof of concept routes the click to a native macOS counterpart of the
// Android source app, optionally straight into a conversation, using only public NSWorkspace API.
// No settings UI: the Open & Clear / Open & Keep policy is read from UserDefaults
// (`defaults write dev.bridgey.mac BridgeyPocTapPolicy keep`, default "clear").

/// What a tap on a mirrored notification should open.
enum NotificationTapTarget: Equatable {
    /// A specific URL (e.g. a conversation deep link), opened with the given app.
    case url(URL, appBundleIdentifier: String)
    /// The native macOS app only (no conversation could be resolved).
    case application(bundleIdentifier: String)
    /// No macOS counterpart: keep the existing behaviour (nothing; the notification's own
    /// Android "Open" action button still opens the app on the phone).
    case none
}

/// One Android app's macOS counterpart. Data, not logic: a production version would load this
/// from user settings instead of the built-in POC catalog below.
struct NotificationTapRoute: Equatable {
    let androidPackage: String
    let macBundleIdentifiers: [String]
    /// Builds a conversation URL from the Android conversation id (`Notification.shortcutId`), or
    /// `nil` when that id cannot be mapped to anything the macOS app understands.
    let conversationURL: ((String) -> URL?)?

    static func == (lhs: NotificationTapRoute, rhs: NotificationTapRoute) -> Bool {
        lhs.androidPackage == rhs.androidPackage && lhs.macBundleIdentifiers == rhs.macBundleIdentifiers
    }
}

/// WhatsApp for Mac opens a specific chat only from a phone number (`whatsapp://send?phone=`).
/// Android WhatsApp conversation ids are JIDs: `<phone>@s.whatsapp.net` carries the number, while
/// the newer privacy-preserving `<id>@lid` and group `<id>@g.us` do not - those cannot be mapped.
func whatsAppConversationURL(conversationID: String) -> URL? {
    let parts = conversationID.split(separator: "@", maxSplits: 1).map(String.init)
    guard parts.count == 2, parts[1] == "s.whatsapp.net",
          !parts[0].isEmpty, parts[0].count <= 20, parts[0].allSatisfy(\.isNumber) else { return nil }
    return URL(string: "whatsapp://send?phone=\(parts[0])")
}

let notificationTapRoutesPoc: [NotificationTapRoute] = [
    NotificationTapRoute(androidPackage: "com.whatsapp", macBundleIdentifiers: ["net.whatsapp.WhatsApp", "desktop.WhatsApp"],
                         conversationURL: whatsAppConversationURL),
    NotificationTapRoute(androidPackage: "com.discord", macBundleIdentifiers: ["com.hnc.Discord"], conversationURL: nil),
    NotificationTapRoute(androidPackage: "com.Slack", macBundleIdentifiers: ["com.tinyspeck.slackmacgap"], conversationURL: nil),
    NotificationTapRoute(androidPackage: "org.telegram.messenger", macBundleIdentifiers: ["ru.keepcoder.Telegram"], conversationURL: nil),
]

/// Pure target resolution. `installedApplication` and `canOpen` are injected so this is testable
/// without the real system (production: NSWorkspace).
func resolveNotificationTapTarget(
    androidPackage: String,
    conversationID: String?,
    routes: [NotificationTapRoute],
    installedApplication: (String) -> Bool,
    canOpen: (URL, String) -> Bool
) -> NotificationTapTarget {
    guard let route = routes.first(where: { $0.androidPackage == androidPackage }),
          let bundleID = route.macBundleIdentifiers.first(where: installedApplication) else { return .none }
    if let conversationID, let url = route.conversationURL?(conversationID), canOpen(url, bundleID) {
        return .url(url, appBundleIdentifier: bundleID)
    }
    return .application(bundleIdentifier: bundleID)
}

enum NotificationTapPolicy: String {
    case clear, keep

    static var current: NotificationTapPolicy {
        UserDefaults.standard.string(forKey: "BridgeyPocTapPolicy").flatMap(NotificationTapPolicy.init(rawValue:)) ?? .clear
    }
}

/// Opens [target] with public NSWorkspace API and reports the system's real launch/open result.
/// "Success" means macOS accepted and performed the open; it cannot confirm which chat the app shows.
@MainActor
func openNotificationTapTarget(_ target: NotificationTapTarget, completion: @escaping @MainActor (Bool, String) -> Void) {
    let workspace = NSWorkspace.shared
    let configuration = NSWorkspace.OpenConfiguration()
    configuration.activates = true
    switch target {
    case .none:
        completion(false, "no_target")
    case let .application(bundleIdentifier):
        guard let appURL = workspace.urlForApplication(withBundleIdentifier: bundleIdentifier) else {
            return completion(false, "not_installed")
        }
        workspace.openApplication(at: appURL, configuration: configuration) { app, error in
            Task { @MainActor in completion(error == nil && app != nil, error.map { "error=\($0.localizedDescription)" } ?? "opened_app") }
        }
    case let .url(url, bundleIdentifier):
        guard let appURL = workspace.urlForApplication(withBundleIdentifier: bundleIdentifier) else {
            return completion(false, "not_installed")
        }
        workspace.open([url], withApplicationAt: appURL, configuration: configuration) { app, error in
            Task { @MainActor in completion(error == nil && app != nil, error.map { "error=\($0.localizedDescription)" } ?? "opened_url") }
        }
    }
}

/// Production resolution against the real system.
@MainActor
func resolveNotificationTapTargetOnThisMac(androidPackage: String, conversationID: String?) -> NotificationTapTarget {
    let workspace = NSWorkspace.shared
    return resolveNotificationTapTarget(
        androidPackage: androidPackage,
        conversationID: conversationID,
        routes: notificationTapRoutesPoc,
        installedApplication: { workspace.urlForApplication(withBundleIdentifier: $0) != nil },
        canOpen: { url, bundleID in
            guard let appURL = workspace.urlForApplication(withBundleIdentifier: bundleID) else { return false }
            return workspace.urlsForApplications(toOpen: url).contains(appURL)
        }
    )
}
