import Foundation

// PER-DEVICE TELEMETRY (MD-4c)
//
// Telemetry is device state: every value belongs to the peer that sent it. Three small pieces:
//   DeviceTelemetryStore    deviceId -> the latest values that peer sent us (display side)
//   TelemetrySubscription   which peer this device is displaying (and has subscribed to)
//   TelemetrySubscribers    which peers are displaying THIS device (publish side), with the
//                           storage/memory dead-band kept per subscriber
// The wire is unchanged: battery.update, telemetry.update, telemetry.subscribe/unsubscribe.

/// The latest telemetry one peer sent us. Absent values were never received (or were cleared).
struct DeviceTelemetry: Equatable {
    var battery: RemoteBatteryStatus?
    var storage: RemoteStorageStatus?
    var memory: RemoteMemoryStatus?
    var cpu: RemoteCpuStatus?
    var temperature: RemoteTemperatureStatus?

    var isEmpty: Bool { self == DeviceTelemetry() }
}

enum TelemetryMetric: CaseIterable {
    case battery, storage, memory, cpu, temperature

    /// The existing feature / capability key of this metric.
    var feature: BridgeyFeature {
        switch self {
        case .battery: .battery
        case .storage: .storage
        case .memory: .memory
        case .cpu: .cpu
        case .temperature: .temperature
        }
    }
}

struct DeviceTelemetryStore: Equatable {
    private(set) var byDevice: [String: DeviceTelemetry] = [:]

    subscript(deviceID: String) -> DeviceTelemetry? { byDevice[deviceID] }

    /// Changes only `deviceID`'s values.
    mutating func update(_ deviceID: String, _ change: (inout DeviceTelemetry) -> Void) {
        var values = byDevice[deviceID] ?? DeviceTelemetry()
        change(&values)
        byDevice[deviceID] = values.isEmpty ? nil : values
    }

    /// The peer's session ended: its values are gone; other peers are untouched.
    mutating func remove(_ deviceID: String) { byDevice[deviceID] = nil }

    /// Drops one metric of one peer (its grant or capability was turned off).
    mutating func clear(_ metric: TelemetryMetric, for deviceID: String) {
        update(deviceID) { values in
            switch metric {
            case .battery: values.battery = nil
            case .storage: values.storage = nil
            case .memory: values.memory = nil
            case .cpu: values.cpu = nil
            case .temperature: values.temperature = nil
            }
        }
    }

    /// Drops every metric `isAllowed(deviceID, metric)` no longer allows.
    mutating func prune(_ isAllowed: (String, TelemetryMetric) -> Bool) {
        for deviceID in Array(byDevice.keys) {
            for metric in TelemetryMetric.allCases where !isAllowed(deviceID, metric) {
                clear(metric, for: deviceID)
            }
        }
    }
}

/// Display side: subscribe to exactly the peer the UI shows, only while it is shown.
struct TelemetrySubscription: Equatable {
    enum Change: Equatable {
        case subscribe(String)
        case unsubscribe(String)
    }

    /// The peer the UI wants to see (nil = panel/window closed).
    private(set) var desiredDeviceID: String?
    /// The peer we actually sent telemetry.subscribe to.
    private(set) var subscribedDeviceID: String?

    /// The UI now shows `deviceID` (or nothing). Returns the messages to send.
    mutating func show(_ deviceID: String?, isConnected: (String) -> Bool) -> [Change] {
        desiredDeviceID = deviceID
        var changes: [Change] = []
        if let subscribed = subscribedDeviceID, subscribed != deviceID {
            changes.append(.unsubscribe(subscribed))
            subscribedDeviceID = nil
        }
        if let deviceID, subscribedDeviceID == nil, isConnected(deviceID) {
            changes.append(.subscribe(deviceID))
            subscribedDeviceID = deviceID
        }
        return changes
    }

    /// A peer's session started: resubscribe if the UI is showing it.
    mutating func sessionStarted(_ deviceID: String) -> [Change] {
        guard desiredDeviceID == deviceID, subscribedDeviceID != deviceID else { return [] }
        subscribedDeviceID = deviceID
        return [.subscribe(deviceID)]
    }

    /// A peer's session ended: that subscription is gone with it (the UI intent stays).
    mutating func sessionEnded(_ deviceID: String) {
        if subscribedDeviceID == deviceID { subscribedDeviceID = nil }
    }
}

/// Publish side: the peers currently displaying this device, plus the storage/memory values last
/// sent to each (the 100 MiB dead-band is per subscriber, so a new subscriber always gets values).
struct TelemetrySubscribers: Equatable {
    private(set) var deviceIDs: Set<String> = []
    private var lastStorage: [String: LocalStorageStatus] = [:]
    private var lastMemory: [String: LocalMemoryStatus] = [:]

    var isEmpty: Bool { deviceIDs.isEmpty }

    /// Returns true when this is the first subscriber (the sampling loop must start).
    @discardableResult
    mutating func add(_ deviceID: String) -> Bool {
        let wasEmpty = deviceIDs.isEmpty
        deviceIDs.insert(deviceID)
        lastStorage[deviceID] = nil
        lastMemory[deviceID] = nil
        return wasEmpty
    }

    /// Returns true when no subscriber remains (the sampling loop must stop).
    @discardableResult
    mutating func remove(_ deviceID: String) -> Bool {
        let removed = deviceIDs.remove(deviceID) != nil
        lastStorage[deviceID] = nil
        lastMemory[deviceID] = nil
        return removed && deviceIDs.isEmpty
    }

    mutating func reset() { self = TelemetrySubscribers() }

    /// The subscriber's grant/capability changed: its next storage/memory values go out regardless.
    mutating func resetDeadBand(_ deviceID: String) {
        lastStorage[deviceID] = nil
        lastMemory[deviceID] = nil
    }

    static let changeThresholdBytes: Int64 = 100 * 1024 * 1024

    func shouldSend(storage status: LocalStorageStatus, to deviceID: String) -> Bool {
        Self.changed(status.usedBytes, status.totalBytes, lastStorage[deviceID].map { ($0.usedBytes, $0.totalBytes) })
    }

    func shouldSend(memory status: LocalMemoryStatus, to deviceID: String) -> Bool {
        Self.changed(status.usedBytes, status.totalBytes, lastMemory[deviceID].map { ($0.usedBytes, $0.totalBytes) })
    }

    mutating func sent(storage status: LocalStorageStatus, to deviceID: String) { lastStorage[deviceID] = status }
    mutating func sent(memory status: LocalMemoryStatus, to deviceID: String) { lastMemory[deviceID] = status }

    private static func changed(_ used: Int64, _ total: Int64, _ previous: (Int64, Int64)?) -> Bool {
        guard let previous else { return true }
        return total != previous.1 || abs(used - previous.0) >= changeThresholdBytes
    }

    static func == (lhs: TelemetrySubscribers, rhs: TelemetrySubscribers) -> Bool {
        lhs.deviceIDs == rhs.deviceIDs && lhs.lastStorage == rhs.lastStorage && lhs.lastMemory == rhs.lastMemory
    }
}
