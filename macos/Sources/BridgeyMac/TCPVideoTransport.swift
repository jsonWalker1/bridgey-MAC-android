import Foundation
import Network

let defaultVideoMaxFrameBytes = 4 * 1024 * 1024 // 4 MiB - covers a keyframe at higher bitrate/resolution than the PoC baseline
let defaultVideoQueueCapacity = 8

/// TCP implementation of VideoTransport (frozen spec: implementation #1, not the architecture).
/// Owns its own dedicated reader/writer threads - never touches the main/session dispatch queue,
/// so a slow video write can never starve unrelated IO-bound work. Direct port of
/// TcpVideoTransport.kt, using NWConnection/NWListener (Network.framework, already used elsewhere
/// in this codebase) as the concrete socket layer instead of raw BSD sockets, driven blockingly via
/// BlockingConnectionIO/BlockingListenerIO to mirror the same synchronous handshake/read/write shape.
final class TCPVideoTransport: VideoTransport {
    let capabilities: TransportCapabilities
    private let queueCapacity: Int
    private let lock = NSLock()
    private var metricsState = TransportMetrics()
    var metrics: TransportMetrics { lock.withLock { metricsState } }
    var onFrameReceived: (EncodedVideoFrame) -> Void = { _ in }
    var onDisconnected: (Error?) -> Void = { _ in }

    private var connection: NWConnection?
    private var listener: NWListener?
    private var channelKey: Data?
    private var sendSequence: Int64 = 0
    private var recvSequence: Int64 = 0
    private var closed = false
    private let closedLock = NSLock()
    private let queueCondition = NSCondition()
    private var sendQueue: [QueuedFrame] = []
    private var readerThread: Thread?
    private var writerThread: Thread?

    private struct QueuedFrame { let bytes: Data; let droppable: Bool }

    init(maxFrameBytes: Int = defaultVideoMaxFrameBytes, queueCapacity: Int = defaultVideoQueueCapacity) {
        capabilities = TransportCapabilities(reliable: true, ordered: true, maxFrameBytes: maxFrameBytes)
        self.queueCapacity = queueCapacity
    }

    /// Client role (algorithm A, steps A6-A14): the side that sent the offer and received the
    /// accept. Blocking - call from a background thread.
    func connectAsInitiator(host: String, port: UInt16, security: ChannelSecurityContext, timeout: TimeInterval = 5) -> Bool {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return false }
        let conn = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
        guard waitUntilReady(conn, timeout: timeout) else { conn.cancel(); return false }
        return completeHandshake(conn, security: security, timeout: timeout, isInitiator: true)
    }

    /// Server role (steps A11-A13): the side that received the offer and sent the accept. The
    /// NWListener is created and its ephemeral port announced by VideoChannelController BEFORE this
    /// is called (the port must be known synchronously to put in the *.accept message), so this
    /// just blocks on one incoming connection + handshake using that already-bound listener.
    func acceptViaListener(_ listener: NWListener, security: ChannelSecurityContext, timeout: TimeInterval = 5) -> Bool {
        self.listener = listener
        guard let conn = BlockingListenerIO.acceptOne(listener, timeout: timeout) else {
            listener.cancel()
            return false
        }
        return completeHandshake(conn, security: security, timeout: timeout, isInitiator: false)
    }

    private func waitUntilReady(_ connection: NWConnection, timeout: TimeInterval) -> Bool {
        let semaphore = DispatchSemaphore(value: 0)
        var ok = false
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready: ok = true; semaphore.signal()
            case .failed, .cancelled: semaphore.signal()
            default: break
            }
        }
        connection.start(queue: DispatchQueue(label: "bridgey.video.connect"))
        _ = semaphore.wait(timeout: .now() + timeout)
        return ok
    }

    private func completeHandshake(_ conn: NWConnection, security: ChannelSecurityContext, timeout: TimeInterval, isInitiator: Bool) -> Bool {
        let ok = isInitiator
            ? ChannelSecurity.performInitiatorHandshake(conn, security: security, timeout: timeout)
            : ChannelSecurity.performAcceptorHandshake(conn, security: security, timeout: timeout)
        guard ok else {
            conn.cancel()
            listener?.cancel()
            return false
        }
        connection = conn
        channelKey = security.channelKey
        sendSequence = 0
        recvSequence = 0
        closedLock.withLock { closed = false }
        startThreads()
        return true
    }

    private func startThreads() {
        let reader = Thread { [weak self] in self?.readLoop() }
        reader.name = "bridgey-video-reader"
        reader.start()
        readerThread = reader
        let writer = Thread { [weak self] in self?.writeLoop() }
        writer.name = "bridgey-video-writer"
        writer.start()
        writerThread = writer
    }

    /// Algorithm B: send. Never blocks the caller on network I/O - just enqueues.
    func send(_ frame: EncodedVideoFrame, droppable: Bool) -> SendOutcome {
        guard let key = channelKey else { return .failed("not connected") }
        if isClosed { return .failed("closed") }
        guard let (nonce, ciphertext) = ChannelSecurity.seal(key: key, plaintext: frame.payload) else { return .failed("seal failed") }
        sendSequence += 1
        let bytes = VideoFrameFraming.encodeFrame(type: frame.type, streamId: frame.streamId, sequence: sendSequence, captureTimestampMs: frame.captureTimestampMs, nonce: nonce, ciphertext: ciphertext)
        if bytes.count > capabilities.maxFrameBytes { return .failed("frame exceeds maxFrameBytes") }

        queueCondition.lock()
        var queueSize = sendQueue.count
        if queueSize >= queueCapacity {
            // Evict the oldest droppable entry first; if this frame itself isn't droppable and the
            // queue is still full of non-droppable entries, evict the oldest regardless - per the
            // frozen backpressure policy, a keyframe/pointer-down must still get through.
            if let index = sendQueue.firstIndex(where: { $0.droppable }) {
                sendQueue.remove(at: index)
            } else if sendQueue.count >= queueCapacity {
                sendQueue.removeFirst()
            }
            lock.withLock { metricsState.framesDropped += 1 }
        }
        sendQueue.append(QueuedFrame(bytes: bytes, droppable: droppable))
        queueSize = sendQueue.count
        queueCondition.signal()
        queueCondition.unlock()
        return queueSize > 1 ? .queuedBounded : .sent
    }

    private func writeLoop() {
        while true {
            queueCondition.lock()
            while sendQueue.isEmpty && !isClosed { queueCondition.wait() }
            if isClosed { queueCondition.unlock(); return }
            let queued = sendQueue.removeFirst()
            queueCondition.unlock()
            guard let conn = connection, BlockingConnectionIO.send(conn, queued.bytes, timeout: 15) else {
                fail(nil)
                return
            }
            lock.withLock {
                metricsState.framesSent += 1
                metricsState.bytesSent += Int64(queued.bytes.count)
            }
        }
    }

    /// Algorithm C: receive.
    private func readLoop() {
        guard let conn = connection, let key = channelKey else { return }
        while !isClosed {
            guard let lengthBytes = BlockingConnectionIO.receiveExactly(conn, 4, timeout: 3600) else {
                fail(nil)
                return
            }
            var offset = lengthBytes.startIndex
            let frameLength = Int(lengthBytes.readBigEndianUInt32(at: &offset))
            if frameLength <= 0 || frameLength > capabilities.maxFrameBytes {
                fail(TransportError.protocolViolation("declared frame length \(frameLength) exceeds cap"))
                return
            }
            guard let body = BlockingConnectionIO.receiveExactly(conn, frameLength, timeout: 15) else {
                fail(nil)
                return
            }
            guard let parsed = VideoFrameFraming.parseFrameBody(body), parsed.header.version == frameVersion, VideoFrameType.all.contains(parsed.header.type) else {
                fail(TransportError.protocolViolation("malformed video frame"))
                return
            }
            if parsed.header.sequence <= recvSequence {
                continue // stale/replayed - silently dropped, connection stays open
            }
            guard let plaintext = ChannelSecurity.open(key: key, nonce: parsed.header.nonce, ciphertext: parsed.ciphertext) else {
                fail(TransportError.protocolViolation("decrypt/tag verification failed"))
                return
            }
            recvSequence = parsed.header.sequence
            lock.withLock { metricsState.framesReceived += 1 }
            onFrameReceived(EncodedVideoFrame(type: parsed.header.type, streamId: parsed.header.streamId, captureTimestampMs: parsed.header.captureTimestampMs, payload: plaintext))
        }
    }

    private var isClosed: Bool { closedLock.withLock { closed } }

    private func fail(_ error: Error?) {
        var shouldNotify = false
        closedLock.withLock {
            if !closed { closed = true; shouldNotify = true }
        }
        guard shouldNotify else { return }
        teardown()
        onDisconnected(error)
    }

    /// Algorithm D: teardown.
    func close() {
        var shouldTeardown = false
        closedLock.withLock {
            if !closed { closed = true; shouldTeardown = true }
        }
        if shouldTeardown { teardown() }
    }

    private func teardown() {
        queueCondition.lock()
        sendQueue.removeAll()
        queueCondition.signal()
        queueCondition.unlock()
        connection?.cancel()
        listener?.cancel()
        connection = nil
        listener = nil
        channelKey = nil
    }
}

enum TransportError: Error { case protocolViolation(String) }
