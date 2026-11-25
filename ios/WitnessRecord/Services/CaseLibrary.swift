import AVFoundation
import CryptoKit
import Foundation
import UIKit
import UniformTypeIdentifiers

/// Cases and everything filed in them, kept on the device.
///
/// Recordings themselves still go where they always went — Photos and the
/// connected cloud account — and their proof is still the session manifest.
/// What this adds per recording is small: a thumbnail, and a low-bitrate audio
/// copy so it can be transcribed later without reading back from Photos (which
/// this app only has add-only access to). Photos, audio recordings and
/// imported files are kept here in full, each with its own hashed and
/// time-stamped record, and also sent to the cloud account.
@MainActor
final class CaseLibrary: ObservableObject {
    static let shared = CaseLibrary()

    @Published private(set) var cases: [EvidenceCase] = []
    @Published private(set) var items: [CaseItem] = []
    /// The recording that just finished, so Home can offer to add details.
    /// Never presented on its own: it's an offer the person can ignore.
    @Published var justFinishedItemID: UUID?

    private struct Stored: Codable {
        var cases: [EvidenceCase]
        var items: [CaseItem]
    }

    nonisolated static var root: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("cases", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    nonisolated static func directory(for itemID: UUID) -> URL {
        let dir = root.appendingPathComponent("items", isDirectory: true)
            .appendingPathComponent(itemID.uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    nonisolated static func thumbnailURL(for itemID: UUID) -> URL {
        directory(for: itemID).appendingPathComponent("thumbnail.jpg")
    }

    /// The audio a transcript is made from: the recording's own for audio
    /// items, the extracted copy for recordings and imported videos.
    nonisolated static func transcriptAudioURL(for item: CaseItem) -> URL? {
        let dir = directory(for: item.id)
        if item.kind == .audio, let name = item.fileName { return dir.appendingPathComponent(name) }
        let copy = dir.appendingPathComponent("audio.m4a")
        return FileManager.default.fileExists(atPath: copy.path) ? copy : nil
    }

    nonisolated static func mediaURL(for item: CaseItem) -> URL? {
        item.fileName.map { directory(for: item.id).appendingPathComponent($0) }
    }

    nonisolated static func recordURL(for itemID: UUID) -> URL {
        directory(for: itemID).appendingPathComponent("record.json")
    }

    nonisolated static func timestampURL(for itemID: UUID) -> URL {
        directory(for: itemID).appendingPathComponent("record.tsr")
    }

    private static var storeURL: URL { root.appendingPathComponent("library.json") }

    private init() {
        if let data = try? Data(contentsOf: Self.storeURL),
           let stored = try? Self.decoder.decode(Stored.self, from: data) {
            cases = stored.cases
            items = stored.items
        }
        adoptArchivedRecordings()
    }

    /// Recordings made before Cases existed, or whose item was removed, still
    /// have their manifest in the archive. Each one without an item gets one,
    /// so every past recording can be filed. No thumbnail or audio copy: the
    /// footage left the device long ago.
    private func adoptArchivedRecordings() {
        let known = Set(items.compactMap(\.sessionID))
        let adoptedKey = "cases.adoptedSessions"
        var adopted = Set(UserDefaults.standard.stringArray(forKey: adoptedKey) ?? [])
        var changed = false
        for manifest in SessionArchive.sessions()
        where !known.contains(manifest.sessionID) && !adopted.contains(manifest.sessionID.uuidString) {
            let first = manifest.chunks.min { $0.captureStartUTC < $1.captureStartUTC }
            items.append(CaseItem(id: UUID(), kind: .video, sessionID: manifest.sessionID,
                                  createdAt: manifest.recordingStartedUTC, endedAt: manifest.recordingEndedUTC,
                                  latitude: first?.latitude, longitude: first?.longitude,
                                  timestamped: SessionArchive.hasTimestamp(manifest.sessionID), uploaded: true))
            // Remembered, so removing one from Witness doesn't bring it back.
            adopted.insert(manifest.sessionID.uuidString)
            changed = true
        }
        if changed {
            UserDefaults.standard.set(Array(adopted), forKey: adoptedKey)
            save()
        }
    }

    private func save() {
        guard let data = try? Self.encoder.encode(Stored(cases: cases, items: items)) else { return }
        try? data.write(to: Self.storeURL, options: .atomic)
    }

    // MARK: - Lookup

    func item(_ id: UUID) -> CaseItem? { items.first { $0.id == id } }

    func items(in caseID: UUID?) -> [CaseItem] {
        items.filter { $0.caseID == caseID }.sorted { $0.createdAt > $1.createdAt }
    }

    func update(_ id: UUID, _ change: (inout CaseItem) -> Void) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        change(&items[i])
        save()
    }

    // MARK: - Cases

    @discardableResult
    func newCase(titled title: String) -> EvidenceCase {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let new = EvidenceCase(id: UUID(),
                               title: trimmed.isEmpty ? String(localized: "Untitled case") : trimmed,
                               createdAt: Date())
        cases.insert(new, at: 0)
        save()
        return new
    }

    func rename(_ caseID: UUID, to title: String) {
        guard let i = cases.firstIndex(where: { $0.id == caseID }) else { return }
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        cases[i].title = trimmed
        save()
    }

    /// Deletes the case only; its items move back to "Not in a case".
    func deleteCase(_ caseID: UUID) {
        cases.removeAll { $0.id == caseID }
        for i in items.indices where items[i].caseID == caseID { items[i].caseID = nil }
        save()
    }

    func move(_ itemID: UUID, to caseID: UUID?) {
        update(itemID) { $0.caseID = caseID }
    }

    func move(_ itemIDs: Set<UUID>, to caseID: UUID?) {
        for i in items.indices where itemIDs.contains(items[i].id) { items[i].caseID = caseID }
        save()
    }

    /// Removes the app's own copy. Never touches Photos or the cloud account,
    /// and the screen asking for this says so.
    func deleteItem(_ itemID: UUID) {
        items.removeAll { $0.id == itemID }
        try? FileManager.default.removeItem(at: Self.directory(for: itemID))
        save()
    }

    // MARK: - Recordings

    /// Called the moment a recording stops, so the item exists for the
    /// "add details" offer before its media has been extracted.
    func registerRecording(sessionID: UUID, startedAt: Date?, endedAt: Date, latitude: Double?, longitude: Double?) {
        if let existing = items.first(where: { $0.sessionID == sessionID }) {
            justFinishedItemID = existing.id
            return
        }
        let item = CaseItem(id: UUID(), kind: .video, sessionID: sessionID,
                            createdAt: startedAt ?? endedAt, endedAt: endedAt,
                            latitude: latitude, longitude: longitude,
                            timestamped: false, uploaded: true)
        items.append(item)
        justFinishedItemID = item.id
        var adopted = UserDefaults.standard.stringArray(forKey: "cases.adoptedSessions") ?? []
        adopted.append(sessionID.uuidString)
        UserDefaults.standard.set(adopted, forKey: "cases.adoptedSessions")
        save()
    }

    /// Takes a thumbnail and an audio copy from a finished recording's video,
    /// before the stitcher hands that file off and deletes it. Best effort:
    /// a recording without either is still a complete recording.
    nonisolated func attachRecordingMedia(sessionID: UUID, videoURL: URL) async {
        let itemID: UUID = await MainActor.run {
            if let existing = items.first(where: { $0.sessionID == sessionID }) { return existing.id }
            registerRecording(sessionID: sessionID, startedAt: nil, endedAt: Date(),
                              latitude: nil, longitude: nil)
            justFinishedItemID = nil
            return items.first { $0.sessionID == sessionID }!.id
        }
        await Self.writeThumbnail(from: videoURL, to: Self.thumbnailURL(for: itemID))
        do {
            try await Self.extractAudio(from: videoURL,
                                        to: Self.directory(for: itemID).appendingPathComponent("audio.m4a"))
        } catch {
            await EventLogger.shared.log("cases.audio_copy_failed", detail: "\(sessionID): \(error.localizedDescription)")
        }
        let stamped = SessionArchive.hasTimestamp(sessionID)
        await MainActor.run {
            update(itemID) { $0.timestamped = stamped }
            objectWillChange.send()
        }
    }

    /// Remembers where in Photos a recording landed. Saves for two cameras
    /// can finish in either order, so each adds its own.
    func rememberPhotoAsset(_ identifier: String, sessionID: UUID) {
        guard !identifier.isEmpty, let item = items.first(where: { $0.sessionID == sessionID }) else { return }
        update(item.id) { item in
            var ids = item.photoAssetIDs ?? []
            if !ids.contains(identifier) { ids.append(identifier) }
            item.photoAssetIDs = ids
        }
    }

    /// The session's proof arrives after upload, so the badge is refreshed
    /// whenever the list is looked at rather than pushed from the uploader.
    func refreshRecordingProofs() {
        var changed = false
        for i in items.indices where items[i].kind == .video && !items[i].timestamped {
            if let id = items[i].sessionID, SessionArchive.hasTimestamp(id) {
                items[i].timestamped = true
                changed = true
            }
        }
        if changed { save() }
    }

    // MARK: - Photos, audio, imports

    func addPhoto(_ image: UIImage, to caseID: UUID?) async {
        guard let data = image.jpegData(compressionQuality: 0.92) else { return }
        await addFile(data: data, ext: "jpg", kind: .photo, imported: false, to: caseID)
    }

    /// `moveFrom` is a file this library takes ownership of.
    func addFile(moveFrom url: URL, kind: CaseItem.Kind, imported: Bool,
                 startedAt: Date? = nil, title: String? = nil, to caseID: UUID?) async {
        let id = UUID()
        let ext = url.pathExtension.isEmpty ? "bin" : url.pathExtension.lowercased()
        let dest = Self.directory(for: id).appendingPathComponent("original.\(ext)")
        do {
            try FileManager.default.moveItem(at: url, to: dest)
        } catch {
            do { try FileManager.default.copyItem(at: url, to: dest) } catch { return }
        }
        await finishAdding(id: id, fileURL: dest, kind: kind, imported: imported,
                           startedAt: startedAt, title: title, to: caseID)
    }

    private func addFile(data: Data, ext: String, kind: CaseItem.Kind, imported: Bool, to caseID: UUID?) async {
        let id = UUID()
        let dest = Self.directory(for: id).appendingPathComponent("original.\(ext)")
        guard (try? data.write(to: dest)) != nil else { return }
        await finishAdding(id: id, fileURL: dest, kind: kind, imported: imported, startedAt: nil, title: nil, to: caseID)
    }

    /// Hashes the file, writes and time-stamps its record, then files it.
    /// The item appears straight away; the proof and upload follow.
    private func finishAdding(id: UUID, fileURL: URL, kind: CaseItem.Kind, imported: Bool,
                              startedAt: Date?, title: String?, to caseID: UUID?) async {
        let now = Date()
        let location = LocationService.shared.lastLocation
        let hash = await Task.detached { try? Hashing.sha256Hex(of: fileURL) }.value
        let isVideo = UTType(filenameExtension: fileURL.pathExtension)?.conforms(to: .movie) ?? false

        var item = CaseItem(id: id, kind: kind, caseID: caseID,
                            createdAt: imported ? now : (startedAt ?? now),
                            endedAt: kind == .audio ? now : nil,
                            fileName: fileURL.lastPathComponent, sha256: hash,
                            latitude: imported ? nil : location?.coordinate.latitude,
                            longitude: imported ? nil : location?.coordinate.longitude)
        item.title = title
        items.append(item)
        save()

        if kind == .photo {
            if let image = UIImage(contentsOfFile: fileURL.path) {
                Self.writeThumbnail(image, to: Self.thumbnailURL(for: id))
            }
            if AppSettings.photosBackupEnabled { PhotoLibraryService.shared.saveImage(fileURL: fileURL) }
        } else if isVideo {
            await Self.writeThumbnail(from: fileURL, to: Self.thumbnailURL(for: id))
            try? await Self.extractAudio(from: fileURL, to: Self.directory(for: id).appendingPathComponent("audio.m4a"))
        }
        objectWillChange.send()

        guard let hash else { return }
        var record = ItemRecord(
            schemaVersion: 1, itemID: id, kind: kind.rawValue,
            source: imported ? "imported into Witness" : "captured in Witness",
            fileName: fileURL.lastPathComponent, sha256: hash,
            capturedUTC: item.createdAt, endedUTC: item.endedAt,
            latitude: item.latitude, longitude: item.longitude,
            horizontalAccuracyMeters: imported ? nil : location?.horizontalAccuracy,
            deviceTimeZone: TimeZone.current.identifier,
            deviceModel: UIDevice.current.model,
            systemVersion: UIDevice.current.systemVersion,
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?")
        if imported {
            record.note = "Hashed and time-stamped when imported. This proves the file existed unaltered from that moment, not when or how it was originally made."
        }
        record.timestampAuthority = TimestampService.defaultAuthority.absoluteString
        record.timestampFileName = "record.tsr"
        guard let recordData = try? SessionManifest.encoder.encode(record) else { return }
        try? recordData.write(to: Self.recordURL(for: id), options: .atomic)

        await stampAndUpload(id)
    }

    /// Retries proofs and uploads left undone — no network at the time, or
    /// no account connected yet. Runs whenever Home appears.
    func finishPendingWork() {
        for item in items where item.kind != .video && (!item.timestamped || !item.uploaded) {
            Task { await stampAndUpload(item.id) }
        }
    }

    private var inFlight: Set<UUID> = []

    private func stampAndUpload(_ id: UUID) async {
        guard !inFlight.contains(id), let item = item(id) else { return }
        inFlight.insert(id)
        defer { inFlight.remove(id) }
        let recordURL = Self.recordURL(for: id)
        guard let recordData = try? Data(contentsOf: recordURL) else { return }

        if !item.timestamped {
            if let token = try? await TimestampService.token(forSHA256: Data(SHA256.hash(data: recordData))) {
                try? token.write(to: Self.timestampURL(for: id), options: .atomic)
                update(id) { $0.timestamped = true }
            }
        }
        guard !item.uploaded, !AppSettings.isLocalOnlyCapture,
              let media = Self.mediaURL(for: item) else { return }
        var files = [media, recordURL]
        if FileManager.default.fileExists(atPath: Self.timestampURL(for: id).path) {
            files.append(Self.timestampURL(for: id))
        }
        // Wait for the proof before uploading, so the cloud copy is complete;
        // the next pass sends everything once it exists.
        guard self.item(id)?.timestamped == true else { return }
        if await Self.upload(files, itemID: id, date: item.createdAt,
                             latitude: item.latitude, longitude: item.longitude) {
            update(id) { $0.uploaded = true }
        }
    }

    nonisolated private static func upload(_ files: [URL], itemID: UUID, date: Date,
                                           latitude: Double?, longitude: Double?) async -> Bool {
        guard let provider = await StorageProviders.shared.currentProvider() else { return false }
        do {
            let token = try await provider.freshAccessToken()
            let rootName = UserDefaults.standard.string(forKey: "driveFolderName") ?? "Witness"
            let rootID = try await provider.ensureRootFolder(named: rootName, token: token)
            let folderName = await SessionFolderNaming.folderName(
                sessionID: itemID, date: date, latitude: latitude, longitude: longitude)
            let folderID = try await provider.ensureSubfolder(named: folderName, parentID: rootID, token: token)
            for file in files {
                let mime = UTType(filenameExtension: file.pathExtension)?.preferredMIMEType
                    ?? "application/octet-stream"
                let (request, body) = try provider.multipartUploadRequest(
                    fileURL: file, uploadName: file.lastPathComponent,
                    mimeType: mime, folderID: folderID, token: token)
                defer { try? FileManager.default.removeItem(at: body) }
                let (_, response) = try await URLSession.shared.upload(for: request, fromFile: body)
                guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                    return false
                }
            }
            return true
        } catch {
            await EventLogger.shared.log("cases.upload_failed", detail: "\(itemID): \(error.localizedDescription)")
            return false
        }
    }

    // MARK: - Transcripts and search

    func setTranscript(_ transcript: Transcript, for id: UUID) {
        update(id) { $0.transcript = transcript }
    }

    struct SearchHit: Identifiable {
        let id = UUID()
        let itemID: UUID
        let time: TimeInterval?
        let text: String
    }

    /// Transcripts and notes containing `query`, ignoring case and accents.
    func search(_ query: String) -> [SearchHit] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else { return [] }
        let options: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
        var hits: [SearchHit] = []
        for item in items.sorted(by: { $0.createdAt > $1.createdAt }) {
            for segment in item.transcript?.segments ?? [] where segment.text.range(of: q, options: options) != nil {
                hits.append(SearchHit(itemID: item.id, time: segment.start, text: segment.text))
            }
            for note in item.notes where note.text.range(of: q, options: options) != nil {
                hits.append(SearchHit(itemID: item.id, time: nil, text: note.text))
            }
            for value in item.details.values where value.range(of: q, options: options) != nil {
                hits.append(SearchHit(itemID: item.id, time: nil, text: value))
            }
        }
        return hits
    }

    // MARK: - Media helpers

    nonisolated static func writeThumbnail(from videoURL: URL, to url: URL) async {
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: videoURL))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 480, height: 480)
        guard let image = try? await generator.image(at: CMTime(seconds: 1, preferredTimescale: 600)).image else { return }
        writeThumbnail(UIImage(cgImage: image), to: url)
    }

    nonisolated static func writeThumbnail(_ image: UIImage, to url: URL) {
        let side: CGFloat = 480
        let scale = min(1, side / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let small = UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        try? small.jpegData(compressionQuality: 0.8)?.write(to: url, options: .atomic)
    }

    /// Mono AAC at 48 kbps: about 20 MB an hour, plenty for speech.
    nonisolated static func extractAudio(from videoURL: URL, to outURL: URL) async throws {
        try? FileManager.default.removeItem(at: outURL)
        let asset = AVURLAsset(url: videoURL)
        guard let track = try await asset.loadTracks(withMediaType: .audio).first else { return }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ])
        reader.add(output)
        let writer = try AVAssetWriter(outputURL: outURL, fileType: .m4a)
        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 48000,
        ])
        input.expectsMediaDataInRealTime = false
        writer.add(input)
        guard reader.startReading(), writer.startWriting() else {
            throw writer.error ?? reader.error ?? NSError(domain: "WitnessRecord", code: 40)
        }
        writer.startSession(atSourceTime: .zero)
        nonisolated(unsafe) let r = reader, w = writer, o = output, i = input
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            i.requestMediaDataWhenReady(on: DispatchQueue(label: "witness.audio-copy")) {
                while i.isReadyForMoreMediaData {
                    if r.status == .reading, let buffer = o.copyNextSampleBuffer() {
                        i.append(buffer)
                    } else {
                        i.markAsFinished()
                        c.resume()
                        return
                    }
                }
            }
        }
        await writer.finishWriting()
        if writer.status != .completed {
            throw writer.error ?? NSError(domain: "WitnessRecord", code: 41)
        }
    }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()
}
