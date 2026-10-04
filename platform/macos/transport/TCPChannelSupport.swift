import Foundation
import Network

// Shared plumbing for the video/input dedicated TCP channels: turning Network.framework's
// callback-based NWListener/NWConnection into the same blocking-call shape the frozen algorithm
// (and the Android Socket/ServerSocket implementation) assumes - "create a listener, learn its
// ephemeral port synchronously" and "block until one incoming connection arrives".

/// Holds the one incoming connection a channel listener will ever accept, bridging the
/// callback-based `newConnectionHandler` to the blocking `acceptOne` call below.
private final class PendingConnectionBox {
    private let lock = NSLock()
    private var connection: NWConnection?
    private let semaphore = DispatchSemaphore(value: 0)

    func deliver(_ connection: NWConnection) {
        lock.lock()
        if self.connection == nil { self.connection = connection }
        lock.unlock()
        semaphore.signal()
    }

    func take(timeout: TimeInterval) -> NWConnection? {
        guard semaphore.wait(timeout: .now() + timeout) == .success else { return nil }
        lock.lock()
        defer { lock.unlock() }
        return connection
    }
}

/// Keyed by listener identity so `start()` and `acceptOne()` can stay two separate calls (the port
/// must be known synchronously, before the accept can happen on a background thread) without
/// changing either function's signature.
private var pendingConnectionBoxes: [ObjectIdentifier: PendingConnectionBox] = [:]
private let pendingConnectionBoxesLock = NSLock()

enum EphemeralTCPListener {
    /// Starts a TCP listener on a system-assigned port and blocks until it is ready, returning the
    /// listener plus the port it bound to - the synchronous equivalent of Android's
    /// `ServerSocket(0)` + `.localPort`, needed because the port must be known before it can be
    /// embedded in the `*.accept` reply.
    static func start(timeout: TimeInterval = 5) -> (NWListener, UInt16)? {
        guard let listener = try? NWListener(using: .tcp, on: .any) else { return nil }

        // NWListener requires newConnectionHandler (or newConnectionGroupHandler) to be set BEFORE
        // start() - otherwise it fails immediately with EINVAL ("Started without setting either new
        // connection handler or new connection group handler"). acceptOne() runs later, on a
        // different thread, so the handler is installed here and handed off via a box keyed by the
        // listener's identity instead.
        let box = PendingConnectionBox()
        pendingConnectionBoxesLock.lock()
        pendingConnectionBoxes[ObjectIdentifier(listener)] = box
        pendingConnectionBoxesLock.unlock()
        listener.newConnectionHandler = { connection in box.deliver(connection) }

        let semaphore = DispatchSemaphore(value: 0)
        var boundPort: UInt16?
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                boundPort = listener.port?.rawValue
                semaphore.signal()
            case .failed, .cancelled:
                semaphore.signal()
            default:
                break
            }
        }
        listener.start(queue: DispatchQueue(label: "bridgey.channel.listener"))
        guard semaphore.wait(timeout: .now() + timeout) == .success, let port = boundPort else {
            pendingConnectionBoxesLock.lock()
            pendingConnectionBoxes.removeValue(forKey: ObjectIdentifier(listener))
            pendingConnectionBoxesLock.unlock()
            listener.cancel()
            return nil
        }
        return (listener, port)
    }
}

enum BlockingListenerIO {
    /// Blocks until exactly one incoming connection arrives, mirroring `ServerSocket.accept()`. The
    /// handler itself was already installed by `EphemeralTCPListener.start()`; this just waits for
    /// whatever it delivers.
    static func acceptOne(_ listener: NWListener, timeout: TimeInterval) -> NWConnection? {
        pendingConnectionBoxesLock.lock()
        let box = pendingConnectionBoxes.removeValue(forKey: ObjectIdentifier(listener))
        pendingConnectionBoxesLock.unlock()
        guard let box, let connection = box.take(timeout: timeout) else { return nil }
        connection.start(queue: DispatchQueue(label: "bridgey.channel.accepted"))
        return connection
    }
}
