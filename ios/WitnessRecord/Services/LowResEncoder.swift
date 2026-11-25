import Foundation
import AVFoundation

/// Produces the small stand-in copy of a chunk that is sent ahead of the
/// full-quality file when the connection is failing.
///
/// The point is arrival, not fidelity: a recognisable 640×480 copy that
/// reaches Drive during the encounter is worth more than a pristine one still
/// sitting on a phone that may be taken. The full file follows and replaces
/// it as soon as the network allows.
enum LowResEncoder {

    /// Marks the stand-in in file names and in the manifest. Spelled out
    /// rather than abbreviated because the audience for these folders is a
    /// contact or an attorney, not a video engineer.
    static let fileNameSuffix = "_lowres"

    struct EncodeError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    static func lowResFileName(for masterFileName: String) -> String {
        let url = URL(fileURLWithPath: masterFileName)
        let stem = url.deletingPathExtension().lastPathComponent
        // Always .mp4: the stand-in exists to be small and widely playable,
        // so it never inherits a ProRes container.
        return "\(stem)\(fileNameSuffix).mp4"
    }

    /// Transcodes `sourceURL` to a small MP4 beside it and returns the result.
    ///
    /// Runs after the chunk is finalised rather than as a second encoder
    /// during capture: capture is the one path that must never be put at risk
    /// to improve delivery.
    static func encode(sourceURL: URL, to destinationURL: URL) async throws {
        let asset = AVURLAsset(url: sourceURL)
        // 640×480 at ~30x less data than a 1080p master, and the widest
        // possible playback compatibility.
        guard let export = AVAssetExportSession(
            asset: asset, presetName: AVAssetExportPreset640x480) else {
            throw EncodeError(message: "No low-res export preset available")
        }
        try? FileManager.default.removeItem(at: destinationURL)

        if #available(iOS 18, *) {
            try await export.export(to: destinationURL, as: .mp4)
        } else {
            export.outputURL = destinationURL
            export.outputFileType = .mp4
            export.shouldOptimizeForNetworkUse = true
            nonisolated(unsafe) let session = export
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                session.exportAsynchronously {
                    if session.status == .completed {
                        continuation.resume()
                    } else {
                        continuation.resume(throwing: session.error ?? EncodeError(
                            message: "Low-res export failed"))
                    }
                }
            }
        }
    }
}
