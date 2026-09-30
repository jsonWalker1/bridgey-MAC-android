import Darwin
import Foundation

enum CpuStatus: Equatable {
    case available(Int)
    case unavailable
}

struct CpuSample: Equatable {
    let total: UInt64
    let idle: UInt64
}

/// Aggregate (all-core) system CPU ticks via the Mach host_statistics API - the same source
/// Activity Monitor's system-wide CPU meter reads from. No entitlement or special permission is
/// needed; unlike Android's /proc/stat this is not sandboxed away for a normal app.
func currentMacCpuSample() -> CpuSample? {
    var cpuLoad = host_cpu_load_info()
    var count = mach_msg_type_number_t(MemoryLayout<host_cpu_load_info>.size / MemoryLayout<integer_t>.size)
    let result = withUnsafeMutablePointer(to: &cpuLoad) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            host_statistics(mach_host_self(), HOST_CPU_LOAD_INFO, $0, &count)
        }
    }
    guard result == KERN_SUCCESS else { return nil }
    let ticks = cpuLoad.cpu_ticks
    let user = UInt64(ticks.0)
    let system = UInt64(ticks.1)
    let idle = UInt64(ticks.2)
    let nice = UInt64(ticks.3)
    return CpuSample(total: user + system + idle + nice, idle: idle)
}

/// Delta-based utilization between two samples. Needs a previous sample (returns nil - "not yet
/// known", distinct from .unavailable - for the very first one, so callers can seed silently rather
/// than briefly flashing "unavailable"). A zero/negative total delta (no elapsed ticks, or a counter
/// reset) can't be trusted and reports .unavailable rather than a nonsense number.
func computeCpuPercent(previous: CpuSample?, current: CpuSample) -> CpuStatus? {
    guard let previous else { return nil }
    let totalDelta = Int64(current.total) - Int64(previous.total)
    let idleDelta = Int64(current.idle) - Int64(previous.idle)
    guard totalDelta > 0, idleDelta >= 0, idleDelta <= totalDelta else { return .unavailable }
    let busy = totalDelta - idleDelta
    let percent = Int((Double(busy) * 100.0 / Double(totalDelta)).rounded())
    return .available(min(max(percent, 0), 100))
}
