import AppKit
import Foundation
import SwiftUI
import UniformTypeIdentifiers

// BRIDGEY NOTIFICATION ACTION ROUTING
//
// What a click on a mirrored Android notification in macOS Notification Center does, configured
// per Android app in Settings. Routing order: explicit per-app rule -> global default -> safe
// fallback. Only public API (NSWorkspace) is used to open targets. Android notification state stays
// owned by the Notification++ sync: "Clear" dismisses the notification exactly like the user closing
// it (Mac and phone), "Keep" leaves it on both.

enum NotificationClickAction: String, Codable, CaseIterable, Identifiable {
    /// Show a small native choice (choose a Mac app, open on the phone, or nothing).
    case ask
    /// Open a native macOS application, identified by bundle identifier.
    case openNativeApp
    /// Open a URL with its default macOS handler. `{conversationId}` is replaced (URL-encoded).
    case openURL
    /// Run the notification's own Android "Open" action on the phone.
    case openOnPhone
    case doNothing

    var id: String { rawValue }
    var title: String {
        switch self {
        case .ask: "Ask"
        case .openNativeApp: "Open Mac app"
        case .openURL: "Open URL"
        case .openOnPhone: "Open on phone"
        case .doNothing: "Do nothing"
        }
    }

    /// Actions that need no per-app target, so they can be the global default.
    static let globalDefaultChoices: [NotificationClickAction] = [.ask, .openOnPhone, .doNothing]
}

enum NotificationClearBehavior: String, Codable, CaseIterable, Identifiable {
    case clear, keep
    var id: String { rawValue }
    var title: String { self == .clear ? "Clear" : "Keep" }
}

struct NotificationActionRule: Codable, Equatable, Identifiable {
    var androidPackage: String
    /// The Android app name as last seen in its notifications (display only).
    var displayName: String
    var action: NotificationClickAction
    /// Stable target for `openNativeApp`.
    var macAppBundleIdentifier: String?
    /// Display name of the Mac app (display only; the bundle identifier is authoritative).
    var macAppName: String?
    /// Target for `openURL`.
    var url: String?
    var clearBehavior: NotificationClearBehavior

    var id: String { androidPackage }
}

struct NotificationActionDefaults: Codable, Equatable {
    var action: NotificationClickAction = .ask
    var clearBehavior: NotificationClearBehavior = .clear
}

/// What is known about the clicked notification.
struct NotificationClickContext: Equatable {
    var androidPackage: String?
    var conversationID: String?
    /// The notification carries Android's generic "Open" action and the phone is connected.
    var canOpenOnPhone: Bool
}

enum NotificationClickDecision: Equatable {
    case openApplication(bundleIdentifier: String, clear: Bool)
    case openURL(URL, clear: Bool)
    case openOnPhone(clear: Bool)
    case ask(clear: Bool)
    /// Leave everything as it was (the notification is kept).
    case doNothing

    var clears: Bool {
        switch self {
        case let .openApplication(_, clear), let .openURL(_, clear), let .openOnPhone(clear), let .ask(clear): clear
        case .doNothing: false
        }
    }
}

/// Expands `{conversationId}` and validates the result: it must parse and carry a scheme.
func notificationActionURL(_ template: String?, conversationID: String?) -> URL? {
    guard var value = template?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
    if value.contains("{conversationId}") {
        guard let conversationID, !conversationID.isEmpty,
              let encoded = conversationID.addingPercentEncoding(withAllowedCharacters: .alphanumerics) else { return nil }
        value = value.replacingOccurrences(of: "{conversationId}", with: encoded)
    }
    guard let url = URL(string: value), let scheme = url.scheme, !scheme.isEmpty else { return nil }
    return url
}

/// Pure routing: per-app rule -> global default -> safe fallback (`ask`, which never needs a target).
/// A rule whose target is unusable (app not installed, invalid URL, phone not available) falls
/// through to the global default; an unusable global default falls back to `ask`.
func routeNotificationClick(
    _ context: NotificationClickContext,
    rules: [NotificationActionRule],
    defaults: NotificationActionDefaults,
    isApplicationInstalled: (String) -> Bool
) -> NotificationClickDecision {
    if let package = context.androidPackage,
       let rule = rules.first(where: { $0.androidPackage == package }),
       let decision = decision(for: rule.action, clear: rule.clearBehavior == .clear, rule: rule, context: context, isApplicationInstalled: isApplicationInstalled) {
        return decision
    }
    let clear = defaults.clearBehavior == .clear
    return decision(for: defaults.action, clear: clear, rule: nil, context: context, isApplicationInstalled: isApplicationInstalled)
        ?? .ask(clear: clear)
}

private func decision(
    for action: NotificationClickAction,
    clear: Bool,
    rule: NotificationActionRule?,
    context: NotificationClickContext,
    isApplicationInstalled: (String) -> Bool
) -> NotificationClickDecision? {
    switch action {
    case .ask:
        return .ask(clear: clear)
    case .doNothing:
        return .doNothing
    case .openOnPhone:
        return context.canOpenOnPhone ? .openOnPhone(clear: clear) : nil
    case .openNativeApp:
        guard let bundleID = rule?.macAppBundleIdentifier, !bundleID.isEmpty, isApplicationInstalled(bundleID) else { return nil }
        return .openApplication(bundleIdentifier: bundleID, clear: clear)
    case .openURL:
        guard let url = notificationActionURL(rule?.url, conversationID: context.conversationID) else { return nil }
        return .openURL(url, clear: clear)
    }
}

/// Persisted notification click configuration (UserDefaults, JSON, versioned key).
@MainActor
final class NotificationActionSettings: ObservableObject {
    private struct Stored: Codable {
        var defaults = NotificationActionDefaults()
        var rules: [NotificationActionRule] = []
        /// Android apps seen in forwarded notifications: package -> display name.
        var seenApps: [String: String] = [:]
        var seededExamples = false
    }

    static let storageKey = "notificationActions.v1"
    /// Example rule created once on first launch, only when its Mac app is installed.
    static let exampleRules: [NotificationActionRule] = [
        NotificationActionRule(androidPackage: "com.whatsapp", displayName: "WhatsApp", action: .openNativeApp,
                               macAppBundleIdentifier: "net.whatsapp.WhatsApp", macAppName: "WhatsApp", url: nil, clearBehavior: .clear),
    ]

    @Published private(set) var defaults: NotificationActionDefaults
    @Published private(set) var rules: [NotificationActionRule]
    @Published private(set) var seenApps: [String: String]
    private let store: UserDefaults

    init(store: UserDefaults = .standard, isApplicationInstalled: (String) -> Bool = NotificationActionSettings.isInstalled) {
        self.store = store
        var stored = store.data(forKey: Self.storageKey).flatMap { try? JSONDecoder().decode(Stored.self, from: $0) } ?? Stored()
        if !stored.seededExamples {
            for example in Self.exampleRules
            where !stored.rules.contains(where: { $0.androidPackage == example.androidPackage })
                && example.macAppBundleIdentifier.map(isApplicationInstalled) == true {
                stored.rules.append(example)
            }
            stored.seededExamples = true
        }
        defaults = stored.defaults
        rules = stored.rules
        seenApps = stored.seenApps
        save()
    }

    nonisolated static func isInstalled(_ bundleIdentifier: String) -> Bool {
        NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundleIdentifier) != nil
    }

    func route(_ context: NotificationClickContext) -> NotificationClickDecision {
        routeNotificationClick(context, rules: rules, defaults: defaults, isApplicationInstalled: Self.isInstalled)
    }

    func setDefaults(_ value: NotificationActionDefaults) {
        defaults = value
        save()
    }

    func upsert(_ rule: NotificationActionRule) {
        if let index = rules.firstIndex(where: { $0.androidPackage == rule.androidPackage }) {
            rules[index] = rule
        } else {
            rules.append(rule)
        }
        save()
    }

    func removeRule(androidPackage: String) {
        rules.removeAll { $0.androidPackage == androidPackage }
        save()
    }

    /// Remembers Android apps that sent notifications, so they can be picked when adding a rule.
    func observe(androidPackage: String, displayName: String) {
        guard !androidPackage.isEmpty, seenApps[androidPackage] != displayName else { return }
        seenApps[androidPackage] = String(displayName.prefix(128))
        if seenApps.count > 200, let drop = seenApps.keys.sorted().first(where: { key in !rules.contains { $0.androidPackage == key } }) {
            seenApps.removeValue(forKey: drop)
        }
        save()
    }

    private func save() {
        let stored = Stored(defaults: defaults, rules: rules, seenApps: seenApps, seededExamples: true)
        if let data = try? JSONEncoder().encode(stored) { store.set(data, forKey: Self.storageKey) }
    }

    /// Lets the user pick a Mac application; returns its stable bundle identifier and name.
    static func chooseMacApplication() -> (bundleIdentifier: String, name: String)? {
        let panel = NSOpenPanel()
        panel.title = "Choose the Mac app to open"
        panel.directoryURL = URL(fileURLWithPath: "/Applications", isDirectory: true)
        panel.allowedContentTypes = [.applicationBundle]
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK, let url = panel.url,
              let bundle = Bundle(url: url), let identifier = bundle.bundleIdentifier else { return nil }
        let name = (bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String)
            ?? (bundle.object(forInfoDictionaryKey: "CFBundleName") as? String)
            ?? url.deletingPathExtension().lastPathComponent
        return (identifier, name)
    }
}

/// Opens a native app or URL with public NSWorkspace API and reports the system's real result.
@MainActor
func openNotificationClickTarget(_ decision: NotificationClickDecision, completion: @escaping @MainActor (Bool, String) -> Void) {
    let workspace = NSWorkspace.shared
    let configuration = NSWorkspace.OpenConfiguration()
    configuration.activates = true
    switch decision {
    case let .openApplication(bundleIdentifier, _):
        guard let appURL = workspace.urlForApplication(withBundleIdentifier: bundleIdentifier) else {
            return completion(false, "not_installed")
        }
        workspace.openApplication(at: appURL, configuration: configuration) { app, error in
            Task { @MainActor in completion(error == nil && app != nil, error?.localizedDescription ?? "opened_app") }
        }
    case let .openURL(url, _):
        workspace.open(url, configuration: configuration) { app, error in
            Task { @MainActor in completion(error == nil, error?.localizedDescription ?? "opened_url") }
        }
    case .openOnPhone, .ask, .doNothing:
        completion(false, "not_a_mac_target")
    }
}

// MARK: - Settings UI

struct NotificationActionSettingsView: View {
    @ObservedObject var actions: NotificationActionSettings

    var body: some View {
        Section("Notification click actions") {
            Text("What happens when you click a phone notification on this Mac. Clear removes it on the Mac and the phone; Keep leaves it on both.")
                .font(.caption).foregroundStyle(.secondary)
            HStack {
                Picker("Default", selection: Binding(
                    get: { actions.defaults.action },
                    set: { actions.setDefaults(NotificationActionDefaults(action: $0, clearBehavior: actions.defaults.clearBehavior)) }
                )) {
                    ForEach(NotificationClickAction.globalDefaultChoices) { Text($0.title).tag($0) }
                }
                Picker("", selection: Binding(
                    get: { actions.defaults.clearBehavior },
                    set: { actions.setDefaults(NotificationActionDefaults(action: actions.defaults.action, clearBehavior: $0)) }
                )) {
                    ForEach(NotificationClearBehavior.allCases) { Text($0.title).tag($0) }
                }
                .labelsHidden()
                .frame(width: 90)
            }
            ForEach(actions.rules) { rule in
                NotificationActionRuleRow(rule: rule, actions: actions)
            }
            let unconfigured = actions.seenApps
                .filter { entry in !actions.rules.contains { $0.androidPackage == entry.key } }
                .sorted { $0.value.localizedCaseInsensitiveCompare($1.value) == .orderedAscending }
            Menu("Add app rule") {
                if unconfigured.isEmpty {
                    Text("Apps appear here after they send a notification")
                }
                ForEach(unconfigured, id: \.key) { package, name in
                    Button(name) {
                        actions.upsert(NotificationActionRule(androidPackage: package, displayName: name, action: .ask,
                                                              macAppBundleIdentifier: nil, macAppName: nil, url: nil,
                                                              clearBehavior: actions.defaults.clearBehavior))
                    }
                }
            }
            .fixedSize()
        }
    }
}

private struct NotificationActionRuleRow: View {
    let rule: NotificationActionRule
    @ObservedObject var actions: NotificationActionSettings
    @State private var urlText = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(rule.displayName).fontWeight(.medium)
                Spacer()
                Picker("", selection: Binding(get: { rule.action }, set: { update(\.action, $0) })) {
                    ForEach(NotificationClickAction.allCases) { Text($0.title).tag($0) }
                }
                .labelsHidden()
                .frame(width: 150)
                Picker("", selection: Binding(get: { rule.clearBehavior }, set: { update(\.clearBehavior, $0) })) {
                    ForEach(NotificationClearBehavior.allCases) { Text($0.title).tag($0) }
                }
                .labelsHidden()
                .frame(width: 90)
                Button { actions.removeRule(androidPackage: rule.androidPackage) } label: { Image(systemName: "minus.circle") }
                    .buttonStyle(.borderless)
                    .help("Remove rule")
            }
            if rule.action == .openNativeApp {
                HStack {
                    let installed = rule.macAppBundleIdentifier.map(NotificationActionSettings.isInstalled) == true
                    Text(rule.macAppName ?? "No Mac app chosen")
                        .foregroundStyle(installed ? .secondary : Color.red)
                    if rule.macAppBundleIdentifier != nil && !installed {
                        Text("not installed").font(.caption).foregroundStyle(.red)
                    }
                    Spacer()
                    Button("Choose…") {
                        guard let app = NotificationActionSettings.chooseMacApplication() else { return }
                        var updated = rule
                        updated.macAppBundleIdentifier = app.bundleIdentifier
                        updated.macAppName = app.name
                        actions.upsert(updated)
                    }
                }
                .font(.callout)
            }
            if rule.action == .openURL {
                HStack {
                    TextField("https://… or app://… ({conversationId} optional)", text: $urlText)
                        .onSubmit { update(\.url, urlText) }
                    Button("Save") { update(\.url, urlText) }
                        .disabled(urlText == (rule.url ?? ""))
                }
                if notificationActionURL(rule.url, conversationID: "example") == nil {
                    Text("Enter a valid URL").font(.caption).foregroundStyle(.red)
                }
            }
        }
        .onAppear { urlText = rule.url ?? "" }
    }

    private func update<Value>(_ keyPath: WritableKeyPath<NotificationActionRule, Value>, _ value: Value) {
        var updated = rule
        updated[keyPath: keyPath] = value
        actions.upsert(updated)
    }
}
