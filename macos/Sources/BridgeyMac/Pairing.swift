import AppKit
import Combine
import CryptoKit
import Foundation
import Network
import Security
import Carbon
import UserNotifications
import UniformTypeIdentifiers

enum PairingState: Equatable {
    case idle
    case connecting(String)
    case verification(peerName: String, code: String)
    case connected(deviceID: String, peerName: String)
    case failed(String)
}

struct RemoteBatteryStatus: Equatable {
    let level: Int
    let isCharging: Bool
}

struct RemoteStorageStatus: Equatable {
    let usedBytes: Int64
    let totalBytes: Int64
}

struct RemoteMemoryStatus: Equatable {
    let usedBytes: Int64
    let totalBytes: Int64
}

enum RemoteCpuStatus: Equatable {
    case available(Int)
    case unavailable
}

enum RemoteTemperatureStatus: Equatable {
    case known(thermalState: String, celsius: Int?)
    case unavailable
}

// RemoteCallAction / RemoteCallStatus / CallRequestPayload live in Calls.swift.

struct FileTransferRow: Identifiable, Equatable {
    let id: String
    let name: String
    let status: String
    let active: Bool
    let startedAt: Date
    let retryable: Bool

    init(
        id: String,
        name: String,
        status: String,
        active: Bool,
        startedAt: Date = Date(),
        retryable: Bool = false
    ) {
        self.id = id
        self.name = name
        self.status = status
        self.active = active
        self.startedAt = startedAt
        self.retryable = retryable
    }
}

struct TrustedDeviceInfo: Identifiable, Equatable {
    let id: String
    let name: String
}

private struct BatteryPayload: Codable {
    let level: Int
    let isCharging: Bool
}

private struct TelemetryPayload: Codable {
    let version: Int
    var storageUsedBytes: Int64?
    var storageTotalBytes: Int64?
    var memoryUsedBytes: Int64?
    var memoryTotalBytes: Int64?
    var cpuPercent: Int?
    var cpuUnavailable: Bool?
    var thermalState: String?
    var temperatureCelsius: Int?
    var temperatureUnavailable: Bool?

    init(
        version: Int,
        storageUsedBytes: Int64? = nil,
        storageTotalBytes: Int64? = nil,
        memoryUsedBytes: Int64? = nil,
        memoryTotalBytes: Int64? = nil,
        cpuPercent: Int? = nil,
        cpuUnavailable: Bool? = nil,
        thermalState: String? = nil,
        temperatureCelsius: Int? = nil,
        temperatureUnavailable: Bool? = nil
    ) {
        self.version = version
        self.storageUsedBytes = storageUsedBytes
        self.storageTotalBytes = storageTotalBytes
        self.memoryUsedBytes = memoryUsedBytes
        self.memoryTotalBytes = memoryTotalBytes
        self.cpuPercent = cpuPercent
        self.cpuUnavailable = cpuUnavailable
        self.thermalState = thermalState
        self.temperatureCelsius = temperatureCelsius
        self.temperatureUnavailable = temperatureUnavailable
    }
}

struct RemoteNotificationPayload: Codable {
    let packageName: String
    let applicationName: String
    let notificationId: String
    let title: String
    let text: String
    let timestamp: Int64
    let actions: [RemoteNotificationActionPayload]?
    let applicationIcon: String?
    let callType: String?
    let hasSound: Bool?
    let availableAudioRoutes: [String]?
    let bluetoothRouteName: String?
    /// BRIDGEY NOTIFICATION++ RECONCILIATION: `true` = a re-post of an existing notification
    /// (reconnect/reconciliation) - replace it silently, no banner and no sound.
    let resync: Bool?
    /// TAP ROUTING POC: Android `Notification.shortcutId` (conversation id), when the app sets one.
    let conversationId: String?
}

struct RemoteNotificationActionPayload: Codable {
    let actionToken: String
    let title: String
    let allowsReply: Bool
}

private struct NotificationReferencePayload: Codable {
    let notificationId: String
}

private struct NotificationActionCommandPayload: Codable {
    let notificationId: String
    let actionToken: String
    let replyText: String?
    let route: String?
}

private struct FileOfferPayload: Codable {
    let transferId: String
    let name: String
    let mimeType: String
    let size: Int64
    let sha256: String
    var assetKey: String? = nil
}

private struct FileCompletePayload: Codable {
    let transferId: String
    let sha256: String
}

private struct FindDevicePayload: Codable {
    let alertId: String
}

private struct PingPayload: Codable {
    let version: Int
}

private struct RemoteScreenSharePayload: Codable {
    let version: Int
}

/// BRIDGEY KVM KEYBOARD V1 (switch shortcut): fire-and-forget, no consent/negotiation needed (unlike
/// Remote Screen Share) - an old Android peer that doesn't understand "kvm.switchKeyboard" simply
/// ignores the unknown `kind`, the same backward-compatible fallback every other command kind gets.
private struct SwitchKeyboardPayload: Codable {
    let version: Int
}

private struct FeatureStatePayload: Codable {
    let version: Int
    let features: [String: Bool]
}

private func defaultRemoteFeatureState() -> [BridgeyFeature: Bool] {
    Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map { ($0, featureEnabledByLegacyPeer($0)) })
}

private final class NotificationPresenter: NSObject, UNUserNotificationCenterDelegate {
    var onDismiss: ((String, String) -> Void)?
    var onAction: ((String, String, String, String?) -> Void)?
    /// Plain click on the notification body: (notificationID, deviceID, content).
    var onOpen: ((String, String, UNNotificationContent) -> Void)?

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // A reconciliation re-post only refreshes Notification Center; it must never re-alert.
        if notification.request.content.userInfo["resync"] as? Bool == true {
            completionHandler([.list])
        } else {
            completionHandler([.banner, .sound])
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        defer { completionHandler() }
        guard let notificationID = response.notification.request.content.userInfo["androidNotificationId"] as? String,
              let deviceID = response.notification.request.content.userInfo["androidDeviceId"] as? String else { return }
        if response.actionIdentifier == UNNotificationDismissActionIdentifier {
            onDismiss?(notificationID, deviceID)
        } else if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
            // Routed by NotificationActionRouting (per-app rules configured in Settings).
            onOpen?(notificationID, deviceID, response.notification.request.content)
        } else if response.actionIdentifier != UNNotificationDefaultActionIdentifier {
            let replyText = (response as? UNTextInputNotificationResponse)?.userText
            onAction?(notificationID, deviceID, response.actionIdentifier, replyText)
        }
    }
}

@MainActor
final class PairingCoordinator: ObservableObject {
    @Published private(set) var state: PairingState = .idle
    @Published private(set) var trustedDeviceIDs: Set<String>
    @Published private(set) var clipboardStatus: String? = nil
    @Published private(set) var remoteBattery: RemoteBatteryStatus? = nil
    @Published private(set) var remoteStorage: RemoteStorageStatus? = nil
    @Published private(set) var remoteMemory: RemoteMemoryStatus? = nil
    @Published private(set) var remoteCpu: RemoteCpuStatus? = nil
    @Published private(set) var remoteTemperature: RemoteTemperatureStatus? = nil
    @Published private(set) var notificationsAuthorized = false
    @Published private(set) var notificationPermissionDetermined = false
    @Published private(set) var fileTransferStatus: String? = nil
    @Published private(set) var fileTransferActive = false
    @Published private(set) var fileTransfers: [String: FileTransferRow] = [:]
    @Published private(set) var macRinging = false
    @Published private(set) var androidRinging = false
    @Published private(set) var remoteFeatures = defaultRemoteFeatureState()
    @Published private(set) var notificationHistory: [NotificationHistoryItem] = []
    @Published var remoteCall: RemoteCallStatus?
    @Published private(set) var callStatus: String?
    @Published private(set) var pingStatus: String?
    let quickActions = QuickActions()
    let mediaController = MediaController()
    let mediaRemote = MediaRemoteController()
    let shortcuts = ShortcutSettings()
    let notificationActions = NotificationActionSettings()
    // M1: transport/security/lifecycle foundation only - no BridgeyFeature gate yet (that's M3), no
    // encoder/decoder/KVM consumer wired up yet (M2/M4/M5).
    lazy var videoChannel = VideoChannelController(
        available: { [weak self] in
            guard let self else { return false }
            if case .connected = self.state { return true }
            return false
        },
        send: { [weak self] kind, payload in self?.sendQuickPayload(kind: kind, payload: payload) == true },
        pairingKeyProvider: { [weak self] in self?.activeSession?.pairingKey },
        sessionIdProvider: { [weak self] in self?.activeSession?.id },
        remoteHostProvider: { [weak self] in self?.activeSession?.remoteHost }
    )
    // M2: adapts the verified ~/screen-poc-mac decode/display pipeline onto the video channel above.
    let screenStreamDecoder = ScreenStreamDecoder()

    var trustedDevices: [TrustedDeviceInfo] {
        registry.devices.map { device in
            TrustedDeviceInfo(id: device.id, name: device.name)
        }.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    private let deviceID: String
    private var deviceName: String
    private let settings: BridgeySettings
    private let identity: MacIdentity
    // MULTI-DEVICE CORE (DeviceCore.swift): trust + presence per deviceId and one independent
    // PeerSession per device. Nothing below the routing seam is limited to a single peer.
    private let registry: DeviceRegistry
    private let peers: PeerSessionManager<Session>
    private var listener: NWListener?
    /// COMPATIBILITY SEAM. Today's features are single-peer, so they use `activeSession`: the session
    /// of the routed device (`DeviceRouting.activePeer`). This is routing only - inactive sessions
    /// stay connected and authenticated and keep their own capabilities. Migrating a feature to
    /// multi-device means replacing its `activeSession` with `peers.session(for: deviceID)`.
    private(set) var activePeerID: String?
    private var activeSession: Session? { activePeerID.flatMap { peers.session(for: $0) } }
    private var failureMessage: String?
    private var discoveryCancellable: AnyCancellable?
    private var settingsCancellable: AnyCancellable?
    private var routingCancellable: AnyCancellable?
    private var reconnectWork: [String: DispatchWorkItem] = [:]
    private var reconnectAttempts: [String: Int] = [:]
    private var lastEndpoints: [String: (host: String, port: Int, name: String)] = [:]
    private var callRequestID: String?
    private var callTimeoutWorkItem: DispatchWorkItem?
    private var callStatusClearWorkItem: DispatchWorkItem?
    private var pingRequestID: String?
    private var pingStatusClearWorkItem: DispatchWorkItem?
    private var pendingCallNumber: String?
    private var pendingCallExpiryWorkItem: DispatchWorkItem?
    private var remoteFeatureStateReceived = false
    private var clipboardSendID: String?
    /// BRIDGEY KVM COPY/PASTE INTEGRATION: lets a caller of `sendClipboard(completion:)` (namely
    /// Command+V during KVM - see `onPasteRequested`) know once the sync has definitely either
    /// succeeded or failed, so it can safely follow up with a KEY(Ctrl+V) forward to Android without
    /// racing the clipboard update across the separate control/input TCP channels. `nil` for the
    /// existing manual-trigger call sites (menu button, global hotkey), which don't need to know.
    private var clipboardCompletion: ((Bool) -> Void)?
    private var clipboardTimeoutWorkItem: DispatchWorkItem?
    private let notificationPresenter = NotificationPresenter()
    private var remoteNotificationCategories: [String: UNNotificationCategory] = [:]
    // BRIDGEY NOTIFICATION++ SOUND POLISH: highest Android postTime already delivered per logical
    // notification identity - see shouldPlayNotificationSound's doc comment for why this is keyed
    // by timestamp rather than a simple "already posted" flag.
    private var lastNotificationSoundTimestamp: [String: Int64] = [:]
    private let notificationSyncAssembler = NotificationSyncAssembler()
    // When each Bridgey request identifier was last (re)posted - lets a sync skip a notification
    // posted AFTER the snapshot was applied (getDeliveredNotifications is asynchronous, so such a
    // newer post could otherwise show up in the delivered list and be removed by an older snapshot).
    private var notificationPostedAt: [String: Date] = [:]
    private var incomingFiles: [String: IncomingFileTransfer] = [:]
    private var incomingSyncAssets: [String: (assetKey: String, isVideo: Bool)] = [:]
    private var outgoingFiles: [String: OutgoingFileTransfer] = [:]
    private var outgoingFileSources: [String: URL] = [:]
    private var fileOperationID: UUID?
    private var filePreparationCancellation: FileCancellationToken?
    private var fileTransferWindow: FileTransferWindowController?
    private var screenShareWindow: ScreenShareWindowController?
    private var fileDropWindow: FileDropWindowController?
    // Accessed from the call-domain extension in Calls.swift, hence not `private`.
    lazy var callOverlayWindow = CallOverlayWindowController(pairing: self)
    var hiddenCallOverlayIdentity: String?
    var audibleCallIdentity: String?
    private var cancelledTransferIDs = Set<String>()
    private var findDeviceSound: NSSound?
    private var lastSentBattery: LocalBatteryStatus?
    private var lastSentStorage: LocalStorageStatus?
    private var lastSentMemory: LocalMemoryStatus?
    private var previousCpuSample: CpuSample?
    // ALL telemetry (storage/memory/cpu) is on-demand only, battery-conscious: nothing is sampled or
    // sent in the background. Opening the panel subscribes; closing it unsubscribes.
    // While subscribed, the peer resends all three every ~3s over the existing telemetry.update kind.
    private var remoteWantsTelemetryUpdates = false
    private var telemetrySamplingWorkItem: DispatchWorkItem?
    // Whether OUR OWN panel is open wanting the peer's telemetry - survives reconnects (unlike the
    // two fields above, which are per-session) so completeIfConfirmed() can resubscribe automatically.
    private var localWantsRemoteTelemetryUpdates = false
    private let diagnostics = BridgeyDiagnostics()
    private let notificationHistoryStore: NotificationHistoryStore

    init(local: LocalDevice, settings: BridgeySettings) {
        self.deviceID = local.deviceID
        self.deviceName = local.name
        self.settings = settings
        let notificationHistoryStore = NotificationHistoryStore()
        self.notificationHistoryStore = notificationHistoryStore
        if settings.notificationHistoryEnabled {
            notificationHistory = notificationHistoryStore.load()
        }
        identity = local.identity
        let registry = DeviceRegistry(trust: MacTrustRegistry())
        self.registry = registry
        peers = PeerSessionManager(localDeviceID: local.deviceID)
        trustedDeviceIDs = registry.trustedDeviceIDs
        settings.migratePreferredDevice(trustedDeviceIDs: registry.trustedDeviceIDs)
        notificationPresenter.onDismiss = { [weak self] notificationID, deviceID in
            Task { @MainActor [weak self] in
                // Already forwarded as notifications.dismiss - never include it in a Clear All.
                self?.clearAllDetector.noteExplainedRemoval([remoteNotificationRequestIdentifier(deviceID: deviceID, notificationID: notificationID)], at: Date())
                self?.dismissAndroidNotification(notificationID, deviceID: deviceID)
            }
        }
        notificationPresenter.onOpen = { [weak self] notificationID, deviceID, content in
            Task { @MainActor [weak self] in
                self?.handleNotificationClick(notificationID, deviceID: deviceID, content: content)
            }
        }
        notificationPresenter.onAction = { [weak self] notificationID, deviceID, actionToken, replyText in
            Task { @MainActor [weak self] in
                self?.performAndroidNotificationAction(
                    notificationID,
                    deviceID: deviceID,
                    actionToken: actionToken,
                    replyText: replyText
                )
            }
        }
        let remoteNotificationCategory = UNNotificationCategory(
            identifier: "bridgey.android.notification",
            actions: [],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        remoteNotificationCategories[remoteNotificationCategory.identifier] = remoteNotificationCategory
        UNUserNotificationCenter.current().setNotificationCategories(Set(remoteNotificationCategories.values))
        UNUserNotificationCenter.current().delegate = notificationPresenter
        refreshNotificationAuthorization()
        startListener()
        quickActions.available = { [weak self] in self?.isFeatureAvailable($0) == true }
        quickActions.send = { [weak self] in self?.sendQuickPayload(kind: $0, payload: $1) == true }
        mediaController.allowed = { [weak self] in
            guard let self, case .connected = self.state else { return false }
            return self.isFeatureAvailable(.media)
        }
        mediaController.onState = { [weak self] in
            _ = self?.sendQuickPayload(kind: "media.state", payload: $0)
        }
        mediaRemote.allowed = { [weak self] in
            guard let self, case .connected = self.state else { return false }
            return self.isFeatureAvailable(.media)
        }
        mediaRemote.sendAction = { [weak self] action, value, generation in
            self?.sendMediaRemoteAction(action: action, value: value, generation: generation)
        }
        videoChannel.onVideoFrame = { [weak self] frame in
            DispatchQueue.main.async { self?.screenStreamDecoder.handle(frame) }
        }
        screenStreamDecoder.requestKeyframe = { [weak self] in
            _ = self?.videoChannel.sendVideoFrame(
                EncodedVideoFrame(type: VideoFrameType.keyframeRequest, streamId: 0, captureTimestampMs: Int64(Date().timeIntervalSince1970 * 1000), payload: Data()),
                droppable: false
            )
        }
        shortcuts.perform = { [weak self] action in
            switch action {
            case .clipboard: self?.sendClipboard()
            case .call: self?.sendCallFromClipboard()
            case .link: self?.quickActions.sendClipboardLink()
            case .ping: self?.sendPing()
            }
        }
        shortcuts.register()
        settingsCancellable = Publishers.CombineLatest3(
            settings.$globalFeatures,
            settings.$deviceFeatures,
            settings.$notificationHistoryEnabled
        )
            .dropFirst()
            .sink { [weak self] value in
                DispatchQueue.main.async {
                    guard let self else { return }
                    if !self.featureEnabled(.battery) { self.remoteBattery = nil }
                    if !self.featureEnabled(.storage) { self.remoteStorage = nil }
                    if !self.featureEnabled(.memory) { self.resetRemoteMemoryState() }
                    if !self.featureEnabled(.cpu) { self.remoteCpu = nil }
                    if !self.featureEnabled(.temperature) { self.remoteTemperature = nil }
                    if !self.featureEnabled(.ping) { self.clearPingStatus() }
                    if !self.isFeatureAvailable(.links) { self.quickActions.reset() }
                    self.mediaController.reset()
                    self.mediaRemote.reset()
                    self.videoChannel.reset()
                    self.screenStreamDecoder.reset()
                    if !self.featureEnabled(.clipboard) { self.clearClipboardSendStatus() }
                    if !self.featureEnabled(.notifications) { self.clearRemoteCall() }
                    // BRIDGEY NOTIFICATION++ RECONCILIATION: forwarding off (globally or for one
                    // device) = nothing mirrored from that device may stay in Notification Center.
                    // Re-enabling needs nothing here: Android reconciles on the features.update.
                    self.removeAllRemoteNotifications(reason: "feature_off") { [weak self] deviceID in
                        self?.settings.isEnabled(.notifications, for: deviceID) == false
                    }
                    if !self.featureEnabled(.calls) {
                        self.clearPendingCall()
                        self.clearCallStatus()
                    }
                    if value.2 {
                        self.notificationHistory = self.notificationHistoryStore.load()
                    } else {
                        self.clearNotificationHistory()
                    }
                    self.sendFeatureState()
                    self.mediaController.refresh()
                    self.publishLocalBattery(force: true)
                    self.publishLocalStorage(force: true)
                    self.publishLocalMemory(force: true)
                }
            }
        routingCancellable = settings.$preferredDeviceID.combineLatest(settings.$deviceRoutingMode)
            .dropFirst()
            .sink { [weak self] _ in
                DispatchQueue.main.async { self?.recomputeActivePeer() }
            }
    }

    func refreshNotificationAuthorization() {
        let notificationCenter = UNUserNotificationCenter.current()
        notificationCenter.getNotificationSettings { [weak self] settings in
            let authorized = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
            DispatchQueue.main.async {
                self?.notificationsAuthorized = authorized
                self?.notificationPermissionDetermined = settings.authorizationStatus != .notDetermined
            }
        }
    }

    func enableNotifications() {
        let notificationCenter = UNUserNotificationCenter.current()
        notificationCenter.getNotificationSettings { [weak self] settings in
            guard let self else { return }
            if settings.authorizationStatus != .notDetermined {
                DispatchQueue.main.async { self.openNotificationSettings() }
                return
            }
            Task { @MainActor in self.requestNotificationAuthorization() }
        }
    }

    private func requestNotificationAuthorization() {
        let notificationCenter = UNUserNotificationCenter.current()
        notificationCenter.requestAuthorization(options: [.alert, .sound]) { granted, error in
            DispatchQueue.main.async { [weak self] in
                self?.notificationsAuthorized = granted
                self?.notificationPermissionDetermined = true
            }
            if let error {
                NSLog("PLUGIN notifications authorization failed error=%@", String(describing: error))
            } else {
                NSLog("PLUGIN notifications authorization granted=%@", String(granted))
            }
        }
    }

    func openNotificationSettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") else { return }
        NSWorkspace.shared.open(url)
    }

    func observe(_ discovery: BonjourDiscovery) {
        discoveryCancellable = discovery.$peers.sink { [weak self] peers in
            guard let self else { return }
            // Presence is keyed by the advertised deviceId, never by service name/host/address.
            self.registry.updatePresence(DevicePresence.group(peers, localDeviceID: self.deviceID))
            self.connectTrustedPeersIfNeeded()
        }
    }

    /// User action on a discovered device: pairs a new device, or selects a trusted one as the
    /// preferred device for today's single-peer features (dialling it only if it has no session).
    func pair(host: String, port: Int, peerName: String, deviceID peerID: String? = nil) {
        failureMessage = nil
        if let peerID, registry.trustedDeviceIDs.contains(peerID) {
            settings.setPreferredDevice(peerID)
            if peers.isBusy(peerID) {
                refreshState()
                return
            }
        }
        dial(host: host, port: port, peerName: peerName, expectedDeviceID: peerID)
    }

    /// Opens one outgoing session. Never touches any other device's session.
    private func dial(host: String, port: Int, peerName: String, expectedDeviceID: String?) {
        if let expectedDeviceID { reconnectWork.removeValue(forKey: expectedDeviceID)?.cancel() }
        diagnostics.record(category: "pairing", event: "connection_started")
        guard let endpointPort = NWEndpoint.Port(rawValue: UInt16(port)) else {
            failureMessage = "Invalid peer port"
            refreshState()
            return
        }
        if let expectedDeviceID { lastEndpoints[expectedDeviceID] = (host, port, peerName) }
        NSLog("CONNECT attempting %@:%d peer=%@", host, port, peerName)
        let connection = NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: .tcp)
        let current = Session(connection: connection, peerName: peerName)
        current.remoteHost = host
        current.initiatedLocally = true
        current.expectedDeviceID = expectedDeviceID
        current.id = UUID().uuidString.lowercased()
        current.privateKey = P256.KeyAgreement.PrivateKey()
        current.localEphemeralKey = current.privateKey!.publicKey.x963Representation.base64EncodedString()
        peers.addPending(current, initiatedLocally: true, expectedDeviceID: expectedDeviceID)
        scheduleConnectionTimeout(for: current)
        refreshState()
        configure(current) { [weak self, weak current] in
            guard let self, let current, current.privateKey != nil else { return }
            current.send(PairingMessage(
                kind: "pairing.offer",
                sessionId: current.id,
                deviceId: self.deviceID,
                deviceName: self.deviceName,
                publicKey: current.localEphemeralKey
            ))
        }
    }

    func confirm() {
        guard let current = peers.verifyingSession else { return }
        confirm(current)
    }

    private func confirm(_ current: Session) {
        // R1: without a readable identity nothing is presented - never a substitute key.
        guard identity.reloadIfUnavailable(), let identityKey = identity.publicKey,
              let signature = identity.sign(authTranscript(current)) else {
            NSLog("IDENTITY unavailable; closing session instead of presenting an identity")
            failureMessage = "This Mac's identity is temporarily unavailable (Keychain). Try again after unlocking."
            endSession(current, scheduleReconnect: true)
            return
        }
        current.localConfirmed = true
        current.send(PairingMessage(
            kind: "pairing.confirm",
            sessionId: current.id,
            deviceId: deviceID,
            identityKey: identityKey,
            proof: confirmationProof(
                key: current.pairingKey!,
                sessionID: current.id,
                deviceID: deviceID,
                identityKey: identityKey
            ),
            signature: signature
        ))
        completeIfConfirmed(current)
    }

    /// Cancels the pairing being verified and clears a shown failure. Connected devices are not
    /// affected.
    func cancel() {
        failureMessage = nil
        if let verifying = peers.verifyingSession {
            verifying.send(PairingMessage(kind: "pairing.cancel", sessionId: verifying.id))
            endSession(verifying, scheduleReconnect: false)
        }
        refreshState()
    }

    /// Disconnects the active device (the one today's UI shows). Other sessions stay connected.
    func dismiss() {
        failureMessage = nil
        guard let id = activePeerID, let current = activeSession else {
            refreshState()
            return
        }
        reconnectWork.removeValue(forKey: id)?.cancel()
        reconnectAttempts[id] = 0
        endSession(current, scheduleReconnect: false)
    }

    func forget(deviceID: String) {
        registry.forget(deviceID)
        trustedDeviceIDs = registry.trustedDeviceIDs
        settings.removeDevice(deviceID)
        if settings.preferredDeviceID == deviceID { settings.setPreferredDevice(nil) }
        reconnectWork.removeValue(forKey: deviceID)?.cancel()
        reconnectAttempts[deviceID] = nil
        lastEndpoints[deviceID] = nil
        if let current = peers.session(for: deviceID) { endSession(current, scheduleReconnect: false) }
        NSLog("PAIRING revoked peerId=%@", String(deviceID.prefix(8)))
    }

    func updateDeviceName(_ value: String) {
        deviceName = String(value.trimmingCharacters(in: .whitespacesAndNewlines).prefix(64))
    }

    func sendCallFromClipboard() {
        guard let value = NSPasteboard.general.string(forType: .string) else {
            setTransientCallStatus("Copy a phone number first")
            return
        }
        sendCall(value)
    }

    func sendCallWhenConnected(_ value: String) {
        guard let number = normalizedPhoneNumber(value) else {
            setTransientCallStatus("Phone link does not contain a valid number")
            return
        }
        pendingCallNumber = number
        pendingCallExpiryWorkItem?.cancel()
        if flushPendingCallIfPossible() { return }
        callStatusClearWorkItem?.cancel()
        callStatusClearWorkItem = nil
        callStatus = "Waiting for Android connection…"
        let expiry = DispatchWorkItem { [weak self] in
            guard let self, self.pendingCallNumber == number else { return }
            self.pendingCallNumber = nil
            self.pendingCallExpiryWorkItem = nil
            self.setTransientCallStatus("Android did not connect in time")
        }
        pendingCallExpiryWorkItem = expiry
        DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: expiry)
    }

    func sendCall(_ value: String) {
        callStatusClearWorkItem?.cancel()
        callStatusClearWorkItem = nil
        guard isFeatureAvailable(.calls) else {
            setTransientCallStatus("Calls are turned off or require Bridgey alpha.5 on both devices")
            return
        }
        guard let number = normalizedPhoneNumber(value) else {
            setTransientCallStatus("Clipboard does not contain a valid phone number")
            return
        }
        guard let current = activeSession, case .connected = state,
              let plaintext = try? JSONEncoder().encode(CallRequestPayload(number: number)),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else {
            setTransientCallStatus("Android is not connected")
            return
        }
        let messageID = UUID().uuidString.lowercased()
        callRequestID = messageID
        callStatus = "Sending call request…"
        current.send(PairingMessage(
            kind: "calls.request",
            sessionId: current.id,
            messageId: messageID,
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        callTimeoutWorkItem?.cancel()
        let timeout = DispatchWorkItem { [weak self] in
            guard self?.callRequestID == messageID else { return }
            self?.callRequestID = nil
            self?.setTransientCallStatus("Android did not confirm the call request")
        }
        callTimeoutWorkItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: timeout)
    }

    /// Sends an answer/decline/hangup command for a call reported over the Telecom v2 channel.
    /// Called from performRemoteCallAction in Calls.swift, hence not `private`.
    func sendCallControl(callID: String, action: String, deviceID: String, route: String? = nil) {
        guard case let .connected(connectedDeviceID, _) = state,
              connectedDeviceID == deviceID,
              let current = activeSession,
              current.remoteDeviceID == deviceID,
              featureEnabled(.calls, current: current),
              UUID(uuidString: callID) != nil,
              isKnownCallAction(action),
              isValidAudioRoute(route),
              let plaintext = try? JSONEncoder().encode(
                CallActionPayload(version: 1, callId: callID, action: action, route: route)
              ),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        current.send(PairingMessage(
            kind: "calls.action",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        diagnostics.record(category: "calls", event: "action_sent", outcome: action)
    }

    /// Sends a play/pause/toggle/next/previous/seek command targeting whatever MediaSession
    /// Android currently reports as primary. `generation` pins the command to the session context
    /// mediaRemote last received a state update for, so Android can reject it if its primary
    /// session has since changed underneath the Mac.
    private func sendMediaRemoteAction(action: String, value: Int64?, generation: Int64) {
        guard case .connected = state, let current = activeSession,
              isFeatureAvailable(.media),
              let plaintext = try? JSONEncoder().encode(MediaRemoteActionPayload(
                version: 1, requestId: UUID().uuidString.lowercased(), action: action, value: value, generation: generation
              )),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        current.send(PairingMessage(
            kind: "media.remote.action",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        diagnostics.record(category: "media", event: "remote_action_sent", outcome: action)
    }

    // Called from the incoming-call state machine in Calls.swift, hence not `private`.
    func clearCallStatus() {
        callTimeoutWorkItem?.cancel()
        callTimeoutWorkItem = nil
        callStatusClearWorkItem?.cancel()
        callStatusClearWorkItem = nil
        callRequestID = nil
        callStatus = nil
    }

    @discardableResult
    private func flushPendingCallIfPossible() -> Bool {
        let isConnected: Bool
        if case .connected = state { isConnected = true } else { isConnected = false }
        guard let number = pendingCallNumber,
              pendingPhoneCallCanDispatch(
                isConnected: isConnected,
                featureStateReceived: remoteFeatureStateReceived
              ) else { return false }
        pendingCallNumber = nil
        pendingCallExpiryWorkItem?.cancel()
        pendingCallExpiryWorkItem = nil
        sendCall(number)
        return true
    }

    private func clearPendingCall() {
        pendingCallNumber = nil
        pendingCallExpiryWorkItem?.cancel()
        pendingCallExpiryWorkItem = nil
    }

    // Called from updateRemoteCallFromTelecom in Calls.swift, hence not `private`.
    func setTransientCallStatus(_ status: String) {
        callStatusClearWorkItem?.cancel()
        callStatus = status
        let work = DispatchWorkItem { [weak self] in
            self?.callStatus = nil
            self?.callStatusClearWorkItem = nil
        }
        callStatusClearWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: work)
    }

    private func featureEnabled(_ feature: BridgeyFeature, current: Session? = nil) -> Bool {
        let id = (current ?? activeSession)?.remoteDeviceID
        return settings.isEnabled(feature, for: id?.isEmpty == false ? id : nil)
    }

    func isFeatureAvailable(_ feature: BridgeyFeature) -> Bool {
        effectiveFeatureAvailable(
            localEnabled: featureEnabled(feature),
            remoteEnabled: remoteFeatures[feature] != false
        )
    }

    /// BRIDGEY KVM COPY/PASTE INTEGRATION: `completion` is `nil` for every pre-existing call site
    /// (the menu button, the global keyboard shortcut) - only `onPasteRequested` (Command+V during
    /// KVM) passes one, to know once the sync has definitely finished (delivered, rejected, or timed
    /// out) before forwarding the follow-up KEY(Ctrl+V). Behavior for existing callers is unchanged.
    func sendClipboard(completion: ((Bool) -> Void)? = nil) {
        guard let current = activeSession, case .connected = state else {
            completion?(false)
            return
        }
        guard isFeatureAvailable(.clipboard) else {
            clipboardStatus = "Clipboard is turned off on one of your devices"
            completion?(false)
            return
        }
        guard let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else {
            clipboardStatus = "Clipboard is empty or unavailable"
            completion?(false)
            return
        }
        guard clipboardTextFits(text) else {
            clipboardStatus = "Clipboard exceeds 32 KiB. Send large text or diagnostics as a file."
            completion?(false)
            return
        }
        let html = NSPasteboard.general.data(forType: .html)
            .flatMap { String(data: $0, encoding: .utf8) }
        let richContent = RichClipboardContent(text: text, html: html)
        let plaintext = richContent.flatMap { try? JSONEncoder().encode($0) } ?? Data(text.utf8)
        guard let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else {
            clipboardStatus = "Encryption failed"
            completion?(false)
            return
        }
        let messageID = UUID().uuidString.lowercased()
        current.send(PairingMessage(
            kind: richContent == nil ? "clipboard.update" : "clipboard.rich",
            sessionId: current.id,
            messageId: messageID,
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        clipboardTimeoutWorkItem?.cancel()
        clipboardSendID = messageID
        clipboardCompletion = completion
        clipboardStatus = "Sending…"
        let timeout = DispatchWorkItem { [weak self, weak current] in
            guard let self, let current, self.activeSession === current,
                  self.clipboardSendID == messageID else { return }
            self.clipboardSendID = nil
            self.clipboardTimeoutWorkItem = nil
            self.clipboardStatus = "No delivery acknowledgement"
            self.sendFeatureState()
            let completion = self.clipboardCompletion
            self.clipboardCompletion = nil
            completion?(false)
        }
        clipboardTimeoutWorkItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: timeout)
        NSLog("PLUGIN clipboard sent")
    }

    private func clearClipboardSendStatus() {
        clipboardTimeoutWorkItem?.cancel()
        clipboardTimeoutWorkItem = nil
        clipboardSendID = nil
        clipboardStatus = nil
        let completion = clipboardCompletion
        clipboardCompletion = nil
        completion?(false)
    }

    func sendPing() {
        guard let current = activeSession, case .connected = state else {
            setTransientPingStatus("Android is not connected")
            return
        }
        guard isFeatureAvailable(.ping) else {
            setTransientPingStatus("Ping requires Bridgey 0.6 on both devices")
            return
        }
        guard let plaintext = try? JSONEncoder().encode(PingPayload(version: 1)),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else {
            setTransientPingStatus("Ping could not be encrypted")
            return
        }
        let messageID = UUID().uuidString.lowercased()
        pingRequestID = messageID
        pingStatusClearWorkItem?.cancel()
        pingStatus = "Pinging Android…"
        current.send(PairingMessage(
            kind: "ping.request",
            sessionId: current.id,
            messageId: messageID,
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        let timeout = DispatchWorkItem { [weak self] in
            guard self?.pingRequestID == messageID else { return }
            self?.pingRequestID = nil
            self?.setTransientPingStatus("Android did not acknowledge the ping")
        }
        pingStatusClearWorkItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: timeout)
    }

    private func receivePing(_ message: PairingMessage, in current: Session) throws {
        guard featureEnabled(.ping, current: current) else {
            sendFeatureState()
            return
        }
        guard case .connected = state,
              message.sessionId == current.id,
              let messageID = message.messageId,
              current.acceptMessageID(messageID),
              let nonce = message.nonce,
              let ciphertext = message.ciphertext,
              let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
              let payload = try? JSONDecoder().decode(PingPayload.self, from: plaintext),
              payload.version == 1 else { throw PairingError.invalidMessage }
        NSSound(named: NSSound.Name("Glass"))?.play()
        setTransientPingStatus("Ping from \(current.peerName)")
        current.send(PairingMessage(kind: "ping.ack", sessionId: current.id, messageId: messageID))
        NSLog("PLUGIN ping received")
    }

    private func setTransientPingStatus(_ value: String) {
        pingStatusClearWorkItem?.cancel()
        pingStatus = value
        let work = DispatchWorkItem { [weak self] in
            self?.pingStatus = nil
            self?.pingStatusClearWorkItem = nil
        }
        pingStatusClearWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: work)
    }

    private func clearPingStatus() {
        pingRequestID = nil
        pingStatusClearWorkItem?.cancel()
        pingStatusClearWorkItem = nil
        pingStatus = nil
    }

    private func sendQuickPayload(kind: String, payload: [String: Any]) -> Bool {
        guard let current = activeSession, case .connected = state, let key = current.pairingKey,
              let data = try? JSONSerialization.data(withJSONObject: payload), data.count <= 32768,
              let encrypted = try? encrypt(data, key: key) else { return false }
        current.send(PairingMessage(kind: kind, sessionId: current.id,
            messageId: UUID().uuidString.lowercased(), nonce: encrypted.nonce, ciphertext: encrypted.ciphertext))
        return true
    }

    /// Dedicated control-channel dispatch for the video/input channel negotiation family
    /// (video.offer/accept/reject/stop, input.offer/accept/reject/stop) - decrypts and hands the
    /// plain JSON payload to VideoChannelController, which owns all negotiation/establishment logic.
    private func receiveVideoChannelMessage(_ message: PairingMessage, current: Session) {
        guard case .connected = state, message.sessionId == current.id,
              let key = current.pairingKey, let id = message.messageId, current.acceptMessageID(id),
              let nonce = message.nonce, let ciphertext = message.ciphertext,
              let data = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: key), data.count <= 8192,
              let payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        videoChannel.receive(kind: message.kind, payload: payload)
    }

    private func receiveQuickPayload(_ message: PairingMessage, current: Session) {
        guard activeSession === current, case .connected = state, message.sessionId == current.id,
              let key = current.pairingKey, let id = message.messageId, current.acceptMessageID(id),
              let nonce = message.nonce, let ciphertext = message.ciphertext,
              let data = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: key), data.count <= 8192,
              let payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              payload["version"] as? Int == 1 else { return }
        if message.kind == "quick.request" {
            guard let feature = payload["feature"] as? String, let sequence = payload["sequence"] as? Int,
                  current.quickSequence.accept(feature: feature, sequence: sequence) else { return }
        }
        if message.kind == "quick.request", payload["feature"] as? String == "media" {
            guard let requestID = payload["requestId"] as? String, UUID(uuidString: requestID) != nil else { return }
            let reply: (Bool) -> Void = { [weak self, weak current] accepted in
                guard let self, let current, self.activeSession === current else { return }
                _ = self.sendQuickPayload(kind: "quick.result", payload: [
                    "version": 1, "requestId": requestID, "feature": "media", "accepted": accepted
                ])
            }
            guard isFeatureAvailable(.media), let action = payload["action"] as? String,
                  let value = payload["value"] as? String else { reply(false); return }
            mediaController.command(action: action, value: value, completion: reply)
        } else {
            quickActions.receive(message.kind, payload: payload)
        }
    }

    private func publishLocalBattery(force: Bool = false) {
        guard isFeatureAvailable(.battery),
              let current = activeSession, case .connected = state,
              let status = currentMacBatteryStatus(),
              force || status != lastSentBattery,
              let plaintext = try? JSONEncoder().encode(BatteryPayload(
                level: status.level,
                isCharging: status.isCharging
              )),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        lastSentBattery = status
        current.send(PairingMessage(
            kind: "battery.update",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        NSLog("PLUGIN battery sent level=%d charging=%@", status.level, String(status.isCharging))
    }

    /// 100 MiB dead-band, matching Android's `STORAGE_CHANGE_THRESHOLD_BYTES` - raw byte counts
    /// churn constantly from routine cache/log activity, so exact-equality (as battery uses) would
    /// resend on every heartbeat tick instead of only on a real, meaningful change.
    private static let storageChangeThresholdBytes: Int64 = 100 * 1024 * 1024

    private func shouldResendStorage(_ status: LocalStorageStatus, previous: LocalStorageStatus?) -> Bool {
        guard let previous else { return true }
        return status.totalBytes != previous.totalBytes ||
            abs(status.usedBytes - previous.usedBytes) >= Self.storageChangeThresholdBytes
    }

    private func publishLocalStorage(force: Bool = false) {
        guard isFeatureAvailable(.storage),
              let current = activeSession, case .connected = state,
              let status = currentMacStorageStatus(),
              force || shouldResendStorage(status, previous: lastSentStorage),
              let plaintext = try? JSONEncoder().encode(TelemetryPayload(
                version: 1,
                storageUsedBytes: status.usedBytes,
                storageTotalBytes: status.totalBytes
              )),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        lastSentStorage = status
        current.send(PairingMessage(
            kind: "telemetry.update",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        NSLog("PLUGIN storage sent usedBytes=%lld totalBytes=%lld", status.usedBytes, status.totalBytes)
    }

    private func resetRemoteMemoryState() {
        remoteMemory = nil
        lastSentMemory = nil
    }

    private func resetTelemetrySubscriptionState() {
        remoteCpu = nil
        previousCpuSample = nil
        remoteTemperature = nil
        remoteWantsTelemetryUpdates = false
        telemetrySamplingWorkItem?.cancel()
        telemetrySamplingWorkItem = nil
    }

    /// Call when the panel becomes visible. Battery-conscious by design: nothing is sampled or sent
    /// while closed. Storage/memory/CPU are all refreshed together, every ~3s, only while the peer
    /// confirms someone is actually looking.
    func requestRemoteTelemetryUpdates() {
        localWantsRemoteTelemetryUpdates = true
        sendTelemetrySubscription(subscribe: true)
    }

    /// Call when the panel closes.
    func stopRequestingRemoteTelemetryUpdates() {
        localWantsRemoteTelemetryUpdates = false
        sendTelemetrySubscription(subscribe: false)
    }

    /// Not gated by any single telemetry feature - this just signals "my panel is open/closed";
    /// each metric's own publish function independently respects its own Settings toggle.
    private func sendTelemetrySubscription(subscribe: Bool) {
        guard let current = activeSession, case .connected = state else { return }
        current.send(PairingMessage(
            kind: subscribe ? "telemetry.subscribe" : "telemetry.unsubscribe",
            sessionId: current.id
        ))
    }

    /// Peer's panel opened and wants our telemetry - start the on-demand loop that resends
    /// storage/memory (dead-band gated, as always), CPU, and temperature (always, being rates/
    /// instant readings) every ~3s. Not gated here by any specific feature - each publish call
    /// below independently no-ops if its own Settings toggle is off.
    private func startTelemetrySamplingLoop() {
        previousCpuSample = nil
        remoteWantsTelemetryUpdates = true
        scheduleTelemetrySample()
    }

    private func scheduleTelemetrySample() {
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.remoteWantsTelemetryUpdates else { return }
            self.publishLocalStorage()
            self.publishLocalMemory()
            self.publishLocalCpu()
            self.publishLocalTemperature()
            self.scheduleTelemetrySample()
        }
        telemetrySamplingWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: work)
    }

    /// Stops the loop only - does NOT clear the last-known storage/memory/CPU values, which stay
    /// visible (e.g. on the connected-device card) until the next real disconnect/reconnect.
    private func stopTelemetrySamplingLoop() {
        remoteWantsTelemetryUpdates = false
        previousCpuSample = nil
        telemetrySamplingWorkItem?.cancel()
        telemetrySamplingWorkItem = nil
    }

    /// Reads one CPU sample and, if a previous sample exists, sends the computed delta as
    /// `cpuPercent`. The first sample after a (re)subscribe only seeds the baseline - sending
    /// nothing that tick avoids a flash of "unavailable" before the second tick has a real delta.
    /// A read/computation failure sends explicit `cpuUnavailable: true` rather than a fabricated
    /// number.
    private func publishLocalCpu() {
        guard isFeatureAvailable(.cpu), let current = activeSession, case .connected = state else { return }
        let sample = currentMacCpuSample()
        let previous = previousCpuSample
        let status: CpuStatus?
        if let sample {
            previousCpuSample = sample
            status = previous == nil ? nil : computeCpuPercent(previous: previous, current: sample)
        } else {
            status = .unavailable
        }
        guard let status else { return } // nil = first sample this subscription: seed only, send nothing
        var payload = TelemetryPayload(version: 1)
        switch status {
        case .available(let percent): payload.cpuPercent = percent
        case .unavailable: payload.cpuUnavailable = true
        }
        guard let plaintext = try? JSONEncoder().encode(payload),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        current.send(PairingMessage(
            kind: "telemetry.update",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        NSLog("PLUGIN cpu sent %@", String(describing: status))
    }

    private static let memoryChangeThresholdBytes: Int64 = 100 * 1024 * 1024

    private func shouldResendMemory(_ status: LocalMemoryStatus, previous: LocalMemoryStatus?) -> Bool {
        guard let previous else { return true }
        return status.totalBytes != previous.totalBytes ||
            abs(status.usedBytes - previous.usedBytes) >= Self.memoryChangeThresholdBytes
    }

    /// Mirrors [publishLocalStorage]'s shape and cadence exactly - same background heartbeat call
    /// site, same dead-band principle - just a second independent metric on the same message kind.
    private func publishLocalMemory(force: Bool = false) {
        guard isFeatureAvailable(.memory),
              let current = activeSession, case .connected = state,
              let status = currentMacMemoryStatus(),
              force || shouldResendMemory(status, previous: lastSentMemory),
              let plaintext = try? JSONEncoder().encode(TelemetryPayload(
                version: 1,
                memoryUsedBytes: status.usedBytes,
                memoryTotalBytes: status.totalBytes
              )),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        lastSentMemory = status
        current.send(PairingMessage(
            kind: "telemetry.update",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        NSLog("PLUGIN memory sent usedBytes=%lld totalBytes=%lld", status.usedBytes, status.totalBytes)
    }

    /// Thermal state is always sent when known (no dead-band - it rarely changes and is cheap to
    /// encode). macOS never sends a Celsius value (see MacTemperature.swift for why); an explicit
    /// unavailable state is sent rather than silence, matching CPU.
    private func publishLocalTemperature() {
        guard isFeatureAvailable(.temperature), let current = activeSession, case .connected = state else { return }
        var payload = TelemetryPayload(version: 1)
        switch currentMacTemperatureStatus() {
        case .known(let thermalState): payload.thermalState = thermalState
        case .unavailable: payload.temperatureUnavailable = true
        }
        guard let plaintext = try? JSONEncoder().encode(payload),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        current.send(PairingMessage(
            kind: "telemetry.update",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        NSLog("PLUGIN temperature sent")
    }

    func findAndroid() {
        _ = sendFindCommand(kind: "find.start")
    }

    func stopFinding() {
        stopMacSound()
        _ = sendFindCommand(kind: "find.stop")
    }

    private func sendFindCommand(kind: String) -> Bool {
        guard let current = activeSession, case .connected = state,
              (kind != "find.start" || isFeatureAvailable(.findDevice)),
              let payload = try? JSONEncoder().encode(FindDevicePayload(alertId: "active")),
              let encrypted = try? encrypt(payload, key: current.pairingKey!) else { return false }
        current.send(PairingMessage(
            kind: kind,
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        return true
    }

    private func receiveFindCommand(_ message: PairingMessage, in current: Session, start: Bool) throws {
        if start && !featureEnabled(.findDevice, current: current) { return }
        guard case .connected = state,
              message.sessionId == current.id,
              let messageID = message.messageId,
              current.acceptMessageID(messageID),
              let nonce = message.nonce,
              let ciphertext = message.ciphertext,
              let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
              let payload = try? JSONDecoder().decode(FindDevicePayload.self, from: plaintext),
              payload.alertId == "active" else { throw PairingError.invalidMessage }
        if start {
            startMacSound()
            _ = sendFindCommand(kind: macRinging ? "find.started" : "find.stopped")
        } else {
            stopMacSound()
            androidRinging = false
            _ = sendFindCommand(kind: "find.stopped")
        }
        NSLog("PLUGIN find-device %@", start ? "started" : "stopped")
    }

    private func receiveFindAcknowledgement(
        _ message: PairingMessage,
        in current: Session,
        started: Bool
    ) throws {
        guard case .connected = state,
              message.sessionId == current.id,
              let messageID = message.messageId,
              current.acceptMessageID(messageID),
              let nonce = message.nonce,
              let ciphertext = message.ciphertext,
              let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
              let payload = try? JSONDecoder().decode(FindDevicePayload.self, from: plaintext),
              payload.alertId == "active" else { throw PairingError.invalidMessage }
        androidRinging = started
    }

    private func startMacSound() {
        guard !macRinging else { return }
        guard let sound = NSSound(contentsOfFile: "/System/Library/Sounds/Funk.aiff", byReference: true) else { return }
        sound.loops = true
        findDeviceSound = sound
        macRinging = true
        sound.play()
    }

    private func stopMacSound() {
        findDeviceSound?.stop()
        findDeviceSound = nil
        macRinging = false
    }

    func chooseAndSendFile() {
        guard activeSession != nil, case .connected = state else {
            fileTransferStatus = "Not connected — file was not sent"
            return
        }
        guard isFeatureAvailable(.files) else {
            fileTransferStatus = "File transfer is turned off on one of your devices"
            return
        }
        // MenuBarExtra closes its transient window after invoking the action.
        // Present the picker on the next run-loop turn as an app-modal panel so
        // an LSUIElement app can reliably bring it in front of other windows.
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            NSApp.activate(ignoringOtherApps: true)
            let panel = NSOpenPanel()
            panel.title = "Send File to Android"
            panel.prompt = "Send"
            panel.canChooseFiles = true
            panel.canChooseDirectories = false
            panel.allowsMultipleSelection = false
            panel.level = .floating
            guard panel.runModal() == .OK, let url = panel.url else { return }
            self.prepareFile(url)
        }
    }

    @discardableResult
    func sendDroppedFile(_ url: URL) -> Bool {
        guard activeSession != nil, case .connected = state else {
            fileTransferStatus = "Not connected — file was not sent"
            return false
        }
        guard isFeatureAvailable(.files) else {
            fileTransferStatus = "File transfer is turned off on one of your devices"
            return false
        }
        guard url.isFileURL,
              (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true else {
            fileTransferStatus = "Drop a file, not a folder"
            return false
        }
        prepareFile(url)
        return true
    }

    func exportDiagnostics() {
        let stateName: String
        switch state {
        case .idle: stateName = "idle"
        case .connecting: stateName = "connecting"
        case .verification: stateName = "verification"
        case .connected: stateName = "connected"
        case .failed: stateName = "failed"
        }
        let localFeatures = Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map {
            ($0, settings.isEnabled($0, for: nil))
        })
        guard let report = try? diagnostics.report(
            connectionState: stateName,
            transfers: Array(fileTransfers.values),
            localFeatures: localFeatures,
            remoteFeatures: remoteFeatures
        ) else { return }
        let panel = NSSavePanel()
        panel.title = "Export Bridgey Diagnostics"
        panel.nameFieldStringValue = "Bridgey-Diagnostics.json"
        panel.allowedContentTypes = [.json]
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK, let url = panel.url else { return }
        try? report.write(to: url, options: .atomic)
    }

    private func prepareFile(_ url: URL) {
        guard let current = activeSession, case .connected = state else { return }
        let expectedSessionID = current.id
        let operationID = UUID()
        let preparationCancellation = FileCancellationToken()
        fileOperationID = operationID
        filePreparationCancellation = preparationCancellation
        beginFileTransferUI()
        fileTransferStatus = "Preparing \(url.lastPathComponent)…"
        diagnostics.record(category: "transfer", event: "send_started")
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            do {
                let transfer = try OutgoingFileTransfer(url: url, cancellation: preparationCancellation)
                DispatchQueue.main.async {
                    guard let self, self.fileOperationID == operationID,
                          let current = self.activeSession, current.id == expectedSessionID,
                          case .connected = self.state else { return }
                    self.filePreparationCancellation = nil
                    do {
                        let payload = try JSONEncoder().encode(transfer.offer)
                        let encrypted = try encrypt(payload, key: current.pairingKey!)
                        self.outgoingFiles[transfer.transferID] = transfer
                        self.outgoingFileSources[transfer.transferID] = url
                        self.updateFileTransfer(
                            id: transfer.transferID,
                            name: transfer.displayName,
                            status: "Waiting for Android…",
                            active: true
                        )
                        current.send(PairingMessage(
                            kind: "files.offer",
                            sessionId: current.id,
                            messageId: UUID().uuidString.lowercased(),
                            nonce: encrypted.nonce,
                            ciphertext: encrypted.ciphertext,
                            transferId: transfer.transferID
                        ))
                        self.fileTransferStatus = "Waiting for Android…"
                    } catch {
                        self.fileTransferStatus = "Could not prepare the selected file"
                    }
                }
            } catch {
                DispatchQueue.main.async {
                    guard self?.fileOperationID == operationID else { return }
                    self?.fileTransferStatus = "Could not read the selected file"
                    self?.fileTransferActive = self?.fileTransfers.values.contains(where: { $0.active }) == true
                    self?.filePreparationCancellation = nil
                }
            }
        }
    }

    func cancelFileTransfer() {
        filePreparationCancellation?.cancel()
        filePreparationCancellation = nil
        let transferIDs = Set(incomingFiles.keys).union(outgoingFiles.keys)
        transferIDs.forEach { transferID in
            markTransferCancelled(transferID)
            activeSession?.send(PairingMessage(kind: "files.cancel", sessionId: activeSession?.id ?? "", transferId: transferID))
        }
        incomingFiles.values.forEach { $0.cancel() }
        incomingFiles.removeAll()
        outgoingFiles.values.forEach { $0.cancel() }
        outgoingFiles.removeAll()
        transferIDs.forEach { markFileTransferFinished(id: $0, status: "Transfer cancelled") }
        fileOperationID = nil
        fileTransferActive = false
        fileTransferStatus = "Transfer cancelled"
    }

    func cancelFileTransfer(id transferID: String) {
        markTransferCancelled(transferID)
        activeSession?.send(PairingMessage(kind: "files.cancel", sessionId: activeSession?.id ?? "", transferId: transferID))
        incomingFiles.removeValue(forKey: transferID)?.cancel()
        outgoingFiles.removeValue(forKey: transferID)?.cancel()
        markFileTransferFinished(id: transferID, status: "Transfer cancelled")
        fileTransferStatus = "Transfer cancelled"
    }

    func retryFileTransfer(id transferID: String) {
        guard let url = outgoingFileSources[transferID] else { return }
        guard activeSession != nil, case .connected = state else {
            markFileTransferFinished(id: transferID, status: "Reconnect before retrying")
            return
        }
        guard isFeatureAvailable(.files) else {
            markFileTransferFinished(id: transferID, status: "File transfer is turned off on one of your devices")
            return
        }
        fileTransfers.removeValue(forKey: transferID)
        outgoingFileSources.removeValue(forKey: transferID)
        diagnostics.record(category: "transfer", event: "retry_started")
        prepareFile(url)
    }

    func clearTransferHistory() {
        let inactiveIDs = fileTransfers.values.filter { !$0.active }.map(\.id)
        inactiveIDs.forEach { outgoingFileSources.removeValue(forKey: $0) }
        fileTransfers = fileTransfers.filter { $0.value.active }
        fileTransferActive = fileTransfers.values.contains(where: { $0.active })
    }

    func showFileTransferWindow() {
        if fileTransferWindow == nil {
            fileTransferWindow = FileTransferWindowController(pairing: self)
        }
        fileTransferWindow?.show()
    }

    func showScreenShareWindow() {
        if screenShareWindow == nil {
            screenShareWindow = ScreenShareWindowController(
                decoder: screenStreamDecoder,
                onUserClosedWindow: { [weak self] in
                    self?.sendRemoteScreenShareStop()
                    // KVM Mouse Input v1: the capture surface is gone with this window, so the input
                    // channel it was feeding has no reason to stay open either.
                    self?.videoChannel.stopInput()
                },
                onPointerEvent: { [weak self] action, x, y, button, scrollDx, scrollDy in
                    guard let self else { return }
                    // offerInput() is a no-op once the channel is already offered/connecting/active
                    // (frozen VideoChannelController guard) - safe to call on every event.
                    videoChannel.offerInput(direction: "mac_to_android")
                    _ = videoChannel.sendInputEvent(.pointer(action: action, x: x, y: y, button: button, scrollDx: scrollDx, scrollDy: scrollDy))
                },
                onGestureEvent: { [weak self] action in
                    guard let self else { return }
                    // BRIDGEY KVM TOUCHPAD GESTURES V1: same authenticated/encrypted "input" channel
                    // as every other InputEvent - no new socket, no new trust mechanism.
                    videoChannel.offerInput(direction: "mac_to_android")
                    _ = videoChannel.sendInputEvent(.gesture(action: action))
                },
                onKeyboardEvent: { [weak self] event in
                    guard let self else { return }
                    // BRIDGEY KVM KEYBOARD V1: same authenticated/encrypted "input" channel as every
                    // other InputEvent - no new socket, no new trust mechanism.
                    videoChannel.offerInput(direction: "mac_to_android")
                    _ = videoChannel.sendInputEvent(event)
                },
                onSwitchKeyboardRequested: { [weak self] in
                    self?.sendSwitchKeyboard()
                },
                onPasteRequested: { [weak self] in
                    guard let self else { return }
                    // BRIDGEY KVM COPY/PASTE INTEGRATION: reuses the EXISTING, unmodified Clipboard
                    // Continuity sendClipboard() - no second clipboard transport/monitor/protocol.
                    // Whether or not the sync itself succeeds (e.g. Mac clipboard empty, feature off),
                    // still forward Ctrl+V - Android just pastes whatever it already has, matching
                    // ordinary paste semantics rather than silently doing nothing on a sync failure.
                    self.sendClipboard { [weak self] _ in
                        guard let self else { return }
                        guard let androidKeyCode = KvmKeyMapping.androidKeyCode(forLetterOrDigit: "v") else { return }
                        let modifiers = KvmKeyMapping.modifierBits(shift: false, control: true, option: false, command: false)
                        videoChannel.offerInput(direction: "mac_to_android")
                        _ = videoChannel.sendInputEvent(.key(keyCode: androidKeyCode, action: .down, modifiers: modifiers))
                        _ = videoChannel.sendInputEvent(.key(keyCode: androidKeyCode, action: .up, modifiers: modifiers))
                        NSLog("PLUGIN KVM paste: clipboard synced, Ctrl+V forwarded to Android")
                    }
                }
            )
        }
        screenShareWindow?.show()
        sendRemoteScreenShareStart()
    }

    /// Advanced Screen Continuity - Remote Start (Case A/B). Reuses the same authenticated,
    /// AES-GCM-encrypted session as every other Bridgey command (ping, find-device, ...) - no
    /// separate pairing/handshake needed, since only a peer that already completed the real
    /// ECDH+SAS-verified handshake holds the pairingKey this payload must decrypt with. A no-op if
    /// the phone hasn't opted in (isFeatureAvailable(.remoteScreenShare) false) or nothing is paired.
    private func sendRemoteScreenShareStart() {
        guard let current = activeSession, case .connected = state, isFeatureAvailable(.remoteScreenShare) else { return }
        guard let plaintext = try? JSONEncoder().encode(RemoteScreenSharePayload(version: 1)),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        current.send(PairingMessage(
            kind: "screenshare.remoteStart",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        NSLog("REMOTE_START request sent to Android")
    }

    private func sendRemoteScreenShareStop() {
        guard let current = activeSession, case .connected = state, isFeatureAvailable(.remoteScreenShare) else { return }
        guard let plaintext = try? JSONEncoder().encode(RemoteScreenSharePayload(version: 1)),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        current.send(PairingMessage(
            kind: "screenshare.remoteStop",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        NSLog("REMOTE_STOP request sent to Android")
    }

    /// BRIDGEY KVM KEYBOARD V1 (switch shortcut): Command+K while the Screen Share window is key asks
    /// Android to toggle its active keyboard to/from "Bridgey KVM Keyboard" - see
    /// PairingCoordinator.receiveSwitchKeyboard / KvmKeyboardSwitcher.kt for what happens on the phone.
    /// No feature-negotiation gate (unlike Remote Screen Share): a peer that doesn't understand this
    /// command kind just ignores it, same fallback every other unrecognized `kind` already gets.
    private func sendSwitchKeyboard() {
        guard let current = activeSession, case .connected = state else { return }
        guard let plaintext = try? JSONEncoder().encode(SwitchKeyboardPayload(version: 1)),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        current.send(PairingMessage(
            kind: "kvm.switchKeyboard",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        NSLog("KVM switchKeyboard request sent to Android")
    }

    func showFileDropWindow() {
        if fileDropWindow == nil {
            fileDropWindow = FileDropWindowController(pairing: self)
        }
        fileDropWindow?.show()
    }

    private func beginFileTransferUI() {
        // Update state only — do not force the transfer window to the front. Progress is
        // reachable from the menu bar (icon reflects an active transfer; the panel's
        // "N transfers" button opens the window on demand) instead of interrupting the user
        // for every transfer, which is especially important for frequent, unattended Photo
        // Sync sends.
        fileTransferActive = true
    }

    private func startListener() {
        diagnostics.record(category: "transport", event: "listener_started")
        do {
            let listener = try NWListener(using: .tcp, on: 42_458)
            listener.newConnectionHandler = { [weak self] connection in
                DispatchQueue.main.async { self?.accept(connection) }
            }
            listener.stateUpdateHandler = { newState in
                if case let .failed(error) = newState {
                    NSLog("PAIRING listener failed error=%@", String(describing: error))
                }
            }
            listener.start(queue: .main)
            self.listener = listener
        } catch {
            failureMessage = "Could not start pairing listener"
            refreshState()
        }
    }

    /// Extracts the peer's dotted-quad/host string from an accepted incoming connection, for
    /// VideoChannelController to dial the dedicated video/input socket back to the same peer.
    private static func remoteHostString(_ connection: NWConnection) -> String? {
        guard case let .hostPort(host, _) = connection.endpoint else { return nil }
        switch host {
        case .ipv4(let address): return "\(address)"
        case .ipv6(let address): return "\(address)"
        case .name(let name, _): return name
        @unknown default: return nil
        }
    }

    private func accept(_ connection: NWConnection) {
        // A new socket is pending until it says which device it is (pairing.offer). Duplicate
        // protection is then applied per deviceId, so it can never displace another device's session.
        NSLog("CONNECT accepting incoming connection")
        let current = Session(connection: connection, peerName: "Bridgey device")
        current.remoteHost = Self.remoteHostString(connection)
        current.initiatedLocally = false
        peers.addPending(current, initiatedLocally: false, expectedDeviceID: nil)
        scheduleConnectionTimeout(for: current)
        configure(current, onReady: {})
    }

    private func configure(_ current: Session, onReady: @escaping () -> Void) {
        current.onMessage = { [weak self, weak current] message in
            guard let self, let current else { return }
            self.receive(message, in: current)
        }
        current.onFailure = { [weak self, weak current] in
            // Transport failure in any phase: only this session ends; reconnect is per device.
            guard let self, let current, self.peers.contains(current) else { return }
            let wasConnected = self.peers.phase(of: current) == .connected
            NSLog("CONNECTION lost: wasConnected=%@ peer=%@", String(wasConnected), String(current.remoteDeviceID.prefix(8)))
            self.diagnostics.record(category: "transport", event: "disconnected", outcome: "reconnecting")
            self.endSession(current, scheduleReconnect: true)
        }
        current.connection.stateUpdateHandler = { [weak self, weak current] newState in
            DispatchQueue.main.async {
                guard let self, let current else { return }
                switch newState {
                case .ready:
                    NSLog("TRANSPORT connected")
                    current.receive()
                    onReady()
                case let .failed(error):
                    NSLog("TRANSPORT connection failed error=%@", String(describing: error))
                    current.onFailure?()
                case .cancelled:
                    NSLog("TRANSPORT connection cancelled")
                    current.onFailure?()
                case let .waiting(error) where localNetworkPermissionDenied(error):
                    guard self.peers.contains(current) else { return }
                    self.failureMessage = "Local Network access is off. Enable Bridgey in System Settings → Privacy & Security → Local Network."
                    self.endSession(current, scheduleReconnect: false)
                    self.diagnostics.record(category: "transport", event: "local_network_denied", outcome: "permission_required")
                default:
                    // DIAGNOSTIC (temporary, logging only): otherwise-unlogged NWConnection state
                    // transitions (.setup, .preparing, other .waiting cases), to see whether the
                    // connection lingers in an intermediate state around a phone screen lock.
                    NSLog("TRANSPORT connection state=%@", String(describing: newState))
                }
            }
        }
        current.connection.start(queue: .main)
    }

    /// Ends exactly one session. Other devices' sessions are untouched; single-peer feature state
    /// is reset only when this was the routed (active) session.
    private func endSession(_ current: Session, scheduleReconnect: Bool) {
        current.heartbeatWork?.cancel()
        current.timeoutWork?.cancel()
        let wasActive = current === activeSession
        let deviceID = peers.remove(current)
        current.close()
        if wasActive { interruptFeatureTransfers() }
        recomputeActivePeer()
        if scheduleReconnect, let deviceID, registry.trustedDeviceIDs.contains(deviceID) {
            self.scheduleReconnect(deviceID)
        }
    }

    /// Binds a socket to the device it announced (proved later by the identity signature).
    private func identify(_ current: Session, as remoteID: String) -> Bool {
        let (result, displaced) = peers.identify(current, as: remoteID)
        guard result == .identified else {
            NSLog("CONNECT rejecting connection for peer=%@: %@", String(remoteID.prefix(8)), String(describing: result))
            peers.remove(current)
            current.timeoutWork?.cancel()
            current.close()
            refreshState()
            return false
        }
        if let displaced {
            NSLog("CONNECT simultaneous dial with peer=%@: keeping the lower-id initiated connection", String(remoteID.prefix(8)))
            displaced.heartbeatWork?.cancel()
            displaced.timeoutWork?.cancel()
            displaced.close()
        }
        return true
    }

    /// Ends in-flight clipboard/file/call exchanges of the single-peer feature layer.
    private func interruptFeatureTransfers() {
        cancelIncomingFiles()
        clearClipboardSendStatus()
        clearCallStatus()
        if !outgoingFiles.isEmpty {
            outgoingFiles.values.forEach { $0.cancel() }
            outgoingFiles.removeAll()
            fileTransferStatus = "File transfer interrupted"
        }
    }

    /// The routing seam: recomputes which connected device today's single-peer features use.
    private func recomputeActivePeer() {
        let next = DeviceRouting.activePeer(
            mode: settings.deviceRoutingMode,
            preferredDeviceID: settings.preferredDeviceID,
            connected: peers.connectedInOrder
        )
        guard next != activePeerID else {
            refreshState()
            return
        }
        let previous = activePeerID.flatMap { peers.session(for: $0) }
        activePeerID = next
        NSLog("ROUTING active peer -> %@", next.map { String($0.prefix(8)) } ?? "none")
        activePeerChanged(previous: previous)
        refreshState()
    }

    /// Feature layer only. Today's single-peer features restart against the newly routed session.
    /// No session, capability, trust or connection state changes, and no capability update is sent.
    private func activePeerChanged(previous: Session?) {
        if localWantsRemoteTelemetryUpdates, let previous, peers.phase(of: previous) == .connected {
            // Battery-conscious: the device we no longer show must stop sampling for us.
            previous.send(PairingMessage(kind: "telemetry.unsubscribe", sessionId: previous.id))
        }
        // Transfers bound to the previous peer cannot complete through the feature layer any more.
        if previous != nil { interruptFeatureTransfers() }
        stopMacSound()
        androidRinging = false
        remoteBattery = nil
        remoteStorage = nil
        clearPingStatus()
        quickActions.reset()
        mediaController.reset()
        mediaRemote.reset()
        videoChannel.reset()
        screenStreamDecoder.reset()
        lastSentBattery = nil
        lastSentStorage = nil
        resetRemoteMemoryState()
        resetTelemetrySubscriptionState()
        clearRemoteCall()
        remoteFeatures = defaultRemoteFeatureState()
        remoteFeatureStateReceived = false
        guard let current = activeSession, peers.phase(of: current) == .connected else { return }
        clearAllDetector.reset() // a new session re-seeds from what is delivered after settling
        clearAllSettlingStartedAt = Date()
        publishLocalBattery(force: true)
        if localWantsRemoteTelemetryUpdates { sendTelemetrySubscription(subscribe: true) }
        if let capabilities = activePeerID.flatMap(peers.capabilities(for:)) { applyRemoteFeatures(capabilities) }
    }

    /// Today's single-value status (UI, menu icon, feature guards), derived from the Core.
    /// `.connected` means the routed (active) device is connected.
    private func refreshState() {
        let next: PairingState
        if let verifying = peers.verifyingSession, let code = verifying.code {
            next = .verification(peerName: verifying.peerName, code: code)
        } else if let current = activeSession, peers.phase(of: current) == .connected {
            next = .connected(deviceID: current.remoteDeviceID, peerName: current.peerName)
        } else if let failureMessage {
            next = .failed(failureMessage)
        } else if let dialing = (peers.identifiedSessions + peers.pendingSessions).first(where: { $0.initiatedLocally }) {
            next = .connecting(dialing.peerName)
        } else {
            next = .idle
        }
        if state != next { state = next }
    }

    /// Protocol messages owned by the Core; everything else belongs to a feature.
    private static let coreMessageKinds: Set<String> = [
        "pairing.offer", "pairing.answer", "pairing.confirm", "pairing.cancel",
        "heartbeat.ping", "heartbeat.pong", "features.update",
    ]

    private func receive(_ message: PairingMessage, in current: Session) {
        // COMPATIBILITY SEAM: features consume only the routed session. An inactive session stays
        // connected; its feature messages are received by the Core but not consumed yet.
        guard Self.coreMessageKinds.contains(message.kind) || current === activeSession else {
            if peers.phase(of: current) == .connected {
                current.unconsumedFeatureMessages += 1
                if current.unconsumedFeatureMessages == 1 {
                    NSLog("ROUTING feature messages from inactive peer=%@ not consumed (kind=%@)", String(current.remoteDeviceID.prefix(8)), message.kind)
                    diagnostics.record(category: "routing", event: "inactive_peer_feature_message", outcome: "not_consumed")
                }
            }
            return
        }
        do {
            switch message.kind {
            case "quick.request", "quick.result":
                receiveQuickPayload(message, current: current)
            case "heartbeat.ping":
                guard message.sessionId == current.id, peers.phase(of: current) == .connected else { return }
                current.heartbeatSupported = true
                current.send(PairingMessage(kind: "heartbeat.pong", sessionId: current.id, messageId: message.messageId))
            case "heartbeat.pong":
                guard message.sessionId == current.id, peers.phase(of: current) == .connected else { return }
                current.heartbeatSupported = true
            case "pairing.offer":
                guard let publicKey = message.publicKey else { throw PairingError.invalidMessage }
                current.id = message.sessionId
                current.peerName = message.deviceName ?? "Bridgey device"
                guard let remoteDeviceID = message.deviceId else { throw PairingError.invalidMessage }
                guard identify(current, as: remoteDeviceID) else { return }
                current.remoteDeviceID = remoteDeviceID
                current.remoteEphemeralKey = publicKey
                let key = P256.KeyAgreement.PrivateKey()
                current.privateKey = key
                current.localEphemeralKey = key.publicKey.x963Representation.base64EncodedString()
                let material = try pairingMaterial(privateKey: key, remoteKey: publicKey, sessionID: current.id)
                current.code = material.code
                current.pairingKey = material.key
                current.send(PairingMessage(
                    kind: "pairing.answer",
                    sessionId: current.id,
                    deviceId: deviceID,
                    deviceName: deviceName,
                    publicKey: current.localEphemeralKey
                ))
                authenticateOrPrompt(current)
            case "pairing.answer":
                guard message.sessionId == current.id,
                      let key = current.privateKey,
                      let publicKey = message.publicKey,
                      let remoteDeviceID = message.deviceId else { throw PairingError.invalidMessage }
                guard identify(current, as: remoteDeviceID) else { return }
                current.peerName = message.deviceName ?? current.peerName
                current.remoteDeviceID = remoteDeviceID
                current.remoteEphemeralKey = publicKey
                let material = try pairingMaterial(privateKey: key, remoteKey: publicKey, sessionID: current.id)
                current.code = material.code
                current.pairingKey = material.key
                authenticateOrPrompt(current)
            case "pairing.confirm":
                guard message.sessionId == current.id,
                      let remoteID = message.deviceId,
                      remoteID == current.remoteDeviceID,
                      let identityKey = message.identityKey,
                      let proof = message.proof,
                      let signature = message.signature,
                      registry.evaluate(deviceID: remoteID, identityKey: identityKey) != .identityMismatch,
                      verifyConfirmationProof(
                        proof,
                        key: current.pairingKey!,
                        sessionID: current.id,
                        deviceID: remoteID,
                        identityKey: identityKey
                      ),
                      verifySignature(signature, identityKey: identityKey, data: authTranscript(current))
                else { throw PairingError.invalidMessage }
                current.remoteIdentityKey = identityKey
                current.remoteConfirmed = true
                completeIfConfirmed(current)
            case "pairing.cancel":
                endSession(current, scheduleReconnect: false)
                refreshState()
            case "features.update":
                guard peers.phase(of: current) == .connected,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let payload = try? JSONDecoder().decode(FeatureStatePayload.self, from: plaintext),
                      payload.version == 1,
                      BridgeyFeature.allCases.filter(featureEnabledByLegacyPeer).allSatisfy({ payload.features[$0.rawValue] != nil }) else {
                    throw PairingError.invalidMessage
                }
                // Core: every session keeps its own real negotiated capabilities.
                let capabilities = Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map {
                    ($0.rawValue, payload.features[$0.rawValue] ?? false)
                })
                peers.setCapabilities(capabilities, for: current)
                // Features: only the routed session's capabilities drive today's single-peer features.
                if current === activeSession { applyRemoteFeatures(capabilities) }
            case "screenshare.remoteStartResult":
                NSLog("REMOTE_START result from Android: %@", message.status ?? "unknown")
            case "ping.request":
                try receivePing(message, in: current)
            case "ping.ack":
                guard message.sessionId == current.id,
                      message.messageId == pingRequestID else { return }
                pingRequestID = nil
                setTransientPingStatus("Ping delivered")
            case "clipboard.update", "clipboard.rich":
                guard featureEnabled(.clipboard, current: current) else {
                    current.send(PairingMessage(
                        kind: "clipboard.rejected",
                        sessionId: current.id,
                        messageId: message.messageId
                    ))
                    sendFeatureState()
                    return
                }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!) else {
                    throw PairingError.invalidMessage
                }
                let text: String
                let html: String?
                if message.kind == "clipboard.rich" {
                    guard let payload = try? JSONDecoder().decode(RichClipboardContent.self, from: plaintext),
                          let content = payload.validated() else { throw PairingError.invalidMessage }
                    text = content.text
                    html = content.html
                } else {
                    guard let decoded = String(data: plaintext, encoding: .utf8),
                          clipboardTextFits(decoded) else { throw PairingError.invalidMessage }
                    text = decoded
                    html = nil
                }
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(text, forType: .string)
                if let html { NSPasteboard.general.setData(Data(html.utf8), forType: .html) }
                diagnostics.record(category: "clipboard", event: html == nil ? "text_received" : "rich_received")
                NSLog("PLUGIN clipboard received")
                current.send(PairingMessage(kind: "clipboard.ack", sessionId: current.id, messageId: messageID))
            case "clipboard.ack":
                guard message.messageId == clipboardSendID else { return }
                clipboardTimeoutWorkItem?.cancel()
                clipboardTimeoutWorkItem = nil
                clipboardSendID = nil
                clipboardStatus = "Delivered"
                NSLog("PLUGIN clipboard acknowledged")
                let ackCompletion = clipboardCompletion
                clipboardCompletion = nil
                ackCompletion?(true)
            case "clipboard.rejected":
                guard message.messageId == clipboardSendID else { return }
                clipboardTimeoutWorkItem?.cancel()
                clipboardTimeoutWorkItem = nil
                clipboardSendID = nil
                clipboardStatus = "Clipboard is turned off on Android"
                let rejectedCompletion = clipboardCompletion
                clipboardCompletion = nil
                rejectedCompletion?(false)
            case "find.start":
                try receiveFindCommand(message, in: current, start: true)
            case "find.stop":
                try receiveFindCommand(message, in: current, start: false)
            case "find.started":
                try receiveFindAcknowledgement(message, in: current, started: true)
            case "find.stopped":
                try receiveFindAcknowledgement(message, in: current, started: false)
            case "battery.update":
                guard featureEnabled(.battery, current: current) else { return }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let payload = try? JSONDecoder().decode(BatteryPayload.self, from: plaintext),
                      (0...100).contains(payload.level) else {
                    throw PairingError.invalidMessage
                }
                remoteBattery = RemoteBatteryStatus(level: payload.level, isCharging: payload.isCharging)
                NSLog("PLUGIN battery received level=%d charging=%@", payload.level, String(payload.isCharging))
            case "telemetry.update":
                // Not gated by a single telemetry feature here - each field block below
                // independently checks its own Settings toggle.
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let payload = try? JSONDecoder().decode(TelemetryPayload.self, from: plaintext) else {
                    throw PairingError.invalidMessage
                }
                if featureEnabled(.storage, current: current),
                   let used = payload.storageUsedBytes, let total = payload.storageTotalBytes {
                    guard total > 0, used >= 0, used <= total else { throw PairingError.invalidMessage }
                    remoteStorage = RemoteStorageStatus(usedBytes: used, totalBytes: total)
                    NSLog("PLUGIN storage received usedBytes=%lld totalBytes=%lld", used, total)
                }
                if featureEnabled(.memory, current: current),
                   let used = payload.memoryUsedBytes, let total = payload.memoryTotalBytes {
                    guard total > 0, used >= 0, used <= total else { throw PairingError.invalidMessage }
                    remoteMemory = RemoteMemoryStatus(usedBytes: used, totalBytes: total)
                    NSLog("PLUGIN memory received usedBytes=%lld totalBytes=%lld", used, total)
                }
                if featureEnabled(.cpu, current: current) {
                    if payload.cpuUnavailable == true {
                        remoteCpu = .unavailable
                        NSLog("PLUGIN cpu received unavailable")
                    } else if let percent = payload.cpuPercent {
                        guard (0...100).contains(percent) else { throw PairingError.invalidMessage }
                        remoteCpu = .available(percent)
                        NSLog("PLUGIN cpu received percent=%d", percent)
                    }
                }
                if featureEnabled(.temperature, current: current) {
                    if payload.temperatureUnavailable == true {
                        remoteTemperature = .unavailable
                        NSLog("PLUGIN temperature received unavailable")
                    } else if let thermalState = payload.thermalState {
                        remoteTemperature = .known(thermalState: thermalState, celsius: payload.temperatureCelsius)
                        NSLog("PLUGIN temperature received state=%@ celsius=%@", thermalState, payload.temperatureCelsius.map(String.init) ?? "nil")
                    }
                }
            case "telemetry.subscribe":
                guard case .connected = state,
                      message.sessionId == current.id else { return }
                startTelemetrySamplingLoop()
            case "telemetry.unsubscribe":
                guard message.sessionId == current.id else { return }
                stopTelemetrySamplingLoop()
            case "notifications.post":
                guard featureEnabled(.notifications, current: current) else { return }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let payload = try? JSONDecoder().decode(RemoteNotificationPayload.self, from: plaintext),
                      !payload.packageName.isEmpty,
                      !payload.applicationName.isEmpty,
                      payload.callType == nil || normalizedRemoteCallType(payload.callType) != nil,
                      (!payload.title.isEmpty || !payload.text.isEmpty) else {
                    throw PairingError.invalidMessage
                }
                recordNotificationHistory(payload, deviceID: current.remoteDeviceID)
                updateRemoteCall(payload, deviceID: current.remoteDeviceID)
                if shouldUseSystemNotification(callType: payload.callType) {
                    postNotification(payload, deviceID: current.remoteDeviceID)
                }
            case "notifications.remove":
                guard featureEnabled(.notifications, current: current),
                      let reference = try receiveNotificationReference(message, in: current) else { return }
                removeRemoteNotification(reference.notificationId, deviceID: current.remoteDeviceID)
            case "notifications.sync":
                guard featureEnabled(.notifications, current: current) else { return }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let payload = try? JSONDecoder().decode(NotificationSyncPayload.self, from: plaintext),
                      isValidNotificationSyncPayload(payload) else {
                    throw PairingError.invalidMessage
                }
                let deviceID = current.remoteDeviceID
                if let snapshot = notificationSyncAssembler.add(payload, deviceID: deviceID) {
                    reconcileRemoteNotifications(deviceID: deviceID, snapshot: snapshot)
                }
            case "calls.started", "calls.confirmation_required", "calls.rejected":
                guard message.messageId == callRequestID else { return }
                callTimeoutWorkItem?.cancel()
                callTimeoutWorkItem = nil
                callRequestID = nil
                switch message.kind {
                case "calls.started": setTransientCallStatus("Call started on Android")
                case "calls.confirmation_required": setTransientCallStatus("Confirm the call from the Android notification")
                default: setTransientCallStatus("Android rejected the call request")
                }
            case "calls.state":
                guard featureEnabled(.calls, current: current) else { return }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let payload = try? JSONDecoder().decode(CallStatePayload.self, from: plaintext),
                      payload.version == 1,
                      UUID(uuidString: payload.callId) != nil,
                      isKnownRemoteCallState(payload.state),
                      payload.callerName.utf8.count <= 128,
                      payload.callerNumber.utf8.count <= 64 else {
                    throw PairingError.invalidMessage
                }
                updateRemoteCallFromTelecom(payload, deviceID: current.remoteDeviceID)
            case "calls.action.ack":
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let payload = try? JSONDecoder().decode(CallActionAckPayload.self, from: plaintext),
                      payload.version == 1 else {
                    throw PairingError.invalidMessage
                }
                // Only surface a failure for the call currently displayed; an ack for a call
                // that already ended or was replaced is a normal race, not worth reporting.
                if !payload.accepted, remoteCall?.notificationID == payload.callId {
                    setTransientCallStatus("Android could not \(payload.action) the call")
                }
            case "media.remote.state":
                guard featureEnabled(.media, current: current) else { return }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      plaintext.count <= 32_768,
                      let payload = try? JSONDecoder().decode(MediaRemoteStatePayload.self, from: plaintext),
                      payload.version == 1 else {
                    throw PairingError.invalidMessage
                }
                mediaRemote.receive(payload)
            case "media.remote.action.ack":
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let payload = try? JSONDecoder().decode(MediaRemoteActionAckPayload.self, from: plaintext),
                      payload.version == 1 else {
                    throw PairingError.invalidMessage
                }
                mediaRemote.receiveAck(payload)
            case "video.offer", "video.accept", "video.reject", "video.stop",
                 "input.offer", "input.accept", "input.reject", "input.stop":
                receiveVideoChannelMessage(message, current: current)
            case "files.offer":
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let offer = try? JSONDecoder().decode(FileOfferPayload.self, from: plaintext),
                      UUID(uuidString: offer.transferId) != nil,
                      !offer.name.isEmpty,
                      offer.size >= 0,
                      offer.size <= 10 * 1024 * 1024 * 1024,
                      Data(base64Encoded: offer.sha256)?.count == 32 else {
                    throw PairingError.invalidMessage
                }
                // A files.offer carrying an assetKey is a Photo Sync send: gated by its own
                // feature flag and routed to the dedicated sync folder instead of the general
                // receive folder, with a dedup check against that folder's sync index.
                let requiredFeature: BridgeyFeature = offer.assetKey != nil ? .photoSync : .files
                guard featureEnabled(requiredFeature, current: current) else {
                    current.send(PairingMessage(
                        kind: "files.rejected",
                        sessionId: current.id,
                        transferId: offer.transferId
                    ))
                    sendFeatureState()
                    return
                }
                guard incomingFiles[offer.transferId] == nil else { throw PairingError.invalidMessage }
                let directoryAccess = offer.assetKey != nil ? settings.syncDirectoryAccess() : settings.receiveDirectoryAccess()
                if let assetKey = offer.assetKey, settings.isAssetSynced(assetKey, directory: directoryAccess.url) {
                    current.send(PairingMessage(
                        kind: "files.rejected",
                        sessionId: current.id,
                        transferId: offer.transferId
                    ))
                    return
                }
                let transfer = try IncomingFileTransfer(offer: offer, directoryAccess: directoryAccess)
                incomingFiles[offer.transferId] = transfer
                if let assetKey = offer.assetKey {
                    incomingSyncAssets[offer.transferId] = (assetKey, offer.mimeType.hasPrefix("video/"))
                }
                fileOperationID = UUID()
                beginFileTransferUI()
                fileTransferStatus = "Receiving \(transfer.displayName): \(transfer.progressStatus(force: true)!)"
                updateFileTransfer(id: offer.transferId, name: transfer.displayName, status: fileTransferStatus!, active: true)
                current.send(PairingMessage(
                    kind: "files.accept",
                    sessionId: current.id,
                    transferId: offer.transferId
                ))
                NSLog("PLUGIN file accepted name=%@ size=%lld", transfer.displayName, offer.size)
            case "files.chunk":
                if let transferID = message.transferId,
                   incomingFiles[transferID] == nil,
                   cancelledTransferIDs.contains(transferID) { return }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let transferID = message.transferId,
                      let sequence = message.sequence,
                      let transfer = incomingFiles[transferID],
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let chunk = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!) else {
                    throw PairingError.invalidMessage
                }
                try transfer.append(chunk, sequence: sequence)
                if let progress = transfer.progressStatus() {
                    fileTransferStatus = "Receiving \(transfer.displayName): \(progress)"
                    updateFileTransfer(id: transferID, name: transfer.displayName, status: fileTransferStatus!, active: true)
                }
            case "files.complete":
                guard case .connected = state,
                      message.sessionId == current.id,
                      let messageID = message.messageId,
                      current.acceptMessageID(messageID),
                      let nonce = message.nonce,
                      let ciphertext = message.ciphertext,
                      let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
                      let completion = try? JSONDecoder().decode(FileCompletePayload.self, from: plaintext) else {
                    throw PairingError.invalidMessage
                }
                guard let transfer = incomingFiles.removeValue(forKey: completion.transferId) else {
                    if cancelledTransferIDs.contains(completion.transferId) { return }
                    throw PairingError.invalidMessage
                }
                let syncAsset = incomingSyncAssets.removeValue(forKey: completion.transferId)
                do {
                    let destination = try transfer.finish(expectedHash: completion.sha256)
                    if let syncAsset {
                        settings.markAssetSynced(syncAsset.assetKey, directory: destination.deletingLastPathComponent())
                        let destinationMode = settings.syncDestination
                        if destinationMode.savesToPhotosLibrary {
                            PhotosImport.importAsset(at: destination, isVideo: syncAsset.isVideo) { success in
                                if !success {
                                    NSLog("PLUGIN photo sync: %@ was not added to Photos", destination.lastPathComponent)
                                } else if !destinationMode.savesToFolder {
                                    try? FileManager.default.removeItem(at: destination)
                                }
                            }
                        }
                    }
                    let folder = destination.deletingLastPathComponent().path
                    fileTransferStatus = "Saved \(transfer.displayName) to \(folder)"
                    updateFileTransfer(id: completion.transferId, name: transfer.displayName, status: fileTransferStatus!, active: false)
                    fileTransferActive = fileTransfers.values.contains(where: { $0.active })
                    fileOperationID = nil
                    current.send(PairingMessage(
                        kind: "files.complete.ack",
                        sessionId: current.id,
                        transferId: completion.transferId
                    ))
                    if syncAsset == nil { NSWorkspace.shared.activateFileViewerSelecting([destination]) }
                    NSLog("PLUGIN file received name=%@", transfer.displayName)
                } catch {
                    transfer.cancel()
                    fileTransferStatus = "File verification failed"
                    throw error
                }
            case "files.accept":
                if let transferID = message.transferId,
                   outgoingFiles[transferID] == nil,
                   cancelledTransferIDs.contains(transferID) { return }
                guard case .connected = state,
                      message.sessionId == current.id,
                      let transferID = message.transferId,
                      let transfer = outgoingFiles[transferID] else { throw PairingError.invalidMessage }
                updateFileTransfer(id: transferID, name: transfer.displayName, status: "Sending \(transfer.displayName)…", active: true)
                transfer.send(
                    through: current,
                    key: current.pairingKey!,
                    status: { [weak self] value in
                        guard let self, self.outgoingFiles[transferID] === transfer,
                              !transfer.isCancelled,
                              !self.cancelledTransferIDs.contains(transferID) else { return }
                        self.fileTransferStatus = value
                        self.updateFileTransfer(id: transferID, name: transfer.displayName, status: value, active: true)
                    },
                    completion: { [weak self, weak current] result in
                        guard let self, let current, self.activeSession === current else { return }
                        switch result {
                        case let .success(completion):
                            guard !transfer.isCancelled,
                                  self.outgoingFiles[transferID] === transfer,
                                  !self.cancelledTransferIDs.contains(transferID) else { return }
                            current.send(completion)
                        case .failure:
                            self.outgoingFiles.removeValue(forKey: transferID)
                            if transfer.isCancelled {
                                self.fileTransferStatus = "Transfer cancelled"
                                self.markFileTransferFinished(id: transferID, status: "Transfer cancelled")
                                return
                            } else {
                                self.fileTransferStatus = "File transfer failed"
                            }
                            self.fileTransferActive = self.fileTransfers.values.contains(where: { $0.active })
                            self.fileOperationID = nil
                            self.updateFileTransfer(id: transferID, name: transfer.displayName, status: self.fileTransferStatus!, active: false)
                        }
                    }
                )
            case "files.rejected":
                guard let transferID = message.transferId,
                      let transfer = outgoingFiles.removeValue(forKey: transferID) else { return }
                transfer.cancel()
                markTransferCancelled(transferID)
                markFileTransferFinished(id: transferID, status: "File transfer is turned off on Android")
                fileOperationID = nil
                filePreparationCancellation = nil
                fileTransferStatus = "File transfer is turned off on Android"
            case "files.complete.ack":
                guard let transferID = message.transferId,
                      let transfer = outgoingFiles.removeValue(forKey: transferID) else { return }
                outgoingFileSources.removeValue(forKey: transferID)
                fileTransferStatus = "\(transfer.displayName) saved on Android"
                updateFileTransfer(id: transferID, name: transfer.displayName, status: fileTransferStatus!, active: false)
                fileTransferActive = fileTransfers.values.contains(where: { $0.active })
                fileOperationID = nil
                filePreparationCancellation = nil
                NSLog("PLUGIN file sent name=%@", transfer.displayName)
            case "files.cancel":
                guard let transferID = message.transferId else { return }
                markTransferCancelled(transferID)
                incomingFiles.removeValue(forKey: transferID)?.cancel()
                incomingSyncAssets.removeValue(forKey: transferID)
                outgoingFiles.removeValue(forKey: transferID)?.cancel()
                markFileTransferFinished(id: transferID, status: "Transfer cancelled by Android")
                fileOperationID = nil
                fileTransferStatus = "Transfer cancelled by Android"
                current.send(PairingMessage(kind: "files.cancel.ack", sessionId: current.id, transferId: transferID))
                NSLog("PLUGIN file cancellation received transfer=%@", String(transferID.prefix(8)))
            case "files.cancel.ack":
                if let transferID = message.transferId {
                    markFileTransferFinished(id: transferID, status: "Transfer cancelled")
                }
            case "files.chunk.ack":
                if let transferID = message.transferId, let sequence = message.sequence {
                    outgoingFiles[transferID]?.acknowledge(sequence: sequence)
                }
            default:
                break
            }
        } catch {
            NSLog("PROTOCOL invalid message kind=%@ from peer=%@, closing that session", message.kind, String(current.remoteDeviceID.prefix(8)))
            current.close()
            diagnostics.record(category: "protocol", event: "message_rejected", outcome: "session_closed")
        }
    }

    /// Mirrors the routed session's capabilities into today's single-peer feature state.
    private func applyRemoteFeatures(_ capabilities: [String: Bool]) {
        remoteFeatures = Dictionary(uniqueKeysWithValues: BridgeyFeature.allCases.map {
            ($0, capabilities[$0.rawValue] ?? false)
        })
        remoteFeatureStateReceived = true
        if !isFeatureAvailable(.links) { quickActions.reset() }
        mediaController.reset()
        mediaRemote.reset()
        videoChannel.reset()
        screenStreamDecoder.reset()
        mediaController.refresh()
        if remoteFeatures[.battery] == false { remoteBattery = nil }
        if remoteFeatures[.storage] == false { remoteStorage = nil }
        if remoteFeatures[.memory] == false { remoteMemory = nil }
        if remoteFeatures[.cpu] == false { remoteCpu = nil }
        if remoteFeatures[.temperature] == false { remoteTemperature = nil }
        if remoteFeatures[.ping] == false { clearPingStatus() }
        if remoteFeatures[.clipboard] == false { clearClipboardSendStatus() }
        if remoteFeatures[.notifications] == false {
            clearRemoteCall()
            let peerID = activePeerID ?? ""
            removeAllRemoteNotifications(reason: "peer_feature_off") { $0 == peerID }
        }
        if remoteFeatures[.calls] == false { clearCallStatus() }
        if remoteFeatures[.files] == false && fileTransferActive {
            cancelFileTransfer()
            fileTransferStatus = nil
        }
        if remoteFeatures[.findDevice] == false {
            stopMacSound()
            androidRinging = false
        }
        flushPendingCallIfPossible()
        publishLocalBattery(force: true)
        publishLocalStorage(force: true)
        publishLocalMemory(force: true)
    }

    private func completeIfConfirmed(_ current: Session) {
        if current.localConfirmed && current.remoteConfirmed {
            let id = current.remoteDeviceID
            guard peers.deviceID(of: current) == id else { return }
            current.timeoutWork?.cancel()
            saveTrust(current)
            peers.markConnected(current)
            failureMessage = nil
            registry.recordConnection(deviceID: id, name: current.peerName, at: Date())
            diagnostics.record(category: "pairing", event: "connected")
            reconnectAttempts[id] = 0
            reconnectWork.removeValue(forKey: id)?.cancel()
            // Per-device last endpoint from current discovery, whichever side initiated.
            if let endpoint = registry.endpoints(for: id).first {
                lastEndpoints[id] = (endpoint.host, endpoint.port, current.peerName)
            }
            sendFeatureState(to: current)
            scheduleHeartbeat(for: current)
            if settings.preferredDeviceID == nil { settings.setPreferredDevice(id) }
            recomputeActivePeer()
            NSLog("PAIRING verified peer=%@", current.peerName)
        }
    }

    /// Sends every connected session its real local feature state (per-device settings). Never
    /// depends on which device is active.
    private func sendFeatureState() {
        for current in peers.identifiedSessions where peers.phase(of: current) == .connected {
            sendFeatureState(to: current)
        }
    }

    private func sendFeatureState(to current: Session) {
        guard peers.phase(of: current) == .connected, let key = current.pairingKey else { return }
        let features = PeerFeatureState.payload(for: current.remoteDeviceID) { [settings] in settings.isEnabled($0, for: $1) }
        guard let payload = try? JSONEncoder().encode(FeatureStatePayload(version: 1, features: features)),
              let encrypted = try? encrypt(payload, key: key) else { return }
        current.send(PairingMessage(
            kind: "features.update",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
    }

    private func cancelIncomingFiles() {
        let hadActiveTransfers = fileTransfers.values.contains(where: { $0.active })
        incomingFiles.values.forEach { $0.cancel() }
        incomingFiles.removeAll()
        outgoingFiles.values.forEach { $0.cancel() }
        outgoingFiles.removeAll()
        fileTransfers = recoverInterruptedTransfers(fileTransfers)
        fileTransferActive = false
        fileOperationID = nil
        filePreparationCancellation = nil
        if fileTransferStatus?.hasPrefix("Receiving ") == true {
            fileTransferStatus = "File transfer interrupted"
        }
        if hadActiveTransfers {
            diagnostics.record(category: "transfer", event: "interrupted", outcome: "retry_available")
        }
    }

    private func markTransferCancelled(_ transferID: String) {
        cancelledTransferIDs.insert(transferID)
        if cancelledTransferIDs.count > 64, let first = cancelledTransferIDs.first {
            cancelledTransferIDs.remove(first)
        }
    }

    private func updateFileTransfer(id: String, name: String, status: String, active: Bool) {
        let previous = fileTransfers[id]
        fileTransfers[id] = FileTransferRow(
            id: id,
            name: name,
            status: status,
            active: active,
            startedAt: previous?.startedAt ?? Date(),
            retryable: !active && outgoingFileSources[id] != nil
        )
        pruneTransferHistory()
        fileTransferActive = fileTransfers.values.contains(where: { $0.active })
    }

    private func markFileTransferFinished(id: String, status: String) {
        guard let transfer = fileTransfers[id] else { return }
        fileTransfers[id] = FileTransferRow(
            id: transfer.id,
            name: transfer.name,
            status: status,
            active: false,
            startedAt: transfer.startedAt,
            retryable: outgoingFileSources[id] != nil
        )
        pruneTransferHistory()
        fileTransferActive = fileTransfers.values.contains(where: { $0.active })
    }

    private func pruneTransferHistory() {
        let active = fileTransfers.values.filter { $0.active }
        let history = fileTransfers.values.filter { !$0.active }
            .sorted { $0.startedAt > $1.startedAt }
            .prefix(maximumTransferHistory)
        fileTransfers = Dictionary(uniqueKeysWithValues: (active + Array(history)).map { ($0.id, $0) })
    }

    private func authenticateOrPrompt(_ current: Session) {
        if registry.identityKey(for: current.remoteDeviceID) != nil {
            confirm(current)
            return
        }
        if let other = peers.verifyingSession, other !== current {
            // One verification code on screen at a time; the other device can retry.
            NSLog("PAIRING another device is being verified, rejecting peer=%@", String(current.remoteDeviceID.prefix(8)))
            current.send(PairingMessage(kind: "pairing.cancel", sessionId: current.id))
            endSession(current, scheduleReconnect: false)
            return
        }
        peers.setPhase(.verifying, for: current)
        refreshState()
    }

    private func authTranscript(_ current: Session) -> Data {
        let fields = current.initiatedLocally
            ? [deviceID, current.remoteDeviceID, current.localEphemeralKey, current.remoteEphemeralKey]
            : [current.remoteDeviceID, deviceID, current.remoteEphemeralKey, current.localEphemeralKey]
        return Data((["bridgey-auth-v1", current.id] + fields).joined(separator: "\0").utf8)
    }

    private func verifySignature(_ value: String, identityKey: String, data: Data) -> Bool {
        guard let publicData = Data(base64Encoded: identityKey),
              let signatureData = Data(base64Encoded: value),
              let publicKey = try? P256.Signing.PublicKey(x963Representation: publicData),
              let signature = try? P256.Signing.ECDSASignature(derRepresentation: signatureData) else { return false }
        return publicKey.isValidSignature(signature, for: data)
    }

    /// Dials every trusted, discovered device that has no session yet (lower deviceId dials).
    private func connectTrustedPeersIfNeeded() {
        let targets = ReconnectPlanner.discoveryDialTargets(
            localDeviceID: deviceID,
            trustedDeviceIDs: registry.trustedDeviceIDs,
            presence: registry.presence,
            endpointIndex: { [reconnectAttempts] in reconnectAttempts[$0, default: 0] },
            isBusy: { [peers, reconnectWork] in peers.isBusy($0) || reconnectWork[$0] != nil }
        )
        for target in targets {
            let name = registry.presence[target.deviceID]?.name ?? "Bridgey device"
            NSLog("RECONNECT discovery match peer=%@", name)
            dial(host: target.endpoint.host, port: target.endpoint.port, peerName: name, expectedDeviceID: target.deviceID)
        }
    }

    /// Per-device reconnect backoff. One device's retries never affect another device.
    private func scheduleReconnect(_ deviceID: String) {
        let attempt = reconnectAttempts[deviceID, default: 0]
        let known = registry.endpoints(for: deviceID)
        // Retries rotate through a device's endpoints, so a stale advert cannot pin every attempt.
        guard let endpoint = (known.isEmpty ? nil : known[attempt % known.count]).map({ (host: $0.host, port: $0.port) })
                ?? lastEndpoints[deviceID].map({ (host: $0.host, port: $0.port) }) else {
            NSLog("RECONNECT skipped: no known endpoint for peer=%@", String(deviceID.prefix(8)))
            return
        }
        reconnectWork[deviceID]?.cancel()
        // Jitter is added here (not inside reconnectDelay, which stays a pure, tested function)
        // so two devices racing to reconnect at the same moment don't stay in lockstep and keep
        // colliding on every subsequent retry.
        let delay = reconnectDelay(attempt: attempt) + TimeInterval.random(in: 0..<1)
        reconnectAttempts[deviceID] = attempt + 1
        let name = registry.device(deviceID)?.name ?? lastEndpoints[deviceID]?.name ?? "Bridgey device"
        NSLog("RECONNECT scheduling attempt to peer=%@ %@:%d in %.1fs", String(deviceID.prefix(8)), endpoint.host, endpoint.port, delay)
        diagnostics.record(category: "reconnect", event: "scheduled")
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.reconnectWork[deviceID] = nil
            guard !self.peers.isBusy(deviceID), self.registry.trustedDeviceIDs.contains(deviceID) else { return }
            let current = self.registry.endpoints(for: deviceID)
            let target = (current.isEmpty ? nil : current[attempt % current.count]).map { (host: $0.host, port: $0.port) } ?? endpoint
            NSLog("RECONNECT attempting peer=%@ %@:%d", String(deviceID.prefix(8)), target.host, target.port)
            self.diagnostics.record(category: "reconnect", event: "attempt")
            self.dial(host: target.host, port: target.port, peerName: name, expectedDeviceID: deviceID)
        }
        reconnectWork[deviceID] = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    /// Times out a session that has not finished its handshake (verification waits for the user).
    private func scheduleConnectionTimeout(for current: Session) {
        current.timeoutWork?.cancel()
        let timeout = DispatchWorkItem { [weak self, weak current] in
            guard let self, let current, self.peers.contains(current) else { return }
            let phase = self.peers.phase(of: current)
            guard phase == nil || phase == .connecting else { return }
            NSLog("TRANSPORT connection timed out")
            self.endSession(current, scheduleReconnect: true)
        }
        current.timeoutWork = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 8, execute: timeout)
    }

    // MARK: - NOTIFICATION CLICK ACTIONS (see NotificationActionRouting.swift)

    private func handleNotificationClick(_ notificationID: String, deviceID: String, content: UNNotificationContent) {
        let identifier = remoteNotificationRequestIdentifier(deviceID: deviceID, notificationID: notificationID)
        // macOS removes a clicked notification from Notification Center without a dismiss callback;
        // it must never count towards an inferred Clear All.
        clearAllDetector.noteExplainedRemoval([identifier], at: Date())
        let phoneOpenToken = remoteNotificationCategories[content.categoryIdentifier]?.actions
            .first(where: { $0.title == "Open" })?.identifier
        let context = NotificationClickContext(
            androidPackage: content.userInfo["androidPackage"] as? String,
            conversationID: content.userInfo["androidConversationId"] as? String,
            canOpenOnPhone: phoneOpenToken != nil && isConnected(to: deviceID)
        )
        let decision = notificationActions.route(context)
        NSLog("PLUGIN notification click package=%@ decision=%@", context.androidPackage ?? "-", String(describing: decision))
        switch decision {
        case .doNothing:
            finishNotificationClick(identifier: identifier, notificationID: notificationID, deviceID: deviceID, content: content, clear: false)
        case let .openOnPhone(clear):
            if let phoneOpenToken {
                performAndroidNotificationAction(notificationID, deviceID: deviceID, actionToken: phoneOpenToken, replyText: nil)
            }
            finishNotificationClick(identifier: identifier, notificationID: notificationID, deviceID: deviceID, content: content, clear: clear)
        case .openApplication, .openURL:
            openNotificationClickTarget(decision) { [weak self] success, detail in
                NSLog("PLUGIN notification click open success=%@ detail=%@", String(success), detail)
                // A failed open never clears: the notification stays where it is.
                self?.finishNotificationClick(identifier: identifier, notificationID: notificationID, deviceID: deviceID,
                                              content: content, clear: success && decision.clears)
            }
        case let .ask(clear):
            askNotificationClick(identifier: identifier, notificationID: notificationID, deviceID: deviceID, content: content,
                                 context: context, phoneOpenToken: phoneOpenToken, clear: clear)
        }
    }

    /// The `ask` action: a small native choice, optionally remembered as a per-app rule.
    private func askNotificationClick(identifier: String, notificationID: String, deviceID: String, content: UNNotificationContent,
                                      context: NotificationClickContext, phoneOpenToken: String?, clear: Bool) {
        let package = context.androidPackage
        let appName = package.flatMap { notificationActions.seenApps[$0] } ?? content.title
        let alert = NSAlert()
        alert.messageText = "Open notification from \(appName)"
        alert.informativeText = "Choose what Bridgey should do with this notification. Rules can be changed in Settings → Notification click actions."
        alert.addButton(withTitle: "Choose Mac app…")
        if context.canOpenOnPhone { alert.addButton(withTitle: "Open on phone") }
        alert.addButton(withTitle: "Cancel")
        if package != nil {
            alert.showsSuppressionButton = true
            alert.suppressionButton?.title = "Always do this for \(appName)"
        }
        NSApp.activate(ignoringOtherApps: true)
        let response = alert.runModal()
        let remember = alert.suppressionButton?.state == .on
        let clearBehavior: NotificationClearBehavior = clear ? .clear : .keep
        switch response {
        case .alertFirstButtonReturn:
            guard let app = NotificationActionSettings.chooseMacApplication() else {
                return finishNotificationClick(identifier: identifier, notificationID: notificationID, deviceID: deviceID, content: content, clear: false)
            }
            if remember, let package {
                notificationActions.upsert(NotificationActionRule(androidPackage: package, displayName: appName, action: .openNativeApp,
                                                                  macAppBundleIdentifier: app.bundleIdentifier, macAppName: app.name,
                                                                  url: nil, clearBehavior: clearBehavior))
            }
            let decision = NotificationClickDecision.openApplication(bundleIdentifier: app.bundleIdentifier, clear: clear)
            openNotificationClickTarget(decision) { [weak self] success, detail in
                NSLog("PLUGIN notification click open success=%@ detail=%@", String(success), detail)
                self?.finishNotificationClick(identifier: identifier, notificationID: notificationID, deviceID: deviceID,
                                              content: content, clear: success && clear)
            }
        case .alertSecondButtonReturn where context.canOpenOnPhone:
            if remember, let package {
                notificationActions.upsert(NotificationActionRule(androidPackage: package, displayName: appName, action: .openOnPhone,
                                                                  macAppBundleIdentifier: nil, macAppName: nil, url: nil, clearBehavior: clearBehavior))
            }
            if let phoneOpenToken {
                performAndroidNotificationAction(notificationID, deviceID: deviceID, actionToken: phoneOpenToken, replyText: nil)
            }
            finishNotificationClick(identifier: identifier, notificationID: notificationID, deviceID: deviceID, content: content, clear: clear)
        default:
            finishNotificationClick(identifier: identifier, notificationID: notificationID, deviceID: deviceID, content: content, clear: false)
        }
    }

    /// Clear = exactly what closing the notification does (removed on the Mac, dismissed on the
    /// phone; Android stays authoritative, so while disconnected the next resync brings it back).
    /// Keep = the notification stays on both: macOS drops a clicked notification from Notification
    /// Center, so Bridgey re-adds the same request silently (without the icon attachment, which
    /// macOS already consumed).
    private func finishNotificationClick(identifier: String, notificationID: String, deviceID: String,
                                         content: UNNotificationContent, clear: Bool) {
        let center = UNUserNotificationCenter.current()
        if clear {
            center.removeDeliveredNotifications(withIdentifiers: [identifier])
            if isConnected(to: deviceID) {
                dismissAndroidNotification(notificationID, deviceID: deviceID)
            } else {
                NSLog("PLUGIN notification click clear: phone not connected, Android keeps it until the next sync")
            }
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            center.getDeliveredNotifications { delivered in
                guard !delivered.contains(where: { $0.request.identifier == identifier }),
                      let copy = content.mutableCopy() as? UNMutableNotificationContent else { return }
                copy.attachments = []
                copy.sound = nil
                copy.interruptionLevel = .passive
                copy.userInfo["resync"] = true
                DispatchQueue.main.async {
                    self?.notificationPostedAt[identifier] = Date()
                    center.add(UNNotificationRequest(identifier: identifier, content: copy, trigger: nil)) { error in
                        NSLog("PLUGIN notification click keep: re-added error=%@", error.map { String(describing: $0) } ?? "none")
                    }
                }
            }
        }
    }

    private func isConnected(to deviceID: String) -> Bool {
        if case let .connected(connectedDeviceID, _) = state, connectedDeviceID == deviceID, activeSession != nil { return true }
        return false
    }

    // MARK: - BRIDGEY NOTIFICATION++ macOS CLEAR ALL (see NotificationClearAllDetector.swift)

    private var clearAllDetector = NotificationClearAllDetector()
    /// Start of the current settling window: new session or notification-permission change. Posts
    /// extend it through notificationPostedAt.
    private var clearAllSettlingStartedAt = Date.distantPast
    private var clearAllNotificationsAuthorized: Bool?

    private func checkNotificationClearAll() {
        guard case let .connected(deviceID, _) = state, let current = activeSession else { return }
        let forwardingAvailable = isFeatureAvailable(.notifications) && featureEnabled(.notifications, current: current)
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            // Turning Bridgey notifications off in System Settings also empties Notification Center;
            // that must never look like a user Clear All.
            let authorized = settings.authorizationStatus == .authorized && settings.notificationCenterSetting == .enabled
            center.getDeliveredNotifications { delivered in
                var entries: [String: ClearAllSnapshotEntry] = [:]
                for notification in delivered {
                    let userInfo = notification.request.content.userInfo
                    guard let itemDeviceID = userInfo["androidDeviceId"] as? String,
                          let notificationID = userInfo["androidNotificationId"] as? String else { continue }
                    entries[notification.request.identifier] = ClearAllSnapshotEntry(deviceID: itemDeviceID, notificationID: notificationID)
                }
                DispatchQueue.main.async { [weak self] in
                    self?.applyNotificationClearAll(entries, deviceID: deviceID, active: forwardingAvailable, authorized: authorized)
                }
            }
        }
    }

    private func applyNotificationClearAll(_ current: [String: ClearAllSnapshotEntry], deviceID: String, active: Bool, authorized: Bool) {
        let now = Date()
        if clearAllNotificationsAuthorized != authorized {
            clearAllNotificationsAuthorized = authorized
            clearAllSettlingStartedAt = now
        }
        let lastPost = notificationPostedAt.values.max() ?? .distantPast
        let settling = now.timeIntervalSince(lastPost) < NotificationClearAllDetector.settlingInterval ||
            now.timeIntervalSince(clearAllSettlingStartedAt) < NotificationClearAllDetector.settlingInterval
        let previous = clearAllDetector.snapshot.count
        let action = clearAllDetector.observe(current: current, active: active && authorized, settling: settling, now: now)
        switch action {
        case .inactive, .settling, .update:
            break
        case .zeroPending:
            NSLog("PLUGIN notification clear_all zero_pending previous=%d", previous)
        case .explained:
            NSLog("PLUGIN notification clear_all explained_by_bridgey previous=%d", previous)
        case .dismissMany(let entries):
            let notificationIDs = entries.filter { $0.deviceID == deviceID }.map(\.notificationID)
            NSLog("PLUGIN notification clear_all detected previous=%d dismissing=%d", previous, notificationIDs.count)
            sendNotificationDismissMany(notificationIDs, deviceID: deviceID)
        }
    }

    private func sendNotificationDismissMany(_ notificationIDs: [String], deviceID: String) {
        guard !notificationIDs.isEmpty,
              case let .connected(connectedDeviceID, _) = state,
              connectedDeviceID == deviceID,
              let current = activeSession,
              current.remoteDeviceID == deviceID,
              isFeatureAvailable(.notifications),
              featureEnabled(.notifications, current: current) else { return }
        for part in notificationDismissManyParts(notificationIDs) {
            guard let plaintext = try? JSONSerialization.data(withJSONObject: [
                "version": 1,
                "reason": "mac_clear_all",
                "notificationIds": part,
            ]),
                  let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { continue }
            current.send(PairingMessage(
                kind: "notifications.dismissMany",
                sessionId: current.id,
                messageId: UUID().uuidString.lowercased(),
                nonce: encrypted.nonce,
                ciphertext: encrypted.ciphertext
            ))
        }
        diagnostics.record(category: "notification", event: "dismiss_many_sent")
    }

    /// Per-session heartbeat; a timeout ends only this session.
    private func scheduleHeartbeat(for current: Session) {
        current.heartbeatWork?.cancel()
        let work = DispatchWorkItem { [weak self, weak current] in
            guard let self, let current, self.peers.phase(of: current) == .connected else { return }
            let sinceLastReceived = Date().timeIntervalSince(current.lastReceivedAt)
            if heartbeatExpired(supported: current.heartbeatSupported, lastReceivedAt: current.lastReceivedAt) {
                NSLog("CONNECTION lost: heartbeat timed out (sinceLastReceived=%.1fs) peer=%@", sinceLastReceived, String(current.remoteDeviceID.prefix(8)))
                current.close()
                return
            }
            current.send(PairingMessage(
                kind: "heartbeat.ping",
                sessionId: current.id,
                messageId: UUID().uuidString.lowercased()
            ))
            if current === self.activeSession {
                self.publishLocalBattery()
                self.mediaController.refresh()
                self.checkNotificationClearAll() // piggybacks on the existing heartbeat, no new timer
            }
            self.scheduleHeartbeat(for: current)
        }
        current.heartbeatWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: work)
    }

    private func postNotification(_ payload: RemoteNotificationPayload, deviceID: String) {
        if payload.callType == nil {
            notificationActions.observe(androidPackage: payload.packageName, displayName: payload.applicationName)
        }
        let content = UNMutableNotificationContent()
        content.title = payload.callType == nil ? payload.applicationName : remoteCallStatusTitle(payload.callType)
        content.subtitle = payload.title
        content.body = payload.callType == nil ? payload.text : remoteCallDetail(payload.text, type: payload.callType)
        content.categoryIdentifier = notificationCategoryIdentifier(for: payload, deviceID: deviceID)
        // BRIDGEY NOTIFICATION++ PHASE 2: lets macOS's own native Notification Center grouping
        // collapse repeated notifications for the same Android logical identity (e.g. the same
        // WhatsApp conversation) under one thread, instead of appearing as unrelated entries.
        let identifier = remoteNotificationRequestIdentifier(deviceID: deviceID, notificationID: payload.notificationId)
        content.threadIdentifier = identifier
        let audible = shouldPlayNotificationSound(
            hasSound: payload.hasSound,
            timestamp: payload.timestamp,
            lastPlayedTimestamp: lastNotificationSoundTimestamp[identifier]
        )
        let resync = payload.resync == true
        let now = Date()
        notificationPostedAt = notificationPostedAt.filter { now.timeIntervalSince($0.value) < 120 }
        notificationPostedAt[identifier] = now
        content.sound = audible && !resync ? .default : nil
        if resync { content.interruptionLevel = .passive }
        if lastNotificationSoundTimestamp.count > 500 { lastNotificationSoundTimestamp.removeAll() }
        lastNotificationSoundTimestamp[identifier] = max(lastNotificationSoundTimestamp[identifier] ?? 0, payload.timestamp)
        if let attachment = notificationIconAttachment(for: payload) {
            content.attachments = [attachment]
        }
        content.userInfo = [
            "androidPackage": payload.packageName,
            "androidNotificationId": payload.notificationId,
            "androidDeviceId": deviceID,
            "resync": resync,
        ]
        if let conversationID = payload.conversationId, !conversationID.isEmpty, conversationID.count <= 256 {
            content.userInfo["androidConversationId"] = conversationID // for {conversationId} URL rules
        }
        let request = UNNotificationRequest(
            identifier: identifier,
            content: content,
            trigger: nil
        )
        let attachmentFile = content.attachments.first?.url
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                // On success the system has moved the copy; on failure it is ours to clean up.
                if let attachmentFile { try? FileManager.default.removeItem(at: attachmentFile) }
                NSLog("PLUGIN notification delivery failed package=%@ error=%@", payload.packageName, String(describing: error))
            } else {
                NSLog("PLUGIN notification received package=%@", payload.packageName)
            }
        }
    }

    // performRemoteCallAction / hideCallOverlay / updateRemoteCall are defined in Calls.swift.

    func clearNotificationHistory() {
        notificationHistoryStore.clear()
        notificationHistory = []
    }

    private func recordNotificationHistory(_ payload: RemoteNotificationPayload, deviceID: String) {
        guard settings.notificationHistoryEnabled else { return }
        let item = NotificationHistoryItem(
            id: remoteNotificationRequestIdentifier(deviceID: deviceID, notificationID: payload.notificationId),
            packageName: String(payload.packageName.prefix(256)),
            applicationName: String(payload.applicationName.prefix(128)),
            title: String(payload.title.prefix(1_024)),
            text: String(payload.text.prefix(8_192)),
            receivedAt: Date()
        )
        notificationHistory = notificationHistoryStore.record(item, in: notificationHistory)
    }

    private func notificationIconAttachment(for payload: RemoteNotificationPayload) -> UNNotificationAttachment? {
        guard let data = remoteNotificationIconData(payload.applicationIcon) else { return nil }
        let fileManager = FileManager.default
        guard let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        let directory = caches.appendingPathComponent("Bridgey/NotificationIcons", isDirectory: true)
        // UNUserNotificationCenter MOVES an attachment's file into its own data store on add(), so a
        // file shared between posts is gone for every concurrent or later post of the same app
        // ("Failed to move attachment file into data store") - and a failed add() of a replacement
        // also drops the notification it was replacing. Every post therefore gets its own copy.
        let file = directory.appendingPathComponent(
            remoteNotificationIconFileName(packageName: payload.packageName, data: data, uniqueSuffix: UUID().uuidString)
        )
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            try data.write(to: file, options: .atomic)
            return try UNNotificationAttachment(
                identifier: "android-app-icon",
                url: file,
                options: [UNNotificationAttachmentOptionsTypeHintKey: UTType.png.identifier]
            )
        } catch {
            try? fileManager.removeItem(at: file)
            NSLog("PLUGIN notification icon attachment failed package=%@ error=%@", payload.packageName, String(describing: error))
            return nil
        }
    }

    private func notificationCategoryIdentifier(for payload: RemoteNotificationPayload, deviceID: String) -> String {
        let actions = (payload.actions ?? []).prefix(4).compactMap { action -> UNNotificationAction? in
            guard action.actionToken.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
                  !action.title.isEmpty,
                  action.title.count <= 64 else { return nil }
            if action.allowsReply {
                return UNTextInputNotificationAction(
                    identifier: action.actionToken,
                    title: action.title,
                    options: [],
                    textInputButtonTitle: "Send",
                    textInputPlaceholder: "Reply"
                )
            }
            return UNNotificationAction(identifier: action.actionToken, title: action.title, options: [])
        }
        guard !actions.isEmpty else { return "bridgey.android.notification" }
        let categoryID = remoteNotificationCategoryIdentifier(
            deviceID: deviceID,
            notificationID: payload.notificationId,
            actionTokens: actions.map(\.identifier)
        )
        remoteNotificationCategories[categoryID] = UNNotificationCategory(
            identifier: categoryID,
            actions: actions,
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        while remoteNotificationCategories.count > 129,
              let staleID = remoteNotificationCategories.keys.first(where: { $0 != "bridgey.android.notification" }) {
            remoteNotificationCategories.removeValue(forKey: staleID)
        }
        UNUserNotificationCenter.current().setNotificationCategories(Set(remoteNotificationCategories.values))
        return categoryID
    }

    private func dismissAndroidNotification(_ notificationID: String, deviceID: String) {
        guard case let .connected(connectedDeviceID, _) = state,
              connectedDeviceID == deviceID,
              let current = activeSession,
              current.remoteDeviceID == deviceID,
              featureEnabled(.notifications, current: current),
              !notificationID.isEmpty,
              notificationID.count <= 512,
              let plaintext = try? JSONEncoder().encode(NotificationReferencePayload(notificationId: notificationID)),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        current.send(PairingMessage(
            kind: "notifications.dismiss",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        diagnostics.record(category: "notification", event: "dismiss_sent")
    }

    // Called from performRemoteCallAction in Calls.swift, hence not `private`.
    func performAndroidNotificationAction(
        _ notificationID: String,
        deviceID: String,
        actionToken: String,
        replyText: String?,
        route: String? = nil
    ) {
        guard case let .connected(connectedDeviceID, _) = state,
              connectedDeviceID == deviceID,
              let current = activeSession,
              current.remoteDeviceID == deviceID,
              featureEnabled(.notifications, current: current),
              !notificationID.isEmpty,
              notificationID.count <= 512,
              actionToken.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              (replyText?.count ?? 0) <= 4_096,
              isValidAudioRoute(route),
              let plaintext = try? JSONEncoder().encode(NotificationActionCommandPayload(
                notificationId: notificationID,
                actionToken: actionToken,
                replyText: replyText,
                route: route
              )),
              let encrypted = try? encrypt(plaintext, key: current.pairingKey!) else { return }
        current.send(PairingMessage(
            kind: "notifications.action",
            sessionId: current.id,
            messageId: UUID().uuidString.lowercased(),
            nonce: encrypted.nonce,
            ciphertext: encrypted.ciphertext
        ))
        diagnostics.record(category: "notification", event: replyText == nil ? "action_sent" : "reply_sent")
    }

    private func receiveNotificationReference(_ message: PairingMessage, in current: Session) throws -> NotificationReferencePayload? {
        guard case .connected = state,
              message.sessionId == current.id,
              let messageID = message.messageId,
              current.acceptMessageID(messageID),
              let nonce = message.nonce,
              let ciphertext = message.ciphertext,
              let plaintext = try? decrypt(nonce: nonce, ciphertext: ciphertext, key: current.pairingKey!),
              let payload = try? JSONDecoder().decode(NotificationReferencePayload.self, from: plaintext),
              !payload.notificationId.isEmpty,
              payload.notificationId.count <= 512 else {
            throw PairingError.invalidMessage
        }
        return payload
    }

    private func removeRemoteNotification(_ notificationID: String, deviceID: String) {
        if remoteCall?.notificationID == notificationID && remoteCall?.deviceID == deviceID {
            mediaController.callChanged(active: false)
            remoteCall = nil
            callOverlayWindow.hide()
            hiddenCallOverlayIdentity = nil
            audibleCallIdentity = nil
        }
        let identifier = remoteNotificationRequestIdentifier(deviceID: deviceID, notificationID: notificationID)
        let center = UNUserNotificationCenter.current()
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        clearAllDetector.noteExplainedRemoval([identifier], at: Date()) // Bridgey-initiated, not a user Clear All
        diagnostics.record(category: "notification", event: "removed_remotely")
    }

    /// BRIDGEY NOTIFICATION++ RECONCILIATION: Android's complete eligible set for [deviceID] has
    /// arrived - remove every delivered notification of that device Android no longer has, and the
    /// notification-driven call card if its notification is gone. Never creates anything (Android
    /// re-posts live notifications before the sync) and never touches other devices.
    private func reconcileRemoteNotifications(deviceID: String, snapshot: Set<String>) {
        if let call = remoteCall, call.deviceID == deviceID, call.source == .notification,
           !snapshot.contains(call.notificationID) {
            removeRemoteNotification(call.notificationID, deviceID: deviceID)
        }
        let appliedAt = Date()
        removeDeliveredRemoteNotifications(reason: "sync") { [weak self] delivered in
            staleRemoteNotificationIdentifiers(delivered: delivered, deviceID: deviceID, snapshot: snapshot).filter { identifier in
                guard let postedAt = self?.notificationPostedAt[identifier] else { return true }
                return postedAt < appliedAt
            }
        }
    }

    /// Reads Notification Center's delivered list, lets [select] choose request identifiers to
    /// remove (on the main actor), and removes exactly those. Non-Bridgey notifications carry no
    /// Android userInfo and are never selected by the callers' pure selectors.
    private func removeDeliveredRemoteNotifications(
        reason: String,
        select: @escaping @MainActor ([DeliveredRemoteNotification]) -> [String]
    ) {
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { delivered in
            let items = delivered.map { notification in
                DeliveredRemoteNotification(
                    requestIdentifier: notification.request.identifier,
                    androidDeviceID: notification.request.content.userInfo["androidDeviceId"] as? String,
                    androidNotificationID: notification.request.content.userInfo["androidNotificationId"] as? String
                )
            }
            DispatchQueue.main.async {
                let identifiers = select(items)
                if !identifiers.isEmpty {
                    center.removeDeliveredNotifications(withIdentifiers: identifiers)
                    self.clearAllDetector.noteExplainedRemoval(identifiers, at: Date()) // Bridgey-initiated, not a user Clear All
                }
                NSLog("PLUGIN notification reconcile reason=%@ delivered=%d removed=%d", reason, items.count, identifiers.count)
            }
        }
    }

    /// Notification forwarding is off for the selected devices: clear everything mirrored from them.
    private func removeAllRemoteNotifications(reason: String, forDevice isAffected: @escaping @MainActor (String) -> Bool) {
        if let call = remoteCall, call.source == .notification, isAffected(call.deviceID) {
            removeRemoteNotification(call.notificationID, deviceID: call.deviceID)
        }
        removeDeliveredRemoteNotifications(reason: reason) { delivered in
            delivered.compactMap { item in
                guard let deviceID = item.androidDeviceID, item.androidNotificationID != nil, isAffected(deviceID) else { return nil }
                return item.requestIdentifier
            }
        }
    }

    // clearRemoteCall / callOverlayIdentity are defined in Calls.swift.

    private func saveTrust(_ current: Session) {
        guard let identityKey = current.remoteIdentityKey else { return }
        registry.remember(deviceID: current.remoteDeviceID, name: current.peerName, identityKey: identityKey)
        trustedDeviceIDs = registry.trustedDeviceIDs
    }

}

private final class Session {
    let connection: NWConnection
    // Used by VideoChannelController to dial the dedicated video/input socket to the same peer this
    // control session is already talking to.
    var remoteHost: String?
    var peerName: String
    var remoteDeviceID = ""
    var remoteIdentityKey: String?
    var initiatedLocally = false
    var localEphemeralKey = ""
    var remoteEphemeralKey = ""
    var id = ""
    var privateKey: P256.KeyAgreement.PrivateKey?
    var code: String?
    var pairingKey: Data?
    var localConfirmed = false
    var remoteConfirmed = false
    var lastReceivedAt = Date()
    var heartbeatSupported = false
    // Per-session lifecycle (multi-device Core): each device's session owns its own timers.
    var heartbeatWork: DispatchWorkItem?
    var timeoutWork: DispatchWorkItem?
    /// The device an outgoing dial targets (discovery hint) until the peer identifies itself.
    var expectedDeviceID: String?
    var unconsumedFeatureMessages = 0
    var onMessage: ((PairingMessage) -> Void)?
    var onFailure: (() -> Void)?
    private var buffer = Data()
    private var seenMessageIDs = Set<String>()
    var quickSequence = QuickRequestSequence()

    init(connection: NWConnection, peerName: String) {
        self.connection = connection
        self.peerName = peerName
    }

    func send(_ message: PairingMessage) {
        guard let encoded = try? JSONEncoder().encode(message) else { return }
        connection.send(content: encoded + Data([0x0A]), completion: .contentProcessed { error in
            if let error { NSLog("TRANSPORT send failed kind=%@ error=%@", message.kind, String(describing: error)) }
        })
    }

    func sendAndWait(_ message: PairingMessage) -> Bool {
        guard let encoded = try? JSONEncoder().encode(message) else { return false }
        let semaphore = DispatchSemaphore(value: 0)
        var succeeded = false
        connection.send(content: encoded + Data([0x0A]), completion: .contentProcessed { error in
            succeeded = error == nil
            if let error { NSLog("TRANSPORT send failed kind=%@ error=%@", message.kind, String(describing: error)) }
            semaphore.signal()
        })
        return semaphore.wait(timeout: .now() + 15) == .success && succeeded
    }

    func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, complete, error in
            DispatchQueue.main.async {
                guard let self else { return }
                if let data { self.buffer.append(data) }
                while let newline = self.buffer.firstIndex(of: 0x0A) {
                    let line = self.buffer[..<newline]
                    self.buffer.removeSubrange(...newline)
                    do {
                        let message = try decodeProtocolMessage(Data(line))
                        self.lastReceivedAt = Date()
                        self.onMessage?(message)
                    } catch {
                        NSLog("TRANSPORT invalid message error=%@", String(describing: error))
                        self.close()
                        self.onFailure?()
                        return
                    }
                }
                guard self.buffer.count <= maximumProtocolFrameBytes else {
                    NSLog("TRANSPORT protocol frame exceeded size limit")
                    self.close()
                    self.onFailure?()
                    return
                }
                if complete || error != nil { self.onFailure?() } else { self.receive() }
            }
        }
    }

    func close() { connection.cancel() }

    func acceptMessageID(_ id: String) -> Bool {
        guard seenMessageIDs.insert(id).inserted else { return false }
        if seenMessageIDs.count > 256 { seenMessageIDs.remove(seenMessageIDs.first!) }
        return true
    }
}

struct PairingMessage: Codable {
    let kind: String
    let sessionId: String
    var deviceId: String? = nil
    var deviceName: String? = nil
    var publicKey: String? = nil
    var identityKey: String? = nil
    var proof: String? = nil
    var signature: String? = nil
    var messageId: String? = nil
    var nonce: String? = nil
    var ciphertext: String? = nil
    var transferId: String? = nil
    var sequence: Int64? = nil
    var status: String? = nil
}

enum PairingError: Error { case invalidMessage, cancelled }

private final class FileCancellationToken: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    var isCancelled: Bool { lock.withLock { value } }
    func cancel() { lock.withLock { value = true } }
}

private final class OutgoingFileTransfer {
    private static let chunkSize = 24 * 1024
    private static let acknowledgementWindow: Int64 = 64
    let transferID = UUID().uuidString.lowercased()
    let displayName: String
    let offer: FileOfferPayload
    private let url: URL
    private let lock = NSLock()
    private var cancelled = false
    private let acknowledgement = NSCondition()
    private var acknowledgedSequence: Int64 = -1
    var isCancelled: Bool { lock.withLock { cancelled } }

    func cancel() {
        lock.withLock { cancelled = true }
        acknowledgement.lock()
        acknowledgement.broadcast()
        acknowledgement.unlock()
    }

    func acknowledge(sequence: Int64) {
        acknowledgement.lock()
        acknowledgedSequence = max(acknowledgedSequence, sequence)
        acknowledgement.broadcast()
        acknowledgement.unlock()
    }

    private func waitForAcknowledgement(sequence: Int64) throws {
        acknowledgement.lock()
        defer { acknowledgement.unlock() }
        let deadline = Date().addingTimeInterval(10)
        while acknowledgedSequence < sequence && !isCancelled {
            if !acknowledgement.wait(until: deadline) { throw PairingError.invalidMessage }
        }
        if isCancelled { throw PairingError.cancelled }
    }

    init(url: URL, cancellation: FileCancellationToken) throws {
        self.url = url
        displayName = url.lastPathComponent
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .contentTypeKey])
        let size = Int64(values.fileSize ?? -1)
        guard size >= 0, size <= 10 * 1024 * 1024 * 1024 else { throw PairingError.invalidMessage }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var digest = SHA256()
        while let data = try handle.read(upToCount: 256 * 1024), !data.isEmpty {
            guard !cancellation.isCancelled else { throw PairingError.cancelled }
            digest.update(data: data)
        }
        offer = FileOfferPayload(
            transferId: transferID,
            name: displayName,
            mimeType: values.contentType?.preferredMIMEType ?? "application/octet-stream",
            size: size,
            sha256: Data(digest.finalize()).base64EncodedString()
        )
    }

    func send(
        through session: Session,
        key: Data,
        status: @escaping @MainActor (String) -> Void,
        completion: @escaping @MainActor (Result<PairingMessage, Error>) -> Void
    ) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let handle = try FileHandle(forReadingFrom: self.url)
                defer { try? handle.close() }
                var sent: Int64 = 0
                var sequence: Int64 = 0
                let progress = MacTransferProgress(totalBytes: self.offer.size)
                while let chunk = try handle.read(upToCount: Self.chunkSize), !chunk.isEmpty {
                    guard !self.isCancelled else { throw PairingError.cancelled }
                    let sealed = try Self.encrypt(chunk, key: key)
                    guard session.sendAndWait(PairingMessage(
                        kind: "files.chunk",
                        sessionId: session.id,
                        messageId: UUID().uuidString.lowercased(),
                        nonce: sealed.nonce,
                        ciphertext: sealed.ciphertext,
                        transferId: self.transferID,
                        sequence: sequence
                    )) else { throw PairingError.invalidMessage }
                    if sequence >= Self.acknowledgementWindow - 1 {
                        try self.waitForAcknowledgement(sequence: sequence - (Self.acknowledgementWindow - 1))
                    }
                    sequence += 1
                    sent += Int64(chunk.count)
                    if let detail = progress.status(transferred: sent) {
                        DispatchQueue.main.async { status("Sending \(self.displayName): \(detail)") }
                    }
                }
                if sequence > 0 { try self.waitForAcknowledgement(sequence: sequence - 1) }
                guard !self.isCancelled else { throw PairingError.cancelled }
                let completionData = try JSONEncoder().encode(FileCompletePayload(
                    transferId: self.transferID,
                    sha256: self.offer.sha256
                ))
                let sealed = try Self.encrypt(completionData, key: key)
                let message = PairingMessage(
                    kind: "files.complete",
                    sessionId: session.id,
                    messageId: UUID().uuidString.lowercased(),
                    nonce: sealed.nonce,
                    ciphertext: sealed.ciphertext
                )
                guard !self.isCancelled else { throw PairingError.cancelled }
                DispatchQueue.main.async { status("Verifying \(self.displayName) on Android…") }
                DispatchQueue.main.async { completion(.success(message)) }
            } catch {
                DispatchQueue.main.async { completion(.failure(error)) }
            }
        }
    }

    private static func encrypt(_ plaintext: Data, key: Data) throws -> (nonce: String, ciphertext: String) {
        let sealed = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key))
        return (Data(sealed.nonce).base64EncodedString(), (sealed.ciphertext + sealed.tag).base64EncodedString())
    }
}

private final class MacTransferProgress {
    private let totalBytes: Int64
    private var lastAt = ProcessInfo.processInfo.systemUptime
    private var lastBytes: Int64 = 0
    private var smoothed = 0.0

    init(totalBytes: Int64) { self.totalBytes = totalBytes }

    func status(transferred: Int64, force: Bool = false) -> String? {
        let now = ProcessInfo.processInfo.systemUptime
        let elapsed = now - lastAt
        if !force && elapsed < 0.25 && transferred < totalBytes { return nil }
        if elapsed > 0 {
            let sample = Double(max(0, transferred - lastBytes)) / elapsed
            smoothed = smoothed == 0 ? sample : smoothed * 0.7 + sample * 0.3
        }
        lastAt = now
        lastBytes = transferred
        let percent = totalBytes == 0 ? 100 : min(100, Int(transferred * 100 / totalBytes))
        let remaining = max(0, totalBytes - transferred)
        let eta: String
        if smoothed >= 1 && remaining > 0 {
            let seconds = Int64(Double(remaining) / smoothed)
            eta = seconds < 60 ? "\(max(1, seconds)) sec" : "\(seconds / 60) min \(seconds % 60) sec"
        } else { eta = "calculating…" }
        return "\(percent)% · \(ByteCountFormatter.string(fromByteCount: transferred, countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: totalBytes, countStyle: .file)) · \(ByteCountFormatter.string(fromByteCount: Int64(smoothed), countStyle: .file))/s · \(eta) left"
    }
}

private final class IncomingFileTransfer {
    let displayName: String
    let expectedSize: Int64
    private let expectedHash: String
    private let partialURL: URL
    private let finalURL: URL
    private let handle: FileHandle
    private let directoryAccess: ReceiveDirectoryAccess
    private var hasher = SHA256()
    private(set) var receivedSize: Int64 = 0
    private var nextSequence: Int64 = 0
    private var lastProgressAt = ProcessInfo.processInfo.systemUptime
    private var lastProgressBytes: Int64 = 0
    private var smoothedBytesPerSecond = 0.0

    init(offer: FileOfferPayload, directoryAccess: ReceiveDirectoryAccess) throws {
        expectedSize = offer.size
        expectedHash = offer.sha256
        self.directoryAccess = directoryAccess
        let sanitized = Self.sanitize(offer.name)
        displayName = sanitized
        let directory = directoryAccess.url
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        finalURL = Self.uniqueURL(directory.appendingPathComponent(sanitized))
        partialURL = finalURL.appendingPathExtension("bridgey-part")
        FileManager.default.createFile(atPath: partialURL.path, contents: nil)
        handle = try FileHandle(forWritingTo: partialURL)
    }

    func append(_ data: Data, sequence: Int64) throws {
        guard sequence == nextSequence,
              data.count <= 24 * 1024,
              receivedSize + Int64(data.count) <= expectedSize else { throw PairingError.invalidMessage }
        try handle.write(contentsOf: data)
        hasher.update(data: data)
        receivedSize += Int64(data.count)
        nextSequence += 1
    }

    func finish(expectedHash completionHash: String) throws -> URL {
        try handle.close()
        let actual = Data(hasher.finalize()).base64EncodedString()
        guard receivedSize == expectedSize,
              completionHash == expectedHash,
              actual == expectedHash else {
            try? FileManager.default.removeItem(at: partialURL)
            throw PairingError.invalidMessage
        }
        try FileManager.default.moveItem(at: partialURL, to: finalURL)
        return finalURL
    }

    func progressStatus(force: Bool = false) -> String? {
        let now = ProcessInfo.processInfo.systemUptime
        let elapsed = now - lastProgressAt
        if !force && elapsed < 0.25 && receivedSize < expectedSize { return nil }
        if elapsed > 0 {
            let sample = Double(max(0, receivedSize - lastProgressBytes)) / elapsed
            smoothedBytesPerSecond = smoothedBytesPerSecond == 0 ? sample : smoothedBytesPerSecond * 0.7 + sample * 0.3
        }
        lastProgressAt = now
        lastProgressBytes = receivedSize
        let percent = expectedSize == 0 ? 100 : min(100, Int(receivedSize * 100 / expectedSize))
        let remaining = max(0, expectedSize - receivedSize)
        let eta = smoothedBytesPerSecond >= 1 && remaining > 0
            ? Self.formatDuration(Int64(Double(remaining) / smoothedBytesPerSecond))
            : "calculating…"
        return "\(percent)% · \(Self.formatBytes(receivedSize)) / \(Self.formatBytes(expectedSize)) · \(Self.formatBytes(Int64(smoothedBytesPerSecond)))/s · \(eta) left"
    }

    func cancel() {
        try? handle.close()
        try? FileManager.default.removeItem(at: partialURL)
    }

    private static func sanitize(_ name: String) -> String {
        let leaf = (name as NSString).lastPathComponent
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        return String(leaf.prefix(255)).isEmpty ? "file" : String(leaf.prefix(255))
    }

    private static func uniqueURL(_ requested: URL) -> URL {
        guard FileManager.default.fileExists(atPath: requested.path) else { return requested }
        let extensionPart = requested.pathExtension
        let stem = requested.deletingPathExtension().lastPathComponent
        for number in 2...10_000 {
            let name = extensionPart.isEmpty ? "\(stem) \(number)" : "\(stem) \(number).\(extensionPart)"
            let candidate = requested.deletingLastPathComponent().appendingPathComponent(name)
            if !FileManager.default.fileExists(atPath: candidate.path) { return candidate }
        }
        return requested.deletingLastPathComponent().appendingPathComponent(UUID().uuidString)
    }

    private static func formatBytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private static func formatDuration(_ seconds: Int64) -> String {
        if seconds < 1 { return "<1 sec" }
        if seconds < 60 { return "\(seconds) sec" }
        if seconds < 3_600 { return "\(seconds / 60) min \(seconds % 60) sec" }
        return "\(seconds / 3_600) h \((seconds % 3_600) / 60) min"
    }
}

/// This Mac's long-term identity key. R1: the identity must never silently change because of a
/// Keychain error - only a confirmed "nothing stored yet" (errSecItemNotFound) creates a key. Any
/// other failure leaves the identity unavailable (nothing is signed or presented) until a later
/// reload succeeds, so peers never see a new key for this deviceId.
final class MacIdentity {
    enum Status: Equatable {
        case available
        /// Keychain could not be read (locked, interaction not allowed, I/O, ...). Retried later.
        case unavailable(OSStatus)
        /// A key is stored but cannot be decoded. Never overwritten automatically.
        case unreadable
    }

    /// The two Keychain operations identity needs, injectable for tests.
    struct KeychainAccess {
        let copy: () -> (OSStatus, Data?)
        let add: (Data) -> OSStatus

        static func system(service: String, account: String) -> KeychainAccess {
            KeychainAccess(
                copy: {
                    var query = baseQuery(service: service, account: account)
                    query[kSecReturnData as String] = true
                    query[kSecMatchLimit as String] = kSecMatchLimitOne
                    var item: CFTypeRef?
                    let status = SecItemCopyMatching(query as CFDictionary, &item)
                    return (status, item as? Data)
                },
                add: { data in
                    var item = baseQuery(service: service, account: account)
                    item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                    item[kSecValueData as String] = data
                    return SecItemAdd(item as CFDictionary, nil)
                }
            )
        }
    }

    static let defaultService = "dev.bridgey.identity"
    static let defaultAccount = "p256-signing-v1"

    private let keychain: KeychainAccess
    private var key: P256.Signing.PrivateKey?
    private(set) var status: Status = .unavailable(errSecNotAvailable)

    convenience init(service: String = MacIdentity.defaultService, account: String = MacIdentity.defaultAccount) {
        self.init(keychain: .system(service: service, account: account))
    }

    init(keychain: KeychainAccess) {
        self.keychain = keychain
        load()
    }

    var isAvailable: Bool { status == .available }

    /// Base64 X9.63 public key, or nil while the identity is unavailable.
    var publicKey: String? { key?.publicKey.x963Representation.base64EncodedString() }

    /// DER ECDSA signature, or nil while the identity is unavailable.
    func sign(_ data: Data) -> String? {
        guard let key else { return nil }
        return try? key.signature(for: data).derRepresentation.base64EncodedString()
    }

    /// Retries a failed load (e.g. Keychain locked at launch). Never replaces an existing key.
    @discardableResult
    func reloadIfUnavailable() -> Bool {
        if !isAvailable { load() }
        return isAvailable
    }

    private func load() {
        let (copyStatus, data) = keychain.copy()
        switch copyStatus {
        case errSecSuccess:
            guard let data, let stored = try? P256.Signing.PrivateKey(rawRepresentation: data) else {
                key = nil
                status = .unreadable
                NSLog("IDENTITY stored key is unreadable; not replacing it")
                return
            }
            key = stored
            status = .available
        case errSecItemNotFound:
            // First launch: the only case in which a new identity may be created.
            let generated = P256.Signing.PrivateKey()
            let addStatus = keychain.add(generated.rawRepresentation)
            guard addStatus == errSecSuccess else {
                key = nil
                status = .unavailable(addStatus)
                NSLog("IDENTITY could not persist a new identity (status=%d)", addStatus)
                return
            }
            key = generated
            status = .available
        default:
            key = nil
            status = .unavailable(copyStatus)
            NSLog("IDENTITY Keychain unavailable (status=%d); identity kept, will retry", copyStatus)
        }
    }

    private static func baseQuery(service: String, account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    static func deleteForTesting(service: String, account: String = MacIdentity.defaultAccount) {
        SecItemDelete(baseQuery(service: service, account: account) as CFDictionary)
    }
}
