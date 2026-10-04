package dev.bridgey.android

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 session isolation: writes fanned out to several peers (features.update after a settings
 * change) go through one serial writer per session, so a peer that stops reading can never delay
 * another peer's writes, and the caller never blocks.
 */
class SessionWriterIsolationTest {
    @Test fun aBlockedWriterNeverDelaysAnotherSessionOrTheCaller() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val stalled = SessionWriter(scope)
            val healthy = SessionWriter(scope)
            val release = CountDownLatch(1)
            val healthyDone = CountDownLatch(1)

            val started = System.nanoTime()
            stalled.enqueue { release.await(10, TimeUnit.SECONDS) } // a peer with a zero TCP window
            healthy.enqueue { healthyDone.countDown() }
            val enqueueMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue("the healthy session's write must not wait for the stalled one", healthyDone.await(2, TimeUnit.SECONDS))
            assertTrue("enqueueing must not block the caller (took ${enqueueMillis}ms)", enqueueMillis < 500)
            release.countDown()
        } finally {
            scope.cancel()
        }
    }

    @Test fun writesOfOneSessionStayInOrderAndDoNotOverlap() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val writer = SessionWriter(scope)
            val order = Collections.synchronizedList(mutableListOf<Int>())
            val concurrent = java.util.concurrent.atomic.AtomicInteger()
            var overlapped = false
            val done = CountDownLatch(50)
            repeat(50) { index ->
                writer.enqueue {
                    if (concurrent.incrementAndGet() > 1) overlapped = true
                    Thread.sleep(1)
                    order += index
                    concurrent.decrementAndGet()
                    done.countDown()
                }
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals((0 until 50).toList(), order.toList())
            assertFalse("one session's writes must be serial (socket writes must not interleave)", overlapped)
        } finally {
            scope.cancel()
        }
    }

    @Test fun aFailingWriteDoesNotStopLaterWritesOfThatSession() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val writer = SessionWriter(scope)
            val done = CountDownLatch(1)
            writer.enqueue { error("socket closed") }
            writer.enqueue { done.countDown() }
            assertTrue(done.await(2, TimeUnit.SECONDS))
        } finally {
            scope.cancel()
        }
    }

    /** Real sockets: one peer never reads, so writing to it blocks inside the kernel send buffer. */
    @Test fun aPeerThatStopsReadingDoesNotDelayAnotherPeerOverRealSockets() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val loopback = InetAddress.getLoopbackAddress()
        val server = ServerSocket(0, 10, loopback)
        val sockets = mutableListOf<Socket>()
        try {
            val stalledLocal = Socket(loopback, server.localPort).apply { sendBufferSize = 4096 }
            val stalledRemote = server.accept().apply { receiveBufferSize = 4096 } // never read
            val healthyLocal = Socket(loopback, server.localPort)
            val healthyRemote = server.accept()
            sockets += listOf(stalledLocal, stalledRemote, healthyLocal, healthyRemote)

            val stalledWriter = SessionWriter(scope)
            val healthyWriter = SessionWriter(scope)
            val stalledWriteStarted = CountDownLatch(1)
            stalledWriter.enqueue {
                stalledWriteStarted.countDown()
                stalledLocal.getOutputStream().apply { write(ByteArray(8 * 1024 * 1024)); flush() } // blocks: nobody reads
            }
            assertTrue(stalledWriteStarted.await(2, TimeUnit.SECONDS))

            healthyWriter.enqueue {
                healthyLocal.getOutputStream().apply { write("features.update\n".toByteArray()); flush() }
            }
            healthyRemote.soTimeout = 2_000
            val line = BufferedReader(InputStreamReader(healthyRemote.getInputStream())).readLine()
            assertEquals("features.update", line)
        } finally {
            sockets.forEach { runCatching { it.close() } }
            server.close()
            scope.cancel()
        }
    }
}
