import Foundation

/// GAMING MODE POC (not wired into production capture/decode/input paths). Swift mirror of
/// GamingUdpFraming.kt - see that file's doc comment for the full wire-format rationale (AAD-bound
/// header, MTU-safe fragmentation, why this deliberately improves on M1's TCP framing where
/// kind/sessionId aren't AEAD-bound).
enum GamingUdpFraming {
    static let version: UInt8 = 1
    static let headerBytes = 1 + 1 + 8 + 8 + 2 + 2 + 8 + 1 + 4
    static let nonceBytes = 12
    static let tagBytes = 16
    static let maxFragmentPayloadBytes = 1200

    enum PacketType {
        static let videoFrameFragment: UInt8 = 0
        static let inputMove: UInt8 = 1
        static let heartbeat: UInt8 = 2
        static let all: Set<UInt8> = [videoFrameFragment, inputMove, heartbeat]
    }

    enum Flags {
        static let keyframe = 1
        static let config = 2
    }

    struct Header {
        let type: UInt8
        let gamingSessionToken: UInt64
        let frameId: UInt64
        let fragmentIndex: Int
        let fragmentCount: Int
        let captureTimestampMs: UInt64
        let flags: Int
        let sequence: UInt32
    }

    struct ParsedPacket {
        let header: Header
        let aad: Data
        let nonce: Data
        let ciphertext: Data
    }

    static func encodeHeader(_ header: Header) -> Data {
        var data = Data(capacity: headerBytes)
        data.append(version)
        data.append(header.type)
        data.append(contentsOf: header.gamingSessionToken.bigEndianBytes)
        data.append(contentsOf: header.frameId.bigEndianBytes)
        data.append(contentsOf: UInt16(header.fragmentIndex).bigEndianBytes)
        data.append(contentsOf: UInt16(header.fragmentCount).bigEndianBytes)
        data.append(contentsOf: header.captureTimestampMs.bigEndianBytes)
        data.append(UInt8(header.flags))
        data.append(contentsOf: header.sequence.bigEndianBytes)
        return data
    }

    static func parse(_ packet: Data) -> ParsedPacket? {
        guard packet.count >= headerBytes + nonceBytes + tagBytes else { return nil }
        let bytes = [UInt8](packet)
        guard bytes[0] == version else { return nil }
        let type = bytes[1]
        guard PacketType.all.contains(type) else { return nil }
        var offset = 2
        let gamingSessionToken = UInt64(bigEndianBytes: bytes, at: &offset)
        let frameId = UInt64(bigEndianBytes: bytes, at: &offset)
        let fragmentIndex = Int(UInt16(bigEndianBytes: bytes, at: &offset))
        let fragmentCount = Int(UInt16(bigEndianBytes: bytes, at: &offset))
        guard fragmentCount > 0, fragmentIndex < fragmentCount else { return nil }
        let captureTimestampMs = UInt64(bigEndianBytes: bytes, at: &offset)
        let flags = Int(bytes[offset]); offset += 1
        let sequence = UInt32(bigEndianBytes: bytes, at: &offset)
        let aad = packet.subdata(in: 0..<headerBytes)
        let nonce = packet.subdata(in: headerBytes..<(headerBytes + nonceBytes))
        let ciphertext = packet.subdata(in: (headerBytes + nonceBytes)..<packet.count)
        return ParsedPacket(
            header: Header(
                type: type, gamingSessionToken: gamingSessionToken, frameId: frameId,
                fragmentIndex: fragmentIndex, fragmentCount: fragmentCount,
                captureTimestampMs: captureTimestampMs, flags: flags, sequence: sequence
            ),
            aad: aad, nonce: nonce, ciphertext: ciphertext
        )
    }

    static func assemble(_ header: Header, nonce: Data, ciphertext: Data) -> Data {
        encodeHeader(header) + nonce + ciphertext
    }

    static func splitIntoFragments(_ payload: Data) -> [Data] {
        guard !payload.isEmpty else { return [Data()] }
        var fragments: [Data] = []
        var offset = 0
        while offset < payload.count {
            let end = min(offset + maxFragmentPayloadBytes, payload.count)
            fragments.append(payload.subdata(in: offset..<end))
            offset = end
        }
        return fragments
    }
}

private extension FixedWidthInteger {
    var bigEndianBytes: [UInt8] {
        withUnsafeBytes(of: bigEndian, Array.init)
    }
}

private extension UInt64 {
    init(bigEndianBytes bytes: [UInt8], at offset: inout Int) {
        var value: UInt64 = 0
        for i in 0..<8 { value = (value << 8) | UInt64(bytes[offset + i]) }
        offset += 8
        self = value
    }
}

private extension UInt32 {
    init(bigEndianBytes bytes: [UInt8], at offset: inout Int) {
        var value: UInt32 = 0
        for i in 0..<4 { value = (value << 8) | UInt32(bytes[offset + i]) }
        offset += 4
        self = value
    }
}

private extension UInt16 {
    init(bigEndianBytes bytes: [UInt8], at offset: inout Int) {
        var value: UInt16 = 0
        for i in 0..<2 { value = (value << 8) | UInt16(bytes[offset + i]) }
        offset += 2
        self = value
    }
}
