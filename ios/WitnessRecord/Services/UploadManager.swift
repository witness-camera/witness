import Foundation
import UIKit
import Network
import CoreLocation
import CryptoKit

/// Drives the chunk upload pipeline:
///   finalized chunk → SHA-256 hash → sidecar JSON → resumable upload
///   → sidecar multipart upload → local file deleted.
///
/// Uses a background URLSession so in-flight uploads survive suspension, a
/// persisted queue (ChunkStore) so nothing is lost across relaunches, and
/// exponential backoff + NWPathMonitor so the queue flushes the moment
/// connectivity returns. Cellular is always allowed — footage needs to leave
/// the device as fast as possible.
///
/// Storage-backend-agnostic: every transfer goes through whichever
/// `StorageProvider` is currently selected (`StorageProviders.shared`), never
/// a hardcoded backend. What used to be Drive-only calls now ask the active
/// provider for a folder ID, a request, or a token, so switching accounts in
/// settings changes where the next chunk actually lands.
final class UploadManager: NSObject, ObservableObject {
    static let shared = UploadManager()
    static let backgroundSessionIdentifier = "com.witnessrecord.upload"

    /// How many chunk transfers may be in flight at once.
    ///
    /// This used to be unbounded: every claimable chunk got a task immediately,
    /// so nine 50 MB files shared one uplink and all sat at 30%. Aggregate
    /// throughput is unchanged by that — the link is the limit either way — but
    /// *when* each file lands is not, and here only landed files count. Split
    /// nine ways, nothing is safe until nearly the end; taken in order, one is
    /// safe every minute or so. A phone taken mid-queue is the case this app
    /// exists for, and a half-uploaded file left on the server is worth nothing.
    ///
    /// How many full-quality chunks may be in flight at once.
    ///
    /// One, not two. Two still put two files at ~50% each instead of one at
    /// 100% and one not started, which is the same trade in miniature and just
    /// as wrong: half a file on the server is worth nothing. The handshake gap
    /// a second slot would have covered is a second or so against chunks that
    /// take a minute, and a stalled transfer is bounded by the request timeout
    /// and then retried, so neither justified the cost.
    ///
    /// Order is not strictly serial even so: a chunk that fails goes to the
    /// back on a backoff and the next one proceeds, rather than the queue
    /// stopping dead on one file a server keeps rejecting.
    static let maxConcurrentChunkUploads = 1

    /// Stand-ins get their own slot, alongside the full-quality file rather
    /// than behind it.
    ///
    /// Priority in the queue was not enough on its own. A stand-in is only
    /// made once uploads are visibly losing ground, by which time a 60 MB
    /// master is usually already transferring — and with one slot for
    /// everything, a 5 MB stand-in whose entire purpose is to be safe within
    /// seconds sat behind minutes of a file it exists to substitute for.
    ///
    /// A second lane rather than cancelling that master: the bytes it has
    /// already sent cannot be recovered on every backend — a WebDAV PUT has no
    /// resume at all — so pre-empting would throw away real progress on
    /// exactly the failing connection that prompted the stand-in. At roughly a
    /// twelfth the size, a stand-in costs the master a fraction of its time
    /// and is finished in seconds.
    static let maxConcurrentStandInUploads = 1

    @Published private(set) var pendingCount: Int = 0
    @Published private(set) var lastError: String?

    /// The outstanding queue, oldest first. Refreshed alongside `pendingCount`.
    @Published private(set) var queued: [QueuedChunk] = []
    /// What the most recent recording has actually landed. Read by the upload
    /// sheet; see `UploadReceipts`.
    @Published private(set) var lastUpload: SessionUploadReceipt?
    /// Fraction uploaded, 0...1, for chunks currently in flight. Absent means
    /// the chunk has not started, and it is removed once the task ends, so the
    /// dictionary never grows with finished work.
    @Published private(set) var chunkProgress: [UUID: Double] = [:]

    /// Stored by the AppDelegate when iOS relaunches us for background events.
    var backgroundCompletionHandler: (() -> Void)?

    private lazy var backgroundSession: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: Self.backgroundSessionIdentifier)
        config.allowsCellularAccess = true
        config.isDiscretionary = false            // upload now, not when convenient
        config.sessionSendsLaunchEvents = true
        config.allowsConstrainedNetworkAccess = true
        config.allowsExpensiveNetworkAccess = true
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    /// `URLSession.uploadTask(with:fromFile:)` raises an **Objective-C**
    /// exception when the file is not readable. That is not a Swift error —
    /// nothing at the call site can catch it — so a file that disappeared takes
    /// the whole app down, mid-recording, which is the worst possible moment.
    ///
    /// Every caller gets here at least one network round trip after it last saw
    /// the file, and plenty can delete it in between: an upload confirming and
    /// deleting its local copy, a stand-in superseded by its master, a session
    /// finalizing and dropping its manifest. So the check happens as late as
    /// possible — the statement before the call rather than seconds before it —
    /// and a vanished file becomes an ordinary Swift error the caller already
    /// knows how to unwind.
    ///
    /// This narrows the window rather than closing it: the file could still go
    /// between these two lines. It cannot be closed here, because the deletions
    /// are not serialised against this queue, but microseconds is a different
    /// order of risk from a round trip to Drive.
    private func makeUploadTask(_ request: URLRequest, fromFile url: URL) throws -> URLSessionUploadTask {
        // The readability check stays, because failing here is cheaper and
        // more specific than failing inside URLSession — but it is no longer
        // what makes this safe. It cannot be: `uploadTaskWithRequest:fromFile:`
        // barrier-syncs onto the session's own queue and does its real check
        // there, so anything checked beforehand races a queue we do not own.
        // Shipping this guard alone did not stop the crash.
        if !FileManager.default.isReadableFile(atPath: url.path) {
            Task { await EventLogger.shared.log("upload.file_vanished", detail: url.lastPathComponent) }
            throw UploadError(message: "\(url.lastPathComponent) is no longer on disk")
        }
        do {
            return try WRUploadTaskFactory.uploadTask(
                with: backgroundSession, request: request, fromFile: url)
        } catch {
            // Whatever URLSession objected to, in its own words. Logged rather
            // than only thrown: the crash report carried no reason string, so
            // the cause had to be guessed at. It does not have to be again.
            Task { await EventLogger.shared.log(
                "upload.task_creation_refused",
                detail: "\(url.lastPathComponent): \(error.localizedDescription)") }
            throw UploadError(
                message: "Couldn't start the upload: \(error.localizedDescription)")
        }
    }

    private let workQueue = DispatchQueue(label: "com.witnessrecord.uploadmanager")
    private let pathMonitor = NWPathMonitor()
    private var networkAvailable = true
    private var retryTimer: DispatchSourceTimer?
    /// Response bodies accumulated per task so we can read the created file's ID.
    private var responseBuffers: [Int: Data] = [:]

    struct UploadError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    // Session-manifest state (work-queue only). The manifest is rewritten as
    // chunks arrive and PATCHed over the same remote file, so versions track
    // what the backend has actually confirmed.
    private var manifestVersion: [UUID: Int] = [:]
    private var manifestConfirmedVersion: [UUID: Int] = [:]
    private var manifestUploadingVersion: [UUID: Int] = [:]
    private var manifestInFlight: Set<UUID> = []
    private var manifestFinalized: Set<UUID> = []

    // Backend-stitch state (work-queue only): once every chunk of a camera in
    // a session is confirmed uploaded, its merged video (built by
    // SessionStitcher) is uploaded, and — once every registered camera's
    // merge is confirmed — the small chunks it superseded are deleted
    // remotely and the manifest is rewritten to document the merge.
    private struct MergedUploadEntry {
        let url: URL
        var remoteFileID: String?
        var sha256: String?
        var confirmed: Bool = false
    }
    private var mergedUploads: [UUID: [String: MergedUploadEntry]] = [:]
    private var mergedInFlight: Set<String> = []       // "sessionID:camera"
    private var mergedFinishInFlight: Set<UUID> = []

    /// Callers awaiting a backfilled video's upload, by task identifier.
    /// Work-queue only. A relaunch loses these; the upload's completion then
    /// only cleans up, and the person can start the backfill again, which
    /// skips what already arrived.
    private var backfillWaiters: [Int: CheckedContinuation<String?, Never>] = [:]

    /// True while a pump is between asking what is in flight and claiming.
    /// Work-queue only.
    ///
    /// The claim used to be synchronous on the work queue, so overlapping
    /// pumps serialised themselves and the second found nothing left to take.
    /// It now sits behind an `await` (the session has to be asked what it is
    /// actually running), so two pumps can both read "none in flight", both
    /// see a full set of slots, and between them start twice the limit.
    private var claiming = false
    /// A pump that arrived during the claim phase and still needs answering.
    /// Dropping it outright would strand a chunk ingested in that window until
    /// some unrelated trigger came along. Work-queue only.
    private var pumpDeferred = false

    private override init() {
        super.init()
        _ = backgroundSession // create eagerly so pending tasks reattach at launch

        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let available = path.status == .satisfied
            self.workQueue.async {
                let cameBack = available && !self.networkAvailable
                self.networkAvailable = available
                if cameBack {
                    Task { await EventLogger.shared.log("network.restored") }
                    self.pump()
                }
            }
        }
        pathMonitor.start(queue: workQueue)
        refreshPendingCount()
        workQueue.async { [self] in restorePendingManifests() }
    }

    /// The active backend, off the main actor. Everything below treats a nil
    /// result the same as "not signed in": chunks stay queued and retry later
    /// rather than being dropped.
    private func requireProvider() async throws -> StorageProvider {
        guard let provider = await StorageProviders.shared.currentProvider() else {
            throw UploadError(message: "No storage account is connected")
        }
        return provider
    }

    // MARK: - Intake

    /// Entry point from the capture layer: a chunk file is finalized on disk.
    func ingest(_ chunk: CaptureSessionController.FinalizedChunk) {
        workQueue.async { [self] in
            let id = UUID()
            var record = ChunkRecord(
                id: id,
                fileName: chunk.fileURL.lastPathComponent,
                sessionID: chunk.sessionID,
                index: chunk.index,
                startedAt: chunk.startedAt,
                endedAt: chunk.endedAt,
                camera: chunk.camera
            )
            record.latitude = chunk.location?.coordinate.latitude
            record.longitude = chunk.location?.coordinate.longitude
            record.horizontalAccuracy = chunk.location?.horizontalAccuracy
            record.byteCount = (try? FileManager.default.attributesOfItem(
                atPath: chunk.fileURL.path)[.size] as? Int) ?? nil
            ChunkStore.shared.add(record)

            // Hash before anything ships — the hash is what the end-of-session
            // manifest attests to.
            do {
                let hash = try Hashing.sha256Hex(of: chunk.fileURL)
                record.sha256 = hash
                record.state = .queued
                ChunkStore.shared.update(record)
                Task { await EventLogger.shared.log(
                    "chunk.finalized",
                    detail: "\(record.fileName) sha256=\(hash.prefix(16))…",
                    location: chunk.location) }
                // Keep the live manifest current so the backend always holds
                // custody records for everything captured so far.
                writeManifest(chunk.sessionID)
                attemptManifestUpload(chunk.sessionID)
            } catch {
                // Keep it queued for a later hash retry rather than losing it.
                record.nextRetryAt = Date().addingTimeInterval(10)
                ChunkStore.shared.update(record)
            }
            // Only once the pipeline is visibly losing ground — on a healthy
            // connection the original goes up directly and nothing else is
            // encoded or sent.
            if shouldSendLowResAhead() {
                enqueueLowRes(for: record, location: chunk.location)
            }

            refreshPendingCount()
            pump()
        }
    }

    /// Work-queue only. Whether uploads are demonstrably failing to keep up.
    ///
    /// Measured from the pipeline's own behaviour rather than inferred from
    /// the radio: "on cellular" says nothing useful, since 5G outruns plenty
    /// of Wi-Fi, while a queue that keeps growing means precisely that
    /// footage is accumulating on a phone instead of leaving it.
    private func shouldSendLowResAhead() -> Bool {
        guard AppSettings.lowResFallbackEnabled else { return false }
        // ProRes never uploads at all, so a stand-in would have nowhere to go.
        guard !AppSettings.isLocalOnlyCapture else { return false }

        let queued = ChunkStore.shared.records(matching: { $0.state != .uploaded })
        // Three chunks outstanding is 45 seconds of footage that hasn't left
        // the device — past the point where the next one should wait its turn.
        if queued.filter({ $0.kind == .master }).count >= 3 { return true }
        // Or uploads are actively failing rather than merely slow.
        return queued.contains { $0.attemptCount >= 2 }
    }

    /// Work-queue only. Removes the stand-in for a chunk whose full-quality
    /// file is now confirmed remotely.
    ///
    /// Ordered deliberately: the master is confirmed *before* the stand-in is
    /// touched, so there is no instant at which neither copy is uploaded. If
    /// the delete fails the folder merely keeps a redundant low-res file,
    /// which is the harmless direction to fail in.
    private func supersedeLowRes(ofMaster masterID: UUID) {
        let standIns = ChunkStore.shared.records {
            $0.kind == .lowRes && $0.masterChunkID == masterID
        }
        guard !standIns.isEmpty else { return }

        for standIn in standIns { retire(standIn: standIn) }
    }

    /// Work-queue only. Disposes of one stand-in whose master has landed.
    private func retire(standIn: ChunkRecord) {
    // A stand-in that is mid-upload is left completely alone.
        //
        // This is the crash. `.uploading` is set when a chunk is claimed
        // and stays set through the whole of `startUpload` — the folder
        // lookup, the resumable-session round trip, and the moment the
        // upload task is built. Deleting its file inside that window is
        // what made URLSession raise, and raise fatally: four crash
        // reports from one user all had this exact call, deleting a
        // stand-in's file, running alongside `startUpload` for it.
        //
        // Nothing needs to happen now. Whichever way that upload ends,
        // `didCompleteWithError` supersedes it then, by which point the
        // file is no longer in anyone's hands.
        if standIn.state == .uploading { return }

        // Never started, or waiting to retry: no task exists to race, so
        // it can go straight away.
        if standIn.state != .uploaded {
            cancelUploadTask(for: standIn.id)
            try? FileManager.default.removeItem(at: standIn.fileURL)
            ChunkStore.shared.remove(id: standIn.id)
            return
        }
        guard let remoteFileID = standIn.driveFileID else {
            ChunkStore.shared.remove(id: standIn.id)
            return
        }
        Task { [self] in
            do {
                let provider = try await requireProvider()
                let token = try await provider.freshAccessToken()
                try await provider.deleteFile(fileID: remoteFileID, token: token)
                await EventLogger.shared.log("lowres.superseded", detail: standIn.fileName)
                workQueue.async { ChunkStore.shared.remove(id: standIn.id) }
            } catch {
                await EventLogger.shared.log(
                    "lowres.supersede_failed", detail: error.localizedDescription)
            }
        }
    }

    /// Work-queue only. The other half of the deferral above: a stand-in whose
    /// upload has just ended, one way or the other, and whose master landed
    /// while it was in flight. Nothing else would ever come back for it —
    /// `supersedeLowRes` fires once, on the master's confirmation, and by then
    /// this one was untouchable.
    private func retireStandInIfMasterLanded(_ id: UUID) {
        guard let standIn = ChunkStore.shared.record(id: id),
              standIn.kind == .lowRes,
              let masterID = standIn.masterChunkID,
              ChunkStore.shared.record(id: masterID)?.state == .uploaded
        else { return }
        retire(standIn: standIn)
    }

    /// Stops an in-flight chunk transfer. Best effort: the task may already
    /// have completed, in which case the cancellation is a no-op and the
    /// completion handler finds the record gone and does nothing.
    private func cancelUploadTask(for id: UUID) {
        let description = "chunk:\(id.uuidString)"
        Task { [self] in
            for task in await backgroundSession.allTasks
            where task.taskDescription == description {
                task.cancel()
                await EventLogger.shared.log("upload.cancelled_superseded", detail: description)
            }
        }
    }

    /// Work-queue only. Transcodes a small stand-in for `master` and queues it
    /// ahead of the full file.
    private func enqueueLowRes(for master: ChunkRecord, location: CLLocation?) {
        let lowResName = LowResEncoder.lowResFileName(for: master.fileName)
        let destination = ChunkStore.chunksDirectory.appendingPathComponent(lowResName)
        let sourceURL = master.fileURL

        Task { [self] in
            do {
                try await LowResEncoder.encode(sourceURL: sourceURL, to: destination)
            } catch {
                await EventLogger.shared.log("lowres.encode_failed", detail: error.localizedDescription)
                return
            }
            workQueue.async { [self] in
                // The master may already have landed while this was encoding,
                // in which case the stand-in is pointless.
                guard let current = ChunkStore.shared.record(id: master.id),
                      current.state != .uploaded else {
                    try? FileManager.default.removeItem(at: destination)
                    return
                }
                var record = ChunkRecord(
                    id: UUID(),
                    fileName: lowResName,
                    sessionID: master.sessionID,
                    index: master.index,
                    startedAt: master.startedAt,
                    endedAt: master.endedAt,
                    camera: master.camera
                )
                record.kind = .lowRes
                record.masterChunkID = master.id
                record.latitude = master.latitude
                record.longitude = master.longitude
                record.horizontalAccuracy = master.horizontalAccuracy
                record.sha256 = try? Hashing.sha256Hex(of: destination)
                record.byteCount = (try? FileManager.default.attributesOfItem(
                    atPath: destination.path)[.size] as? Int) ?? nil
                record.state = .queued
                ChunkStore.shared.add(record)
                Task { await EventLogger.shared.log(
                    "lowres.queued", detail: lowResName, location: location) }
                refreshPendingCount()
                pump()
            }
        }
    }

    // MARK: - Session manifest

    static func manifestFileName(for sessionID: UUID) -> String {
        "manifest_\(sessionID.uuidString).json"
    }

    /// Rewrites the session's manifest and pushes it to the backend,
    /// overwriting the same remote file each time. The folder therefore holds
    /// exactly one manifest per session, which stays current as the recording
    /// proceeds — so custody records exist remotely even if the session is
    /// never stopped normally (e.g. the phone is seized mid-encounter).
    func refreshSessionManifest(_ sessionID: UUID, finalize: Bool = false) {
        workQueue.async { [self] in
            writeManifest(sessionID)
            if finalize { manifestFinalized.insert(sessionID) }
            attemptManifestUpload(sessionID)
        }
    }

    /// Called once a recording session's chunks are all finalized. Runs on the
    /// serial work queue, which already holds the pending `ingest` blocks for
    /// this session — so every chunk is hashed and recorded by this point.
    func finalizeSession(_ sessionID: UUID) {
        refreshSessionManifest(sessionID, finalize: true)
    }

    /// Builds and writes the manifest file. Work-queue only.
    private func writeManifest(_ sessionID: UUID) {
        let records = ChunkStore.shared.records(forSession: sessionID)
        guard !records.isEmpty else { return }

        var manifest = SessionManifest(
                schemaVersion: 1,
                sessionID: sessionID,
                recordingStartedUTC: records.map(\.startedAt).min() ?? Date(),
                recordingEndedUTC: records.map(\.endedAt).max() ?? Date(),
                deviceTimeZone: TimeZone.current.identifier,
                deviceModel: UIDevice.current.model,
                systemVersion: UIDevice.current.systemVersion,
                appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?",
                // Counts what was filmed, so a stand-in doesn't read as an
                // extra 15 seconds of footage that never existed.
                chunkCount: records.filter { $0.kind == .master }.count,
                chunks: records.map {
                    SessionManifest.Chunk(
                        index: $0.index,
                        camera: $0.camera.rawValue,
                        fileName: $0.fileName,
                        kind: $0.kind.rawValue,
                        sha256: $0.sha256,
                        captureStartUTC: $0.startedAt,
                        captureEndUTC: $0.endedAt,
                        latitude: $0.latitude,
                        longitude: $0.longitude,
                        horizontalAccuracyMeters: $0.horizontalAccuracy)
                }
            )

        let url = Self.manifestURL(for: sessionID)
        let marks = MomentMarkers.between(manifest.recordingStartedUTC, manifest.recordingEndedUTC)
        manifest.markers = marks.isEmpty ? nil : marks
        manifest.crew = CrewStore.stamp(for: sessionID)
        guard let data = try? SessionManifest.encoder.encode(manifest),
              (try? data.write(to: url, options: .atomic)) != nil else { return }

        // A newer revision exists locally than whatever the backend last confirmed.
        manifestVersion[sessionID, default: 0] += 1
        Task { await EventLogger.shared.log(
            "manifest.written", detail: "\(sessionID.uuidString) chunks=\(records.count)") }
    }

    static func manifestURL(for sessionID: UUID) -> URL {
        ChunkStore.chunksDirectory.appendingPathComponent(manifestFileName(for: sessionID))
    }

    /// Creates the manifest remotely the first time, then PATCHes the same
    /// file on every later revision. Work-queue only.
    private func attemptManifestUpload(_ sessionID: UUID) {
        guard networkAvailable else { return }
        let url = Self.manifestURL(for: sessionID)
        guard FileManager.default.fileExists(atPath: url.path) else { return }
        // Nothing new since the last confirmed upload.
        guard manifestVersion[sessionID, default: 0] > manifestConfirmedVersion[sessionID, default: 0] else { return }
        // One upload in flight at a time; a newer revision re-runs on completion.
        guard !manifestInFlight.contains(sessionID) else { return }

        manifestInFlight.insert(sessionID)
        manifestUploadingVersion[sessionID] = manifestVersion[sessionID, default: 0]

        Task { [self] in
            do {
                let provider = try await requireProvider()
                let token = try await provider.freshAccessToken()
                let existingFileID = Self.manifestDriveIDs()[Self.manifestCacheKey(provider: provider, sessionID: sessionID)]
                let task: URLSessionUploadTask
                if let existingFileID {
                    // Overwrite in place — same remote file, new contents.
                    let request = provider.updateFileRequest(
                        fileID: existingFileID, mimeType: "application/json", token: token)
                    task = try makeUploadTask(request, fromFile: url)
                } else {
                    let folderName = UserDefaults.standard.string(forKey: "driveFolderName") ?? "Witness"
                    let rootID = try await provider.ensureRootFolder(
                        named: folderName, token: token)
                    let folderID = try await ensureSessionFolder(
                        sessionID: sessionID, folderName: folderName, rootID: rootID,
                        provider: provider, token: token)
                    await shareSessionFolderIfNeeded(
                        sessionID: sessionID, folderID: folderID, provider: provider, token: token)
                    let (request, bodyURL) = try provider.multipartUploadRequest(
                        fileURL: url,
                        uploadName: url.lastPathComponent,
                        mimeType: "application/json",
                        folderID: folderID,
                        token: token)
                    task = try makeUploadTask(request, fromFile: bodyURL)
                }
                task.taskDescription = "manifest:\(sessionID.uuidString)"
                task.resume()
            } catch {
                workQueue.async { [self] in
                    manifestInFlight.remove(sessionID)
                }
            }
        }
    }

    /// Work-queue only. Records the outcome of a manifest upload.
    private func handleManifestCompletion(_ sessionID: UUID, succeeded: Bool, body: Data?) {
        manifestInFlight.remove(sessionID)

        if succeeded {
            Task {
                if let body, let provider = await StorageProviders.shared.currentProvider(),
                   let fileID = provider.fileID(fromResponseBody: body) {
                    var map = Self.manifestDriveIDs()
                    map[Self.manifestCacheKey(provider: provider, sessionID: sessionID)] = fileID
                    UserDefaults.standard.set(map, forKey: Self.manifestDriveIDKey)
                }
            }
            manifestConfirmedVersion[sessionID] = manifestUploadingVersion[sessionID] ?? 0
            Task { await EventLogger.shared.log("manifest.confirmed", detail: sessionID.uuidString) }

            // Session is over and the backend holds the final revision — done.
            if manifestFinalized.contains(sessionID),
               manifestConfirmedVersion[sessionID, default: 0] >= manifestVersion[sessionID, default: 0] {
                try? FileManager.default.removeItem(at: Self.manifestURL(for: sessionID))
                manifestFinalized.remove(sessionID)
                manifestVersion[sessionID] = nil
                manifestConfirmedVersion[sessionID] = nil
                manifestUploadingVersion[sessionID] = nil
                // The manifest settling does not by itself mean the footage
                // landed: the manifest is rewritten as chunks are *ingested*,
                // so its last revision can confirm while chunks are still
                // uploading — and far more easily now that they go one at a
                // time. Checked explicitly, so the prompt follows the moment
                // the app demonstrably did its job rather than a moment that
                // merely tends to come after it.
                //
                // Only *earns* a rating prompt; the Home screen decides whether
                // to ask, so nothing here can surface UI mid-recording.
                let everythingLanded = ChunkStore.shared
                    .records(forSession: sessionID)
                    .allSatisfy { $0.state == .uploaded }
                if everythingLanded {
                    Task { @MainActor in ReviewPrompt.shared.sessionFullyPreserved() }
                }
                // The stitch pipeline waits for the ordinary manifest to
                // settle before consolidating chunks, so it can only start now.
                checkAndUploadMergedIfReady(sessionID)
                return
            }
        }
        // Either it failed, or newer content landed while this was in flight.
        attemptManifestUpload(sessionID)
    }

    private static let manifestDriveIDKey = "sessionManifestFileIDs"

    private static func manifestDriveIDs() -> [String: String] {
        UserDefaults.standard.dictionary(forKey: manifestDriveIDKey) as? [String: String] ?? [:]
    }

    /// Namespaced the same way as `sessionCacheKey`: a manifest's remote file
    /// ID from one backend must never be read back as another backend's.
    private static func manifestCacheKey(provider: StorageProvider, sessionID: UUID) -> String {
        "\(provider.id.rawValue):\(sessionID.uuidString)"
    }

    /// Re-queues manifests left on disk by a previous launch (e.g. the app was
    /// killed before the final revision reached the backend). Work-queue only.
    private func restorePendingManifests() {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: ChunkStore.chunksDirectory, includingPropertiesForKeys: nil))?
            .filter { $0.lastPathComponent.hasPrefix("manifest_") && $0.pathExtension == "json" } ?? []
        for url in files {
            let raw = url.deletingPathExtension().lastPathComponent
                .replacingOccurrences(of: "manifest_", with: "")
            guard let sessionID = UUID(uuidString: raw) else { continue }
            // Unknown whether the backend has this revision — treat it as pending.
            manifestVersion[sessionID, default: 0] += 1
            manifestFinalized.insert(sessionID)
        }
    }

    // MARK: - Backend stitch (merged session video)

    /// One distinct name per kind of video in a session's folder. The upload
    /// first looks for a file of this name and, finding one, counts the work as
    /// already done — so two videos sharing a name never both reach the cloud:
    /// the second is silently taken for the first.
    static func mergedDriveFileName(camera: String) -> String {
        switch camera {
        case "back": return "recording_rear.mp4"
        case "front": return "recording_front.mp4"
        case "wide": return "recording_wide_1x.mp4"
        case "ultrawide": return "recording_ultrawide_0.5x.mp4"
        case "pip": return "recording_picture_in_picture.mp4"
        case "highlights": return "recording_highlights.mp4"
        case "blurred": return "recording_faces_plates_blurred.mp4"
        case "blurred_front": return "recording_front_faces_plates_blurred.mp4"
        default: return "recording_\(camera).mp4"
        }
    }

    /// Called by `SessionStitcher` once a camera's chunks are merged into one
    /// video — either right after a session ends, or at launch recovering a
    /// merge a prior app lifetime didn't finish uploading.
    func registerMergedVideos(sessionID: UUID, merges: [(camera: String, url: URL)]) {
        workQueue.async { [self] in
            guard AppSettings.driveStitchEnabled else {
                // Feature is off (possibly toggled off since these were made
                // for) — the caller has no further use for them.
                for m in merges { try? FileManager.default.removeItem(at: m.url) }
                return
            }
            var entries = mergedUploads[sessionID] ?? [:]
            for m in merges where entries[m.camera] == nil {
                entries[m.camera] = MergedUploadEntry(url: m.url)
            }
            mergedUploads[sessionID] = entries
            checkAndUploadMergedIfReady(sessionID)
        }
    }

    /// Starts uploading any not-yet-confirmed registered merges for a session,
    /// once it's safe to: every one of its chunks is individually confirmed
    /// remotely (or already superseded by an earlier, partial run), *and* the
    /// ordinary per-chunk manifest has finished its own upload — avoiding a
    /// race where both write the manifest at once.
    private func checkAndUploadMergedIfReady(_ sessionID: UUID) {
        guard AppSettings.driveStitchEnabled,
              let entries = mergedUploads[sessionID], !entries.isEmpty,
              !manifestFinalized.contains(sessionID) else { return }
        let records = ChunkStore.shared.records(forSession: sessionID)
        guard records.isEmpty || records.allSatisfy({ $0.state == .uploaded }) else { return }
        for camera in entries.keys where entries[camera]?.confirmed != true {
            attemptMergedUpload(sessionID: sessionID, camera: camera)
        }
    }

    private func attemptMergedUpload(sessionID: UUID, camera: String) {
        guard networkAvailable,
              let entry = mergedUploads[sessionID]?[camera], !entry.confirmed else { return }
        let key = "\(sessionID.uuidString):\(camera)"
        guard !mergedInFlight.contains(key) else { return }
        guard FileManager.default.fileExists(atPath: entry.url.path) else {
            // Should not happen — drop this camera's entry rather than retry forever.
            mergedUploads[sessionID]?[camera] = nil
            return
        }
        mergedInFlight.insert(key)
        let url = entry.url

        Task { [self] in
            // Hashed off the work queue — a multi-minute merged video is much
            // larger than a single chunk, so hashing it shouldn't stall the
            // serial queue coordinating everything else.
            let hash = try? Hashing.sha256Hex(of: url)
            do {
                let provider = try await requireProvider()
                let token = try await provider.freshAccessToken()
                let folderName = UserDefaults.standard.string(forKey: "driveFolderName") ?? "Witness"
                let rootID = try await provider.ensureRootFolder(named: folderName, token: token)
                let folderID = try await self.ensureSessionFolder(
                    sessionID: sessionID, folderName: folderName, rootID: rootID,
                    provider: provider, token: token)
                let fileName = Self.mergedDriveFileName(camera: camera)

                // Idempotent: a prior attempt may have already uploaded this
                // (e.g. we crashed between confirmation and local cleanup) —
                // reuse it instead of creating a duplicate.
                if let existingID = try await provider.findFile(
                    named: fileName, parentID: folderID, token: token) {
                    workQueue.async { [self] in
                        mergedUploads[sessionID]?[camera]?.sha256 = hash
                        handleMergedUploadCompletion(
                            sessionID: sessionID, camera: camera, succeeded: true, remoteFileID: existingID)
                    }
                    return
                }

                guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
                      let fileSize = attrs[.size] as? Int else {
                    workQueue.async { [self] in mergedInFlight.remove(key) }
                    return
                }
                let sessionURI = try await provider.beginResumableSession(
                    fileName: fileName, mimeType: "video/mp4", fileSize: fileSize,
                    folderID: folderID, token: token)
                let request = provider.resumableRequest(
                    sessionURI: sessionURI, fileSize: fileSize, mimeType: "video/mp4", token: token)
                workQueue.async { [self] in mergedUploads[sessionID]?[camera]?.sha256 = hash }
                let task = try makeUploadTask(request, fromFile: url)
                task.taskDescription = "merged:\(sessionID.uuidString):\(camera)"
                task.resume()
            } catch {
                workQueue.async { [self] in mergedInFlight.remove(key) }
            }
        }
    }

    /// Work-queue only. Records the outcome of a merged-video upload.
    private func handleMergedUploadCompletion(sessionID: UUID, camera: String,
                                              succeeded: Bool, remoteFileID: String?) {
        mergedInFlight.remove("\(sessionID.uuidString):\(camera)")
        guard succeeded else {
            Task { await EventLogger.shared.log(
                "merged.upload_failed", detail: "\(sessionID.uuidString) \(camera)") }
            return // retried by a later pump()
        }
        mergedUploads[sessionID]?[camera]?.remoteFileID = remoteFileID
        mergedUploads[sessionID]?[camera]?.confirmed = true
        Task { await EventLogger.shared.log(
            "merged.upload_confirmed",
            detail: "\(sessionID.uuidString) \(camera) fileID=\(remoteFileID ?? "?")") }

        if let entries = mergedUploads[sessionID], !entries.isEmpty, entries.values.allSatisfy(\.confirmed) {
            finishDriveStitch(sessionID: sessionID, entries: entries)
        }
    }

    /// Once every registered camera's merged video is confirmed: delete the
    /// small chunks it superseded remotely (only for cameras that actually
    /// got a confirmed merge — a camera whose merge failed keeps its chunks,
    /// so nothing is ever lost), write the manifest's final revision
    /// documenting the merge, then clean up locally. Idempotent and safe to
    /// re-invoke — e.g. from `pump()` — if an earlier attempt didn't finish.
    private func finishDriveStitch(sessionID: UUID, entries: [String: MergedUploadEntry]) {
        guard !mergedFinishInFlight.contains(sessionID) else { return }
        mergedFinishInFlight.insert(sessionID)
        // Captured before any deletion, so the final manifest (and the delete
        // list) reflect exactly what existed at this point.
        let records = ChunkStore.shared.records(forSession: sessionID)
        let toDelete = records.filter { entries.keys.contains($0.camera.rawValue) }

        Task { [self] in
            var ok = true
            do {
                let provider = try await requireProvider()
                let token = try await provider.freshAccessToken()
                for fileID in toDelete.compactMap(\.driveFileID) {
                    do {
                        try await provider.deleteFile(fileID: fileID, token: token)
                    } catch {
                        ok = false
                    }
                }
                if ok {
                    do {
                        try await uploadFinalManifest(
                            sessionID: sessionID, records: records, entries: entries,
                            provider: provider, token: token)
                    } catch {
                        ok = false
                    }
                }
            } catch {
                ok = false
            }

            workQueue.async { [self] in
                mergedFinishInFlight.remove(sessionID)
                guard ok else { return } // retried by a later pump()

                for camera in entries.keys {
                    if let source = CameraSource(rawValue: camera) {
                        ChunkStore.shared.removeAll(forSession: sessionID, camera: source)
                    }
                }
                for entry in entries.values { try? FileManager.default.removeItem(at: entry.url) }
                if let dir = entries.values.first?.url.deletingLastPathComponent() {
                    try? FileManager.default.removeItem(at: dir)
                }
                mergedUploads[sessionID] = nil
                if let startedAt = records.first?.startedAt {
                    UploadReceipts.recordMerged(
                        sessionID: sessionID, startedAt: startedAt, videos: entries.count)
                }
                Task { await EventLogger.shared.log(
                    "drive.session_consolidated",
                    detail: "\(sessionID.uuidString) chunks_removed=\(toDelete.count) merged=\(entries.count)") }
                refreshPendingCount()
            }
        }
    }

    /// Writes the session's definitive final manifest — the ordinary chunk
    /// list (from the `records` snapshot taken before any deletion) plus the
    /// merged-video entries — directly to the backend. A one-shot foreground
    /// upload rather than going through the versioned PATCH cycle above: by
    /// this point that cycle has already finished and torn down its own state
    /// (see the `checkAndUploadMergedIfReady` gate), so this is the one
    /// remaining write for this session's manifest.
    private func uploadFinalManifest(sessionID: UUID, records: [ChunkRecord],
                                     entries: [String: MergedUploadEntry],
                                     provider: StorageProvider, token: String) async throws {
        var manifest = SessionManifest(
            schemaVersion: 1,
            sessionID: sessionID,
            recordingStartedUTC: records.map(\.startedAt).min() ?? Date(),
            recordingEndedUTC: records.map(\.endedAt).max() ?? Date(),
            deviceTimeZone: TimeZone.current.identifier,
            deviceModel: await UIDevice.current.model,
            systemVersion: await UIDevice.current.systemVersion,
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?",
            chunkCount: records.filter { $0.kind == .master }.count,
            chunks: records.map {
                SessionManifest.Chunk(
                    index: $0.index, camera: $0.camera.rawValue, fileName: $0.fileName,
                    kind: $0.kind.rawValue, sha256: $0.sha256,
                    captureStartUTC: $0.startedAt, captureEndUTC: $0.endedAt,
                    latitude: $0.latitude, longitude: $0.longitude, horizontalAccuracyMeters: $0.horizontalAccuracy)
            },
            mergedVideos: entries.map { camera, entry in
                SessionManifest.MergedVideo(
                    camera: camera, fileName: Self.mergedDriveFileName(camera: camera), sha256: entry.sha256)
            }
        )
        // Name the proof before hashing, so the manifest that gets anchored is
        // the same one that tells a reader where the proof is and how to check
        // it. The digest is taken over the final bytes below.
        let timestampName = Self.timestampFileName(for: sessionID)
        manifest.timestampAuthority = TimestampService.defaultAuthority.absoluteString
        manifest.timestampFileName = timestampName
        manifest.timestampVerification = SessionManifest.verificationInstructions

        let marks = MomentMarkers.between(manifest.recordingStartedUTC, manifest.recordingEndedUTC)
        manifest.markers = marks.isEmpty ? nil : marks
        guard let data = try? SessionManifest.encoder.encode(manifest) else { return }

        // Best-effort: an unreachable authority must never cost the user the
        // manifest itself. The proof is an addition to the evidence, not a
        // precondition for keeping it.
        var timestampToken: Data?
        do {
            timestampToken = try await TimestampService.token(forSHA256: Data(SHA256.hash(data: data)))
        } catch {
            await EventLogger.shared.log("timestamp.failed", detail: error.localizedDescription)
        }

        // Kept locally for good: these exact bytes are what the proof covers,
        // and everything else about this session is about to be cleaned up.
        SessionArchive.store(manifestData: data, timestampToken: timestampToken, for: sessionID)

        let folderName = UserDefaults.standard.string(forKey: "driveFolderName") ?? "Witness"
        let rootID = try await provider.ensureRootFolder(named: folderName, token: token)
        let folderID = try await ensureSessionFolder(
            sessionID: sessionID, folderName: folderName, rootID: rootID,
            provider: provider, token: token)

        let tmpURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("manifest-final-\(UUID().uuidString).json")
        try data.write(to: tmpURL)
        defer { try? FileManager.default.removeItem(at: tmpURL) }

        if let existingID = Self.manifestDriveIDs()[Self.manifestCacheKey(provider: provider, sessionID: sessionID)] {
            let request = provider.updateFileRequest(
                fileID: existingID, mimeType: "application/json", token: token)
            let (_, response) = try await URLSession.shared.upload(for: request, fromFile: tmpURL)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                throw UploadError(message: "Final manifest update failed")
            }
        } else {
            let (request, bodyURL) = try provider.multipartUploadRequest(
                fileURL: tmpURL, uploadName: Self.manifestFileName(for: sessionID),
                mimeType: "application/json", folderID: folderID, token: token)
            let (_, response) = try await URLSession.shared.upload(for: request, fromFile: bodyURL)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                throw UploadError(message: "Final manifest creation failed")
            }
        }
        // Upload the proof after the manifest it covers, so the folder is
        // never left holding a timestamp for a file that isn't there.
        if let timestampToken {
            let proofURL = FileManager.default.temporaryDirectory
                .appendingPathComponent("ts-\(UUID().uuidString).tsr")
            defer { try? FileManager.default.removeItem(at: proofURL) }
            if (try? timestampToken.write(to: proofURL)) != nil {
                let (request, bodyURL) = try provider.multipartUploadRequest(
                    fileURL: proofURL, uploadName: timestampName,
                    mimeType: "application/timestamp-reply", folderID: folderID, token: token)
                let (_, response) = try await URLSession.shared.upload(for: request, fromFile: bodyURL)
                if let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) {
                    await EventLogger.shared.log("timestamp.anchored", detail: timestampName)
                }
            }
        }

        try? FileManager.default.removeItem(at: Self.manifestURL(for: sessionID))
    }

    static func timestampFileName(for sessionID: UUID) -> String {
        "timestamp_\(sessionID.uuidString).tsr"
    }

    // MARK: - Recording lifecycle hooks

    func recordingDidStart() {
        workQueue.async { self.pump() }
    }

    /// After a recording stops, also ship a snapshot of the event log.
    func recordingDidStop() {
        Task {
            if let snapshot = await EventLogger.shared.snapshotForUpload() {
                self.uploadAuxiliaryFile(at: snapshot, mimeType: "application/x-ndjson")
            }
        }
        workQueue.async { self.pump() }
    }

    // MARK: - Queue pump

    /// Starts uploads for as many eligible chunks as there are free slots (see
    /// `maxConcurrentChunkUploads`), and re-pumps as each one finishes. Safe to
    /// call often — chunks are claimed atomically, so overlapping calls never
    /// upload the same file twice.
    func pump() {
        workQueue.async { [self] in
            guard networkAvailable else { return }
            // Retry any manifest revision the backend hasn't confirmed yet.
            for sessionID in manifestVersion.keys { attemptManifestUpload(sessionID) }
            // Retry any stitch merge still waiting to upload, and any
            // fully-confirmed session whose chunk-deletion/cleanup didn't finish.
            for sessionID in mergedUploads.keys { checkAndUploadMergedIfReady(sessionID) }
            for (sessionID, entries) in mergedUploads
            where !entries.isEmpty && entries.values.allSatisfy(\.confirmed) {
                finishDriveStitch(sessionID: sessionID, entries: entries)
            }
            guard !claiming else { pumpDeferred = true; return }
            claiming = true

            Task { [self] in
                defer {
                    self.workQueue.async { [self] in
                        claiming = false
                        if pumpDeferred { pumpDeferred = false; pump() }
                    }
                }
                // Ask the session what is genuinely in flight before claiming
                // more. Counting `.uploading` records instead would be enough
                // in the normal case but would wedge the queue for good if one
                // ever got stranded in that state.
                let live = await self.liveChunkTaskIDs()
                // Counted per kind: the two lanes fill independently, so a
                // master in flight must not close the stand-in lane.
                let inFlight = ChunkStore.shared.records { live.contains($0.id) }
                let limits: [ChunkRecord.Kind: Int] = [
                    .master: Self.maxConcurrentChunkUploads
                        - inFlight.filter { $0.kind == .master }.count,
                    .lowRes: Self.maxConcurrentStandInUploads
                        - inFlight.filter { $0.kind == .lowRes }.count,
                ]
                guard limits.values.contains(where: { $0 > 0 }) else { return }

                let claimed = ChunkStore.shared.claimUploadable(
                    limits: limits, excluding: live)
                guard !claimed.isEmpty else { return }

                let provider: StorageProvider
                let token: String
                do {
                    provider = try await self.requireProvider()
                    token = try await provider.freshAccessToken()
                } catch {
                    await MainActor.run { self.lastError = error.localizedDescription }
                    // Not a failed attempt — hand them back for a later pump.
                    claimed.forEach { ChunkStore.shared.release(id: $0.id, retryAfter: 30) }
                    self.scheduleRetryTimer()
                    return
                }
                let folderName = UserDefaults.standard.string(forKey: "driveFolderName") ?? "Witness"
                let folderID: String
                do {
                    folderID = try await provider.ensureRootFolder(named: folderName, token: token)
                } catch {
                    // Said out loud, not just retried. A server that refuses to
                    // create the root folder fails every chunk before any of
                    // them is attempted, so the queue shows "retrying, after 0
                    // attempts" and nothing else — for as long as the user
                    // leaves it. Reported from a WebDAV account where the
                    // server answered the folder creation with a 403 and the
                    // app never said so.
                    await MainActor.run { self.lastError = error.localizedDescription }
                    await EventLogger.shared.log(
                        "upload.root_folder_failed",
                        detail: "\(folderName): \(error.localizedDescription)")
                    claimed.forEach { ChunkStore.shared.release(id: $0.id, retryAfter: 30) }
                    self.scheduleGlobalRetry()
                    return
                }
                // Both steps above that can set `lastError` just succeeded —
                // clear a stale one now rather than leaving it to describe a
                // problem that stopped being true. It was never cleared
                // before, so a fixed account still showed its old failure
                // forever, in the one place someone would look to check
                // whether the fix had worked.
                await MainActor.run { self.lastError = nil }
                for record in claimed {
                    // Resolve (or create) the per-session subfolder named for
                    // where and when the recording started, then upload into it.
                    let sessionFolderID: String
                    do {
                        sessionFolderID = try await self.ensureSessionFolder(
                            sessionID: record.sessionID, folderName: folderName, rootID: folderID,
                            provider: provider, token: token, fallback: record)
                    } catch {
                        self.fail(recordID: record.id, message: error.localizedDescription)
                        continue
                    }
                    CrewStore.noteSessionFolder(record.sessionID, provider: provider.id, folderID: sessionFolderID)
                    // Footage is reaching the backend — make sure emergency
                    // contacts can see it as it arrives (where the backend
                    // supports sharing at all).
                    await self.shareSessionFolderIfNeeded(
                        sessionID: record.sessionID, folderID: sessionFolderID,
                        provider: provider, token: token)
                    await self.startUpload(record, provider: provider, token: token, folderID: sessionFolderID)
                }
                // Re-armed once the claims have resolved, not only before they
                // are made. Now that the pump takes a few chunks at a time, a
                // claim that comes straight back (an unhashable file, say)
                // needs a timer that knows about the deadline it just set —
                // there is no longer a guaranteed completion coming to re-pump.
                self.workQueue.async { self.scheduleRetryTimer() }
            }
            scheduleRetryTimer()
        }
    }

    /// The ids of chunk uploads the background session currently holds.
    ///
    /// This is the authoritative answer, and it survives the app being killed
    /// and relaunched for background events, which is exactly when the
    /// persisted queue's own view of what is uploading is out of date.
    private func liveChunkTaskIDs() async -> Set<UUID> {
        var ids: Set<UUID> = []
        for task in await backgroundSession.allTasks where task.state != .completed {
            guard let description = task.taskDescription,
                  description.hasPrefix("chunk:"),
                  let id = UUID(uuidString: String(description.dropFirst("chunk:".count)))
            else { continue }
            ids.insert(id)
        }
        return ids
    }

    private func startUpload(_ record: ChunkRecord, provider: StorageProvider,
                             token: String, folderID: String) async {
        var record = record
        if record.sha256 == nil {
            // Hash failed earlier — retry it now.
            if let hash = try? Hashing.sha256Hex(of: record.fileURL) {
                record.sha256 = hash
                ChunkStore.shared.update(record)
            } else {
                // Put it back in the queue; leaving it `.uploading` would
                // strand it until the next launch.
                ChunkStore.shared.release(id: record.id, retryAfter: 30)
                return
            }
        }

        guard let attrs = try? FileManager.default.attributesOfItem(atPath: record.fileURL.path),
              let fileSize = attrs[.size] as? Int else {
            // File vanished (should not happen) — log and drop the record.
            Task { await EventLogger.shared.log("upload.file_missing", detail: record.fileName) }
            ChunkStore.shared.markUploaded(id: record.id, driveFileID: nil)
            refreshPendingCount()
            return
        }

        do {
            let sessionURI = try await provider.beginResumableSession(
                fileName: record.fileName,
                mimeType: "video/mp4",
                fileSize: fileSize,
                folderID: folderID,
                token: token
            )
            record.resumableSessionURI = sessionURI.absoluteString
            record.state = .uploading
            ChunkStore.shared.update(record)

            let request = provider.resumableRequest(
                sessionURI: sessionURI, fileSize: fileSize, mimeType: "video/mp4", token: token)
            guard FileManager.default.isReadableFile(atPath: record.fileURL.path) else {
                // Almost certainly this chunk was confirmed by an earlier task
                // whose completion arrived while this one was being set up —
                // `markUploaded` deletes the local copy. Keep whatever remote
                // id that earlier pass recorded rather than blanking it.
                Task { await EventLogger.shared.log("upload.file_vanished", detail: record.fileName) }
                ChunkStore.shared.markUploaded(id: record.id, driveFileID: record.driveFileID)
                refreshPendingCount()
                return
            }
            let task: URLSessionUploadTask
            do {
                task = try makeUploadTask(request, fromFile: record.fileURL)
            } catch {
                // The guard above can lose the race — URLSession does its own
                // check on its own queue, after ours — so the file may be gone
                // by the time the task is built. Same conclusion either way: it
                // is not coming back, so retiring it beats retrying a file that
                // no longer exists until the app is deleted.
                if !FileManager.default.isReadableFile(atPath: record.fileURL.path) {
                    ChunkStore.shared.markUploaded(id: record.id, driveFileID: record.driveFileID)
                    refreshPendingCount()
                    return
                }
                throw error
            }
            task.taskDescription = "chunk:\(record.id.uuidString)"
            task.resume()
        } catch {
            fail(recordID: record.id, message: error.localizedDescription)
        }
    }

    // MARK: - Uploading a library-only recording later

    struct BackfillVideo {
        let fileName: String
        let mimeType: String
        /// A temporary export from the photo library, deleted once uploaded.
        let url: URL
    }

    /// Sends a recording that was kept only in the photo library to the
    /// folder it would have had: its videos, then its manifest, then the
    /// manifest's proof — the same order a live session ends in, so the
    /// folder never holds a proof for a file that isn't there. Videos already
    /// in the folder are skipped, so running it again after an interruption
    /// only sends what is missing.
    func uploadLibraryRecording(sessionID: UUID, manifest: SessionManifest,
                                videos: [BackfillVideo], manifestURL: URL,
                                timestampURL: URL?) async throws {
        let provider = try await requireProvider()
        let token = try await provider.freshAccessToken()
        let folderName = UserDefaults.standard.string(forKey: "driveFolderName") ?? "Witness"
        let rootID = try await provider.ensureRootFolder(named: folderName, token: token)
        // The folder is named for when and where the recording started, which
        // only the manifest knows: none of its pieces were ever queued.
        var origin = ChunkRecord(id: UUID(), fileName: "", sessionID: sessionID, index: 0,
                                 startedAt: manifest.recordingStartedUTC,
                                 endedAt: manifest.recordingEndedUTC)
        let located = manifest.chunks.first { $0.latitude != nil }
        origin.latitude = located?.latitude
        origin.longitude = located?.longitude
        let folderID = try await ensureSessionFolder(
            sessionID: sessionID, folderName: folderName, rootID: rootID,
            provider: provider, token: token, fallback: origin)
        CrewStore.noteSessionFolder(sessionID, provider: provider.id, folderID: folderID)
        await shareSessionFolderIfNeeded(sessionID: sessionID, folderID: folderID, provider: provider, token: token)

        for video in videos {
            defer { try? FileManager.default.removeItem(at: video.url) }
            if try await provider.findFile(named: video.fileName, parentID: folderID, token: token) != nil {
                continue
            }
            guard let size = try? FileManager.default.attributesOfItem(atPath: video.url.path)[.size] as? Int else {
                throw UploadError(message: "\(video.fileName) couldn't be read")
            }
            let sessionURI = try await provider.beginResumableSession(
                fileName: video.fileName, mimeType: video.mimeType, fileSize: size,
                folderID: folderID, token: token)
            let request = provider.resumableRequest(
                sessionURI: sessionURI, fileSize: size, mimeType: video.mimeType, token: token)
            let task = try makeUploadTask(request, fromFile: video.url)
            task.taskDescription = "backfill:\(video.url.path)"
            let failure: String? = await withCheckedContinuation { cont in
                workQueue.async { [self] in
                    backfillWaiters[task.taskIdentifier] = cont
                    task.resume()
                }
            }
            if let failure { throw UploadError(message: failure) }
            await EventLogger.shared.log("backfill.video_confirmed", detail: "\(sessionID.uuidString) \(video.fileName)")
        }

        for (url, name, mime) in [(manifestURL, Self.manifestFileName(for: sessionID), "application/json")]
            + (timestampURL.map { [($0, Self.timestampFileName(for: sessionID), "application/timestamp-reply")] } ?? []) {
            if try await provider.findFile(named: name, parentID: folderID, token: token) != nil { continue }
            let (request, bodyURL) = try provider.multipartUploadRequest(
                fileURL: url, uploadName: name, mimeType: mime, folderID: folderID, token: token)
            defer { try? FileManager.default.removeItem(at: bodyURL) }
            let (_, response) = try await URLSession.shared.upload(for: request, fromFile: bodyURL)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                throw UploadError(message: "\(name) wasn't accepted")
            }
        }
        await EventLogger.shared.log("backfill.session_uploaded", detail: sessionID.uuidString)
    }

    // MARK: - Emergency-contact sharing

    private static let sessionSharesKey = "sessionSharesGranted"

    /// Grants every emergency contact (with a valid email) read access to the
    /// session's remote folder, on backends that support it — the notification
    /// email from the backend is what alerts them. A backend with no sharing
    /// API (WebDAV, or any provider that says so via `supportsEmailSharing`)
    /// is skipped outright rather than retried every pump, since the UI
    /// already told the user at setup time that contacts won't be notified.
    /// Grants are recorded per session+email, so this is safe to call on every
    /// pump: already-granted contacts are skipped, failed grants retry, and a
    /// contact added mid-recording still gets access.
    /// Shares this phone's crew recordings with members whose email arrived
    /// after the footage had finished uploading. Sharing used to happen only
    /// as each piece uploaded, so a member learned later never got access.
    /// Already-granted addresses are skipped, so this is safe to repeat.
    func reshareCrewSessions(crewID: UUID) {
        Task {
            guard let provider = try? await requireProvider(),
                  let token = try? await provider.freshAccessToken() else { return }
            for sessionID in CrewStore.sessions(in: crewID) {
                guard let folder = CrewStore.folder(for: sessionID),
                      folder.provider == provider.id.rawValue else { continue }
                await shareSessionFolderIfNeeded(
                    sessionID: sessionID, folderID: folder.id, provider: provider, token: token)
            }
        }
    }

    private func shareSessionFolderIfNeeded(sessionID: UUID,
                                            folderID: String,
                                            provider: StorageProvider,
                                            token: String) async {
        guard provider.supportsEmailSharing else { return }
        // Emergency contacts, plus the rest of the crew when this was a crew
        // recording, so every member can reach every angle.
        let eligible = Set(EmergencyContact.load().filter(\.canReceiveFootage).compactMap(\.email)
                           + CrewStore.shareEmails(for: sessionID))
        guard !eligible.isEmpty else { return }

        var grantedMap = UserDefaults.standard.dictionary(forKey: Self.sessionSharesKey)
            as? [String: [String]] ?? [:]
        let granted = Set(grantedMap[sessionID.uuidString] ?? [])

        for email in eligible.sorted() {
            guard !granted.contains(email) else { continue }
            do {
                try await provider.share(fileID: folderID, withEmail: email, token: token)
                grantedMap[sessionID.uuidString, default: []].append(email)
                UserDefaults.standard.set(grantedMap, forKey: Self.sessionSharesKey)
                await EventLogger.shared.log(
                    "share.granted", detail: "\(sessionID.uuidString) → \(email)")
            } catch {
                // Not granted — retried on the next pump.
                await EventLogger.shared.log(
                    "share.failed", detail: "\(sessionID.uuidString) → \(email): \(error.localizedDescription)")
            }
        }
    }

    /// Resolves the remote subfolder for a recording session, named for where
    /// and when it started (e.g. "Witness 2026-07-17 13-04-22, San Francisco,
    /// CA"). Resolved once per session and cached (persisted across
    /// relaunches), so every chunk and the manifest land in the same folder.
    ///
    /// Self-healing: if resolution fails — most often because the user
    /// manually deleted the folder on the backend, leaving the cached ID
    /// dangling — the stale root and session cache entries are evicted and
    /// resolution is retried once against a freshly created folder, instead of
    /// failing forever on the same dead ID. Retrying unconditionally (rather
    /// than only on a "not found" response) costs one extra round trip on an
    /// ordinary network failure, which is cheap next to needing a per-backend
    /// way to recognise "not found".
    private func ensureSessionFolder(sessionID: UUID,
                                     folderName: String,
                                     rootID: String,
                                     provider: StorageProvider,
                                     token: String,
                                     fallback: ChunkRecord? = nil) async throws -> String {
        do {
            return try await resolveSessionFolder(
                sessionID: sessionID, rootID: rootID, provider: provider,
                token: token, fallback: fallback)
        } catch {
            await DriveFolderCache.sharedRoot.evict(key: "root")
            await DriveFolderCache.shared.evict(key: Self.sessionCacheKey(provider: provider, sessionID: sessionID))
            let freshRootID = try await provider.ensureRootFolder(
                named: folderName, token: token)
            return try await resolveSessionFolder(
                sessionID: sessionID, rootID: freshRootID, provider: provider,
                token: token, fallback: fallback)
        }
    }

    /// Namespaced by provider: a session's folder ID from one backend must
    /// never be reused as another backend's, if the user switches storage
    /// providers between uploading a session's chunks (both root-folder and
    /// per-session caches are otherwise shared, provider-agnostic stores).
    private static func sessionCacheKey(provider: StorageProvider, sessionID: UUID) -> String {
        "\(provider.id.rawValue):\(sessionID.uuidString)"
    }

    private func resolveSessionFolder(sessionID: UUID,
                                      rootID: String,
                                      provider: StorageProvider,
                                      token: String,
                                      fallback: ChunkRecord?) async throws -> String {
        try await DriveFolderCache.shared.folderID(for: Self.sessionCacheKey(provider: provider, sessionID: sessionID)) {
            // Name from the session's *first* chunk, not whichever uploads
            // first — otherwise each chunk would name its own folder.
            let origin = ChunkStore.shared.sessionOrigin(sessionID)
            let date = origin?.date ?? fallback?.startedAt ?? Date()
            var latitude = origin?.latitude ?? fallback?.latitude
            var longitude = origin?.longitude ?? fallback?.longitude
            // Still none: use the phone's current fix if it is from around
            // the recording, as the folder is made while it is under way.
            if latitude == nil, let now = await MainActor.run(body: { LocationService.shared.lastLocation }),
               abs(now.timestamp.timeIntervalSince(date)) < 600 {
                latitude = now.coordinate.latitude
                longitude = now.coordinate.longitude
            }
            let name = await SessionFolderNaming.folderName(
                sessionID: sessionID, date: date, latitude: latitude, longitude: longitude)
            return try await provider.ensureSubfolder(
                named: name, parentID: rootID, token: token)
        }
    }

    /// Uploads a standalone file (session manifest, event-log snapshot) into
    /// `folderID`, or the root folder when none is given. Best-effort with the
    /// same background session; deleted locally only on confirmed receipt.
    private func uploadAuxiliaryFile(at url: URL,
                                     mimeType: String,
                                     folderID: String? = nil,
                                     token: String? = nil) {
        Task {
            do {
                let provider = try await requireProvider()
                let accessToken: String
                if let token {
                    accessToken = token
                } else {
                    accessToken = try await provider.freshAccessToken()
                }
                let target: String
                if let folderID {
                    target = folderID
                } else {
                    let folderName = UserDefaults.standard.string(forKey: "driveFolderName") ?? "Witness"
                    target = try await provider.ensureRootFolder(
                        named: folderName, token: accessToken)
                }
                let (request, bodyURL) = try provider.multipartUploadRequest(
                    fileURL: url,
                    uploadName: url.lastPathComponent,
                    mimeType: mimeType,
                    folderID: target,
                    token: accessToken
                )
                let task = try self.makeUploadTask(request, fromFile: bodyURL)
                task.taskDescription = "aux:\(url.path)"
                task.resume()
            } catch {
                // Stays on disk; retried on a later pump or next launch.
            }
        }
    }

    // MARK: - Retry / backoff

    private func fail(recordID: UUID, message: String) {
        guard var record = ChunkStore.shared.record(id: recordID) else { return }
        record.attemptCount += 1
        record.state = .queued
        record.resumableSessionURI = nil // re-initiate next attempt
        record.lastErrorMessage = message
        let delay = min(300.0, 5.0 * pow(2.0, Double(record.attemptCount - 1)))
        record.nextRetryAt = Date().addingTimeInterval(delay)
        ChunkStore.shared.update(record)
        Task { await EventLogger.shared.log(
            "upload.failed",
            detail: "\(record.fileName) attempt=\(record.attemptCount) retryIn=\(Int(delay))s: \(message)") }
        DispatchQueue.main.async { self.lastError = message }
        scheduleRetryTimer()
    }

    private func scheduleGlobalRetry() {
        workQueue.asyncAfter(deadline: .now() + 30) { self.pump() }
    }

    private func scheduleRetryTimer() {
        retryTimer?.cancel()
        guard let next = ChunkStore.shared.earliestRetryDate else { return }
        let delay = max(1, next.timeIntervalSinceNow)
        let timer = DispatchSource.makeTimerSource(queue: workQueue)
        timer.schedule(deadline: .now() + delay)
        timer.setEventHandler { [weak self] in self?.pump() }
        timer.resume()
        retryTimer = timer
    }

    func refreshPendingCount() {
        let count = ChunkStore.shared.pendingCount
        let queue = Self.queueSnapshot()
        let receipt = UploadReceipts.mostRecent
        DispatchQueue.main.async {
            self.pendingCount = count
            self.queued = queue
            self.lastUpload = receipt
        }
    }

    /// The outstanding queue, flattened for display.
    ///
    /// Built here rather than read from `ChunkStore` by the view, so the UI
    /// never touches the store on the main thread while the work queue is
    /// mutating it. Sorted by the same comparator the uploader claims with, so
    /// the list is the actual plan — top row next — rather than a second
    /// opinion about it.
    private static func queueSnapshot() -> [QueuedChunk] {
        ChunkStore.shared.records { $0.state != .uploaded }
            .sorted(by: ChunkRecord.precedesInUploadOrder)
            .map { record in
                QueuedChunk(
                    id: record.id,
                    index: record.index,
                    camera: record.camera,
                    kind: record.kind,
                    state: record.state,
                    attemptCount: record.attemptCount,
                    nextRetryAt: record.nextRetryAt,
                    lastErrorMessage: record.lastErrorMessage,
                    // Recorded at ingest; the disk is only consulted for
                    // records written before that field existed.
                    byteCount: record.byteCount
                        ?? ((try? FileManager.default.attributesOfItem(
                            atPath: record.fileURL.path)[.size] as? Int) ?? nil)
                )
            }
    }
}

/// One outstanding chunk, as the upload sheet needs it. A value type snapshot
/// rather than the record itself: the store is mutated off the main thread, and
/// handing SwiftUI a live reference to it would be a data race.
struct QueuedChunk: Identifiable, Equatable {
    let id: UUID
    let index: Int
    let camera: CameraSource
    let kind: ChunkRecord.Kind
    let state: ChunkRecord.State
    let attemptCount: Int
    let nextRetryAt: Date?
    let lastErrorMessage: String?
    let byteCount: Int?
}

// MARK: - URLSessionDelegate

extension UploadManager: URLSessionDataDelegate {
    /// Byte progress for a chunk in flight.
    ///
    /// Called often, so it does the least possible work: parse the id, compute
    /// a fraction, hop to main. Anything heavier here would compete with the
    /// transfer it is measuring.
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64,
                    totalBytesExpectedToSend: Int64) {
        guard totalBytesExpectedToSend > 0,
              let description = task.taskDescription,
              description.hasPrefix("chunk:"),
              let id = UUID(uuidString: String(description.dropFirst("chunk:".count)))
        else { return }

        let fraction = min(1.0, Double(totalBytesSent) / Double(totalBytesExpectedToSend))
        DispatchQueue.main.async { self.chunkProgress[id] = fraction }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        workQueue.async {
            self.responseBuffers[dataTask.taskIdentifier, default: Data()].append(data)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        // Whatever happened to it, this chunk is no longer in flight, so its
        // progress entry goes. Left behind, a failed chunk would sit in the
        // sheet showing a stalled bar at whatever fraction it died on.
        if let description = task.taskDescription, description.hasPrefix("chunk:"),
           let id = UUID(uuidString: String(description.dropFirst("chunk:".count))) {
            DispatchQueue.main.async { self.chunkProgress[id] = nil }
        }

        workQueue.async { [self] in
            let body = responseBuffers.removeValue(forKey: task.taskIdentifier)
            let status = (task.response as? HTTPURLResponse)?.statusCode ?? -1
            let succeeded = error == nil && (200...299).contains(status)
            guard let description = task.taskDescription else { return }

            if description.hasPrefix("backfill:") {
                try? FileManager.default.removeItem(atPath: String(description.dropFirst("backfill:".count)))
                let failure = succeeded ? nil : (error?.localizedDescription ?? "HTTP \(status)")
                backfillWaiters.removeValue(forKey: task.taskIdentifier)?.resume(returning: failure)
                return
            }

            if description.hasPrefix("aux:") {
                let path = String(description.dropFirst(4))
                if succeeded { try? FileManager.default.removeItem(atPath: path) }
                return
            }

            if description.hasPrefix("manifest:") {
                let raw = String(description.dropFirst("manifest:".count))
                if let sessionID = UUID(uuidString: raw) {
                    handleManifestCompletion(sessionID, succeeded: succeeded, body: body)
                }
                return
            }

            if description.hasPrefix("merged:") {
                let rest = description.dropFirst("merged:".count)
                let parts = rest.split(separator: ":", maxSplits: 1)
                if parts.count == 2, let sessionID = UUID(uuidString: String(parts[0])) {
                    Task { [self] in
                        // Immutable before crossing queues, as above.
                        let provider = succeeded
                            ? await StorageProviders.shared.currentProvider() : nil
                        let remoteFileID = body.flatMap { provider?.fileID(fromResponseBody: $0) }
                        self.workQueue.async { [self] in
                            handleMergedUploadCompletion(
                                sessionID: sessionID, camera: String(parts[1]),
                                succeeded: succeeded, remoteFileID: remoteFileID)
                        }
                    }
                }
                return
            }

            let idString = description.split(separator: ":").last.map(String.init) ?? ""
            guard let id = UUID(uuidString: idString) else { return }

            if !succeeded {
                let message = error?.localizedDescription ?? "HTTP \(status)"
                fail(recordID: id, message: message)
                // Its master may have landed while this was in flight, in
                // which case retrying it would upload something already
                // redundant — and `supersedeLowRes` has been and gone.
                retireStandInIfMasterLanded(id)
                return
            }

            if description.hasPrefix("chunk:") {
                Task { [self] in
                    // Resolved to an immutable value before it crosses onto
                    // the work queue: a captured `var` read from another
                    // queue is a data race, not just a Swift 6 diagnostic.
                    let provider = await StorageProviders.shared.currentProvider()
                    let remoteFileID = body.flatMap { provider?.fileID(fromResponseBody: $0) }
                    self.workQueue.async { [self] in
                        let confirmed = ChunkStore.shared.record(id: id)
                        let fileName = confirmed?.fileName ?? idString
                        // Weighed before `markUploaded`, which deletes the
                        // file — afterwards there is nothing left to measure.
                        if let confirmed {
                            let size = (try? FileManager.default.attributesOfItem(
                                atPath: confirmed.fileURL.path)[.size] as? Int) ?? nil
                            UploadReceipts.recordConfirmed(
                                sessionID: confirmed.sessionID, startedAt: confirmed.startedAt,
                                kind: confirmed.kind, bytes: size ?? 0)
                        }
                        // Backend confirmed the video → safe to delete the
                        // local copy. The chunk's hash lives on in the
                        // session manifest.
                        ChunkStore.shared.markUploaded(id: id, driveFileID: remoteFileID)
                        Task { await EventLogger.shared.log(
                            "upload.chunk_confirmed",
                            detail: "\(fileName) fileID=\(remoteFileID ?? "?")") }
                        // The full file has landed, so its stand-in is now redundant.
                        if confirmed?.kind == .master {
                            supersedeLowRes(ofMaster: id)
                        } else {
                            // Or this *is* a stand-in, finishing after the
                            // master it stands in for. It was skipped while in
                            // flight; now it can go.
                            retireStandInIfMasterLanded(id)
                        }
                        refreshPendingCount()
                        pump()
                    }
                }
            }
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async { [self] in
            backgroundCompletionHandler?()
            backgroundCompletionHandler = nil
        }
    }
}
