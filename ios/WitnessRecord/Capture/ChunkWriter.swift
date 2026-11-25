import Foundation
import AVFoundation
import CoreLocation
import VideoToolbox

extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

/// Wraps one AVAssetWriter writing a single ~15 second .mp4 chunk.
/// Created and driven entirely on the capture data queue.
final class ChunkWriter {
    let outputURL: URL
    let startedAt: Date
    private(set) var firstVideoPTS: CMTime?
    private(set) var lastPTS: CMTime = .invalid

    private let writer: AVAssetWriter
    private let videoInput: AVAssetWriterInput
    private let audioInput: AVAssetWriterInput

    /// Video frames this writer had to skip because its input was still busy
    /// with the previous one — i.e. the encoder could not keep up. Zero in
    /// normal use; a non-zero count is the signal that a configuration is
    /// asking more of the hardware than it can give, and is reported in the
    /// diagnostics readout.
    private(set) var refusedVideoFrames = 0

    /// False once the writer has failed outright (a media-services reset
    /// mid-recording, most often). A failed writer silently accepts nothing
    /// more, so callers that have a fallback need to be able to ask.
    var isHealthy: Bool { writer.status != .failed && writer.status != .cancelled }

    /// Seconds of video written so far, used by the controller to decide
    /// when to rotate to the next chunk.
    var elapsedSeconds: Double {
        guard let first = firstVideoPTS, lastPTS.isValid else { return 0 }
        return CMTimeGetSeconds(CMTimeSubtract(lastPTS, first))
    }

    init(outputURL: URL, location: CLLocation?, width: Int = 1920, height: Int = 1080) throws {
        self.outputURL = outputURL
        self.startedAt = Date()

        let codec = AppSettings.videoCodec
        let isLog = AppSettings.logRecordingEnabled
        let fps = AppSettings.videoFrameRate.rawValue

        // ProRes is only legal in a QuickTime container; MP4 would be rejected.
        writer = try AVAssetWriter(outputURL: outputURL,
                                   fileType: codec.isProRes ? .mov : .mp4)
        // Only meaningful for a file destined to be streamed over the network,
        // which a local-only ProRes master never is.
        writer.shouldOptimizeForNetworkUse = !codec.isProRes
        writer.metadata = Self.metadataItems(location: location, date: startedAt)

        // Encode at the source camera's native dimensions so front and back
        // (which can differ in dual-camera mode) are each stored undistorted.
        var videoSettings: [String: Any] = [
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
        ]

        switch codec {
        case .proRes422LT, .proRes422HQ:
            // ProRes is fixed-quality: it derives its own data rate from the
            // frame size and has no bit-rate knob, so no compression
            // properties are supplied at all.
            videoSettings[AVVideoCodecKey] = codec == .proRes422HQ
                ? AVVideoCodecType.proRes422HQ
                : AVVideoCodecType.proRes422LT

        case .h264, .hevc:
            // Bit rate scales with pixel count off an 8 Mbps @ 1080p30
            // baseline, and again with frame rate, so 4K60 isn't starved into
            // mush by a cap that was only ever sized for 1080p.
            let pixels = max(1, width * height)
            let scale = (Double(pixels) / (1920.0 * 1080.0)) * (Double(fps) / 30.0)
            // Log keeps more of the sensor's range and is graded later, so it
            // is given extra headroom rather than being compressed like a
            // final deliverable.
            let bitRate = Int(scale * 8_000_000 * (isLog ? 1.6 : 1.0))
                .clamped(to: 2_000_000...120_000_000)

            var compression: [String: Any] = [
                AVVideoAverageBitRateKey: bitRate,
                AVVideoExpectedSourceFrameRateKey: fps,
            ]
            if codec == .hevc {
                videoSettings[AVVideoCodecKey] = AVVideoCodecType.hevc
                compression[AVVideoProfileLevelKey] = kVTProfileLevel_HEVC_Main10_AutoLevel
            } else {
                videoSettings[AVVideoCodecKey] = AVVideoCodecType.h264
                compression[AVVideoProfileLevelKey] = AVVideoProfileLevelH264HighAutoLevel
            }
            videoSettings[AVVideoCompressionPropertiesKey] = compression
        }

        // Deliberately no AVVideoColorPropertiesKey anywhere above. Apple Log
        // is signalled by its own `kCVImageBufferLogTransferFunctionKey`
        // attachment on the source buffers, not by the standard transfer
        // function — and naming any standard color properties makes the writer
        // *convert* into them, re-applying a display curve and silently
        // un-flattening the very footage Log exists to preserve. Leaving them
        // unset passes the camera's own tagging through untouched.
        videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
        videoInput.expectsMediaDataInRealTime = true

        let audioSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 96_000,
        ]
        audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: audioSettings)
        audioInput.expectsMediaDataInRealTime = true

        guard writer.canAdd(videoInput), writer.canAdd(audioInput) else {
            throw NSError(domain: "WitnessRecord", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "AVAssetWriter rejected inputs"])
        }
        writer.add(videoInput)
        writer.add(audioInput)
    }

    /// Embeds GPS + UTC timestamp in the container metadata (no visible
    /// overlay; the frame stays clean).
    static func metadataItems(location: CLLocation?, date: Date) -> [AVMetadataItem] {
        var items: [AVMetadataItem] = []

        let creation = AVMutableMetadataItem()
        creation.identifier = .commonIdentifierCreationDate
        creation.value = ISO8601DateFormatter().string(from: date) as NSString
        items.append(creation)

        if let loc = location {
            let iso6709 = String(format: "%+09.5f%+010.5f/",
                                 loc.coordinate.latitude, loc.coordinate.longitude)
            let gps = AVMutableMetadataItem()
            gps.identifier = .commonIdentifierLocation
            gps.value = iso6709 as NSString
            items.append(gps)
        }
        return items
    }

    func append(_ sampleBuffer: CMSampleBuffer, isVideo: Bool) {
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)

        if writer.status == .unknown {
            // Only start the timeline on a video frame so the first frame
            // of every chunk lands exactly at time zero.
            guard isVideo else { return }
            guard writer.startWriting() else { return }
            writer.startSession(atSourceTime: pts)
            firstVideoPTS = pts
        }

        guard writer.status == .writing else { return }
        let input = isVideo ? videoInput : audioInput
        guard input.isReadyForMoreMediaData else {
            if isVideo { refusedVideoFrames += 1 }
            return
        }
        input.append(sampleBuffer)
        if isVideo { lastPTS = pts }
    }

    /// Finalizes the file. Calls back on an arbitrary queue with the end
    /// wall-clock date (or nil if the chunk was empty/failed and was removed).
    func finish(completion: @escaping (Date?) -> Void) {
        guard writer.status == .writing else {
            // Never started (.unknown) or already terminal — cancelWriting
            // is only valid while writing, so just remove the stub file.
            try? FileManager.default.removeItem(at: outputURL)
            completion(nil)
            return
        }
        videoInput.markAsFinished()
        audioInput.markAsFinished()
        let endedAt = Date()
        writer.finishWriting { [writer, outputURL] in
            if writer.status == .completed {
                completion(endedAt)
            } else {
                try? FileManager.default.removeItem(at: outputURL)
                completion(nil)
            }
        }
    }
}
