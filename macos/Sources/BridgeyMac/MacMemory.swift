import Darwin
import Foundation

struct LocalMemoryStatus: Equatable {
    let usedBytes: Int64
    let totalBytes: Int64
}

func normalizedMemoryStatus(usedBytes: Int64, totalBytes: Int64) -> LocalMemoryStatus? {
    guard totalBytes > 0, usedBytes >= 0 else { return nil }
    return LocalMemoryStatus(usedBytes: min(usedBytes, totalBytes), totalBytes: totalBytes)
}

/// Total from ProcessInfo (stable, no syscall failure mode); used derived from host_statistics64's
/// VM page counts - the same low-level source Activity Monitor's memory tab reads from. Active +
/// wired + compressed pages counted as "used", matching how macOS itself defines memory pressure
/// rather than a naive free-vs-total split.
func currentMacMemoryStatus() -> LocalMemoryStatus? {
    let totalBytes = Int64(ProcessInfo.processInfo.physicalMemory)
    var stats = vm_statistics64()
    var count = mach_msg_type_number_t(MemoryLayout<vm_statistics64>.size / MemoryLayout<integer_t>.size)
    let result = withUnsafeMutablePointer(to: &stats) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            host_statistics64(mach_host_self(), HOST_VM_INFO64, $0, &count)
        }
    }
    guard result == KERN_SUCCESS else { return nil }
    let pageSize = Int64(vm_kernel_page_size)
    let usedPages = Int64(stats.active_count) + Int64(stats.wire_count) + Int64(stats.compressor_page_count)
    return normalizedMemoryStatus(usedBytes: usedPages * pageSize, totalBytes: totalBytes)
}
