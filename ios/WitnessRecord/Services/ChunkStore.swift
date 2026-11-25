import Foundation
import UIKit

/// Owns the on-disk chunk directory and the persisted upload queue.
/// Chunk files are only deleted here, and only after Drive confirms receipt.
final class ChunkStore {
    static let shared = ChunkStore()

    static var chunksDirectory: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("chunks", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private static var queueFileURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("upload_queue.json")
    }

    private let queue = DispatchQueue(label: "com.witnessrecord.chunkstore")
    private var records: [ChunkRecord] = []

    private init() {
        // A coalesced write must not be lost to an app that stops running
        // inside the two second window.
        for name in [UIApplication.didEnterBackgroundNotification,
                     UIApplication.willTerminateNotification] {
            NotificationCenter.default.addObserver(
                forName: name, object: nil, queue: nil) { [weak self] _ in self?.flush() }
        }
        records = Self.loadFromDisk()
        // Anything that was mid-upload when the app died goes back to queued.
        for i in records.indices where records[i].state == .uploading {
            records[i].state = .queued
        }
        persistLocked()
    }

    // MARK: - Queue operations

    func add(_ record: ChunkRecord) {
        queue.sync {
            records.append(record)
            persistLocked()
        }
    }

    func update(_ record: ChunkRecord) {
        queue.sync {
            if let i = records.firstIndex(where: { $0.id == record.id }) {
                records[i] = record
                persistLocked(.soon)
            }
        }
    }

    /// Marks a chunk uploaded and deletes its local video file. The record
    /// itself is kept (without the file) as a receipt of what went where.
    func markUploaded(id: UUID, driveFileID: String?) {
        queue.sync {
            guard let i = records.firstIndex(where: { $0.id == id }) else { return }
            records[i].state = .uploaded
            records[i].driveFileID = driveFileID
            try? FileManager.default.removeItem(at: records[i].fileURL)
            persistLocked()
        }
    }

    func record(id: UUID) -> ChunkRecord? {
        queue.sync { records.first { $0.id == id } }
    }

    func records(matching predicate: (ChunkRecord) -> Bool) -> [ChunkRecord] {
        queue.sync { records.filter(predicate) }
    }

    /// Drops a single record and its local file. Used for a low-res stand-in
    /// once the full-quality chunk has superseded it — unlike `markUploaded`,
    /// no receipt is kept, because the file it described no longer exists in
    /// Drive either.
    func remove(id: UUID) {
        queue.sync {
            guard let i = records.firstIndex(where: { $0.id == id }) else { return }
            try? FileManager.default.removeItem(at: records[i].fileURL)
            records.remove(at: i)
            persistLocked()
        }
    }

    /// Atomically selects the chunks ready for upload (oldest first, honoring
    /// backoff deadlines) and marks them `.uploading` in the same locked step.
    ///
    /// Claiming and marking must be atomic: uploads are kicked off across
    /// `await` boundaries, and `pump()` runs from many triggers. If a chunk
    /// stayed `.queued` while its upload was being set up, a second pump would
    /// claim it too and upload the same file twice.
    /// - Parameters:
    ///   - limits: How many to claim of each kind, so stand-ins are not held
    ///     up behind a full-quality file that started before they existed;
    ///     see `UploadManager.maxConcurrentChunkUploads`.
    ///   - inFlight: Chunks the caller already has a live upload task for.
    ///     After a background relaunch the store has reset those to `.queued`
    ///     (it cannot see the session's tasks), so without this they would be
    ///     claimed a second time and uploaded twice.
    func claimUploadable(now: Date = Date(),
                         limits: [ChunkRecord.Kind: Int],
                         excluding inFlight: Set<UUID> = []) -> [ChunkRecord] {
        queue.sync {
            let eligible = records.indices.filter { i in
                records[i].state == .queued
                    && !inFlight.contains(records[i].id)
                    && (records[i].nextRetryAt.map { $0 <= now } ?? true)
            }
            // Ordered before the limits are applied, not after: truncating
            // first would hand back whichever chunks happen to sit earliest in
            // the array rather than the ones that should go next.
            let ordered = eligible.sorted {
                ChunkRecord.precedesInUploadOrder(records[$0], records[$1])
            }

            var remaining = limits
            var claimed: [ChunkRecord] = []
            for i in ordered {
                guard let left = remaining[records[i].kind], left > 0 else { continue }
                remaining[records[i].kind] = left - 1
                records[i].state = .uploading
                claimed.append(records[i])
            }
            if !claimed.isEmpty { persistLocked(.soon) }
            return claimed
        }
    }

    /// Returns a claimed chunk to the queue without counting a failed attempt —
    /// used when setup fails before any upload was actually started.
    func release(id: UUID, retryAfter: TimeInterval? = nil) {
        queue.sync {
            guard let i = records.firstIndex(where: { $0.id == id }),
                  records[i].state == .uploading else { return }
            records[i].state = .queued
            if let retryAfter {
                records[i].nextRetryAt = Date().addingTimeInterval(retryAfter)
            }
            persistLocked(.soon)
        }
    }

    var pendingCount: Int {
        queue.sync { records.filter { $0.state != .uploaded }.count }
    }

    /// When and where a recording session began, taken from its lowest-indexed
    /// chunk. Every chunk of a session resolves to the same origin, so the
    /// session's Drive folder name is identical no matter which chunk uploads
    /// first. Uploaded records are retained as receipts, so this stays stable
    /// for the whole session.
    /// Removes a session's records — used once its small chunks have been
    /// deleted from Drive because a merged video now supersedes them. Safe:
    /// the merged video's own upload is confirmed before this is ever called,
    /// so nothing is dropped while still the only copy anywhere.
    ///
    /// When `camera` is given, only that camera's records are removed — e.g.
    /// the rear camera's merge succeeded and its chunks were deleted from
    /// Drive, but the front camera's merge failed, so its small chunks are
    /// still the only copy and must stay tracked.
    func removeAll(forSession sessionID: UUID, camera: CameraSource? = nil) {
        queue.sync {
            let matches: (ChunkRecord) -> Bool = {
                $0.sessionID == sessionID && (camera == nil || $0.camera == camera)
            }
            for record in records where matches(record) {
                try? FileManager.default.removeItem(at: record.fileURL)
            }
            records.removeAll(where: matches)
            persistLocked()
        }
    }

    /// Every record belonging to a session, ordered for the manifest.
    /// Uploaded records are retained as receipts, so this stays complete for
    /// the whole session even after files are deleted.
    func records(forSession sessionID: UUID) -> [ChunkRecord] {
        queue.sync {
            records
                .filter { $0.sessionID == sessionID }
                .sorted { ($0.index, $0.camera.rawValue) < ($1.index, $1.camera.rawValue) }
        }
    }

    func sessionOrigin(_ sessionID: UUID) -> (date: Date, latitude: Double?, longitude: Double?)? {
        queue.sync {
            // The date from the first chunk; the place from the first chunk
            // that has one, since a cold start (Action Button) often begins
            // recording before the phone has a location fix.
            let chunks = records.filter { $0.sessionID == sessionID }.sorted { $0.index < $1.index }
            guard let first = chunks.first else { return nil }
            let located = chunks.first { $0.latitude != nil && $0.longitude != nil }
            return (first.startedAt, located?.latitude, located?.longitude)
        }
    }

    var earliestRetryDate: Date? {
        queue.sync {
            records.filter { $0.state == .queued }.compactMap(\.nextRetryAt).min()
        }
    }

    // MARK: - Persistence

    /// Bump when `ChunkRecord`'s on-disk shape changes in a way lenient
    /// decoding can't absorb on its own — a field rename or type change, not
    /// just adding a new Optional field (which decodes fine on old data as-is).
    /// When that happens, branch on the *stored* version inside
    /// `loadFromDisk` to transform the raw JSON before decoding, so a user who
    /// updates mid-recording doesn't lose chunks still waiting to upload.
    private static let currentSchemaVersion = 1

    private struct PersistedQueue: Codable {
        var schemaVersion: Int
        var records: [ChunkRecord]
    }

    /// Mirrors `PersistedQueue` but decodes each record independently, so one
    /// unreadable record can't blank out the whole queue.
    private struct PersistedQueueDecoding: Decodable {
        var schemaVersion: Int?
        var records: [FailableDecodable<ChunkRecord>]
    }

    /// How soon a change has to be on disk.
    ///
    /// Every write re-encodes the entire queue, so its cost grows with the
    /// session: a two-hour recording ends up rewriting a third of a megabyte
    /// on each change, several times per chunk. That is not only wasted work —
    /// it puts a growing encode in front of every state change, including the
    /// file deletions that other uploads are racing.
    ///
    /// So changes that a relaunch can reconstruct are coalesced, and only the
    /// ones that cannot be are written immediately.
    private enum Durability {
        /// The record is the only thing that knows this file exists, or that
        /// it no longer does. Losing it strands a chunk on disk forever —
        /// nothing scans the directory for files without records.
        case immediately
        /// Recoverable at launch: hashes recompute, upload states are reset to
        /// `.queued` anyway, and backoff deadlines are advisory.
        case soon
    }

    private var pendingWrite: DispatchWorkItem?

    private func persistLocked(_ durability: Durability = .immediately) {
        pendingWrite?.cancel()
        pendingWrite = nil

        guard durability == .immediately else {
            let work = DispatchWorkItem { [weak self] in
                guard let self else { return }
                self.pendingWrite = nil
                self.writeLocked()   // already on `queue`, via asyncAfter below
            }
            pendingWrite = work
            queue.asyncAfter(deadline: .now() + 2, execute: work)
            return
        }
        writeLocked()
    }

    /// Caller must hold `queue`.
    private func writeLocked() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let payload = PersistedQueue(schemaVersion: Self.currentSchemaVersion, records: records)
        if let data = try? encoder.encode(payload) {
            try? data.write(to: Self.queueFileURL, options: .atomic)
        }
    }

    /// Writes anything still coalesced. Called when the app is backgrounded or
    /// about to be terminated, so a pending change is never lost to a two
    /// second window that the app did not survive.
    func flush() {
        queue.sync {
            guard pendingWrite != nil else { return }
            pendingWrite?.cancel()
            pendingWrite = nil
            writeLocked()
        }
    }

    private static func loadFromDisk() -> [ChunkRecord] {
        guard let data = try? Data(contentsOf: queueFileURL) else { return [] }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601

        // Current versioned envelope, decoded leniently per record.
        if let versioned = try? decoder.decode(PersistedQueueDecoding.self, from: data) {
            let records = versioned.records.compactMap(\.value)
            let dropped = versioned.records.count - records.count
            if dropped > 0 {
                Task { await EventLogger.shared.log(
                    "chunkstore.records_dropped",
                    detail: "\(dropped) unreadable record(s) on load — see file for others") }
            }
            return records
        }

        // Legacy format: a bare array, written before schema versioning
        // existed. Same lenient per-record decoding.
        if let legacy = try? decoder.decode([FailableDecodable<ChunkRecord>].self, from: data) {
            return legacy.compactMap(\.value)
        }

        Task { await EventLogger.shared.log(
            "chunkstore.load_failed", detail: "queue file unreadable, starting empty") }
        return []
    }
}
