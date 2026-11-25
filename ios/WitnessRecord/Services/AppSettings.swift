import AVFoundation
import SwiftUI

/// User's preferred rear-camera focal length. Persisted as a raw string.
enum LensPreference: String, CaseIterable, Identifiable {
    case auto        // ultra-wide (0.5×) if available, else wide (1×)
    case ultraWide   // lock to 0.5×
    case wide        // lock to 1×

    var id: String { rawValue }

    /// `LocalizedStringKey`, not `String`: a `String`-typed label passed to
    /// `Text(pref.label)` would use the non-localizing initializer even
    /// though every case here is a literal.
    var label: LocalizedStringKey {
        switch self {
        case .auto: return "Auto (0.5× when available)"
        case .ultraWide: return "0.5× ultra-wide"
        case .wide: return "1× wide"
        }
    }
}

/// Capture resolution. Pro-only above 1080p.
enum VideoResolution: String, CaseIterable, Identifiable {
    case hd1080
    case uhd4K

    var id: String { rawValue }

    var label: LocalizedStringKey {
        switch self {
        case .hd1080: return "1080p HD"
        case .uhd4K: return "4K Ultra HD"
        }
    }

    var height: Int32 { self == .uhd4K ? 2160 : 1080 }
    var width: Int32 { self == .uhd4K ? 3840 : 1920 }
    var requiresPro: Bool { self == .uhd4K }
}

/// Capture frame rate. 30 is the free default; the cinema standards (24 in
/// the US, 25 in Europe) and 60 are Pro.
enum VideoFrameRate: Int, CaseIterable, Identifiable {
    case fps24 = 24
    case fps25 = 25
    case fps30 = 30
    case fps60 = 60

    var id: Int { rawValue }

    var label: LocalizedStringKey {
        switch self {
        case .fps24: return "24 fps (cinema)"
        case .fps25: return "25 fps (PAL)"
        case .fps30: return "30 fps"
        case .fps60: return "60 fps"
        }
    }

    var requiresPro: Bool { self != .fps30 }
}

/// Recording codec. H.264 is the free default; the rest are Pro.
///
/// ProRes is a fixed-quality mastering codec, an order of magnitude heavier
/// than HEVC. At those rates live upload stops being merely slow and becomes
/// arithmetically impossible, so ProRes recordings are kept on the device
/// instead of being promised an upload that could never finish.
enum VideoCodec: String, CaseIterable, Identifiable {
    case h264
    case hevc
    case proRes422LT
    case proRes422HQ

    var id: String { rawValue }

    var label: LocalizedStringKey {
        switch self {
        case .h264: return "H.264"
        case .hevc: return "HEVC 10-bit"
        case .proRes422LT: return "ProRes 422 LT"
        case .proRes422HQ: return "ProRes 422 HQ"
        }
    }

    var requiresPro: Bool { self != .h264 }
    var isProRes: Bool { self == .proRes422LT || self == .proRes422HQ }

    /// ProRes never uploads: see the type comment.
    var uploadsLive: Bool { !isProRes }

    /// ProRes is only valid in a QuickTime container, not MP4.
    var fileExtension: String { isProRes ? "mov" : "mp4" }
    var mimeType: String { isProRes ? "video/quicktime" : "video/mp4" }

    /// Apple Log needs a 10-bit-capable codec; H.264 here is 8-bit.
    var supportsLog: Bool { self != .h264 }

    /// Approximate encoded bit rate in bits per second, used to show the user
    /// what a setting actually costs before they commit to it.
    ///
    /// ProRes figures are the published fixed data rates (scaled from 1080p30
    /// by pixel count and frame rate); HEVC/H.264 mirror the variable rate
    /// `ChunkWriter` actually requests.
    func estimatedBitRate(resolution: VideoResolution, frameRate: VideoFrameRate) -> Double {
        let pixelScale = Double(resolution.width * resolution.height) / (1920.0 * 1080.0)
        let rateScale = Double(frameRate.rawValue) / 30.0
        switch self {
        case .proRes422LT: return 102_000_000 * pixelScale * rateScale
        case .proRes422HQ: return 220_000_000 * pixelScale * rateScale
        case .hevc: return 8_000_000 * pixelScale * rateScale * 1.6
        case .h264: return 8_000_000 * pixelScale * rateScale
        }
    }
}

/// Non-view access to user-facing settings that also have an @AppStorage
/// binding in the UI. Keys and defaults are kept in one place so the view and
/// the capture/stitch layers agree.
enum AppSettings {
    static let photosBackupKey = "photosBackupEnabled"
    static let dualCameraKey = "dualCameraEnabled"
    static let lensPreferenceKey = "lensPreference"

    /// Preferred rear lens. Defaults to Auto (0.5× when the device allows it,
    /// otherwise 1×).
    static var lensPreference: LensPreference {
        (UserDefaults.standard.string(forKey: lensPreferenceKey))
            .flatMap(LensPreference.init(rawValue:)) ?? .auto
    }

    /// Whether the stitched per-session video is saved to the Photos library.
    /// Defaults to on; Drive upload is independent of this.
    static var photosBackupEnabled: Bool {
        UserDefaults.standard.object(forKey: photosBackupKey) as? Bool ?? true
    }

    /// Whether to record the front camera alongside the rear one. Defaults to
    /// on; only takes effect on devices that support simultaneous capture.
    static var dualCameraEnabled: Bool {
        UserDefaults.standard.object(forKey: dualCameraKey) as? Bool ?? true
    }

    static let driveStitchKey = "driveStitchEnabled"

    /// Whether, once every chunk of a session is confirmed uploaded, the app
    /// merges them into one video per camera, uploads that to the session's
    /// Drive folder, and then deletes the small chunk files from Drive (they
    /// already served their purpose — getting footage off the device during
    /// recording). Defaults to on.
    static var driveStitchEnabled: Bool {
        UserDefaults.standard.object(forKey: driveStitchKey) as? Bool ?? true
    }

    // MARK: - Pro capture

    static let proEntitledKey = "proEntitled"
    static let videoResolutionKey = "videoResolution"
    static let videoFrameRateKey = "videoFrameRate"
    static let stabilizationKey = "videoStabilization"

    /// Pro: how hard the camera smooths out shake. Free recordings use
    /// Standard, as before.
    enum Stabilization: String, CaseIterable, Identifiable {
        case off, standard, cinematic, extreme
        var id: String { rawValue }
        var label: LocalizedStringKey {
            switch self {
            case .off: return "Off"
            case .standard: return "Standard"
            case .cinematic: return "Cinematic"
            case .extreme: return "Extreme"
            }
        }

        /// Apple's modes, strongest first, so a format that can't do the
        /// asked-for one falls back to the nearest it can.
        var candidates: [AVCaptureVideoStabilizationMode] {
            switch self {
            case .off: return [.off]
            case .standard: return [.standard]
            case .cinematic: return [.cinematic, .standard]
            case .extreme:
                var modes: [AVCaptureVideoStabilizationMode] = []
                if #available(iOS 18, *) { modes.append(.cinematicExtendedEnhanced) }
                return modes + [.cinematicExtended, .cinematic, .standard]
            }
        }
    }

    static var stabilization: Stabilization {
        guard isProEntitled else { return .standard }
        return UserDefaults.standard.string(forKey: stabilizationKey).flatMap(Stabilization.init) ?? .standard
    }

    static let logRecordingKey = "logRecordingEnabled"

    /// Mirror of the live StoreKit entitlement, refreshed by
    /// `ProEntitlement`. StoreKit is the source of truth and is re-checked at
    /// every launch; this copy exists only so the capture pipeline — which
    /// runs off the main actor — can read the gate synchronously.
    static var isProEntitled: Bool {
        UserDefaults.standard.bool(forKey: proEntitledKey)
    }

    /// Capture resolution, falling back to 1080p when a Pro-only value is
    /// stored but the subscription has lapsed, so an expired subscriber
    /// silently returns to the free tier instead of keeping 4K.
    static var videoResolution: VideoResolution {
        let stored = (UserDefaults.standard.string(forKey: videoResolutionKey))
            .flatMap(VideoResolution.init(rawValue:)) ?? .hd1080
        return (stored.requiresPro && !isProEntitled) ? .hd1080 : stored
    }

    static var videoFrameRate: VideoFrameRate {
        let raw = UserDefaults.standard.object(forKey: videoFrameRateKey) as? Int
        let stored = raw.flatMap(VideoFrameRate.init(rawValue:)) ?? .fps30
        return (stored.requiresPro && !isProEntitled) ? .fps30 : stored
    }

    static let lowResFallbackKey = "lowResFallbackEnabled"

    /// Whether, when uploads are demonstrably falling behind, Witness sends a
    /// small stand-in of each new chunk ahead of the full-quality file.
    ///
    /// Only ever used while the pipeline is actually losing ground: on a
    /// healthy connection the original goes up directly and no stand-in is
    /// ever produced. Defaults to on — on a failing network a low-resolution
    /// copy that arrives beats a pristine one that doesn't.
    static var lowResFallbackEnabled: Bool {
        UserDefaults.standard.object(forKey: lowResFallbackKey) as? Bool ?? true
    }

    static let videoCodecKey = "videoCodec"

    /// Recording codec, falling back to H.264 when a Pro codec is stored but
    /// the subscription has lapsed.
    static var videoCodec: VideoCodec {
        let stored = (UserDefaults.standard.string(forKey: videoCodecKey))
            .flatMap(VideoCodec.init(rawValue:)) ?? .h264
        return (stored.requiresPro && !isProEntitled) ? .h264 : stored
    }

    static let cloudUploadKey = "cloudUploadEnabled"

    /// Whether new recordings are sent to cloud storage at all.
    ///
    /// Read only when a chunk is finalized, and deliberately never consulted
    /// by the upload queue. Turning it off stops the *next* recording from
    /// being queued; it cannot reach into footage already recorded, and no
    /// combination of settings will cancel an upload that is under way.
    ///
    /// That asymmetry is the point. Someone who takes an unlocked phone
    /// during an encounter would otherwise have a switch that deletes the
    /// evidence of it — which is precisely the outcome the app exists to make
    /// impossible. Off is a decision about what to record next, not a way to
    /// unsend what already happened.
    static var cloudUploadEnabled: Bool {
        UserDefaults.standard.object(forKey: cloudUploadKey) as? Bool ?? true
    }

    static let cloudAccountConnectedKey = "cloudAccountConnected"

    /// Whether a cloud account is connected for the selected backend.
    ///
    /// Read by the capture pipeline, which can run on a locked phone from an
    /// Action Button cold start, before Google's sign-in has been restored —
    /// so it can't ask the live sign-in state. The stored credentials answer
    /// synchronously (the Keychain items are readable after first unlock);
    /// the mirrored flag covers a Keychain that can't be read. Unknown counts
    /// as connected: wrongly assuming an account keeps chunks queued for a
    /// cloud that never arrives, which wastes space, while wrongly assuming
    /// none would keep footage off the cloud during an encounter.
    static var cloudAccountConnected: Bool {
        if hasStoredCloudCredentials { return true }
        return UserDefaults.standard.object(forKey: cloudAccountConnectedKey) as? Bool ?? true
    }

    static var hasStoredCloudCredentials: Bool {
        switch StorageProviders.selectedID {
        case .googleDrive: return KeychainService.shared.string(for: .googleRefreshToken) != nil
        case .dropbox: return KeychainService.shared.string(for: .dropboxRefreshToken) != nil
        case .webDAV: return WebDAVProvider.credentials != nil
        case .proton: return false
        }
    }

    /// Mirrors the stored credentials into the flag above. Called while the
    /// app is in the foreground, when the Keychain is certainly readable.
    static func refreshCloudAccountConnected() {
        UserDefaults.standard.set(hasStoredCloudCredentials, forKey: cloudAccountConnectedKey)
    }

    /// Whether this session's footage is kept on the device rather than
    /// uploaded — because ProRes is far too large to send live, because
    /// uploading has been switched off for new recordings, or because no cloud
    /// account is connected to send it to. In the last case, queuing chunks
    /// for a cloud that isn't there kept every recording on the phone twice:
    /// once as chunks waiting indefinitely, once as the photo library copy.
    ///
    /// Pure and takes its inputs, for the same two reasons as
    /// `dualCameraBlockers`: Settings has to reach the same answer as the
    /// capture pipeline, and a SwiftUI view only updates when it reads its own
    /// `@AppStorage` values rather than a static going to `UserDefaults`
    /// behind its back.
    ///
    /// It matters that both agree here. This decides whether a recording's
    /// originals are deleted at capture, which makes the photo library the
    /// only copy — so a Settings screen that disagreed with it would be
    /// offering a switch that silently destroys footage.
    static func isLocalOnlyCapture(codec: VideoCodec,
                                   cloudUploadEnabled: Bool,
                                   cloudAccountConnected: Bool,
                                   isPro: Bool) -> Bool {
        let effective = (codec.requiresPro && !isPro) ? .h264 : codec
        return !effective.uploadsLive || !cloudUploadEnabled || !cloudAccountConnected
    }

    static var isLocalOnlyCapture: Bool {
        isLocalOnlyCapture(codec: videoCodec,
                           cloudUploadEnabled: cloudUploadEnabled,
                           cloudAccountConnected: cloudAccountConnected,
                           isPro: isProEntitled)
    }

    /// One capture setting that is keeping the front camera off.
    enum DualCameraBlocker: String, CaseIterable {
        case resolution, frameRate, codec, log
    }

    /// Which settings are ruling out simultaneous front+rear capture.
    ///
    /// Simultaneous capture is limited to the formats the hardware can run on
    /// two cameras at once — in practice 1080p30 H.264, never Apple Log — so
    /// these are mutually exclusive. Pro wins: silently ignoring settings
    /// somebody is paying for is worse than dropping the second camera.
    ///
    /// Pure, and takes its inputs, for two reasons. It used to be written
    /// three times — here, in Settings and in the Pro page — and the copies
    /// disagreed about Apple Log, so the front camera could be reported off
    /// with nothing actually keeping it off. And a SwiftUI view has to *read*
    /// its `@AppStorage` values for the row to update when they change, which
    /// reading a static that goes to `UserDefaults` behind its back would not
    /// do. Passing them in gives both callers the same answer and the view its
    /// dependency.
    static func dualCameraBlockers(resolution: VideoResolution,
                                   frameRate: VideoFrameRate,
                                   codec: VideoCodec,
                                   logRecording: Bool,
                                   isPro: Bool) -> [DualCameraBlocker] {
        // Without the subscription every Pro setting reverts to its free
        // default, so none of them blocks anything.
        guard isPro else { return [] }
        var blockers: [DualCameraBlocker] = []
        if resolution != .hd1080 { blockers.append(.resolution) }
        if frameRate != .fps30 { blockers.append(.frameRate) }
        if codec != .h264 { blockers.append(.codec) }
        // Apple Log needs a 10-bit codec, so the stored flag can outlive a
        // switch back to H.264 and mean nothing. A setting that is not doing
        // anything must not keep the second camera off — that was the bug:
        // going HEVC → Log on → back to H.264 left the front camera off with
        // no visible cause, because the flag was still set and two of the
        // three copies of this rule looked at it without checking the codec.
        if logRecording && codec.supportsLog { blockers.append(.log) }
        return blockers
    }

    /// The same question asked of the stored settings, for the capture layer.
    static var dualCameraBlockers: [DualCameraBlocker] {
        dualCameraBlockers(
            resolution: videoResolution,
            frameRate: videoFrameRate,
            codec: videoCodec,
            logRecording: UserDefaults.standard.bool(forKey: logRecordingKey),
            isPro: isProEntitled)
    }

    /// Whether any capture setting departs from the free defaults. Derived
    /// from the list above, so "is the front camera off" and "what is keeping
    /// it off" cannot give contradictory answers.
    static var usesProCapture: Bool { !dualCameraBlockers.isEmpty }

    // MARK: - Session video

    /// How the single per-session video — the one that goes to Photos, and to
    /// Drive when stitching is on — gets built.
    enum SessionVideoStrategy {
        /// Written live, by a second writer running alongside the chunk
        /// writers, and finished the instant recording stops.
        case live
        /// Concatenated from the staged chunks after recording ends.
        case merge
    }

    /// The pixel throughput one camera costs at these settings, in pixels per
    /// second. The unit the encoder actually cares about: 4K30 and 1080p120
    /// are roughly the same amount of work, and the setting names are not.
    static func pixelsPerSecond(resolution: VideoResolution,
                                frameRate: VideoFrameRate) -> Double {
        Double(resolution.width) * Double(resolution.height) * Double(frameRate.rawValue)
    }

    /// Everything above this, summed across every writer that will be running,
    /// takes the merge path instead. 1080p30 × 4 — which is what dual-camera
    /// recording costs once each camera is also writing its session video
    /// live, and the heaviest configuration this app asks for.
    static let liveSessionVideoBudget: Double = 1920 * 1080 * 30 * 4

    /// Which way this recording will produce its session video.
    ///
    /// Writing it live is strictly better when it fits: the file is complete
    /// the moment recording stops, so it reaches Photos immediately rather
    /// than after an export whose cost grows with the length of the recording
    /// — the export that a backgrounded app has about thirty seconds to
    /// finish, and that a half-hour recording cannot.
    ///
    /// What it costs is a second hardware encode of every frame, per camera.
    /// At 1080p30 that is comfortable; at 4K60, or in ProRes, it competes with
    /// the recording itself, and dropped frames in the footage are a far worse
    /// outcome than a slow save. So the heavy settings keep the old behaviour,
    /// where the extra work happens after the encounter rather than during it.
    static func sessionVideoStrategy(resolution: VideoResolution,
                                     frameRate: VideoFrameRate,
                                     codec: VideoCodec,
                                     cameras: Int) -> SessionVideoStrategy {
        // ProRes rates are an order of magnitude above HEVC, and a second
        // ProRes stream would fill the device as fast as it encoded.
        guard !codec.isProRes else { return .merge }
        let perCamera = pixelsPerSecond(resolution: resolution, frameRate: frameRate)
        let writers = Double(max(1, cameras) * 2) // chunks + session video
        return perCamera * writers <= liveSessionVideoBudget ? .live : .merge
    }

    /// The same question asked of the stored settings.
    static func sessionVideoStrategy(cameras: Int) -> SessionVideoStrategy {
        sessionVideoStrategy(resolution: videoResolution,
                             frameRate: videoFrameRate,
                             codec: videoCodec,
                             cameras: cameras)
    }

    /// Whether anything is going to want a single per-session video at all.
    /// When nothing is, the live writer is pure cost and is not started.
    static var wantsSessionVideo: Bool {
        photosBackupEnabled || isLocalOnlyCapture
            || (driveStitchEnabled && !isLocalOnlyCapture)
    }

    /// Whether to capture in Apple Log. Pro-only, and additionally requires
    /// hardware support (iPhone 15 Pro and newer) — the capture layer checks
    /// the active format and silently stays in the standard color space when
    /// the device can't do it.
    static var logRecordingEnabled: Bool {
        guard isProEntitled, videoCodec.supportsLog else { return false }
        return UserDefaults.standard.bool(forKey: logRecordingKey)
    }

    static let quickStartViewfinderKey = "quickStartViewfinder"

    /// Whether a recording started from the Action Button, widget or Siri
    /// shows the camera. Off by default: the black screen is the point of a
    /// one-press start. Off also leaves an already-open viewfinder as it is.
    static var quickStartViewfinder: Bool {
        UserDefaults.standard.bool(forKey: quickStartViewfinderKey)
    }

    static let timestampBurnInKey = "timestampBurnInEnabled"

    /// Whether to burn a visible timestamp and GPS coordinate into the
    /// picture itself, on top of the invisible metadata (SHA-256 hash,
    /// manifest, RFC 3161 timestamp) every recording already carries
    /// regardless of this setting.
    ///
    /// Free for everyone. Without Pro the overlay also carries a
    /// "Recorded with Witness" line; Pro leaves only the time and place.
    static var timestampBurnInEnabled: Bool {
        return UserDefaults.standard.bool(forKey: timestampBurnInKey)
    }

    static let pipExportKey = "pipExportEnabled"

    /// Whether a dual-camera session also produces one combined
    /// picture-in-picture video (rear full-frame, front inset) in addition
    /// to — never instead of — the two separate per-camera videos it
    /// already produces. Purely a convenience for sharing one file instead
    /// of two; nothing about the underlying two recordings changes.
    static var pipExportEnabled: Bool {
        guard isProEntitled else { return false }
        return UserDefaults.standard.bool(forKey: pipExportKey)
    }

    static let highlightExportKey = "highlightExportEnabled"

    /// Whether a session also produces a shorter "highlights" cut — the
    /// stretches with someone actually talking, dead air trimmed out — in
    /// addition to, never instead of, the full per-camera video it already
    /// produces. Built from the rear camera's own audio and video, so it
    /// exists regardless of whether dual capture or picture-in-picture is on.
    static var highlightExportEnabled: Bool {
        guard isProEntitled else { return false }
        return UserDefaults.standard.bool(forKey: highlightExportKey)
    }

    static let blurExportKey = "blurExportEnabled"

    /// Whether a session also produces a copy with faces and licence plates
    /// blurred, for publishing — in addition to, never instead of, the
    /// untouched original, which remains the evidence. Pro, as a publishing
    /// workflow; nothing about the recording itself changes.
    static var blurExportEnabled: Bool {
        guard isProEntitled else { return false }
        return UserDefaults.standard.bool(forKey: blurExportKey)
    }

    static let voiceDisguiseKey = "voiceDisguiseEnabled"

    /// Whether the blurred, publish-safe copy also disguises voices. Only
    /// meaningful with `blurExportEnabled`; the original's audio is never
    /// touched.
    static var voiceDisguiseEnabled: Bool {
        guard isProEntitled else { return false }
        return UserDefaults.standard.bool(forKey: voiceDisguiseKey)
    }

    static let stealthLandscapeKey = "stealthLandscapeEnabled"

    /// Record a landscape 16:9 video while holding the phone upright: the
    /// tall 4:3 sensor format with a landscape strip cut from each frame.
    /// Single camera, so it takes priority over dual-camera and two-lens.
    static var stealthLandscapeEnabled: Bool {
        guard isProEntitled else { return false }
        return UserDefaults.standard.bool(forKey: stealthLandscapeKey)
    }

    static let dualLensKey = "dualLensEnabled"

    /// Whether to record the 1× wide and 0.5× ultra-wide rear lenses at the
    /// same time, as two separate videos, so the framing can be chosen
    /// afterwards. Pro-gated as a capture-workflow choice; nothing about the
    /// free recording changes, and it stands down whenever a Pro format
    /// (4K, 60 fps, Apple Log, ProRes) is chosen, since two cameras can't
    /// run those.
    static var dualLensEnabled: Bool {
        guard isProEntitled else { return false }
        return UserDefaults.standard.bool(forKey: dualLensKey)
    }

    static let folderNameTemplateKey = "folderNameTemplate"
    static let defaultFolderNameTemplate = "Witness {date} {time}, {location}"

    /// Template for each session's Drive subfolder name. Supports `{date}`,
    /// `{time}`, `{location}`, and `{session}` placeholders — see
    /// `SessionFolderNaming.placeholders`. Falls back to the default if unset
    /// or if it resolves to an empty name.
    static var folderNameTemplate: String {
        let stored = UserDefaults.standard.string(forKey: folderNameTemplateKey)
        guard let stored, !stored.trimmingCharacters(in: .whitespaces).isEmpty else {
            return defaultFolderNameTemplate
        }
        return stored
    }
}
