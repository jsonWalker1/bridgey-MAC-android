import AppKit
import Foundation

func phoneNumberFromTelURL(_ value: String) -> String? {
    guard value.lowercased().hasPrefix("tel:") else { return nil }
    var number = String(value.dropFirst(4))
    if number.hasPrefix("//") { number.removeFirst(2) }
    guard let decoded = number.removingPercentEncoding else { return nil }
    return normalizedPhoneNumber(decoded)
}

final class PendingPhoneCallRouter {
    private var onCall: ((String) -> Void)?
    private var pendingNumbers: [String] = []

    func configure(onCall: @escaping (String) -> Void) {
        self.onCall = onCall
        let queued = pendingNumbers
        pendingNumbers.removeAll()
        queued.forEach(onCall)
    }

    func receive(_ value: String) {
        guard let number = phoneNumberFromTelURL(value) else { return }
        if let onCall {
            onCall(number)
        } else if pendingNumbers.count < 8 {
            pendingNumbers.append(number)
        }
    }
}

@MainActor
final class PhoneURLHandler: NSObject, NSApplicationDelegate {
    private let router = PendingPhoneCallRouter()

    func configure(onCall: @escaping (String) -> Void) {
        router.configure(onCall: onCall)
    }

    func application(_: NSApplication, open urls: [URL]) {
        urls.forEach { router.receive($0.absoluteString) }
    }

    // Single-instance enforcement: two BridgeyMac processes (e.g. one launched from Xcode/an old
    // build output, one from /Applications) both listening on the same pairing port and both
    // dialing the phone independently caused real, confusing double-connection behavior in
    // practice - not a hypothetical. applicationWillFinishLaunching runs as early as the app
    // delegate lifecycle allows, before the rest of app startup (Pairing/discovery) does
    // meaningful work, to minimize the window where a duplicate launch could still bind the port.
    func applicationWillFinishLaunching(_ notification: Notification) {
        guard let bundleID = Bundle.main.bundleIdentifier else { return }
        let myPid = ProcessInfo.processInfo.processIdentifier
        let others = NSRunningApplication.runningApplications(withBundleIdentifier: bundleID)
            .filter { $0.processIdentifier != myPid }
        guard let existing = others.first else { return }
        NSLog("Bridgey: another instance (pid %d) is already running - activating it and quitting this one", existing.processIdentifier)
        existing.activate()
        NSApp.terminate(nil)
    }
}
