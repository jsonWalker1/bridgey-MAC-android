import AppKit

// FINDER "SEND TO BRIDGEY…" (macOS Service)
//
// Finder → select files → right-click → Services → "Send to Bridgey…". An NSServices entry in the
// app's Info.plist; the request is handled inside the running Bridgey process (macOS launches it
// when needed), so the destinations are this Mac's own eligible peers and every file goes through
// the ordinary MD-6 path (`PairingCoordinator.sendFile(_:to:)`): explicit deviceId, the same
// applicability/authorization checks, per-device transfer state. No extension, listener or protocol.
//
// Registration: macOS reads services from the installed app bundle (/Applications), keyed by bundle
// id - a build run from elsewhere does not change what Finder offers.

/// Pure decisions of the service, kept testable.
enum FileSendRequest {
    struct Destination: Equatable {
        let deviceID: String
        let name: String
        let detail: String
    }

    /// What the service shows once it knows the files and the eligible peers.
    enum Plan: Equatable {
        /// No regular, readable file in the selection.
        case nothingToSend
        /// No connected peer may receive files from this Mac.
        case noDestination
        /// Exactly one eligible peer: a one-step confirmation naming it.
        case confirm(Destination)
        /// Several eligible peers: the user must pick one explicitly.
        case choose([Destination])
    }

    /// Sendable regular files versus everything else (folders, non-file or unreadable URLs).
    static func partition(_ urls: [URL], isRegularReadableFile: (URL) -> Bool) -> (files: [URL], rejected: [URL]) {
        var files: [URL] = []
        var rejected: [URL] = []
        for url in urls {
            if url.isFileURL && isRegularReadableFile(url) { files.append(url) } else { rejected.append(url) }
        }
        return (files, rejected)
    }

    /// The peers the user can choose from: exactly the eligible connected peers, in their order.
    static func destinations(_ eligible: [DeviceDirectoryEntry]) -> [Destination] {
        eligible.map { Destination(deviceID: $0.deviceID, name: $0.name, detail: "\(platformName($0.platform)) · Connected") }
    }

    static func plan(files: [URL], destinations: [Destination]) -> Plan {
        guard !files.isEmpty else { return .nothingToSend }
        switch destinations.count {
        case 0: return .noDestination
        case 1: return .confirm(destinations[0])
        default: return .choose(destinations)
        }
    }

    enum Outcome: Equatable {
        /// Every file was handed to the transfer path for `deviceID`; `refused` lists the files it
        /// refused (e.g. the peer's grant changed) by name.
        case sent(deviceID: String, count: Int, refused: [String])
        /// The chosen peer is no longer eligible (disconnected, or no longer authorized): nothing is
        /// sent, and no other peer is used.
        case targetUnavailable(name: String)
    }

    /// Sends to the chosen peer only while it is still eligible, one transfer per file through
    /// `sendFile` (the existing MD-6 path). Never another peer.
    static func send(
        _ files: [URL],
        to chosen: Destination,
        eligibleNow: [String],
        sendFile: (URL, String) -> Bool
    ) -> Outcome {
        guard eligibleNow.contains(chosen.deviceID) else { return .targetUnavailable(name: chosen.name) }
        let refused = files.filter { !sendFile($0, chosen.deviceID) }.map(\.lastPathComponent)
        return .sent(deviceID: chosen.deviceID, count: files.count - refused.count, refused: refused)
    }

    private static func platformName(_ platform: DevicePlatform) -> String {
        switch platform {
        case .android: "Android"
        case .macos: "macOS"
        case .unknown: "Device"
        }
    }
}

/// The NSServices handler for "Send to Bridgey…": reads the file URLs synchronously, returns to
/// Finder, then asks for the destination in a native alert.
@MainActor
final class FileSendServiceProvider: NSObject {
    private weak var pairing: PairingCoordinator?
    private let onOpenSettings: () -> Void
    /// A Bridgey that macOS just launched for the service needs a moment for its peers to connect.
    private static let peerWait: TimeInterval = 8

    init(pairing: PairingCoordinator, onOpenSettings: @escaping () -> Void) {
        self.pairing = pairing
        self.onOpenSettings = onOpenSettings
    }

    @objc func sendToBridgey(
        _ pasteboard: NSPasteboard,
        userData: String,
        error: AutoreleasingUnsafeMutablePointer<NSString?>
    ) {
        let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        let request = FileSendRequest.partition(urls, isRegularReadableFile: Self.isRegularReadableFile)
        NSLog("FILES service request files=%d skipped=%d", request.files.count, request.rejected.count)
        guard !request.files.isEmpty else {
            error.pointee = "Bridgey sends files only, not folders or unreadable items."
            return
        }
        DispatchQueue.main.async { [weak self] in
            self?.present(request.files, rejected: request.rejected, deadline: Date().addingTimeInterval(Self.peerWait))
        }
    }

    private static func isRegularReadableFile(_ url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true
            && FileManager.default.isReadableFile(atPath: url.path)
    }

    private func eligible() -> [DeviceDirectoryEntry] {
        pairing?.targets(for: .files) ?? []
    }

    private func present(_ files: [URL], rejected: [URL], deadline: Date) {
        let plan = FileSendRequest.plan(files: files, destinations: FileSendRequest.destinations(eligible()))
        if plan == .noDestination && Date() < deadline {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                self?.present(files, rejected: rejected, deadline: deadline)
            }
            return
        }
        NSApp.activate(ignoringOtherApps: true)
        let subject = files.count == 1 ? files[0].lastPathComponent : "\(files.count) files"
        switch plan {
        case .nothingToSend:
            return
        case .noDestination:
            let alert = NSAlert()
            alert.messageText = "No device can receive files"
            alert.informativeText = "Bridgey is not connected to a device that accepts files from this Mac. Open Bridgey on the other device and make sure both are on the same network, or check the device's File transfer setting. Nothing was sent."
            alert.addButton(withTitle: "OK")
            alert.addButton(withTitle: "Open Bridgey Settings")
            if alert.runModal() == .alertSecondButtonReturn { onOpenSettings() }
        case let .confirm(destination):
            let alert = NSAlert()
            alert.messageText = "Send \(subject) to \(destination.name)?"
            alert.informativeText = destination.detail
            alert.addButton(withTitle: "Send") // default button: Return sends
            alert.addButton(withTitle: "Cancel")
            guard alert.runModal() == .alertFirstButtonReturn else { return }
            finish(files, to: destination, rejected: rejected)
        case let .choose(destinations):
            let alert = NSAlert()
            alert.messageText = "Send \(subject) to:"
            let picker = NSPopUpButton(frame: NSRect(x: 0, y: 0, width: 320, height: 26), pullsDown: false)
            picker.addItem(withTitle: "Choose a device…")
            destinations.forEach { picker.addItem(withTitle: "\($0.name) — \($0.detail)") }
            let sendButton = alert.addButton(withTitle: "Send")
            alert.addButton(withTitle: "Cancel")
            sendButton.isEnabled = false // explicit choice required
            let choice = PopUpChoice { [weak picker, weak sendButton] in
                sendButton?.isEnabled = (picker?.indexOfSelectedItem ?? 0) > 0
            }
            picker.target = choice
            picker.action = #selector(PopUpChoice.changed(_:))
            alert.accessoryView = picker
            let response = withExtendedLifetime(choice) { alert.runModal() }
            let index = picker.indexOfSelectedItem - 1
            guard response == .alertFirstButtonReturn, destinations.indices.contains(index) else { return }
            finish(files, to: destinations[index], rejected: rejected)
        }
    }

    private func finish(_ files: [URL], to destination: FileSendRequest.Destination, rejected: [URL]) {
        guard let pairing else { return }
        let outcome = FileSendRequest.send(files, to: destination, eligibleNow: eligible().map(\.deviceID)) {
            pairing.sendFile($0, to: $1)
        }
        switch outcome {
        case let .targetUnavailable(name):
            inform("\(name) is no longer available", "\(name) disconnected or no longer accepts files from this Mac. Nothing was sent.")
        case let .sent(_, _, refused):
            let skipped = rejected.map(\.lastPathComponent) + refused
            if !skipped.isEmpty {
                inform("Some items were not sent", "Not sent: \(skipped.joined(separator: ", "))")
            }
        }
    }

    private func inform(_ title: String, _ detail: String) {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = detail
        alert.addButton(withTitle: "OK")
        alert.runModal()
    }
}

/// Target for the destination pop-up while the modal alert runs.
private final class PopUpChoice: NSObject {
    private let onChange: () -> Void
    init(onChange: @escaping () -> Void) { self.onChange = onChange }
    @objc func changed(_ sender: NSPopUpButton) { onChange() }
}
