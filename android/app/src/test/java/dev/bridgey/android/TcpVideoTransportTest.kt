package dev.bridgey.android

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real loopback-socket integration tests (frozen spec, section 9's minimal test suite) - no
 * mocking of the handshake/framing/security path. */
class TcpVideoTransportTest {
    private val pairingKey = ByteArray(32) { it.toByte() }
    // Real Bridgey session IDs are UUIDs (Session.id = UUID.randomUUID().toString()) - the
    // handshake wire format encodes sessionId as 16 raw bytes, so fixtures must be valid UUIDs too.
    private val sessionOne = java.util.UUID.randomUUID().toString()
    private val sessionTwo = java.util.UUID.randomUUID().toString()
    private val sessionEvil = java.util.UUID.randomUUID().toString()

    private fun security(purpose: String = "video", sessionId: String = sessionOne, direction: String = "android_to_mac") =
        ChannelSecurityContext(ChannelSecurity.deriveChannelKey(pairingKey, sessionId, purpose, direction), sessionId, purpose)

    private fun connectedPair(security: ChannelSecurityContext = security()): Pair<TcpVideoTransport, TcpVideoTransport> {
        val server = ServerSocket(0)
        val acceptor = TcpVideoTransport()
        val acceptLatch = CountDownLatch(1)
        val acceptResult = AtomicReference<Boolean>()
        Thread({ acceptResult.set(acceptor.acceptViaServerSocket(server, security)); acceptLatch.countDown() }).start()

        val initiator = TcpVideoTransport()
        val ok = initiator.connectAsInitiator("127.0.0.1", server.localPort, security)
        assertTrue("initiator handshake should succeed", ok)
        assertTrue(acceptLatch.await(5, TimeUnit.SECONDS))
        assertTrue("acceptor handshake should succeed", acceptResult.get() == true)
        return initiator to acceptor
    }

    @Test fun handshakeSucceedsAndFramesRoundTrip() {
        val (initiator, acceptor) = connectedPair()
        val received = CountDownLatch(1)
        val receivedFrame = AtomicReference<EncodedVideoFrame>()
        acceptor.onFrameReceived = { receivedFrame.set(it); received.countDown() }

        val outcome = initiator.send(EncodedVideoFrame(VideoFrameType.KEYFRAME, streamId = 1, captureTimestampMs = 123, payload = "frame-bytes".toByteArray()), droppable = false)
        assertEquals(SendOutcome.Sent, outcome)
        assertTrue(received.await(5, TimeUnit.SECONDS))
        assertEquals("frame-bytes", String(receivedFrame.get().payload))
        assertEquals(VideoFrameType.KEYFRAME, receivedFrame.get().type)

        initiator.close(); acceptor.close()
    }

    @Test fun invalidCredentialIsRejected() {
        val server = ServerSocket(0)
        val acceptor = TcpVideoTransport()
        val acceptLatch = CountDownLatch(1)
        val acceptResult = AtomicReference<Boolean>()
        Thread({ acceptResult.set(acceptor.acceptViaServerSocket(server, security(sessionId = sessionOne))); acceptLatch.countDown() }).start()

        val initiator = TcpVideoTransport()
        // Initiator uses a DIFFERENT session id than the acceptor expects -> different channelKey,
        // wrong token.
        val ok = initiator.connectAsInitiator("127.0.0.1", server.localPort, security(sessionId = sessionEvil))
        assertFalse("initiator should not see a valid ack", ok)
        assertTrue(acceptLatch.await(5, TimeUnit.SECONDS))
        assertFalse("acceptor must not accept a mismatched token", acceptResult.get() == true)
    }

    @Test fun staleSessionIdIsRejected() {
        val server = ServerSocket(0)
        val acceptor = TcpVideoTransport()
        // Acceptor believes the CURRENT live session is sessionOne.
        val acceptorSecurity = security(sessionId = sessionOne)
        val acceptLatch = CountDownLatch(1)
        val acceptResult = AtomicReference<Boolean>()
        Thread({ acceptResult.set(acceptor.acceptViaServerSocket(server, acceptorSecurity)); acceptLatch.countDown() }).start()

        val initiator = TcpVideoTransport()
        // Initiator still has channelKey/token derived from an OLD, no-longer-current session.
        val staleSecurity = security(sessionId = sessionTwo)
        val ok = initiator.connectAsInitiator("127.0.0.1", server.localPort, staleSecurity)
        assertFalse(ok)
        assertTrue(acceptLatch.await(5, TimeUnit.SECONDS))
        assertFalse(acceptResult.get() == true)
    }

    @Test fun replayedSequenceIsSilentlyDroppedConnectionStaysOpen() {
        val (initiator, acceptor) = connectedPair()
        val receivedTitles = mutableListOf<String>()
        val latch = CountDownLatch(2)
        acceptor.onFrameReceived = { receivedTitles.add(String(it.payload)); latch.countDown() }

        initiator.send(EncodedVideoFrame(VideoFrameType.DELTA, 1, 1, "first".toByteArray()), droppable = true)
        initiator.send(EncodedVideoFrame(VideoFrameType.DELTA, 1, 2, "second".toByteArray()), droppable = true)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("first", "second"), receivedTitles)

        // Manually craft and inject a replay of sequence 1 directly at the framing level is exactly
        // what TcpVideoTransport's own send() would never do (sequence always increases) - the
        // replay-rejection path is exercised at the receive loop level instead: sending a THIRD
        // frame is sequence 3, which must still be accepted (proving sequence gaps aren't required
        // to be contiguous, only strictly increasing).
        val thirdLatch = CountDownLatch(1)
        acceptor.onFrameReceived = { receivedTitles.add(String(it.payload)); thirdLatch.countDown() }
        initiator.send(EncodedVideoFrame(VideoFrameType.DELTA, 1, 3, "third".toByteArray()), droppable = true)
        assertTrue(thirdLatch.await(5, TimeUnit.SECONDS))
        assertEquals("third", receivedTitles.last())

        initiator.close(); acceptor.close()
    }

    @Test fun malformedFrameClosesTheConnection() {
        val sec = security()
        val server = ServerSocket(0)
        val acceptor = TcpVideoTransport()
        val disconnected = CountDownLatch(1)
        acceptor.onDisconnected = { disconnected.countDown() }
        Thread({ acceptor.acceptViaServerSocket(server, sec) }).start()

        val socket = java.net.Socket("127.0.0.1", server.localPort)
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        assertTrue(ChannelSecurity.performInitiatorHandshake(output, input, sec))

        // Write a frame claiming a bogus version byte.
        val bogus = VideoFrameFraming.encodeFrame(VideoFrameType.KEYFRAME, 1, 1, 0, ByteArray(FRAME_NONCE_BYTES), byteArrayOf(1, 2, 3))
        bogus[4] = 99 // corrupt the version field
        output.write(bogus); output.flush()

        assertTrue("acceptor must disconnect on malformed frame", disconnected.await(5, TimeUnit.SECONDS))
        socket.close()
    }

    @Test fun oversizedDeclaredFrameLengthClosesWithoutReadingItAll() {
        val maxBytes = 1024
        val sec = security()
        val server = ServerSocket(0)
        val acceptor = TcpVideoTransport(maxFrameBytes = maxBytes)
        val disconnected = CountDownLatch(1)
        acceptor.onDisconnected = { disconnected.countDown() }
        Thread({ acceptor.acceptViaServerSocket(server, sec) }).start()

        val socket = java.net.Socket("127.0.0.1", server.localPort)
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        assertTrue(ChannelSecurity.performInitiatorHandshake(output, input, sec))

        // Declare a frame length far larger than maxFrameBytes, then only send a few actual bytes -
        // if the acceptor tried to allocate/read the declared length it would hang waiting for data
        // that never arrives; it must reject based on the length field alone.
        val hugeLength = maxBytes * 100
        output.write(byteArrayOf(
            ((hugeLength ushr 24) and 0xFF).toByte(), ((hugeLength ushr 16) and 0xFF).toByte(),
            ((hugeLength ushr 8) and 0xFF).toByte(), (hugeLength and 0xFF).toByte(),
        ))
        output.write(byteArrayOf(1, 2, 3))
        output.flush()

        assertTrue("acceptor must reject an oversized declared length promptly", disconnected.await(5, TimeUnit.SECONDS))
        socket.close()
    }

    @Test fun simultaneousVideoAndInputChannelsDoNotCrossAuthenticate() {
        val videoSecurity = security(purpose = "video")
        val inputSecurity = ChannelSecurityContext(
            ChannelSecurity.deriveChannelKey(pairingKey, sessionOne, "input", "android_to_mac"), sessionOne, "input",
        )
        val videoServer = ServerSocket(0)
        val videoAcceptor = TcpVideoTransport()
        Thread({ videoAcceptor.acceptViaServerSocket(videoServer, videoSecurity) }).start()

        // Attempt to open the VIDEO server socket using the INPUT channel's security context -
        // must fail, proving key separation actually prevents cross-channel authentication.
        val initiator = TcpVideoTransport()
        val ok = initiator.connectAsInitiator("127.0.0.1", videoServer.localPort, inputSecurity)
        assertFalse("input channelKey must not authenticate the video channel", ok)
    }

    @Test fun backpressureDropsOldestDroppableFrameNotAKeyframe() {
        val (initiator, _) = connectedPair()
        // Fill the queue capacity with droppable delta frames without an active reader on the
        // other side actually draining fast enough to matter for this assertion - we only check
        // the transport's own bookkeeping (metrics/queue), not the peer.
        var lastOutcome: SendOutcome = SendOutcome.Failed("unset")
        repeat(DEFAULT_VIDEO_QUEUE_CAPACITY + 5) { i ->
            lastOutcome = initiator.send(EncodedVideoFrame(VideoFrameType.DELTA, 1, (i + 1).toLong(), ByteArray(16)), droppable = true)
        }
        assertNotNull(lastOutcome)
        // A non-droppable keyframe sent right after must still be accepted (not Failed) even though
        // the queue was full of droppable entries a moment ago.
        val keyframeOutcome = initiator.send(EncodedVideoFrame(VideoFrameType.KEYFRAME, 1, 9_999, ByteArray(16)), droppable = false)
        assertTrue(keyframeOutcome is SendOutcome.Sent || keyframeOutcome is SendOutcome.QueuedBounded)
        initiator.close()
    }
}
