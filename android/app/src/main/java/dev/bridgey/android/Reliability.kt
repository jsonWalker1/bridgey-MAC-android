package dev.bridgey.android

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.min

internal const val MAX_PROTOCOL_FRAME_BYTES = 65_536
internal const val MAX_TRANSFER_HISTORY = 20

internal class ProtocolFrameTooLargeException : IllegalArgumentException("Protocol frame is too large")

internal fun InputStream.readProtocolLine(maxBytes: Int = MAX_PROTOCOL_FRAME_BYTES): String? {
    val value = ByteArrayOutputStream(min(maxBytes, 1024))
    while (true) {
        when (val byte = read()) {
            -1 -> return value.takeIf { it.size() > 0 }?.toUtf8String()
            '\n'.code -> return value.toUtf8String()
            '\r'.code -> Unit
            else -> {
                if (value.size() >= maxBytes) throw ProtocolFrameTooLargeException()
                value.write(byte)
            }
        }
    }
}

private fun ByteArrayOutputStream.toUtf8String(): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(toByteArray()))
    .toString()

internal fun reconnectDelayMillis(attempt: Int): Long {
    val boundedAttempt = attempt.coerceIn(0, 30)
    return min(1L shl boundedAttempt, 30L) * 1_000L
}

internal const val CONNECT_TIMEOUT_MILLIS = 5_000

/**
 * Connects with a bounded timeout instead of `Socket(host, port)`'s implicit (platform-default,
 * potentially very long) connect timeout. A phone reconnecting right after a Wi-Fi roam/handoff
 * can otherwise have each doomed connect attempt sit blocked for far longer than the exponential
 * backoff between attempts, stretching out how long a real reconnect ends up taking. On any
 * failure (timeout or otherwise) the socket is closed before the exception is rethrown, so the
 * caller's existing runCatching/onFailure handling is unchanged.
 */
internal fun connectWithTimeout(host: String, port: Int, timeoutMillis: Int = CONNECT_TIMEOUT_MILLIS): Socket {
    val socket = Socket()
    runCatching {
        socket.connect(InetSocketAddress(host, port), timeoutMillis)
    }.onFailure {
        socket.close()
        throw it
    }
    return socket
}

internal fun heartbeatExpired(
    supported: Boolean,
    lastReceivedAtMillis: Long,
    nowMillis: Long,
    timeoutMillis: Long = 30_000L,
): Boolean = supported && nowMillis - lastReceivedAtMillis >= timeoutMillis

internal fun recoverInterruptedTransfers(
    transfers: Map<String, FileTransferState>,
): Map<String, FileTransferState> = transfers
    .mapValues { (_, transfer) ->
        if (transfer.active) transfer.copy(
            status = "Transfer interrupted — reconnect to retry",
            active = false,
            progressPercent = null,
        ) else transfer
    }
    .values
    .sortedByDescending(FileTransferState::startedAtMillis)
    .take(MAX_TRANSFER_HISTORY)
    .associateBy(FileTransferState::id)
