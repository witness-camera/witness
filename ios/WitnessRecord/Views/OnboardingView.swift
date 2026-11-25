import SwiftUI
import AVFoundation

/// One-time onboarding, paged one idea per screen — how it works, instant
/// start, permissions, connecting Drive, adding an emergency contact, then
/// rights — so each is actually read rather than scrolled past. Full-bleed
/// ambient background, big minimal text, a "Next"/"Get Started" pill, and
/// native TabView paging (swipe or tap).
struct OnboardingView: View {
    @AppStorage("onboardingComplete") private var onboardingComplete = false
    @ObservedObject private var location = LocationService.shared
    @ObservedObject private var storage = StorageProviders.shared
    @State private var page = 0
    @State private var signInError: String?
    @State private var connectingProvider: StorageProviderID?
    @State private var showWebDAVSetup = false
    @State private var unsupportedExplanation: StorageProviderID?
    @State private var contactName = ""
    @State private var contactEmail = ""
    @State private var contactPhone = ""
    @State private var contactAdded = false
    /// The screen's true point width, from `UIScreen` rather than a
    /// `GeometryReader` proxy — a `GeometryReader` wrapping this hierarchy
    /// measured a width visibly larger than the device's actual bounds here
    /// (content and even chrome outside the TabView were cut off past the
    /// right edge), so every page gets this authoritative value directly
    /// instead of trusting proposed/measured sizes to propagate correctly.
    private let pageWidth = UIScreen.main.bounds.width

    private let pageCount = 7

    var body: some View {
        ZStack {
            OnboardingBackground(page: page)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                topBar

                TabView(selection: $page) {
                    welcomePage.tag(0)
                    howItWorksPage.tag(1)
                    actionButtonPage.tag(2)
                    permissionsPage.tag(3)
                    drivePage.tag(4)
                    contactPage.tag(5)
                    rightsPage.tag(6)
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .frame(width: pageWidth)
                #if DEBUG
                // App Store screenshots: `defaults write … screenshotOnboardingPage N`
                // opens straight on page N. Set after a beat, since a paging
                // view can ignore a non-zero starting selection. Debug only.
                .task {
                    let target = UserDefaults.standard.integer(forKey: "screenshotOnboardingPage")
                    guard target > 0 else { return }
                    try? await Task.sleep(for: .milliseconds(400))
                    page = target
                }
                #endif

                footer
            }
        }
        .preferredColorScheme(.dark)
    }

    // MARK: - Chrome

    private var topBar: some View {
        HStack {
            if page > 0 {
                Button {
                    tap()
                    withAnimation(.easeInOut(duration: 0.3)) { page -= 1 }
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.75))
                        .frame(width: 34, height: 34)
                        .background(.white.opacity(0.1), in: Circle())
                }
            } else {
                Color.clear.frame(width: 34, height: 34)
            }
            Spacer()
        }
        .padding(.horizontal, 20)
        .padding(.top, 6)
        .frame(height: 44)
    }

    private var footer: some View {
        VStack(spacing: 18) {
            HStack(spacing: 7) {
                ForEach(0..<pageCount, id: \.self) { i in
                    Capsule()
                        .fill(.white.opacity(i == page ? 0.9 : 0.25))
                        .frame(width: i == page ? 20 : 6, height: 6)
                }
            }
            .animation(.spring(response: 0.4, dampingFraction: 0.8), value: page)

            Button {
                if page == pageCount - 1 {
                    tap(.medium)
                    onboardingComplete = true
                } else {
                    tap()
                    withAnimation(.easeInOut(duration: 0.3)) { page += 1 }
                }
            } label: {
                Group {
                    // Two literal Text calls, not one ternary-selected string —
                    // a ternary between string literals passed to Text resolves
                    // to the non-localizing String initializer, not
                    // LocalizedStringKey, so each branch must be its own call.
                    if page == pageCount - 1 {
                        Text("Get Started")
                    } else {
                        Text("Next")
                    }
                }
                .font(.system(size: 18, weight: .semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
            }
            .buttonStyle(.heroPill)
        }
        .padding(.horizontal, 24)
        .padding(.top, 8)
        .padding(.bottom, 20)
        .frame(width: pageWidth)
    }

    // MARK: - Pages

    private var welcomePage: some View {
        // Centered rather than the shared top-aligned OnboardingPage shell:
        // this is the app's own brand mark, not a feature bullet, so it gets
        // the one page that reads like a title screen instead of a list.
        VStack(alignment: .center, spacing: 0) {
            Spacer()
            Image("WitnessMark")
                .resizable()
                .scaledToFit()
                .frame(width: 140, height: 140)
                .padding(.bottom, 12)
            Text("Witness")
                .font(.system(size: 34, weight: .bold))
                .foregroundStyle(.white)
                .padding(.bottom, 6)
            bodyText("Preserve footage automatically, even if your phone is taken.")
                .multilineTextAlignment(.center)
            Spacer()
        }
        .padding(.horizontal, 28)
        .frame(width: pageWidth)
    }

    private var howItWorksPage: some View {
        OnboardingPage(width: pageWidth) {
            titleRow("How it works")
            VStack(alignment: .leading, spacing: 18) {
                factRow("eye.slash.fill", "Screen goes black.")
                factRow("antenna.radiowaves.left.and.right", "Uploads live, every 15s.")
                factRow("checkmark.seal.fill", "Hashed & time-stamped.")
            }
            .padding(.top, 6)
        }
    }

    private var actionButtonPage: some View {
        OnboardingPage(width: pageWidth) {
            Image("ActionButtonRender")
                .resizable()
                .scaledToFit()
                .frame(width: 24, height: 112)
                .padding(.bottom, 16)
            titleRow("Start in one press")
            bodyText("iPhone 15 Pro or newer? Bind the Action Button:")
            VStack(alignment: .leading, spacing: 12) {
                flowRow("Settings", "Action Button")
                flowRow("Shortcut", "Start Witness Recording")
            }
            .padding(.top, 8)
            captionText("No Action Button? Use the Lock Screen widget, or say \"Hey Siri, start recording.\"")
                .padding(.top, 10)
        }
    }

    private var permissionsPage: some View {
        OnboardingPage(width: pageWidth) {
            titleRow("Turn on access")
            bodyText("Camera, microphone, and location, used only while recording.")
            VStack(spacing: 10) {
                permissionRow("Camera & microphone", icon: "video.fill") {
                    AVCaptureDevice.requestAccess(for: .video) { _ in
                        AVCaptureDevice.requestAccess(for: .audio) { _ in }
                    }
                }
                permissionRow("Location", icon: "location.fill") {
                    location.requestPermissions()
                }
                permissionRow("Photos", icon: "photo.fill") {
                    PhotoLibraryService.shared.requestPermission()
                }
                permissionRow("Notifications", icon: "bell.fill") {
                    BackgroundSaveNotifier.shared.requestPermissionIfNeeded()
                }
            }
            .padding(.top, 8)
            captionText("Photos backup keeps a copy on-device, independent of Drive.")
                .padding(.top, 6)
            captionText("Notifications are only ever used to tell you a recording still needs the app reopened to finish saving.")
                .padding(.top, 2)
        }
    }

    private var drivePage: some View {
        OnboardingPage(width: pageWidth) {
            Image(systemName: "icloud.and.arrow.up.fill")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(.red)
                .padding(.bottom, 12)
            titleRow("Connect your storage")
            bodyText("Footage uploads to an account you own as you record, safe even if your phone isn't.")
            if let provider = storage.current, provider.isSignedIn {
                connectedRow(provider)
                    .padding(.top, 8)
            } else {
                // Every registered backend, not just Google — the same
                // autonomous choice as the in-app picker, so onboarding
                // doesn't quietly funnel everyone into one account type
                // before they've seen the alternatives exist.
                VStack(spacing: 10) {
                    ForEach(StorageProviderID.allCases) { id in
                        storageRow(id)
                    }
                }
                .padding(.top, 8)
                if let signInError {
                    // Bypasses captionText deliberately: this is a runtime
                    // error string (from the sign-in SDK), not translatable
                    // app copy — it must not be treated as a localization key.
                    Text(signInError)
                        .font(.system(size: 14))
                        .foregroundStyle(.red.opacity(0.85))
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 6)
                }
            }
            captionText("Until you connect one, footage stays only on this device.")
                .padding(.top, 10)
        }
        .sheet(isPresented: $showWebDAVSetup) {
            WebDAVSetupView { UploadManager.shared.pump() }
        }
        .alert(
            StorageProviders.unsupportedExplanationTitle,
            isPresented: Binding(
                get: { unsupportedExplanation != nil },
                set: { if !$0 { unsupportedExplanation = nil } })
        ) {
            Button("OK") { unsupportedExplanation = nil }
        } message: {
            Text(StorageProviders.unsupportedExplanationMessage)
        }
    }

    private func connectedRow(_ provider: StorageProvider) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
            Text(provider.accountLabel ?? provider.id.displayName)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(.white)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    private func storageRow(_ id: StorageProviderID) -> some View {
        Button {
            tap()
            connect(id)
        } label: {
            HStack(spacing: 12) {
                storageIcon(id)
                    .frame(width: 22, height: 22)
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 6) {
                        Text(id.displayName)
                            .font(.system(size: 17, weight: .semibold))
                        // Steers the autonomous choice without removing it:
                        // Google stays the one-tap default for most people,
                        // but every other backend is a real, equally-tappable
                        // option on the same screen.
                        if id == .googleDrive {
                            Text("Recommended")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(.black)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(.white, in: Capsule())
                        }
                    }
                    Text(id.subtitle)
                        .font(.system(size: 13))
                        .foregroundStyle(storage.isUnsupported(id) ? .orange.opacity(0.85) : .white.opacity(0.55))
                }
                Spacer()
                if connectingProvider == id {
                    ProgressView().tint(.white)
                } else if storage.isUnsupported(id) {
                    Image(systemName: "info.circle")
                        .font(.system(size: 14))
                        .foregroundStyle(.white.opacity(0.35))
                } else {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.35))
                }
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plainBounce)
        .disabled(connectingProvider != nil)
    }

    @ViewBuilder
    private func storageIcon(_ id: StorageProviderID) -> some View {
        if let asset = id.iconAssetName {
            Image(asset)
                .resizable()
                .scaledToFit()
        } else {
            Image(systemName: id.fallbackSymbol)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white.opacity(0.85))
        }
    }

    private var contactPage: some View {
        OnboardingPage(width: pageWidth) {
            titleRow("Add someone who'll know")
            bodyText("An emergency contact automatically gets access to your footage as it uploads, and can be reached with your location at a tap while you're recording.")
            VStack(spacing: 10) {
                TextField("", text: $contactName, prompt: Text("Name").foregroundStyle(.white.opacity(0.4)))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                TextField("", text: $contactEmail, prompt: Text("Email").foregroundStyle(.white.opacity(0.4)))
                    .foregroundStyle(.white)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                TextField("", text: $contactPhone, prompt: Text("Phone (optional)").foregroundStyle(.white.opacity(0.4)))
                    .foregroundStyle(.white)
                    .keyboardType(.phonePad)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                Button {
                    tap()
                    addContact()
                } label: {
                    Text("Add contact")
                        .font(.system(size: 16, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(.heroPill)
                .disabled(contactName.isEmpty || (!contactEmail.contains("@") && contactPhone.isEmpty))
                .opacity(contactName.isEmpty || (!contactEmail.contains("@") && contactPhone.isEmpty) ? 0.5 : 1)
            }
            .padding(.top, 8)
            if contactAdded {
                captionText("Added, they'll be notified once you start recording.")
                    .foregroundStyle(.green.opacity(0.85))
                    .padding(.top, 8)
            }
            captionText("You can add more anytime in Settings.")
                .padding(.top, 10)
        }
    }

    private var rightsPage: some View {
        OnboardingPage(width: pageWidth) {
            titleRow("Know your rights")
            bodyText("Recording in public is legal in most countries, but rules on distance, audio, and interference vary widely by jurisdiction.")
            Link(destination: URL(string: "https://www.aclu.org/know-your-rights/protesters-rights")!) {
                HStack(spacing: 6) {
                    Text("US rights guide (ACLU)")
                    Image(systemName: "arrow.up.right")
                }
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.white.opacity(0.12), in: Capsule())
            }
            .padding(.top, 4)
            captionText("Not legal advice. Check your local laws, stay safe, and announce you're recording if you can.")
                .padding(.top, 10)
        }
    }

    private func connect(_ id: StorageProviderID) {
        // Checked before selecting anything: an unsupported backend must
        // never become the active one, or uploads would silently have
        // nowhere to go until the user picked something else.
        if storage.isUnsupported(id) {
            unsupportedExplanation = id
            return
        }
        storage.select(id)
        // WebDAV has no hosted sign-in page: it needs a server address and
        // credentials instead, same as the in-app picker.
        if storage.usesManualSetup(id) {
            showWebDAVSetup = true
            return
        }
        guard let provider = storage.current else {
            signInError = String(localized: "That storage provider isn't available yet.")
            return
        }
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let root = scene.keyWindow?.rootViewController else { return }
        connectingProvider = id
        Task {
            do {
                try await provider.signIn(presenting: root)
                signInError = nil
                UploadManager.shared.pump()
            } catch {
                signInError = error.localizedDescription
            }
            connectingProvider = nil
        }
    }

    private func addContact() {
        var contacts = EmergencyContact.load()
        contacts.append(EmergencyContact(
            id: UUID(), name: contactName,
            email: contactEmail.trimmingCharacters(in: .whitespaces),
            phone: contactPhone.trimmingCharacters(in: .whitespaces)))
        EmergencyContact.save(contacts)
        contactName = ""
        contactEmail = ""
        contactPhone = ""
        contactAdded = true
    }

    // MARK: - Building blocks

    // Localized-key parameters (not `String`): a `String`-typed parameter
    // would make `Text(text)` inside these helpers resolve to the
    // non-localizing `Text(StringProtocol)` initializer even when every call
    // site passes a literal — `LocalizedStringKey` is what keeps them
    // translatable via the String Catalog.
    private func titleRow(_ title: LocalizedStringKey, kicker: LocalizedStringKey? = nil) -> some View {
        // Stacked, not side-by-side: on the narrowest iPhones, a big bold
        // title plus a kicker rarely both fit on one line without wrapping
        // unpredictably — stacking is correct at every width.
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.system(size: 34, weight: .bold))
                .foregroundStyle(.white)
            if let kicker {
                Text(kicker)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(.white.opacity(0.5))
            }
        }
        .padding(.bottom, 6)
    }

    private func bodyText(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.system(size: 21, weight: .regular))
            .foregroundStyle(.white.opacity(0.85))
            .fixedSize(horizontal: false, vertical: true)
    }

    private func captionText(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.system(size: 14))
            .foregroundStyle(.white.opacity(0.5))
            .fixedSize(horizontal: false, vertical: true)
    }

    private func factRow(_ icon: String, _ text: LocalizedStringKey) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 19, weight: .semibold))
                .foregroundStyle(.red)
                .frame(width: 26)
            Text(text)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(.white)
        }
    }

    private func flowRow(_ a: LocalizedStringKey, _ b: LocalizedStringKey) -> some View {
        // Composed from two independently-localized Text values plus a
        // literal separator — not one interpolated "\(a) → \(b)" string,
        // which would bake the *words* in as opaque runtime arguments that
        // never go through their own translation lookup, only the
        // surrounding template would. Still wraps naturally at narrow widths.
        (Text(a) + Text(" → ") + Text(b))
            .font(.system(size: 19, weight: .semibold))
            .foregroundStyle(.white)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func permissionRow(_ title: LocalizedStringKey, icon: String, action: @escaping () -> Void) -> some View {
        Button {
            tap()
            action()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.red)
                    .frame(width: 22)
                Text(title)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.35))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plainBounce)
    }

    private func tap(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .light) {
        UIImpactFeedbackGenerator(style: style).impactOccurred()
    }
}

// MARK: - Page shell

/// One onboarding page's content, top-aligned with generous breathing room
/// above — the same vertical rhythm as a native full-bleed onboarding screen.
private struct OnboardingPage<Content: View>: View {
    /// Passed down explicitly from the outer GeometryReader rather than
    /// relying on TabView(.page) to propagate its width to children — it
    /// doesn't reliably do so here, which let Text measure its own unwrapped
    /// intrinsic width and overflow/off-center the page.
    let width: CGFloat
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 60)
            content
            Spacer()
        }
        .padding(.horizontal, 28)
        .frame(width: width, alignment: .topLeading)
    }
}

// MARK: - Background

/// Full-bleed ambient backdrop: a soft blurred red glow that drifts to a
/// different corner per page, over near-black, with a faint diagonal sheen —
/// the same moody, textured full-bleed look as a premium editorial app.
private struct OnboardingBackground: View {
    let page: Int

    private var glowOffset: CGSize {
        switch page % 3 {
        case 0: return CGSize(width: -90, height: -180)
        case 1: return CGSize(width: 110, height: -40)
        default: return CGSize(width: -50, height: 140)
        }
    }

    var body: some View {
        ZStack {
            Color.black
            RadialGradient(
                colors: [.red.opacity(0.36), .red.opacity(0.05), .clear],
                center: .center, startRadius: 10, endRadius: 380
            )
            .frame(width: 520, height: 520)
            .blur(radius: 60)
            .offset(glowOffset)
            .animation(.easeInOut(duration: 0.7), value: page)

            LinearGradient(
                colors: [.white.opacity(0.05), .clear, .white.opacity(0.03)],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
        }
    }
}

// MARK: - Button styles

private struct HeroPillButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(.black)
            .background(.white, in: Capsule())
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.88 : 1)
            .animation(.spring(response: 0.28, dampingFraction: 0.55), value: configuration.isPressed)
    }
}

private struct PlainBounceButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.8 : 1)
            .animation(.spring(response: 0.28, dampingFraction: 0.55), value: configuration.isPressed)
    }
}

private extension ButtonStyle where Self == HeroPillButtonStyle {
    static var heroPill: HeroPillButtonStyle { HeroPillButtonStyle() }
}

private extension ButtonStyle where Self == PlainBounceButtonStyle {
    static var plainBounce: PlainBounceButtonStyle { PlainBounceButtonStyle() }
}
