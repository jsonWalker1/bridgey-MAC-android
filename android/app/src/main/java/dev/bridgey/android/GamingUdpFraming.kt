package dev.bridgey.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GAMING MODE POC (not wired into production capture/decode/input paths - see
 * GamingModePoc.md investigation notes). Defines the UDP realtime packet format used to prove out
 * fragmentation/reassembly/security mechanics before any production Gaming Mode is built.
 *
 * Wire format (all integers big-endian):
 *   [1B version][1B type][8B gamingSessionToken][8B frameId][2B fragmentIndex][2B fragmentCount]
 *   [8B captureTimestampMs][1B flags][4B sequence][12B nonce][ciphertext + 16B GCM tag]
 *
 * Everything before the nonce is the AEAD's "associated data" (AAD) - authenticated but not
 * encrypted - so frameId/fragmentIndex/flags/sequence/etc. cannot be tampered with by a network
 * observer without invalidating the GCM tag, even though they're readable in plaintext for routing
 * (deciding which reassembly bucket a fragment belongs to, or dropping stale packets) before
 * spending a decrypt call. This is a deliberate improvement over the frozen M1 TCP framing (there,
 * `kind`/`sessionId` travel outside any AEAD binding entirely - acceptable there only because every
 * actionable command additionally requires a successful decrypt of its own payload as proof of
 * session possession; here we can do better for free since AAD support costs nothing extra).
 */
internal object GamingUdpFraming {
    const val VERSION: Byte = 1
    const val HEADER_BYTES = 1 + 1 + 8 + 8 + 2 + 2 + 8 + 1 + 4
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    /** Safely below the standard 1500B Ethernet MTU after IP/UDP/our-header overhead, avoiding IP
     *  fragmentation (which would reintroduce exactly the reliability dependency UDP is meant to
     *  avoid here - a single lost IP fragment would silently corrupt the whole UDP datagram). */
    const val MAX_FRAGMENT_PAYLOAD_BYTES = 1200

    object PacketType {
        const val VIDEO_FRAME_FRAGMENT: Byte = 0
        const val INPUT_MOVE: Byte = 1
        const val HEARTBEAT: Byte = 2
        val all = setOf(VIDEO_FRAME_FRAGMENT, INPUT_MOVE, HEARTBEAT)
    }

    object Flags {
        const val KEYFRAME: Int = 1
        const val CONFIG: Int = 2
    }

    data class Header(
        val type: Byte,
        val gamingSessionToken: Long,
        val frameId: Long,
        val fragmentIndex: Int,
        val fragmentCount: Int,
        val captureTimestampMs: Long,
        val flags: Int,
        val sequence: Int,
    )

    data class ParsedPacket(val header: Header, val aad: ByteArray, val nonce: ByteArray, val ciphertext: ByteArray)

    /** Builds the plaintext header bytes (used both as the wire prefix and as AEAD associated
     *  data) - the caller encrypts `payload` separately and appends nonce+ciphertext+tag. */
    fun encodeHeader(header: Header): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.put(VERSION)
        buffer.put(header.type)
        buffer.putLong(header.gamingSessionToken)
        buffer.putLong(header.frameId)
        buffer.putShort(header.fragmentIndex.toShort())
        buffer.putShort(header.fragmentCount.toShort())
        buffer.putLong(header.captureTimestampMs)
        buffer.put(header.flags.toByte())
        buffer.putInt(header.sequence)
        return buffer.array()
    }

    /** Splits a full packet into (header AAD, nonce, ciphertext+tag) without touching crypto -
     *  callers decrypt the ciphertext against the returned AAD themselves (keeps this file crypto-
     *  library-agnostic, matching how VideoFrameFraming.kt already separates framing from Crypto). */
    fun parse(packet: ByteArray): ParsedPacket? {
        if (packet.size < HEADER_BYTES + NONCE_BYTES + TAG_BYTES) return null
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get()
        if (version != VERSION) return null
        val type = buffer.get()
        if (type !in PacketType.all) return null
        val gamingSessionToken = buffer.long
        val frameId = buffer.long
        val fragmentIndex = buffer.short.toInt() and 0xFFFF
        val fragmentCount = buffer.short.toInt() and 0xFFFF
        if (fragmentCount <= 0 || fragmentIndex >= fragmentCount) return null
        val captureTimestampMs = buffer.long
        val flags = buffer.get().toInt() and 0xFF
        val sequence = buffer.int
        val aad = packet.copyOfRange(0, HEADER_BYTES)
        val nonce = packet.copyOfRange(HEADER_BYTES, HEADER_BYTES + NONCE_BYTES)
        val ciphertext = packet.copyOfRange(HEADER_BYTES + NONCE_BYTES, packet.size)
        return ParsedPacket(
            Header(type, gamingSessionToken, frameId, fragmentIndex, fragmentCount, captureTimestampMs, flags, sequence),
            aad,
            nonce,
            ciphertext,
        )
    }

    fun assemble(header: Header, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES)
        val headerBytes = encodeHeader(header)
        return headerBytes + nonce + ciphertext
    }

    /** Splits an encoded video frame's plaintext payload into MTU-safe fragments. Real fragment
     *  encryption/nonce assignment happens per-fragment at the call site (each fragment is its own
     *  independent AEAD-sealed UDP datagram, so losing one fragment never blocks decrypting the
     *  others - there is no cross-fragment authentication dependency). */
    fun splitIntoFragments(payload: ByteArray): List<ByteArray> {
        if (payload.isEmpty()) return listOf(ByteArray(0))
        return payload.toList().chunked(MAX_FRAGMENT_PAYLOAD_BYTES).map { it.toByteArray() }
    }
}
