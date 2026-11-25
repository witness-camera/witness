import Foundation
import Photos
import UniformTypeIdentifiers

/// Keeps a recording from weighing twice on the phone when the photo library
/// holds it, and sends library-only recordings to the cloud once an account
/// is connected.
///
/// Both actions are offered, never automatic. Freeing space deletes files,
/// so it first confirms the library still has every video; uploading can
/// mean gigabytes over mobile data, so it waits to be asked.
@MainActor
final class LibraryStorage: ObservableObject {
    static let shared = LibraryStorage()

    /// Pieces queued for a cloud account that isn't connected, belonging to
    /// recordings the library already holds.
    @Published private(set) var redundantBytes: Int64 = 0
    @Published private(set) var freeing = false

    /// Recordings kept only in the library and not yet uploaded.
    @Published private(set) var awaitingUpload: [UUID] = []
    @Published private(set) var uploadProgress: (done: Int, total: Int)?

    @Published private(set) var message: String?

    private var redundantSessions: [UUID: [String]] = [:]

    private init() {}

    func refresh() {
        awaitingUpload = LocalSessionRecord.awaitingUpload()
        guard !AppSettings.cloudAccountConnected else {
            redundantBytes = 0
            redundantSessions = [:]
            return
        }
        let assetIDs = Dictionary(
            CaseLibrary.shared.items.compactMap { item -> (UUID, [String])? in
                guard let session = item.sessionID,
                      let ids = item.photoAssetIDs?.filter({ !$0.isEmpty }), !ids.isEmpty else { return nil }
                return (session, ids)
            },
            uniquingKeysWith: { first, _ in first })
        Task.detached(priority: .utility) {
            let waiting = ChunkStore.shared.records { $0.state != .uploaded }
            var sessions: [UUID: [String]] = [:]
            var bytes: Int64 = 0
            // Once the library can be read, only recordings it really still
            // holds are offered; before that, tapping is what checks.
            let canRead = PhotoLibraryService.shared.canRead
            var present: [UUID: Bool] = [:]
            for record in waiting {
                guard let ids = assetIDs[record.sessionID] else { continue }
                if canRead {
                    let held = present[record.sessionID]
                        ?? (PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil).count == ids.count)
                    present[record.sessionID] = held
                    guard held else { continue }
                }
                sessions[record.sessionID] = ids
                let size = (try? FileManager.default.attributesOfItem(atPath: record.fileURL.path)[.size] as? Int64) ?? 0
                bytes += size ?? 0
            }
            await MainActor.run {
                self.redundantSessions = sessions
                self.redundantBytes = bytes
            }
        }
    }

    // MARK: - Free up space

    func freeUpSpace() async {
        guard !freeing else { return }
        freeing = true
        defer { freeing = false; refresh() }
        guard await PhotoLibraryService.shared.requestReadAccess() else {
            message = String(localized: "To check your photo library still has these recordings, Witness needs to see the videos it saved. Nothing was deleted.")
            return
        }
        var freed = 0
        var kept = 0
        for (sessionID, ids) in redundantSessions {
            let found = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
            // Every video the library was given must still be there: one
            // deleted from Photos means these pieces are its only copy.
            guard found.count == ids.count else {
                kept += 1
                continue
            }
            let records = ChunkStore.shared.records(forSession: sessionID)
            let masters = records.filter { $0.kind == .master }
            let chunks = masters.map {
                LocalSessionRecord.ChunkInfo(
                    index: $0.index, camera: $0.camera.rawValue, fileName: $0.fileName,
                    startedAt: $0.startedAt, endedAt: $0.endedAt,
                    latitude: $0.latitude, longitude: $0.longitude,
                    horizontalAccuracy: $0.horizontalAccuracy, sha256: $0.sha256)
            }
            // Which library video is which camera wasn't recorded at the time.
            // One video takes the session's camera; several are numbered.
            let cameras = Set(masters.map(\.camera.rawValue))
            let videos = ids.enumerated().map { i, id in
                LocalSessionRecord.Video(
                    camera: ids.count == 1 ? (cameras.first ?? "back") : String(i + 1),
                    sha256: nil, photoAssetID: id)
            }
            LocalSessionRecord.adopt(sessionID: sessionID, chunks: chunks, videos: videos)
            await LocalSessionRecord.finish(sessionID: sessionID, storageNote: LocalSessionRecord.convertedNote)
            ChunkStore.shared.removeAll(forSession: sessionID)
            try? FileManager.default.removeItem(at: UploadManager.manifestURL(for: sessionID))
            freed += 1
            await EventLogger.shared.log("storage.freed_session", detail: sessionID.uuidString)
        }
        UploadManager.shared.refreshPendingCount()
        if kept > 0 {
            message = String(localized: "\(kept) recording(s) kept on this iPhone: their video is no longer in your photo library, so this is the only copy.")
        } else if freed > 0 {
            message = nil
        }
    }

    // MARK: - Upload earlier recordings

    func uploadEarlierRecordings() async {
        guard uploadProgress == nil else { return }
        let sessions = awaitingUpload
        guard !sessions.isEmpty else { return }
        guard await PhotoLibraryService.shared.requestReadAccess() else {
            message = String(localized: "Witness needs to see the videos it saved to your photo library to upload them.")
            return
        }
        message = nil
        uploadProgress = (0, sessions.count)
        defer { uploadProgress = nil; refresh() }

        var missing = 0
        for (i, sessionID) in sessions.enumerated() {
            guard let manifest = SessionArchive.sessions().first(where: { $0.sessionID == sessionID }),
                  let gallery = LocalSessionRecord.gallery(for: sessionID) else { continue }
            var videos: [UploadManager.BackfillVideo] = []
            for video in gallery.videos {
                guard let exported = await Self.export(video) else { continue }
                videos.append(exported)
            }
            guard !videos.isEmpty else {
                missing += 1
                continue
            }
            do {
                let timestamp = SessionArchive.hasTimestamp(sessionID) ? SessionArchive.timestampURL(for: sessionID) : nil
                try await UploadManager.shared.uploadLibraryRecording(
                    sessionID: sessionID, manifest: manifest, videos: videos,
                    manifestURL: SessionArchive.manifestURL(for: sessionID), timestampURL: timestamp)
                LocalSessionRecord.markUploaded(sessionID)
                uploadProgress = (i + 1, sessions.count)
            } catch {
                for video in videos { try? FileManager.default.removeItem(at: video.url) }
                message = String(localized: "Upload paused: \(error.localizedDescription) Tap again to continue.")
                await EventLogger.shared.log("backfill.failed", detail: "\(sessionID.uuidString): \(error.localizedDescription)")
                return
            }
        }
        if missing > 0 {
            message = String(localized: "\(missing) recording(s) couldn't be uploaded: their video is no longer in your photo library.")
        }
    }

    /// Copies a library video's original file out to a temporary file, and
    /// checks it against the hash taken when it was saved.
    private static func export(_ video: LocalSessionRecord.Video) async -> UploadManager.BackfillVideo? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [video.photoAssetID], options: nil).firstObject else {
            return nil
        }
        let resources = PHAssetResource.assetResources(for: asset)
        guard let resource = resources.first(where: { $0.type == .video }) ?? resources.first else { return nil }
        let ext = (resource.originalFilename as NSString).pathExtension.lowercased()
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("backfill-\(UUID().uuidString)")
            .appendingPathExtension(ext.isEmpty ? "mp4" : ext)
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        let written: Bool = await withCheckedContinuation { cont in
            PHAssetResourceManager.default().writeData(for: resource, toFile: url, options: options) { error in
                cont.resume(returning: error == nil)
            }
        }
        guard written else { return nil }

        if let expected = video.sha256 {
            let actual = await Task.detached { try? Hashing.sha256Hex(of: url) }.value
            if actual != expected {
                // Uploaded anyway: it is still the person's footage. The
                // mismatch is on record for whoever checks it against the
                // manifest.
                await EventLogger.shared.log(
                    "backfill.hash_mismatch", detail: "\(video.photoAssetID) expected=\(expected.prefix(16))…")
            }
        }
        let name = (UploadManager.mergedDriveFileName(camera: video.camera) as NSString)
            .deletingPathExtension + "." + url.pathExtension
        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "video/mp4"
        return UploadManager.BackfillVideo(fileName: name, mimeType: mime, url: url)
    }
}
