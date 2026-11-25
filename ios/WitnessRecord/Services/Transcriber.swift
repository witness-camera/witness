import AVFoundation
import Speech

/// Turns a recording's audio into a timed transcript, in a language the
/// person chooses.
///
/// On the device wherever the language allows it, which is most of them on a
/// recent iPhone: the audio of a recording like this should not leave the
/// phone for the sake of a search index. Where a language can only be done on
/// Apple's servers the person is asked first, and the transcript records which
/// way it was made.
///
/// The audio is fed in 50-second pieces. On the device that only keeps memory
/// flat; on the server it is required, since a single request is capped at
/// about a minute.
enum Transcriber {
    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private static let window: TimeInterval = 50
    /// Words within this many seconds of each other share one line.
    private static let lineSpan: TimeInterval = 8

    static var languages: [Locale] {
        SFSpeechRecognizer.supportedLocales()
            .sorted { name(of: $0) < name(of: $1) }
    }

    /// The supported language closest to `identifier`: the same one, else the
    /// same language in another region, else English.
    static func bestMatch(for identifier: String) -> Locale {
        let all = languages
        let wanted = identifier.replacingOccurrences(of: "_", with: "-")
        if let exact = all.first(where: { $0.identifier == wanted }) { return exact }
        let language = Locale(identifier: identifier).language.languageCode
        if let same = all.first(where: { $0.language.languageCode == language }) { return same }
        return all.first { $0.identifier == "en-US" } ?? all.first ?? Locale(identifier: "en-US")
    }

    static func name(of locale: Locale) -> String {
        Locale.current.localizedString(forIdentifier: locale.identifier) ?? locale.identifier
    }

    static func canRunOnDevice(_ locale: Locale) -> Bool {
        if modernLocales.contains(where: { $0.identifier(.bcp47) == locale.identifier(.bcp47) }) { return true }
        return SFSpeechRecognizer(locale: locale)?.supportsOnDeviceRecognition ?? false
    }

    /// Languages the iOS 26 engine handles, filled by `prepare()`.
    nonisolated(unsafe) private static var modernLocales: [Locale] = []

    /// Call before showing the language choice.
    static func prepare() async {
        if #available(iOS 26, *), SpeechTranscriber.isAvailable {
            modernLocales = await SpeechTranscriber.supportedLocales
        }
    }

    static func authorize() async -> Bool {
        let status = await withCheckedContinuation { c in
            SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0) }
        }
        return status == .authorized
    }

    static func transcribe(_ audioURL: URL, locale: Locale, allowServer: Bool,
                           progress: @escaping @Sendable (Double) -> Void) async throws -> Transcript {
        // iOS 26's engine is a long-form model: it takes the whole recording
        // in one pass, keeps context across sentences, and is far more
        // accurate than the dictation recognizer below, most of all outside
        // English. Always on the device; the model downloads once per
        // language.
        if #available(iOS 26, *), let segments = try await transcribeModern(audioURL, locale: locale, progress: progress) {
            return Transcript(localeIdentifier: locale.identifier, createdAt: Date(),
                              onDevice: true, segments: segments)
        }

        guard let recognizer = SFSpeechRecognizer(locale: locale), recognizer.isAvailable else {
            throw Failure(message: String(localized: "This language isn't available for transcription right now."))
        }
        let onDevice = recognizer.supportsOnDeviceRecognition
        guard onDevice || allowServer else {
            throw Failure(message: String(localized: "This language can only be transcribed on Apple's servers."))
        }

        let asset = AVURLAsset(url: audioURL)
        let duration = try await asset.load(.duration).seconds
        guard duration > 0 else { return Transcript(localeIdentifier: locale.identifier, createdAt: Date(), onDevice: onDevice, segments: []) }

        var words: [(time: TimeInterval, text: String)] = []
        var start: TimeInterval = 0
        while start < duration {
            try Task.checkCancellation()
            let length = min(window, duration - start)
            let piece = FileManager.default.temporaryDirectory
                .appendingPathComponent("transcribe-\(UUID().uuidString).m4a")
            defer { try? FileManager.default.removeItem(at: piece) }
            try await export(asset, from: start, length: length, to: piece)
            for segment in try await recognize(piece, with: recognizer, onDevice: onDevice) {
                words.append((start + segment.timestamp, segment.substring))
            }
            start += window
            progress(min(1, start / duration))
        }

        var segments: [Transcript.Segment] = []
        var lineStart: TimeInterval?
        var line: [String] = []
        for word in words {
            if let s = lineStart, word.time - s > lineSpan {
                segments.append(Transcript.Segment(start: s, text: line.joined(separator: " ")))
                line = []
                lineStart = nil
            }
            if lineStart == nil { lineStart = word.time }
            line.append(word.text)
        }
        if let s = lineStart, !line.isEmpty {
            segments.append(Transcript.Segment(start: s, text: line.joined(separator: " ")))
        }
        return Transcript(localeIdentifier: locale.identifier, createdAt: Date(),
                          onDevice: onDevice, segments: segments)
    }

    /// Nil when the new engine doesn't cover this language.
    @available(iOS 26, *)
    private static func transcribeModern(_ audioURL: URL, locale: Locale,
                                         progress: @escaping @Sendable (Double) -> Void) async throws -> [Transcript.Segment]? {
        guard SpeechTranscriber.isAvailable,
              let supported = await SpeechTranscriber.supportedLocale(equivalentTo: locale) else { return nil }
        let transcriber = SpeechTranscriber(locale: supported, transcriptionOptions: [],
                                            reportingOptions: [], attributeOptions: [.audioTimeRange])
        if let install = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
            progress(0.02)
            try await install.downloadAndInstall()
        }
        let file = try AVAudioFile(forReading: audioURL)
        let duration = Double(file.length) / file.processingFormat.sampleRate
        let analyzer = SpeechAnalyzer(modules: [transcriber])

        let collect = Task { () throws -> [Transcript.Segment] in
            var segments: [Transcript.Segment] = []
            for try await result in transcriber.results {
                let text = String(result.text.characters).trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { continue }
                let start = result.range.start.seconds
                segments.append(Transcript.Segment(start: start.isFinite ? start : 0, text: text))
                if duration > 0 { progress(min(0.99, result.range.end.seconds / duration)) }
            }
            return segments
        }
        do {
            try await withTaskCancellationHandler {
                try await analyzer.start(inputAudioFile: file, finishAfterFile: true)
            } onCancel: {
                Task { await analyzer.cancelAndFinishNow() }
            }
        } catch {
            collect.cancel()
            throw error
        }
        let segments = try await collect.value
        progress(1)
        return merge(segments)
    }

    /// Joins short results into lines of about `lineSpan` seconds, like the
    /// older path, so a transcript reads the same either way.
    private static func merge(_ results: [Transcript.Segment]) -> [Transcript.Segment] {
        var lines: [Transcript.Segment] = []
        for r in results.sorted(by: { $0.start < $1.start }) {
            if let last = lines.last, r.start - last.start < lineSpan, last.text.count < 160 {
                lines[lines.count - 1] = Transcript.Segment(start: last.start, text: last.text + " " + r.text)
            } else {
                lines.append(r)
            }
        }
        return lines
    }

    private static func recognize(_ url: URL, with recognizer: SFSpeechRecognizer,
                                  onDevice: Bool) async throws -> [SFTranscriptionSegment] {
        let request = SFSpeechURLRecognitionRequest(url: url)
        request.requiresOnDeviceRecognition = onDevice
        request.shouldReportPartialResults = false
        request.addsPunctuation = true
        final class Box: @unchecked Sendable { var task: SFSpeechRecognitionTask?; var done = false }
        let box = Box()
        return try await withCheckedThrowingContinuation { c in
            box.task = recognizer.recognitionTask(with: request) { result, error in
                guard !box.done else { return }
                if let result, result.isFinal {
                    box.done = true
                    c.resume(returning: result.bestTranscription.segments)
                } else if let error {
                    box.done = true
                    // Silence comes back as "no speech detected": an empty
                    // piece, not a failed transcript.
                    let ns = error as NSError
                    if ns.domain == "kAFAssistantErrorDomain" && (ns.code == 1110 || ns.code == 1101) {
                        c.resume(returning: [])
                    } else {
                        c.resume(throwing: error)
                    }
                }
            }
        }
    }

    private static func export(_ asset: AVAsset, from start: TimeInterval, length: TimeInterval, to url: URL) async throws {
        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw Failure(message: "Could not prepare audio")
        }
        session.timeRange = CMTimeRange(start: CMTime(seconds: start, preferredTimescale: 600),
                                        duration: CMTime(seconds: length, preferredTimescale: 600))
        if #available(iOS 18, *) {
            try await session.export(to: url, as: .m4a)
        } else {
            session.outputURL = url
            session.outputFileType = .m4a
            nonisolated(unsafe) let s = session
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                s.exportAsynchronously {
                    if s.status == .completed { c.resume() } else {
                        c.resume(throwing: s.error ?? Failure(message: "Could not prepare audio"))
                    }
                }
            }
        }
    }
}
