import AVFoundation
import UIKit

/// Renders a crew edit as one video: each stretch of the timeline taken from
/// the angle chosen for it, with the sound of that angle.
///
/// Built from the crew previews, which are what this phone has of the other
/// members' footage, so the result is preview quality. Phones film in
/// different orientations and sizes; every angle is fitted, whole, into one
/// 16:9 frame, with black where nobody was filming.
enum CrewEditExport {
    struct Piece {
        /// Nil for a stretch no camera covered.
        let url: URL?
        /// Where in that angle's video the stretch starts, in seconds.
        let sourceStart: Double
        let duration: Double
    }

    struct ExportError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private static let renderSize = CGSize(width: 1280, height: 720)
    private static let timescale: CMTimeScale = 600

    static func export(_ pieces: [Piece], title: String) async throws -> URL {
        let composition = AVMutableComposition()
        guard let videoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
        else { throw ExportError(message: String(localized: "Couldn't start the export.")) }

        let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: videoTrack)
        var assets: [URL: AVURLAsset] = [:]
        var cursor = CMTime.zero
        var anyVideo = false

        func gap(_ duration: CMTime) {
            let range = CMTimeRange(start: cursor, duration: duration)
            videoTrack.insertEmptyTimeRange(range)
            audioTrack.insertEmptyTimeRange(range)
            layer.setOpacity(0, at: cursor)
            cursor = cursor + duration
        }

        for piece in pieces {
            let wanted = CMTime(seconds: piece.duration, preferredTimescale: timescale)
            guard wanted > .zero else { continue }
            guard let url = piece.url else { gap(wanted); continue }
            let asset = assets[url] ?? AVURLAsset(url: url)
            assets[url] = asset
            guard let source = try await asset.loadTracks(withMediaType: .video).first else { gap(wanted); continue }

            // A preview can end a little before the recording it stands for.
            let start = CMTime(seconds: piece.sourceStart, preferredTimescale: timescale)
            let available = try await asset.load(.duration) - start
            let used = CMTimeMinimum(wanted, CMTimeMaximum(available, .zero))
            if used > .zero {
                let range = CMTimeRange(start: start, duration: used)
                try videoTrack.insertTimeRange(range, of: source, at: cursor)
                if let sound = try await asset.loadTracks(withMediaType: .audio).first {
                    try audioTrack.insertTimeRange(range, of: sound, at: cursor)
                } else {
                    audioTrack.insertEmptyTimeRange(CMTimeRange(start: cursor, duration: used))
                }
                let (transform, size) = try await source.load(.preferredTransform, .naturalSize)
                layer.setTransform(fitted(size, transform), at: cursor)
                layer.setOpacity(1, at: cursor)
                cursor = cursor + used
                anyVideo = true
            }
            if used < wanted { gap(wanted - used) }
        }
        guard anyVideo, cursor > .zero else {
            throw ExportError(message: String(localized: "There's no footage in this part of the timeline yet."))
        }
        // Held until the export ends: a track is only readable while its
        // asset is alive.
        let silence = try await fillWithSilence(audioTrack)

        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: .zero, duration: cursor)
        instruction.layerInstructions = [layer]
        instruction.backgroundColor = UIColor.black.cgColor
        let video = AVMutableVideoComposition()
        video.renderSize = renderSize
        video.frameDuration = CMTime(value: 1, timescale: 30)
        video.instructions = [instruction]

        guard let session = AVAssetExportSession(asset: composition, presetName: AVAssetExportPreset1280x720) else {
            throw ExportError(message: String(localized: "Couldn't start the export."))
        }
        session.videoComposition = video
        session.shouldOptimizeForNetworkUse = true
        let safe = title.components(separatedBy: CharacterSet(charactersIn: "/\\:?%*|\"<>")).joined()
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(safe.isEmpty ? "Crew" : safe) – Witness edit.mp4")
        try? FileManager.default.removeItem(at: destination)
        try await session.export(to: destination, as: .mp4)
        withExtendedLifetime((assets, silence)) {}
        return destination
    }

    /// Replaces the audio track's empty stretches with real silence. Left
    /// empty, the exporter writes a gap as a jump in the file rather than as
    /// silence, and players disagree about it: some stop the sound there,
    /// others play straight through and drift out of sync with the picture.
    private static func fillWithSilence(_ track: AVMutableCompositionTrack) async throws -> AVURLAsset? {
        let gaps = track.segments.filter(\.isEmpty).map(\.timeMapping.target)
        guard !gaps.isEmpty else { return nil }
        let (asset, silence) = try await silenceTrack()
        let length = try await silence.load(.timeRange).duration
        for gap in gaps {
            track.removeTimeRange(gap)
            var at = gap.start
            var remaining = gap.duration
            while remaining > .zero {
                let chunk = CMTimeMinimum(remaining, length)
                try track.insertTimeRange(CMTimeRange(start: .zero, duration: chunk), of: silence, at: at)
                at = at + chunk
                remaining = remaining - chunk
            }
        }
        return asset
    }

    /// Ten seconds of silent audio, made once and reused in a loop.
    private static func silenceTrack() async throws -> (AVURLAsset, AVAssetTrack) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("witness-silence-10s.caf")
        if !FileManager.default.fileExists(atPath: url.path) {
            guard let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1),
                  let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 441_000),
                  let samples = buffer.floatChannelData?[0] else {
                throw ExportError(message: String(localized: "Couldn't start the export."))
            }
            buffer.frameLength = buffer.frameCapacity
            samples.update(repeating: 0, count: Int(buffer.frameLength))
            let file = try AVAudioFile(forWriting: url, settings: format.settings)
            try file.write(from: buffer)
        }
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .audio).first else {
            throw ExportError(message: String(localized: "Couldn't start the export."))
        }
        return (asset, track)
    }

    /// Scales and centres a video, upright, inside the frame without
    /// cropping, whatever orientation the phone filmed in.
    private static func fitted(_ size: CGSize, _ transform: CGAffineTransform) -> CGAffineTransform {
        let shown = CGRect(origin: .zero, size: size).applying(transform)
        let scale = min(renderSize.width / abs(shown.width), renderSize.height / abs(shown.height))
        let width = abs(shown.width) * scale
        let height = abs(shown.height) * scale
        return transform
            .concatenating(CGAffineTransform(translationX: -shown.minX, y: -shown.minY))
            .concatenating(CGAffineTransform(scaleX: scale, y: scale))
            .concatenating(CGAffineTransform(translationX: (renderSize.width - width) / 2,
                                             y: (renderSize.height - height) / 2))
    }
}
