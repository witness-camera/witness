import Foundation
import SwiftUI
import AVFoundation
import Combine

@MainActor
final class CaptureViewModel: ObservableObject {
    enum State: Equatable {
        case idle
        case recording(since: Date)
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published var lowStorageWarning: String?
    /// Set instead of `state = .failed(...)` when camera or microphone access
    /// was already denied once. iOS never shows its own permission dialog a
    /// second time — `requestAccess` just returns `false` immediately with no
    /// UI — so a plain error message here would be a dead end. Home turns
    /// this into an alert with a direct link to the app's Settings page,
    /// which is the only way left to actually grant it.
    @Published var permissionSettingsPromptNeeded = false
    /// Whether the live viewfinder is shown instead of a black screen.
    ///
    /// Deliberately not persisted anywhere — no `AppSettings` key backs
    /// this. It exists so Witness can double as an ordinary camera on top
    /// of being a safety tool, but that convenience must never outrank the
    /// safety default: every fresh launch starts black-screen regardless of
    /// what was chosen last time, because a plain in-memory `false` here is
    /// what it starts as. Set from Home's own toggle before recording (see
    /// `setViewfinderPreference`), and still flippable mid-recording via the
    /// existing eye-icon control, which affects only the rest of that one
    /// recording.
    @Published private(set) var viewfinderVisible = false
    /// Whether the front camera is shown full-screen with the rear as the
    /// inset, instead of the default rear-main arrangement. Only meaningful
    /// in dual-camera mode. Not persisted, same reasoning as
    /// `viewfinderVisible` and reset alongside it at the start of every
    /// recording — a per-session choice for someone actively using dual
    /// capture, never a state that quietly carries over.
    @Published private(set) var mainViewSwapped = false
    /// Whether both cameras are actually recording (drives PiP visibility).
    @Published private(set) var dualActive = false
    /// Current rear-lens multiplier shown in the viewfinder control
    /// (0.5 = ultra-wide, 1.0 = wide).
    @Published private(set) var lensMultiplier: Double = CaptureSessionController.startingLens()
    /// Whether the device can switch to 0.5× (drives whether the lens control
    /// is shown at all).
    @Published private(set) var lensControlAvailable = false

    var isRecording: Bool {
        if case .recording = state { return true }
        return false
    }

    /// When the current recording began, for the viewfinder's elapsed-time
    /// readout. Nil whenever not recording, so the view has one thing to
    /// check rather than reaching into `state` itself.
    var recordingStartedAt: Date? {
        if case .recording(let since) = state { return since }
        return nil
    }

    /// Display layers for the optional viewfinder. The back camera fills the
    /// screen; the front camera is a small picture-in-picture.
    let backPreviewLayer = AVSampleBufferDisplayLayer()
    let frontPreviewLayer = AVSampleBufferDisplayLayer()
    /// Set when the viewfinder has to come from the capture session directly
    /// (10-bit Log/ProRes, which the sample-buffer layer renders black).
    @Published private(set) var hardwarePreviewLayer: AVCaptureVideoPreviewLayer?
    /// What the camera was actually configured to do, for the Pro page.
    @Published private(set) var captureDiagnostics: String = ""

    private let controller = CaptureSessionController()
    private var savedBrightness: CGFloat?
    private var storageTimer: Timer?

    init() {
        // `.resizeAspect`, always. The viewfinder shows the whole frame at its
        // own proportions — it never crops to fill the screen, and it never
        // changes which it does. Filling crops; switching between the two
        // means the viewfinder behaves differently depending on how the phone
        // was held when recording began, which is worse than either.
        //
        // Where the frame's shape and the screen's differ, the remainder is
        // screen, not something taken out of the video. Nothing is hidden and
        // nothing moves.
        backPreviewLayer.videoGravity = .resizeAspect
        frontPreviewLayer.videoGravity = .resizeAspect

        controller.onConfigured = { [weak self] dual in
            self?.dualActive = dual
            self?.lensControlAvailable = self?.controller.availableLensMultipliers().count ?? 0 > 1
        }
        controller.onLensChanged = { [weak self] multiplier in
            self?.lensMultiplier = multiplier
        }
        controller.onPreviewFrame = { [weak self] sampleBuffer, camera in
            self?.enqueuePreview(sampleBuffer, camera: camera)
        }
        controller.onHardwarePreview = { [weak self] layer in
            self?.hardwarePreviewLayer = layer
        }
        controller.onDiagnostics = { [weak self] text in
            self?.captureDiagnostics = text
        }
        controller.onChunkFinalized = { chunk in
            if AppSettings.isLocalOnlyCapture {
                // Nothing is uploading (ProRes, uploads off, or no account
                // connected), so nothing downstream will ever confirm receipt
                // and delete this file. Stage the hardlink that the merge
                // reads from, note the piece's timing and place for the
                // session's record, then drop the original immediately —
                // otherwise every chunk would be retained alongside the
                // library copy until the device filled up.
                SessionStitcher.shared.stage(chunk: chunk)
                LocalSessionRecord.noteChunk(chunk)
                try? FileManager.default.removeItem(at: chunk.fileURL)
                return
            }
            // Stage a hardlink for later stitching before the uploader can
            // delete the file on confirmed receipt, then hand the chunk to the
            // upload pipeline. Staging is skipped only when *neither* the
            // Photos backup nor the Drive-stitch feature wants a merged video —
            // Drive's per-chunk upload is unaffected either way.
            if AppSettings.photosBackupEnabled || AppSettings.driveStitchEnabled {
                SessionStitcher.shared.stage(chunk: chunk)
            }
            UploadManager.shared.ingest(chunk)
        }
        controller.onSessionFinalized = { sessionID, sessionVideos in
            // Produce one video per camera — saved to Photos and/or handed to
            // the Drive-stitch pipeline depending on which of those features
            // are enabled. A camera that wrote its session video live during
            // the recording hands it straight over; the rest are merged from
            // the staged chunks as before.
            //
            // The completion here — not this closure's own start — is what
            // tells BackgroundSaveNotifier the recording is genuinely done,
            // since for a merge this fires only once the merge itself (and
            // the Photos save it may trigger) has actually finished.
            // Filed under "Not in a case" at once, so Home can offer to add
            // details while the video is still being finished.
            // A local-only session has no queued records; its pieces were
            // noted by LocalSessionRecord instead.
            let records = ChunkStore.shared.records(forSession: sessionID).sorted { $0.startedAt < $1.startedAt }
            let local = LocalSessionRecord.chunks(for: sessionID).sorted { $0.startedAt < $1.startedAt }
            let startedAt = records.first?.startedAt ?? local.first?.startedAt
            let endedAt = records.map(\.endedAt).max() ?? local.map(\.endedAt).max() ?? Date()
            let located = records.first { $0.latitude != nil }
            let locatedLocal = local.first { $0.latitude != nil }
            let latitude = located?.latitude ?? locatedLocal?.latitude
            let longitude = located?.longitude ?? locatedLocal?.longitude
            let cameras = Set(records.filter { $0.kind == .master }.map(\.camera.rawValue)
                              + local.map(\.camera)).sorted()
            Task { @MainActor in
                CaseLibrary.shared.registerRecording(
                    sessionID: sessionID, startedAt: startedAt, endedAt: endedAt,
                    latitude: latitude, longitude: longitude)
                // A crew recording goes straight into the crew's case and onto
                // its timeline, under the folder name its footage is sent to.
                if let stamp = CrewStore.stamp(for: sessionID), let startedAt {
                    if let caseID = CrewStore.shared.crew(stamp.crewID)?.caseID,
                       let item = CaseLibrary.shared.items.first(where: { $0.sessionID == sessionID }) {
                        CaseLibrary.shared.move(item.id, to: caseID)
                    }
                    let folder = await SessionFolderNaming.folderName(
                        sessionID: sessionID, date: startedAt, latitude: latitude, longitude: longitude)
                    CrewStore.shared.addRecording(
                        sessionID: sessionID, stamp: stamp, cameras: cameras.isEmpty ? ["back"] : cameras,
                        startedAt: startedAt, endedAt: endedAt, folderName: folder)
                }
            }
            SessionStitcher.shared.finishSession(sessionID, liveVideos: sessionVideos) {
                Task { @MainActor in BackgroundSaveNotifier.shared.disarm() }
            }
            // A local-only session has nothing in Drive for a manifest to
            // describe, so Drive is left untouched entirely rather than
            // receiving a receipt for footage that was never sent.
            guard !AppSettings.isLocalOnlyCapture else { return }
            // Write and upload the session's single chain-of-custody manifest.
            UploadManager.shared.finalizeSession(sessionID)
        }
        // Fired once a session's per-camera merge is ready for (or still
        // awaiting) upload — including at launch, for a merge left pending by
        // a prior app lifetime.
        SessionStitcher.shared.onSessionMerged = { sessionID, merges in
            UploadManager.shared.registerMergedVideos(sessionID: sessionID, merges: merges)
        }
        controller.onError = { [weak self] message in
            self?.state = .failed(message)
            self?.restoreScreenAndIdleTimer()
        }

        let center = NotificationCenter.default
        center.addObserver(forName: UIApplication.didEnterBackgroundNotification,
                           object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.isRecording else { return }
                await EventLogger.shared.log("app.backgrounded_while_recording")
                // Armed optimistically; disarmed the moment the session
                // actually finishes (see onSessionFinalized above). Most
                // recordings finish well within the delay and this never
                // reaches the user at all — it's only for the recording
                // still being merged when iOS suspends the app.
                BackgroundSaveNotifier.shared.armIfNeeded()
                // Ended, not paused: the same path as pressing stop.
                //
                // The camera stops delivering frames the moment this app is
                // backgrounded, so a "paused" recording was already over — it
                // simply had not been finalized, which meant no merge, no save
                // to Photos, and nothing to show for it until the app happened
                // to be opened again. A journalist lost an afternoon to a
                // session left in that state.
                //
                // It also covers being force-quit, which has no callback of
                // its own: reaching the app switcher backgrounds the app
                // first, so by the time anyone swipes up, this has run and the
                // merge is already under way.
                self.stopRecording()
            }
        }
        center.addObserver(forName: UIApplication.didBecomeActiveNotification,
                           object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.isRecording else { return }
                // Reapply screen state in case the system restored brightness.
                self.applyScreenMode()
                await EventLogger.shared.log("app.foregrounded_while_recording")
            }
        }
    }

    /// Rebuilds the capture session now if the settings changed, so pressing
    /// record stays instant. Safe to call whenever settings might have been
    /// edited — it does nothing when nothing changed.
    func prepareCamera() {
        guard !isRecording else { return }
        controller.prepare()
    }

    // MARK: - Viewfinder

    /// Toggles the live viewfinder on/off during (or before) recording and
    /// remembers the choice as the default for next time.
    func toggleViewfinder() {
        viewfinderVisible.toggle()
        controller.setPreviewEnabled(viewfinderVisible)
        if !viewfinderVisible { flushPreviewLayers() }
        applyScreenMode()
    }

    /// Sets the pre-recording camera-vs-safety choice from Home's own
    /// toggle. No screen/preview side effects — those only mean anything
    /// once actually recording, which `toggleViewfinder` above still
    /// handles for mid-recording changes.
    func setViewfinderPreference(_ isOn: Bool) {
        viewfinderVisible = isOn
    }

    /// Swaps which camera is the main view and which is the small inset.
    /// Only shown to the user while both the viewfinder is on and dual
    /// capture is active — meaningless otherwise, since there is only one
    /// view to have an inset over.
    /// The most recent moment mark, for the brief on-screen confirmation.
    @Published private(set) var lastMarkAt: Date?

    /// Marks this moment of the recording (Pro). Silent apart from a light
    /// haptic, so it can be done with the screen black or the phone in a
    /// pocket, by the volume button.
    func addMarker() {
        guard isRecording, ProEntitlement.shared.isPro else { return }
        let now = Date()
        MomentMarkers.add(now)
        lastMarkAt = now
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    func toggleMainViewSwap() {
        mainViewSwapped.toggle()
    }


    // MARK: - Lens

    /// Toggles the rear lens between 0.5× and 1× live, with no gap in the
    /// recording. Only meaningful when `lensControlAvailable`.
    func toggleLens() {
        let next = lensMultiplier <= 0.5 ? 1.0 : 0.5
        lensMultiplier = next            // optimistic; controller confirms via callback
        controller.setLensMultiplier(next)
    }

    private func enqueuePreview(_ sampleBuffer: CMSampleBuffer, camera: CameraSource) {
        // The display layer with no control timebase renders frames live as
        // they arrive. The buffer is not mutated here — it may still be held by
        // the asset-writer encoder on the capture queue.
        // The second stream (front, or the 0.5× lens) is the small inset.
        let layer = (camera == .front || camera == .ultraWide) ? frontPreviewLayer : backPreviewLayer
        DispatchQueue.main.async {
            if layer.status == .failed { layer.flush() }
            layer.enqueue(sampleBuffer)
        }
    }


    private func flushPreviewLayers() {
        backPreviewLayer.flushAndRemoveImage()
        frontPreviewLayer.flushAndRemoveImage()
    }

    // MARK: - Start / stop

    func startRecording() async {
        guard !isRecording else { return }

        // `requestAccess` only ever puts up the system dialog when the
        // status is still `.notDetermined`; once someone has answered
        // "Don't Allow" — here or back in onboarding — every later call
        // resolves to `false` instantly with no UI at all. Checking first
        // means a first-time "not asked yet" tap still gets the real system
        // prompt below, while an already-denied tap goes straight to the
        // Settings deep link instead of silently failing again.
        let cameraStatus = AVCaptureDevice.authorizationStatus(for: .video)
        let micStatus = AVCaptureDevice.authorizationStatus(for: .audio)
        guard cameraStatus != .denied, cameraStatus != .restricted,
              micStatus != .denied, micStatus != .restricted else {
            permissionSettingsPromptNeeded = true
            return
        }

        let cameraOK = await AVCaptureDevice.requestAccess(for: .video)
        let micOK = await AVCaptureDevice.requestAccess(for: .audio)
        guard cameraOK && micOK else {
            // Eagerly localized here: `state` carries a plain String through
            // to HomeView's Text(message), which (being a variable, not a
            // literal at that point) can't be localized after the fact.
            state = .failed(String(localized: "Camera or microphone permission denied. Enable both in Settings."))
            return
        }

        configureAudioSession()
        LocationService.shared.startUpdating()

        UIApplication.shared.isIdleTimerDisabled = true
        // Not reset here: whatever Home's toggle is currently showing is
        // exactly what this recording should start with.
        controller.setPreviewEnabled(viewfinderVisible)
        // Unlike viewfinderVisible, this one does start fresh every
        // recording — it has no Home-screen control setting it in advance,
        // so a swap chosen last time has nowhere to be read from.
        mainViewSwapped = false
        applyScreenMode()

        // The preview layers still hold the last frame of the previous
        // session. If the session has to be rebuilt for changed Pro settings
        // that frame sits there for the second or two it takes, which reads
        // as the app having frozen rather than as the camera starting up.
        flushPreviewLayers()
        controller.start()
        state = .recording(since: Date())
        startStorageMonitor()

        let location = LocationService.shared.lastLocation
        Task { await EventLogger.shared.log("recording.start", location: location) }
        UploadManager.shared.recordingDidStart()
    }

    func stopRecording() {
        guard isRecording else { return }
        let location = LocationService.shared.lastLocation
        Task { await EventLogger.shared.log("recording.stop", location: location) }

        controller.stop {
            Task { @MainActor in
                UploadManager.shared.recordingDidStop()
            }
        }
        controller.setPreviewEnabled(false)
        flushPreviewLayers()
        LocationService.shared.stopUpdating()
        restoreScreenAndIdleTimer()
        stopStorageMonitor()
        state = .idle
    }

    func resetFailure() {
        if case .failed = state { state = .idle }
    }

    // MARK: - Screen / audio session

    /// Blacks out the screen when the viewfinder is hidden; restores normal
    /// brightness when it's shown so the user can see what they're filming.
    private func applyScreenMode() {
        if viewfinderVisible {
            restoreBrightness()
        } else {
            dimScreen()
        }
    }

    private func dimScreen() {
        if savedBrightness == nil {
            savedBrightness = UIScreen.main.brightness
        }
        UIScreen.main.brightness = 0.0
    }

    private func restoreBrightness() {
        if let brightness = savedBrightness {
            UIScreen.main.brightness = brightness
            savedBrightness = nil
        }
    }

    private func restoreScreenAndIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = false
        restoreBrightness()
    }

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .videoRecording,
                                 options: [.allowBluetoothHFP])
        try? session.setActive(true)
    }

    // MARK: - Storage monitoring (warn, never interrupt)

    private func startStorageMonitor() {
        checkStorage()
        storageTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { _ in
            Task { @MainActor [weak self] in self?.checkStorage() }
        }
    }

    private func stopStorageMonitor() {
        storageTimer?.invalidate()
        storageTimer = nil
        lowStorageWarning = nil
    }

    private func checkStorage() {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        guard let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
              let available = values.volumeAvailableCapacityForImportantUsage else { return }
        let availableMB = available / 1_000_000
        if availableMB < 500 {
            // Eagerly localized (see the .failed(...) case above for why).
            lowStorageWarning = String(localized: "Storage low (\(availableMB) MB free). Recording continues; uploaded chunks are deleted automatically to free space.")
            Task { await EventLogger.shared.log("storage.low", detail: "\(availableMB) MB") }
        } else {
            lowStorageWarning = nil
        }
    }
}
