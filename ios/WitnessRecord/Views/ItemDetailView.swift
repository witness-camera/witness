import AVFoundation
import AVKit
import SwiftUI

/// The system player, for its scrubber, AirPlay and picture in picture.
private struct PlayerController: UIViewControllerRepresentable {
    let player: AVPlayer
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        player.play()
        return controller
    }
    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {}
}

/// One recording, photo or file: which case it's in, the details and notes
/// added afterwards, its transcript, and what proves it.
struct ItemDetailView: View {
    let itemID: UUID
    /// Shown straight after a recording, so the case picker comes first.
    var justRecorded = false

    @ObservedObject private var library = CaseLibrary.shared
    @ObservedObject private var pro = ProEntitlement.shared
    @Environment(\.dismiss) private var dismiss
    @State private var newNote = ""
    @State private var showNewCase = false
    @State private var newCaseTitle = ""
    @State private var showPaywall = false
    @State private var transcribing = false
    @State private var confirmDelete = false
    @State private var player: AVAudioPlayer?
    @State private var playing = false
    @State private var videoPlayer: AVPlayer?
    @State private var loadingVideo = false
    @State private var videoError: String?
    @FocusState private var noteFocused: Bool

    private var item: CaseItem? { library.item(itemID) }

    var body: some View {
        if let item {
            Form {
                header(item)
                caseSection(item)
                detailsSection(item)
                notesSection(item)
                transcriptSection(item)
                proofSection(item)
                Section {
                    Button("Remove from Witness", role: .destructive) { confirmDelete = true }
                } footer: {
                    if item.kind == .video {
                        Text("Removes the app's thumbnail, audio copy, details and notes. The recording itself stays in Photos and your cloud account.")
                    } else {
                        Text("Removes the app's copy. Anything already in Photos or your cloud account stays there.")
                    }
                }
            }
            .navigationTitle(CaseReport.kindName(item))
            .navigationBarTitleDisplayMode(.inline)
            .confirmationDialog("Remove this item?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Remove", role: .destructive) {
                    library.deleteItem(itemID)
                    dismiss()
                }
            }
            .alert("New case", isPresented: $showNewCase) {
                TextField("Name, e.g. Flat 4B deposit", text: $newCaseTitle)
                Button("Create") {
                    let created = library.newCase(titled: newCaseTitle)
                    library.move(itemID, to: created.id)
                    newCaseTitle = ""
                }
                Button("Cancel", role: .cancel) { newCaseTitle = "" }
            }
            .sheet(isPresented: $showPaywall) { PaywallView() }
            .sheet(isPresented: $transcribing) { TranscribeSheet(itemID: itemID) }
            .fullScreenCover(isPresented: Binding(get: { videoPlayer != nil },
                                                  set: { if !$0 { videoPlayer?.pause(); videoPlayer = nil } })) {
                if let videoPlayer {
                    PlayerController(player: videoPlayer).ignoresSafeArea()
                }
            }
            .onDisappear { player?.stop() }
        } else {
            ContentUnavailableView("Removed", systemImage: "trash")
        }
    }

    // MARK: Sections

    @ViewBuilder private func header(_ item: CaseItem) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 12) {
                if item.kind == .photo, let url = CaseLibrary.mediaURL(for: item),
                   let image = UIImage(contentsOfFile: url.path) {
                    Image(uiImage: image).resizable().scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                } else if item.kind == .video {
                    Button { Task { await playVideo(item) } } label: {
                        ZStack {
                            Color(white: 0.12)
                            if let image = UIImage(contentsOfFile: CaseLibrary.thumbnailURL(for: item.id).path) {
                                Color.clear.overlay(Image(uiImage: image).resizable().scaledToFill())
                            }
                            if loadingVideo {
                                ProgressView().tint(.white)
                            } else {
                                Image(systemName: "play.fill").font(.title2).foregroundStyle(.white)
                                    .padding(16).background(.black.opacity(0.55), in: Circle())
                            }
                        }
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Play recording"))
                    if let videoError {
                        Text(videoError).font(.caption).foregroundStyle(.secondary)
                    }
                    if CaseLibrary.transcriptAudioURL(for: item) != nil {
                        audioButton(item)
                    }
                } else {
                    HStack(spacing: 14) {
                        ItemThumbnail(item: item).frame(width: 84, height: 84)
                        if let url = CaseLibrary.mediaURL(for: item), Self.isMovie(url) {
                            Button { videoPlayer = AVPlayer(url: url) } label: {
                                Label("Play video", systemImage: "play.circle.fill").font(.headline)
                            }
                            .buttonStyle(.borderless)
                        } else if CaseLibrary.transcriptAudioURL(for: item) != nil {
                            audioButton(item)
                        }
                    }
                }
                TextField("Name (optional)", text: Binding(
                    get: { library.item(itemID)?.title ?? "" },
                    set: { value in library.update(itemID) { $0.title = value.isEmpty ? nil : value } }))
                    .font(.headline)
                Text(subtitle(item)).font(.caption).foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)
        }
    }

    private func audioButton(_ item: CaseItem) -> some View {
        Button { togglePlay(item) } label: {
            Label(LocalizedStringKey(playing ? "Pause" : "Play audio"),
                  systemImage: playing ? "pause.circle.fill" : "waveform.circle.fill")
                .font(.subheadline.weight(.semibold))
        }
        .buttonStyle(.borderless)
    }

    private static func isMovie(_ url: URL) -> Bool {
        ["mov", "mp4", "m4v"].contains(url.pathExtension.lowercased())
    }

    /// The footage is in Photos, not in the app, so watching it needs read
    /// access, asked for the first time someone presses play.
    private func playVideo(_ item: CaseItem) async {
        videoError = nil
        guard await PhotoLibraryService.shared.requestReadAccess() else {
            videoError = String(localized: "To watch recordings here, allow Witness to see its videos in Photos (Settings › Witness › Photos). Your recording is safe in Photos and your cloud account.")
            return
        }
        loadingVideo = true
        defer { loadingVideo = false }
        let assets = PhotoLibraryService.shared.videoAssets(
            identifiers: item.photoAssetIDs ?? [], start: item.createdAt, end: item.endedAt)
        guard let asset = assets.first, let playerItem = await PhotoLibraryService.shared.playerItem(for: asset) else {
            videoError = String(localized: "This recording isn't in Photos on this phone. It may have been deleted there, or saved only to your cloud account.")
            return
        }
        if item.photoAssetIDs?.isEmpty ?? true {
            library.update(itemID) { $0.photoAssetIDs = [asset.localIdentifier] }
        }
        player?.stop(); playing = false
        videoPlayer = AVPlayer(playerItem: playerItem)
    }

    @ViewBuilder private func caseSection(_ item: CaseItem) -> some View {
        Section {
            Picker(selection: Binding(
                get: { item.caseID },
                set: { library.move(itemID, to: $0) })) {
                Text("Not in a case").tag(UUID?.none)
                ForEach(library.cases) { c in Text(c.title).tag(UUID?.some(c.id)) }
            } label: {
                Label("Case", systemImage: "folder")
            }
            Button { showNewCase = true } label: { Label("New case…", systemImage: "folder.badge.plus") }
        } header: {
            if justRecorded { Text("Recording saved") }
        }
    }

    @ViewBuilder private func detailsSection(_ item: CaseItem) -> some View {
        let template = item.template.flatMap(DetailTemplate.init(rawValue:))
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(DetailTemplate.allCases) { t in
                        let selected = template == t
                        Button {
                            withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                                library.update(itemID) { $0.template = selected ? nil : t.rawValue }
                            }
                        } label: {
                            Label(t.name, systemImage: t.symbol)
                                .labelStyle(TightLabel())
                                .font(.caption.weight(.semibold))
                                .padding(.horizontal, 10).padding(.vertical, 7)
                                .background(selected ? Color.red : Color(white: 0.2), in: Capsule())
                                .foregroundStyle(.white)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 2)
            }
            if let template {
                ForEach(template.prompts) { prompt in
                    TextField(prompt.label, text: Binding(
                        get: { library.item(itemID)?.details[prompt.id] ?? "" },
                        set: { value in library.update(itemID) { $0.details[prompt.id] = value } }),
                              axis: .vertical)
                }
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        } header: {
            Text("Details")
        } footer: {
            Text("Pick what this was to get a checklist. Everything is optional and is kept apart from the recording's proof.")
        }
    }

    @ViewBuilder private func notesSection(_ item: CaseItem) -> some View {
        Section("Notes") {
            ForEach(item.notes) { note in
                VStack(alignment: .leading, spacing: 3) {
                    Text(note.text)
                    Text(note.createdAt.formatted(date: .abbreviated, time: .shortened))
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }
            .onDelete { offsets in library.update(itemID) { $0.notes.remove(atOffsets: offsets) } }
            HStack {
                TextField("Add a note", text: $newNote, axis: .vertical).focused($noteFocused)
                Button {
                    let text = newNote.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !text.isEmpty else { return }
                    withAnimation {
                        library.update(itemID) { $0.notes.append(ItemNote(id: UUID(), createdAt: Date(), text: text)) }
                    }
                    newNote = ""
                    noteFocused = false
                } label: {
                    Image(systemName: "arrow.up.circle.fill").font(.title2)
                }
                .buttonStyle(.borderless)
                .disabled(newNote.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    @ViewBuilder private func transcriptSection(_ item: CaseItem) -> some View {
        if CaseLibrary.transcriptAudioURL(for: item) != nil {
            Section {
                if let transcript = item.transcript {
                    if transcript.segments.isEmpty {
                        Text("No speech was found.").foregroundStyle(.secondary)
                    }
                    ForEach(transcript.segments) { segment in
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            Text(CaseReport.clock(segment.start))
                                .font(.caption.monospacedDigit()).foregroundStyle(.red)
                                .frame(width: 44, alignment: .leading)
                            Text(segment.text).textSelection(.enabled)
                        }
                    }
                }
                Button {
                    if pro.isPro { transcribing = true } else { showPaywall = true }
                } label: {
                    Label(LocalizedStringKey(item.transcript == nil ? "Transcribe" : "Transcribe again"),
                          systemImage: pro.isPro ? "text.bubble" : "lock.fill")
                }
            } header: {
                Text("Transcript")
            } footer: {
                if let t = item.transcript {
                    let language = Transcriber.name(of: Locale(identifier: t.localeIdentifier))
                    if t.onDevice {
                        Text("\(language) · automatic, check it against the recording")
                    } else {
                        Text("\(language) · automatic, made on Apple's servers. Check it against the recording.")
                    }
                } else if !pro.isPro {
                    Text("Witness Pro transcribes recordings on your iPhone, in the language you choose, and makes them searchable.")
                }
            }
        }
    }

    @ViewBuilder private func proofSection(_ item: CaseItem) -> some View {
        Section {
            if item.kind == .video {
                LabeledContent("Proof", value: String(localized: "Session manifest"))
                if let session = item.sessionID {
                    LabeledContent("Recording") { Text(session.uuidString.prefix(8)).monospaced() }
                }
            } else if let hash = item.sha256 {
                VStack(alignment: .leading, spacing: 4) {
                    Text("SHA-256").font(.caption).foregroundStyle(.secondary)
                    Text(hash).font(.caption2.monospaced()).textSelection(.enabled)
                }
            }
            Label(LocalizedStringKey(item.timestamped ? "Independently time-stamped" : "Time-stamp pending"),
                  systemImage: item.timestamped ? "checkmark.seal.fill" : "clock")
                .foregroundStyle(item.timestamped ? .green : .secondary)
            if item.kind != .video {
                Label(LocalizedStringKey(item.uploaded ? "Copied to your cloud account" : "Waiting to upload"),
                      systemImage: item.uploaded ? "icloud.and.arrow.up.fill" : "icloud.slash")
                    .foregroundStyle(item.uploaded ? .primary : .secondary)
            }
        } header: {
            Text("Proof")
        } footer: {
            if item.isImported {
                Text("Imported files are hashed and time-stamped when they're imported. That proves they haven't changed since then, not when they were made.")
            } else if item.kind == .video {
                Text("The recording is in Photos and your cloud account. Its manifest lists every segment's hash and is time-stamped once uploaded.")
            }
        }
    }

    private func subtitle(_ item: CaseItem) -> String {
        var parts = [item.createdAt.formatted(date: .long, time: .standard)]
        if let end = item.endedAt, item.kind == .video || item.kind == .audio {
            parts.append(CaseReport.clock(end.timeIntervalSince(item.createdAt)))
        }
        if let lat = item.latitude, let lon = item.longitude {
            parts.append(String(format: "%.4f, %.4f", lat, lon))
        }
        return parts.joined(separator: " · ")
    }

    private func togglePlay(_ item: CaseItem) {
        if playing { player?.pause(); playing = false; return }
        if player == nil, let url = CaseLibrary.transcriptAudioURL(for: item) {
            try? AVAudioSession.sharedInstance().setCategory(.playback)
            try? AVAudioSession.sharedInstance().setActive(true)
            player = try? AVAudioPlayer(contentsOf: url)
        }
        playing = player?.play() ?? false
    }
}
