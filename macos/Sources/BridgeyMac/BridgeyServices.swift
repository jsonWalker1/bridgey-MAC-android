import AppKit

/// The app's single NSServices provider (`NSApplication.servicesProvider`): each service declared in
/// Info.plist is forwarded to the feature that owns it.
@MainActor
final class BridgeyServices: NSObject {
    private let call: CallServiceProvider
    private let files: FileSendServiceProvider

    init(call: CallServiceProvider, files: FileSendServiceProvider) {
        self.call = call
        self.files = files
    }

    /// "Call with Bridgey" (calls feature).
    @objc func callWithBridgey(_ pasteboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        call.callWithBridgey(pasteboard, userData: userData, error: error)
    }

    /// "Send to Bridgey…" from Finder (files feature, MD-6).
    @objc func sendToBridgey(_ pasteboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        files.sendToBridgey(pasteboard, userData: userData, error: error)
    }
}
