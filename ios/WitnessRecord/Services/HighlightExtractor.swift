import AVFoundation

/// Produces a shorter "highlights" cut of a session's rear-camera video: the
/// stretches where someone is actually talking, at ordinary conversational
/// volume, with the quiet dead air in between trimmed out.
///
/// Deliberately tuned to catch normal speech, not just shouting or sudden
/// loud spikes — most of what an encounter recording needs to keep is
/// ordinary conversation, and a threshold that only fired on loud moments
/// would silently drop everything that matters most.
enum HighlightExtractor {
    /// How long each analysis window is. Short enough that a highlight
    /// segment's boundaries land close to where speech actually starts and
    /// stops, long enough that a window's level is a meaningful average
    /// rather than noise from a single sample.
    private static let windowDuration: TimeInterval = 0.5
    /// How much louder than this recording's own quiet stretches counts as
    /// speech. A fixed offset above an adaptive floor, not an absolute
    /// volume, because mic gain and distance from the speaker both shift
    /// what "quiet" and "talking" measure as from one recording to the next.
    private static let activeMarginDB: Float = 8
    /// Kept on each side of a detected active stretch so a word right at the
    /// edge of a window isn't clipped.
    private static let padding: TimeInterval = 1.0
    /// Two active stretches closer together than this are joined into one
    /// segment instead of producing a jump-cut a couple of seconds later.
    private static let mergeGap: TimeInterval = 2.5
    /// Below this, a stretch is almost certainly a stray click or a single
    /// loud frame, not real speech worth keeping as its own segment.
    private static let minimumSegmentDuration: TimeInterval = 1.0

    /// Thrown when nothing in the recording ever rose above its own ambient
    /// floor — silence throughout, or no audio track at all. Not a failure:
    /// there is simply no shorter cut worth producing.
    struct NoActivity: Error {}

    /// `marks` are seconds into the recording that the person flagged; the
    /// cut always keeps a few seconds either side of each, talking or not.
    static func build(from sourceURL: URL, sessionID: UUID, in dir: URL,
                      marks: [TimeInterval] = []) async throws -> URL {
        let asset = AVURLAsset(url: sourceURL)
        let segments = try await activeSegments(in: asset, marks: marks)
        guard !segments.isEmpty else { throw NoActivity() }

        guard let srcVideo = try await asset.loadTracks(withMediaType: .video).first else {
            throw NSError(domain: "WitnessRecord", code: 13,
                          userInfo: [NSLocalizedDescriptionKey: "Missing a video track to extract highlights from"])
        }
        let srcAudio = try await asset.loadTracks(withMediaType: .audio).first
        let preferredTransform = try await srcVideo.load(.preferredTransform)

        let composition = AVMutableComposition()
        let videoTrack = composition.addMutableTrack(
            withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)
        let audioTrack = composition.addMutableTrack(
            withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
        videoTrack?.preferredTransform = preferredTransform

        var cursor = CMTime.zero
        for segment in segments {
            try videoTrack?.insertTimeRange(segment, of: srcVideo, at: cursor)
            if let srcAudio {
                try? audioTrack?.insertTimeRange(segment, of: srcAudio, at: cursor)
            }
            cursor = CMTimeAdd(cursor, segment.duration)
        }

        // Same partial-then-move pattern as every other export in this
        // pipeline: the final file's presence is what means the export
        // actually completed, so nothing downstream can pick up a truncated
        // one left behind by the app being suspended mid-export.
        let outputURL = dir.appendingPathComponent(Self.fileName(sessionID: sessionID))
        let partialURL = outputURL.appendingPathExtension("partial")
        try? FileManager.default.removeItem(at: outputURL)
        try? FileManager.default.removeItem(at: partialURL)

        guard let export = AVAssetExportSession(
            asset: composition, presetName: AVAssetExportPresetHighestQuality) else {
            throw NSError(domain: "WitnessRecord", code: 14,
                          userInfo: [NSLocalizedDescriptionKey: "Could not create export session"])
        }

        if #available(iOS 18, *) {
            try await export.export(to: partialURL, as: .mp4)
        } else {
            export.outputURL = partialURL
            export.outputFileType = .mp4
            nonisolated(unsafe) let session = export
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                session.exportAsynchronously {
                    if session.status == .completed {
                        continuation.resume()
                    } else {
                        continuation.resume(throwing: session.error ?? NSError(
                            domain: "WitnessRecord", code: 15,
                            userInfo: [NSLocalizedDescriptionKey: "Export failed"]))
                    }
                }
            }
        }

        try FileManager.default.moveItem(at: partialURL, to: outputURL)
        return outputURL
    }

    private static func fileName(sessionID: UUID) -> String {
        "session_\(sessionID.uuidString)_highlights.mp4"
    }

    /// Reads the audio track sample by sample, buckets it into windows,
    /// scores each window's loudness in decibels, and keeps whichever
    /// windows sit clearly above this recording's own quiet stretches.
    private static func activeSegments(in asset: AVURLAsset, marks: [TimeInterval]) async throws -> [CMTimeRange] {
        guard let audioTrack = try await asset.loadTracks(withMediaType: .audio).first else {
            return []
        }
        let duration = try await asset.load(.duration)

        var sampleRate: Double = 44100
        if let formatDescriptions = try? await audioTrack.load(.formatDescriptions),
           let asbd = formatDescriptions.first.flatMap({
               CMAudioFormatDescriptionGetStreamBasicDescription($0)
           }) {
            sampleRate = asbd.pointee.mSampleRate
        }
        let samplesPerWindow = max(1, Int(sampleRate * windowDuration))

        let reader = try AVAssetReader(asset: asset)
        let outputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
            AVNumberOfChannelsKey: 1,
        ]
        let output = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: outputSettings)
        reader.add(output)
        guard reader.startReading() else { return [] }

        var windowLevels: [Float] = []
        var pendingSamples: [Int16] = []
        pendingSamples.reserveCapacity(samplesPerWindow)

        while let sampleBuffer = output.copyNextSampleBuffer() {
            guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { continue }
            let length = CMBlockBufferGetDataLength(blockBuffer)
            let sampleCount = length / MemoryLayout<Int16>.size
            guard sampleCount > 0 else { continue }
            var samples = [Int16](repeating: 0, count: sampleCount)
            let status = samples.withUnsafeMutableBytes { rawBuffer -> OSStatus in
                CMBlockBufferCopyDataBytes(
                    blockBuffer, atOffset: 0, dataLength: length, destination: rawBuffer.baseAddress!)
            }
            guard status == noErr else { continue }

            for sample in samples {
                pendingSamples.append(sample)
                if pendingSamples.count >= samplesPerWindow {
                    windowLevels.append(dBFS(of: pendingSamples))
                    pendingSamples.removeAll(keepingCapacity: true)
                }
            }
        }
        if !pendingSamples.isEmpty {
            windowLevels.append(dBFS(of: pendingSamples))
        }
        guard !windowLevels.isEmpty else { return [] }

        // The 20th-percentile level stands in for "this recording's own
        // ambient quiet" — adaptive to this specific mic gain and
        // background noise, rather than a fixed number that would be too
        // strict in a loud room and too loose in a silent one.
        let floor = percentile(windowLevels.sorted(), 0.2)
        let threshold = floor + activeMarginDB
        let active = windowLevels.map { $0 >= threshold }

        var ranges: [(start: TimeInterval, end: TimeInterval)] = []
        var i = 0
        while i < active.count {
            if active[i] {
                let start = Double(i) * windowDuration
                var j = i
                while j < active.count && active[j] { j += 1 }
                ranges.append((start, Double(j) * windowDuration))
                i = j
            } else {
                i += 1
            }
        }
        for mark in marks where mark >= 0 && mark <= duration.seconds {
            ranges.append((max(0, mark - 8), mark + 5))
        }
        ranges.sort { $0.start < $1.start }
        guard !ranges.isEmpty else { return [] }

        let clampedDuration = duration.seconds
        let padded = ranges.map {
            (start: max(0, $0.start - padding), end: min(clampedDuration, $0.end + padding))
        }

        var merged: [(start: TimeInterval, end: TimeInterval)] = [padded[0]]
        for range in padded.dropFirst() {
            if range.start - merged[merged.count - 1].end <= mergeGap {
                merged[merged.count - 1].end = max(merged[merged.count - 1].end, range.end)
            } else {
                merged.append(range)
            }
        }

        return merged
            .filter { $0.end - $0.start >= minimumSegmentDuration }
            .map {
                CMTimeRange(
                    start: CMTime(seconds: $0.start, preferredTimescale: 600),
                    end: CMTime(seconds: $0.end, preferredTimescale: 600))
            }
    }

    private static func dBFS(of samples: [Int16]) -> Float {
        guard !samples.isEmpty else { return -120 }
        var sumSquares: Double = 0
        for sample in samples {
            let normalized = Double(sample) / Double(Int16.max)
            sumSquares += normalized * normalized
        }
        let rms = sqrt(sumSquares / Double(samples.count))
        guard rms > 0 else { return -120 }
        return Float(20 * log10(rms))
    }

    private static func percentile(_ sorted: [Float], _ p: Double) -> Float {
        guard !sorted.isEmpty else { return -120 }
        let index = Int(Double(sorted.count - 1) * p)
        return sorted[index]
    }
}
