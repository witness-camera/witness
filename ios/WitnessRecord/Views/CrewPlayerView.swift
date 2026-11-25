import AVFoundation
import AVKit
import Combine
import SwiftUI

/// Every angle of a crew on one timeline, on the phone: play, scrub, and cut
/// between cameras into one edit, then export it. The point is to see
/// straight away what filming together bought — the moment one phone missed,
/// another caught — and to hand someone that moment as a single video.
struct CrewPlayerView: View {
    let crewID: UUID
    @ObservedObject private var store = CrewStore.shared
    @ObservedObject private var previews = CrewPreviews.shared
    @StateObject private var model = CrewPlayerModel()
    @State private var exportRange: CrewPlayerModel.ExportRange = .whole

    private var crew: Crew? { store.crew(crewID) }

    var body: some View {
        VStack(spacing: 0) {
            screen
            if let crew {
                controls(crew)
            }
        }
        .background(Color.black)
        .navigationTitle(crew?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if let crew { previews.sync(crew); model.load(crew, previews: previews) }
        }
        .onChange(of: previews.available) { _, _ in
            if let crew { model.load(crew, previews: previews) }
        }
        .onChange(of: crew?.angles.count) { _, _ in
            if let crew { previews.sync(crew); model.load(crew, previews: previews) }
        }
        .onChange(of: model.hasInOut) { _, has in exportRange = has ? .inOut : .whole }
        .onDisappear { model.pause() }
        // New footage keeps arriving while the crew is still filming: the
        // crew's sync and the previews are checked on a short interval for
        // as long as the player is open, and lanes and cameras appear as
        // they land.
        .task {
            while !Task.isCancelled {
                CrewCloud.shared.focusedCrewID = crewID
                CrewCloud.shared.syncNow()
                if let crew { previews.sync(crew) }
                try? await Task.sleep(for: .seconds(20))
            }
        }
    }

    // MARK: - Screen

    private var screen: some View {
        ZStack {
            Color.black
            if let player = model.selectedPlayer, model.selectedCovers {
                PlayerLayerView(player: player)
            } else {
                VStack(spacing: 8) {
                    Image(systemName: "video.slash").font(.largeTitle)
                    Text(model.angles.isEmpty || model.readyCount == 0
                         ? String(localized: "No previews yet")
                         : String(localized: "No camera was filming at this moment"))
                        .font(.callout)
                }
                .foregroundStyle(.white.opacity(0.7))
            }
            VStack {
                HStack {
                    if let angle = model.selectedAngle, model.selectedCovers, let crew {
                        HStack(spacing: 6) {
                            Circle().fill(model.color(for: angle.id)).frame(width: 8, height: 8)
                            Text(label(angle, in: crew))
                        }
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(.black.opacity(0.6), in: Capsule())
                        .foregroundStyle(.white)
                    }
                    Spacer()
                    Text(model.clockText)
                        .font(.caption.monospacedDigit())
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(.black.opacity(0.6), in: Capsule())
                        .foregroundStyle(.white)
                }
                Spacer()
            }
            .padding(10)
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .frame(maxWidth: .infinity)
    }

    // MARK: - Controls

    private func controls(_ crew: Crew) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                transport
                timeline(crew)
                editControls
                onNow(crew)

                Text("Cameras")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                ForEach(crew.angles.sorted { $0.startedAt < $1.startedAt }) { angle in
                    cameraButton(angle, in: crew)
                }

                exportSection(crew)

                Text("Tap a camera to cut to it at the playhead. Cuts are saved on this iPhone and playback follows them; if the chosen camera wasn't filming, another one that was takes over. Previews are small copies shared through the crew's iCloud sync, sealed with the crew's key, and arrive a minute or two after a phone stops recording. The full footage stays in each member's own cloud.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding()
        }
        .background(Color(.systemBackground))
    }

    private var transport: some View {
        HStack(spacing: 18) {
            Button { model.skip(-10) } label: { Image(systemName: "gobackward.10") }
            Button { model.skip(-1) } label: { Image(systemName: "chevron.left") }
                .accessibilityLabel("Back one second")
            Button { model.togglePlay() } label: {
                Image(systemName: model.isPlaying ? "pause.fill" : "play.fill").font(.title)
            }
            Button { model.skip(1) } label: { Image(systemName: "chevron.right") }
                .accessibilityLabel("Forward one second")
            Button { model.skip(10) } label: { Image(systemName: "goforward.10") }
            Spacer()
            Text(model.positionText).font(.footnote.monospacedDigit()).foregroundStyle(.secondary)
        }
        .disabled(model.angles.isEmpty)
    }

    private var editControls: some View {
        HStack(spacing: 8) {
            Button { model.setIn() } label: { Label("In", systemImage: "arrow.right.to.line") }
            Button { model.setOut() } label: { Label("Out", systemImage: "arrow.left.to.line") }
            Button { model.zoomed.toggle() } label: {
                Image(systemName: model.zoomed ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right")
            }
            .disabled(!model.hasInOut)
            .accessibilityLabel(model.zoomed ? "Show the whole timeline" : "Zoom to In and Out")
            Spacer()
            Button("Remove cut") { model.removeCutNearPlayhead() }
                .disabled(model.cutNearPlayhead == nil)
            Menu {
                Button("Go to In") { model.goToIn() }.disabled(model.edit.inPoint == nil)
                Button("Go to Out") { model.goToOut() }.disabled(model.edit.outPoint == nil)
                Button("Clear In and Out", role: .destructive) { model.clearInOut() }
                    .disabled(model.edit.inPoint == nil && model.edit.outPoint == nil)
                Button("Clear all cuts", role: .destructive) { model.clearCuts() }
                    .disabled(model.edit.cuts.isEmpty)
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .accessibilityLabel("More editing options")
        }
        .buttonStyle(.bordered)
        .controlSize(.small)
        .disabled(model.angles.isEmpty)
    }

    /// The cameras filming at the playhead, side by side, for cutting
    /// between angles of the same moment with one tap.
    @ViewBuilder
    private func onNow(_ crew: Crew) -> some View {
        let live = crew.angles.filter { model.isReady($0.id) && model.covers($0) }
            .sorted { $0.startedAt < $1.startedAt }
        if live.count > 1 {
            VStack(alignment: .leading, spacing: 6) {
                Text("\(live.count) cameras at this moment")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.secondary)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(live) { angle in
                            Button(label(angle, in: crew)) { model.cut(to: angle.id) }
                                .buttonStyle(.borderedProminent)
                                .tint(angle.id == model.selectedID ? model.color(for: angle.id) : .gray)
                        }
                    }
                }
            }
        }
    }

    /// The edit on top — which camera is on when, with its cuts — and one
    /// lane per angle below showing when it was filming. In and Out dim the
    /// rest; drag anywhere to scrub.
    private func timeline(_ crew: Crew) -> some View {
        let sorted = crew.angles.sorted { $0.startedAt < $1.startedAt }
        let lanesHeight = CGFloat(max(sorted.count, 1)) * 14
        let editHeight: CGFloat = 18
        let height = editHeight + 8 + lanesHeight
        return GeometryReader { geo in
            let width = geo.size.width
            ZStack(alignment: .topLeading) {
                VStack(alignment: .leading, spacing: 8) {
                    editLane(width: width)
                        .frame(height: editHeight)
                    VStack(spacing: 4) {
                        ForEach(sorted) { angle in
                            let (x, w) = model.span(of: angle, width: width)
                            ZStack(alignment: .leading) {
                                Capsule().fill(Color.secondary.opacity(0.15))
                                Capsule()
                                    .fill(model.color(for: angle.id).opacity(model.isReady(angle.id) ? 0.85 : 0.3))
                                    .frame(width: max(w, 4))
                                    .offset(x: x)
                            }
                            .frame(height: 10)
                            .clipped()
                        }
                    }
                }
                inOutMarks(width: width, height: height)
                Rectangle()
                    .fill(Color.red)
                    .frame(width: 2, height: height)
                    .offset(x: model.playheadX(width: width) - 1)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                model.scrub(toFraction: value.location.x / max(width, 1))
            })
        }
        .frame(height: height)
    }

    private func editLane(width: CGFloat) -> some View {
        ZStack(alignment: .leading) {
            RoundedRectangle(cornerRadius: 4).fill(Color.secondary.opacity(0.12))
            ForEach(model.visibleSegments) { segment in
                let (x, w) = model.span(from: segment.start, to: segment.end, width: width)
                Rectangle()
                    .fill(segment.angleID.map { model.color(for: $0) } ?? .clear)
                    .frame(width: max(w, 1))
                    .offset(x: x)
            }
            ForEach(model.visibleCuts, id: \.at) { cut in
                Image(systemName: "arrowtriangle.down.fill")
                    .font(.system(size: 9))
                    .foregroundStyle(.primary)
                    .offset(x: model.x(for: cut.at, width: width) - 4.5, y: -9)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 4))
        .accessibilityLabel("Edit: \(model.edit.cuts.count) cuts")
    }

    @ViewBuilder
    private func inOutMarks(width: CGFloat, height: CGFloat) -> some View {
        if let inDate = model.edit.inPoint {
            let x = model.x(for: inDate, width: width)
            Rectangle().fill(Color.black.opacity(0.35)).frame(width: max(x, 0), height: height)
            Rectangle().fill(Color.yellow).frame(width: 2, height: height).offset(x: x - 1)
        }
        if let outDate = model.edit.outPoint {
            let x = model.x(for: outDate, width: width)
            Rectangle().fill(Color.black.opacity(0.35)).frame(width: max(width - x, 0), height: height).offset(x: x)
            Rectangle().fill(Color.yellow).frame(width: 2, height: height).offset(x: x - 1)
        }
    }

    private func cameraButton(_ angle: CrewAngle, in crew: Crew) -> some View {
        let ready = model.isReady(angle.id)
        let covers = model.covers(angle)
        return Button {
            model.cut(to: angle.id)
        } label: {
            HStack {
                Circle().fill(model.color(for: angle.id)).frame(width: 10, height: 10)
                Image(systemName: angle.id == model.selectedID ? "video.fill" : "video")
                VStack(alignment: .leading, spacing: 2) {
                    Text(label(angle, in: crew))
                    Text(status(angle, ready: ready, covers: covers))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if previews.inFlight.contains(angle.id) { ProgressView() }
            }
            .padding(10)
            .background(angle.id == model.selectedID ? model.color(for: angle.id).opacity(0.18) : Color.secondary.opacity(0.08),
                        in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(!ready)
    }

    // MARK: - Export

    private func exportSection(_ crew: Crew) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Export")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.secondary)
            Picker("Range", selection: $exportRange) {
                Text("Whole timeline").tag(CrewPlayerModel.ExportRange.whole)
                Text("In to Out").tag(CrewPlayerModel.ExportRange.inOut)
            }
            .pickerStyle(.segmented)
            .disabled(!model.hasInOut)

            Button {
                Task { await model.export(exportRange, title: crew.title) }
            } label: {
                HStack {
                    if model.exporting { ProgressView().tint(.white) }
                    Text(model.exporting ? String(localized: "Exporting…") : String(localized: "Export video"))
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.exporting || model.readyCount == 0)

            if let url = model.exportedURL {
                HStack {
                    ShareLink(item: url) { Label("Share", systemImage: "square.and.arrow.up") }
                    Button {
                        Task { await model.saveExportToPhotos() }
                    } label: {
                        Label(model.savedToPhotos ? String(localized: "Saved to Photos") : String(localized: "Save to Photos"),
                              systemImage: model.savedToPhotos ? "checkmark" : "photo.on.rectangle")
                    }
                    .disabled(model.savedToPhotos)
                }
                .buttonStyle(.bordered)
            }
            if let error = model.exportError {
                Text(error).font(.caption).foregroundStyle(.red)
            }
            Text("Made from the crew's previews, so it's preview quality. For full quality, use Make multicam edit files on the crew screen and conform the full footage in your editor.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Labels

    private func label(_ angle: CrewAngle, in crew: Crew) -> String {
        let isMine = angle.memberID == store.myMemberID(in: crew.id)
        let name = isMine ? String(localized: "You")
            : (crew.members.first { $0.id == angle.memberID }?.name ?? String(localized: "Crew phone"))
        let sameMember = crew.angles.filter { $0.memberID == angle.memberID }
        if sameMember.count > 1 {
            let n = (sameMember.sorted { $0.startedAt < $1.startedAt }.firstIndex { $0.id == angle.id } ?? 0) + 1
            return "\(name) · \(n)"
        }
        return name
    }

    private func status(_ angle: CrewAngle, ready: Bool, covers: Bool) -> String {
        if !ready {
            return previews.inFlight.contains(angle.id)
                ? String(localized: "Getting preview…")
                : String(localized: "Preview not available yet")
        }
        return covers ? String(localized: "Filming at this moment") : String(localized: "Not filming at this moment")
    }
}

/// Plays all angles against one clock, following the edit: at every moment
/// the angle shown and heard is the one cut to, or, where it wasn't filming,
/// one that was. Switching seeks the new angle to the same crew-clock moment.
@MainActor
final class CrewPlayerModel: ObservableObject {
    enum ExportRange: Hashable { case whole, inOut }

    struct Segment: Identifiable {
        var id: Double { start }
        let start: Double
        let end: Double
        let angleID: String?
    }

    @Published private(set) var angles: [CrewAngle] = []
    @Published private(set) var selectedID: String?
    @Published private(set) var isPlaying = false
    /// Seconds since the earliest angle began.
    @Published private(set) var position: Double = 0
    @Published private(set) var edit = CrewEdit()
    /// Fits the timeline to In and Out, for placing cuts in a long event.
    @Published var zoomed = false
    @Published private(set) var exporting = false
    @Published private(set) var exportedURL: URL?
    @Published private(set) var exportError: String?
    @Published private(set) var savedToPhotos = false

    private var crewID: UUID?
    private var players: [String: AVPlayer] = [:]
    private var urls: [String: URL] = [:]
    private var origin = Date()
    private var end = Date()
    private var ticker: AnyCancellable?

    private static let palette: [Color] = [.orange, .blue, .green, .pink, .purple, .teal, .yellow, .indigo, .mint, .brown]

    var selectedAngle: CrewAngle? { angles.first { $0.id == selectedID } }
    var selectedPlayer: AVPlayer? { selectedID.flatMap { players[$0] } }
    var selectedCovers: Bool { selectedAngle.map(covers) ?? false }
    var readyCount: Int { players.count }
    var hasInOut: Bool {
        guard let i = edit.inPoint, let o = edit.outPoint else { return false }
        return o > i
    }
    private var total: Double { max(end.timeIntervalSince(origin), 1) }
    private var now: Date { origin.addingTimeInterval(position) }

    func load(_ crew: Crew, previews: CrewPreviews) {
        guard !crew.angles.isEmpty else { return }
        if crewID != crew.id {
            crewID = crew.id
            edit = CrewEdit.load(crew.id)
        }
        origin = crew.angles.map(\.startedAt).min() ?? Date()
        end = crew.angles.map { $0.startedAt.addingTimeInterval($0.duration) }.max() ?? origin
        for angle in crew.angles where players[angle.id] == nil {
            guard let url = previews.url(for: angle) else { continue }
            let player = AVPlayer(url: url)
            player.isMuted = true
            player.automaticallyWaitsToMinimizeStalling = false
            players[angle.id] = player
            urls[angle.id] = url
        }
        angles = crew.angles
        follow()
    }

    // MARK: - Which camera, when

    func isReady(_ id: String) -> Bool { players[id] != nil }

    func covers(_ angle: CrewAngle) -> Bool { covers(angle, at: now) }

    private func covers(_ angle: CrewAngle, at date: Date) -> Bool {
        let t = date.timeIntervalSince(angle.startedAt)
        return t >= 0 && t < min(angle.duration, CrewPreviews.maxPreviewSeconds)
    }

    /// The angle the edit shows at a moment: the one cut to if it was
    /// filming and has a preview here, otherwise the earliest-started one
    /// that was. Nil when no camera covers it.
    func effectiveID(at date: Date) -> String? {
        let ready = angles.filter { isReady($0.id) }
        if let chosen = edit.chosenAngle(at: date), let angle = ready.first(where: { $0.id == chosen }),
           covers(angle, at: date) {
            return chosen
        }
        return ready.filter { covers($0, at: date) }.sorted { $0.startedAt < $1.startedAt }.first?.id
    }

    /// The edit between two timeline positions, as stretches of one angle
    /// each (or none), split wherever a cut is or a camera starts or stops.
    func segments(from a: Double, to b: Double) -> [Segment] {
        guard b > a else { return [] }
        var bounds: Set<Double> = [a, b]
        for cut in edit.cuts {
            let t = cut.at.timeIntervalSince(origin)
            if t > a && t < b { bounds.insert(t) }
        }
        for angle in angles where isReady(angle.id) {
            let start = angle.startedAt.timeIntervalSince(origin)
            for t in [start, start + min(angle.duration, CrewPreviews.maxPreviewSeconds)] where t > a && t < b {
                bounds.insert(t)
            }
        }
        let sorted = bounds.sorted()
        var result: [Segment] = []
        for (t0, t1) in zip(sorted, sorted.dropFirst()) where t1 - t0 > 0.001 {
            let id = effectiveID(at: origin.addingTimeInterval((t0 + t1) / 2))
            if let last = result.last, last.angleID == id {
                result[result.count - 1] = Segment(start: last.start, end: t1, angleID: id)
            } else {
                result.append(Segment(start: t0, end: t1, angleID: id))
            }
        }
        return result
    }

    var visibleSegments: [Segment] {
        let w = window
        return segments(from: w.start, to: w.end)
    }

    var visibleCuts: [CrewEdit.Cut] {
        let w = window
        return edit.cuts.filter {
            let t = $0.at.timeIntervalSince(origin)
            return t >= w.start && t <= w.end
        }
    }

    func color(for angleID: String) -> Color {
        let index = angles.sorted { $0.startedAt < $1.startedAt }.firstIndex { $0.id == angleID } ?? 0
        return Self.palette[index % Self.palette.count]
    }

    // MARK: - Editing

    /// Cuts to a camera at the playhead. A cut already within a fraction of
    /// a second of it is replaced rather than stacked.
    func cut(to id: String) {
        guard isReady(id) else { return }
        edit.cuts.removeAll { abs($0.at.timeIntervalSince(now)) < 0.3 }
        edit.cuts.append(CrewEdit.Cut(at: now, angleID: id))
        saveEdit()
        follow()
    }

    /// A cut close enough to the playhead to be the one meant by "remove".
    var cutNearPlayhead: CrewEdit.Cut? {
        let reach = max(0.5, (window.end - window.start) * 0.015)
        return edit.cuts
            .filter { abs($0.at.timeIntervalSince(now)) <= reach }
            .min { abs($0.at.timeIntervalSince(now)) < abs($1.at.timeIntervalSince(now)) }
    }

    func removeCutNearPlayhead() {
        guard let cut = cutNearPlayhead else { return }
        edit.cuts.removeAll { $0 == cut }
        saveEdit()
        follow()
    }

    func clearCuts() {
        edit.cuts.removeAll()
        saveEdit()
        follow()
    }

    func setIn() {
        edit.inPoint = now
        if let out = edit.outPoint, out <= now { edit.outPoint = nil }
        saveEdit()
    }

    func setOut() {
        edit.outPoint = now
        if let inPoint = edit.inPoint, inPoint >= now { edit.inPoint = nil }
        saveEdit()
    }

    func clearInOut() {
        edit.inPoint = nil
        edit.outPoint = nil
        zoomed = false
        saveEdit()
    }

    func goToIn() { if let d = edit.inPoint { seek(to: d.timeIntervalSince(origin)) } }
    func goToOut() { if let d = edit.outPoint { seek(to: d.timeIntervalSince(origin)) } }

    private func saveEdit() {
        if let crewID { edit.save(crewID) }
        if !hasInOut { zoomed = false }
    }

    // MARK: - Playback

    func togglePlay() { isPlaying ? pause() : play() }

    func play() {
        if position >= total { position = 0 }
        isPlaying = true
        follow(force: true)
        let start = Date().addingTimeInterval(-position)
        ticker = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect().sink { [weak self] _ in
            guard let self else { return }
            let wasCovered = self.selectedCovers
            self.position = min(Date().timeIntervalSince(start), self.total)
            if self.position >= self.total { self.pause(); return }
            // A cut, or the shown camera starting or stopping mid-play.
            if self.effectiveID(at: self.now) != self.selectedID || wasCovered != self.selectedCovers {
                self.follow(force: true)
            }
        }
    }

    func pause() {
        isPlaying = false
        ticker = nil
        players.values.forEach { $0.pause() }
    }

    func skip(_ seconds: Double) {
        seek(to: position + seconds)
    }

    func scrub(toFraction f: Double) {
        let w = window
        seek(to: w.start + (w.end - w.start) * min(max(f, 0), 1))
    }

    private func seek(to seconds: Double) {
        let wasPlaying = isPlaying
        pause()
        position = min(max(seconds, 0), total)
        follow(force: true)
        if wasPlaying { play() }
    }

    /// Puts the edit's angle for the playhead on screen. Only the shown angle
    /// plays and is heard; the rest stay paused, so a cut costs only a seek.
    private func follow(force: Bool = true) {
        if let id = effectiveID(at: now) { selectedID = id }
        for (id, player) in players where id != selectedID {
            player.pause()
            player.isMuted = true
        }
        guard let angle = selectedAngle, let player = selectedPlayer else { return }
        player.isMuted = false
        if covers(angle) {
            player.seek(to: CMTime(seconds: now.timeIntervalSince(angle.startedAt), preferredTimescale: 600),
                        toleranceBefore: .zero, toleranceAfter: .zero)
            if isPlaying { player.play() } else { player.pause() }
        } else {
            player.pause()
        }
    }

    // MARK: - Export

    func export(_ range: ExportRange, title: String) async {
        pause()
        let (a, b): (Double, Double)
        if range == .inOut, hasInOut, let i = edit.inPoint, let o = edit.outPoint {
            (a, b) = (max(i.timeIntervalSince(origin), 0), min(o.timeIntervalSince(origin), total))
        } else {
            (a, b) = (0, total)
        }
        let pieces = segments(from: a, to: b).map { segment -> CrewEditExport.Piece in
            guard let id = segment.angleID, let angle = angles.first(where: { $0.id == id }), let url = urls[id] else {
                return CrewEditExport.Piece(url: nil, sourceStart: 0, duration: segment.end - segment.start)
            }
            return CrewEditExport.Piece(url: url,
                                        sourceStart: segment.start - angle.startedAt.timeIntervalSince(origin),
                                        duration: segment.end - segment.start)
        }
        exporting = true
        exportError = nil
        savedToPhotos = false
        defer { exporting = false }
        do {
            exportedURL = try await CrewEditExport.export(pieces, title: title)
            await EventLogger.shared.log("crew_edit.exported", detail: String(format: "%.0fs, %d pieces", b - a, pieces.count))
        } catch {
            exportedURL = nil
            exportError = error.localizedDescription
            await EventLogger.shared.log("crew_edit.export_failed", detail: error.localizedDescription)
        }
    }

    func saveExportToPhotos() async {
        guard let url = exportedURL else { return }
        savedToPhotos = await PhotoLibraryService.shared.saveVideo(fileURL: url) != nil
        if !savedToPhotos {
            exportError = String(localized: "Photos didn't accept the video. Check that Witness can add to your photo library in Settings.")
        }
    }

    // MARK: - Display

    /// The stretch of the timeline on screen: everything, or In to Out with
    /// a little room either side when zoomed.
    private var window: (start: Double, end: Double) {
        if zoomed, hasInOut, let i = edit.inPoint, let o = edit.outPoint {
            let a = i.timeIntervalSince(origin), b = o.timeIntervalSince(origin)
            let pad = (b - a) * 0.05
            return (max(0, a - pad), min(total, b + pad))
        }
        return (0, total)
    }

    private func fraction(_ seconds: Double) -> Double {
        let w = window
        return (seconds - w.start) / max(w.end - w.start, 0.001)
    }

    func x(for date: Date, width: CGFloat) -> CGFloat {
        CGFloat(fraction(date.timeIntervalSince(origin))) * width
    }

    func span(of angle: CrewAngle, width: CGFloat) -> (CGFloat, CGFloat) {
        let start = angle.startedAt.timeIntervalSince(origin)
        return span(from: start, to: start + angle.duration, width: width)
    }

    func span(from a: Double, to b: Double, width: CGFloat) -> (CGFloat, CGFloat) {
        let x = CGFloat(fraction(a)) * width
        return (x, CGFloat(fraction(b)) * width - x)
    }

    func playheadX(width: CGFloat) -> CGFloat { CGFloat(fraction(position)) * width }

    var clockText: String { now.formatted(date: .omitted, time: .standard) }

    var positionText: String {
        "\(Self.format(position)) / \(Self.format(total))"
    }

    private static func format(_ s: Double) -> String {
        let s = Int(s)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            : String(format: "%d:%02d", s / 60, s % 60)
    }
}

/// A plain video surface, without the system controls: the crew player's
/// own controls work across all angles.
private struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerUIView {
        let view = PlayerUIView()
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ view: PlayerUIView, context: Context) {
        if view.playerLayer.player !== player { view.playerLayer.player = player }
    }

    final class PlayerUIView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}
