import AppKit
import Foundation
import ServiceManagement

enum BridgeyFeature: String, CaseIterable, Identifiable {
    case clipboard
    case files
    case notifications
    case battery
    case findDevice = "find_device"
    case ping
    case links
    case media
    case calls
    case photoSync = "photo_sync"

    var id: String { rawValue }
    var title: String {
        switch self {
        case .clipboard: "Clipboard"
        case .files: "File transfer"
        case .notifications: "Android notifications"
        case .battery: "Battery status"
        case .findDevice: "Find Device"
        case .ping: "Ping"
        case .links: "Web links"
        case .media: "Media controls"
        case .calls: "Calls from Mac"
        case .photoSync: "Photo & video sync"
        }
    }
}

func featureEnabledByLegacyPeer(_ feature: BridgeyFeature) -> Bool {
    ![.calls, .ping, .links, .media, .photoSync].contains(feature)
}

enum PhotoSyncDestination: String, CaseIterable, Identifiable {
    case folderAndPhotos = "folder_and_photos"
    case photosOnly = "photos_only"
    case folderOnly = "folder_only"

    var id: String { rawValue }
    var title: String {
        switch self {
        case .folderAndPhotos: "Folder and Photos"
        case .photosOnly: "Photos only"
        case .folderOnly: "Folder only"
        }
    }

    var savesToFolder: Bool { self != .photosOnly }
    var savesToPhotosLibrary: Bool { self != .folderOnly }
}

func effectiveFeatureEnabled(globalEnabled: Bool, deviceEnabled: Bool?) -> Bool {
    globalEnabled && deviceEnabled != false
}

func effectiveFeatureAvailable(localEnabled: Bool, remoteEnabled: Bool) -> Bool {
    localEnabled && remoteEnabled
}

final class ReceiveDirectoryAccess {
    let url: URL
    private let scoped: Bool

    init(url: URL, scoped: Bool) {
        self.url = url
        self.scoped = scoped
    }

    deinit {
        if scoped { url.stopAccessingSecurityScopedResource() }
    }
}

@MainActor
final class BridgeySettings: ObservableObject {
    @Published private(set) var deviceName: String
    @Published private(set) var globalFeatures: [BridgeyFeature: Bool]
    @Published private(set) var deviceFeatures: [String: [BridgeyFeature: Bool]]
    @Published private(set) var receiveFolderPath: String
    @Published private(set) var syncFolderPath: String
    @Published private(set) var syncDestination: PhotoSyncDestination
    @Published private(set) var launchAtLogin = false
    @Published private(set) var loginItemMessage: String?
    @Published private(set) var hasCompletedOnboarding: Bool
    @Published private(set) var notificationHistoryEnabled: Bool

    private let defaults = UserDefaults.standard

    init() {
        let storedDefaults = UserDefaults.standard
        let systemName = Host.current().localizedName ?? "Mac"
        deviceName = storedDefaults.string(forKey: "settings.deviceName") ?? systemName
        hasCompletedOnboarding = storedDefaults.bool(forKey: "settings.onboarding.completed")
        notificationHistoryEnabled = storedDefaults.bool(forKey: "settings.notificationHistory.enabled")
        globalFeatures = Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map {
            ($0, storedDefaults.object(forKey: "settings.global.\($0.rawValue)") as? Bool ?? ![.media, .photoSync].contains($0))
        })
        var perDevice: [String: [BridgeyFeature: Bool]] = [:]
        for (key, value) in storedDefaults.dictionaryRepresentation() where key.hasPrefix("settings.device.") {
            guard let enabled = value as? Bool else { continue }
            for feature in BridgeyFeature.allCases where key.hasSuffix(".\(feature.rawValue)") {
                let id = key
                    .replacingOccurrences(of: "settings.device.", with: "")
                    .replacingOccurrences(of: ".\(feature.rawValue)", with: "")
                if !id.isEmpty { perDevice[id, default: [:]][feature] = enabled }
            }
        }
        deviceFeatures = perDevice
        receiveFolderPath = storedDefaults.string(forKey: "settings.receiveFolderPath")
            ?? FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first!
                .appendingPathComponent("Bridgey", isDirectory: true).path
        syncFolderPath = storedDefaults.string(forKey: "settings.syncFolderPath")
            ?? FileManager.default.urls(for: .picturesDirectory, in: .userDomainMask).first!
                .appendingPathComponent("Bridgey", isDirectory: true).path
        syncDestination = storedDefaults.string(forKey: "settings.syncDestination")
            .flatMap(PhotoSyncDestination.init(rawValue:)) ?? .folderAndPhotos
        refreshLoginItemStatus()
    }

    func setDeviceName(_ value: String) {
        let name = String(value.trimmingCharacters(in: .whitespacesAndNewlines).prefix(64))
        guard !name.isEmpty else { return }
        defaults.set(name, forKey: "settings.deviceName")
        deviceName = name
    }

    func completeOnboarding() {
        defaults.set(true, forKey: "settings.onboarding.completed")
        hasCompletedOnboarding = true
    }

    func setGlobal(_ feature: BridgeyFeature, enabled: Bool) {
        defaults.set(enabled, forKey: "settings.global.\(feature.rawValue)")
        globalFeatures[feature] = enabled
    }

    func setNotificationHistoryEnabled(_ enabled: Bool) {
        defaults.set(enabled, forKey: "settings.notificationHistory.enabled")
        notificationHistoryEnabled = enabled
    }

    func setForDevice(_ deviceID: String, feature: BridgeyFeature, enabled: Bool) {
        defaults.set(enabled, forKey: "settings.device.\(deviceID).\(feature.rawValue)")
        deviceFeatures[deviceID, default: [:]][feature] = enabled
    }

    func isEnabled(_ feature: BridgeyFeature, for deviceID: String?) -> Bool {
        effectiveFeatureEnabled(
            globalEnabled: globalFeatures[feature] != false,
            deviceEnabled: deviceID.flatMap { deviceFeatures[$0]?[feature] }
        )
    }

    func removeDevice(_ deviceID: String) {
        for feature in BridgeyFeature.allCases {
            defaults.removeObject(forKey: "settings.device.\(deviceID).\(feature.rawValue)")
        }
        deviceFeatures.removeValue(forKey: deviceID)
    }

    func chooseReceiveFolder() {
        let panel = NSOpenPanel()
        panel.title = "Choose where Bridgey saves received files"
        panel.prompt = "Choose"
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        panel.directoryURL = URL(fileURLWithPath: receiveFolderPath, isDirectory: true)
        guard panel.runModal() == .OK, let url = panel.url else { return }
        defaults.set(url.path, forKey: "settings.receiveFolderPath")
        if let bookmark = try? url.bookmarkData(options: .withSecurityScope) {
            defaults.set(bookmark, forKey: "settings.receiveFolderBookmark")
        }
        receiveFolderPath = url.path
    }

    func receiveDirectoryAccess() -> ReceiveDirectoryAccess {
        if let data = defaults.data(forKey: "settings.receiveFolderBookmark") {
            var stale = false
            if let url = try? URL(
                resolvingBookmarkData: data,
                options: .withSecurityScope,
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            ) {
                let scoped = url.startAccessingSecurityScopedResource()
                if stale, let refreshed = try? url.bookmarkData(options: .withSecurityScope) {
                    defaults.set(refreshed, forKey: "settings.receiveFolderBookmark")
                }
                return ReceiveDirectoryAccess(url: url, scoped: scoped)
            }
        }
        return ReceiveDirectoryAccess(url: URL(fileURLWithPath: receiveFolderPath, isDirectory: true), scoped: false)
    }

    func chooseSyncFolder() {
        let panel = NSOpenPanel()
        panel.title = "Choose where Bridgey saves synced photos & videos"
        panel.prompt = "Choose"
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = false
        panel.directoryURL = URL(fileURLWithPath: syncFolderPath, isDirectory: true)
        guard panel.runModal() == .OK, let url = panel.url else { return }
        defaults.set(url.path, forKey: "settings.syncFolderPath")
        if let bookmark = try? url.bookmarkData(options: .withSecurityScope) {
            defaults.set(bookmark, forKey: "settings.syncFolderBookmark")
        }
        syncFolderPath = url.path
    }

    func setSyncDestination(_ value: PhotoSyncDestination) {
        defaults.set(value.rawValue, forKey: "settings.syncDestination")
        syncDestination = value
    }

    func syncDirectoryAccess() -> ReceiveDirectoryAccess {
        if let data = defaults.data(forKey: "settings.syncFolderBookmark") {
            var stale = false
            if let url = try? URL(
                resolvingBookmarkData: data,
                options: .withSecurityScope,
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            ) {
                let scoped = url.startAccessingSecurityScopedResource()
                if stale, let refreshed = try? url.bookmarkData(options: .withSecurityScope) {
                    defaults.set(refreshed, forKey: "settings.syncFolderBookmark")
                }
                return ReceiveDirectoryAccess(url: url, scoped: scoped)
            }
        }
        return ReceiveDirectoryAccess(url: URL(fileURLWithPath: syncFolderPath, isDirectory: true), scoped: false)
    }

    /// A flat, append-only list of already-synced asset keys living next to the synced files
    /// themselves — the backstop dedup check so a Mac never re-saves an asset it already has,
    /// even if Android's own local ledger were ever lost (reinstall, data clear).
    private func syncIndexURL(directory: URL) -> URL {
        directory.appendingPathComponent(".bridgey-sync-index")
    }

    func isAssetSynced(_ assetKey: String, directory: URL) -> Bool {
        syncedAssetKeys(directory: directory).contains(assetKey)
    }

    func markAssetSynced(_ assetKey: String, directory: URL) {
        let url = syncIndexURL(directory: directory)
        guard let data = (assetKey + "\n").data(using: .utf8) else { return }
        if let handle = try? FileHandle(forWritingTo: url) {
            defer { try? handle.close() }
            handle.seekToEndOfFile()
            try? handle.write(contentsOf: data)
        } else {
            try? data.write(to: url)
        }
    }

    private func syncedAssetKeys(directory: URL) -> Set<String> {
        guard let contents = try? String(contentsOf: syncIndexURL(directory: directory), encoding: .utf8) else { return [] }
        return Set(contents.split(separator: "\n").map(String.init))
    }

    func setLaunchAtLogin(_ enabled: Bool) {
        do {
            if enabled { try SMAppService.mainApp.register() }
            else { try SMAppService.mainApp.unregister() }
            loginItemMessage = nil
        } catch {
            loginItemMessage = error.localizedDescription
        }
        refreshLoginItemStatus()
    }

    func refreshLoginItemStatus() {
        launchAtLogin = SMAppService.mainApp.status == .enabled
    }
}
