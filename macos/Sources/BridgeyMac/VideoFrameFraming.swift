import Foundation

// Binary frame format (frozen spec, section 4):
//   [4B frameLength][1B version][1B type][8B streamId][8B sequence][8B captureTimestampMs][12B nonce][ciphertext+16B tag]
// frameLength counts everything AFTER the frameLength field itself.
// Shared by both the video channel and the input channel - only the `type` value range differs.
// Direct port of VideoFrameFraming.kt.

let frameVersion: UInt8 = 1
let frameNonceBytes = 12
let frameHeaderBytesAfterLength = 1 + 1 + 8 + 8 + 8 + frameNonceBytes // = 38

enum VideoFrameType {
    static let config = 0
    static let keyframe = 1
    static let delta = 2
    static let keyframeRequest = 3
    static let streamRestart = 4
    static let all: Set<Int> = [config, keyframe, delta, keyframeRequest, streamRestart]
}

enum InputFrameType {
    static let pointer = 16
    static let key = 17
    static let text = 18
    // 19 = CONTROLLER, reserved, unused in M1.
    static let all: Set<Int> = [pointer, key, text]
}

struct FrameHeader {
    let version: UInt8
    let type: Int
    let streamId: Int64
    let sequence: Int64
    let captureTimestampMs: Int64
    let nonce: Data
}

struct ParsedFrame {
    let header: FrameHeader
    let ciphertext: Data
}

enum VideoFrameFraming {
    /// Encodes a complete wire frame, including the 4-byte length prefix, ready to write to a socket.
    static func encodeFrame(
        type: Int,
        streamId: Int64,
        sequence: Int64,
        captureTimestampMs: Int64,
        nonce: Data,
        ciphertext: Data
    ) -> Data {
        precondition(nonce.count == frameNonceBytes, "nonce must be \(frameNonceBytes) bytes")
        let afterLength = frameHeaderBytesAfterLength + ciphertext.count
        var buffer = Data(capacity: 4 + afterLength)
        buffer.appendBigEndian(UInt32(afterLength))
        buffer.append(frameVersion)
        buffer.append(UInt8(type))
        buffer.appendBigEndian(UInt64(bitPattern: streamId))
        buffer.appendBigEndian(UInt64(bitPattern: sequence))
        buffer.appendBigEndian(UInt64(bitPattern: captureTimestampMs))
        buffer.append(nonce)
        buffer.append(ciphertext)
        return buffer
    }

    /// Parses the frame body - everything the socket read AFTER the 4-byte length prefix (i.e.
    /// exactly `frameLength` bytes). Returns nil for a body too short to even contain a header;
    /// does not itself enforce a maximum size - the caller must reject an oversized declared
    /// `frameLength` before ever reading/allocating that many bytes.
    static func parseFrameBody(_ body: Data) -> ParsedFrame? {
        guard body.count >= frameHeaderBytesAfterLength else { return nil }
        var offset = body.startIndex
        let version = body[offset]; offset += 1
        let type = Int(body[offset]); offset += 1
        let streamId = body.readBigEndianInt64(at: &offset)
        let sequence = body.readBigEndianInt64(at: &offset)
        let captureTimestampMs = body.readBigEndianInt64(at: &offset)
        let nonce = body[offset..<(offset + frameNonceBytes)]; offset += frameNonceBytes
        let ciphertext = body[offset...]
        return ParsedFrame(
            header: FrameHeader(version: version, type: type, streamId: streamId, sequence: sequence, captureTimestampMs: captureTimestampMs, nonce: Data(nonce)),
            ciphertext: Data(ciphertext)
        )
    }
}

extension Data {
    mutating func appendBigEndian(_ value: UInt32) {
        var big = value.bigEndian
        Swift.withUnsafeBytes(of: &big) { append(contentsOf: $0) }
    }

    mutating func appendBigEndian(_ value: UInt64) {
        var big = value.bigEndian
        Swift.withUnsafeBytes(of: &big) { append(contentsOf: $0) }
    }

    func readBigEndianUInt32(at offset: inout Int) -> UInt32 {
        let range = offset..<(offset + 4)
        let value = self[range].withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) }.bigEndian
        offset += 4
        return value
    }

    func readBigEndianInt64(at offset: inout Int) -> Int64 {
        let range = offset..<(offset + 8)
        let value = self[range].withUnsafeBytes { $0.loadUnaligned(as: UInt64.self) }.bigEndian
        offset += 8
        return Int64(bitPattern: value)
    }
}
