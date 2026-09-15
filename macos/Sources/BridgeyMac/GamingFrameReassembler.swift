import Foundation

/// GAMING MODE POC. Swift mirror of ReplayWindow (Kotlin) - see that file for the construction
/// rationale (same sliding-window anti-replay IPsec/DTLS use).
final class ReplayWindow {
    private let windowSize: UInt64
    private var highestSequence: Int64 = -1
    private var bitmask: UInt64 = 0
    private let lock = NSLock()

    init(windowSize: Int = 64) {
        self.windowSize = UInt64(windowSize)
    }

    @discardableResult
    func acceptAndRecord(_ sequence: UInt32) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let seq = Int64(sequence)
        if highestSequence < 0 {
            highestSequence = seq
            bitmask = 1
            return true
        }
        let diff = seq - highestSequence
        if diff > 0 {
            bitmask = UInt64(diff) >= windowSize ? 1 : (bitmask << UInt64(diff)) | 1
            highestSequence = seq
            return true
        }
        let backBits = UInt64(-diff)
        guard backBits < windowSize else { return false }
        let bit: UInt64 = 1 << backBits
        guard bitmask & bit == 0 else { return false }
        bitmask |= bit
        return true
    }
}

/// GAMING MODE POC. Swift mirror of GamingFrameReassembler.kt - see that file's doc comment for the
/// full loss-handling rationale (preempt-on-newer-frame, timeout-on-nothing-newer, keyframe/config
/// loss reported for TCP-side recovery, ordinary delta loss silent by design).
final class GamingFrameReassembler {
    private final class InProgress {
        var fragments: [Data?]
        var received = 0
        let flags: Int
        let firstSeenAtMs: Int64
        init(fragmentCount: Int, flags: Int, firstSeenAtMs: Int64) {
            fragments = Array(repeating: nil, count: fragmentCount)
            self.flags = flags
            self.firstSeenAtMs = firstSeenAtMs
        }
    }

    private var inProgress: [UInt64: InProgress] = [:]
    private var order: [UInt64] = []
    private var highestFrameIdSeen: Int64 = -1
    private let staleFrameTimeoutMs: Int64
    private let onKeyframeOrConfigLost: () -> Void
    private let lock = NSLock()

    init(staleFrameTimeoutMs: Int64 = 200, onKeyframeOrConfigLost: @escaping () -> Void) {
        self.staleFrameTimeoutMs = staleFrameTimeoutMs
        self.onKeyframeOrConfigLost = onKeyframeOrConfigLost
    }

    func onFragment(
        frameId: UInt64, fragmentIndex: Int, fragmentCount: Int, flags: Int, nowMs: Int64, payload: Data
    ) -> Data? {
        lock.lock()
        defer { lock.unlock() }
        let signedFrameId = Int64(frameId)
        if signedFrameId < highestFrameIdSeen { return nil }
        if signedFrameId > highestFrameIdSeen {
            dropOlderThan(frameId)
            highestFrameIdSeen = signedFrameId
        }
        let state: InProgress
        if let existing = inProgress[frameId] {
            state = existing
        } else {
            state = InProgress(fragmentCount: fragmentCount, flags: flags, firstSeenAtMs: nowMs)
            inProgress[frameId] = state
            order.append(frameId)
        }
        guard fragmentIndex < state.fragments.count else { return nil }
        if state.fragments[fragmentIndex] == nil {
            state.fragments[fragmentIndex] = payload
            state.received += 1
        }
        guard state.received == state.fragments.count else { return nil }
        inProgress.removeValue(forKey: frameId)
        order.removeAll { $0 == frameId }
        var out = Data()
        for fragment in state.fragments {
            guard let fragment else { return nil }
            out.append(fragment)
        }
        return out
    }

    func dropStale(nowMs: Int64) {
        lock.lock()
        defer { lock.unlock() }
        let staleIds = order.filter { id in
            guard let state = inProgress[id] else { return false }
            return nowMs - state.firstSeenAtMs > staleFrameTimeoutMs
        }
        for id in staleIds {
            let dropped = inProgress.removeValue(forKey: id)
            order.removeAll { $0 == id }
            reportIfImportant(dropped)
        }
    }

    private func dropOlderThan(_ frameId: UInt64) {
        let staleIds = order.filter { $0 < frameId }
        for id in staleIds {
            let dropped = inProgress.removeValue(forKey: id)
            order.removeAll { $0 == id }
            reportIfImportant(dropped)
        }
    }

    private func reportIfImportant(_ dropped: InProgress?) {
        guard let dropped, dropped.flags & (GamingUdpFraming.Flags.keyframe | GamingUdpFraming.Flags.config) != 0 else { return }
        onKeyframeOrConfigLost()
    }
}
