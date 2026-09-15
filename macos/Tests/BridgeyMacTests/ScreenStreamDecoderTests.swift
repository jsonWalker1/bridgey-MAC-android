import XCTest
@testable import BridgeyMac

@MainActor
final class ScreenStreamDecoderTests: XCTestCase {
    /// Regression test for a real crash: an earlier implementation passed `&sps`/`&pps` as `inout`
    /// into a helper that itself read `self.sps`/`self.pps` while rebuilding the format description -
    /// an overlapping-exclusive-access Swift runtime trap (SIGABRT) the very first time a SECOND,
    /// different CONFIG frame arrived (exactly what happens when Android recreates its encoder for
    /// an orientation change). A regression here crashes the whole test process, not just this
    /// assertion, which is the loud signal we want.
    func testANewConfigFrameWithDifferentParameterSetsDoesNotCrashAndIsStillTrackedAsReceiving() {
        let decoder = ScreenStreamDecoder()

        let firstConfig = annexBConfigPayload(spsTag: 0xAA, ppsTag: 0xBB)
        decoder.handle(EncodedVideoFrame(type: VideoFrameType.config, streamId: 0, captureTimestampMs: 0, payload: firstConfig))
        XCTAssertTrue(decoder.isReceivingVideo)

        // A second CONFIG frame with different SPS/PPS bytes - e.g. Android's encoder recreated
        // after a rotation - must be handled without crashing, exercising the same rebuild path.
        let secondConfig = annexBConfigPayload(spsTag: 0xCC, ppsTag: 0xDD)
        decoder.handle(EncodedVideoFrame(type: VideoFrameType.config, streamId: 0, captureTimestampMs: 0, payload: secondConfig))
        XCTAssertTrue(decoder.isReceivingVideo)

        // An identical repeat of the same parameter sets must also be a safe no-op rebuild path.
        decoder.handle(EncodedVideoFrame(type: VideoFrameType.config, streamId: 0, captureTimestampMs: 0, payload: secondConfig))
        XCTAssertTrue(decoder.isReceivingVideo)
    }

    /// Builds an Annex-B CONFIG payload: [start code][SPS NAL][start code][PPS NAL]. The bytes
    /// aren't a semantically valid SPS/PPS (CMVideoFormatDescriptionCreateFromH264ParameterSets is
    /// free to reject them and leave formatDescription nil) - this test only needs two distinguishable
    /// "generations" of parameter sets to exercise the change-detection/rebuild path safely.
    private func annexBConfigPayload(spsTag: UInt8, ppsTag: UInt8) -> Data {
        var data = Data()
        let startCode: [UInt8] = [0x00, 0x00, 0x00, 0x01]
        data.append(contentsOf: startCode)
        data.append(contentsOf: [0x67, spsTag, 0x01, 0x02, 0x03]) // NAL type 7 (SPS)
        data.append(contentsOf: startCode)
        data.append(contentsOf: [0x68, ppsTag, 0x04]) // NAL type 8 (PPS)
        return data
    }
}
