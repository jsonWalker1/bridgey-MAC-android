package dev.bridgey.android

// Pure state machine shared by the Video channel and the Input channel (two independent instances,
// one per channel). Matches the frozen M1 algorithm exactly - see the approved state diagram.

internal enum class ChannelState { IDLE, NEGOTIATING, CONNECTING, HANDSHAKING, ACTIVE, CLOSING, FAILED }

internal enum class ChannelEvent {
    OFFER_SENT,
    ACCEPT_RECEIVED,
    REJECT_RECEIVED,
    NEGOTIATION_TIMEOUT,
    SOCKET_CONNECTED,
    CONNECT_FAILED,
    HANDSHAKE_SUCCEEDED,
    HANDSHAKE_FAILED,
    MALFORMED_OR_TAMPERED,
    STOP_REQUESTED,
    SOCKET_CLOSED,
    SESSION_RESET,
}

internal object ChannelLifecycle {
    /** SESSION_RESET is unconditional: it always wins, from any state, no exceptions. */
    fun transition(current: ChannelState, event: ChannelEvent): ChannelState {
        if (event == ChannelEvent.SESSION_RESET) return ChannelState.IDLE
        return when (current) {
            ChannelState.IDLE -> when (event) {
                ChannelEvent.OFFER_SENT -> ChannelState.NEGOTIATING
                else -> current
            }
            ChannelState.NEGOTIATING -> when (event) {
                ChannelEvent.ACCEPT_RECEIVED -> ChannelState.CONNECTING
                ChannelEvent.REJECT_RECEIVED, ChannelEvent.NEGOTIATION_TIMEOUT -> ChannelState.IDLE
                else -> current
            }
            ChannelState.CONNECTING -> when (event) {
                ChannelEvent.SOCKET_CONNECTED -> ChannelState.HANDSHAKING
                ChannelEvent.CONNECT_FAILED -> ChannelState.FAILED
                else -> current
            }
            ChannelState.HANDSHAKING -> when (event) {
                ChannelEvent.HANDSHAKE_SUCCEEDED -> ChannelState.ACTIVE
                ChannelEvent.HANDSHAKE_FAILED -> ChannelState.FAILED
                else -> current
            }
            ChannelState.ACTIVE -> when (event) {
                ChannelEvent.MALFORMED_OR_TAMPERED, ChannelEvent.SOCKET_CLOSED -> ChannelState.FAILED
                ChannelEvent.STOP_REQUESTED -> ChannelState.CLOSING
                else -> current
            }
            ChannelState.CLOSING -> when (event) {
                ChannelEvent.SOCKET_CLOSED -> ChannelState.IDLE
                else -> current
            }
            ChannelState.FAILED -> when (event) {
                ChannelEvent.STOP_REQUESTED -> ChannelState.IDLE
                else -> current
            }
        }
    }
}
