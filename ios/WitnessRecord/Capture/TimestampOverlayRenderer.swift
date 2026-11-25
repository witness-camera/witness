import Foundation
import CoreImage
import CoreLocation
import CoreMedia
import UIKit

/// Burns a visible timestamp and GPS coordinate into video frames, on the
/// capture data queue, before they reach a writer or the viewfinder.
///
/// Deliberately kept separate from `AVVideoColorPropertiesKey`-sensitive
/// territory: the `CIContext` here has both working and output color space
/// disabled (`kCIContextWorkingColorSpace`/`kCIContextOutputColorSpace` set
/// to `NSNull`), which keeps Core Image passing pixel values straight
/// through untouched except where the overlay itself is drawn. Without that,
/// Core Image's default color management would reinterpret the buffer under
/// a standard gamma curve on the way through — exactly the kind of silent
/// "un-flattening" `ChunkWriter` already goes out of its way to avoid for
/// Apple Log footage, just arrived at by a different, easier-to-miss route.
final class TimestampOverlayRenderer {
    private let context = CIContext(options: [
        .workingColorSpace: NSNull(),
        .outputColorSpace: NSNull(),
    ])
    private var pool: CVPixelBufferPool?
    private var poolWidth = 0
    private var poolHeight = 0
    private var poolFormat: OSType = 0

    // Re-rendering the text glyphs is the expensive part; the composite step
    // onto each incoming frame is a cheap GPU blend. The on-screen text only
    // needs to change once a second, so only the glyph image is cached and
    // invalidated — not redrawn on every one of 30-60 frames that share it.
    private var cachedText: String?
    private var cachedSize: CGSize?
    private var cachedOverlay: CIImage?

    /// Renders `text` into the bottom-left corner of `pixelBuffer` and
    /// returns a new pixel buffer with it burned in. Returns nil (rather
    /// than the original buffer) on any failure, so a caller can tell "this
    /// didn't work" apart from "here is the composited frame" without an
    /// extra flag — a silent no-op here would otherwise record an
    /// un-watermarked frame while believing it had succeeded.
    func render(_ pixelBuffer: CVPixelBuffer, text: String) -> CVPixelBuffer? {
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let format = CVPixelBufferGetPixelFormatType(pixelBuffer)
        let size = CGSize(width: width, height: height)

        if cachedText != text || cachedSize != size {
            cachedOverlay = Self.makeTextOverlay(text: text, canvasSize: size)
            cachedText = text
            cachedSize = size
        }
        guard let overlay = cachedOverlay else { return nil }

        if pool == nil || poolWidth != width || poolHeight != height || poolFormat != format {
            pool = Self.makePool(width: width, height: height, format: format)
            poolWidth = width
            poolHeight = height
            poolFormat = format
        }
        guard let pool else { return nil }

        var outputBuffer: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &outputBuffer)
        guard let output = outputBuffer else { return nil }

        let base = CIImage(cvPixelBuffer: pixelBuffer)
        let composited = overlay.composited(over: base)
        context.render(composited, to: output)
        return output
    }

    /// Formats the on-screen text the way a dashcam's own overlay reads:
    /// absolute local time first (what anyone glancing at the video wants),
    /// UTC alongside it (what a chain-of-custody comparison actually needs),
    /// and coordinates only when a location is actually available — an
    /// overlay claiming a location it doesn't have would undermine the
    /// exact provenance this feature exists to show.
    static func overlayText(date: Date, location: CLLocation?, branded: Bool = false) -> String {
        let body = stampText(date: date, location: location)
        return branded ? body + "\n" + String(localized: "Recorded with Witness") : body
    }

    private static func stampText(date: Date, location: CLLocation?) -> String {
        let local = Self.localFormatter.string(from: date)
        let utc = Self.utcFormatter.string(from: date)
        guard let location else { return "\(local)\nUTC \(utc)" }
        let lat = String(format: "%.5f°%@", abs(location.coordinate.latitude),
                         location.coordinate.latitude >= 0 ? "N" : "S")
        let lon = String(format: "%.5f°%@", abs(location.coordinate.longitude),
                         location.coordinate.longitude >= 0 ? "E" : "W")
        return "\(local)  UTC \(utc)\n\(lat) \(lon)"
    }

    private static let localFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()

    private static let utcFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        f.timeZone = TimeZone(identifier: "UTC")
        return f
    }()

    /// Draws the overlay text, white on a translucent black backing bar, in
    /// UIKit — plain, legible, and sized relative to the frame so it reads
    /// the same way at 1080p and at 4K rather than becoming illegibly small
    /// or comically large as resolution changes.
    private static func makeTextOverlay(text: String, canvasSize: CGSize) -> CIImage? {
        // Sized from the short edge so it reads the same in portrait and
        // landscape, then shrunk further if it would still run wider than
        // the frame — the earlier single long line did exactly that.
        let shortEdge = min(canvasSize.width, canvasSize.height)
        var fontSize = max(14, shortEdge * 0.026)

        func layout(_ size: CGFloat) -> (NSAttributedString, CGSize) {
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.monospacedSystemFont(ofSize: size, weight: .medium),
                .foregroundColor: UIColor.white,
            ]
            let attributed = NSAttributedString(string: text, attributes: attributes)
            return (attributed, attributed.size())
        }

        var (attributed, textSize) = layout(fontSize)
        let maxBarWidth = canvasSize.width * 0.9
        while textSize.width + fontSize * 0.9 > maxBarWidth, fontSize > 10 {
            fontSize -= 1
            (attributed, textSize) = layout(fontSize)
        }

        let margin = fontSize * 1.0
        let padding = fontSize * 0.5
        let barSize = CGSize(width: ceil(textSize.width) + padding * 2,
                             height: ceil(textSize.height) + padding * 2)

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: canvasSize, format: format)
        let image = renderer.image { _ in
            let barOrigin = CGPoint(x: margin, y: canvasSize.height - margin - barSize.height)
            let barRect = CGRect(origin: barOrigin, size: barSize)
            UIColor.black.withAlphaComponent(0.4).setFill()
            UIBezierPath(roundedRect: barRect, cornerRadius: padding * 0.9).fill()
            attributed.draw(at: CGPoint(x: barOrigin.x + padding, y: barOrigin.y + padding))
        }
        // No flip: CIImage(cgImage:) already maps the image's top-left
        // origin into Core Image's bottom-left space. Flipping it again is
        // what mirrored the text and moved the bar to the top of the frame.
        guard let cgImage = image.cgImage else { return nil }
        return CIImage(cgImage: cgImage)
    }

    private static func makePool(width: Int, height: Int, format: OSType) -> CVPixelBufferPool? {
        let pixelBufferAttributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: format,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
        ]
        var pool: CVPixelBufferPool?
        CVPixelBufferPoolCreate(nil, nil, pixelBufferAttributes as CFDictionary, &pool)
        return pool
    }
}
