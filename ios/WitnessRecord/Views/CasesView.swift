import AVFoundation
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Every case, plus whatever isn't filed in one yet. Opened from the Home
/// toolbar as a panel that slides in from the left, where its button is.
struct CasesView: View {
    let close: () -> Void

    @ObservedObject private var library = CaseLibrary.shared
    @ObservedObject private var pro = ProEntitlement.shared
    @State private var query = ""
    @State private var showNewCase = false
    @State private var newCaseTitle = ""
    @State private var showPaywall = false
    @State private var appeared = false
    @State private var path = NavigationPath()
    @State private var filing: UUID?

    var body: some View {
        NavigationStack(path: $path) {
            list.searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always),
                            prompt: Text("Search transcripts and notes"))
            .navigationTitle("Cases")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(action: close) { Image(systemName: "chevron.left") }
                        .accessibilityLabel("Close cases")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    AddEvidenceMenu(caseID: nil) {
                        Button { showNewCase = true } label: { Label("New case", systemImage: "folder.badge.plus") }
                        Button { AppState.shared.showCrew = true } label: { Label("Record as a crew", systemImage: "person.3") }
                    }
                }
            }
            .navigationDestination(for: UUID.self) { id in
                if library.cases.contains(where: { $0.id == id }) {
                    CaseDetailView(caseID: id)
                } else {
                    ItemDetailView(itemID: id)
                }
            }
            .alert("New case", isPresented: $showNewCase) {
                TextField("Name, e.g. Flat 4B deposit", text: $newCaseTitle)
                Button("Create") {
                    let created = library.newCase(titled: newCaseTitle)
                    newCaseTitle = ""
                    path.append(created.id)
                }
                Button("Cancel", role: .cancel) { newCaseTitle = "" }
            }
            .sheet(isPresented: $showPaywall) { PaywallView() }
            .sheet(item: $filing) { id in FileIntoCaseSheet(itemIDs: [id]) }
        }
        .onAppear {
            library.refreshRecordingProofs()
            withAnimation(.spring(response: 0.5, dampingFraction: 0.8).delay(0.08)) { appeared = true }
        }
    }

    @ViewBuilder private var list: some View {
        List {
            if !query.isEmpty && !pro.isPro {
                Section {
                    VStack(spacing: 10) {
                        Image(systemName: "lock.fill").font(.title2).foregroundStyle(.red)
                        Text("Search is a Witness Pro feature").font(.headline)
                        Text("Pro transcribes your recordings and lets you search everything that was said, across every case.")
                            .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        Button("Unlock Witness Pro") { showPaywall = true }
                            .buttonStyle(.borderedProminent).tint(.red).padding(.top, 4)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                }
            } else if !query.isEmpty {
                searchResults
            } else {
                Section {
                    if library.cases.isEmpty {
                        Button { showNewCase = true } label: {
                            Label("Create your first case", systemImage: "folder.badge.plus")
                        }
                    }
                    ForEach(Array(library.cases.enumerated()), id: \.element.id) { index, evidenceCase in
                        NavigationLink(value: evidenceCase.id) {
                            CaseRow(evidenceCase: evidenceCase, count: library.items(in: evidenceCase.id).count)
                        }
                        .opacity(appeared ? 1 : 0)
                        .offset(x: appeared ? 0 : -24)
                        .animation(.spring(response: 0.5, dampingFraction: 0.8).delay(Double(index) * 0.04), value: appeared)
                    }
                    .onDelete { offsets in
                        for i in offsets { library.deleteCase(library.cases[i].id) }
                    }
                } header: {
                    Text("Cases")
                } footer: {
                    Text("A case groups recordings, photos and files about one matter. Deleting a case keeps its items.")
                }

                let unfiled = library.items(in: nil)
                if !unfiled.isEmpty {
                    Section {
                        ForEach(unfiled) { item in
                            NavigationLink(value: item.id) { ItemRow(item: item) }
                                .itemContextMenu(item, showPaywall: $showPaywall)
                                .swipeActions(edge: .leading, allowsFullSwipe: true) {
                                    Button { filing = item.id } label: { Label("Add to case", systemImage: "folder.badge.plus") }
                                        .tint(.red)
                                }
                        }
                    } header: {
                        Text("Not in a case")
                    } footer: {
                        Text("Swipe right on a recording to add it to a case, or open a case and tap + to pick several.")
                    }
                }
            }
        }
        .animation(.default, value: library.cases)
    }

    @ViewBuilder private var searchResults: some View {
        let hits = library.search(query)
        if hits.isEmpty {
            ContentUnavailableView.search(text: query)
        } else {
            Section {
                ForEach(hits) { hit in
                    if let item = library.item(hit.itemID) {
                        NavigationLink(value: item.id) {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack(spacing: 6) {
                                    Text(itemTitle(item)).font(.subheadline.weight(.semibold))
                                    if let t = hit.time {
                                        Text(CaseReport.clock(t)).font(.caption.monospacedDigit())
                                            .foregroundStyle(.red)
                                    }
                                }
                                Text(highlighted(hit.text)).font(.callout).lineLimit(3)
                                if let caseID = item.caseID,
                                   let c = library.cases.first(where: { $0.id == caseID }) {
                                    Label(c.title, systemImage: "folder").font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            } header: {
                Text("\(hits.count) matches")
            }
        }
    }

    private func highlighted(_ text: String) -> AttributedString {
        var s = AttributedString(text)
        if let r = s.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) {
            s[r].foregroundColor = .red
            s[r].font = .callout.bold()
        }
        return s
    }
}

func itemTitle(_ item: CaseItem) -> String {
    if let title = item.title, !title.isEmpty { return title }
    return "\(CaseReport.kindName(item)) · \(item.createdAt.formatted(date: .abbreviated, time: .shortened))"
}

private struct CaseRow: View {
    let evidenceCase: EvidenceCase
    let count: Int

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "folder.fill")
                .font(.title3)
                .foregroundStyle(.red)
                .frame(width: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(evidenceCase.title).font(.headline)
                Text("^[\(count) item](inflect: true) · \(evidenceCase.createdAt.formatted(date: .abbreviated, time: .omitted))")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

struct ItemRow: View {
    let item: CaseItem

    var body: some View {
        HStack(spacing: 12) {
            ItemThumbnail(item: item).frame(width: 56, height: 56)
            VStack(alignment: .leading, spacing: 3) {
                Text(itemTitle(item)).font(.subheadline.weight(.semibold)).lineLimit(1)
                HStack(spacing: 8) {
                    if item.timestamped {
                        Label("Time-stamped", systemImage: "checkmark.seal.fill").foregroundStyle(.green)
                    }
                    if item.transcript != nil {
                        Label("Transcribed", systemImage: "text.quote").foregroundStyle(.secondary)
                    }
                    if item.isImported {
                        Label("Imported", systemImage: "square.and.arrow.down").foregroundStyle(.secondary)
                    }
                }
                .font(.caption2)
                .labelStyle(TightLabel())
            }
        }
        .padding(.vertical, 2)
    }
}

/// Icon and title close together, for small badges and chips.
struct TightLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) { configuration.icon; configuration.title }
    }
}

struct ItemThumbnail: View {
    let item: CaseItem

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10).fill(Color(white: 0.15))
            let image = UIImage(contentsOfFile: CaseLibrary.thumbnailURL(for: item.id).path)
            if let image {
                // In an overlay so a wide frame fills the tile instead of
                // stretching it past its frame.
                Color.clear.overlay(Image(uiImage: image).resizable().scaledToFill())
            } else {
                Image(systemName: symbol).font(.title3).foregroundStyle(.secondary)
            }
            if item.kind == .video, image != nil {
                Image(systemName: "play.fill").font(.caption).foregroundStyle(.white)
                    .padding(5).background(.black.opacity(0.5), in: Circle())
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private var symbol: String {
        switch item.kind {
        case .video: return "video.fill"
        case .photo: return "photo"
        case .audio: return "waveform"
        case .file: return "doc"
        }
    }
}

// MARK: - Case

struct CaseDetailView: View {
    let caseID: UUID

    @ObservedObject private var library = CaseLibrary.shared
    @ObservedObject private var pro = ProEntitlement.shared
    @State private var showRename = false
    @State private var newTitle = ""
    @State private var showPaywall = false
    @State private var shareItems: [URL] = []
    @State private var showShare = false
    @State private var exportError: String?
    @State private var showPicker = false

    private var evidenceCase: EvidenceCase? { library.cases.first { $0.id == caseID } }

    var body: some View {
        let items = library.items(in: caseID)
        List {
            if items.isEmpty {
                ContentUnavailableView {
                    Label("Nothing here yet", systemImage: "tray")
                } description: {
                    Text("Add past recordings, or a new photo, audio recording or file.")
                } actions: {
                    Button("Add recordings…") { showPicker = true }
                        .buttonStyle(.borderedProminent).tint(.red)
                }
            }
            ForEach(items) { item in
                NavigationLink(value: item.id) { ItemRow(item: item) }
                    .itemContextMenu(item, showPaywall: $showPaywall)
            }
            if !items.isEmpty {
                Section {
                    Button {
                        guard pro.isPro else { showPaywall = true; return }
                        export(items)
                    } label: {
                        Label("Export case report", systemImage: "doc.richtext")
                    }
                } footer: {
                    Text("One PDF covering every item, with each item's file, record and time-stamp proof. Long-press a recording to transcribe it.")
                }
            }
        }
        .navigationTitle(evidenceCase?.title ?? "")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                AddEvidenceMenu(caseID: caseID) {
                    Button { showPicker = true } label: {
                        Label("Add recordings…", systemImage: "rectangle.stack.badge.plus")
                    }
                    Button {
                        newTitle = evidenceCase?.title ?? ""
                        showRename = true
                    } label: { Label("Rename case", systemImage: "pencil") }
                }
            }
        }
        .alert("Rename case", isPresented: $showRename) {
            TextField("Name", text: $newTitle)
            Button("Save") { library.rename(caseID, to: newTitle) }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Couldn't build the report", isPresented: .constant(exportError != nil)) {
            Button("OK") { exportError = nil }
        } message: { Text(exportError ?? "") }
        .sheet(isPresented: $showPaywall) { PaywallView() }
        .sheet(isPresented: $showShare) { ShareSheet(items: shareItems) }
        .sheet(isPresented: $showPicker) { AddToCaseSheet(caseID: caseID) }
    }

    private func export(_ items: [CaseItem]) {
        guard let evidenceCase else { return }
        do {
            shareItems = try CaseReport.package(for: evidenceCase, items: items)
            showShare = true
        } catch {
            exportError = error.localizedDescription
        }
    }
}

// MARK: - Filing

/// Every recording and item not already in this case, newest first, to tick
/// and add in one go. Those in another case are listed separately and say
/// where they are now, since adding them here moves them.
struct AddToCaseSheet: View {
    let caseID: UUID

    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var library = CaseLibrary.shared
    @State private var selected: Set<UUID> = []

    var body: some View {
        let others = library.items.filter { $0.caseID != caseID }.sorted { $0.createdAt > $1.createdAt }
        let unfiled = others.filter { $0.caseID == nil }
        let elsewhere = others.filter { $0.caseID != nil }
        NavigationStack {
            List {
                if others.isEmpty {
                    ContentUnavailableView("Nothing else to add", systemImage: "checkmark.circle",
                                           description: Text("Every recording is already in this case."))
                }
                if !unfiled.isEmpty {
                    Section("Not in a case") { ForEach(unfiled) { pickRow($0) } }
                }
                if !elsewhere.isEmpty {
                    Section {
                        ForEach(elsewhere) { pickRow($0) }
                    } header: {
                        Text("In another case")
                    } footer: {
                        Text("Adding one of these moves it here.")
                    }
                }
            }
            .navigationTitle("Add recordings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(selected.isEmpty ? String(localized: "Add") : String(localized: "Add (\(selected.count))")) {
                        withAnimation { library.move(selected, to: caseID) }
                        dismiss()
                    }
                    .disabled(selected.isEmpty)
                }
            }
        }
    }

    private func pickRow(_ item: CaseItem) -> some View {
        let on = selected.contains(item.id)
        return Button {
            withAnimation(.spring(response: 0.25, dampingFraction: 0.8)) {
                if on { selected.remove(item.id) } else { selected.insert(item.id) }
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: on ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(on ? Color.red : Color(.tertiaryLabel))
                    .contentTransition(.symbolEffect(.replace))
                ItemRow(item: item)
                if let other = item.caseID, let c = library.cases.first(where: { $0.id == other }) {
                    Spacer(minLength: 0)
                    Text(c.title).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

/// The swipe action's case picker: the existing cases, or a new one.
struct FileIntoCaseSheet: View {
    let itemIDs: Set<UUID>

    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var library = CaseLibrary.shared
    @State private var newTitle = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        TextField("New case, e.g. Flat 4B deposit", text: $newTitle)
                        Button("Create") {
                            let created = library.newCase(titled: newTitle)
                            library.move(itemIDs, to: created.id)
                            dismiss()
                        }
                        .disabled(newTitle.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
                if !library.cases.isEmpty {
                    Section("Cases") {
                        ForEach(library.cases) { c in
                            Button {
                                library.move(itemIDs, to: c.id)
                                dismiss()
                            } label: {
                                Label(c.title, systemImage: "folder.fill")
                            }
                        }
                    }
                }
            }
            .navigationTitle("Add to case")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - Long press

private struct ItemContextMenu: ViewModifier {
    let item: CaseItem
    @Binding var showPaywall: Bool
    @ObservedObject private var library = CaseLibrary.shared
    @ObservedObject private var pro = ProEntitlement.shared
    @State private var transcribing = false

    func body(content: Content) -> some View {
        content
            .contextMenu {
                if CaseLibrary.transcriptAudioURL(for: item) != nil {
                    Button {
                        if pro.isPro { transcribing = true } else { showPaywall = true }
                    } label: {
                        Label(LocalizedStringKey(item.transcript == nil ? "Transcribe…" : "Transcribe again…"),
                              systemImage: pro.isPro ? "text.bubble" : "lock")
                    }
                }
                Menu {
                    Button("Not in a case") { library.move(item.id, to: nil) }
                    ForEach(library.cases) { c in
                        Button(c.title) { library.move(item.id, to: c.id) }
                    }
                } label: {
                    Label("Move to case", systemImage: "folder")
                }
            }
            .sheet(isPresented: $transcribing) { TranscribeSheet(itemID: item.id) }
    }
}

extension View {
    func itemContextMenu(_ item: CaseItem, showPaywall: Binding<Bool>) -> some View {
        modifier(ItemContextMenu(item: item, showPaywall: showPaywall))
    }
}

// MARK: - Transcribe

struct TranscribeSheet: View {
    let itemID: UUID

    @Environment(\.dismiss) private var dismiss
    @AppStorage("transcriptLanguage") private var languageID = Locale.current.identifier
    @State private var progress: Double?
    @State private var error: String?
    @State private var allowServer = false
    @State private var task: Task<Void, Never>?
    /// Redraws once the newer engine's languages are known.
    @State private var refresh = false

    private var locale: Locale { Locale(identifier: languageID) }

    var body: some View {
        let _ = refresh
        NavigationStack {
            Form {
                Section {
                    Picker("Language", selection: $languageID) {
                        ForEach(Transcriber.languages, id: \.identifier) { l in
                            Text(Transcriber.name(of: l)).tag(l.identifier)
                        }
                    }
                } footer: {
                    if Transcriber.canRunOnDevice(locale) {
                        Label("Transcribed on this iPhone. The audio never leaves it.", systemImage: "lock.iphone")
                    } else {
                        Text("This language can't be transcribed on this iPhone, only on Apple's servers. The audio would be sent to Apple to do it.")
                    }
                }
                if !Transcriber.canRunOnDevice(locale) {
                    Toggle("Use Apple's servers for this", isOn: $allowServer)
                }
                if let progress {
                    Section {
                        ProgressView(value: progress) {
                            Text("Transcribing… \(Int(progress * 100))%")
                        }
                    }
                }
                if let error {
                    Section { Text(error).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Transcribe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { task?.cancel(); dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Start") { start() }
                        .disabled(progress != nil || (!Transcriber.canRunOnDevice(locale) && !allowServer))
                }
            }
        }
        .presentationDetents([.medium])
        .interactiveDismissDisabled(progress != nil)
        .onAppear { languageID = Transcriber.bestMatch(for: languageID).identifier }
        .task { await Transcriber.prepare(); refresh.toggle() }
    }

    private func start() {
        guard let item = CaseLibrary.shared.item(itemID),
              let audio = CaseLibrary.transcriptAudioURL(for: item) else { return }
        error = nil
        progress = 0
        let locale = self.locale
        let server = allowServer
        task = Task {
            guard await Transcriber.authorize() else {
                error = String(localized: "Speech recognition is turned off for Witness. Turn it on in Settings.")
                progress = nil
                return
            }
            do {
                let transcript = try await Transcriber.transcribe(audio, locale: locale, allowServer: server) { p in
                    Task { @MainActor in progress = p }
                }
                CaseLibrary.shared.setTranscript(transcript, for: itemID)
                dismiss()
            } catch is CancellationError {
            } catch {
                self.error = error.localizedDescription
                progress = nil
            }
        }
    }
}

// MARK: - Adding

/// The + menu: a verified photo, an audio recording, or an import.
struct AddEvidenceMenu<Extra: View>: View {
    let caseID: UUID?
    @ViewBuilder let extra: () -> Extra

    @State private var showCamera = false
    @State private var showAudio = false
    @State private var showFileImporter = false
    @State private var showPhotoPicker = false
    @State private var picked: [PhotosPickerItem] = []
    @State private var importing = false

    var body: some View {
        Menu {
            extra()
            Section {
                Button { showCamera = true } label: { Label("Take a verified photo", systemImage: "camera") }
                Button { showAudio = true } label: { Label("Record audio only", systemImage: "mic") }
            }
            Section("Import") {
                Button { showPhotoPicker = true } label: {
                    Label("Screen recording or photo", systemImage: "record.circle")
                }
                Button { showFileImporter = true } label: { Label("File", systemImage: "doc") }
            }
        } label: {
            if importing { ProgressView() } else { Image(systemName: "plus") }
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { image in
                Task { await CaseLibrary.shared.addPhoto(image, to: caseID) }
            }
            .ignoresSafeArea()
        }
        .sheet(isPresented: $showAudio) { AudioRecorderView(caseID: caseID) }
        .photosPicker(isPresented: $showPhotoPicker, selection: $picked,
                      matching: .any(of: [.videos, .images, .screenRecordings]))
        .onChange(of: picked) { _, items in
            guard !items.isEmpty else { return }
            picked = []
            importing = true
            Task {
                for item in items { await importPicked(item) }
                importing = false
            }
        }
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.item],
                      allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            importing = true
            Task {
                for url in urls {
                    let scoped = url.startAccessingSecurityScopedResource()
                    let tmp = FileManager.default.temporaryDirectory
                        .appendingPathComponent(UUID().uuidString)
                        .appendingPathExtension(url.pathExtension)
                    try? FileManager.default.copyItem(at: url, to: tmp)
                    if scoped { url.stopAccessingSecurityScopedResource() }
                    await CaseLibrary.shared.addFile(moveFrom: tmp, kind: .file, imported: true,
                                                     title: url.lastPathComponent, to: caseID)
                }
                importing = false
            }
        }
    }

    private func importPicked(_ item: PhotosPickerItem) async {
        if item.supportedContentTypes.contains(where: { $0.conforms(to: .movie) }),
           let movie = try? await item.loadTransferable(type: PickedMovie.self) {
            await CaseLibrary.shared.addFile(moveFrom: movie.url, kind: .file, imported: true, to: caseID)
        } else if let data = try? await item.loadTransferable(type: Data.self) {
            let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
            let tmp = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString).appendingPathExtension(ext)
            guard (try? data.write(to: tmp)) != nil else { return }
            await CaseLibrary.shared.addFile(moveFrom: tmp, kind: .file, imported: true, to: caseID)
        }
    }
}

extension AddEvidenceMenu where Extra == EmptyView {
    init(caseID: UUID?) { self.init(caseID: caseID) { EmptyView() } }
}

struct PickedMovie: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { SentTransferredFile($0.url) } importing: { received in
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(received.file.pathExtension)
            try FileManager.default.copyItem(at: received.file, to: dest)
            return PickedMovie(url: dest)
        }
    }
}

struct CameraPicker: UIViewControllerRepresentable {
    let onPhoto: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage { parent.onPhoto(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}

/// Audio only: discreet, light on battery, for meetings and calls where
/// recording them is lawful.
struct AudioRecorderView: View {
    let caseID: UUID?

    @Environment(\.dismiss) private var dismiss
    @State private var recorder: AVAudioRecorder?
    @State private var startedAt: Date?
    @State private var level: CGFloat = 0
    @State private var failed = false

    private let tick = Timer.publish(every: 0.08, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 28) {
            Text("Audio recording").font(.headline).padding(.top, 24)
            TimelineView(.periodic(from: .now, by: 1)) { _ in
                Text(elapsed).font(.system(size: 52, weight: .light).monospacedDigit())
            }
            Circle()
                .fill(.red.opacity(0.25))
                .frame(width: 120 + level * 90, height: 120 + level * 90)
                .overlay(Circle().fill(.red).frame(width: 96, height: 96))
                .overlay(Image(systemName: recorder == nil ? "mic.fill" : "stop.fill")
                    .font(.system(size: 34, weight: .bold)).foregroundStyle(.white))
                .frame(height: 220)
                .animation(.easeOut(duration: 0.08), value: level)
                .onTapGesture { recorder == nil ? start() : stop() }
            Text(LocalizedStringKey(recorder == nil ? "Tap to start" : "Tap to stop and save"))
                .font(.subheadline).foregroundStyle(.secondary)
            Text("Recording people may need their consent where you are. Audio is hashed and time-stamped like video.")
                .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            if failed { Text("Couldn't start the microphone.").foregroundStyle(.red) }
            Spacer()
            Button("Cancel") { cancel() }.padding(.bottom)
        }
        .presentationDetents([.large])
        .interactiveDismissDisabled(recorder != nil)
        .onReceive(tick) { _ in
            guard let recorder else { return }
            recorder.updateMeters()
            level = CGFloat(max(0, (recorder.averagePower(forChannel: 0) + 50) / 50))
        }
    }

    private var elapsed: String {
        guard let startedAt else { return "0:00" }
        return CaseReport.clock(Date().timeIntervalSince(startedAt))
    }

    private func start() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("audio-\(UUID().uuidString).m4a")
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
            let r = try AVAudioRecorder(url: url, settings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: 44100,
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
            ])
            r.isMeteringEnabled = true
            guard r.record() else { failed = true; return }
            recorder = r
            startedAt = Date()
            UIApplication.shared.isIdleTimerDisabled = true
            LocationService.shared.requestOneShotLocation()
        } catch {
            failed = true
        }
    }

    private func stop() {
        guard let recorder else { return }
        recorder.stop()
        UIApplication.shared.isIdleTimerDisabled = false
        let url = recorder.url
        let started = startedAt
        self.recorder = nil
        Task {
            await CaseLibrary.shared.addFile(moveFrom: url, kind: .audio, imported: false,
                                            startedAt: started, to: caseID)
        }
        dismiss()
    }

    private func cancel() {
        if let recorder {
            // Never thrown away silently: a recording in progress is kept.
            stop()
            _ = recorder
        } else {
            dismiss()
        }
    }
}
