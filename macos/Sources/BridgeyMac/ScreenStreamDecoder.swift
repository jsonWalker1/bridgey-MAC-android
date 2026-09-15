import AVFoundation
import CoreMedia
import Foundation

/// M2: adapts the verified ~/screen-poc-mac H264Decoder onto the frozen M1 VideoChannelController -
/// receives already-authenticated, already-framed EncodedVideoFrame values (instead of the PoC's raw
/// length-prefixed socket bytes) and decodes/displays them exactly as the PoC did. Self-healing: if a
/// keyframe/delta frame arrives before SPS/PPS are known (a fresh connection racing the first CONFIG
/// frame, or - more importantly - a reconnect where the Android encoder is still running and will
/// never re-emit its codec-config buffer on its own), it asks Android for a fresh CONFIG + keyframe
/// via the already-defined VideoFrameType.KEYFRAME_REQUEST rather than sitting there undecodable.
@MainActor
final class ScreenStreamDecoder: ObservableObject {
    private var formatDescription: CMVideoFormatDescription?
    private var sps: Data?
    private var pps: Data?
    let displayLayer = AVSampleBufferDisplayLayer()
    @Published private(set) var isReceivingVideo = false
    /// The encoded phone-screen dimensions, known once the first CONFIG frame's SPS/PPS are parsed.
    /// Published so the presenting view can compute the displayed content rectangle (see
    /// VideoContentGeometry) - not just size the layer to the window's full bounds.
    @Published private(set) var sourceSize: CGSize?
    @Published var presentationMode: VideoPresentationMode = .fit

    /// window bounds -> video content viewport -> this rectangle, for the given container bounds.
    func contentRect(in bounds: CGRect) -> CGRect {
        VideoContentGeometry.contentRect(sourceSize: sourceSize, mode: presentationMode, in: bounds)
    }

    /// Wired by PairingCoordinator to videoChannel.sendVideoFrame(... KEYFRAME_REQUEST ...).
    var requestKeyframe: () -> Void = {}

    func handle(_ frame: EncodedVideoFrame) {
        if formatDescription == nil, frame.type == VideoFrameType.keyframe || frame.type == VideoFrameType.delta {
            requestKeyframe()
            return
        }
        isReceivingVideo = true
        for unit in Self.splitAnnexB(frame.payload) {
            guard let first = unit.first else { continue }
            switch first & 0x1F {
            // Android recreates its encoder (and re-derives fresh SPS/PPS) whenever the device
            // rotates, so a genuinely new pair (different bytes, e.g. new dimensions) must force
            // the stale formatDescription to be rebuilt, not silently ignored the way a
            // duplicate/unchanged pair should be. (Deliberately plain property access, not an
            // `inout` helper: passing `&sps`/`&pps` into a function that itself reads `self.sps`/
            // `self.pps` via tryBuildFormat() is an overlapping-exclusive-access Swift runtime trap.)
            case 7:
                if sps != unit { sps = unit; formatDescription = nil; tryBuildFormat() }
            case 8:
                if pps != unit { pps = unit; formatDescription = nil; tryBuildFormat() }
            default: enqueue(unit)
            }
        }
    }

    /// Mirrors videoChannel.reset() - called at the same session-reset sites so a stale
    /// formatDescription from a previous connection is never fed a new connection's NAL units.
    func reset() {
        formatDescription = nil
        sps = nil
        pps = nil
        isReceivingVideo = false
        sourceSize = nil
        displayLayer.flush()
    }

    private func tryBuildFormat() {
        guard let sps, let pps, formatDescription == nil else { return }
        let result = sps.withUnsafeBytes { spsPtr -> CMVideoFormatDescription? in
            pps.withUnsafeBytes { ppsPtr -> CMVideoFormatDescription? in
                let spsPointer = spsPtr.bindMemory(to: UInt8.self).baseAddress!
                let ppsPointer = ppsPtr.bindMemory(to: UInt8.self).baseAddress!
                var params: [UnsafePointer<UInt8>] = [spsPointer, ppsPointer]
                var sizes: [Int] = [sps.count, pps.count]
                var desc: CMVideoFormatDescription?
                _ = CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: 2,
                    parameterSetPointers: &params,
                    parameterSetSizes: &sizes,
                    nalUnitHeaderLength: 4,
                    formatDescriptionOut: &desc
                )
                return desc
            }
        }
        formatDescription = result
        if let result {
            let dimensions = CMVideoFormatDescriptionGetDimensions(result)
            let newSize = CGSize(width: Int(dimensions.width), height: Int(dimensions.height))
            if let previousSize = sourceSize, previousSize != newSize {
                NSLog(
                    "VIDEO source resolution changed: %.0fx%.0f -> %.0fx%.0f (formatDescription rebuilt in place - " +
                        "same video channel/TCP connection, no reset, no renegotiation)",
                    previousSize.width, previousSize.height, newSize.width, newSize.height
                )
            }
            sourceSize = newSize
        }
    }

    private func enqueue(_ nal: Data) {
        guard let formatDescription else { return }
        // Convert Annex-B NAL (no start code, already split) to AVCC (4-byte length prefix).
        var length = UInt32(nal.count).bigEndian
        var avcc = Data(bytes: &length, count: 4)
        avcc.append(nal)

        var blockBuffer: CMBlockBuffer?
        let status = avcc.withUnsafeBytes { _ -> OSStatus in
            CMBlockBufferCreateWithMemoryBlock(
                allocator: kCFAllocatorDefault,
                memoryBlock: nil,
                blockLength: avcc.count,
                blockAllocator: kCFAllocatorDefault,
                customBlockSource: nil,
                offsetToData: 0,
                dataLength: avcc.count,
                flags: 0,
                blockBufferOut: &blockBuffer
            )
        }
        guard status == noErr, let blockBuffer else { return }
        avcc.withUnsafeBytes { ptr in
            _ = CMBlockBufferReplaceDataBytes(with: ptr.baseAddress!, blockBuffer: blockBuffer, offsetIntoDestination: 0, dataLength: avcc.count)
        }

        var sampleBuffer: CMSampleBuffer?
        var sampleSizeArray = [avcc.count]
        let sampleStatus = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: formatDescription,
            sampleCount: 1,
            sampleTimingEntryCount: 0,
            sampleTimingArray: nil,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSizeArray,
            sampleBufferOut: &sampleBuffer
        )
        guard sampleStatus == noErr, let sampleBuffer else { return }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: true) {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(), Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        displayLayer.enqueue(sampleBuffer)
    }

    private static func splitAnnexB(_ data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var startCodePositions: [Int] = []
        var payloadStarts: [Int] = []
        var i = 0
        while i + 2 < bytes.count {
            if bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 1 {
                startCodePositions.append(i)
                payloadStarts.append(i + 3)
                i += 3
            } else if i + 3 < bytes.count, bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 0, bytes[i + 3] == 1 {
                startCodePositions.append(i)
                payloadStarts.append(i + 4)
                i += 4
            } else {
                i += 1
            }
        }
        var units: [Data] = []
        for (idx, payloadStart) in payloadStarts.enumerated() {
            let payloadEnd = idx + 1 < startCodePositions.count ? startCodePositions[idx + 1] : bytes.count
            if payloadStart < payloadEnd { units.append(data.subdata(in: payloadStart..<payloadEnd)) }
        }
        return units
    }
}
