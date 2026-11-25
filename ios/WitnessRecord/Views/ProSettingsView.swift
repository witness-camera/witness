import SwiftUI
import AVFoundation

/// Pro capture settings on their own page, reached from the PRO badge next to
/// the gear on the Home screen — kept out of Settings so that page doesn't
/// grow unwieldy, and so the capability is visible at the top level.
///
/// The controls are always shown, never hidden behind the purchase: they are
/// simply inert until the subscription is active, so a filmmaker can see
/// exactly what Pro does before paying for it.
///
/// Every choice states its own cost. The estimate line is the point of the
/// page: these settings differ by more than an order of magnitude in file
/// size, and the difference decides whether footage can leave the device at
/// all — so the numbers are shown rather than buried in a footnote.
struct ProSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var capture: CaptureViewModel
    @ObservedObject private var pro = ProEntitlement.shared

    @AppStorage(AppSettings.stabilizationKey) private var stabilization = AppSettings.Stabilization.standard.rawValue
    @AppStorage(AppSettings.videoResolutionKey) private var videoResolution = VideoResolution.hd1080.rawValue
    @AppStorage(AppSettings.videoFrameRateKey) private var videoFrameRate = VideoFrameRate.fps30.rawValue
    @AppStorage(AppSettings.videoCodecKey) private var videoCodec = VideoCodec.h264.rawValue
    @AppStorage(AppSettings.logRecordingKey) private var logRecording = false
    @AppStorage(AppSettings.pipExportKey) private var pipExport = false
    @AppStorage(AppSettings.highlightExportKey) private var highlightExport = false
    @AppStorage(AppSettings.blurExportKey) private var blurExport = false
    @AppStorage(AppSettings.voiceDisguiseKey) private var voiceDisguise = false
    @AppStorage(AppSettings.dualLensKey) private var dualLens = false
    @AppStorage(AppSettings.stealthLandscapeKey) private var stealthLandscape = false
    /// The same stored setting Settings edits — mirrored here, not gated, so
    /// framing can be chosen alongside the other capture choices without
    /// leaving the page. It stays free.
    @AppStorage(AppSettings.lensPreferenceKey) private var lensPreference = LensPreference.auto.rawValue

    @State private var showPaywall = false
    @State private var showProResWarning = false
    @State private var showEvidenceExport = false
    /// The codec to fall back to if the ProRes warning is declined, so the
    /// choice is genuinely reversible from inside the alert.
    @State private var codecBeforeProRes: String?

    private var resolution: VideoResolution {
        VideoResolution(rawValue: videoResolution) ?? .hd1080
    }
    private var frameRate: VideoFrameRate {
        VideoFrameRate(rawValue: videoFrameRate) ?? .fps30
    }
    private var codec: VideoCodec {
        VideoCodec(rawValue: videoCodec) ?? .h264
    }

    /// The settings currently ruling out simultaneous front+rear capture,
    /// listed individually so it is obvious which one to change. Read from the
    /// @AppStorage values above, so the notice appears the moment a picker
    /// changes rather than next launch, but decided by `AppSettings` so it
    /// cannot say the front camera is off when the capture layer disagrees.
    private var blockingDualCamera: [AppSettings.DualCameraBlocker] {
        AppSettings.dualCameraBlockers(
            resolution: resolution, frameRate: frameRate, codec: codec,
            logRecording: logRecording, isPro: pro.isPro)
    }

    private var usesProCapture: Bool { !blockingDualCamera.isEmpty }

    var body: some View {
        NavigationStack {
            Form {
                // A short silent loop of what Pro adds, shown in the product's
                // own screens. No audio track, so it can never interrupt
                // whatever the person is listening to.
                Section {
                    PromoVideoView()
                        .aspectRatio(4.0 / 3.0, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .accessibilityLabel("Witness Pro features: 4K at 60 fps in Apple Log, visible timestamp and location, both cameras in one video, both rear lenses at once, and highlight cuts.")
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)

                if !pro.isPro { unlockSection }

                Section {
                    Picker("Resolution", selection: $videoResolution) {
                        ForEach(VideoResolution.allCases) { value in
                            Text(value.label).tag(value.rawValue)
                        }
                    }
                    Picker("Frame rate", selection: $videoFrameRate) {
                        ForEach(VideoFrameRate.allCases) { value in
                            Text(value.label).tag(value.rawValue)
                        }
                    }
                    Picker("Codec", selection: $videoCodec) {
                        ForEach(VideoCodec.allCases) { value in
                            Text(value.label).tag(value.rawValue)
                        }
                    }
                } header: {
                    Text("Capture")
                } footer: {
                    codecFooter
                }
                .disabled(!pro.isPro)

                // Free, and deliberately outside the .disabled() block above:
                // framing is not a Pro feature, it is just useful to set in
                // the same place as the rest of the capture settings.
                Section {
                    Picker("Starting lens", selection: $lensPreference) {
                        ForEach(LensPreference.allCases) { pref in
                            Text(pref.label).tag(pref.rawValue)
                        }
                    }
                } header: {
                    Text("Framing")
                } footer: {
                    Text(lensFooter)
                }

                Section {
                    Picker("Stabilization", selection: $stabilization) {
                        ForEach(AppSettings.Stabilization.allCases) { choice in
                            Text(choice.label).tag(choice.rawValue)
                        }
                    }
                    .disabled(!pro.isPro)
                } footer: {
                    Text(stabilizationFooter)
                }

                Section {
                    Toggle("Apple Log", isOn: $logRecording)
                        .disabled(!pro.isPro || !codec.supportsLog
                                  || !CaptureSessionController.deviceSupportsAppleLog)
                } footer: {
                    Text(logFooter)
                }

                if CaptureSessionController.isDualCameraSupported {
                    Section {
                        Toggle("Combined picture-in-picture video", isOn: $pipExport)
                            .disabled(!pro.isPro)
                    } footer: {
                        Text("When recording both cameras, also produce one video with the front camera as a small inset over the rear one — in addition to, never instead of, the two separate videos you already get. Meant for sharing a single file rather than two. Adds noticeably to how long the recording takes to finish assembling afterward.")
                    }
                }

                Section {
                    Toggle("Stealth landscape", isOn: $stealthLandscape)
                        .disabled(!pro.isPro)
                } footer: {
                    Text("Record a landscape 16:9 video while holding the phone upright, the way you'd hold it for a call or a text. Witness uses the camera's taller sensor shape and keeps a landscape strip from the middle: on recent iPhones that is about 3024 × 1700, sharper than HD. The viewfinder shows exactly that strip. Single camera only, so the front camera and two-lens recording pause while it's on; not available with Apple Log.")
                }

                Section {
                    Toggle("Record both rear lenses", isOn: $dualLens)
                        .disabled(!pro.isPro || !CaptureSessionController.isRearPairSupported)
                } footer: {
                    if CaptureSessionController.isRearPairSupported {
                        Text("Records the 1× wide and 0.5× ultra-wide lenses at the same time, as two separate videos, so you can choose the framing afterwards. Both run at 1080p and 30 fps, so this switches itself off while Pro 4K, 60 fps, Apple Log or ProRes is chosen, and the front camera isn't recorded alongside.")
                            + (dualLens && usesProCapture
                               ? Text("\n\n") + Text("Off right now because of: ").foregroundColor(.orange)
                                 + blockingDualCameraText.foregroundColor(.orange) + Text(verbatim: ".").foregroundColor(.orange)
                               : Text(verbatim: ""))
                    } else {
                        Text("This iPhone can't run both rear lenses at once.")
                    }
                }

                Section {
                    Toggle("Blur faces and licence plates", isOn: $blurExport)
                        .disabled(!pro.isPro)
                    Toggle("Disguise voices in that copy", isOn: $voiceDisguise)
                        .disabled(!pro.isPro || !blurExport)
                } footer: {
                    Text("Also save a copy of each recording, and of the selfie camera's video when it is recording, with faces and licence plates blurred, ready to post without exposing bystanders. Detection runs entirely on this iPhone and nothing is uploaded to analyse it. It is automatic and can miss a face turned away or a plate at a steep angle, so check the copy before publishing. Your original recording is never changed.")
                        + Text("\n\n")
                        + Text("Voice disguise pitches voices down and reshapes their tone so speakers are hard to recognise while words stay clear. It deters ordinary listeners, not specialist analysis.")
                }

                Section {
                    Toggle("Highlight export", isOn: $highlightExport)
                        .disabled(!pro.isPro)
                } footer: {
                    Text("Also produce a shorter cut of the rear camera's video with the quiet dead air trimmed out and the stretches where someone is actually talking kept — in addition to, never instead of, the full recording you already get. Meant for sharing something watchable without asking someone to sit through the whole thing. Adds to how long the recording takes to finish assembling afterward.")
                }

                // Gated on the subscription like every other Pro consequence:
                // without it the stored 4K reverts to 1080p and there is
                // nothing to warn about.
                if pro.isPro && resolution == .uhd4K && !codec.isProRes { uploadRateSection }

                sizeSection

                if codec.isProRes { localOnlySection }

                if pro.isPro && usesProCapture { singleCameraSection }

                Section {
                    Button { showEvidenceExport = true } label: {
                        Label("Evidence packages", systemImage: "doc.text.magnifyingglass")
                    }
                } footer: {
                    Text("Turns a finished recording into a PDF report with its timeline, hashes and verification steps, alongside the manifest and time-stamp proof it describes.")
                }

                diagnosticsSection

                // A lifetime purchase has nothing to manage or cancel —
                // sending it to the same "Manage subscription" screen as an
                // active renewal would be misleading about what was bought.
                if pro.isPro && !pro.isLifetime { manageSection }
                if pro.isLifetime { lifetimeSection }
            }
            .navigationTitle("Witness Pro")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                Button("Done") { dismiss() }
            }
            // Re-checked on open, and again whenever the paywall closes: a
            // subscription bought on another device, restored, or still
            // settling when the purchase returned would otherwise leave this
            // page locked until the next launch.
            .task { await pro.refreshEntitlement() }
            .sheet(isPresented: $showPaywall) {
                Task { await pro.refreshEntitlement() }
            } content: {
                PaywallView()
            }
            .sheet(isPresented: $showEvidenceExport) { EvidenceExportView() }
            // Switching to ProRes silently turns off the upload the whole app
            // exists for, so it is confirmed explicitly rather than discovered
            // later from a footer nobody scrolled to.
            .onChange(of: videoCodec) { previous, current in
                let now = VideoCodec(rawValue: current) ?? .h264
                // H.264 can't carry Log, so leaving the stored flag set would
                // show the toggle on while nothing recorded in Log — and,
                // worse, keep the whole app in Pro-capture mode (no dual
                // camera) because of a setting that isn't doing anything.
                if !now.supportsLog { logRecording = false }
                let wasProRes = (VideoCodec(rawValue: previous) ?? .h264).isProRes
                guard now.isProRes, !wasProRes else { return }
                codecBeforeProRes = previous
                showProResWarning = true
            }
            .alert("Recordings won't upload to Drive",
                   isPresented: $showProResWarning) {
                Button("Use ProRes") { codecBeforeProRes = nil }
                Button("Cancel", role: .cancel) {
                    if let previous = codecBeforeProRes { videoCodec = previous }
                    codecBeforeProRes = nil
                }
            } message: {
                Text("ProRes is far too large to send live, so footage stays on this phone and is saved to Photos instead. It won't reach Drive, and your emergency contacts won't see it. Switch back to HEVC or H.264 to start uploading again.")
            }
        }
    }

    // MARK: - Sections

    private var unlockSection: some View {
        Section {
            Button { showPaywall = true } label: {
                HStack(spacing: 12) {
                    Image(systemName: "lock.fill")
                        .foregroundStyle(.red)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Unlock Witness Pro")
                            .font(.headline)
                            .foregroundStyle(.primary)
                        Text("4K, 60 fps and Apple Log")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.caption.bold())
                        .foregroundStyle(.tertiary)
                }
            }
        } footer: {
            Text("Built for documentary and investigative work. Everything in the free app stays free, Pro only adds capture quality.")
        }
    }

    private var sizeSection: some View {
        Section {
            LabeledContent("Every 15 seconds", value: chunkSizeText)
            LabeledContent("Every minute", value: minuteSizeText)
        } header: {
            Text("How much it weighs")
        } footer: {
            Text("Recording fills storage at this rate. Witness keeps recording when space runs low, but the device will eventually stop it.")
        }
    }

    /// What 4K costs on the way *out* of the phone, as opposed to on it.
    ///
    /// The size section below says how fast storage fills, which is the
    /// question people expect. It is not the one that decides whether the
    /// footage survives: a recording only counts once it has left, and 4K
    /// produces data faster than most mobile connections can send it. Stated
    /// as a rate to compare against a speed test, rather than "may be slow".
    private var uploadRateSection: some View {
        Section {
            Label("Needs a fast upload connection", systemImage: "antenna.radiowaves.left.and.right")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.orange)
            LabeledContent("Upload speed needed", value: requiredUploadRateText)
        } footer: {
            Text("4K records faster than it can be sent on most mobile networks — typical LTE gives 5 to 10 Mbps up, and 5G varies hugely by where you are standing. Below the rate above, the queue grows for as long as you keep recording and the last pieces arrive well after the encounter ends.")
            + Text("\n\n")
            + Text("Nothing is lost when that happens: Witness keeps every piece on the phone until your storage confirms it, and sends a small preview copy of each one ahead of the full file so something of every moment is safe early. But if getting footage out during the encounter is what matters, 1080p is the setting that does it.")
        }
    }

    /// The sustained upload rate the current settings actually demand, in the
    /// same Mbps a speed test reports.
    private var requiredUploadRateText: String {
        let megabitsPerSecond = codec.estimatedBitRate(
            resolution: resolution, frameRate: frameRate) / 1_000_000
        return String(format: "%.0f Mbps", megabitsPerSecond.rounded())
    }

    private var localOnlySection: some View {
        Section {
            Label("Stays on this device", systemImage: "iphone.and.arrow.forward.inward")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.orange)
        } footer: {
            Text("ProRes runs roughly twenty times the data rate of HEVC. Uploading it live would need more bandwidth than any mobile connection has, so a ProRes recording is never sent to Drive: it is merged and saved to your Photos library instead, and nothing leaves the phone.")
            + Text("\n\n")
            + Text("That trades away the protection the rest of the app is built on — footage surviving because it left the device during the encounter. Choose ProRes when picture quality matters more than that, and HEVC when it doesn't.")
        }
    }

    /// Why Apple Log will or won't be used, stated plainly. Silence here is
    /// what made a device that can't do Log look like a broken feature.
    private var stabilizationFooter: String {
        var text = String(localized: "Standard smooths small shakes with almost no crop. Cinematic is steadier and crops in more. Extreme is the steadiest, for walking or running, and crops in the most. Off keeps the full frame with no smoothing.")
        let choice = AppSettings.Stabilization(rawValue: stabilization) ?? .standard
        if let support = CaptureSessionController.rearStabilizationSupport, !support.contains(choice) {
            text += "\n\n" + String(localized: "Your current resolution and frame rate can't do this one, so the strongest mode they can do is used instead.")
        }
        return text
    }

    private var logFooter: LocalizedStringKey {
        if !CaptureSessionController.deviceSupportsAppleLog {
            return "This iPhone can't record Apple Log — that needs an iPhone 15 Pro or newer. Every other setting on this page still works."
        }
        if !codec.supportsLog {
            return "Apple Log needs a 10-bit codec. Choose HEVC or ProRes to enable it."
        }
        if !pro.isPro {
            return "Records flat footage keeping the full dynamic range for grading in post. Unlock Witness Pro to use it."
        }
        if logRecording {
            return "On. Footage will look flat and washed out straight out of the camera — that is what Log is: the contrast and colour are left for you to apply in grading."
        }
        return "Records flat footage keeping the full dynamic range for grading in post."
    }

    /// Joined with a literal separator rather than an interpolated string:
    /// interpolating would bake the names in as opaque runtime arguments that
    /// never go through their own translation lookup.
    private var blockingDualCameraText: Text {
        blockingDualCamera.enumerated().reduce(Text(verbatim: "")) { joined, item in
            joined + (item.offset == 0 ? Text(verbatim: "") : Text(verbatim: ", "))
                + Text(item.element.label(resolution: resolution, frameRate: frameRate, codec: codec))
        }
    }

    private var singleCameraSection: some View {
        Section {
            Label("Front camera is off", systemImage: "info.circle")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.orange)
            if !blockingDualCamera.isEmpty {
                LabeledContent("Because of") {
                    blockingDualCameraText
                        .multilineTextAlignment(.trailing)
                }
            }
            // Reverting one setting isn't enough — all four have to be back at
            // the free defaults before both cameras can run, which is not
            // obvious from changing them one at a time.
            Button("Restore dual-camera settings") { restoreDualCameraDefaults() }
        } footer: {
            Text("Both cameras at once is limited to 1080p, 30 fps and H.264, and can't do Apple Log. Anything above that takes priority and only the rear camera records.")
        }
    }

    private func restoreDualCameraDefaults() {
        videoResolution = VideoResolution.hd1080.rawValue
        videoFrameRate = VideoFrameRate.fps30.rawValue
        videoCodec = VideoCodec.h264.rawValue
        logRecording = false
    }

    /// What the chosen lens will actually do, including the case where Log has
    /// taken the choice away — stated here rather than leaving a disabled
    /// picker to explain itself.
    private var lensFooter: LocalizedStringKey {
        if logRecording {
            // Not a restriction the app imposes: some Apple Log formats keep
            // the ultra-wide lens and some don't, and only the device knows
            // which. The Diagnostics section below reports the lens actually
            // used, so a preference that couldn't be honoured is visible
            // rather than silently ignored.
            return "Apple Log formats don't always keep the 0.5× lens available. If it isn't, recording starts at 1× — Diagnostics below shows which lens was actually used."
        }
        switch LensPreference(rawValue: lensPreference) ?? .auto {
        case .auto:
            return "Recordings start at the 0.5× ultra-wide lens when the device supports it, otherwise 1×. With the viewfinder on you can switch 0.5×↔1× live while recording, with no gap in the footage."
        case .ultraWide:
            return "Recordings start at the 0.5× ultra-wide lens. With the viewfinder on you can switch 0.5×↔1× live while recording, with no gap in the footage."
        case .wide:
            return "Recordings start at the standard 1× lens. With the viewfinder on you can switch 0.5×↔1× live while recording, with no gap in the footage."
        }
    }

    /// The current settings state, available without recording anything.
    private var settingsStateText: String {
        """
        Pro active: \(pro.isPro ? "yes" : "no")
        Codec selected: \(codec.rawValue)
        Log switched on: \(logRecording ? "yes" : "no")
        This device offers Log: \(CaptureSessionController.deviceSupportsAppleLog ? "yes" : "no")
        """
    }

    /// What the camera actually did, rather than what was asked of it.
    /// Apple Log can fail for several unrelated reasons and they all look
    /// identical in the footage, so the outcome is stated here.
    private var diagnosticsSection: some View {
        Section {
            Text(verbatim: pro.productDiagnostics)
                .font(.footnote.monospaced())
                .foregroundStyle(pro.storeUnavailable ? .orange : .secondary)
                .textSelection(.enabled)
            Text(verbatim: settingsStateText)
                .font(.footnote.monospaced())
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
            Text(verbatim: pro.entitlementDiagnostics)
                .font(.footnote.monospaced())
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
            if capture.captureDiagnostics.isEmpty {
                Text("Start a recording once to see what the camera actually did.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Text(verbatim: capture.captureDiagnostics)
                    .font(.footnote.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
        } header: {
            Text("Diagnostics")
        } footer: {
            Text("Read back from the camera itself, not from the settings above. If Log is switched on but the colour space isn't Apple Log, this says which step didn't take.")
        }
    }

    private var manageSection: some View {
        Section {
            Button("Manage subscription") { openSubscriptionSettings() }
        } footer: {
            Text("Witness Pro is active. Manage or cancel it in your App Store account settings.")
        }
    }

    private var lifetimeSection: some View {
        Section {
            Label("Witness Pro — purchased for life", systemImage: "checkmark.seal.fill")
                .foregroundStyle(.green)
        } footer: {
            Text("A one-time purchase, tied to this Apple ID. Nothing to renew, manage, or cancel — restoring purchases on a new device brings it back for free.")
        }
    }

    // MARK: - Copy

    @ViewBuilder
    private var codecFooter: some View {
        switch codec {
        case .h264:
            Text("H.264 is the most compatible and the lightest to upload. Best when getting footage off the device matters most.")
        case .hevc:
            Text("HEVC 10-bit holds far more tonal detail than H.264 at a similar size, and still uploads live. The best balance for evidence you also want to grade.")
        case .proRes422LT:
            Text("ProRes 422 LT is a true mastering codec at roughly half the size of HQ. Every frame is stored on its own, so edits stay sharp through repeated cuts and colour work.")
        case .proRes422HQ:
            Text("ProRes 422 HQ is the highest quality Witness can record — the format broadcasters accept as a delivery master. It is also by far the heaviest.")
        }
    }

    // MARK: - Estimates

    private var estimatedBytesPerSecond: Double {
        codec.estimatedBitRate(resolution: resolution, frameRate: frameRate) / 8.0
    }

    private var chunkSizeText: String {
        format(bytes: estimatedBytesPerSecond * 15)
    }

    private var minuteSizeText: String {
        format(bytes: estimatedBytesPerSecond * 60)
    }

    private func format(bytes: Double) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: Int64(bytes))
    }

    private func openSubscriptionSettings() {
        guard let url = URL(string: "https://apps.apple.com/account/subscriptions") else { return }
        UIApplication.shared.open(url)
    }
}

/// Loops the bundled Pro promo, muted and without controls. Paused (on its
/// opening frame) when Reduce Motion is on, since it is decoration, not
/// information the page depends on.
private struct PromoVideoView: UIViewRepresentable {
    func makeUIView(context: Context) -> PromoPlayerUIView { PromoPlayerUIView() }
    func updateUIView(_ uiView: PromoPlayerUIView, context: Context) {}
}

private final class PromoPlayerUIView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    private var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    private let player = AVQueuePlayer()
    private var looper: AVPlayerLooper?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        guard let url = Bundle.main.url(forResource: "ProPromo", withExtension: "mp4") else { return }
        player.isMuted = true
        player.preventsDisplaySleepDuringVideoPlayback = false
        looper = AVPlayerLooper(player: player, templateItem: AVPlayerItem(url: url))
        playerLayer.player = player
        playerLayer.videoGravity = .resizeAspect
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil && !UIAccessibility.isReduceMotionEnabled {
            player.play()
        } else {
            player.pause()
        }
    }
}
