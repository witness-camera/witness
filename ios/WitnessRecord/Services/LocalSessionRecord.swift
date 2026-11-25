import CryptoKit
import Foundation
import UIKit

/// The chain-of-custody record for a recording kept only in the photo
/// library — no cloud account connected, uploads switched off, or ProRes.
///
/// Such a session drops each 15-second piece as soon as it is staged, so
/// the manifest can't list hashes for files that no longer exist. What it
/// lists instead is each finished video's SHA-256, taken from the exact file
/// handed to the library, alongside the timing and location of every piece.
/// The video is the evidence; the record proves it was not altered.
///
/// It also remembers which library asset holds each camera's video, so the
/// recording can be uploaded later, once an account is connected, without
/// ever keeping a second copy on the phone.
enum LocalSessionRecord {

    struct ChunkInfo: Codable {
        let index: Int
        let camera: String
        let fileName: String
        let startedAt: Date
        let endedAt: Date
        let latitude: Double?
        let longitude: Double?
        let horizontalAccuracy: Double?
        /// Only for pieces that were hashed for upload before being removed.
        var sha256: String? = nil
    }

    struct Video: Codable {
        let camera: String
        /// Nil for a recording converted after the fact, whose library copy
        /// was saved before anything hashed it.
        let sha256: String?
        let photoAssetID: String
    }

    struct Gallery: Codable {
        var videos: [Video] = []
        /// True when the recording stayed local only because no account was
        /// connected — not ProRes, and not uploads switched off, which were
        /// choices not to upload and shouldn't be second-guessed later.
        var offerUpload: Bool? = nil
        /// Set once every video, the manifest and its proof are confirmed in
        /// the cloud.
        var uploaded = false
    }

    private static let lock = NSLock()
    private static let chunksName = "local-chunks.json"
    private static let galleryName = "gallery.json"

    // MARK: - While recording

    /// Called for each piece as it is finalized, before its file is deleted.
    static func noteChunk(_ chunk: CaptureSessionController.FinalizedChunk) {
        lock.lock(); defer { lock.unlock() }
        var infos = readChunks(chunk.sessionID)
        infos.append(ChunkInfo(
            index: chunk.index, camera: chunk.camera.rawValue,
            fileName: chunk.fileURL.lastPathComponent,
            startedAt: chunk.startedAt, endedAt: chunk.endedAt,
            latitude: chunk.location?.coordinate.latitude,
            longitude: chunk.location?.coordinate.longitude,
            horizontalAccuracy: chunk.location?.horizontalAccuracy))
        write(infos, chunksName, chunk.sessionID)
    }

    static func chunks(for sessionID: UUID) -> [ChunkInfo] {
        lock.lock(); defer { lock.unlock() }
        return readChunks(sessionID)
    }

    // MARK: - Once the library has the video

    /// Records one camera's video as saved. Cameras finish in any order.
    static func recordSavedVideo(sessionID: UUID, camera: String, sha256: String, photoAssetID: String) {
        lock.lock(); defer { lock.unlock() }
        var gallery = readGallery(sessionID) ?? Gallery()
        if gallery.offerUpload == nil { gallery.offerUpload = keptLocalForWantOfAccount }
        gallery.videos.removeAll { $0.camera == camera }
        gallery.videos.append(Video(camera: camera, sha256: sha256, photoAssetID: photoAssetID))
        write(gallery, galleryName, sessionID)
    }

    static func gallery(for sessionID: UUID) -> Gallery? {
        lock.lock(); defer { lock.unlock() }
        return readGallery(sessionID)
    }

    /// Takes over a recording whose pieces were queued for a cloud that never
    /// came, once the library is confirmed to hold its videos.
    static func adopt(sessionID: UUID, chunks: [ChunkInfo], videos: [Video]) {
        lock.lock(); defer { lock.unlock() }
        write(chunks, chunksName, sessionID)
        write(Gallery(videos: videos, offerUpload: true), galleryName, sessionID)
    }

    /// Whether the current settings would upload, if only an account were
    /// connected.
    private static var keptLocalForWantOfAccount: Bool {
        !AppSettings.cloudAccountConnected
            && !AppSettings.isLocalOnlyCapture(
                codec: AppSettings.videoCodec, cloudUploadEnabled: AppSettings.cloudUploadEnabled,
                cloudAccountConnected: true, isPro: AppSettings.isProEntitled)
    }

    static func markUploaded(_ sessionID: UUID) {
        lock.lock(); defer { lock.unlock() }
        guard var gallery = readGallery(sessionID) else { return }
        gallery.uploaded = true
        write(gallery, galleryName, sessionID)
    }

    /// Writes and time-stamps the session's manifest from what was recorded
    /// above. Safe to call again: a later call (a camera saved on a retry at
    /// the next launch) rewrites it with the fuller list.
    static let savedLocallyNote = "Kept in this iPhone's photo library. The recording's 15-second pieces were "
        + "not uploaded or kept; each finished video's SHA-256 above is what this record attests to."
    static let convertedNote = "Kept in this iPhone's photo library. The 15-second pieces listed above were "
        + "hashed while recording, then removed from the phone to free space before any cloud account was "
        + "connected. The library copy was saved before Witness hashed finished videos, so it has no hash here."

    static func finish(sessionID: UUID, storageNote: String = savedLocallyNote) async {
        let infos = chunks(for: sessionID)
        guard let gallery = gallery(for: sessionID), !gallery.videos.isEmpty else { return }

        var manifest = SessionManifest(
            schemaVersion: 1,
            sessionID: sessionID,
            recordingStartedUTC: infos.map(\.startedAt).min() ?? Date(),
            recordingEndedUTC: infos.map(\.endedAt).max() ?? Date(),
            deviceTimeZone: TimeZone.current.identifier,
            deviceModel: await UIDevice.current.model,
            systemVersion: await UIDevice.current.systemVersion,
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?",
            chunkCount: infos.count,
            // Timing and place of every piece, kept even though the pieces
            // themselves were not: no hash, because there is no file left
            // for one to describe.
            chunks: infos.sorted { ($0.index, $0.camera) < ($1.index, $1.camera) }.map {
                SessionManifest.Chunk(
                    index: $0.index, camera: $0.camera, fileName: $0.fileName,
                    kind: "not_kept", sha256: $0.sha256,
                    captureStartUTC: $0.startedAt, captureEndUTC: $0.endedAt,
                    latitude: $0.latitude, longitude: $0.longitude,
                    horizontalAccuracyMeters: $0.horizontalAccuracy)
            },
            mergedVideos: gallery.videos.map {
                SessionManifest.MergedVideo(
                    camera: $0.camera,
                    fileName: UploadManager.mergedDriveFileName(camera: $0.camera),
                    sha256: $0.sha256)
            })
        manifest.storage = storageNote
        manifest.crew = CrewStore.stamp(for: sessionID)
        manifest.timestampAuthority = TimestampService.defaultAuthority.absoluteString
        manifest.timestampFileName = UploadManager.timestampFileName(for: sessionID)
        manifest.timestampVerification = SessionManifest.verificationInstructions
        let marks = MomentMarkers.between(manifest.recordingStartedUTC, manifest.recordingEndedUTC)
        manifest.markers = marks.isEmpty ? nil : marks

        guard let data = try? SessionManifest.encoder.encode(manifest) else { return }
        var token: Data?
        do {
            token = try await TimestampService.token(forSHA256: Data(SHA256.hash(data: data)))
        } catch {
            await EventLogger.shared.log("local_record.timestamp_failed", detail: error.localizedDescription)
        }
        SessionArchive.store(manifestData: data, timestampToken: token, for: sessionID)
        await EventLogger.shared.log(
            "local_record.written",
            detail: "\(sessionID.uuidString) videos=\(gallery.videos.count) stamped=\(token != nil)")
    }

    /// Proofs that couldn't be obtained at the time, usually for want of a
    /// connection. The manifest is already final, so its bytes are stamped as
    /// they are.
    static func retryMissingTimestamps() async {
        for manifest in SessionArchive.sessions()
        where gallery(for: manifest.sessionID) != nil && !SessionArchive.hasTimestamp(manifest.sessionID) {
            guard let data = try? Data(contentsOf: SessionArchive.manifestURL(for: manifest.sessionID)),
                  let token = try? await TimestampService.token(forSHA256: Data(SHA256.hash(data: data)))
            else { continue }
            try? token.write(to: SessionArchive.timestampURL(for: manifest.sessionID), options: .atomic)
        }
    }

    /// Recordings that are only in the library and haven't been uploaded.
    static func awaitingUpload() -> [UUID] {
        SessionArchive.sessions().map(\.sessionID).filter { id in
            guard let gallery = gallery(for: id) else { return false }
            return gallery.offerUpload == true && !gallery.uploaded && !gallery.videos.isEmpty
        }
    }

    // MARK: - Storage

    private static func readChunks(_ sessionID: UUID) -> [ChunkInfo] {
        guard let data = try? Data(contentsOf: SessionArchive.path(chunksName, for: sessionID)) else { return [] }
        return (try? decoder.decode([ChunkInfo].self, from: data)) ?? []
    }

    private static func readGallery(_ sessionID: UUID) -> Gallery? {
        guard let data = try? Data(contentsOf: SessionArchive.path(galleryName, for: sessionID)) else { return nil }
        return try? decoder.decode(Gallery.self, from: data)
    }

    private static func write<T: Encodable>(_ value: T, _ name: String, _ sessionID: UUID) {
        guard let data = try? encoder.encode(value) else { return }
        try? data.write(to: SessionArchive.directory(for: sessionID).appendingPathComponent(name), options: .atomic)
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
