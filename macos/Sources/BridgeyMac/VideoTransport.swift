import Foundation

// Minimal transport abstraction (frozen spec, section 3): expresses transport semantics, not TCP
// specifics. TCPVideoTransport is implementation #1; a future UDP/QUIC implementation satisfies the
// same protocol without VideoChannelController, encoder, or decoder ever changing.
// Direct port of VideoTransport.kt.

struct TransportCapabilities {
    let reliable: Bool
    let ordered: Bool
    let maxFrameBytes: Int
}

struct TransportMetrics {
    var framesSent: Int64 = 0
    var framesReceived: Int64 = 0
    var framesDropped: Int64 = 0
    var bytesSent: Int64 = 0
}

enum SendOutcome: Equatable {
    case sent
    case queuedBounded
    case failed(String)
}

struct EncodedVideoFrame {
    let type: Int // VideoFrameType.*
    let streamId: Int64
    let captureTimestampMs: Int64
    let payload: Data
}

protocol VideoTransport: AnyObject {
    var capabilities: TransportCapabilities { get }
    var metrics: TransportMetrics { get }
    var onFrameReceived: (EncodedVideoFrame) -> Void { get set }
    var onDisconnected: (Error?) -> Void { get set }

    /// Enqueues a frame for sending. `droppable=false` (KEYFRAME, CONFIG) must never be silently
    /// dropped by backpressure; `droppable=true` (DELTA) may be, per the frozen backpressure policy.
    func send(_ frame: EncodedVideoFrame, droppable: Bool) -> SendOutcome
    func close()
}
