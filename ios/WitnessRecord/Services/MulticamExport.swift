import Foundation

/// Turns a crew's recordings into timelines a video editor opens with every
/// angle already lined up on the crew's shared clock:
///
/// - `.fcpxml`, a multicam clip for Final Cut Pro, one angle per recording.
/// - `.xml` in the older Final Cut 7 format, which Premiere Pro and DaVinci
///   Resolve import, as a sequence with one video track per angle.
///
/// The footage itself isn't in the export: each member's videos are in their
/// own cloud, in folders shared with the crew. Both files point at the layout
/// the README describes — one folder per member, holding their session
/// folders as downloaded — and an editor relinks by file name if it differs.
enum MulticamExport {
    /// Editors work in frames; 30 fps is what Witness records at by default,
    /// and any offset is still placed to within a frame.
    private static let fps = 30

    static func export(_ crew: Crew) throws -> [URL] {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("crew-export-\(crew.id.uuidString)", isDirectory: true)
        try? FileManager.default.removeItem(at: directory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        let base = safeName(crew.title)
        let files: [(String, String)] = [
            ("\(base).fcpxml", fcpxml(crew)),
            ("\(base) (Premiere, Resolve).xml", xmeml(crew)),
            ("README.txt", readme(crew)),
        ]
        return try files.map { name, contents in
            let url = directory.appendingPathComponent(name)
            try contents.write(to: url, atomically: true, encoding: .utf8)
            return url
        }
    }

    // MARK: - Layout

    private struct Placed {
        let angle: CrewAngle
        let name: String
        let path: String
        /// Seconds from the earliest angle's start.
        let offset: Double
    }

    private static func placed(_ crew: Crew) -> [Placed] {
        let angles = crew.angles.sorted { $0.startedAt < $1.startedAt }
        guard let origin = angles.first?.startedAt else { return [] }
        return angles.map { angle in
            let member = crew.members.first { $0.id == angle.memberID }?.name ?? String(localized: "Crew member")
            let camera = cameraLabel(angle.camera)
            let path = [safeName(member), angle.folderName.map(safeName), angle.fileName]
                .compactMap { $0 }.joined(separator: "/")
            return Placed(angle: angle, name: "\(member) – \(camera)", path: path,
                          offset: angle.startedAt.timeIntervalSince(origin))
        }
    }

    private static func cameraLabel(_ camera: String) -> String {
        switch camera {
        case "front": return String(localized: "front camera")
        case "wide": return String(localized: "wide")
        case "ultrawide": return String(localized: "ultra wide")
        case "pip": return String(localized: "picture in picture")
        default: return String(localized: "rear camera")
        }
    }

    // MARK: - Final Cut Pro

    private static func fcpxml(_ crew: Crew) -> String {
        let angles = placed(crew)
        let total = angles.map { $0.offset + $0.angle.duration }.max() ?? 0
        var assets = ""
        var mcAngles = ""
        for (i, p) in angles.enumerated() {
            let assetID = "a\(i + 1)"
            assets += """
                    <asset id="\(assetID)" name="\(xml(p.name))" start="0s" duration="\(time(p.angle.duration))" hasVideo="1" hasAudio="1" format="r1">
                        <media-rep kind="original-media" src="\(xml(fileURL(p.path)))"/>
                    </asset>

            """
            // The angle that starts first needs no gap in front of it.
            let gap = time(p.offset) == time(0) ? "" : """
                <gap name="Gap" offset="0s" duration="\(time(p.offset))"/>

                """
            mcAngles += """
                            <mc-angle name="\(xml(p.name))" angleID="\(xml(p.angle.id))">
                                \(gap)<asset-clip ref="\(assetID)" name="\(xml(p.name))" offset="\(time(p.offset))" duration="\(time(p.angle.duration))"/>
                            </mc-angle>

            """
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE fcpxml>
            <fcpxml version="1.10">
                <resources>
                    <format id="r1" name="FFVideoFormat1080p30" frameDuration="1/\(fps)s" width="1920" height="1080"/>
            \(assets)        <media id="m1" name="\(xml(crew.title))">
                        <multicam format="r1" tcStart="0s">
            \(mcAngles)            </multicam>
                    </media>
                </resources>
                <library>
                    <event name="\(xml(crew.title))">
                        <mc-clip ref="m1" name="\(xml(crew.title))" offset="0s" duration="\(time(total))"/>
                    </event>
                </library>
            </fcpxml>

            """
    }

    /// Rational seconds on the frame grid, as FCPXML expects.
    private static func time(_ seconds: Double) -> String {
        let frames = Int((max(0, seconds) * Double(fps)).rounded())
        return "\(frames)/\(fps)s"
    }

    // MARK: - Premiere Pro, DaVinci Resolve (Final Cut 7 XML)

    private static func xmeml(_ crew: Crew) -> String {
        let angles = placed(crew)
        let total = frames(angles.map { $0.offset + $0.angle.duration }.max() ?? 0)
        var videoTracks = ""
        var audioTracks = ""
        for (i, p) in angles.enumerated() {
            let start = frames(p.offset)
            let length = frames(p.angle.duration)
            let fileID = "file-\(i + 1)"
            videoTracks += """
                            <track>
                                <clipitem id="v\(i + 1)">
                                    <name>\(xml(p.name))</name>
                                    <duration>\(length)</duration>
                                    \(rate)
                                    <start>\(start)</start>
                                    <end>\(start + length)</end>
                                    <in>0</in>
                                    <out>\(length)</out>
                                    <file id="\(fileID)">
                                        <name>\(xml(p.angle.fileName))</name>
                                        <pathurl>\(xml(fileURL(p.path)))</pathurl>
                                        \(rate)
                                        <duration>\(length)</duration>
                                        <media><video/><audio/></media>
                                    </file>
                                </clipitem>
                            </track>

            """
            audioTracks += """
                            <track>
                                <clipitem id="aud\(i + 1)">
                                    <name>\(xml(p.name))</name>
                                    <duration>\(length)</duration>
                                    \(rate)
                                    <start>\(start)</start>
                                    <end>\(start + length)</end>
                                    <in>0</in>
                                    <out>\(length)</out>
                                    <file id="\(fileID)"/>
                                </clipitem>
                            </track>

            """
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE xmeml>
            <xmeml version="5">
                <sequence id="crew">
                    <name>\(xml(crew.title))</name>
                    <duration>\(total)</duration>
                    \(rate)
                    <media>
                        <video>
            \(videoTracks)            </video>
                        <audio>
            \(audioTracks)            </audio>
                    </media>
                </sequence>
            </xmeml>

            """
    }

    private static var rate: String { "<rate><timebase>\(fps)</timebase><ntsc>FALSE</ntsc></rate>" }

    private static func frames(_ seconds: Double) -> Int {
        Int((max(0, seconds) * Double(fps)).rounded())
    }

    // MARK: - README

    private static func readme(_ crew: Crew) -> String {
        let angles = placed(crew)
        let df = ISO8601DateFormatter()
        df.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var lines = [
            crew.title,
            "",
            String(localized: "Every angle below is placed on the crew's shared clock. To open the timelines, put the footage in one folder laid out like this, next to these files:"),
            "",
        ]
        lines += angles.map { "  \($0.path)" }
        lines += [
            "",
            String(localized: "Each member's footage is in the folders they shared with the crew. If your files are laid out differently, relink by file name when your editor asks."),
            "",
        ]
        for p in angles {
            let start = df.string(from: p.angle.startedAt)
            lines.append("\(p.name): \(start), +\(String(format: "%.3f", p.offset)) s, \(String(format: "%.1f", p.angle.duration)) s")
        }
        return lines.joined(separator: "\n") + "\n"
    }

    // MARK: - Helpers

    /// A relative layout under a placeholder root, so both editors read the
    /// path and fall back to relinking by name.
    private static func fileURL(_ path: String) -> String {
        let encoded = path.split(separator: "/").map {
            String($0).addingPercentEncoding(withAllowedCharacters: unreserved) ?? String($0)
        }.joined(separator: "/")
        return "file:///Witness%20Crew/\(encoded)"
    }

    private static let unreserved = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))

    /// Fit for a file or folder name on any system an editor might use.
    private static func safeName(_ name: String) -> String {
        let cleaned = name.components(separatedBy: CharacterSet(charactersIn: "/\\:<>\"|?*")).joined(separator: "-")
            .trimmingCharacters(in: .whitespaces)
        return cleaned.isEmpty ? "Witness" : cleaned
    }

    private static func xml(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
    }
}
