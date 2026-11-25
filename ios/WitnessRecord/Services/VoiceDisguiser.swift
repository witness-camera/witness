import AVFoundation

/// Disguises the voices in a video's audio, in place: pitched down and
/// re-shaped tonally, so speech stays intelligible but the speakers are hard
/// to recognise. Used on the publish-safe copy only — the original recording
/// keeps its real audio as evidence.
///
/// A pitch shift on its own can be partly reversed by someone shifting it
/// back, so the tone is reshaped as well; even so, this makes voices hard to
/// recognise for an ordinary listener rather than defeating specialist
/// analysis, and the setting says so.
enum VoiceDisguiser {
    /// Semitones × 100. Low enough to change who a voice sounds like,
    /// not so low that words blur.
    private static let pitchCents: Float = -450

    static func apply(to videoURL: URL) async throws {
        let dir = videoURL.deletingLastPathComponent()
        let stem = videoURL.deletingPathExtension().lastPathComponent
        let rawAudio = dir.appendingPathComponent("\(stem)_audio.m4a")
        let disguised = dir.appendingPathComponent("\(stem)_voice.m4a")
        let partial = dir.appendingPathComponent("\(stem)_remux.mp4.partial")
        for url in [rawAudio, disguised, partial] { try? FileManager.default.removeItem(at: url) }
        defer { for url in [rawAudio, disguised, partial] { try? FileManager.default.removeItem(at: url) } }

        let asset = AVURLAsset(url: videoURL)
        guard try await !asset.loadTracks(withMediaType: .audio).isEmpty else { return }

        // 1. Audio out on its own, in a form AVAudioFile reads reliably.
        try await export(asset, preset: AVAssetExportPresetAppleM4A, to: rawAudio, as: .m4a)

        // 2. Disguise it, offline and faster than real time.
        try render(rawAudio, to: disguised)

        // 3. Put the disguised audio back with the untouched video stream.
        let composition = AVMutableComposition()
        let duration = try await asset.load(.duration)
        let range = CMTimeRange(start: .zero, duration: duration)
        if let srcVideo = try await asset.loadTracks(withMediaType: .video).first,
           let track = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) {
            try track.insertTimeRange(range, of: srcVideo, at: .zero)
            track.preferredTransform = try await srcVideo.load(.preferredTransform)
        }
        let voiceAsset = AVURLAsset(url: disguised)
        if let srcAudio = try await voiceAsset.loadTracks(withMediaType: .audio).first,
           let track = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) {
            let audioDuration = try await voiceAsset.load(.duration)
            try track.insertTimeRange(CMTimeRange(start: .zero, duration: min(duration, audioDuration)),
                                      of: srcAudio, at: .zero)
        }
        try await export(composition, preset: AVAssetExportPresetPassthrough, to: partial, as: .mp4)

        _ = try FileManager.default.replaceItemAt(videoURL, withItemAt: partial)
    }

    private static func render(_ input: URL, to output: URL) throws {
        let file = try AVAudioFile(forReading: input)
        let format = file.processingFormat

        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let pitch = AVAudioUnitTimePitch()
        pitch.pitch = pitchCents
        // Reshape the tone too, so shifting the pitch back doesn't restore
        // the original voice: thin the chest resonance, lift the upper mids.
        let eq = AVAudioUnitEQ(numberOfBands: 2)
        eq.bands[0].filterType = .highPass
        eq.bands[0].frequency = 160
        eq.bands[0].bypass = false
        eq.bands[1].filterType = .parametric
        eq.bands[1].frequency = 1900
        eq.bands[1].bandwidth = 1.2
        eq.bands[1].gain = 5
        eq.bands[1].bypass = false

        [player, pitch, eq].forEach(engine.attach)
        engine.connect(player, to: pitch, format: format)
        engine.connect(pitch, to: eq, format: format)
        engine.connect(eq, to: engine.mainMixerNode, format: format)

        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4096)
        try engine.start()
        player.scheduleFile(file, at: nil)
        player.play()

        let out = try AVAudioFile(forWriting: output, settings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: format.sampleRate,
            AVNumberOfChannelsKey: format.channelCount,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ], commonFormat: format.commonFormat, interleaved: format.isInterleaved)

        guard let buffer = AVAudioPCMBuffer(pcmFormat: engine.manualRenderingFormat,
                                            frameCapacity: engine.manualRenderingMaximumFrameCount) else {
            throw NSError(domain: "WitnessRecord", code: 30,
                          userInfo: [NSLocalizedDescriptionKey: "Could not allocate audio buffer"])
        }
        while engine.manualRenderingSampleTime < file.length {
            let left = file.length - engine.manualRenderingSampleTime
            let frames = AVAudioFrameCount(min(Int64(buffer.frameCapacity), left))
            switch try engine.renderOffline(frames, to: buffer) {
            case .success: try out.write(from: buffer)
            case .insufficientDataFromInputNode, .cannotDoInCurrentContext: continue
            case .error: throw NSError(domain: "WitnessRecord", code: 31,
                                       userInfo: [NSLocalizedDescriptionKey: "Voice processing failed"])
            @unknown default: break
            }
        }
        player.stop()
        engine.stop()
    }

    private static func export(_ asset: AVAsset, preset: String, to url: URL, as type: AVFileType) async throws {
        guard let session = AVAssetExportSession(asset: asset, presetName: preset) else {
            throw NSError(domain: "WitnessRecord", code: 32,
                          userInfo: [NSLocalizedDescriptionKey: "Could not create export session"])
        }
        if #available(iOS 18, macOS 15, *) {
            try await session.export(to: url, as: type)
        } else {
            session.outputURL = url
            session.outputFileType = type
            nonisolated(unsafe) let s = session
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                s.exportAsynchronously {
                    if s.status == .completed { c.resume() } else {
                        c.resume(throwing: s.error ?? NSError(domain: "WitnessRecord", code: 33,
                                                              userInfo: [NSLocalizedDescriptionKey: "Export failed"]))
                    }
                }
            }
        }
    }
}
