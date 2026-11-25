import SwiftUI

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var storage = StorageProviders.shared
    @AppStorage("driveFolderName") private var driveFolderName = "Witness"
    @AppStorage(AppSettings.quickStartViewfinderKey) private var quickStartViewfinder = false
    @AppStorage(AppSettings.timestampBurnInKey) private var timestampBurnIn = false
    @AppStorage(AppSettings.photosBackupKey) private var photosBackupEnabled = true
    @AppStorage(AppSettings.cloudUploadKey) private var cloudUploadEnabled = true
    @AppStorage(AppSettings.dualCameraKey) private var dualCameraEnabled = true
    // Watched so the dual-camera row reacts the moment a Pro capture setting
    // rules it out, rather than next launch.
    @AppStorage(AppSettings.videoResolutionKey) private var videoResolution = VideoResolution.hd1080.rawValue
    @AppStorage(AppSettings.videoFrameRateKey) private var videoFrameRate = VideoFrameRate.fps30.rawValue
    @AppStorage(AppSettings.videoCodecKey) private var videoCodec = VideoCodec.h264.rawValue
    @AppStorage(AppSettings.logRecordingKey) private var logRecording = false
    @ObservedObject private var pro = ProEntitlement.shared
    @State private var showDualBlocked = false
    @AppStorage(AppSettings.lensPreferenceKey) private var lensPreference = LensPreference.auto.rawValue
    @AppStorage(AppSettings.driveStitchKey) private var driveStitchEnabled = true
    @AppStorage(AppSettings.lowResFallbackKey) private var lowResFallback = true
    @AppStorage(AppSettings.folderNameTemplateKey) private var folderNameTemplate = AppSettings.defaultFolderNameTemplate
    @State private var contacts: [EmergencyContact] = EmergencyContact.load()
    @State private var newContactName = ""
    @State private var newContactEmail = ""
    @State private var newContactPhone = ""

    private var folderNamePreview: String {
        let trimmed = folderNameTemplate.trimmingCharacters(in: .whitespaces)
        var name = trimmed.isEmpty ? AppSettings.defaultFolderNameTemplate : folderNameTemplate
        for (token, example) in SessionFolderNaming.placeholders {
            name = name.replacingOccurrences(of: token, with: example)
        }
        return name.replacingOccurrences(of: "/", with: "-")
    }

    // Built from `Text` pieces, not `String` concatenation: a `String`-typed
    // property passed to `Text(_:)` uses the non-localizing initializer even
    // though every branch here is a literal — composing `Text + Text` keeps
    // each piece genuinely translatable via the String Catalog.
    private var lensFooter: Text {
        let base: Text
        switch LensPreference(rawValue: lensPreference) ?? .auto {
        case .auto:
            base = Text("Recordings start at the 0.5× ultra-wide lens when the device supports it, otherwise 1×.")
        case .ultraWide:
            base = Text("Recordings start at the 0.5× ultra-wide lens. In dual-camera mode, if the device can't run 0.5× with the front camera, it records a single 0.5× camera rather than switching to 1×.")
        case .wide:
            base = Text("Recordings start at the standard 1× lens.")
        }
        return base + Text(" ") + Text("With the viewfinder on you can switch 0.5×↔1× live while recording, with no gap in the footage.")
    }

    /// The settings currently ruling out both cameras — the same answer the
    /// capture layer acts on, computed from the values this view observes so
    /// the row updates the moment one of them changes.
    private var dualBlockers: [AppSettings.DualCameraBlocker] {
        let resolution = VideoResolution(rawValue: videoResolution) ?? .hd1080
        let frameRate = VideoFrameRate(rawValue: videoFrameRate) ?? .fps30
        let codec = VideoCodec(rawValue: videoCodec) ?? .h264
        return AppSettings.dualCameraBlockers(
            resolution: resolution, frameRate: frameRate, codec: codec,
            logRecording: logRecording, isPro: pro.isPro)
    }

    private var proCaptureBlocksDual: Bool { !dualBlockers.isEmpty }

    private var dualBlockersText: Text {
        let resolution = VideoResolution(rawValue: videoResolution) ?? .hd1080
        let frameRate = VideoFrameRate(rawValue: videoFrameRate) ?? .fps30
        let codec = VideoCodec(rawValue: videoCodec) ?? .h264
        return dualBlockers.enumerated().reduce(Text(verbatim: "")) { joined, item in
            joined + (item.offset == 0 ? Text(verbatim: "") : Text(verbatim: ", "))
                + Text(item.element.label(resolution: resolution, frameRate: frameRate, codec: codec))
        }
    }

    /// Whether nothing is uploading this session's footage — because upload is
    /// switched off, or because ProRes is too large to send. Computed from the
    /// values this view observes, so the row updates as they change.
    private var nothingIsUploading: Bool {
        AppSettings.isLocalOnlyCapture(
            codec: VideoCodec(rawValue: videoCodec) ?? .h264,
            cloudUploadEnabled: cloudUploadEnabled,
            cloudAccountConnected: AppSettings.cloudAccountConnected,
            isPro: pro.isPro)
    }

    /// Reads as on whenever nothing is uploading, because that is what the
    /// capture pipeline does. Such a session deletes each chunk's original as
    /// it is staged, so the library is the only copy — a switch that let you
    /// turn it off would be a switch that deletes the recording, and one
    /// reading "off" while the app forces it on is the same lie in reverse.
    ///
    /// The stored preference is untouched, so it comes back when uploading
    /// resumes.
    private var photosBackupBinding: Binding<Bool> {
        Binding(
            get: { photosBackupEnabled || nothingIsUploading },
            set: { photosBackupEnabled = $0 })
    }

    /// Shows off while Pro capture rules both cameras out, without discarding
    /// the stored preference — turning 4K or 60 fps back down restores it
    /// rather than making the user remember to switch this on again. Trying to
    /// switch it on explains what is in the way instead of silently refusing.
    private var dualCameraBinding: Binding<Bool> {
        Binding(
            get: { dualCameraEnabled && !proCaptureBlocksDual },
            set: { wanted in
                if wanted && proCaptureBlocksDual {
                    // Deferred a runloop: raising the flag inside the setter
                    // puts it in the same update SwiftUI uses to re-read the
                    // getter and snap the switch back, and the alert is lost
                    // in it — the toggle refuses correctly, silently, which
                    // is the behaviour this was meant to replace.
                    DispatchQueue.main.async { showDualBlocked = true }
                    return
                }
                dualCameraEnabled = wanted
            })
    }

    /// Puts the capture settings back where both cameras can run. The stored
    /// dual-camera preference is left alone: it was never turned off, only
    /// overridden, so it comes back on its own.
    private func restoreDualCameraDefaults() {
        videoResolution = VideoResolution.hd1080.rawValue
        videoFrameRate = VideoFrameRate.fps30.rawValue
        videoCodec = VideoCodec.h264.rawValue
        logRecording = false
    }

    private var dualCameraFooter: Text {
        CaptureSessionController.isDualCameraSupported
            ? Text("Records the front (selfie) camera at the same time as the rear one, producing two synchronized videos per session. Uses more battery and may warm the device during long recordings.")
              + Text("\n\n")
              + Text("With the viewfinder on, recording starts with a live preview (rear full-screen, front in a corner) instead of a black screen. You can also toggle it any time while recording. Takes effect on your next recording.")
            : Text("This device can't record both cameras simultaneously, so only the rear camera is used.")
              + Text("\n\n")
              + Text("With the viewfinder on, recording starts with a live preview instead of a black screen. You can also toggle it any time while recording.")
    }

    @State private var showHelp = false

    var body: some View {
        NavigationStack {
            Form {
                // First, so somebody who thinks a recording is lost finds it
                // without scrolling.
                Section {
                    Button { showHelp = true } label: {
                        Label("Help finding your footage", systemImage: "questionmark.circle")
                    }
                }
                Section(storage.current?.id.displayName ?? String(localized: "Cloud storage")) {
                    if let provider = storage.current, provider.isSignedIn {
                        LabeledContent("Account", value: provider.accountLabel ?? String(localized: "Connected"))
                        Button("Sign out", role: .destructive) {
                            provider.signOut()
                            AppSettings.refreshCloudAccountConnected()
                        }
                    } else {
                        Text("Not signed in, sign in from the home screen.")
                            .foregroundStyle(.secondary)
                    }
                    TextField("Upload folder name", text: $driveFolderName)
                        .autocorrectionDisabled()
                        .onChange(of: driveFolderName) {
                            // Folder is resolved fresh on next upload.
                            KeychainService.shared.delete(.driveFolderID)
                        }
                }

                Section {
                    TextField("Folder name template", text: $folderNameTemplate, axis: .vertical)
                        .autocorrectionDisabled()
                        .font(.system(.footnote, design: .monospaced))
                    LabeledContent("Preview") {
                        Text(folderNamePreview)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    .font(.footnote)
                    if folderNameTemplate != AppSettings.defaultFolderNameTemplate {
                        Button("Reset to default") {
                            folderNameTemplate = AppSettings.defaultFolderNameTemplate
                        }
                    }
                } header: {
                    Text("Session folder naming")
                } footer: {
                    Text("Placeholders: {date}, {time}, {location}, {session}. Each recording session's Drive subfolder is named from this template. Only applies to sessions started after you change it, existing folders keep their names.")
                }

                Section {
                    Picker("Starting lens", selection: $lensPreference) {
                        ForEach(LensPreference.allCases) { pref in
                            Text(pref.label).tag(pref.rawValue)
                        }
                    }
                    if CaptureSessionController.isDualCameraSupported {
                        Toggle("Record front camera too", isOn: dualCameraBinding)
                    } else {
                        LabeledContent("Dual camera", value: "Not supported on this device")
                    }
                } header: {
                    Text("Camera")
                } footer: {
                    // The viewfinder toggle itself moved to the Home screen,
                    // above Start Recording — this stays in Settings only as
                    // context for the lens/dual-camera behavior it affects.
                    lensFooter + Text("\n\n") + dualCameraFooter
                        + (proCaptureBlocksDual
                           ? Text("\n\n") + Text("Off right now because of the Witness Pro capture settings: ")
                             + dualBlockersText + Text(".")
                           : Text(verbatim: ""))
                }

                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("1. Open the Settings app, then search for \"Action Button\" at the top.")
                        Text("2. Swipe to \"Shortcut\", then tap \"Choose a Shortcut\".")
                        Text("3. Pick \"Start Witness Recording\" from the Witness section.")
                    }
                    .font(.subheadline)
                    Picker("Screen when started this way", selection: $quickStartViewfinder) {
                        Text("Black").tag(false)
                        Text("Show camera").tag(true)
                    }
                } header: {
                    Text("Start in one press")
                } footer: {
                    Text("iPhone 15 Pro or newer. Once set, pressing the Action Button starts recording immediately, with no unlocking or tapping. No Action Button? Use the Lock Screen widget, or say \"Hey Siri, start recording.\"")
                    + Text(verbatim: "\n\n")
                    + Text("Black keeps the screen dark while recording; triple-tap it for the controls, then tap the eye to see the camera. Show camera opens straight into the viewfinder.")
                }

                Section {
                    Toggle("Upload new recordings", isOn: $cloudUploadEnabled)
                } header: {
                    Text("Cloud upload")
                } footer: {
                    Text("On, every recording is sent to your storage as you film. Off, new recordings stay on this phone only — still recorded, still hashed and timestamped, and saved to Photos, which becomes the only place they exist.")
                    + Text("\n\n")
                    + Text("Turning this off never stops an upload already under way. Anything filmed while it was on keeps going until your storage confirms it, and no setting in this app can call that back — so taking the phone and switching this off cannot unsend what has already been recorded.")
                }

                Section {
                    Toggle("Visible timestamp & location", isOn: $timestampBurnIn)
                } footer: {
                    Text("Burns the date, time and coordinates into the picture itself, visible to anyone who watches the video. This is separate from the invisible metadata every recording already carries, on or off: the SHA-256 hash, the chain-of-custody manifest, and the independent RFC 3161 timestamp.")
                    + (pro.isPro ? Text(verbatim: "") : Text(verbatim: " ") + Text("It also reads \"Recorded with Witness\". Witness Pro leaves only the time and place."))
                }

                Section {
                    Toggle("Save recordings to Photos", isOn: photosBackupBinding)
                        .disabled(nothingIsUploading)
                } header: {
                    Text("On-device backup")
                } footer: {
                    Text("Saves one stitched video per camera per recording session to your Photos library, as backup independent of your cloud storage account. If iCloud Photos is enabled, it also syncs there. Turn this off to keep recordings out of your camera roll, cloud upload is unaffected.")
                    + (nothingIsUploading
                       ? Text("\n\n") + Text("Held on because nothing is uploading these recordings — with cloud upload off, or in ProRes, Photos is the only place they exist. Your own setting comes back when uploading resumes.")
                       : Text(verbatim: ""))
                }

                Section {
                    Toggle("Send a low-res copy first when the network struggles",
                           isOn: $lowResFallback)
                } header: {
                    Text("Poor connections")
                } footer: {
                    Text("If uploads start falling behind, Witness sends a small copy of each new recording ahead of the full-quality one, so something reaches Drive during the encounter instead of waiting on a connection that isn't coping. The full file follows and replaces it as soon as the network allows.")
                    + Text("\n\n")
                    + Text("On a healthy connection this never happens: the original is uploaded directly and no extra copy is made.")
                }

                Section {
                    Toggle("Combine into one video per camera", isOn: $driveStitchEnabled)
                } header: {
                    Text("Merging")
                } footer: {
                    Text("Once every small chunk of a session is safely uploaded, Witness merges them into a single video per camera, uploads that to the session's cloud folder, and then deletes the small chunks, so you get one clean file per camera instead of a folder full of pieces. This never happens until the small chunks are already confirmed uploaded, so nothing is at risk while it runs. Turn this off to keep the individual chunks instead.")
                }

                Section {
                    ForEach(contacts) { contact in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(contact.name)
                            if let email = contact.email, !email.isEmpty {
                                Text(email)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            if let phone = contact.phone, !phone.isEmpty {
                                Text(phone)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            if !contact.canReceiveFootage {
                                Text("No valid email, won't receive footage")
                                    .font(.caption2)
                                    .foregroundStyle(.orange)
                            }
                            if !contact.canReceiveSMS && !contact.hasValidEmail {
                                Text("No phone or email, can't be alerted")
                                    .font(.caption2)
                                    .foregroundStyle(.orange)
                            }
                        }
                    }
                    .onDelete { offsets in
                        contacts.remove(atOffsets: offsets)
                        EmergencyContact.save(contacts)
                    }

                    VStack(spacing: 8) {
                        TextField("Name", text: $newContactName)
                        TextField("Email", text: $newContactEmail)
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("Phone (optional)", text: $newContactPhone)
                            .keyboardType(.phonePad)
                        Button {
                            let contact = EmergencyContact(
                                id: UUID(), name: newContactName,
                                email: newContactEmail.trimmingCharacters(in: .whitespaces),
                                phone: newContactPhone.trimmingCharacters(in: .whitespaces))
                            contacts.append(contact)
                            EmergencyContact.save(contacts)
                            newContactName = ""
                            newContactEmail = ""
                            newContactPhone = ""
                        } label: {
                            Label("Add contact", systemImage: "plus.circle.fill")
                        }
                        // Either channel is enough to save a contact — email
                        // used to be required because it was the only thing
                        // this list did anything with, but the location ping
                        // can reach someone by phone alone.
                        .disabled(newContactName.isEmpty
                                  || (!newContactEmail.contains("@") && newContactPhone.isEmpty))
                    }
                } header: {
                    Text("Emergency contacts")
                } footer: {
                    if storage.current?.supportsEmailSharing == false {
                        Text("\(storage.current?.id.displayName ?? String(localized: "This storage account")) can't grant access automatically: emergency contacts with an email won't be notified or given access to your recordings. Switch to a provider that supports sharing, or share a folder yourself from that account.")
                    } else {
                        Text("When a recording starts uploading, each contact with an email is granted view access to that recording's cloud folder and notified by email. They can watch footage arrive live, access that survives even if your phone is taken. Contacts are only shared with when you record.")
                    }
                    Text("\n\nDuring a recording, a location button lets you send your last known location to every contact here by text and email, whichever they have on file — you send it yourself from a compose screen, nothing goes out automatically.")
                }

                Section {
                    NavigationLink {
                        ProtectFootageView()
                    } label: {
                        Label("Protect footage from deletion", systemImage: "lock.shield")
                    }
                } footer: {
                    Text("Witness never deletes your recordings from cloud storage itself. But anyone holding your unlocked phone could delete them through that account's own app or website. Learn how to guard against that.")
                }

                // Moved here from the old support page, which Cases replaced
                // on the Home toolbar.
                Section {
                    Link(destination: URL(string: "mailto:athingby@gmail.com")!) {
                        Label {
                            Text(verbatim: "athingby@gmail.com")
                        } icon: {
                            Image(systemName: "envelope")
                        }
                    }
                    // The same PayPal-blue button the old support page had.
                    Link(destination: URL(string: "https://paypal.me/athingby")!) {
                        HStack(spacing: 10) {
                            Image(systemName: "heart.fill")
                                .font(.system(size: 16, weight: .semibold))
                            Text("Donate with PayPal")
                                .font(.system(size: 17, weight: .semibold))
                        }
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Color(red: 0, green: 112 / 255, blue: 186 / 255), in: Capsule())
                    }
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 4, trailing: 0))
                } header: {
                    Text("Support")
                } footer: {
                    Text("Kept running by donations.")
                }
            }
            .navigationTitle("Settings")
            .sheet(isPresented: $showHelp) { HelpView() }
            .toolbar {
                Button("Done") { dismiss() }
            }
        }
        .alert("Both cameras need the free capture settings",
               isPresented: $showDualBlocked) {
            Button("Restore them") { restoreDualCameraDefaults() }
            Button("Leave as is", role: .cancel) {}
        } message: {
            Text("Recording both cameras at once is limited to 1080p, 30 fps and H.264, and can't do Apple Log. Change these in Witness Pro, or restore them here and the front camera switches back on by itself.")
        }
    }
}

/// The picker label for whatever a blocker refers to, so the reason shown to
/// the user is always the wording on the control they need to change.
extension AppSettings.DualCameraBlocker {
    func label(resolution: VideoResolution,
               frameRate: VideoFrameRate,
               codec: VideoCodec) -> LocalizedStringKey {
        switch self {
        case .resolution: return resolution.label
        case .frameRate:  return frameRate.label
        case .codec:      return codec.label
        case .log:        return "Apple Log"
        }
    }
}
