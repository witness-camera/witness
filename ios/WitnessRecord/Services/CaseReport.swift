import Foundation
import UIKit

/// One PDF for a whole case: every item in order, with its hash, its proof,
/// the details and notes added afterwards, and its transcript.
///
/// Like `EvidenceReport`, it presents what is recorded and adds nothing. The
/// line between the two kinds of content is kept visible: what was captured
/// and hashed, and what the person wrote later.
enum CaseReport {
    private static let page = CGRect(x: 0, y: 0, width: 595, height: 842)
    private static let margin: CGFloat = 48

    /// The PDF plus every file that an independent check would run against.
    @MainActor
    static func package(for evidenceCase: EvidenceCase, items: [CaseItem]) throws -> [URL] {
        let ordered = items.sorted { $0.createdAt < $1.createdAt }
        var attachments: [URL] = []
        for item in ordered {
            if item.kind == .video, let session = item.sessionID {
                for url in [SessionArchive.manifestURL(for: session), SessionArchive.timestampURL(for: session)]
                where FileManager.default.fileExists(atPath: url.path) {
                    attachments.append(copy(url, as: "\(short(item.id))-\(url.lastPathComponent)"))
                }
            } else {
                for url in [CaseLibrary.mediaURL(for: item), CaseLibrary.recordURL(for: item.id),
                            CaseLibrary.timestampURL(for: item.id)].compactMap({ $0 })
                where FileManager.default.fileExists(atPath: url.path) {
                    attachments.append(copy(url, as: "\(short(item.id))-\(url.lastPathComponent)"))
                }
            }
        }
        return [try pdf(evidenceCase, ordered)] + attachments
    }

    private static func short(_ id: UUID) -> String { String(id.uuidString.prefix(8)) }

    private static func copy(_ url: URL, as name: String) -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("case-export", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dest = dir.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: dest)
        try? FileManager.default.copyItem(at: url, to: dest)
        return dest
    }

    @MainActor
    private static func pdf(_ evidenceCase: EvidenceCase, _ items: [CaseItem]) throws -> URL {
        let safeTitle = evidenceCase.title.components(separatedBy: CharacterSet(charactersIn: "/\\:?*\"<>|")).joined()
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Witness case - \(safeTitle).pdf")
        let format = UIGraphicsPDFRendererFormat()
        format.documentInfo = [kCGPDFContextTitle as String: evidenceCase.title,
                               kCGPDFContextCreator as String: "Witness"]
        let renderer = UIGraphicsPDFRenderer(bounds: page, format: format)
        try renderer.writePDF(to: url) { context in
            var w = Writer(context: context)
            w.newPage()
            w.text(evidenceCase.title, .systemFont(ofSize: 20, weight: .bold))
            w.text(String(localized: "Case report · \(items.count) items · generated \(stamp(Date()))"),
                   .systemFont(ofSize: 10), .darkGray)
            w.gap(8)
            w.text(String(localized: "Each item below lists the SHA-256 of what was captured and whether an independent RFC 3161 time-stamp proof exists for it. Details and notes were written afterwards by the person who made the recording and are not part of the hashed record."),
                   .systemFont(ofSize: 9), .gray)
            w.text(String(localized: "To check any item, drop its files together with the .json record and .tsr proof shared with this report into witnesscamera.com/verify. It runs in the browser and uploads nothing."),
                   .systemFont(ofSize: 9, weight: .medium), .darkGray)
            for (n, item) in items.enumerated() {
                w.gap(14)
                w.rule()
                w.text("\(n + 1). \(kindName(item)) — \(stamp(item.createdAt))",
                       .systemFont(ofSize: 13, weight: .semibold))
                if let title = item.title { w.field(String(localized: "Name"), title) }
                if let end = item.endedAt, item.kind != .photo { w.field(String(localized: "Ended"), stamp(end)) }
                if let lat = item.latitude, let lon = item.longitude {
                    w.field(String(localized: "Location"), String(format: "%.5f, %.5f", lat, lon))
                }
                if item.kind == .video, let session = item.sessionID {
                    w.field(String(localized: "Recording"), session.uuidString)
                    if let manifest = SessionArchive.sessions().first(where: { $0.sessionID == session }) {
                        w.field(String(localized: "Segments"), "\(manifest.chunkCount)")
                        for video in manifest.mergedVideos ?? [] {
                            w.field(video.fileName, video.sha256 ?? "—", mono: true)
                        }
                    }
                } else {
                    w.field("SHA-256", item.sha256 ?? "—", mono: true)
                }
                w.field(String(localized: "Time-stamp proof"),
                        item.timestamped ? String(localized: "Yes (RFC 3161)") : String(localized: "Not obtained"))
                if item.isImported {
                    w.text(String(localized: "Imported: the proof starts when the file was imported, not when it was made."),
                           .italicSystemFont(ofSize: 9), .darkGray)
                }
                let template = item.template.flatMap(DetailTemplate.init(rawValue:))
                let answered = (template?.prompts ?? []).filter { !(item.details[$0.id] ?? "").isEmpty }
                if !answered.isEmpty {
                    w.gap(4)
                    w.text(String(localized: "Details added afterwards"), .systemFont(ofSize: 10, weight: .semibold))
                    for prompt in answered { w.field(prompt.label, item.details[prompt.id] ?? "") }
                }
                if !item.notes.isEmpty {
                    w.gap(4)
                    w.text(String(localized: "Notes added afterwards"), .systemFont(ofSize: 10, weight: .semibold))
                    for note in item.notes { w.field(stamp(note.createdAt), note.text) }
                }
                if let transcript = item.transcript, !transcript.segments.isEmpty {
                    w.gap(4)
                    w.text(String(localized: "Transcript (automatic, \(Transcriber.name(of: Locale(identifier: transcript.localeIdentifier))))"),
                           .systemFont(ofSize: 10, weight: .semibold))
                    for segment in transcript.segments { w.field(clock(segment.start), segment.text) }
                }
            }
        }
        return url
    }

    static func kindName(_ item: CaseItem) -> String {
        switch item.kind {
        case .video: return String(localized: "Recording")
        case .photo: return String(localized: "Photo")
        case .audio: return String(localized: "Audio recording")
        case .file: return String(localized: "Imported file")
        }
    }

    static func stamp(_ date: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss zzz"
        return f.string(from: date)
    }

    static func clock(_ t: TimeInterval) -> String {
        let s = Int(t)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                         : String(format: "%d:%02d", s / 60, s % 60)
    }

    private struct Writer {
        let context: UIGraphicsPDFRendererContext
        var y: CGFloat = 0
        var width: CGFloat { page.width - 2 * margin }

        mutating func newPage() { context.beginPage(); y = margin }

        mutating func ensure(_ h: CGFloat) { if y + h > page.height - margin { newPage() } }

        mutating func gap(_ h: CGFloat) { y += h }

        mutating func rule() {
            ensure(8)
            UIColor.lightGray.setFill()
            UIRectFill(CGRect(x: margin, y: y, width: width, height: 0.5))
            y += 8
        }

        mutating func text(_ s: String, _ font: UIFont, _ color: UIColor = .black) {
            let h = height(s, font, width)
            ensure(h + 3)
            s.draw(in: CGRect(x: margin, y: y, width: width, height: h),
                   withAttributes: [.font: font, .foregroundColor: color])
            y += h + 3
        }

        mutating func field(_ label: String, _ value: String, mono: Bool = false) {
            let font: UIFont = mono ? .monospacedSystemFont(ofSize: 8, weight: .regular) : .systemFont(ofSize: 9.5)
            let labelFont = UIFont.systemFont(ofSize: 9.5, weight: .medium)
            let h = max(height(value, font, width - 130), height(label, labelFont, 124))
            ensure(h + 3)
            label.draw(in: CGRect(x: margin, y: y, width: 124, height: h),
                       withAttributes: [.font: labelFont, .foregroundColor: UIColor.darkGray])
            value.draw(in: CGRect(x: margin + 130, y: y, width: width - 130, height: h),
                       withAttributes: [.font: font, .foregroundColor: UIColor.black])
            y += h + 3
        }

        private func height(_ s: String, _ font: UIFont, _ w: CGFloat) -> CGFloat {
            ceil((s as NSString).boundingRect(with: CGSize(width: w, height: .greatestFiniteMagnitude),
                                              options: [.usesLineFragmentOrigin, .usesFontLeading],
                                              attributes: [.font: font], context: nil).height)
        }
    }
}
