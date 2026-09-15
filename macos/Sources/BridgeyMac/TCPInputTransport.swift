import Foundation
import Network

let defaultInputMaxFrameBytes = 64 * 1024 // input events are tiny; anything bigger is suspicious
let defaultInputQueueCapacity = 32

/// TCP implementation of InputTransport. Mirrors TCPVideoTransport's establishment/framing/security
/// handling exactly (same handshake, same frame envelope) - only the payload codec and the
/// never-drop policy (pointer down/up, key down/up) differ from video's keyframe-protection policy.
/// Direct port of TcpInputTransport.kt.
final class TCPInputTransport: InputTransport {
    let capabilities: TransportCapabilities
    private let queueCapacity: Int
    private let lock = NSLock()
    private var metricsState = TransportMetrics()
    var metrics: TransportMetrics { lock.withLock { metricsState } }
    var onEventReceived: (InputEvent) -> Void = { _ in }
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

    private struct QueuedFrame { let bytes: Data; let coalescible: Bool }

    init(maxFrameBytes: Int = defaultInputMaxFrameBytes, queueCapacity: Int = defaultInputQueueCapacity) {
        capabilities = TransportCapabilities(reliable: true, ordered: true, maxFrameBytes: maxFrameBytes)
        self.queueCapacity = queueCapacity
    }

    func connectAsInitiator(host: String, port: UInt16, security: ChannelSecurityContext, timeout: TimeInterval = 5) -> Bool {
        guard let nwPort = NWEndpoint.Port(rawValue: port) else { return false }
        let conn = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
        guard waitUntilReady(conn, timeout: timeout) else { conn.cancel(); return false }
        return completeHandshake(conn, security: security, timeout: timeout, isInitiator: true)
    }

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
        connection.start(queue: DispatchQueue(label: "bridgey.input.connect"))
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
        let reader = Thread { [weak self] in self?.readLoop() }
        reader.name = "bridgey-input-reader"
        reader.start()
        readerThread = reader
        let writer = Thread { [weak self] in self?.writeLoop() }
        writer.name = "bridgey-input-writer"
        writer.start()
        writerThread = writer
        return true
    }

    /// Pointer move is the only coalescible event type - down/up/key/text are never dropped.
    func send(_ event: InputEvent) -> SendOutcome {
        guard let key = channelKey else { return .failed("not connected") }
        if isClosed { return .failed("closed") }
        let (type, payload) = InputEventCodec.encode(event)
        guard let (nonce, ciphertext) = ChannelSecurity.seal(key: key, plaintext: payload) else { return .failed("seal failed") }
        sendSequence += 1
        let bytes = VideoFrameFraming.encodeFrame(type: type, streamId: 0, sequence: sendSequence, captureTimestampMs: Int64(Date().timeIntervalSince1970 * 1000), nonce: nonce, ciphertext: ciphertext)
        if bytes.count > capabilities.maxFrameBytes { return .failed("frame exceeds maxFrameBytes") }
        let coalescible: Bool
        if case .pointer(let action, _, _) = event, action == .move { coalescible = true } else { coalescible = false }

        queueCondition.lock()
        var queueSize = sendQueue.count
        if queueSize >= queueCapacity {
            if let index = sendQueue.firstIndex(where: { $0.coalescible }) {
                sendQueue.remove(at: index)
            } else if sendQueue.count >= queueCapacity {
                sendQueue.removeFirst()
            }
            lock.withLock { metricsState.framesDropped += 1 }
        }
        sendQueue.append(QueuedFrame(bytes: bytes, coalescible: coalescible))
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
            guard let parsed = VideoFrameFraming.parseFrameBody(body), parsed.header.version == frameVersion, InputFrameType.all.contains(parsed.header.type) else {
                fail(TransportError.protocolViolation("malformed input frame"))
                return
            }
            if parsed.header.sequence <= recvSequence {
                continue
            }
            guard let plaintext = ChannelSecurity.open(key: key, nonce: parsed.header.nonce, ciphertext: parsed.ciphertext) else {
                fail(TransportError.protocolViolation("decrypt/tag verification failed"))
                return
            }
            recvSequence = parsed.header.sequence
            guard let event = InputEventCodec.decode(type: parsed.header.type, payload: plaintext) else {
                fail(TransportError.protocolViolation("undecodable input event"))
                return
            }
            lock.withLock { metricsState.framesReceived += 1 }
            onEventReceived(event)
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
