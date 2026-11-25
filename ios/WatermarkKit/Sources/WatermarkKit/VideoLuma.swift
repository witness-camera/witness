import Foundation
import AVFoundation
import Accelerate
import CoreVideo

/// Reading a video's per-frame brightness, and shifting a frame's brightness
/// on the way to the encoder. The two halves of the channel the watermark
/// travels through.
public enum VideoLuma {

    // MARK: - Embedding

    /// Adds `offset` (in 8-bit luma levels) to every pixel of a frame.
    ///
    /// Applied through a 256-entry lookup table rather than per-pixel
    /// arithmetic: the table is built once per frame and vImage applies it
    /// across millions of pixels in one pass, which is what makes this
    /// affordable at 4K. It also clamps for free — a naive add would wrap
    /// black to white on any pixel already at the end of the range.
    ///
    /// Only the luma plane is touched, so colour is unchanged; the frame gets
    /// very slightly lighter or darker and nothing else moves.
    @discardableResult
    public static func apply(offset: Double, to pixelBuffer: CVPixelBuffer) -> Bool {
        let format = CVPixelBufferGetPixelFormatType(pixelBuffer)
        // 8-bit bi-planar only. 10-bit Log and ProRes are deliberately out of
        // scope for now: they are graded before release, which is the one
        // transformation this watermark does not survive, so embedding into
        // them would promise a recovery that will not happen.
        guard format == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
                || format == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange else {
            return false
        }
        // A shift small enough to round to nothing would silently do nothing,
        // and the caller should not be told it succeeded.
        let shift = Int(offset.rounded())
        guard shift != 0 else { return true }

        guard CVPixelBufferLockBaseAddress(pixelBuffer, []) == kCVReturnSuccess else { return false }
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }
        guard let base = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) else { return false }

        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
        let stride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)

        var table = [UInt8](repeating: 0, count: 256)
        for value in 0..<256 {
            table[value] = UInt8(clamping: value + shift)
        }

        var buffer = vImage_Buffer(data: base, height: vImagePixelCount(height),
                                   width: vImagePixelCount(width), rowBytes: stride)
        let status = table.withUnsafeBufferPointer { pointer in
            vImageTableLookUp_Planar8(&buffer, &buffer, pointer.baseAddress!, vImage_Flags(kvImageDoNotTile))
        }
        return status == kvImageNoError
    }

    // MARK: - Reading

    /// Mean luma of every frame in a video, in presentation order.
    ///
    /// The watermark rides on this one number per frame, so this is the only
    /// thing the verifier needs from a file — no decoding to RGB, no frame
    /// buffers held, which is what lets it run over long footage cheaply.
    public static func meanLumaPerFrame(of url: URL) async throws -> [Double] {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else {
            throw WatermarkError.noVideoTrack
        }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [
                kCVPixelBufferPixelFormatTypeKey as String:
                    kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            ])
        output.alwaysCopiesSampleData = false
        reader.add(output)
        guard reader.startReading() else {
            throw WatermarkError.unreadable(reader.error?.localizedDescription ?? "unknown")
        }

        var means = [Double]()
        while let sample = output.copyNextSampleBuffer() {
            defer { CMSampleBufferInvalidate(sample) }
            guard let pixels = CMSampleBufferGetImageBuffer(sample) else { continue }
            if let mean = meanLuma(of: pixels) { means.append(mean) }
        }
        if reader.status == .failed {
            throw WatermarkError.unreadable(reader.error?.localizedDescription ?? "unknown")
        }
        return means
    }

    /// Mean of the luma plane. Uses the row stride rather than the width, so
    /// padding bytes on each row are excluded — including them would add a
    /// constant that varies with resolution and quietly bias every frame.
    public static func meanLuma(of pixelBuffer: CVPixelBuffer) -> Double? {
        guard CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) else { return nil }

        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
        let stride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        guard width > 0, height > 0 else { return nil }

        let pointer = base.assumingMemoryBound(to: UInt8.self)
        var total = 0.0
        for row in 0..<height {
            let start = pointer + row * stride
            var rowSum: UInt32 = 0
            // Summed per row in 32-bit and accumulated in double: a single
            // 32-bit accumulator overflows on a 4K frame.
            for column in 0..<width { rowSum &+= UInt32(start[column]) }
            total += Double(rowSum)
        }
        return total / Double(width * height)
    }
}

public enum WatermarkError: Error, LocalizedError {
    case noVideoTrack
    case unreadable(String)
    case tooShort(frames: Int, needed: Int)

    public var errorDescription: String? {
        switch self {
        case .noVideoTrack:
            return "That file has no video track."
        case .unreadable(let detail):
            return "Could not read the video: \(detail)"
        case .tooShort(let frames, let needed):
            return "Too short to carry a watermark: \(frames) frames, needs at least \(needed)."
        }
    }
}
