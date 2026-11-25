import XCTest
import AVFoundation
import CryptoKit
@testable import WatermarkKit

/// The test the whole design turns on: does the payload survive a real
/// H.264 encoder at the bit rate this app actually records?
///
/// Everything else is arithmetic that can be reasoned about. Compression
/// cannot — an encoder's rate control decides frame by frame what to discard,
/// and a sub-perceptual brightness shift is exactly the kind of thing it is
/// designed to throw away. So this runs actual footage through
/// AVAssetWriter and reads it back off disk.
final class EncoderRoundTripTests: XCTestCase {
    let key = SymmetricKey(data: Data(repeating: 0xAB, count: 32))
    let accountID = "70001234567"

    /// 720p at the same bits-per-pixel as the app's 1080p default (8 Mbps at
    /// 1920×1080×30 ≈ 0.129 bpp), so compression is as aggressive here as in
    /// a real recording while the test stays quick.
    let width = 1280
    let height = 720
    let fps: Int32 = 30
    let frameCount = 450          // 15 seconds — one chunk
    var bitRate: Int { Int(0.129 * Double(width * height) * Double(fps)) }

    // MARK: - Synthetic footage

    /// A moving scene rather than a still: pans, a bright moving object, and
    /// per-pixel noise. A static frame would compress to almost nothing and
    /// preserve the watermark far too easily, which would make this test
    /// prove nothing.
    func renderFrame(_ index: Int, into buffer: CVPixelBuffer) {
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }

        let yPlane = CVPixelBufferGetBaseAddressOfPlane(buffer, 0)!.assumingMemoryBound(to: UInt8.self)
        let yStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)
        let t = Double(index)
        var seed = UInt64(index &+ 1) &* 0x9E37_79B9_7F4A_7C15

        for row in 0..<height {
            let line = yPlane + row * yStride
            for column in 0..<width {
                // Slow diagonal pan.
                let gradient = 70.0 + 50.0 * sin((Double(column) + t * 2.0) / 140.0)
                    + 30.0 * cos((Double(row) - t * 1.5) / 190.0)
                // A bright object crossing the frame.
                let objectX = 200.0 + t * 1.9
                let objectY = 360.0 + 120.0 * sin(t / 40.0)
                let distance = ((Double(column) - objectX) * (Double(column) - objectX)
                                + (Double(row) - objectY) * (Double(row) - objectY)).squareRoot()
                let object = distance < 90 ? 70.0 * (1 - distance / 90) : 0
                // Sensor-like grain, so the encoder has real residual to spend bits on.
                seed = (seed &* 6364136223846793005) &+ 1442695040888963407
                let grain = Double((seed >> 33) % 13) - 6.0

                line[column] = UInt8(clamping: Int(gradient + object + grain))
            }
        }
        // Flat chroma: the watermark never touches it, and it keeps the test
        // focused on what luma does.
        let uvPlane = CVPixelBufferGetBaseAddressOfPlane(buffer, 1)!.assumingMemoryBound(to: UInt8.self)
        let uvStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 1)
        for row in 0..<(height / 2) {
            let line = uvPlane + row * uvStride
            for i in 0..<width { line[i] = 128 }
        }
    }

    /// Writes a watermarked H.264 file and returns its URL.
    func writeVideo(offsets: [Double], codec: AVVideoCodecType = .h264) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("wm-\(UUID().uuidString).mp4")

        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: codec,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: bitRate,
                AVVideoExpectedSourceFrameRateKey: fps,
            ],
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String:
                    kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
            ])
        writer.add(input)
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)

        for index in 0..<frameCount {
            while !input.isReadyForMoreMediaData { usleep(1000) }
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &buffer)
            guard let pixelBuffer = buffer else { continue }
            renderFrame(index, into: pixelBuffer)
            // Exactly the call the capture pipeline will make.
            VideoLuma.apply(offset: offsets[index], to: pixelBuffer)
            adaptor.append(pixelBuffer,
                           withPresentationTime: CMTime(value: CMTimeValue(index), timescale: fps))
        }
        input.markAsFinished()

        let done = XCTestExpectation(description: "write")
        writer.finishWriting { done.fulfill() }
        wait(for: [done], timeout: 120)
        XCTAssertEqual(writer.status, .completed, "writer failed: \(String(describing: writer.error))")
        return url
    }

    // MARK: - Tests

    func testPayloadSurvivesRealH264Encode() async throws {
        let watermark = TemporalWatermark()
        let payload = WatermarkPayload.make(appTransactionID: accountID, key: key, nonce: 0x1234_5678_9A)
        let offsets = watermark.frameOffsets(bits: payload.bits, frameCount: frameCount)

        let url = try writeVideo(offsets: offsets)
        defer { try? FileManager.default.removeItem(at: url) }

        let means = try await VideoLuma.meanLumaPerFrame(of: url)
        XCTAssertGreaterThan(means.count, TemporalWatermark.chipPeriod)

        let (bits, confidence) = watermark.decode(meanLuma: means)
        let recovered = WatermarkPayload(bits: bits)
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
        print("[watermark] H.264 \(width)×\(height) @ \(bitRate / 1000)kbps, "
              + "\(means.count) frames, \(size / 1024)KB, confidence \(String(format: "%.3f", confidence))")

        XCTAssertEqual(bits, payload.bits, "payload did not survive H.264")
        XCTAssertTrue(recovered?.matches(candidateID: accountID, key: key) ?? false)
        XCTAssertFalse(recovered?.matches(candidateID: "70009999999", key: key) ?? true,
                       "verified against the wrong account")
    }

    /// Unwatermarked footage through the same encoder must not produce a
    /// payload that verifies — the false-positive case, where an innocent
    /// account gets attributed.
    func testUnwatermarkedFootageDoesNotVerify() async throws {
        let watermark = TemporalWatermark()
        let url = try writeVideo(offsets: [Double](repeating: 0, count: frameCount))
        defer { try? FileManager.default.removeItem(at: url) }

        let means = try await VideoLuma.meanLumaPerFrame(of: url)
        let (bits, confidence) = watermark.decode(meanLuma: means)
        print("[watermark] clean footage confidence \(String(format: "%.3f", confidence))")
        if let payload = WatermarkPayload(bits: bits) {
            XCTAssertFalse(payload.matches(candidateID: accountID, key: key))
        }
    }

    /// A re-encode is what happens to anything shared: uploaded, recompressed,
    /// downloaded, recompressed again.
    func testSurvivesRecompression() async throws {
        let watermark = TemporalWatermark()
        let payload = WatermarkPayload.make(appTransactionID: accountID, key: key, nonce: 0xAB_CDEF_0123)
        let offsets = watermark.frameOffsets(bits: payload.bits, frameCount: frameCount)

        let original = try writeVideo(offsets: offsets)
        defer { try? FileManager.default.removeItem(at: original) }

        // Re-encode at half the bit rate, the way a social platform would.
        let recompressed = FileManager.default.temporaryDirectory
            .appendingPathComponent("wm-re-\(UUID().uuidString).mp4")
        defer { try? FileManager.default.removeItem(at: recompressed) }
        let export = AVAssetExportSession(asset: AVURLAsset(url: original),
                                          presetName: AVAssetExportPreset960x540)!
        export.outputURL = recompressed
        export.outputFileType = .mp4
        await export.export()
        XCTAssertEqual(export.status, .completed, "re-encode failed: \(String(describing: export.error))")

        let means = try await VideoLuma.meanLumaPerFrame(of: recompressed)
        let (bits, confidence) = watermark.decode(meanLuma: means)
        print("[watermark] after 960×540 re-encode: \(means.count) frames, "
              + "confidence \(String(format: "%.3f", confidence))")
        XCTAssertEqual(bits, payload.bits, "payload did not survive re-encoding")
    }
}
