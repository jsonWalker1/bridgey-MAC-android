import Foundation

// Pure state machine shared by the Video channel and the Input channel (two independent instances,
// one per channel). Matches the frozen M1 algorithm exactly - direct port of ChannelLifecycle.kt.

enum ChannelState { case idle, negotiating, connecting, handshaking, active, closing, failed }

enum ChannelEvent {
    case offerSent
    case acceptReceived
    case rejectReceived
    case negotiationTimeout
    case socketConnected
    case connectFailed
    case handshakeSucceeded
    case handshakeFailed
    case malformedOrTampered
    case stopRequested
    case socketClosed
    case sessionReset
}

enum ChannelLifecycle {
    /// sessionReset is unconditional: it always wins, from any state, no exceptions.
    static func transition(_ current: ChannelState, _ event: ChannelEvent) -> ChannelState {
        if case .sessionReset = event { return .idle }
        switch current {
        case .idle:
            if case .offerSent = event { return .negotiating }
            return current
        case .negotiating:
            switch event {
            case .acceptReceived: return .connecting
            case .rejectReceived, .negotiationTimeout: return .idle
            default: return current
            }
        case .connecting:
            switch event {
            case .socketConnected: return .handshaking
            case .connectFailed: return .failed
            default: return current
            }
        case .handshaking:
            switch event {
            case .handshakeSucceeded: return .active
            case .handshakeFailed: return .failed
            default: return current
            }
        case .active:
            switch event {
            case .malformedOrTampered, .socketClosed: return .failed
            case .stopRequested: return .closing
            default: return current
            }
        case .closing:
            if case .socketClosed = event { return .idle }
            return current
        case .failed:
            if case .stopRequested = event { return .idle }
            return current
        }
    }
}
