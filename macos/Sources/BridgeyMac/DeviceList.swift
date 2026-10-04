import Foundation

// DEVICE LIST (MD-4, UI layer)
//
// A read-only presentation of the device directory plus the user's selection. It never stores
// devices of its own (the directory is the source), never knows a feature, and never stands in for
// routing: `selectedDeviceID` only says which device a user action should target.

struct DeviceListItem: Equatable, Identifiable {
    let deviceID: String
    let name: String
    let platform: DevicePlatform
    let kind: DeviceKind
    let isConnected: Bool

    var id: String { deviceID }

    /// "Android · Phone", "macOS · Computer"; empty when nothing is known.
    var detail: String {
        [platformLabel, kindLabel].compactMap { $0 }.joined(separator: " · ")
    }

    var systemImage: String {
        switch kind {
        case .phone: "smartphone"
        case .tablet: "ipad.landscape"
        case .computer: "laptopcomputer"
        case .unknown: "display"
        }
    }

    private var platformLabel: String? {
        switch platform {
        case .android: "Android"
        case .macos: "macOS"
        case .unknown: nil
        }
    }

    private var kindLabel: String? {
        switch kind {
        case .phone: "Phone"
        case .tablet: "Tablet"
        case .computer: "Computer"
        case .unknown: nil
        }
    }
}

/// Which device a feature action targets. Always an explicit device, never "the routed one".
enum DeviceTarget: Equatable {
    case device(String)
    /// Several eligible devices and no eligible selection: the user picks one.
    case choose([String])
    case none
}

enum DeviceList {
    /// Connected devices first, then the directory's stable order (name, then deviceId).
    static func items(_ entries: [DeviceDirectoryEntry], connectedOnly: Bool = true) -> [DeviceListItem] {
        let items = entries.map {
            DeviceListItem(deviceID: $0.deviceID, name: $0.name, platform: $0.platform, kind: $0.kind,
                           isConnected: $0.connection == .connected)
        }
        let filtered = connectedOnly ? items.filter(\.isConnected) : items
        return filtered.enumerated()
            .sorted { ($0.element.isConnected ? 0 : 1, $0.offset) < ($1.element.isConnected ? 0 : 1, $1.offset) }
            .map(\.element)
    }

    /// Keeps a selection only while that device is still listed and connected. When it goes away,
    /// the only remaining connected device is selected (unambiguous); otherwise nothing is.
    static func reconcile(selected: String?, items: [DeviceListItem]) -> String? {
        let connected = items.filter(\.isConnected)
        if let selected, connected.contains(where: { $0.deviceID == selected }) { return selected }
        return connected.count == 1 ? connected[0].deviceID : nil
    }

    /// The target of an action among `eligible` devices (already filtered by applicability): the
    /// selection if it is eligible, else the only eligible device, else the user chooses.
    static func target(selected: String?, eligible: [String]) -> DeviceTarget {
        if let selected, eligible.contains(selected) { return .device(selected) }
        if eligible.count == 1 { return .device(eligible[0]) }
        return eligible.isEmpty ? .none : .choose(eligible)
    }
}

/// The selected peer as the UI shows it, kept apart from the legacy routed peer (MD-4b).
/// Peer-centric: no device is "main"; the routed peer only matters to features that still use
/// the single-peer compatibility routing.
struct SelectedDeviceContext: Equatable {
    /// The peer the user is looking at; nil when nothing is selected.
    let selected: DeviceListItem?
    /// The connected peer legacy single-peer features currently use (`preferredDeviceID` routing).
    let routed: DeviceListItem?

    /// Legacy feature state (telemetry, media, calls, files, clipboard, links, screen share) belongs
    /// to the routed peer. It may be shown under the selected peer only when they are the same peer.
    var legacyFeaturesApply: Bool {
        guard let selected, let routed else { return false }
        return selected.deviceID == routed.deviceID
    }

    /// The routed peer when it is a different peer than the selected one (to explain where legacy
    /// features currently go), nil otherwise.
    var legacyFeaturesUseOtherPeer: DeviceListItem? {
        guard let routed, routed.deviceID != selected?.deviceID else { return nil }
        return routed
    }

    static func make(items: [DeviceListItem], selectedDeviceID: String?, routedDeviceID: String?) -> SelectedDeviceContext {
        SelectedDeviceContext(
            selected: items.first { $0.deviceID == selectedDeviceID && $0.isConnected },
            routed: items.first { $0.deviceID == routedDeviceID && $0.isConnected }
        )
    }
}

/// UI state only: the device the user selected in the device list. Not a session, not routing.
final class DeviceSelection: ObservableObject {
    @Published var selectedDeviceID: String?

    func reconcile(with items: [DeviceListItem]) {
        let next = DeviceList.reconcile(selected: selectedDeviceID, items: items)
        if next != selectedDeviceID { selectedDeviceID = next }
    }
}
