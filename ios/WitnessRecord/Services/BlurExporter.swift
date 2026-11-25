import AVFoundation
import CoreImage
import CoreImage.CIFilterBuiltins
import Vision

/// Produces a publish-safe copy of a recording with bystanders' faces and
/// vehicle licence plates blurred — in addition to, never instead of, the
/// untouched original, which stays the evidence.
///
/// Everything runs on the device: Apple's Vision face detector for faces, and
/// on-device text recognition for plates (there is no plate detector), keeping
/// only text shaped like a plate — short, letters and digits, wider than tall.
/// Detection is automatic and can miss a face turned away or a plate at a
/// steep angle, which the setting says plainly; this copy is a head start on
/// redaction, not a guarantee.
enum BlurExporter {
    /// Faces are re-detected every few frames and plates less often; boxes
    /// carry over in between, padded, so a moving face stays covered without
    /// running Vision on all thirty frames a second.
    private static let faceEvery = 1
    private static let plateEvery = 3

    static func build(from sourceURL: URL, sessionID: UUID, in dir: URL,
                      name: String = "blurred") async throws -> URL {
        let asset = AVURLAsset(url: sourceURL)
        let state = DetectionState()

        let composition = try await AVMutableVideoComposition.videoComposition(
            with: asset, applyingCIFiltersWithHandler: { request in
                let image = request.sourceImage.clampedToExtent().cropped(to: request.sourceImage.extent)
                let regions = state.regions(for: image)
                request.finish(with: blur(image, regions: regions), context: nil)
            })

        let outputURL = dir.appendingPathComponent("session_\(sessionID.uuidString)_\(name).mp4")
        let partialURL = outputURL.appendingPathExtension("partial")
        try? FileManager.default.removeItem(at: outputURL)
        try? FileManager.default.removeItem(at: partialURL)

        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetHighestQuality) else {
            throw NSError(domain: "WitnessRecord", code: 21,
                          userInfo: [NSLocalizedDescriptionKey: "Could not create export session"])
        }
        export.videoComposition = composition

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
                            domain: "WitnessRecord", code: 22,
                            userInfo: [NSLocalizedDescriptionKey: "Blurred export failed"]))
                    }
                }
            }
        }

        try FileManager.default.moveItem(at: partialURL, to: outputURL)
        return outputURL
    }

    /// One region to hide, in Core Image coordinates (origin bottom-left).
    struct Region { let rect: CGRect; let round: Bool }

    /// Pixelate then blur the whole frame once, and show that only through a
    /// soft-edged mask of the regions. Pixelating first is what makes it
    /// irreversible: a plain Gaussian blur of a face can be partly undone.
    static func blur(_ image: CIImage, regions: [Region]) -> CIImage {
        guard !regions.isEmpty else { return image }
        let extent = image.extent
        let pixel = CIFilter.pixellate()
        pixel.inputImage = image.clampedToExtent()
        pixel.scale = Float(max(26, extent.width / 20))
        pixel.center = CGPoint(x: extent.midX, y: extent.midY)
        let gauss = CIFilter.gaussianBlur()
        gauss.inputImage = pixel.outputImage
        gauss.radius = Float(max(22, extent.width / 27.5))
        guard let hidden = gauss.outputImage?.cropped(to: extent) else { return image }

        var mask = CIImage(color: .black).cropped(to: extent)
        for region in regions {
            let r = region.rect
            let shape: CIImage?
            if region.round {
                // A soft disc of radius 0.5, stretched to the box: an ellipse
                // with a feathered edge.
                let g = CIFilter.radialGradient()
                // Drawn at 1000 points and scaled down to the box: drawing it
                // tiny and scaling up resamples a 1-pixel image, which came
                // out as a faint smudge the face showed through.
                g.center = .zero
                g.radius0 = 440
                g.radius1 = 500
                g.color0 = .white
                g.color1 = CIColor(red: 0, green: 0, blue: 0, alpha: 0)
                shape = g.outputImage?
                    .cropped(to: CGRect(x: -500, y: -500, width: 1000, height: 1000))
                    .transformed(by: CGAffineTransform(scaleX: r.width / 1000, y: r.height / 1000)
                        .concatenating(CGAffineTransform(translationX: r.midX, y: r.midY)))
            } else {
                shape = CIImage(color: .white).cropped(to: r)
                    .applyingGaussianBlur(sigma: Double(r.height * 0.12))
                    .cropped(to: r.insetBy(dx: -r.height * 0.3, dy: -r.height * 0.3))
            }
            if let shape { mask = shape.composited(over: mask) }
        }

        let blend = CIFilter.blendWithMask()
        blend.inputImage = hidden
        blend.backgroundImage = image
        blend.maskImage = mask.cropped(to: extent)
        return blend.outputImage?.cropped(to: extent) ?? image
    }

    /// Tracks what has been found across frames, so the blur holds steady
    /// instead of flickering: each face or plate keeps its box for a while
    /// after it was last detected (a missed frame never exposes it), boxes
    /// glide toward new detections rather than jumping, and they grow at
    /// once but shrink only gradually.
    final class DetectionState: @unchecked Sendable {
        /// `vx`/`vy` are the box's speed in points per frame, so between
        /// detections (and after a miss) it keeps moving with the subject
        /// instead of trailing behind it.
        private struct Track { var rect: CGRect; let round: Bool; var lastSeen: Int; var vx: CGFloat; var vy: CGFloat }
        /// About half a second at 30 fps.
        private static let hold = 15
        private let lock = NSLock()
        private var frame = 0
        private var tracks: [Track] = []

        func regions(for image: CIImage) -> [Region] {
            lock.lock()
            let n = frame
            frame += 1
            lock.unlock()

            var found: [Region] = []
            if n % BlurExporter.faceEvery == 0 { found += BlurExporter.detectFaces(in: image) }
            if n % BlurExporter.plateEvery == 0 { found += BlurExporter.detectPlates(in: image) }

            lock.lock(); defer { lock.unlock() }
            for region in found {
                if let i = tracks.indices.first(where: {
                    tracks[$0].round == region.round
                        && Self.sameThing(Self.predicted(tracks[$0], at: n), region.rect)
                }) {
                    let t = tracks[i]
                    let frames = CGFloat(max(1, n - t.lastSeen))
                    let rawVx = (region.rect.midX - t.rect.midX) / frames
                    let rawVy = (region.rect.midY - t.rect.midY) / frames
                    tracks[i].vx = t.vx * 0.5 + rawVx * 0.5
                    tracks[i].vy = t.vy * 0.5 + rawVy * 0.5
                    tracks[i].rect = Self.follow(t.rect, toward: region.rect)
                    tracks[i].lastSeen = n
                } else {
                    tracks.append(Track(rect: region.rect, round: region.round, lastSeen: n, vx: 0, vy: 0))
                }
            }
            tracks.removeAll { n - $0.lastSeen > Self.hold }
            let extent = image.extent
            return tracks.map { t in
                var r = Self.predicted(t, at: n)
                // A box drifting off-frame keeps covering the edge it left by.
                r = r.intersection(extent.insetBy(dx: -r.width, dy: -r.height))
                // A little extra margin the longer it goes unconfirmed.
                let age = CGFloat(n - t.lastSeen)
                return Region(rect: r.insetBy(dx: -r.width * 0.02 * age, dy: -r.height * 0.02 * age), round: t.round)
            }
        }

        private static func predicted(_ t: Track, at n: Int) -> CGRect {
            let frames = CGFloat(n - t.lastSeen)
            return t.rect.offsetBy(dx: t.vx * frames, dy: t.vy * frames)
        }

        /// Same face or plate if the centres are within about one box size.
        private static func sameThing(_ a: CGRect, _ b: CGRect) -> Bool {
            let dx = a.midX - b.midX, dy = a.midY - b.midY
            let reach = max(a.width, a.height, b.width, b.height) * 0.8
            return dx * dx + dy * dy < reach * reach
        }

        /// Centre snaps nearly onto the new detection; size grows at once but
        /// shrinks slowly, so the cover never suddenly gets smaller.
        private static func follow(_ old: CGRect, toward new: CGRect) -> CGRect {
            let cx = old.midX + (new.midX - old.midX) * 0.9
            let cy = old.midY + (new.midY - old.midY) * 0.9
            let w = new.width >= old.width ? new.width : old.width + (new.width - old.width) * 0.15
            let h = new.height >= old.height ? new.height : old.height + (new.height - old.height) * 0.15
            return CGRect(x: cx - w / 2, y: cy - h / 2, width: w, height: h)
        }
    }

    static func detectFaces(in image: CIImage) -> [Region] {
        let request = VNDetectFaceRectanglesRequest()
        let handler = VNImageRequestHandler(ciImage: image, options: [:])
        try? handler.perform([request])
        let e = image.extent
        return (request.results ?? []).map { face in
            let b = face.boundingBox
            var r = CGRect(x: e.minX + b.minX * e.width, y: e.minY + b.minY * e.height,
                           width: b.width * e.width, height: b.height * e.height)
            // Vision's box is the inner face; widen for hair, ears and chin.
            r = r.insetBy(dx: -r.width * 0.45, dy: -r.height * 0.45)
            r.origin.y += r.height * 0.05
            return Region(rect: r, round: true)
        }
    }

    static func detectPlates(in image: CIImage) -> [Region] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .fast
        request.usesLanguageCorrection = false
        request.minimumTextHeight = 0.01
        let handler = VNImageRequestHandler(ciImage: image, options: [:])
        try? handler.perform([request])
        let e = image.extent
        return (request.results ?? []).compactMap { obs in
            guard let text = obs.topCandidates(1).first?.string, looksLikePlate(text) else { return nil }
            let b = obs.boundingBox
            let r = CGRect(x: e.minX + b.minX * e.width, y: e.minY + b.minY * e.height,
                           width: b.width * e.width, height: b.height * e.height)
            let aspect = r.width / max(r.height, 1)
            guard aspect > 1.4, aspect < 9 else { return nil }
            return Region(rect: r.insetBy(dx: -r.width * 0.2, dy: -r.height * 0.7), round: false)
        }
    }

    /// Plate-shaped text: 4–10 letters and digits once spaces and dashes are
    /// dropped, with at least one of each. Keeps shop signs and street names
    /// from being blurred while catching EU, UK and US formats alike.
    static func looksLikePlate(_ text: String) -> Bool {
        let core = text.uppercased().filter { !" -·.•".contains($0) }
        guard (4...10).contains(core.count),
              core.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { return false }
        return core.contains(where: \.isNumber) && core.contains(where: \.isLetter)
    }
}
