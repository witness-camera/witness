import Foundation
import CoreLocation

/// Which physical camera produced a chunk. In dual-camera mode both are
/// recorded simultaneously; in single-camera mode only `.back` is used.
enum CameraSource: String, Codable {
    case back
    case front
    /// The two-lens mode records the rear 1× and 0.5× lenses side by side,
    /// each as its own stream, so neither is "the" rear camera.
    case wide
    case ultraWide = "ultrawide"

    /// Which stream uploads first when both have a chunk waiting: the
    /// primary view, then the secondary one.
    var uploadPriority: Int {
        switch self {
        case .back, .wide: return 0
        case .front, .ultraWide: return 1
        }
    }

    var displayName: String {
        switch self {
        case .back: return "Rear"
        case .front: return "Front"
        case .wide: return "Wide 1×"
        case .ultraWide: return "Ultra-wide 0.5×"
        }
    }
}

/// One recorded video chunk, tracked from finalization on disk until Google
/// Drive confirms receipt. Persisted as JSON by `ChunkStore` so the upload
/// queue survives relaunches — including across an app update, possibly with
/// chunks from an in-progress recording still queued.
///
/// Schema discipline: new fields must be `Optional` (or have a `Decodable`
/// default) so records written by an older app version keep decoding after an
/// update. `ChunkStore` also decodes each record independently, so even a
/// record that fails to decode only drops that one chunk rather than the
/// whole queue — but a rename or type change of an *existing* field still
/// needs a real migration; see `ChunkStore.currentSchemaVersion`.
struct ChunkRecord: Codable, Identifiable, Equatable {
    enum State: String, Codable {
        case pendingHash      // file finalized, hash not yet computed
        case queued           // hashed, waiting for upload
        case uploading        // upload task in flight
        case uploaded         // Drive confirmed receipt; local file deleted
    }

    /// Whether this record is the full-quality recording or the small
    /// stand-in sent ahead of it on a poor connection.
    enum Kind: String, Codable {
        case master
        case lowRes
    }

    let id: UUID
    /// File name inside the chunks directory (relative, so the record stays
    /// valid if the app container path changes between launches).
    let fileName: String
    let sessionID: UUID
    let index: Int
    let startedAt: Date
    let endedAt: Date
    var camera: CameraSource = .back

    var sha256: String?
    var latitude: Double?
    var longitude: Double?
    var horizontalAccuracy: Double?

    /// Optional with a default so records written before low-res existed keep
    /// decoding after an update, per the schema discipline above.
    var kind: Kind = .master
    /// Set only on a low-res record: the full-quality chunk it stands in for.
    /// Once that chunk is confirmed in Drive, this one is deleted there — the
    /// stand-in has served its purpose and would only clutter the folder.
    var masterChunkID: UUID?

    /// Size on disk, measured once when the chunk is finalized.
    ///
    /// Stored rather than read back, because the upload sheet needs it for
    /// every outstanding chunk and reading it meant a `stat` per record on
    /// every refresh — and a refresh happens each time a chunk confirms. Over
    /// a two-hour session that is hundreds of thousands of syscalls to
    /// re-answer a question whose answer never changes. Optional, per the
    /// schema rule above: records written before this existed decode fine and
    /// fall back to the disk.
    var byteCount: Int?

    var state: State = .pendingHash
    var attemptCount: Int = 0
    var nextRetryAt: Date?
    /// What the last failed attempt actually said — an HTTP status or the
    /// error text from the network/provider layer. Shown next to the retry
    /// countdown in the upload queue so "retrying" comes with a reason
    /// instead of leaving that to a single global message that a different
    /// chunk's failure could overwrite a moment later. Optional per the
    /// schema rule above; cleared implicitly, since a record that uploads
    /// successfully leaves the queue rather than needing its error reset.
    var lastErrorMessage: String?
    /// Drive resumable-session URI, kept so an interrupted upload can resume.
    var resumableSessionURI: String?
    var driveFileID: String?

    var fileURL: URL { ChunkStore.chunksDirectory.appendingPathComponent(fileName) }
}

extension ChunkRecord {
    /// The one definition of upload order — used both to pick what uploads
    /// next and to draw the queue.
    ///
    /// It used to be written twice, in `ChunkStore.claimUploadable` and in the
    /// upload sheet's snapshot, and the two disagreed. The sheet grouped a
    /// stand-in next to its master by index; the uploader took every stand-in
    /// first. So the list showed a plan the uploader was not following, which
    /// is worse than showing nothing.
    ///
    /// Stand-ins go first — all of them, not just the one whose turn it is.
    /// They exist only because the connection is failing, they are a tenth the
    /// size, and the point is to get *something* of every moment off the
    /// device before spending minutes on any single full-quality file.
    /// Then oldest first, so what remains drains in the order it was filmed.
    ///
    /// A stand-in inherits its master's `startedAt` and `index`, so the two tie
    /// on both and need a last tiebreak: `sorted(by:)` is not stable, and
    /// without one a pair would swap places between refreshes.
    static func precedesInUploadOrder(_ a: ChunkRecord, _ b: ChunkRecord) -> Bool {
        if (a.kind == .lowRes) != (b.kind == .lowRes) { return a.kind == .lowRes }
        if a.startedAt != b.startedAt { return a.startedAt < b.startedAt }
        if a.index != b.index { return a.index < b.index }
        if a.camera != b.camera { return a.camera.uploadPriority < b.camera.uploadPriority }
        return a.id.uuidString < b.id.uuidString
    }
}

/// One JSON manifest per recording session — the chain-of-custody record for
/// the whole encounter, uploaded once when recording ends. It ties every chunk
/// of every camera to its SHA-256 hash, capture window, and location.
struct SessionManifest: Codable {
    struct Chunk: Codable {
        let index: Int
        let camera: String
        let fileName: String
        /// "master" for the full-quality recording, "lowRes" for a small
        /// stand-in uploaded ahead of it on a failing connection. Named
        /// explicitly so a reader can tell at a glance why two files carry the
        /// same index, and which one is the record of what was filmed.
        ///
        /// Optional for records written before stand-ins existed; absent means
        /// master.
        var kind: String? = nil
        let sha256: String?
        let captureStartUTC: Date
        let captureEndUTC: Date
        let latitude: Double?
        let longitude: Double?
        let horizontalAccuracyMeters: Double?
    }

    /// One camera's small chunks consolidated into a single video, once every
    /// chunk of that camera was confirmed uploaded. Present only after
    /// consolidation — see `UploadManager`'s Drive-stitch pipeline. The
    /// `chunks` list above still documents the original per-piece hashes even
    /// after the small files themselves are removed from Drive.
    struct MergedVideo: Codable {
        let camera: String
        let fileName: String
        let sha256: String?
    }

    /// Format version of this manifest file itself — for any external tool or
    /// person reading it later (a contact, an attorney) to know which shape to
    /// expect. Bump alongside `ChunkStore`'s schema version if this struct's
    /// on-disk shape changes in an incompatible way.
    let schemaVersion: Int
    let sessionID: UUID
    let recordingStartedUTC: Date
    let recordingEndedUTC: Date
    let deviceTimeZone: String
    let deviceModel: String
    let systemVersion: String
    let appVersion: String
    let chunkCount: Int
    let chunks: [Chunk]
    var mergedVideos: [MergedVideo]? = nil
    /// Where the footage was kept, when that isn't the cloud folder this
    /// manifest sits in — written for recordings kept only in the photo
    /// library, whose 15-second pieces were never uploaded or retained.
    var storage: String? = nil
    /// The crew this recording was part of, and how this phone's clock
    /// relates to the crew's, so the angles can be lined up from the
    /// manifests alone.
    var crew: CrewStamp? = nil
    /// Moments the person marked while recording (a flag tap or volume-up
    /// press), as UTC times. Part of the hashed, time-stamped record, so a
    /// mark can be shown to have been made during the recording itself.
    var markers: [Date]? = nil

    /// Where the accompanying RFC 3161 proof came from, and what it is called
    /// in this folder. The proof covers the SHA-256 of *this file*, so it
    /// cannot name its own hash here — instead it names the file that carries
    /// it, and the note below says how to check the two against each other.
    ///
    /// Present only once a timestamp was actually obtained: absent means the
    /// authority was unreachable, never that the check failed.
    var timestampAuthority: String? = nil
    var timestampFileName: String? = nil
    var timestampVerification: String? = nil

    /// Written into the manifest so the instructions travel with the evidence
    /// and stay readable years later, by someone with no access to this app.
    static let verificationInstructions = """
        This manifest is counter-signed by an independent RFC 3161 time-stamp \
        authority, proving it existed unmodified before the time in the \
        accompanying .tsr file. Because every chunk's SHA-256 is listed above, \
        that proof extends to the footage itself. To verify: \
        `openssl ts -verify -data <this file> -in <timestampFileName> -CAfile <authority CA cert>`. \
        Any edit to this manifest, or to any file whose hash it lists, breaks \
        the check.
        """

    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        return e
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()
}
