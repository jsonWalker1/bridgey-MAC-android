package dev.bridgey.android

import java.net.ServerSocket
import java.util.UUID
import org.json.JSONObject

/**
 * Orchestrates the Video and Input dedicated channels: control-channel negotiation, establishment,
 * and the hard reset invariant (frozen M1 spec). Mirrors the wiring pattern already used for
 * MediaContinuityManager - a handful of closures, no logic added to PairingCoordinator itself.
 *
 * M1 scope only: no encoder/decoder, no KVM injection, no UI. onVideoFrame/onInputEvent/
 * sendVideoFrame/sendInputEvent exist so a future M2/M4 consumer has something to hook into: this
 * class is done once negotiation+establishment+security+lifecycle+backpressure work correctly.
 */
internal class VideoChannelManager(
    private val available: () -> Boolean,
    private val send: (String, JSONObject) -> Boolean,
    private val pairingKeyProvider: () -> ByteArray?,
    private val sessionIdProvider: () -> String?,
    private val remoteHostProvider: () -> String?,
) {
    var onVideoFrame: (EncodedVideoFrame) -> Unit = {}
    var onInputEvent: (InputEvent) -> Unit = {}

    private var videoState = ChannelState.IDLE
    private var inputState = ChannelState.IDLE
    private var videoChannelId: String? = null
    private var inputChannelId: String? = null
    private var videoDirection: String? = null
    private var inputDirection: String? = null
    private var videoTransport: TcpVideoTransport? = null
    private var inputTransport: TcpInputTransport? = null
    private var pendingVideoServer: ServerSocket? = null
    private var pendingInputServer: ServerSocket? = null

    val currentVideoState: ChannelState get() = videoState
    val currentInputState: ChannelState get() = inputState

    // --- Outbound negotiation (this device is the initiator) ---

    fun offerVideo(direction: String, width: Int, height: Int, bitrateKbps: Int, fps: Int) {
        if (!available() || videoState != ChannelState.IDLE) return
        val channelId = UUID.randomUUID().toString()
        videoChannelId = channelId
        videoDirection = direction
        videoState = ChannelLifecycle.transition(videoState, ChannelEvent.OFFER_SENT)
        send(
            "video.offer",
            JSONObject().put("version", 1).put("channelId", channelId).put("direction", direction)
                .put("width", width).put("height", height).put("bitrateKbps", bitrateKbps).put("fps", fps),
        )
    }

    fun offerInput(direction: String) {
        if (!available() || inputState != ChannelState.IDLE) return
        val channelId = UUID.randomUUID().toString()
        inputChannelId = channelId
        inputDirection = direction
        inputState = ChannelLifecycle.transition(inputState, ChannelEvent.OFFER_SENT)
        send("input.offer", JSONObject().put("version", 1).put("channelId", channelId).put("direction", direction))
    }

    fun stopVideo() {
        val channelId = videoChannelId ?: return
        send("video.stop", JSONObject().put("version", 1).put("channelId", channelId))
        teardownVideo(ChannelEvent.STOP_REQUESTED)
    }

    fun stopInput() {
        val channelId = inputChannelId ?: return
        send("input.stop", JSONObject().put("version", 1).put("channelId", channelId))
        teardownInput(ChannelEvent.STOP_REQUESTED)
    }

    // --- Inbound control messages (called by PairingCoordinator's dispatch) ---

    fun receive(kind: String, payload: JSONObject) {
        if (payload.optInt("version") != 1) return
        when (kind) {
            "video.offer" -> handleOffer(payload, isVideo = true)
            "video.accept" -> handleAccept(payload, isVideo = true)
            "video.reject" -> teardownVideo(ChannelEvent.REJECT_RECEIVED)
            // A peer-initiated stop is a normal, expected shutdown - not a failure - so it's treated
            // the same as our own stopVideo()/stopInput() (STOP_REQUESTED -> CLOSING -> IDLE), never
            // SOCKET_CLOSED (which means an *unexpected* drop and lands on FAILED).
            "video.stop" -> teardownVideo(ChannelEvent.STOP_REQUESTED)
            "input.offer" -> handleOffer(payload, isVideo = false)
            "input.accept" -> handleAccept(payload, isVideo = false)
            "input.reject" -> teardownInput(ChannelEvent.REJECT_RECEIVED)
            "input.stop" -> teardownInput(ChannelEvent.STOP_REQUESTED)
        }
    }

    private fun handleOffer(payload: JSONObject, isVideo: Boolean) {
        val channelId = payload.optString("channelId").takeIf { it.isNotEmpty() } ?: return
        val purpose = if (isVideo) "video" else "input"
        val currentlyIdle = (if (isVideo) videoState else inputState) == ChannelState.IDLE
        if (!available() || !currentlyIdle) {
            send("$purpose.reject", JSONObject().put("version", 1).put("channelId", channelId).put("reason", if (!currentlyIdle) "busy" else "unavailable"))
            return
        }
        val pairingKey = pairingKeyProvider() ?: return
        val sessionId = sessionIdProvider() ?: return
        val direction = payload.optString("direction")
        val channelKey = ChannelSecurity.deriveChannelKey(pairingKey, sessionId, purpose, direction)
        val security = ChannelSecurityContext(channelKey, sessionId, purpose)

        val server = try { ServerSocket(0) } catch (t: Throwable) { return }
        val port = server.localPort

        // The acceptor never locally "sends an offer", so there is no NEGOTIATING phase from its own
        // side - it goes directly to CONNECTING (a socket is about to be opened/accepted), which is
        // exactly what CONNECTING already means in the frozen state diagram for either side.
        if (isVideo) {
            videoChannelId = channelId
            videoDirection = direction
            pendingVideoServer = server
            videoState = ChannelState.CONNECTING
        } else {
            inputChannelId = channelId
            inputDirection = direction
            pendingInputServer = server
            inputState = ChannelState.CONNECTING
        }
        send(
            "$purpose.accept",
            JSONObject().put("version", 1).put("channelId", channelId).put("port", port),
        )
        Thread({ acceptIncoming(isVideo, server, security) }, "bridgey-$purpose-accept").apply { isDaemon = true; start() }
    }

    private fun acceptIncoming(isVideo: Boolean, server: ServerSocket, security: ChannelSecurityContext) {
        // Mirrors the initiator's two-step CONNECTING -> HANDSHAKING -> ACTIVE/FAILED path (see
        // connectOutgoing): acceptViaServerSocket performs accept()+handshake as one blocking call,
        // so both local transitions are applied here once it returns.
        if (isVideo) {
            videoState = ChannelLifecycle.transition(videoState, ChannelEvent.SOCKET_CONNECTED)
            val transport = TcpVideoTransport()
            val ok = transport.acceptViaServerSocket(server, security)
            if (ok) {
                wireVideoTransport(transport)
                videoState = ChannelLifecycle.transition(videoState, ChannelEvent.HANDSHAKE_SUCCEEDED)
            } else {
                videoState = ChannelLifecycle.transition(videoState, ChannelEvent.HANDSHAKE_FAILED)
            }
        } else {
            inputState = ChannelLifecycle.transition(inputState, ChannelEvent.SOCKET_CONNECTED)
            val transport = TcpInputTransport()
            val ok = transport.acceptViaServerSocket(server, security)
            if (ok) {
                wireInputTransport(transport)
                inputState = ChannelLifecycle.transition(inputState, ChannelEvent.HANDSHAKE_SUCCEEDED)
            } else {
                inputState = ChannelLifecycle.transition(inputState, ChannelEvent.HANDSHAKE_FAILED)
            }
        }
    }

    private fun handleAccept(payload: JSONObject, isVideo: Boolean) {
        val channelId = payload.optString("channelId").takeIf { it.isNotEmpty() } ?: return
        val expected = if (isVideo) videoChannelId else inputChannelId
        if (channelId != expected) return
        val port = payload.optInt("port", -1)
        if (port !in 1..65535) return
        val host = remoteHostProvider() ?: return
        val pairingKey = pairingKeyProvider() ?: return
        val sessionId = sessionIdProvider() ?: return
        val purpose = if (isVideo) "video" else "input"
        // Direction was decided locally when we sent the offer - not re-read from the wire, since
        // the acceptor derives the identical channelKey from the same value we already hold.
        val direction = (if (isVideo) videoDirection else inputDirection) ?: return
        val channelKey = ChannelSecurity.deriveChannelKey(pairingKey, sessionId, purpose, direction)
        val security = ChannelSecurityContext(channelKey, sessionId, purpose)

        if (isVideo) {
            videoState = ChannelLifecycle.transition(videoState, ChannelEvent.ACCEPT_RECEIVED)
        } else {
            inputState = ChannelLifecycle.transition(inputState, ChannelEvent.ACCEPT_RECEIVED)
        }
        Thread({ connectOutgoing(isVideo, host, port, security) }, "bridgey-$purpose-connect").apply { isDaemon = true; start() }
    }

    private fun connectOutgoing(isVideo: Boolean, host: String, port: Int, security: ChannelSecurityContext) {
        if (isVideo) {
            videoState = ChannelLifecycle.transition(videoState, ChannelEvent.SOCKET_CONNECTED)
            val transport = TcpVideoTransport()
            val ok = transport.connectAsInitiator(host, port, security)
            if (ok) {
                wireVideoTransport(transport)
                videoState = ChannelLifecycle.transition(videoState, ChannelEvent.HANDSHAKE_SUCCEEDED)
            } else {
                videoState = ChannelLifecycle.transition(videoState, ChannelEvent.CONNECT_FAILED)
            }
        } else {
            inputState = ChannelLifecycle.transition(inputState, ChannelEvent.SOCKET_CONNECTED)
            val transport = TcpInputTransport()
            val ok = transport.connectAsInitiator(host, port, security)
            if (ok) {
                wireInputTransport(transport)
                inputState = ChannelLifecycle.transition(inputState, ChannelEvent.HANDSHAKE_SUCCEEDED)
            } else {
                inputState = ChannelLifecycle.transition(inputState, ChannelEvent.CONNECT_FAILED)
            }
        }
    }

    private fun wireVideoTransport(transport: TcpVideoTransport) {
        videoTransport = transport
        transport.onFrameReceived = { onVideoFrame(it) }
        transport.onDisconnected = { teardownVideo(ChannelEvent.SOCKET_CLOSED) }
    }

    private fun wireInputTransport(transport: TcpInputTransport) {
        inputTransport = transport
        transport.onEventReceived = { onInputEvent(it) }
        transport.onDisconnected = { teardownInput(ChannelEvent.SOCKET_CLOSED) }
    }

    fun sendVideoFrame(frame: EncodedVideoFrame, droppable: Boolean): SendOutcome =
        videoTransport?.send(frame, droppable) ?: SendOutcome.Failed("no active video channel")

    fun sendInputEvent(event: InputEvent): SendOutcome =
        inputTransport?.send(event) ?: SendOutcome.Failed("no active input channel")

    private fun teardownVideo(event: ChannelEvent) {
        videoState = ChannelLifecycle.transition(videoState, event)
        if (videoState == ChannelState.CLOSING) {
            videoState = ChannelLifecycle.transition(videoState, ChannelEvent.SOCKET_CLOSED)
        }
        // Whatever state this left us in (including a negotiation/connect/handshake still "in
        // progress" per the pure table), resources are being torn down right now regardless - only
        // an actual FAILED outcome is preserved as observable; everything else settles on IDLE.
        if (videoState != ChannelState.FAILED) videoState = ChannelState.IDLE
        videoTransport?.close()
        videoTransport = null
        runCatching { pendingVideoServer?.close() }
        pendingVideoServer = null
        videoChannelId = null
        videoDirection = null
    }

    private fun teardownInput(event: ChannelEvent) {
        inputState = ChannelLifecycle.transition(inputState, event)
        if (inputState == ChannelState.CLOSING) {
            inputState = ChannelLifecycle.transition(inputState, ChannelEvent.SOCKET_CLOSED)
        }
        if (inputState != ChannelState.FAILED) inputState = ChannelState.IDLE
        inputTransport?.close()
        inputTransport = null
        runCatching { pendingInputServer?.close() }
        pendingInputServer = null
        inputChannelId = null
        inputDirection = null
    }

    /** Hard invariant (Decision 10): called whenever the MAIN Bridgey session resets, regardless of
     * either channel's current state. Immediate, unconditional. */
    fun reset() {
        videoState = ChannelLifecycle.transition(videoState, ChannelEvent.SESSION_RESET)
        inputState = ChannelLifecycle.transition(inputState, ChannelEvent.SESSION_RESET)
        videoTransport?.close(); videoTransport = null
        inputTransport?.close(); inputTransport = null
        runCatching { pendingVideoServer?.close() }; pendingVideoServer = null
        runCatching { pendingInputServer?.close() }; pendingInputServer = null
        videoChannelId = null
        inputChannelId = null
        videoDirection = null
        inputDirection = null
    }
}
