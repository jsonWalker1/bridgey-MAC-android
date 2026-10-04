@testable import BridgeyMac
import XCTest

/// Notification click routing: per-app rule -> global default -> safe fallback.
@MainActor
final class NotificationActionRoutingTests: XCTestCase {
    private let installed: Set<String> = ["net.whatsapp.WhatsApp", "com.google.Chrome"]

    private func rule(_ package: String, _ action: NotificationClickAction, app: String? = nil, url: String? = nil,
                      _ clear: NotificationClearBehavior = .clear) -> NotificationActionRule {
        NotificationActionRule(androidPackage: package, displayName: package, action: action,
                               macAppBundleIdentifier: app, macAppName: app, url: url, clearBehavior: clear)
    }

    private func route(_ package: String?, conversation: String? = nil, phone: Bool = true,
                       rules: [NotificationActionRule], defaults: NotificationActionDefaults = .init()) -> NotificationClickDecision {
        routeNotificationClick(NotificationClickContext(androidPackage: package, conversationID: conversation, canOpenOnPhone: phone),
                               rules: rules, defaults: defaults, isApplicationInstalled: { self.installed.contains($0) })
    }

    // 1. Global default
    func testGlobalDefaultAppliesWithoutARule() {
        XCTAssertEqual(route("com.example", rules: [], defaults: .init(action: .openOnPhone, clearBehavior: .keep)), .openOnPhone(clear: false))
        XCTAssertEqual(route("com.example", rules: [], defaults: .init(action: .doNothing, clearBehavior: .clear)), .doNothing)
    }

    // 2. Per-app override wins over the default
    func testPerAppRuleOverridesTheDefault() {
        let rules = [rule("com.google.android.apps.dynamite", .openNativeApp, app: "com.google.Chrome", .keep)]
        XCTAssertEqual(route("com.google.android.apps.dynamite", rules: rules, defaults: .init(action: .doNothing, clearBehavior: .clear)),
                       .openApplication(bundleIdentifier: "com.google.Chrome", clear: false))
    }

    // 3. Unknown app -> default (ask by default)
    func testUnknownAppFallsBackToTheDefaultAsk() {
        XCTAssertEqual(route("com.unknown", rules: [rule("com.whatsapp", .openNativeApp, app: "net.whatsapp.WhatsApp")]), .ask(clear: true))
    }

    // 4. Native app target
    func testNativeAppTarget() {
        XCTAssertEqual(route("com.whatsapp", rules: [rule("com.whatsapp", .openNativeApp, app: "net.whatsapp.WhatsApp")]),
                       .openApplication(bundleIdentifier: "net.whatsapp.WhatsApp", clear: true))
    }

    // 5. URL target, with optional {conversationId}
    func testURLTarget() {
        XCTAssertEqual(route("com.mail", rules: [rule("com.mail", .openURL, url: "https://mail.google.com")]),
                       .openURL(URL(string: "https://mail.google.com")!, clear: true))
        XCTAssertEqual(route("com.chat", conversation: "room 1@x", rules: [rule("com.chat", .openURL, url: "chat://open?c={conversationId}")]),
                       .openURL(URL(string: "chat://open?c=room%201%40x")!, clear: true))
    }

    // 6 + 7. Clear and keep follow the rule (or the default when no rule)
    func testClearAndKeepBehaviour() {
        XCTAssertTrue(route("com.whatsapp", rules: [rule("com.whatsapp", .openNativeApp, app: "net.whatsapp.WhatsApp", .clear)]).clears)
        XCTAssertFalse(route("com.whatsapp", rules: [rule("com.whatsapp", .openNativeApp, app: "net.whatsapp.WhatsApp", .keep)]).clears)
        XCTAssertFalse(route("com.x", rules: [], defaults: .init(action: .ask, clearBehavior: .keep)).clears)
        XCTAssertFalse(NotificationClickDecision.doNothing.clears)
    }

    // 8. Invalid / missing targets fall through to the default, never crash
    func testInvalidOrMissingTargetsFallBackDeterministically() {
        let defaults = NotificationActionDefaults(action: .openOnPhone, clearBehavior: .keep)
        XCTAssertEqual(route("a", rules: [rule("a", .openNativeApp, app: "com.not.installed")], defaults: defaults), .openOnPhone(clear: false))
        XCTAssertEqual(route("a", rules: [rule("a", .openNativeApp, app: nil)], defaults: defaults), .openOnPhone(clear: false))
        XCTAssertEqual(route("a", rules: [rule("a", .openURL, url: "not a url")], defaults: defaults), .openOnPhone(clear: false))
        XCTAssertEqual(route("a", rules: [rule("a", .openURL, url: "x://{conversationId}")], defaults: defaults), .openOnPhone(clear: false),
                       "placeholder without a conversation id")
        XCTAssertEqual(route("a", phone: false, rules: [rule("a", .openOnPhone)], defaults: defaults), .ask(clear: false),
                       "phone not available and default not usable -> ask")
        XCTAssertEqual(route(nil, rules: [rule("a", .doNothing)], defaults: .init(action: .ask, clearBehavior: .clear)), .ask(clear: true),
                       "incomplete metadata -> default")
    }

    // 9. WhatsApp is just a generic rule (seeded example only when installed)
    func testWhatsAppGoesThroughTheGenericRuleAndIsSeededOnlyWhenInstalled() {
        let suite = UserDefaults(suiteName: "bridgey.tests.\(UUID().uuidString)")!
        let seeded = NotificationActionSettings(store: suite, isApplicationInstalled: { $0 == "net.whatsapp.WhatsApp" })
        XCTAssertEqual(seeded.rules.map(\.androidPackage), ["com.whatsapp"])
        XCTAssertEqual(seeded.rules.first?.action, .openNativeApp)

        let empty = UserDefaults(suiteName: "bridgey.tests.\(UUID().uuidString)")!
        XCTAssertTrue(NotificationActionSettings(store: empty, isApplicationInstalled: { _ in false }).rules.isEmpty)
    }

    // 10. Persistence / reload, and a removed example is never re-seeded
    func testRulesDefaultsAndSeenAppsPersistAcrossReload() {
        let suite = UserDefaults(suiteName: "bridgey.tests.\(UUID().uuidString)")!
        let first = NotificationActionSettings(store: suite, isApplicationInstalled: { _ in true })
        first.removeRule(androidPackage: "com.whatsapp")
        first.setDefaults(.init(action: .doNothing, clearBehavior: .keep))
        first.upsert(rule("com.slack", .openURL, url: "https://app.slack.com", .keep))
        first.observe(androidPackage: "com.slack", displayName: "Slack")

        let reloaded = NotificationActionSettings(store: suite, isApplicationInstalled: { _ in true })
        XCTAssertEqual(reloaded.defaults, .init(action: .doNothing, clearBehavior: .keep))
        XCTAssertEqual(reloaded.rules, [rule("com.slack", .openURL, url: "https://app.slack.com", .keep)])
        XCTAssertEqual(reloaded.seenApps["com.slack"], "Slack")
    }

    // Small routing matrix: action x availability
    func testRoutingMatrix() {
        let cases: [(NotificationActionRule, Bool, NotificationClickDecision)] = [
            (rule("p", .openNativeApp, app: "net.whatsapp.WhatsApp"), true, .openApplication(bundleIdentifier: "net.whatsapp.WhatsApp", clear: true)),
            (rule("p", .openNativeApp, app: "missing.app"), true, .ask(clear: true)),
            (rule("p", .openURL, url: "https://example.com"), true, .openURL(URL(string: "https://example.com")!, clear: true)),
            (rule("p", .openOnPhone), true, .openOnPhone(clear: true)),
            (rule("p", .openOnPhone), false, .ask(clear: true)),
            (rule("p", .ask, .keep), true, .ask(clear: false)),
            (rule("p", .doNothing), true, .doNothing),
        ]
        for (rule, phone, expected) in cases {
            XCTAssertEqual(route("p", phone: phone, rules: [rule]), expected, "\(rule.action) phone=\(phone)")
        }
    }
}
