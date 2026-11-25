import SwiftUI
// For the requestReview environment action.
import StoreKit

struct HomeView: View {
    @Environment(\.requestReview) private var requestReview
    @EnvironmentObject private var capture: CaptureViewModel
    @EnvironmentObject private var appState: AppState
    @ObservedObject private var crews = CrewStore.shared
    @ObservedObject private var storage = StorageProviders.shared
    @ObservedObject private var uploads = UploadManager.shared
    @ObservedObject private var pro = ProEntitlement.shared
    @State private var showUploadQueue = false
    /// Observed rather than read once, so the Home status reacts as soon as
    /// the codec changes in the Pro page.
    @AppStorage(AppSettings.videoCodecKey) private var videoCodec = VideoCodec.h264.rawValue
    @State private var showSettings = false
    @State private var showCases = false
    /// How far a swipe on the main page has pulled Cases in, while it lasts.
    @State private var openingDrag: CGFloat?
    @State private var casesDrag: CGFloat = 0
    @State private var detailsItemID: UUID?
    @ObservedObject private var library = CaseLibrary.shared
    @ObservedObject private var libraryStorage = LibraryStorage.shared
    @State private var showPro = false
    @State private var showProUpsell = false
    @State private var showPaywallFromUpsell = false
    @State private var showStoragePicker = false
    @State private var showWebDAVSetup = false
    /// Set by the picker, acted on once its sheet has actually closed.
    @State private var pendingConnect: StorageProviderID?
    @State private var signInError: String?

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            ZStack {
                home
                    // A swipe to the right pulls Cases in under the finger,
                    // from the side it slides in from.
                    .simultaneousGesture(
                        DragGesture(minimumDistance: 12)
                            .onChanged { v in
                                guard !showCases else { return }
                                if openingDrag == nil {
                                    guard v.translation.width > 0,
                                          v.translation.width > abs(v.translation.height) else { return }
                                }
                                openingDrag = max(0, v.translation.width)
                            }
                            .onEnded { v in
                                guard openingDrag != nil else { return }
                                if v.translation.width > width * 0.3 || v.predictedEndTranslation.width > width * 0.6 {
                                    casesDrag = 0
                                    withAnimation(.spring(response: 0.35, dampingFraction: 0.86)) {
                                        showCases = true
                                        openingDrag = nil
                                    }
                                } else {
                                    withAnimation(.spring(response: 0.3, dampingFraction: 0.9)) { openingDrag = 0 }
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                                        if openingDrag == 0 { openingDrag = nil }
                                    }
                                }
                            })
                if showCases || openingDrag != nil {
                    let offset = openingDrag.map { min(0, $0 - width) } ?? min(0, casesDrag)
                    Color.black.opacity(0.45 * Double(max(0, 1 + offset / width)))
                        .ignoresSafeArea()
                        .onTapGesture { closeCases() }
                        .transition(.opacity)
                        .zIndex(1)
                    CasesView(close: closeCases)
                        .offset(x: offset)
                        .simultaneousGesture(
                            DragGesture(minimumDistance: 20)
                                .onChanged { v in
                                    if abs(v.translation.width) > abs(v.translation.height) {
                                        casesDrag = v.translation.width
                                    }
                                }
                                .onEnded { v in
                                    if v.translation.width < -110 || v.predictedEndTranslation.width < -260 {
                                        closeCases()
                                    } else {
                                        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { casesDrag = 0 }
                                    }
                                })
                        .transition(.move(edge: .leading))
                        .zIndex(2)
                }
            }
        }
    }

    private func openCases() {
        casesDrag = 0
        withAnimation(.spring(response: 0.45, dampingFraction: 0.86)) { showCases = true }
    }

    private func closeCases() {
        withAnimation(.spring(response: 0.4, dampingFraction: 0.9)) {
            showCases = false
            casesDrag = 0
        }
    }

    /// Offered once after a recording, never forced: one tap to add details
    /// or file it in a case, or dismiss it.
    private func savedBanner(_ id: UUID) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).font(.title3)
            Button { detailsItemID = id; library.justFinishedItemID = nil } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Recording saved").font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                    Text("Add details or file it in a case").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption.bold()).foregroundStyle(.tertiary)
            }
            .buttonStyle(.plain)
            Button { library.justFinishedItemID = nil } label: {
                Image(systemName: "xmark").font(.caption.bold()).foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Dismiss")
        }
        .padding(14)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, 4)
    }

    private var home: some View {
        NavigationStack {
            VStack(spacing: 32) {
                // Capped, not a plain Spacer: with the toggle now living
                // below the button instead of above it, an equal top/bottom
                // Spacer would center the taller block and push Start
                // Recording down from where it always sat. Capping the top
                // one keeps the button the first thing seen, with the
                // bottom Spacer free to absorb the rest.
                Spacer().frame(maxHeight: 90)

                Button {
                    Task { await capture.startRecording() }
                } label: {
                    VStack(spacing: 12) {
                        Image("WitnessMark")
                            .resizable()
                            .scaledToFit()
                            .frame(width: 140, height: 140)
                        Text("Start Recording")
                            .font(.headline)
                    }
                }
                .buttonStyle(.plain)

                // Two literal Text calls, not a ternary between string
                // literals — that resolves to the non-localizing String
                // initializer instead of LocalizedStringKey.
                Group {
                    if capture.viewfinderVisible {
                        Text("Recording shows a live preview.\nTriple-tap, then hold the stop icon to end.")
                    } else {
                        Text("Screen goes black while recording.\nTriple-tap, then hold the stop icon to end.")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

                viewfinderToggle

                if case .failed(let message) = capture.state {
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                        .onTapGesture { capture.resetFailure() }
                }

                Spacer()

                if let id = library.justFinishedItemID, !capture.isRecording {
                    savedBanner(id)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }

                VStack(spacing: 8) {
                    driveStatus
                    crewStatus
                    libraryStorageActions
                    if uploads.pendingCount > 0 {
                        // Tappable: the count says how many are waiting but
                        // not whether anything is moving, which on a poor
                        // connection is the question people actually have.
                        Button { showUploadQueue = true } label: {
                            HStack(spacing: 5) {
                                Label("\(uploads.pendingCount) chunk(s) queued for upload",
                                      systemImage: "arrow.up.circle")
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 10, weight: .semibold))
                            }
                            .font(.caption)
                            .foregroundStyle(.orange)
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("Shows upload progress for each piece")
                    }
                    if let error = uploads.lastError {
                        Text("Last upload error: \(error)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }
                .padding(.bottom)
            }
            .padding()
            .toolbar {
                // .principal centers between leading/trailing items — a plain
                // navigationTitle renders large and left-aligned instead, which
                // doesn't match the centered wordmark this app wants at top.
                ToolbarItem(placement: .principal) {
                    Text("Witness")
                        .font(.system(size: 20, weight: .bold))
                }
                // Top left, so the panel slides in from the side its
                // button is on.
                ToolbarItem(placement: .topBarLeading) {
                    Button { openCases() } label: {
                        Image(systemName: "folder")
                    }
                    .accessibilityLabel("Cases")
                }
                // Next to it, on the main screen: a crew is set up in the
                // minutes before an event, and its screen has the room to
                // explain why and how. Help lives in Settings.
                ToolbarItem(placement: .topBarLeading) {
                    Button { appState.showCrew = true } label: {
                        Image(systemName: crews.activeCrew == nil ? "person.3" : "person.3.fill")
                    }
                    .accessibilityLabel("Crew: record with others")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showPro = true } label: {
                        Text("PRO")
                            .font(.system(size: 11, weight: .heavy))
                            .foregroundStyle(pro.isPro ? .black : .red)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background {
                                // Filled once subscribed, outlined while it's
                                // still an offer.
                                if pro.isPro {
                                    Capsule().fill(.red)
                                } else {
                                    Capsule().stroke(.red, lineWidth: 1.5)
                                }
                            }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape")
                    }
                }
            }
            // Settings can restore the capture defaults, so it warms the
            // camera on the way out for the same reason the Pro page does.
            .sheet(isPresented: $showSettings, onDismiss: { capture.prepareCamera() }) {
                SettingsView()
            }
            .sheet(item: $detailsItemID) { id in
                NavigationStack {
                    ItemDetailView(itemID: id, justRecorded: true)
                        .toolbar { Button("Done") { detailsItemID = nil } }
                }
            }
            .sheet(isPresented: $showUploadQueue) { UploadQueueView() }
            // Warms the camera for whatever was just changed, so the cost of
            // switching to 4K/Log lands here rather than on the record button.
            .sheet(isPresented: $showPro, onDismiss: { capture.prepareCamera() }) {
                ProSettingsView()
            }
            // The chosen provider is connected in onDismiss, never inside the
            // picker's callback: starting a sign-in (or presenting the WebDAV
            // form) while this sheet is still dismissing means presenting on a
            // view controller that is mid-transition, which iOS drops
            // silently — the picker closed and nothing whatsoever happened.
            .sheet(isPresented: $showStoragePicker, onDismiss: connectPendingProvider) {
                StoragePickerView { provider in
                    pendingConnect = provider
                }
            }
            .sheet(isPresented: $appState.showCrew) {
                CrewView()
            }
            .sheet(isPresented: $showWebDAVSetup) {
                WebDAVSetupView {
                    AppSettings.refreshCloudAccountConnected()
                    libraryStorage.refresh()
                    UploadManager.shared.pump()
                }
            }
            .task {
                // One task, in order — two separate .task modifiers run
                // concurrently, which could show both dialogs on the same
                // visit. Awaiting the first before starting the second is
                // what actually guarantees they never stack.
                let showedReview = await askForReviewIfEarned()
                if !showedReview {
                    await showProUpsellIfEarned()
                }
            }
            .alert("Want to film in 4K?", isPresented: $showProUpsell) {
                Button("See Witness Pro") { showPaywallFromUpsell = true }
                // No action needed: didPresent() already marked this shown
                // for good, whichever button gets tapped.
                Button("Not now", role: .cancel) {}
            } message: {
                // "Everything in the free app stays free. Pro only adds
                // capture quality." is the paywall's own line for this exact
                // claim — repeated verbatim here rather than reworded, so
                // the two places making the same promise say the same thing.
                Text("Witness Pro unlocks 4K, higher frame rates, and Apple Log — and helps fund the app. Everything in the free app stays free, whether or not you subscribe. Pro only adds capture quality.")
            }
            .sheet(isPresented: $showPaywallFromUpsell) { PaywallView() }
            .alert("Camera & Microphone Access Needed",
                   isPresented: $capture.permissionSettingsPromptNeeded) {
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                // iOS only shows its own permission dialog once; a decline
                // there (here or back in onboarding) means there is no way
                // back into it except this Settings deep link.
                Text("Witness can't record without camera and microphone access. Turn them on in Settings to start recording.")
            }
            .task {
                storage.refreshAvailableSpace()
                library.finishPendingWork()
                libraryStorage.refresh()
                await LocalSessionRecord.retryMissingTimestamps()
            }
            .animation(.spring(response: 0.45, dampingFraction: 0.85), value: library.justFinishedItemID)
            .onReceive(NotificationCenter.default.publisher(
                for: UIApplication.didBecomeActiveNotification)) { _ in
                storage.refreshAvailableSpace()
                libraryStorage.refresh()
            }
        }
    }

    /// Asks for a rating, but only from Home and only once a session has been
    /// recorded *and* confirmed held by the backend.
    ///
    /// Home is the deliberate place for this: it is not on screen while
    /// recording, so a StoreKit dialog cannot appear over a blacked-out screen
    /// during an encounter. The recording check and the settle delay are belt
    /// and braces on top of that.
    /// Returns whether the request was actually made — not whether Apple
    /// chose to show a dialog for it, which this app is never told — so the
    /// caller can decide whether the Pro upsell is still free to ask instead.
    private func askForReviewIfEarned() async -> Bool {
        guard ReviewPrompt.shared.shouldPresent(isRecording: capture.isRecording) else { return false }
        // Let the screen settle first; a dialog arriving in the same frame as
        // the view reads as a glitch and gets dismissed unread.
        try? await Task.sleep(for: .seconds(1.5))
        guard !capture.isRecording else { return false }
        ReviewPrompt.shared.didRequest()
        requestReview()
        return true
    }

    /// Same idea as `askForReviewIfEarned`, for the Pro upsell — and run
    /// after it, not alongside it, so the two never stack into two dialogs
    /// on the same visit. Losing the review ask to the upsell this once is
    /// the safer failure than showing both: a system rating sheet and an
    /// alert competing for the same tap is worse than either alone.
    private func showProUpsellIfEarned() async {
        guard !showProUpsell, case .idle = capture.state else { return }
        guard ProUpsellPrompt.shared.shouldPresent(
            isRecording: capture.isRecording, isPro: pro.isPro) else { return }
        try? await Task.sleep(for: .seconds(1.5))
        guard !capture.isRecording else { return }
        ProUpsellPrompt.shared.didPresent()
        showProUpsell = true
    }

    /// Lets Witness double as an ordinary camera, on top of being a safety
    /// tool — but never at the expense of the safety default. Off every
    /// time the app is opened fresh, regardless of what was chosen last
    /// time: nothing backs this with a stored setting, so there is nothing
    /// for a relaunch to remember. Turning it on is a conscious choice made
    /// new each time, never a forgotten state that quietly carries over
    /// into a moment it wasn't meant for.
    private var viewfinderToggle: some View {
        Toggle(isOn: Binding(
            get: { capture.viewfinderVisible },
            set: { capture.setViewfinderPreference($0) }
        )) {
            Text("Show viewfinder")
                .font(.system(size: 15, weight: .medium))
        }
        .tint(.red)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .frame(maxWidth: 240)
    }

    @ViewBuilder
    private var driveStatus: some View {
        if localOnlyCapture {
            // Saying "uploading to Drive" here would be untrue, and this is
            // the one claim of the app nobody should be wrong about.
            VStack(spacing: 4) {
                Label("Recording stays on this device", systemImage: "iphone")
                    .font(.caption)
                    .foregroundStyle(.orange)
                Text("ProRes is too large to upload live. Turn it off in Pro to send footage to Drive again.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        } else if let provider = storage.current, provider.isSignedIn {
            Label(signedInLabel(for: provider), systemImage: "checkmark.icloud")
                .font(.caption)
                .foregroundStyle(.green)
            if let warning = storageSpaceWarning {
                Label(warning, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption2)
                    .foregroundStyle(.orange)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
            }
        } else {
            VStack(spacing: 4) {
                Image(systemName: "cloud")
                    .font(.system(size: 18))
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 2)
                Button("Connect cloud storage") {
                    showStoragePicker = true
                }
                .font(.callout.bold())
                Text("Until you sign in, footage is kept safely on this device only.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                if let signInError {
                    Text(signInError).font(.caption2).foregroundStyle(.red)
                }
            }
        }
    }

    /// Says which crew new recordings join, so nobody records into one by
    /// mistake, and opens it.
    @ViewBuilder
    private var crewStatus: some View {
        if let crew = crews.activeCrew {
            Button { appState.showCrew = true } label: {
                Label("Recording with crew: \(crew.title)", systemImage: "person.3.fill")
                    .font(.caption)
                    .lineLimit(1)
            }
            .padding(.horizontal, 24)
        }
    }

    /// Both are offers the person taps, never things that happen to them: one
    /// deletes files from the phone, the other can send gigabytes over
    /// mobile data.
    @ViewBuilder
    private var libraryStorageActions: some View {
        if libraryStorage.redundantBytes > 0 {
            Button {
                Task { await libraryStorage.freeUpSpace() }
            } label: {
                Label(libraryStorage.freeing
                        ? String(localized: "Freeing up space…")
                        : String(localized: "Free up \(ByteCountFormatter.string(fromByteCount: libraryStorage.redundantBytes, countStyle: .file)): already in your photo library"),
                      systemImage: "externaldrive.badge.minus")
                    .font(.caption)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .disabled(libraryStorage.freeing)
            .padding(.horizontal, 24)
        }
        if !libraryStorage.awaitingUpload.isEmpty, let provider = storage.current, provider.isSignedIn {
            Button {
                Task { await libraryStorage.uploadEarlierRecordings() }
            } label: {
                if let progress = libraryStorage.uploadProgress {
                    Label("Uploading earlier recordings, \(progress.done) of \(progress.total)…",
                          systemImage: "arrow.up.circle")
                } else {
                    Label("Upload \(libraryStorage.awaitingUpload.count) earlier recording(s) from your photo library",
                          systemImage: "icloud.and.arrow.up")
                }
            }
            .font(.caption)
            .multilineTextAlignment(.center)
            .disabled(libraryStorage.uploadProgress != nil)
            .padding(.horizontal, 24)
        }
        if let message = libraryStorage.message {
            Text(message)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 24)
        }
    }

    /// Gated on the live entitlement too: a lapsed subscription falls back to
    /// H.264, and the status must follow rather than keep claiming local-only.
    private var localOnlyCapture: Bool {
        pro.isPro && (VideoCodec(rawValue: videoCodec) ?? .h264).isProRes
    }

    /// Warns while there is still time to do something about it.
    ///
    /// Stated as minutes of recording rather than gigabytes, because that is
    /// the decision being made — "2.1 GB free" does not tell anyone whether
    /// they can film the thing they are about to film, and at 4K it is four
    /// minutes. Below ten minutes is worth interrupting for; above it, silence.
    private var storageSpaceWarning: String? {
        guard let seconds = storage.recordableSeconds else { return nil }
        guard seconds < 10 * 60 else { return nil }
        let minutes = Int(seconds / 60)
        if minutes < 1 {
            return String(localized: "Your storage is full — recordings can't be uploaded. Free up space or connect another account.")
        }
        return String(localized: "Only about \(minutes) min of recording will fit in your storage. Free up space or connect another account.")
    }

    private func signedInLabel(for provider: StorageProvider) -> String {
        if let account = provider.accountLabel {
            return String(localized: "Uploading to \(provider.id.displayName) as \(account)")
        }
        return String(localized: "Uploading to \(provider.id.displayName)")
    }

    /// Runs after the picker sheet has closed, so the account flow has a
    /// settled view controller to present from.
    private func connectPendingProvider() {
        guard let provider = pendingConnect else { return }
        pendingConnect = nil
        // WebDAV has no hosted sign-in page: it needs a server address and
        // credentials instead.
        if StorageProviders.shared.usesManualSetup(provider) {
            showWebDAVSetup = true
        } else {
            signIn()
        }
    }

    private func signIn() {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let root = scene.keyWindow?.rootViewController else { return }
        guard let provider = StorageProviders.shared.current else {
            signInError = String(localized: "That storage provider isn't available yet.")
            return
        }
        Task {
            do {
                try await provider.signIn(presenting: root)
                signInError = nil
                AppSettings.refreshCloudAccountConnected()
                libraryStorage.refresh()
                UploadManager.shared.pump()
            } catch {
                signInError = error.localizedDescription
            }
        }
    }
}
