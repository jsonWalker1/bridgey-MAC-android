import Foundation

struct LocalStorageStatus: Equatable {
    let usedBytes: Int64
    let totalBytes: Int64
}

func normalizedStorageStatus(usedBytes: Int64, totalBytes: Int64) -> LocalStorageStatus? {
    guard totalBytes > 0, usedBytes >= 0 else { return nil }
    return LocalStorageStatus(usedBytes: min(usedBytes, totalBytes), totalBytes: totalBytes)
}

/// Reads the data volume (the same volume the user's home directory lives on) rather than the
/// boot volume - on modern macOS's APFS volume-group layout these can differ, and the data volume
/// is what Finder/Settings storage panels actually report as "available".
func currentMacStorageStatus() -> LocalStorageStatus? {
    let url = URL(fileURLWithPath: NSHomeDirectory())
    guard let values = try? url.resourceValues(forKeys: [
        .volumeTotalCapacityKey,
        .volumeAvailableCapacityForImportantUsageKey,
    ]),
        let totalBytes = values.volumeTotalCapacity,
        let availableBytes = values.volumeAvailableCapacityForImportantUsage
    else { return nil }
    let usedBytes = max(0, Int64(totalBytes) - availableBytes)
    return normalizedStorageStatus(usedBytes: usedBytes, totalBytes: Int64(totalBytes))
}
