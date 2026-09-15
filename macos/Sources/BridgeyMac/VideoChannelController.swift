import Foundation
import Network

/// Orchestrates the Video and Input dedicated channels: control-channel negotiation, establishment,
/// and the hard reset invariant (frozen M1 spec). Mirrors the wiring pattern already used for
/// MediaRemoteController - a handful of closures, no logic added to PairingCoordinator (Pairing.swift)
/// itself. Direct port of VideoChannelManager.kt.
///
/// M1 scope only: no encoder/decoder, no KVM injection, no UI. onVideoFrame/onInputEvent/
/// sendVideoFrame/sendInputEvent exist so a future M2/M4 consumer has something to hook into: this
/// class is done once negotiation+establishment+security+lifecycle+backpressure work correctly.
final class VideoChannelController {
    private let available: () -> Bool
    private let send: (String, [String: Any]) -> Bool
    private let pairingKeyProvider: () -> Data?
    private let sessionIdProvider: () -> String?
    private let remoteHostProvider: () -> String?

    var onVideoFrame: (EncodedVideoFrame) -> Void = { _ in }
    var onInputEvent: (InputEvent) -> Void = { _ in }

    private var videoState: ChannelState = .idle
    private var inputState: ChannelState = .idle
    private var videoChannelId: String?
    private var inputChannelId: String?
    private var videoDirection: String?
    private var inputDirection: String?
    private var videoTransport: TCPVideoTransport?
    private var inputTransport: TCPInputTransport?
    private var pendingVideoListener: NWListener?
    private var pendingInputListener: NWListener?

    var currentVideoState: ChannelState { videoState }
    var currentInputState: ChannelState { inputState }

    init(
        available: @escaping () -> Bool,
        send: @escaping (String, [String: Any]) -> Bool,
        pairingKeyProvider: @escaping () -> Data?,
        sessionIdProvider: @escaping () -> String?,
        remoteHostProvider: @escaping () -> String?
    ) {
        self.available = available
        self.send = send
        self.pairingKeyProvider = pairingKeyProvider
        self.sessionIdProvider = sessionIdProvider
        self.remoteHostProvider = remoteHostProvider
    }

    // MARK: Outbound negotiation (this device is the initiator)

    func offerVideo(direction: String, width: Int, height: Int, bitrateKbps: Int, fps: Int) {
        guard available(), videoState == .idle else { return }
        let channelId = UUID().uuidString.lowercased()
        videoChannelId = channelId
        videoDirection = direction
        videoState = ChannelLifecycle.transition(videoState, .offerSent)
        _ = send("video.offer", [
            "version": 1, "channelId": channelId, "direction": direction,
            "width": width, "height": height, "bitrateKbps": bitrateKbps, "fps": fps,
        ])
    }

    func offerInput(direction: String) {
        guard available(), inputState == .idle else { return }
        let channelId = UUID().uuidString.lowercased()
        inputChannelId = channelId
        inputDirection = direction
        inputState = ChannelLifecycle.transition(inputState, .offerSent)
        _ = send("input.offer", ["version": 1, "channelId": channelId, "direction": direction])
    }

    func stopVideo() {
        guard let channelId = videoChannelId else { return }
        _ = send("video.stop", ["version": 1, "channelId": channelId])
        teardownVideo(.stopRequested)
    }

    func stopInput() {
        guard let channelId = inputChannelId else { return }
        _ = send("input.stop", ["version": 1, "channelId": channelId])
        teardownInput(.stopRequested)
    }

    // MARK: Inbound control messages (called by Pairing's dispatch)

    func receive(kind: String, payload: [String: Any]) {
        guard (payload["version"] as? Int) == 1 else { return }
        switch kind {
        case "video.offer": handleOffer(payload, isVideo: true)
        case "video.accept": handleAccept(payload, isVideo: true)
        case "video.reject": teardownVideo(.rejectReceived)
        // A peer-initiated stop is a normal, expected shutdown - not a failure - so it's treated the
        // same as our own stopVideo()/stopInput() (stopRequested -> closing -> idle), never
        // socketClosed (which means an *unexpected* drop and lands on failed).
        case "video.stop": teardownVideo(.stopRequested)
        case "input.offer": handleOffer(payload, isVideo: false)
        case "input.accept": handleAccept(payload, isVideo: false)
        case "input.reject": teardownInput(.rejectReceived)
        case "input.stop": teardownInput(.stopRequested)
        default: break
        }
    }

    private func handleOffer(_ payload: [String: Any], isVideo: Bool) {
        guard let channelId = payload["channelId"] as? String, !channelId.isEmpty else { return }
        let purpose = isVideo ? "video" : "input"
        let currentlyIdle = (isVideo ? videoState : inputState) == .idle
        guard available(), currentlyIdle else {
            _ = send("\(purpose).reject", ["version": 1, "channelId": channelId, "reason": currentlyIdle ? "unavailable" : "busy"])
            return
        }
        guard let pairingKey = pairingKeyProvider(), let sessionId = sessionIdProvider(),
              let direction = payload["direction"] as? String else { return }
        let channelKey = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: sessionId, purpose: purpose, direction: direction)
        let security = ChannelSecurityContext(channelKey: channelKey, sessionId: sessionId, purpose: purpose)

        guard let (listener, port) = EphemeralTCPListener.start() else { return }

        // The acceptor never locally "sends an offer", so there is no negotiating phase from its own
        // side - it goes directly to connecting (a socket is about to be opened/accepted), which is
        // exactly what connecting already means in the frozen state diagram for either side.
        if isVideo {
            videoChannelId = channelId
            videoDirection = direction
            pendingVideoListener = listener
            videoState = .connecting
        } else {
            inputChannelId = channelId
            inputDirection = direction
            pendingInputListener = listener
            inputState = .connecting
        }
        _ = send("\(purpose).accept", ["version": 1, "channelId": channelId, "port": Int(port)])
        let thread = Thread { [weak self] in self?.acceptIncoming(isVideo: isVideo, listener: listener, security: security) }
        thread.name = "bridgey-\(purpose)-accept"
        thread.start()
    }

    private func acceptIncoming(isVideo: Bool, listener: NWListener, security: ChannelSecurityContext) {
        // Mirrors the initiator's two-step connecting -> handshaking -> active/failed path (see
        // connectOutgoing): acceptViaListener performs accept()+handshake as one blocking call, so
        // both local transitions are applied here once it returns.
        if isVideo {
            videoState = ChannelLifecycle.transition(videoState, .socketConnected)
            let transport = TCPVideoTransport()
            let ok = transport.acceptViaListener(listener, security: security)
            if ok {
                wireVideoTransport(transport)
                videoState = ChannelLifecycle.transition(videoState, .handshakeSucceeded)
            } else {
                videoState = ChannelLifecycle.transition(videoState, .handshakeFailed)
            }
        } else {
            inputState = ChannelLifecycle.transition(inputState, .socketConnected)
            let transport = TCPInputTransport()
            let ok = transport.acceptViaListener(listener, security: security)
            if ok {
                wireInputTransport(transport)
                inputState = ChannelLifecycle.transition(inputState, .handshakeSucceeded)
            } else {
                inputState = ChannelLifecycle.transition(inputState, .handshakeFailed)
            }
        }
    }

    private func handleAccept(_ payload: [String: Any], isVideo: Bool) {
        guard let channelId = payload["channelId"] as? String, !channelId.isEmpty else { return }
        let expected = isVideo ? videoChannelId : inputChannelId
        guard channelId == expected else { return }
        guard let port = payload["port"] as? Int, (1...65535).contains(port) else { return }
        guard let host = remoteHostProvider(), let pairingKey = pairingKeyProvider(), let sessionId = sessionIdProvider() else { return }
        let purpose = isVideo ? "video" : "input"
        // Direction was decided locally when we sent the offer - not re-read from the wire, since
        // the acceptor derives the identical channelKey from the same value we already hold.
        guard let direction = isVideo ? videoDirection : inputDirection else { return }
        let channelKey = ChannelSecurity.deriveChannelKey(pairingKey: pairingKey, sessionId: sessionId, purpose: purpose, direction: direction)
        let security = ChannelSecurityContext(channelKey: channelKey, sessionId: sessionId, purpose: purpose)

        if isVideo {
            videoState = ChannelLifecycle.transition(videoState, .acceptReceived)
        } else {
            inputState = ChannelLifecycle.transition(inputState, .acceptReceived)
        }
        let thread = Thread { [weak self] in self?.connectOutgoing(isVideo: isVideo, host: host, port: UInt16(port), security: security) }
        thread.name = "bridgey-\(purpose)-connect"
        thread.start()
    }

    private func connectOutgoing(isVideo: Bool, host: String, port: UInt16, security: ChannelSecurityContext) {
        if isVideo {
            videoState = ChannelLifecycle.transition(videoState, .socketConnected)
            let transport = TCPVideoTransport()
            let ok = transport.connectAsInitiator(host: host, port: port, security: security)
            if ok {
                wireVideoTransport(transport)
                videoState = ChannelLifecycle.transition(videoState, .handshakeSucceeded)
            } else {
                videoState = ChannelLifecycle.transition(videoState, .connectFailed)
            }
        } else {
            inputState = ChannelLifecycle.transition(inputState, .socketConnected)
            let transport = TCPInputTransport()
            let ok = transport.connectAsInitiator(host: host, port: port, security: security)
            if ok {
                wireInputTransport(transport)
                inputState = ChannelLifecycle.transition(inputState, .handshakeSucceeded)
            } else {
                inputState = ChannelLifecycle.transition(inputState, .connectFailed)
            }
        }
    }

    private func wireVideoTransport(_ transport: TCPVideoTransport) {
        videoTransport = transport
        transport.onFrameReceived = { [weak self] in self?.onVideoFrame($0) }
        transport.onDisconnected = { [weak self] _ in self?.teardownVideo(.socketClosed) }
    }

    private func wireInputTransport(_ transport: TCPInputTransport) {
        inputTransport = transport
        transport.onEventReceived = { [weak self] in self?.onInputEvent($0) }
        transport.onDisconnected = { [weak self] _ in self?.teardownInput(.socketClosed) }
    }

    func sendVideoFrame(_ frame: EncodedVideoFrame, droppable: Bool) -> SendOutcome {
        videoTransport?.send(frame, droppable: droppable) ?? .failed("no active video channel")
    }

    func sendInputEvent(_ event: InputEvent) -> SendOutcome {
        inputTransport?.send(event) ?? .failed("no active input channel")
    }

    private func teardownVideo(_ event: ChannelEvent) {
        videoState = ChannelLifecycle.transition(videoState, event)
        if videoState == .closing {
            videoState = ChannelLifecycle.transition(videoState, .socketClosed)
        }
        // Whatever state this left us in (including a negotiation/connect/handshake still "in
        // progress" per the pure table), resources are being torn down right now regardless - only
        // an actual failed outcome is preserved as observable; everything else settles on idle.
        if videoState != .failed { videoState = .idle }
        videoTransport?.close()
        videoTransport = nil
        pendingVideoListener?.cancel()
        pendingVideoListener = nil
        videoChannelId = nil
        videoDirection = nil
    }

    private func teardownInput(_ event: ChannelEvent) {
        inputState = ChannelLifecycle.transition(inputState, event)
        if inputState == .closing {
            inputState = ChannelLifecycle.transition(inputState, .socketClosed)
        }
        if inputState != .failed { inputState = .idle }
        inputTransport?.close()
        inputTransport = nil
        pendingInputListener?.cancel()
        pendingInputListener = nil
        inputChannelId = nil
        inputDirection = nil
    }

    /// Hard invariant (Decision 10): called whenever the MAIN Bridgey session resets, regardless of
    /// either channel's current state. Immediate, unconditional.
    func reset() {
        videoState = ChannelLifecycle.transition(videoState, .sessionReset)
        inputState = ChannelLifecycle.transition(inputState, .sessionReset)
        videoTransport?.close(); videoTransport = nil
        inputTransport?.close(); inputTransport = nil
        pendingVideoListener?.cancel(); pendingVideoListener = nil
        pendingInputListener?.cancel(); pendingInputListener = nil
        videoChannelId = nil
        inputChannelId = nil
        videoDirection = nil
        inputDirection = nil
    }
}
