import CoreImage
import CoreVideo

/// Cuts a centred 16:9 landscape strip out of an upright (portrait) frame, for
/// stealth landscape: the phone stays upright, the file comes out landscape.
///
/// Same colour-safe Core Image setup as `TimestampOverlayRenderer` — working
/// and output colour spaces disabled, so pixels pass through untouched — and
/// the same output-pool pattern, so a crop costs one GPU copy per frame.
final class FrameCropper {
    private let context = CIContext(options: [
        .workingColorSpace: NSNull(),
        .outputColorSpace: NSNull(),
    ])
    private var pool: CVPixelBufferPool?
    private var poolKey = (width: 0, height: 0, format: OSType(0))

    /// The strip's size for a given upright frame: full width, 16:9 tall,
    /// both even (encoders reject odd dimensions).
    static func stripSize(width: Int, height: Int) -> (width: Int, height: Int) {
        let w = width & ~1
        return (w, (w * 9 / 16) & ~1)
    }

    /// nil when the frame isn't upright or a buffer can't be made, so the
    /// caller can tell a crop from a failure.
    func crop(_ pixelBuffer: CVPixelBuffer) -> CVPixelBuffer? {
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        guard height > width else { return nil }
        let (outW, outH) = Self.stripSize(width: width, height: height)
        let format = CVPixelBufferGetPixelFormatType(pixelBuffer)

        if pool == nil || poolKey.width != outW || poolKey.height != outH || poolKey.format != format {
            let attributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: format,
                kCVPixelBufferWidthKey as String: outW,
                kCVPixelBufferHeightKey as String: outH,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:],
            ]
            var created: CVPixelBufferPool?
            CVPixelBufferPoolCreate(nil, nil, attributes as CFDictionary, &created)
            pool = created
            poolKey = (outW, outH, format)
        }
        guard let pool else { return nil }
        var output: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &output)
        guard let output else { return nil }

        // Core Image's origin is bottom-left, but a centred strip is the
        // same distance from either edge, so one offset serves both.
        let y0 = CGFloat((height - outH) / 2)
        let strip = CIImage(cvPixelBuffer: pixelBuffer)
            .cropped(to: CGRect(x: 0, y: y0, width: CGFloat(outW), height: CGFloat(outH)))
            .transformed(by: CGAffineTransform(translationX: 0, y: -y0))
        context.render(strip, to: output)
        return output
    }
}
