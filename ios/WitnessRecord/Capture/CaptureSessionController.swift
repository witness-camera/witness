import Foundation
import AVFoundation
import CoreLocation
import UIKit

/// Owns the capture session and rotates AVAssetWriter chunks every ~15 seconds
/// so footage uploads incrementally during the encounter.
///
/// Two modes:
///  - Single-camera: an AVCaptureSession with the rear camera + mic.
///  - Dual-camera: an AVCaptureMultiCamSession recording the rear and front
///    cameras simultaneously into two parallel chunk streams (each with audio),
///    when the user enables it and the device supports it.
///
/// Configuration tries, in order: dual with the ultra-wide (0.5×) rear lens,
/// dual with the wide (1×) rear lens, then single-camera with ultra-wide, then
/// single with wide — so a device that can't do a particular combination
/// degrades gracefully instead of failing. Fresh capture outputs are built for
/// each attempt and only adopted on success, so a failed attempt never leaves
/// an output attached to an abandoned session.
///
/// All sample handling happens on a private serial queue.
final class CaptureSessionController: NSObject {

    struct FinalizedChunk {
        let fileURL: URL
        let sessionID: UUID
        let index: Int
        let camera: CameraSource
        let startedAt: Date
        let endedAt: Date
        let location: CLLocation?
    }

    /// Whether this device can record both cameras at once.
    static var isDualCameraSupported: Bool {
        AVCaptureMultiCamSession.isMultiCamSupported
    }

    /// Whether this device can run the 1× and 0.5× rear lenses together.
    /// Asked of the system's own list of camera combinations that can run at
    /// once, rather than inferred from a model name.
    static var isRearPairSupported: Bool {
        guard AVCaptureMultiCamSession.isMultiCamSupported,
              let wide = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let ultra = AVCaptureDevice.default(.builtInUltraWideCamera, for: .video, position: .back)
        else { return false }
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera, .builtInUltraWideCamera],
            mediaType: .video, position: .back)
        return discovery.supportedMultiCamDeviceSets.contains {
            $0.contains(wide) && $0.contains(ultra)
        }
    }

    /// Called every time a chunk file is finalized on disk.
    var onChunkFinalized: ((FinalizedChunk) -> Void)?
    /// Called once a stop() has fully finalized its last chunk(s), carrying the
    /// just-ended recording session's ID and any session videos that were
    /// written live alongside the chunks (see `writesSessionVideoLive`), one
    /// per camera. Fires after every file for the session is on disk (both
    /// cameras in dual mode). An empty array means the session video has to be
    /// merged from the chunks the old way.
    var onSessionFinalized: ((UUID, [(camera: String, url: URL)]) -> Void)?
    /// Called on the main queue when the session hits an unrecoverable error.
    var onError: ((String) -> Void)?
    /// Called on the main queue after configuration, reporting whether dual
    /// camera is actually active (may be false even when requested, if the
    /// device fell back to single-camera).
    var onConfigured: ((_ dualActive: Bool) -> Void)?
    /// Called on the capture queue with each live video frame while preview is
    /// enabled, for an optional on-screen viewfinder.
    var onPreviewFrame: ((CMSampleBuffer, CameraSource) -> Void)?
    /// Called on the main queue with a session-backed preview layer when the
    /// viewfinder has to be driven by the hardware rather than by sample
    /// buffers, and with nil when it doesn't. See `makeHardwarePreviewIfNeeded`.
    var onHardwarePreview: ((AVCaptureVideoPreviewLayer?) -> Void)?
    /// Called on the main queue after configuration with a human-readable
    /// description of what the camera is actually doing. See
    /// `publishDiagnostics`.
    var onDiagnostics: ((String) -> Void)?

    private var session = AVCaptureSession()
    private var backVideoOutput = AVCaptureVideoDataOutput()
    private var frontVideoOutput = AVCaptureVideoDataOutput()
    private var audioOutput = AVCaptureAudioDataOutput()
    private let sessionQueue = DispatchQueue(label: "com.witnessrecord.session")
    private let dataQueue = DispatchQueue(label: "com.witnessrecord.capturedata")

    // Shorter chunks mean less footage is ever in the "not yet uploaded" gap —
    // worth it even though it roughly doubles chunk/manifest-revision count.
    private let chunkSeconds: Double = 15

    /// The active rear device, retained so its `videoZoomFactor` can be ramped
    /// live for gapless lens changes.
    private var activeBackDevice: AVCaptureDevice?
    /// Current effective rear-lens multiplier in the Camera-app sense
    /// (0.5 = ultra-wide, 1.0 = wide). Reported to the UI via `onLensChanged`.
    private(set) var currentLensMultiplier: Double = 0.5
    /// Called on the main queue whenever the effective lens changes.
    var onLensChanged: ((Double) -> Void)?

    // Data-queue state
    private var backWriter: ChunkWriter?
    private var frontWriter: ChunkWriter?
    /// One writer per camera taking the same frames as the chunk writers, but
    /// never rotated — so when recording stops, the whole session already
    /// exists as one file and only has to be closed.
    ///
    /// This is what the system Camera app gets for free from
    /// `AVCaptureMovieFileOutput`, and what this app gave up by slicing its
    /// recording into uploadable pieces: closing a file is instant, whereas
    /// concatenating half an hour of chunks is not, and a backgrounded app has
    /// roughly thirty seconds before iOS suspends it. Recordings were reaching
    /// Photos on the next launch instead of immediately, and a long enough one
    /// never reached it at all.
    ///
    /// The cost is a second hardware encode of every frame, per camera, which
    /// is why `AppSettings.sessionVideoStrategy` only allows it within a
    /// throughput budget. Heavier settings keep merging after the fact.
    private var liveSessionWriters: [CameraSource: ChunkWriter] = [:]
    private var liveSessionWriterAttempted: Set<CameraSource> = []
    /// One renderer per camera, not shared: each caches its last-drawn text
    /// image by (text, frame size), and back/front frames interleaving
    /// through a single shared renderer would invalidate that cache on
    /// every frame instead of once a second.
    private var overlayRenderers: [CameraSource: TimestampOverlayRenderer] = [:]
    private var writesSessionVideoLive = false
    /// Finalized live session videos, collected on the data queue as each
    /// writer completes and handed to `onSessionFinalized` once they all have.
    private var finishedSessionVideos: [(camera: String, url: URL)] = []
    /// Frames the capture system discarded before they ever reached a writer,
    /// per camera — the load signal that matters, since it counts what the
    /// pipeline as a whole could not keep up with.
    private var systemDroppedFrames: [CameraSource: Int] = [:]
    /// How hard the last recording leaned on the hardware, for diagnostics.
    /// Plain counters rather than a formatted string on purpose: these are
    /// written on the data queue and read by `rotationSummary()` off it, and a
    /// word-sized value read mid-update is merely stale, where a String would
    /// not be.
    private var lastSessionUsedLiveWriter = false
    private var lastSessionSystemDrops = 0
    private var lastSessionRefusedFrames = 0
    private var dualMode = false
    /// Two-lens mode: the two streams are the rear 1× and 0.5× lenses, not
    /// rear and front. Implies `dualMode` — the same two output slots, writers
    /// and rotation rules — and only changes which cameras feed them and what
    /// each stream is called.
    private var rearPairMode = false
    /// Why two-lens mode is or isn't running, for Diagnostics — it falls back
    /// silently by design, so without this a fallback looks like a bug.
    private var rearPairStatus = "off (not requested)"
    private var rearPairFormats = ""
    /// Stealth landscape: upright phone, a tall 4:3 format, and a 16:9
    /// landscape strip cut from each frame before it is written.
    private var stealthActive = false
    private var stealthStrip = ""
    /// Data-queue state: consecutive crop failures, and whether cropping has
    /// been abandoned for the rest of this recording (see `stealthFramed`).
    private var stealthCropFailures = 0
    private var stealthBroken = false
    private let cropper = FrameCropper()
    /// What the stream in a given slot is called. In ordinary dual mode the
    /// first slot is the rear camera and the second the front; in two-lens
    /// mode they are the 1× and the 0.5×.
    private func cameraLabel(secondSlot: Bool) -> CameraSource {
        if rearPairMode { return secondSlot ? .ultraWide : .wide }
        return secondSlot ? .front : .back
    }
    private var previewEnabled = false
    private var recordingSessionID = UUID()
    private var chunkIndex = 0
    private var isCapturing = false
    private var chunkStartLocation: CLLocation?
    /// The shape of the frames each camera is actually delivering — which is
    /// the shape of the file, since writers are sized from them. Recorded for
    /// the diagnostics readout: the device format alone does not say how the
    /// rotation left it, and "is it cropped or just a different shape" is not
    /// answerable by looking at the footage. Data-queue writes, read on the
    /// session queue; a torn read here would only misprint a diagnostic.
    private var deliveredDimensions: [CameraSource: CMVideoDimensions] = [:]

    // MARK: - Public control

    func start() {
        sessionQueue.async { [self] in
            // If camera-shaping settings changed since the session was built,
            // rebuild it so the new lens / dual choice takes effect this
            // recording rather than only after an app restart.
            if isConfigured && configuredSignature != Self.currentSettingsSignature() {
                if session.isRunning { session.stopRunning() }
                isConfigured = false
            }
            configureIfNeeded()
            // Each recording starts at the preferred default lens, even when a
            // prior recording left the live control on a different setting.
            // Not in two-lens mode: there the 1× lens is simply the 1× lens,
            // and a "starting lens" preference has nothing to choose between.
            if let device = activeBackDevice, !rearPairMode {
                applyLensImmediately(Self.startingLens(), to: device)
            }
            // Re-asserted each recording: the session is configured once and
            // reused, and a connection rebuilt in between would come back at
            // AVFoundation's default rather than ours.
            applyCaptureRotation(backVideoOutput, position: .back)
            if dualMode {
                applyCaptureRotation(frontVideoOutput, position: .front)
            }
            Task { await EventLogger.shared.log(
                "capture.rotation_locked", detail: self.rotationSummary()) }
            // Re-published: configuration ran before the angle was applied, so
            // the readout would otherwise show the previous recording's.
            if let device = activeBackDevice {
                publishDiagnostics(device: device, output: backVideoOutput, dual: dualMode)
            }
            let dual = dualMode
            dataQueue.sync {
                recordingSessionID = UUID()
                // Joins the active crew, if any, before a single piece exists,
                // so the first manifest revision already says so.
                CrewStore.stampSession(recordingSessionID)
                chunkIndex = 0
                stealthCropFailures = 0
                stealthBroken = false
                isCapturing = true
                liveSessionWriters.removeAll()
                liveSessionWriterAttempted.removeAll()
                finishedSessionVideos.removeAll()
                systemDroppedFrames.removeAll()
                // Decided once, here, rather than per frame: a settings change
                // part-way through a recording must not leave one camera
                // writing a session video and the other not.
                //
                // Skipped outright when nothing wants a single session video,
                // because then the second encode would buy nothing at all.
                writesSessionVideoLive = AppSettings.wantsSessionVideo
                    && AppSettings.sessionVideoStrategy(cameras: dual ? 2 : 1) == .live
                let strategy = writesSessionVideoLive ? "live" : "merge"
                Task { await EventLogger.shared.log(
                    "capture.session_video_strategy",
                    detail: "\(strategy) · \(AppSettings.videoResolution.rawValue) "
                        + "\(AppSettings.videoFrameRate.rawValue)fps "
                        + "\(AppSettings.videoCodec.rawValue) "
                        + "cameras=\(dual ? 2 : 1)") }
            }
            if !session.isRunning { session.startRunning() }
        }
    }

    /// Rebuilds the session ahead of time when the capture settings have
    /// changed, instead of leaving it to the moment record is pressed.
    ///
    /// Switching to a 4K, 60 fps or Apple Log format takes AVFoundation a
    /// second or two — tolerable while somebody is browsing settings, not at
    /// the start of an encounter, which is the one moment this app cannot
    /// afford to be slow.
    func prepare() {
        sessionQueue.async { [self] in
            if isConfigured && configuredSignature != Self.currentSettingsSignature() {
                if session.isRunning { session.stopRunning() }
                isConfigured = false
            }
            configureIfNeeded()
        }
    }

    /// Stops capture, finalizing the in-flight chunk(s) first.
    func stop(completion: (() -> Void)? = nil) {
        sessionQueue.async { [self] in
            dataQueue.sync { isCapturing = false }
            dataQueue.async { [self] in
                rotateChunkLocked(sessionEnding: true)
                sessionQueue.async { [self] in
                    if session.isRunning { session.stopRunning() }
                    completion?()
                }
            }
        }
    }

    /// Finalizes the current chunk(s) without stopping the session — used when
    /// the app is backgrounded so already-captured footage becomes uploadable
    /// immediately.
    func flushCurrentChunk() {
        dataQueue.async { [self] in
            rotateChunkLocked()
        }
    }

    /// Enables/disables delivery of live frames to `onPreviewFrame`.
    func setPreviewEnabled(_ enabled: Bool) {
        dataQueue.async { [self] in previewEnabled = enabled }
    }

    /// Which discrete lens multipliers the active rear device can reach. Used
    /// to decide whether to show the viewfinder lens control at all (a device
    /// with no ultra-wide can only do 1×).
    func availableLensMultipliers() -> [Double] {
        guard let device = activeBackDevice, !rearPairMode else { return [1.0] }
        return Self.canReachUltraWide(device) ? [0.5, 1.0] : [1.0]
    }

    /// Ramps the rear lens to `multiplier` (0.5 or 1.0) with no interruption to
    /// recording — the video stream stays continuous, so there is no gap in the
    /// footage. On a virtual device (dual/triple camera) the ramp crosses the
    /// real ultra-wide↔wide handoff optically, just like the Camera app; on a
    /// single physical lens it is a smooth digital zoom.
    func setLensMultiplier(_ multiplier: Double) {
        sessionQueue.async { [self] in
            guard let device = activeBackDevice else { return }
            let target = Self.zoomFactor(uiMultiplier: multiplier, for: device)
            do {
                try device.lockForConfiguration()
                device.ramp(toVideoZoomFactor: target, withRate: 12.0)
                device.unlockForConfiguration()
                currentLensMultiplier = multiplier
                DispatchQueue.main.async { [self] in onLensChanged?(multiplier) }
                Task { await EventLogger.shared.log(
                    "capture.lens_changed", detail: "\(multiplier)x zoom=\(String(format: "%.2f", target))") }
            } catch {
                Task { await EventLogger.shared.log(
                    "capture.lens_change_failed", detail: error.localizedDescription) }
            }
        }
    }

    /// Sets the lens instantly (no ramp) — used to apply the starting lens at
    /// configuration time before frames flow.
    private func applyLensImmediately(_ multiplier: Double, to device: AVCaptureDevice) {
        let target = Self.zoomFactor(uiMultiplier: multiplier, for: device)
        if (try? device.lockForConfiguration()) != nil {
            device.videoZoomFactor = target
            device.unlockForConfiguration()
        }
        currentLensMultiplier = multiplier
        DispatchQueue.main.async { [self] in onLensChanged?(multiplier) }
    }

    // MARK: - Session configuration

    private var isConfigured = false
    /// The camera-shaping settings the current session was built with, so a
    /// later change (lens preference, dual on/off) can trigger a rebuild.
    private var configuredSignature = ""

    private static func currentSettingsSignature() -> String {
        "\(AppSettings.lensPreference.rawValue)|\(AppSettings.dualCameraEnabled)"
        + "|\(AppSettings.videoResolution.rawValue)|\(AppSettings.videoFrameRate.rawValue)"
        + "|\(AppSettings.logRecordingEnabled)|\(AppSettings.videoCodec.rawValue)"
            + "|\(AppSettings.stabilization.rawValue)"
        + "|\(AppSettings.dualLensEnabled)|\(AppSettings.stealthLandscapeEnabled)"
    }

    private func configureIfNeeded() {
        guard !isConfigured else { return }
        // Without this `UIDevice.current.orientation` is always `.unknown`, and
        // the rotation guard above would fall back to portrait every time.
        if !UIDevice.current.isGeneratingDeviceOrientationNotifications {
            DispatchQueue.main.async {
                UIDevice.current.beginGeneratingDeviceOrientationNotifications()
            }
        }

        // Pro capture and dual-camera can't coexist: multi-cam formats top out
        // well below 4K/60 and don't offer Apple Log, so honouring both would
        // mean quietly discarding whichever the user cares about more.
        let wantDual = AppSettings.dualCameraEnabled
            && Self.isDualCameraSupported
            && !AppSettings.usesProCapture
            && !AppSettings.stealthLandscapeEnabled
        var configured = false
        rearPairMode = false
        stealthActive = false
        stealthStrip = ""
        if !AppSettings.dualLensEnabled {
            rearPairStatus = UserDefaults.standard.bool(forKey: AppSettings.dualLensKey)
                ? "off (needs Pro)" : "off (not requested)"
        } else if AppSettings.usesProCapture {
            rearPairStatus = "off (a 4K, 60 fps, Log or ProRes setting is selected)"
        } else if !Self.isRearPairSupported {
            rearPairStatus = "off (this iPhone can't run both rear lenses at once)"
        }

        // Two lenses beat the front camera when both are asked for — the
        // setting is a deliberate, Pro-only choice — and fall back to
        // whatever else is configured when this device or format can't do it.
        if AppSettings.dualLensEnabled && !AppSettings.usesProCapture
            && !AppSettings.stealthLandscapeEnabled,
           let wide = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
           let ultra = AVCaptureDevice.default(.builtInUltraWideCamera, for: .video, position: .back),
           Self.isRearPairSupported {
            do {
                try configureMultiCamRearPair(wide: wide, ultra: ultra)
                dualMode = true
                rearPairMode = true
                configured = true
                rearPairStatus = "on · \(rearPairFormats)"
                logChosenCamera(wide, dual: true)
                // Re-published: the configure step reported before the mode
                // and status above were set.
                publishDiagnostics(device: wide, output: backVideoOutput, dual: true)
            } catch {
                rearPairStatus = "failed, recording one lens instead (\(error))"
                Task { await EventLogger.shared.log(
                    "capture.rear_pair_fallback", detail: "\(error)") }
            }
        }

        // Both paths prefer the ultra-wide (0.5×) rear lens and fall back to
        // wide (1×) — for dual, because not every device supports ultra-wide
        // simultaneously with the front camera; for single, because not every
        // device has an ultra-wide lens at all.
        if wantDual && !configured {
            for back in Self.rearCandidates() where !configured {
                if (try? configureMultiCam(back: back)) != nil {
                    dualMode = true
                    configured = true
                    logChosenCamera(back, dual: true)
                }
            }
            if !configured {
                Task { await EventLogger.shared.log("capture.dual_fallback") }
            }
        }
        if !configured {
            for back in Self.singleCamCandidates() where !configured {
                if (try? configureSingleCam(back: back)) != nil {
                    dualMode = false
                    configured = true
                    logChosenCamera(back, dual: false)
                }
            }
        }

        guard configured else {
            DispatchQueue.main.async { [self] in
                // Eagerly localized: this crosses into a plain-String closure
                // parameter, so it can't be localized downstream as a literal.
                onError?(String(localized: "Camera setup failed. Check camera permission in Settings."))
            }
            return
        }
        registerObservers()
        isConfigured = true
        configuredSignature = Self.currentSettingsSignature()
        let dual = dualMode
        DispatchQueue.main.async { [self] in onConfigured?(dual) }
    }

    private func configureSingleCam(back: AVCaptureDevice) throws {
        let session = AVCaptureSession()
        session.beginConfiguration()
        // The session configures `activeColorSpace` itself unless told not to,
        // and it re-applies that choice on commit and on start — overwriting
        // Apple Log after every attempt to set it, however many times and at
        // whatever point it was assigned. This is why the device reported HLG
        // on a format that offers Log, with Log requested and every other
        // gate open. Ownership of the colour space has to be taken from the
        // session before the device will keep what it is given.
        // Taken only when Log needs it: everywhere else the session's own
        // wide-colour handling is the better default and stays in charge.
        session.automaticallyConfiguresCaptureDeviceForWideColor =
            !AppSettings.logRecordingEnabled
        // 4K is Pro-gated upstream; fall back to 1080p if this device or
        // session can't actually take the preset.
        let wants4K = AppSettings.videoResolution == .uhd4K
        if wants4K, session.canSetSessionPreset(.hd4K3840x2160) {
            session.sessionPreset = .hd4K3840x2160
        } else {
            session.sessionPreset = .hd1920x1080
        }

        let backOut = AVCaptureVideoDataOutput()
        let audioOut = AVCaptureAudioDataOutput()

        do {
            let backInput = try AVCaptureDeviceInput(device: back)
            guard session.canAddInput(backInput) else { throw CaptureError.config("video input") }
            session.addInput(backInput)

            guard let mic = AVCaptureDevice.default(for: .audio) else {
                throw CaptureError.config("microphone")
            }
            let micInput = try AVCaptureDeviceInput(device: mic)
            guard session.canAddInput(micInput) else { throw CaptureError.config("audio input") }
            session.addInput(micInput)

            backOut.setSampleBufferDelegate(self, queue: dataQueue)
            backOut.alwaysDiscardsLateVideoFrames = false
            guard session.canAddOutput(backOut) else { throw CaptureError.config("video output") }
            session.addOutput(backOut)

            audioOut.setSampleBufferDelegate(self, queue: dataQueue)
            guard session.canAddOutput(audioOut) else { throw CaptureError.config("audio output") }
            session.addOutput(audioOut)

            applyCaptureRotation(backOut, position: .back)
            // Order matters: picking the format first means the frame-rate
            // lock and the color space are both evaluated against the format
            // actually in use.
            try applyProFormat(back)
            // Stealth landscape takes over the format choice: it needs the
            // sensor's tall 4:3 shape to cut a landscape strip from. Not
            // with Apple Log, whose 10-bit frames the crop doesn't handle.
            if AppSettings.stealthLandscapeEnabled && !AppSettings.logRecordingEnabled {
                stealthActive = (try? applyStealthFormat(back)) == true
            }
            try lockFrameRate(back, fps: Int32(AppSettings.videoFrameRate.rawValue))
            applyLogColorSpaceIfRequested(back)
            // Last: which pixel formats the output can vend depends on the
            // format and color space settled above.
            requestTenBitOutputIfNeeded(backOut)
            // After the format is settled: which modes exist depends on it.
            setStandardStabilization(backOut, format: back.activeFormat)
        } catch {
            session.commitConfiguration()
            throw error
        }

        session.commitConfiguration()
        adopt(session: session, back: backOut, front: nil, audio: audioOut)
        activeBackDevice = back
        applyLensImmediately(Self.startingLens(), to: back)
        // Re-asserted after the session has committed and the lens applied:
        // this colour space has been overwritten by a later stage before, and
        // it is cheap to insist. The diagnostics below read the final state.
        applyLogColorSpaceIfRequested(back, quiet: true)
        makeHardwarePreviewIfNeeded(session: session, output: backOut)
        publishDiagnostics(device: back, output: backOut)
        Self.rearStabilizationSupport = Self.supportedStabilization(for: back.activeFormat)
    }

    /// What the last rear format could do, read by Settings.
    nonisolated(unsafe) static var rearStabilizationSupport: Set<AppSettings.Stabilization>?

    /// Reports what the camera was *actually* configured to do, read back from
    /// the device rather than from the settings that were asked for.
    ///
    /// Apple Log has several independent ways to end up not running — the
    /// subscription, the codec, the hardware, the chosen camera, the chosen
    /// format — and each of them previously failed the same way: footage that
    /// simply wasn't flat. This makes the outcome legible instead.
    /// What each camera's connection is *actually* set to, read back from the
    /// connection rather than from the value we asked for.
    ///
    /// Front-camera orientation has now been wrong in four different ways
    /// across four attempts, every one of them reasoned rather than seen — the
    /// Simulator has no camera, so nothing here can be checked locally. This
    /// prints the two numbers that decide it, plus whether the connection is
    /// mirroring, because a mirrored connection reverses the sense of rotation
    /// on its own and would make a correction for that a double negative.
    private func rotationSummary() -> String {
        func describe(_ output: AVCaptureVideoDataOutput, _ name: String) -> String? {
            guard let c = output.connection(with: .video) else { return nil }
            return "\(name) \(Int(c.videoRotationAngle))°\(c.isVideoMirrored ? " mirrored" : "")"
        }
        var parts = [describe(backVideoOutput, "rear")].compactMap { $0 }
        if dualMode, let front = describe(frontVideoOutput, "front") { parts.append(front) }

        // What was actually written last time, as opposed to what was asked
        // for. A rotated frame is transposed, never cropped, so these numbers
        // settle whether a recording that looks cropped really is.
        let written = [cameraLabel(secondSlot: false), cameraLabel(secondSlot: true)]
            .compactMap { camera -> String? in
            guard let d = deliveredDimensions[camera] else { return nil }
            let shape = d.width > d.height ? "landscape" : (d.width < d.height ? "portrait" : "square")
            return "\(camera.rawValue) \(d.width)x\(d.height) \(shape)"
        }
        let device = UIDevice.current.orientation
        let held: String
        switch device {
        case .portrait: held = "upright"
        case .portraitUpsideDown: held = "upside down"
        case .landscapeLeft: held = "on its left side"
        case .landscapeRight: held = "on its right side"
        case .faceUp: held = "face up"
        case .faceDown: held = "face down"
        default: held = "unknown"
        }
        var summary = "Rotation: \(parts.joined(separator: " · ")) · phone \(held)"
        if !written.isEmpty { summary += "\nWrote: \(written.joined(separator: " · "))" }
        // How the last recording actually ran, which is the only way to tell
        // whether writing the session video live is costing frames on this
        // device at these settings. Both counts should read zero.
        if lastSessionUsedLiveWriter {
            summary += "\nSession video: written live · dropped \(lastSessionSystemDrops)"
                + " · encoder behind \(lastSessionRefusedFrames)"
        }
        return summary
    }

    private func publishDiagnostics(device: AVCaptureDevice,
                                    output: AVCaptureVideoDataOutput,
                                    dual: Bool = false) {
        let d = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        var colorSpace: String
        switch device.activeColorSpace {
        case .appleLog: colorSpace = "Apple Log"
        case .P3_D65: colorSpace = "P3"
        case .sRGB: colorSpace = "sRGB"
        case .HLG_BT2020: colorSpace = "HLG"
        // Plain `default`, not `@unknown default`: .appleLog2 is gated behind
        // a newer iOS than this target and so cannot be listed as a case here.
        default: colorSpace = "other"
        }
        if #available(iOS 26.0, *), device.activeColorSpace == .appleLog2 {
            colorSpace = "Apple Log 2"
        }
        let pixel = (output.videoSettings[kCVPixelBufferPixelFormatTypeKey as String] as? OSType)
            == kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange ? "10-bit" : "8-bit"
        let frameSeconds = CMTimeGetSeconds(device.activeVideoMinFrameDuration)
        let fps = frameSeconds > 0 ? Int((1.0 / frameSeconds).rounded()) : 0
        let logCapable = device.activeFormat.supportedColorSpaces.contains(.appleLog)
        let text = """
        Pro active: \(AppSettings.isProEntitled ? "yes" : "no")
        Codec in use: \(AppSettings.videoCodec.rawValue)
        Log switched on: \(UserDefaults.standard.bool(forKey: AppSettings.logRecordingKey) ? "yes" : "no")
        Log actually requested: \(AppSettings.logRecordingEnabled ? "yes" : "no")
        Camera: \(device.localizedName)\(dual ? " (dual)" : "")
        Two lenses: \(rearPairStatus)
        Stealth landscape: \(stealthActive ? "on · " + stealthStrip : (AppSettings.stealthLandscapeEnabled ? "off (Apple Log is on, or no 4:3 format)" : "off"))
        Lens: \(Self.canReachUltraWide(device) ? "0.5× reachable" : "1× only") · \
        now at \(String(format: "%.2f", device.videoZoomFactor))×
        \(d.width)×\(d.height) · \(fps) fps · \(pixel)
        Colour space: \(colorSpace)
        This format offers Log: \(logCapable ? "yes" : "no")
        This device offers Log: \(Self.deviceSupportsAppleLog ? "yes" : "no")
        \(rotationSummary())
        """
        Task { await EventLogger.shared.log(
            "capture.configured", detail: text.replacingOccurrences(of: "\n", with: " | ")) }
        DispatchQueue.main.async { [self] in onDiagnostics?(text) }
    }

    /// Drives the viewfinder from the session itself when the video output is
    /// vending 10-bit buffers.
    ///
    /// `AVSampleBufferDisplayLayer` renders those as black — the viewfinder
    /// went dark in exactly the modes that request them, Log and ProRes, while
    /// H.264 was unaffected. `AVCaptureVideoPreviewLayer` takes its frames from
    /// the capture pipeline instead and handles Log and 10-bit natively, which
    /// is what the system camera does. Used only when needed: the sample-buffer
    /// path stays in charge everywhere it already works, including dual camera,
    /// which this can't serve.
    private func makeHardwarePreviewIfNeeded(session: AVCaptureSession,
                                             output: AVCaptureVideoDataOutput) {
        let tenBit = (output.videoSettings[kCVPixelBufferPixelFormatTypeKey as String] as? OSType)
            == kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
        guard tenBit else {
            DispatchQueue.main.async { [self] in onHardwarePreview?(nil) }
            return
        }
        DispatchQueue.main.async { [self] in
            let layer = AVCaptureVideoPreviewLayer(session: session)
            // The whole frame rather than a screen-filling crop of it. Unlike
            // the sample-buffer viewfinder this layer is session-backed, with
            // no frames here to measure, so it cannot choose per frame the way
            // `CaptureViewModel.fit` does — and a 10-bit recording is the one
            // most likely to be framed deliberately.
            layer.videoGravity = .resizeAspect
            if let connection = layer.connection,
               connection.isVideoRotationAngleSupported(90) {
                connection.videoRotationAngle = 90
            }
            onHardwarePreview?(layer)
        }
    }

    private func configureMultiCam(back: AVCaptureDevice) throws {
        guard let front = AVCaptureDevice.default(.builtInWideAngleCamera,
                                                  for: .video, position: .front) else {
            throw CaptureError.config("front camera")
        }
        let session = AVCaptureMultiCamSession()
        session.beginConfiguration()

        let backOut = AVCaptureVideoDataOutput()
        let frontOut = AVCaptureVideoDataOutput()
        let audioOut = AVCaptureAudioDataOutput()

        do {
            // Back camera (no auto-connection; wired manually below).
            let backInput = try AVCaptureDeviceInput(device: back)
            guard session.canAddInput(backInput) else { throw CaptureError.config("back input") }
            session.addInputWithNoConnections(backInput)
            try setMultiCamFormat(back)

            // Front camera.
            let frontInput = try AVCaptureDeviceInput(device: front)
            guard session.canAddInput(frontInput) else { throw CaptureError.config("front input") }
            session.addInputWithNoConnections(frontInput)
            try setMultiCamFormat(front)

            // Microphone.
            guard let mic = AVCaptureDevice.default(for: .audio) else {
                throw CaptureError.config("microphone")
            }
            let micInput = try AVCaptureDeviceInput(device: mic)
            guard session.canAddInput(micInput) else { throw CaptureError.config("audio input") }
            session.addInputWithNoConnections(micInput)

            // Outputs (no connections).
            for output in [backOut, frontOut] {
                output.setSampleBufferDelegate(self, queue: dataQueue)
                output.alwaysDiscardsLateVideoFrames = false
                guard session.canAddOutput(output) else { throw CaptureError.config("video output") }
                session.addOutputWithNoConnections(output)
            }
            audioOut.setSampleBufferDelegate(self, queue: dataQueue)
            guard session.canAddOutput(audioOut) else { throw CaptureError.config("audio output") }
            session.addOutputWithNoConnections(audioOut)

            // Manual connections.
            try connect(backInput, device: back, mediaType: .video,
                        to: backOut, in: session, isVideo: true)
            try connect(frontInput, device: front, mediaType: .video,
                        to: frontOut, in: session, isVideo: true)
            try connect(micInput, device: mic, mediaType: .audio,
                        to: audioOut, in: session, isVideo: false)

            try lockFrameRate(back)
            try lockFrameRate(front)
        } catch {
            session.commitConfiguration() // balance beginConfiguration on failure
            throw error
        }

        session.commitConfiguration()
        adopt(session: session, back: backOut, front: frontOut, audio: audioOut)
        activeBackDevice = back
        // In dual mode the back is a physical lens; still apply the starting
        // zoom (digital beyond the lens's optical point) so the framing matches
        // the chosen lens and the live control has a consistent baseline.
        applyLensImmediately(Self.startingLens(), to: back)
        // Reported from this path too. Falling back to dual is itself a
        // finding worth seeing — it means nothing was asking for Pro capture.
        DispatchQueue.main.async { [self] in onHardwarePreview?(nil) }
        publishDiagnostics(device: back, output: backOut, dual: true)
    }

    /// Two rear lenses at once: the 1× wide in the first slot and the 0.5×
    /// ultra-wide in the second, each its own stream with the shared
    /// microphone. Built exactly like `configureMultiCam`, with the front
    /// camera swapped for the second rear lens; a session that would exceed
    /// the hardware's budget is refused here, so the caller falls back to a
    /// configuration that will actually run rather than one that stutters.
    private func configureMultiCamRearPair(wide: AVCaptureDevice,
                                           ultra: AVCaptureDevice) throws {
        let session = AVCaptureMultiCamSession()
        session.beginConfiguration()

        let wideOut = AVCaptureVideoDataOutput()
        let ultraOut = AVCaptureVideoDataOutput()
        let audioOut = AVCaptureAudioDataOutput()

        do {
            let wideInput = try AVCaptureDeviceInput(device: wide)
            guard session.canAddInput(wideInput) else { throw CaptureError.config("wide input") }
            session.addInputWithNoConnections(wideInput)
            try setMultiCamFormat(wide)

            let ultraInput = try AVCaptureDeviceInput(device: ultra)
            guard session.canAddInput(ultraInput) else { throw CaptureError.config("ultra-wide input") }
            session.addInputWithNoConnections(ultraInput)
            try setMultiCamFormat(ultra)

            guard let mic = AVCaptureDevice.default(for: .audio) else {
                throw CaptureError.config("microphone")
            }
            let micInput = try AVCaptureDeviceInput(device: mic)
            guard session.canAddInput(micInput) else { throw CaptureError.config("audio input") }
            session.addInputWithNoConnections(micInput)

            for output in [wideOut, ultraOut] {
                output.setSampleBufferDelegate(self, queue: dataQueue)
                output.alwaysDiscardsLateVideoFrames = false
                guard session.canAddOutput(output) else { throw CaptureError.config("video output") }
                session.addOutputWithNoConnections(output)
            }
            audioOut.setSampleBufferDelegate(self, queue: dataQueue)
            guard session.canAddOutput(audioOut) else { throw CaptureError.config("audio output") }
            session.addOutputWithNoConnections(audioOut)

            try connect(wideInput, device: wide, mediaType: .video,
                        to: wideOut, in: session, isVideo: true)
            try connect(ultraInput, device: ultra, mediaType: .video,
                        to: ultraOut, in: session, isVideo: true)
            try connect(micInput, device: mic, mediaType: .audio,
                        to: audioOut, in: session, isVideo: false)

            try lockFrameRate(wide)
            try lockFrameRate(ultra)
        } catch {
            session.commitConfiguration()
            throw error
        }

        session.commitConfiguration()

        // Two full-resolution rear streams can exceed what the hardware can
        // run at once. Step down, the way Apple's own multi-camera sample
        // does — the 0.5× first, so the main 1× view keeps its quality as
        // long as possible — each step a binned or smaller 16:9 format,
        // re-checking the cost after every one. Nothing has been adopted
        // yet, so giving up here leaves no trace.
        var steps = 0
        while session.hardwareCost > 1.0 && steps < 12 {
            session.beginConfiguration()
            let lowered = (try? stepDownFormat(ultra)) == true
                || (try? stepDownFormat(wide)) == true
            session.commitConfiguration()
            if !lowered { break }
            steps += 1
        }
        // Hardware cost above 1 is a hard limit: the session will not run.
        if session.hardwareCost > 1.0 {
            throw CaptureError.config(String(format:
                "two lenses exceed the hardware budget (cost %.2f)", session.hardwareCost))
        }
        // System pressure above 1 is a forecast of heat and power over a long
        // recording, not a refusal — iOS throttles if the phone really gets
        // hot, and the front+rear dual mode has always run without checking
        // it. Lowered where it costs little (the 0.5× stream only, so the
        // main 1× view keeps full quality), then accepted.
        var pressureSteps = 0
        while session.systemPressureCost > 1.0 && pressureSteps < 6 {
            session.beginConfiguration()
            let lowered = (try? stepDownFormat(ultra)) == true
            session.commitConfiguration()
            if !lowered { break }
            pressureSteps += 1
        }
        rearPairFormats = "1× \(Self.describeFormat(wide.activeFormat)), "
            + "0.5× \(Self.describeFormat(ultra.activeFormat))"
            + String(format: " · pressure %.2f", session.systemPressureCost)
        adopt(session: session, back: wideOut, front: ultraOut, audio: audioOut)
        activeBackDevice = wide
        currentLensMultiplier = 1.0
        DispatchQueue.main.async { [self] in onHardwarePreview?(nil) }
        publishDiagnostics(device: wide, output: wideOut, dual: true)
    }

    /// Moves `device` to the next cheaper multi-cam 16:9 format: the binned
    /// variant of its current size if it isn't binned yet, otherwise the
    /// next smaller size. Returns false when there is nothing cheaper left.
    /// Frame rate is re-locked afterwards, since a format change resets it.
    private func stepDownFormat(_ device: AVCaptureDevice) throws -> Bool {
        let current = device.activeFormat
        let cur = CMVideoFormatDescriptionGetDimensions(current.formatDescription)
        func dims(_ f: AVCaptureDevice.Format) -> CMVideoDimensions {
            CMVideoFormatDescriptionGetDimensions(f.formatDescription)
        }
        func isWide(_ f: AVCaptureDevice.Format) -> Bool {
            let d = dims(f); return abs(Double(d.width) / Double(max(d.height, 1)) - 16.0 / 9.0) < 0.05
        }
        func runs30(_ f: AVCaptureDevice.Format) -> Bool {
            f.videoSupportedFrameRateRanges.contains { $0.minFrameRate <= 30 && $0.maxFrameRate >= 30 }
        }
        let pool = device.formats.filter { $0.isMultiCamSupported && isWide($0) && runs30($0) }
        let next: AVCaptureDevice.Format?
        if !current.isVideoBinned,
           let binned = pool.first(where: { $0.isVideoBinned && dims($0).height == cur.height }) {
            next = binned
        } else {
            next = pool
                .filter { dims($0).height < cur.height }
                .max { a, b in
                    (dims(a).height, a.isVideoBinned ? 0 : 1) < (dims(b).height, b.isVideoBinned ? 0 : 1)
                }
        }
        guard let next else { return false }
        try device.lockForConfiguration()
        device.activeFormat = next
        device.unlockForConfiguration()
        try lockFrameRate(device)
        return true
    }

    private static func describeFormat(_ f: AVCaptureDevice.Format) -> String {
        let d = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
        return "\(d.width)×\(d.height)\(f.isVideoBinned ? " binned" : "")"
    }

    /// Adopts a successfully configured session and its outputs as the active
    /// ones. Only called on success, so a failed attempt's outputs are never
    /// retained or reused.
    private func adopt(session: AVCaptureSession,
                       back: AVCaptureVideoDataOutput,
                       front: AVCaptureVideoDataOutput?,
                       audio: AVCaptureAudioDataOutput) {
        self.session = session
        self.backVideoOutput = back
        self.frontVideoOutput = front ?? AVCaptureVideoDataOutput() // unused placeholder
        self.audioOutput = audio
    }

    private func connect(_ input: AVCaptureDeviceInput,
                         device: AVCaptureDevice,
                         mediaType: AVMediaType,
                         to output: AVCaptureOutput,
                         in session: AVCaptureMultiCamSession,
                         isVideo: Bool) throws {
        let ports = input.ports(for: mediaType,
                                sourceDeviceType: device.deviceType,
                                sourceDevicePosition: device.position)
        guard let port = ports.first else { throw CaptureError.config("\(mediaType.rawValue) port") }
        let connection = AVCaptureConnection(inputPorts: [port], output: output)
        guard session.canAddConnection(connection) else { throw CaptureError.config("connection") }
        session.addConnection(connection)
        if isVideo {
            let angle = captureRotationAngle(for: device.position)
            if connection.isVideoRotationAngleSupported(angle) {
                connection.videoRotationAngle = angle
            } else if connection.isVideoRotationAngleSupported(90) {
                connection.videoRotationAngle = 90
            }
            Self.applyStabilization(connection, format: device.activeFormat)
        }
    }

    /// Records which rear device and format were actually selected, so the
    /// resulting footage can be checked against what the device accepted.
    private func logChosenCamera(_ device: AVCaptureDevice, dual: Bool) {
        let lens: String
        switch device.deviceType {
        case .builtInTripleCamera: lens = "triple(virtual,0.5–2x)"
        case .builtInDualWideCamera: lens = "dualWide(virtual,0.5–1x)"
        case .builtInUltraWideCamera: lens = "ultraWide(0.5x)"
        default: lens = "wide(1x)"
        }
        let dims = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        let stabilized = backVideoOutput.connection(with: .video)
            .map { $0.activeVideoStabilizationMode != .off } ?? false
        Task {
            await EventLogger.shared.log(
                "capture.configured",
                detail: "\(dual ? "dual" : "single") rear=\(lens) \(dims.width)x\(dims.height) stabilized=\(stabilized)")
        }
    }

    /// Single-camera rear devices to try, best first. Prefers a *virtual*
    /// device (triple or dual-wide) whenever the user might want 0.5×, because
    /// a virtual device can cross the ultra-wide↔wide handoff by ramping the
    /// zoom factor — giving gapless, optical lens switching just like the
    /// Camera app. Falls back to physical lenses.
    static func singleCamCandidates() -> [AVCaptureDevice] {
        let triple = AVCaptureDevice.default(.builtInTripleCamera, for: .video, position: .back)
        let dualWide = AVCaptureDevice.default(.builtInDualWideCamera, for: .video, position: .back)
        let ultraWide = AVCaptureDevice.default(.builtInUltraWideCamera, for: .video, position: .back)
        let wide = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)

        let ordered: [AVCaptureDevice]
        switch AppSettings.lensPreference {
        case .auto, .ultraWide:
            ordered = [triple, dualWide, ultraWide, wide].compactMap { $0 }
        case .wide:
            // Still prefer a virtual device so the live lens control can reach
            // 0.5× if wanted, but recording starts at 1× (see startingLens()).
            ordered = [dualWide, triple, wide, ultraWide].compactMap { $0 }
        }

        // Apple Log is only ever offered by a *physical* camera's formats —
        // the virtual triple/dual devices, which are otherwise preferred
        // because they can switch lenses mid-recording, never advertise it.
        // Preferring them first meant Log could never engage no matter what
        // else was configured, and the only symptom was footage that wasn't
        // flat. When Log is on, a camera that can actually deliver it comes
        // first and the live lens control is given up for the recording.
        guard AppSettings.logRecordingEnabled else { return ordered }
        let capable = ordered.filter(supportsAppleLog)
        return capable + ordered.filter { !supportsAppleLog($0) }
    }

    /// Whether any of this camera's formats can record Apple Log.
    static func supportsAppleLog(_ device: AVCaptureDevice) -> Bool {
        device.formats.contains { logColorSpace(for: $0) != nil }
    }

    /// The Apple Log colour space a format offers, if any.
    ///
    /// There is more than one: newer hardware exposes `.appleLog2` and need
    /// not offer the original at all. Checking only for `.appleLog` meant a
    /// device that could record Log was treated as one that couldn't, and the
    /// colour space was skipped without a word.
    static func logColorSpace(for format: AVCaptureDevice.Format) -> AVCaptureColorSpace? {
        let spaces = format.supportedColorSpaces
        // Newest first: hardware that offers Log 2 is the hardware whose
        // pipeline is built around it.
        if #available(iOS 26.0, *), spaces.contains(.appleLog2) { return .appleLog2 }
        if spaces.contains(.appleLog) { return .appleLog }
        return nil
    }

    /// Physical rear lenses to try in dual-camera mode. Virtual devices are not
    /// usable in a multi-cam session, so 0.5× vs 1× here is a physical choice:
    ///  - `.auto`: prefer ultra-wide (0.5×), fall back to wide (1×) so both
    ///    cameras keep running.
    ///  - `.ultraWide`: 0.5× only (single 0.5× camera if it can't pair with the
    ///    front camera, rather than switching to 1×).
    ///  - `.wide`: 1× only.
    static func rearCandidates() -> [AVCaptureDevice] {
        let ultraWide = AVCaptureDevice.default(.builtInUltraWideCamera, for: .video, position: .back)
        let wide = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)

        switch AppSettings.lensPreference {
        case .auto:
            return [ultraWide, wide].compactMap { $0 }
        case .ultraWide:
            return ultraWide.map { [$0] } ?? [wide].compactMap { $0 }
        case .wide:
            return [wide].compactMap { $0 }
        }
    }

    /// The lens each recording should start at, from the user's preference.
    static func startingLens() -> Double {
        AppSettings.lensPreference == .wide ? 1.0 : 0.5
    }

    /// Whether this hardware can record Apple Log at all (iPhone 15 Pro and
    /// newer). Asked of the formats themselves rather than inferred from a
    /// model name, so it stays right on devices that don't exist yet.
    ///
    /// Exists so the Pro page can say "this iPhone can't do Apple Log"
    /// outright. Without it the capture layer silently stays in the standard
    /// colour space and the only symptom is footage that isn't flat — which
    /// is indistinguishable from the feature being broken.
    static let deviceSupportsAppleLog: Bool = {
        let types: [AVCaptureDevice.DeviceType] = [
            .builtInTripleCamera, .builtInDualWideCamera,
            .builtInDualCamera, .builtInWideAngleCamera,
        ]
        return AVCaptureDevice.DiscoverySession(
            deviceTypes: types, mediaType: .video, position: .back)
            .devices
            .contains(where: supportsAppleLog)
    }()

    /// Whether `device` can reach the 0.5× ultra-wide field of view.
    static func deviceHasUltraWideBase(_ device: AVCaptureDevice) -> Bool {
        switch device.deviceType {
        case .builtInUltraWideCamera, .builtInDualWideCamera, .builtInTripleCamera:
            return true
        default:
            return false
        }
    }

    /// Whether 0.5× is reachable *right now*, on the format currently active.
    ///
    /// Having an ultra-wide constituent is not the same as being able to use
    /// it: a virtual device keeps its ultra-wide for some formats and not
    /// others, and an Apple Log format is one that may not offer it. The
    /// device says so through `minAvailableVideoZoomFactor`, which is
    /// format-dependent — when the ultra-wide is out of reach it reports the
    /// 1× point, and a request for 0.5× lands on 1× with nothing to show that
    /// it was ignored.
    static func canReachUltraWide(_ device: AVCaptureDevice) -> Bool {
        deviceHasUltraWideBase(device) && device.minAvailableVideoZoomFactor < 2.0
    }

    /// Maps a Camera-app lens multiplier (0.5 / 1.0) to the device's
    /// `videoZoomFactor`. For a device with an ultra-wide constituent, the base
    /// zoom (1.0) is the 0.5× view and 2.0 is the 1× view — on a virtual device
    /// the system optically hands over to the wide lens at that point. A
    /// wide-only device can only be 1×.
    static func zoomFactor(uiMultiplier: Double, for device: AVCaptureDevice) -> CGFloat {
        guard deviceHasUltraWideBase(device) else { return 1.0 }
        return uiMultiplier <= 0.5 ? device.minAvailableVideoZoomFactor : 2.0
    }

    /// Picks the widest 4:3 format at the requested frame rate (falling back
    /// to 30 fps), for stealth landscape. Its width becomes the landscape
    /// strip's width once the phone is upright, so wider is sharper: 4032x3024
    /// gives a 3024x1700 landscape video. Returns false if none exists.
    private func applyStealthFormat(_ device: AVCaptureDevice) throws -> Bool {
        func dims(_ f: AVCaptureDevice.Format) -> CMVideoDimensions {
            CMVideoFormatDescriptionGetDimensions(f.formatDescription)
        }
        func runs(_ f: AVCaptureDevice.Format, _ fps: Double) -> Bool {
            f.videoSupportedFrameRateRanges.contains { $0.minFrameRate <= fps && $0.maxFrameRate >= fps }
        }
        let fourThree = device.formats.filter {
            let d = dims($0)
            return abs(Double(d.width) / Double(max(d.height, 1)) - 4.0 / 3.0) < 0.02
                && CMFormatDescriptionGetMediaSubType($0.formatDescription)
                    == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        }
        let fps = Double(AppSettings.videoFrameRate.rawValue)
        let pool = fourThree.filter { runs($0, fps) }.isEmpty ? fourThree.filter { runs($0, 30) }
                                                               : fourThree.filter { runs($0, fps) }
        // Widest first; among equals prefer a non-binned format for detail.
        guard let best = pool.max(by: { a, b in
            (dims(a).width, a.isVideoBinned ? 0 : 1) < (dims(b).width, b.isVideoBinned ? 0 : 1)
        }) else { return false }
        try device.lockForConfiguration()
        device.activeFormat = best
        device.unlockForConfiguration()
        let d = dims(best)
        let strip = FrameCropper.stripSize(width: Int(d.height), height: Int(d.width))
        stealthStrip = "\(strip.width)×\(strip.height) from \(d.width)×\(d.height)"
        return true
    }

    /// Selects a multi-cam-supported format for `device`, at ~1080p.
    ///
    /// The rear camera prefers true 16:9: without it the closest-by-height
    /// match is often 4:3, which yields squarish footage rather than HD.
    ///
    /// The front camera prefers the *widest field of view* instead, which on
    /// iPhone means its native 4:3. A 16:9 front format is a crop of that
    /// sensor, and the crop is felt hardest in landscape: the sensor delivers
    /// one fixed rectangle either way, so turning the phone turns a tall frame
    /// into a short one and a face goes from filling a fraction of the height
    /// to filling most of it. At 16:9 that is a 1.78x jump between
    /// orientations; at 4:3 it is 1.33x.
    ///
    /// It cannot be removed altogether without a square image, which is what
    /// Apple's own front camera does in hardware on recent iPhones and cannot
    /// be reproduced by rotating a rectangular stream.
    private func setMultiCamFormat(_ device: AVCaptureDevice, targetHeight: Int32 = 1080) throws {
        let candidates = device.formats.filter { $0.isMultiCamSupported }
        let wantsWidescreen = device.position != .front
        // Rank: preferred aspect first, then closest to the target height.
        func rank(_ format: AVCaptureDevice.Format) -> (Int, Int) {
            let d = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            let aspect = Double(d.width) / Double(max(d.height, 1))
            let target = wantsWidescreen ? 16.0 / 9.0 : 4.0 / 3.0
            let matchesAspect = abs(aspect - target) < 0.05 ? 0 : 1
            return (matchesAspect, abs(Int(d.height) - Int(targetHeight)))
        }
        guard let best = candidates.min(by: { rank($0) < rank($1) }) else { return }
        try device.lockForConfiguration()
        device.activeFormat = best
        device.unlockForConfiguration()
    }

    /// Selects an explicit format when the Pro settings ask for something the
    /// plain 1080p preset can't express (4K, 60fps, or Apple Log). Setting
    /// `activeFormat` supersedes `sessionPreset`, so this is skipped entirely
    /// at the free defaults and the preset stays in charge.
    ///
    /// Every step degrades rather than throws: a device that can't offer the
    /// requested combination keeps whatever the preset gave it, so recording
    /// still starts. Losing quality is recoverable, losing the recording of
    /// an encounter is not.
    private func applyProFormat(_ device: AVCaptureDevice) throws {
        let resolution = AppSettings.videoResolution
        let frameRate = AppSettings.videoFrameRate
        let fps = Double(frameRate.rawValue)
        let wantsLog = AppSettings.logRecordingEnabled
        // Anything other than the free defaults needs a format chosen by hand:
        // 24 and 25 fps are as much a departure from the preset as 60 is.
        guard resolution != .hd1080 || frameRate != .fps30 || wantsLog else { return }

        func dimensions(_ format: AVCaptureDevice.Format) -> CMVideoDimensions {
            CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        }
        func runsAtRequestedRate(_ format: AVCaptureDevice.Format) -> Bool {
            format.videoSupportedFrameRateRanges.contains {
                $0.minFrameRate <= fps && $0.maxFrameRate >= fps
            }
        }
        func doesLog(_ format: AVCaptureDevice.Format) -> Bool {
            Self.logColorSpace(for: format) != nil
        }

        let atRate = device.formats.filter(runsAtRequestedRate)
        let atRequestedSize = atRate.filter {
            let d = dimensions($0)
            return d.width == resolution.width && d.height == resolution.height
        }

        var chosen: AVCaptureDevice.Format?
        if wantsLog {
            chosen = atRequestedSize.first(where: doesLog)
            if chosen == nil {
                // Not every resolution offers Log. Keeping the resolution and
                // dropping Log is the one outcome to avoid: Log is what the
                // user explicitly switched on, and losing it silently is
                // exactly the failure this path exists to prevent. Give up
                // the resolution instead, and say so.
                chosen = atRate.filter(doesLog).max { dimensions($0).width < dimensions($1).width }
                if let chosen {
                    let d = dimensions(chosen)
                    Task { await EventLogger.shared.log(
                        "capture.log_resolution_changed",
                        detail: "\(resolution.rawValue) has no Log; using \(d.width)x\(d.height)") }
                }
            }
        }
        guard let best = chosen ?? atRequestedSize.first else {
            Task { await EventLogger.shared.log(
                "capture.pro_format_unavailable",
                detail: "\(resolution.rawValue)@\(Int(fps))fps log=\(wantsLog)") }
            return
        }
        try device.lockForConfiguration()
        device.activeFormat = best
        device.unlockForConfiguration()
    }

    /// Switches the device into Apple Log when asked for and supported
    /// (iPhone 15 Pro and newer). Silently left alone otherwise — the setting
    /// is offered without a hardware check in the UI, so this is where an
    /// unsupported device quietly stays in the standard color space.
    private func applyLogColorSpaceIfRequested(_ device: AVCaptureDevice, quiet: Bool = false) {
        guard AppSettings.logRecordingEnabled else {
            // Distinguishes "the user didn't ask" from "we couldn't": the
            // gate is Pro entitlement plus a 10-bit codec, and when either is
            // missing the only visible symptom is footage that isn't flat.
            if UserDefaults.standard.bool(forKey: AppSettings.logRecordingKey) {
                Task { await EventLogger.shared.log(
                    "capture.log_gated",
                    detail: "pro=\(AppSettings.isProEntitled) codec=\(AppSettings.videoCodec.rawValue)") }
            }
            return
        }
        guard let logSpace = Self.logColorSpace(for: device.activeFormat) else {
            Task { await EventLogger.shared.log(
                "capture.log_unsupported",
                detail: "format has no Apple Log at \(AppSettings.videoResolution.rawValue)") }
            return
        }
        guard (try? device.lockForConfiguration()) != nil else { return }
        // The step that was missing. AVFoundation manages HDR on its own by
        // default, and that management sets the colour space to HLG — after
        // Apple Log has been assigned, so Log was overwritten every time. The
        // device reported HLG on a format that offers Log, with Log
        // requested, which is exactly what this looks like. Automatic HDR and
        // Apple Log cannot both be in charge of the colour space.
        if device.activeFormat.isVideoHDRSupported {
            device.automaticallyAdjustsVideoHDREnabled = false
        }
        device.activeColorSpace = logSpace

        // Read back rather than assumed: this is the one line that decides
        // whether footage is actually flat, and a silent no-op here is
        // indistinguishable from the feature being broken.
        if device.activeColorSpace != logSpace, device.activeFormat.isVideoHDRSupported {
            // Some formats keep HDR switched on independently of the automatic
            // setting, and that alone is enough to hold the colour space at
            // HLG. Turning it off explicitly is the second and last lever.
            device.isVideoHDREnabled = false
            device.activeColorSpace = logSpace
        }
        let engaged = device.activeColorSpace == logSpace
        device.unlockForConfiguration()
        guard !quiet else { return }
        Task { await EventLogger.shared.log(
            engaged ? "capture.log_active" : "capture.log_failed") }
    }

    /// Asks the video output for 10-bit buffers when recording Log or ProRes.
    ///
    /// Without this the output vends its default 8-bit 420v, which would
    /// quantise the Log curve down to 8 bits before the encoder ever sees it
    /// — the highlight and shadow latitude Log is shot for would already be
    /// gone, no matter what the encoder is then told to do. ProRes gets the
    /// same treatment: feeding a 10-bit mastering codec 8-bit source would
    /// pay ProRes's full file-size cost for none of its precision.
    private func requestTenBitOutputIfNeeded(_ output: AVCaptureVideoDataOutput) {
        guard AppSettings.logRecordingEnabled || AppSettings.videoCodec.isProRes else { return }
        let tenBit = kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
        guard output.availableVideoPixelFormatTypes.contains(tenBit) else {
            Task { await EventLogger.shared.log("capture.no_10bit_format") }
            return
        }
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: tenBit]
    }

    private func lockFrameRate(_ device: AVCaptureDevice, fps: Int32 = 30) throws {
        try device.lockForConfiguration()
        defer { device.unlockForConfiguration() }
        let supports = device.activeFormat.videoSupportedFrameRateRanges.contains {
            $0.minFrameRate <= Double(fps) && $0.maxFrameRate >= Double(fps)
        }
        if supports {
            let duration = CMTime(value: 1, timescale: fps)
            device.activeVideoMinFrameDuration = duration
            device.activeVideoMaxFrameDuration = duration
        }
    }

    /// Every recording is portrait-shaped, whichever way the phone is held.
    ///
    /// Not a limitation — a decision, and the one that makes the viewfinder
    /// behave. The interface is locked to portrait, so the screen is always a
    /// tall rectangle. If the video were sometimes wide, the viewfinder would
    /// have to letterbox it, crop it, or turn it, and there is no fourth
    /// option. Fixing the shape removes the mismatch rather than managing it:
    /// the preview always fills, never crops, and never changes.
    ///
    /// Held sideways, this portrait frame takes in a wide slice of the world
    /// instead of a tall one. That is what the viewfinder shows and what gets
    /// recorded.
    ///
    /// What it costs, stated plainly: a recording made with the phone sideways
    /// plays back with the world on its side, because the frame is upright and
    /// the scene inside it is not. That has been reported as a bug, and it is
    /// the other half of this trade. The alternative — the frame following the
    /// phone — was tried for a week and left the viewfinder unpredictable in
    /// every case but one.
    ///
    /// Both cameras take the same angle. Splitting them was tried four ways
    /// and each was wrong in some orientation.
    private static let captureRotationDegrees: CGFloat = 90

    private func captureRotationAngle(for position: AVCaptureDevice.Position = .back) -> CGFloat {
        Self.captureRotationDegrees
    }

    /// Fixes a video connection's rotation for the whole recording.
    ///
    /// Deliberately a snapshot, not a subscription. Writers are sized from the
    /// frames they are actually handed and are rebuilt for every chunk, so
    /// rotating mid-session would produce chunks of two different shapes and
    /// the merge would letterbox or squash whichever half disagreed with the
    /// first. Orientation is therefore whatever the phone was doing when
    /// recording started, and stays there — which is also the better
    /// behaviour for a phone being carried through an encounter.
    private func applyCaptureRotation(_ output: AVCaptureVideoDataOutput,
                                      position: AVCaptureDevice.Position) {
        guard let connection = output.connection(with: .video) else { return }
        let angle = captureRotationAngle(for: position)
        if connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        } else if connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
        }
    }

    /// The stabilization chosen in Pro settings (Standard otherwise), or the
    /// nearest weaker mode the active format supports.
    private func setStandardStabilization(_ output: AVCaptureVideoDataOutput, format: AVCaptureDevice.Format?) {
        if let connection = output.connection(with: .video) {
            Self.applyStabilization(connection, format: format)
        }
    }

    static func applyStabilization(_ connection: AVCaptureConnection, format: AVCaptureDevice.Format?) {
        guard connection.isVideoStabilizationSupported else { return }
        let wanted = AppSettings.stabilization.candidates
        let mode = wanted.first { format?.isVideoStabilizationModeSupported($0) ?? ($0 == .standard) } ?? .off
        connection.preferredVideoStabilizationMode = mode
    }

    /// Which choices the rear camera's current format can actually do, for
    /// Settings to grey out the rest.
    static func supportedStabilization(for format: AVCaptureDevice.Format?) -> Set<AppSettings.Stabilization> {
        guard let format else { return [.off, .standard] }
        var out: Set<AppSettings.Stabilization> = [.off]
        for choice in AppSettings.Stabilization.allCases where choice != .off {
            if let first = choice.candidates.first, format.isVideoStabilizationModeSupported(first) { out.insert(choice) }
        }
        return out
    }

    private func registerObservers() {
        let center = NotificationCenter.default
        center.removeObserver(self)
        center.addObserver(self, selector: #selector(sessionWasInterrupted),
                           name: .AVCaptureSessionWasInterrupted, object: session)
        center.addObserver(self, selector: #selector(sessionInterruptionEnded),
                           name: .AVCaptureSessionInterruptionEnded, object: session)

        center.addObserver(self, selector: #selector(sessionRuntimeError),
                           name: .AVCaptureSessionRuntimeError, object: session)
    }

    private enum CaptureError: Error {
        case config(String)
    }

    // MARK: - Chunk rotation (data queue only)

    private func rotateChunkLocked(sessionEnding: Bool = false) {
        let sessionID = recordingSessionID
        let index = chunkIndex
        let location = chunkStartLocation

        let active: [(ChunkWriter, CameraSource)] =
            [(backWriter, cameraLabel(secondSlot: false)), (frontWriter, cameraLabel(secondSlot: true))]
            .compactMap { writer, camera in writer.map { ($0, camera) } }

        // One group covers the chunk writers and the live session writers
        // alike, so the session is not reported finished until every file it
        // produced is closed and in place.
        let group = sessionEnding ? DispatchGroup() : nil

        for (writer, camera) in active {
            group?.enter()
            let url = writer.outputURL
            let startedAt = writer.startedAt
            writer.finish { [weak self] endedAt in
                if let endedAt { // nil = empty/failed chunk, already removed
                    self?.onChunkFinalized?(FinalizedChunk(
                        fileURL: url, sessionID: sessionID, index: index, camera: camera,
                        startedAt: startedAt, endedAt: endedAt, location: location))
                }
                group?.leave()
            }
        }
        if !active.isEmpty { chunkIndex += 1 }

        backWriter = nil
        frontWriter = nil
        // Writers are recreated lazily from the next frame of each camera, so
        // their encoded size always matches the frames actually delivered.

        guard sessionEnding, let group else { return }
        finishLiveSessionWriters(sessionID: sessionID, group: group)
        group.notify(queue: dataQueue) { [weak self] in
            guard let self else { return }
            self.onSessionFinalized?(sessionID, self.finishedSessionVideos)
            self.finishedSessionVideos.removeAll()
        }
    }

    /// Closes the live session writers, moving each finished file off its
    /// `.partial` name so that its presence means the recording is whole.
    ///
    /// Anything that fails here is recoverable rather than lost: the partial is
    /// discarded and the camera simply reports no session video, which sends
    /// `SessionStitcher` back to merging that camera's chunks — they are still
    /// staged at this point and are only deleted once a session video exists.
    private func finishLiveSessionWriters(sessionID: UUID, group: DispatchGroup) {
        let writers = liveSessionWriters
        liveSessionWriters.removeAll()

        // Summed across cameras: the question these answer is whether the
        // hardware kept up, and it did not if either number is above zero.
        lastSessionUsedLiveWriter = !writers.isEmpty
        lastSessionSystemDrops = systemDroppedFrames.values.reduce(0, +)
        lastSessionRefusedFrames = writers.values.reduce(0) { $0 + $1.refusedVideoFrames }
        if lastSessionUsedLiveWriter {
            let drops = lastSessionSystemDrops
            let refused = lastSessionRefusedFrames
            Task { await EventLogger.shared.log(
                "capture.live_session_video_load",
                detail: "dropped=\(drops) refused=\(refused) cameras=\(writers.count)") }
        }
        republishDiagnostics()

        for (camera, writer) in writers {
            group.enter()
            let dest = SessionStitcher.shared.liveSessionVideoDestination(
                sessionID: sessionID, camera: camera.rawValue)
            writer.finish { [weak self] endedAt in
                guard let self else { group.leave(); return }
                self.dataQueue.async {
                    defer { group.leave() }
                    guard endedAt != nil else {
                        try? FileManager.default.removeItem(at: dest.partial)
                        Task { await EventLogger.shared.log(
                            "capture.live_session_video_failed",
                            detail: "\(sessionID.uuidString) \(camera.rawValue)") }
                        return
                    }
                    do {
                        try? FileManager.default.removeItem(at: dest.final)
                        try FileManager.default.moveItem(at: dest.partial, to: dest.final)
                        self.finishedSessionVideos.append((camera.rawValue, dest.final))
                    } catch {
                        try? FileManager.default.removeItem(at: dest.partial)
                        Task { await EventLogger.shared.log(
                            "capture.live_session_video_unplaceable",
                            detail: error.localizedDescription) }
                    }
                }
            }
        }
    }

    /// Builds a writer sized to `sampleBuffer`'s own dimensions. Deriving the
    /// size from the frame rather than the device format is what keeps footage
    /// undistorted: the capture connection rotates frames to portrait, so the
    /// delivered buffers are the transpose of the camera format's dimensions.
    private func makeWriter(camera: CameraSource,
                            sampleBuffer: CMSampleBuffer,
                            location: CLLocation?) -> ChunkWriter? {
        guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer) else { return nil }
        let dimensions = CMVideoFormatDescriptionGetDimensions(formatDescription)
        let name = String(format: "chunk_%@_%04d_%@.%@",
                          recordingSessionID.uuidString, chunkIndex, camera.rawValue,
                          AppSettings.videoCodec.fileExtension)
        let url = ChunkStore.chunksDirectory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url)
        return try? ChunkWriter(outputURL: url, location: location,
                                width: Int(dimensions.width), height: Int(dimensions.height))
    }

    /// Builds the per-camera live session writer, sized to the frame exactly
    /// as `makeWriter` is, and writing under a `.partial` name until it is
    /// finalized.
    private func makeLiveSessionWriter(camera: CameraSource,
                                       sampleBuffer: CMSampleBuffer,
                                       location: CLLocation?) -> ChunkWriter? {
        guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer) else { return nil }
        let dimensions = CMVideoFormatDescriptionGetDimensions(formatDescription)
        let dest = SessionStitcher.shared.liveSessionVideoDestination(
            sessionID: recordingSessionID, camera: camera.rawValue)
        try? FileManager.default.removeItem(at: dest.partial)
        do {
            return try ChunkWriter(outputURL: dest.partial, location: location,
                                   width: Int(dimensions.width), height: Int(dimensions.height))
        } catch {
            // Logged rather than swallowed: without this the feature would
            // simply never engage, and the merge path it falls back to looks
            // identical from the outside.
            Task { await EventLogger.shared.log(
                "capture.live_session_writer_unavailable",
                detail: "\(camera.rawValue): \(error.localizedDescription)") }
            return nil
        }
    }

    /// Burns the current timestamp and location into `sampleBuffer`'s pixel
    /// buffer, returning a new sample buffer wrapping the result — or nil on
    /// any failure along the way, so the caller can fall back to the
    /// original frame untouched rather than lose it.
    private func overlayedSampleBuffer(_ sampleBuffer: CMSampleBuffer,
                                       camera: CameraSource) -> CMSampleBuffer? {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return nil }
        let renderer = overlayRenderers[camera] ?? {
            let r = TimestampOverlayRenderer()
            overlayRenderers[camera] = r
            return r
        }()
        let text = TimestampOverlayRenderer.overlayText(
            date: Date(), location: LocationService.shared.lastLocation,
            branded: !AppSettings.isProEntitled)
        guard let overlaid = renderer.render(pixelBuffer, text: text) else { return nil }
        return Self.rewrap(sampleBuffer, with: overlaid)
    }

    /// The landscape strip for stealth landscape, the frame untouched when
    /// it's off, or nil to drop this one frame.
    ///
    /// A failed crop drops the frame rather than passing the full-size one
    /// through: a writer sized for the strip can't take a frame of another
    /// size mid-file. If cropping keeps failing, it is abandoned for the rest
    /// of the recording and the chunk rotated, so new writers are sized for
    /// full frames and recording carries on uncropped — never stops.
    private func stealthFramed(_ sampleBuffer: CMSampleBuffer, isFront: Bool) -> CMSampleBuffer? {
        guard stealthActive, !stealthBroken, !isFront else { return sampleBuffer }
        if let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer),
           let strip = cropper.crop(pixelBuffer),
           let wrapped = Self.rewrap(sampleBuffer, with: strip) {
            stealthCropFailures = 0
            return wrapped
        }
        stealthCropFailures += 1
        if stealthCropFailures >= 10 {
            stealthBroken = true
            Task { await EventLogger.shared.log("capture.stealth_crop_abandoned") }
            rotateChunkLocked()
        }
        return nil
    }

    /// A new sample buffer carrying `pixelBuffer` with `original`'s timing.
    private static func rewrap(_ original: CMSampleBuffer, with pixelBuffer: CVPixelBuffer) -> CMSampleBuffer? {
        let overlaid = pixelBuffer
        let sampleBuffer = original
        var timingInfo = CMSampleTimingInfo()
        guard CMSampleBufferGetSampleTimingInfo(sampleBuffer, at: 0, timingInfoOut: &timingInfo) == noErr
        else { return nil }

        var formatDescription: CMVideoFormatDescription?
        guard CMVideoFormatDescriptionCreateForImageBuffer(
            allocator: nil, imageBuffer: overlaid, formatDescriptionOut: &formatDescription) == noErr,
            let formatDescription
        else { return nil }

        var newSampleBuffer: CMSampleBuffer?
        let status = CMSampleBufferCreateForImageBuffer(
            allocator: nil, imageBuffer: overlaid, dataReady: true,
            makeDataReadyCallback: nil, refcon: nil,
            formatDescription: formatDescription, sampleTiming: &timingInfo,
            sampleBufferOut: &newSampleBuffer)
        guard status == noErr else { return nil }
        return newSampleBuffer
    }

    private func republishDiagnostics() {
        sessionQueue.async { [self] in
            guard let device = activeBackDevice else { return }
            publishDiagnostics(device: device, output: backVideoOutput, dual: dualMode)
        }
    }

    // MARK: - Interruptions

    @objc private func sessionWasInterrupted(_ note: Notification) {
        // Phone call, Camera app, etc. Finalize what we have so it uploads
        // even if the interruption never ends.
        Task { await EventLogger.shared.log("capture.interrupted") }
        flushCurrentChunk()
    }

    @objc private func sessionInterruptionEnded(_ note: Notification) {
        Task { await EventLogger.shared.log("capture.interruption_ended") }
        // Session resumes delivering buffers; new writers spin up on the next
        // video frame automatically.
    }

    @objc private func sessionRuntimeError(_ note: Notification) {
        let error = note.userInfo?[AVCaptureSessionErrorKey] as? AVError
        Task { await EventLogger.shared.log("capture.runtime_error",
                                            detail: error?.localizedDescription) }
        flushCurrentChunk()
        // Media-services reset and similar transient failures: try to keep going.
        sessionQueue.async { [self] in
            var stillCapturing = false
            dataQueue.sync { stillCapturing = isCapturing }
            if stillCapturing && !session.isRunning {
                session.startRunning()
            }
        }
    }
}

// MARK: - Sample buffer delegates

extension CaptureSessionController: AVCaptureVideoDataOutputSampleBufferDelegate,
                                    AVCaptureAudioDataOutputSampleBufferDelegate {
    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard isCapturing else { return }

        // Audio feeds both writers so each camera's file is independently usable.
        if output === audioOutput {
            backWriter?.append(sampleBuffer, isVideo: false)
            frontWriter?.append(sampleBuffer, isVideo: false)
            for writer in liveSessionWriters.values {
                writer.append(sampleBuffer, isVideo: false)
            }
            return
        }

        let isFront = output === frontVideoOutput
        let camera = cameraLabel(secondSlot: isFront)

        // Swapped in before anything downstream sees this frame — the
        // writer, the live session writer, and the viewfinder all end up
        // showing/recording the same, already-overlaid picture, rather than
        // the viewfinder showing a clean frame while the file gets a
        // watermarked one. Falls back to the original untouched frame on
        // any failure, so a rendering problem can never itself interrupt
        // recording — at worst the overlay for that one frame is missing.
        guard let framed = stealthFramed(sampleBuffer, isFront: isFront) else { return }
        let sampleBuffer = AppSettings.timestampBurnInEnabled
            ? (overlayedSampleBuffer(framed, camera: camera) ?? framed)
            : framed

        if let format = CMSampleBufferGetFormatDescription(sampleBuffer) {
            deliveredDimensions[camera] = CMVideoFormatDescriptionGetDimensions(format)
        }

        // Each camera's writer is created on that camera's first frame of the
        // chunk, sized to that frame.
        if (isFront ? frontWriter : backWriter) == nil {
            if backWriter == nil && frontWriter == nil {
                chunkStartLocation = LocationService.shared.lastLocation
            }
            let writer = makeWriter(camera: camera, sampleBuffer: sampleBuffer,
                                    location: chunkStartLocation)
            if isFront { frontWriter = writer } else { backWriter = writer }
        }

        // The session video takes the same frames, into a writer that is never
        // rotated — so it is complete, bar closing, at every instant of the
        // recording. Created on this camera's first frame and kept for the
        // whole session.
        if writesSessionVideoLive {
            // Attempted once per camera, not once per frame: a writer that
            // cannot be created will not become creatable, and retrying thirty
            // times a second would spend the recording failing.
            if liveSessionWriterAttempted.insert(camera).inserted {
                liveSessionWriters[camera] = makeLiveSessionWriter(
                    camera: camera, sampleBuffer: sampleBuffer, location: chunkStartLocation)
            }
            liveSessionWriters[camera]?.append(sampleBuffer, isVideo: true)
        }

        if let writer = isFront ? frontWriter : backWriter {
            writer.append(sampleBuffer, isVideo: true)

            // The back camera is the reference clock; when it fills a chunk,
            // both cameras rotate together to keep the two streams aligned.
            if !isFront, writer.elapsedSeconds >= chunkSeconds {
                rotateChunkLocked()
            }
        }

        if previewEnabled {
            onPreviewFrame?(sampleBuffer, camera)
        }
    }

    /// Frames the capture system threw away rather than delivering — because
    /// the data queue was still busy with the previous one.
    ///
    /// This is the measurement that decides whether a second encode per camera
    /// is affordable, and it is the reason this delegate method exists at all:
    /// dropped frames are invisible in the footage (it simply runs slightly
    /// short) so without counting them there is no way to tell a configuration
    /// that fits from one that does not.
    func captureOutput(_ output: AVCaptureOutput,
                       didDrop sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        guard isCapturing, output !== audioOutput else { return }
        let camera = cameraLabel(secondSlot: output === frontVideoOutput)
        systemDroppedFrames[camera, default: 0] += 1
    }
}
