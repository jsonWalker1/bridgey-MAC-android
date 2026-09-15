import CryptoKit
import Foundation
import Network

// Pure crypto for the video/input dedicated-channel handshake (frozen spec: "M1 - Security Design").
// Direct port of ChannelSecurity.kt - same manual HKDF-SHA256 (extract-then-expand, single 32-byte
// block) construction, so the derived channelKey is byte-identical to the Android side given the
// same pairingKey/sessionId/purpose/direction. No new crypto primitive.

let channelOpenNonceBytes = 16
let channelProofBytes = 32 // HMAC-SHA256 output size

struct ChannelSecurityContext {
    let channelKey: Data
    let sessionId: String
    let purpose: String // "video" | "input"
}

enum ChannelSecurity {
    static func deriveChannelKey(pairingKey: Data, sessionId: String, purpose: String, direction: String) -> Data {
        let salt = sha256(domainString(sessionId, purpose, direction))
        let extract = hmacSHA256(key: salt, message: pairingKey)
        return hmacSHA256(key: extract, message: Data("bridgey-channel-v1".utf8) + Data([1]))
    }

    static func generateOpenNonce() -> Data {
        var bytes = [UInt8](repeating: 0, count: channelOpenNonceBytes)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }

    static func computeToken(channelKey: Data, sessionId: String, purpose: String, openNonce: Data) -> Data {
        hmacSHA256(key: channelKey, message: domainString("bridgey-channel-open-v1", sessionId, purpose) + openNonce)
    }

    static func computeAckProof(channelKey: Data, sessionId: String, purpose: String, openNonce: Data) -> Data {
        hmacSHA256(key: channelKey, message: domainString("bridgey-channel-ack-v1", sessionId, purpose) + openNonce)
    }

    static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count { diff |= a[a.startIndex + i] ^ b[b.startIndex + i] }
        return diff == 0
    }

    /// Raw-bytes AES-GCM for the binary frame channel (frozen spec, section 4) - same algorithm as
    /// the existing encrypt/decrypt in ProtocolCrypto.swift, without the base64 encoding the JSON
    /// envelope needs. Ciphertext includes the 16-byte GCM tag appended, matching that convention.
    static func seal(key: Data, plaintext: Data) -> (nonce: Data, ciphertext: Data)? {
        guard let sealed = try? AES.GCM.seal(plaintext, using: SymmetricKey(data: key)) else { return nil }
        return (Data(sealed.nonce), sealed.ciphertext + sealed.tag)
    }

    static func open(key: Data, nonce: Data, ciphertext: Data) -> Data? {
        guard ciphertext.count >= 16, let gcmNonce = try? AES.GCM.Nonce(data: nonce) else { return nil }
        guard let box = try? AES.GCM.SealedBox(nonce: gcmNonce, ciphertext: ciphertext.dropLast(16), tag: ciphertext.suffix(16)) else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key))
    }

    static func uuidToBytes(_ uuidString: String) -> Data? {
        guard let uuid = UUID(uuidString: uuidString) else { return nil }
        let u = uuid.uuid
        return Data([u.0, u.1, u.2, u.3, u.4, u.5, u.6, u.7, u.8, u.9, u.10, u.11, u.12, u.13, u.14, u.15])
    }

    static func uuidFromBytes(_ bytes: Data) -> String? {
        guard bytes.count == 16 else { return nil }
        let b = [UInt8](bytes)
        let u: uuid_t = (b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15])
        return UUID(uuid: u).uuidString.lowercased()
    }

    private static func purposeByte(_ purpose: String) -> UInt8 { purpose == "video" ? 0 : 1 }
    private static func purpose(fromByte value: UInt8) -> String? {
        switch value {
        case 0: return "video"
        case 1: return "input"
        default: return nil
        }
    }

    /// Initiator side of the handshake (frozen algorithm A, steps A8-A13d). Blocking - call from a
    /// background thread. Returns true only once the acceptor's ackProof has been verified.
    static func performInitiatorHandshake(_ connection: NWConnection, security: ChannelSecurityContext, timeout: TimeInterval = 5) -> Bool {
        guard let sessionIdBytes = uuidToBytes(security.sessionId) else { return false }
        let openNonce = generateOpenNonce()
        let token = computeToken(channelKey: security.channelKey, sessionId: security.sessionId, purpose: security.purpose, openNonce: openNonce)
        var payload = Data()
        payload.append(sessionIdBytes)
        payload.append(purposeByte(security.purpose))
        payload.append(openNonce)
        payload.append(token)
        guard BlockingConnectionIO.send(connection, payload, timeout: timeout) else { return false }
        guard let ackProof = BlockingConnectionIO.receiveExactly(connection, channelProofBytes, timeout: timeout) else { return false }
        let expected = computeAckProof(channelKey: security.channelKey, sessionId: security.sessionId, purpose: security.purpose, openNonce: openNonce)
        return constantTimeEquals(ackProof, expected)
    }

    /// Acceptor side (steps A11-A12). `security` carries the acceptor's OWN idea of the currently
    /// live session/channel - the claimed values read off the wire are checked against it, never
    /// trusted on their own. Blocking - call from a background thread.
    static func performAcceptorHandshake(_ connection: NWConnection, security: ChannelSecurityContext, timeout: TimeInterval = 5) -> Bool {
        guard let sessionIdBytes = BlockingConnectionIO.receiveExactly(connection, 16, timeout: timeout),
              let purposeByteData = BlockingConnectionIO.receiveExactly(connection, 1, timeout: timeout),
              let openNonce = BlockingConnectionIO.receiveExactly(connection, channelOpenNonceBytes, timeout: timeout),
              let token = BlockingConnectionIO.receiveExactly(connection, channelProofBytes, timeout: timeout) else { return false }

        guard let claimedSessionId = uuidFromBytes(sessionIdBytes),
              let claimedPurpose = purpose(fromByte: purposeByteData[purposeByteData.startIndex]),
              claimedSessionId == security.sessionId, claimedPurpose == security.purpose else { return false }

        let expectedToken = computeToken(channelKey: security.channelKey, sessionId: security.sessionId, purpose: security.purpose, openNonce: openNonce)
        guard constantTimeEquals(token, expectedToken) else { return false }

        let ackProof = computeAckProof(channelKey: security.channelKey, sessionId: security.sessionId, purpose: security.purpose, openNonce: openNonce)
        return BlockingConnectionIO.send(connection, ackProof, timeout: timeout)
    }

    // Joins parts with a single embedded NUL byte between each, matching the Kotlin sides
    // actual runtime behavior confirmed against a reference implementation, not a printable
    // separator character. Built by explicit byte concatenation, not a string literal separator,
    // so there is no ambiguity about what byte ends up between parts.
    private static func domainString(_ parts: String...) -> Data {
        var result = Data()
        for (index, part) in parts.enumerated() {
            if index > 0 { result.append(0) }
            result.append(contentsOf: Array(part.utf8))
        }
        return result
    }

    private static func sha256(_ data: Data) -> Data { Data(SHA256.hash(data: data)) }

    private static func hmacSHA256(key: Data, message: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: key)))
    }
}

/// Synchronous send/receive over an NWConnection via a semaphore, mirroring the blocking
/// Socket I/O the Android side uses - the same pattern already used by Session.sendAndWait
/// elsewhere in this codebase, generalized for the video/input channel's own connections.
enum BlockingConnectionIO {
    static func send(_ connection: NWConnection, _ data: Data, timeout: TimeInterval) -> Bool {
        let semaphore = DispatchSemaphore(value: 0)
        var ok = false
        connection.send(content: data, completion: .contentProcessed { error in
            ok = error == nil
            semaphore.signal()
        })
        return semaphore.wait(timeout: .now() + timeout) == .success && ok
    }

    static func receiveExactly(_ connection: NWConnection, _ count: Int, timeout: TimeInterval) -> Data? {
        guard count > 0 else { return Data() }
        var buffer = Data()
        while buffer.count < count {
            let semaphore = DispatchSemaphore(value: 0)
            var chunk: Data?
            var failed = false
            connection.receive(minimumIncompleteLength: 1, maximumLength: count - buffer.count) { data, _, isComplete, error in
                chunk = data
                failed = error != nil || (data == nil && isComplete)
                semaphore.signal()
            }
            guard semaphore.wait(timeout: .now() + timeout) == .success else { return nil }
            guard !failed, let data = chunk, !data.isEmpty else { return nil }
            buffer.append(data)
        }
        return buffer
    }
}
