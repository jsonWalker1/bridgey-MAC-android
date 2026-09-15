package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelLifecycleTest {
    @Test fun happyPathInitiatorFlow() {
        var s = ChannelState.IDLE
        s = ChannelLifecycle.transition(s, ChannelEvent.OFFER_SENT); assertEquals(ChannelState.NEGOTIATING, s)
        s = ChannelLifecycle.transition(s, ChannelEvent.ACCEPT_RECEIVED); assertEquals(ChannelState.CONNECTING, s)
        s = ChannelLifecycle.transition(s, ChannelEvent.SOCKET_CONNECTED); assertEquals(ChannelState.HANDSHAKING, s)
        s = ChannelLifecycle.transition(s, ChannelEvent.HANDSHAKE_SUCCEEDED); assertEquals(ChannelState.ACTIVE, s)
    }

    @Test fun rejectDuringNegotiationReturnsToIdle() {
        val s = ChannelLifecycle.transition(ChannelState.NEGOTIATING, ChannelEvent.REJECT_RECEIVED)
        assertEquals(ChannelState.IDLE, s)
    }

    @Test fun negotiationTimeoutReturnsToIdle() {
        val s = ChannelLifecycle.transition(ChannelState.NEGOTIATING, ChannelEvent.NEGOTIATION_TIMEOUT)
        assertEquals(ChannelState.IDLE, s)
    }

    @Test fun connectFailureLeadsToFailed() {
        val s = ChannelLifecycle.transition(ChannelState.CONNECTING, ChannelEvent.CONNECT_FAILED)
        assertEquals(ChannelState.FAILED, s)
    }

    @Test fun handshakeFailureLeadsToFailed() {
        val s = ChannelLifecycle.transition(ChannelState.HANDSHAKING, ChannelEvent.HANDSHAKE_FAILED)
        assertEquals(ChannelState.FAILED, s)
    }

    @Test fun malformedOrTamperedWhileActiveLeadsToFailed() {
        val s = ChannelLifecycle.transition(ChannelState.ACTIVE, ChannelEvent.MALFORMED_OR_TAMPERED)
        assertEquals(ChannelState.FAILED, s)
    }

    @Test fun unexpectedSocketCloseWhileActiveLeadsToFailed() {
        val s = ChannelLifecycle.transition(ChannelState.ACTIVE, ChannelEvent.SOCKET_CLOSED)
        assertEquals(ChannelState.FAILED, s)
    }

    @Test fun explicitStopFromActiveGoesThroughClosingToIdle() {
        var s = ChannelLifecycle.transition(ChannelState.ACTIVE, ChannelEvent.STOP_REQUESTED)
        assertEquals(ChannelState.CLOSING, s)
        s = ChannelLifecycle.transition(s, ChannelEvent.SOCKET_CLOSED)
        assertEquals(ChannelState.IDLE, s)
    }

    @Test fun managerCanMoveOnFromFailedBackToIdle() {
        val s = ChannelLifecycle.transition(ChannelState.FAILED, ChannelEvent.STOP_REQUESTED)
        assertEquals(ChannelState.IDLE, s)
    }

    @Test fun sessionResetIsUnconditionalFromEveryState() {
        for (state in ChannelState.entries) {
            assertEquals("from $state", ChannelState.IDLE, ChannelLifecycle.transition(state, ChannelEvent.SESSION_RESET))
        }
    }

    @Test fun unexpectedEventsAreIgnoredNotCrashedOn() {
        // e.g. an ACCEPT_RECEIVED while already ACTIVE is not a valid transition - state just holds.
        assertEquals(ChannelState.ACTIVE, ChannelLifecycle.transition(ChannelState.ACTIVE, ChannelEvent.ACCEPT_RECEIVED))
        assertEquals(ChannelState.IDLE, ChannelLifecycle.transition(ChannelState.IDLE, ChannelEvent.HANDSHAKE_FAILED))
    }
}
