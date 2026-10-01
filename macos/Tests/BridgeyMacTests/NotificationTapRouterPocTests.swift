@testable import BridgeyMac
import XCTest

/// BRIDGEY NOTIFICATION TAP ROUTING POC: target resolution.
final class NotificationTapRouterPocTests: XCTestCase {
    private func resolve(_ package: String, _ conversation: String?, installed: Set<String>, canOpenURLs: Bool = true) -> NotificationTapTarget {
        resolveNotificationTapTarget(
            androidPackage: package,
            conversationID: conversation,
            routes: notificationTapRoutesPoc,
            installedApplication: { installed.contains($0) },
            canOpen: { _, _ in canOpenURLs }
        )
    }

    func testWhatsAppPhoneJIDResolvesToAConversationURL() {
        XCTAssertEqual(whatsAppConversationURL(conversationID: "420777123456@s.whatsapp.net")?.absoluteString, "whatsapp://send?phone=420777123456")
    }

    func testWhatsAppLidAndGroupIdsCannotBeMappedToAChat() {
        XCTAssertNil(whatsAppConversationURL(conversationID: "112575488491769@lid"))
        XCTAssertNil(whatsAppConversationURL(conversationID: "120363000000000000@g.us"))
        XCTAssertNil(whatsAppConversationURL(conversationID: "not-a-jid"))
        XCTAssertNil(whatsAppConversationURL(conversationID: "12a4@s.whatsapp.net"))
    }

    func testInstalledWhatsAppWithMappableChatOpensTheChat() {
        XCTAssertEqual(
            resolve("com.whatsapp", "420777123456@s.whatsapp.net", installed: ["net.whatsapp.WhatsApp"]),
            .url(URL(string: "whatsapp://send?phone=420777123456")!, appBundleIdentifier: "net.whatsapp.WhatsApp")
        )
    }

    func testInstalledWhatsAppWithoutMappableChatOpensTheApp() {
        XCTAssertEqual(resolve("com.whatsapp", "112575488491769@lid", installed: ["net.whatsapp.WhatsApp"]),
                       .application(bundleIdentifier: "net.whatsapp.WhatsApp"))
        XCTAssertEqual(resolve("com.whatsapp", nil, installed: ["net.whatsapp.WhatsApp"]),
                       .application(bundleIdentifier: "net.whatsapp.WhatsApp"))
    }

    func testAChatURLTheAppCannotOpenFallsBackToTheApp() {
        XCTAssertEqual(resolve("com.whatsapp", "420777123456@s.whatsapp.net", installed: ["net.whatsapp.WhatsApp"], canOpenURLs: false),
                       .application(bundleIdentifier: "net.whatsapp.WhatsApp"))
    }

    func testNotInstalledOrUnknownAppKeepsTheExistingBehaviour() {
        XCTAssertEqual(resolve("com.whatsapp", "420777123456@s.whatsapp.net", installed: []), .none)
        XCTAssertEqual(resolve("com.emclient.mailclient", nil, installed: ["net.whatsapp.WhatsApp"]), .none)
    }

    func testAlternativeBundleIdentifiersAreTriedInOrder() {
        XCTAssertEqual(resolve("com.whatsapp", nil, installed: ["desktop.WhatsApp"]), .application(bundleIdentifier: "desktop.WhatsApp"))
    }

    func testConversationIdDecodesAndIsOptional() throws {
        let base = #"{"packageName":"p","applicationName":"A","notificationId":"n","title":"t","text":"x","timestamp":1"#
        XCTAssertNil(try JSONDecoder().decode(RemoteNotificationPayload.self, from: Data((base + "}").utf8)).conversationId)
        XCTAssertEqual(try JSONDecoder().decode(RemoteNotificationPayload.self, from: Data((base + #","conversationId":"x@lid"}"#).utf8)).conversationId, "x@lid")
    }
}
