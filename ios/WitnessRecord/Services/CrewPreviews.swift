import AVFoundation
import CloudKit
import Combine
import CryptoKit
import Foundation

/// Small watchable copies of every crew angle, so the crew can see the event
/// from all its phones, on one clock, right there on a phone — without
/// waiting to get the full footage onto a laptop.
///
/// When a crew recording stops, each camera's video is shrunk to a preview
/// (a few MB a minute), sealed with the crew's key and posted to the same
/// public iCloud database the crew already syncs through. Other members'
/// phones fetch the previews they don't have while the crew screen is open.
/// As with the crew's sync, Apple stores only ciphertext and Witness has no
/// server: only phones holding the crew's code can open them.
///
/// The full-quality footage still goes only to each member's own cloud;
/// previews are for watching and choosing angles, not evidence.
///
/// Setup outside the code, once: in CloudKit Console a `CrewPreview` record
/// type with `tag` (String, Queryable), `angle` (Bytes) and `video` (Asset),
/// deployed to Production before release.
///
/// Built so a live view can be added later: a preview is just an angle's
/// video on the crew's clock, and the player takes any `AVPlayer` source.
@MainActor
final class CrewPreviews: ObservableObject {
    static let shared = CrewPreviews()
    private static let recordType = "CrewPreview"
    /// Long recordings are previewed up to this length; the full file has the
    /// rest. Keeps an upload to tens of MB.
    nonisolated static let maxPreviewSeconds: Double = 20 * 60

    /// Angle ids with a preview on this phone. Published so the player and the
    /// crew screen update as downloads land.
    @Published private(set) var available: Set<String> = []
    /// Angle ids being prepared or fetched right now.
    @Published private(set) var inFlight: Set<String> = []

    private lazy var database = CKContainer(identifier: CrewCloud.containerID).publicCloudDatabase

    private init() { refreshAvailable() }

    // MARK: - Files

    nonisolated static var root: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("crew-previews", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    nonisolated static func fileURL(for angleID: String) -> URL {
        root.appendingPathComponent("\(angleID).mp4")
    }

    func url(for angle: CrewAngle) -> URL? {
        available.contains(angle.id) ? Self.fileURL(for: angle.id) : nil
    }

    private func refreshAvailable() {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: Self.root.path)) ?? []
        available = Set(names.filter { $0.hasSuffix(".mp4") }.map { String($0.dropLast(4)) })
    }

    /// Removes the previews of a crew deleted from this phone, and this
    /// phone's own previews of it from iCloud.
    func forget(_ crew: Crew) {
        let mine = CrewStore.shared.myMemberID(in: crew.id)
        for angle in crew.angles {
            try? FileManager.default.removeItem(at: Self.fileURL(for: angle.id))
        }
        refreshAvailable()
        guard let keys = CrewCloud.Keys(crew) else { return }
        let ids = crew.angles.filter { $0.memberID == mine }
            .map { CKRecord.ID(recordName: "\(keys.tag)-\($0.id)") }
        guard !ids.isEmpty else { return }
        Task { _ = try? await database.modifyRecords(saving: [], deleting: ids) }
    }

    // MARK: - Making and posting

    /// Called by the stitcher with a finished camera video of a crew
    /// recording, before that video is handed off or deleted. Makes the
    /// preview synchronously enough that the source can go afterwards.
    nonisolated static func prepare(sessionID: UUID, camera: String, from videoURL: URL) async {
        guard let stamp = CrewStore.stamp(for: sessionID) else { return }
        let angleID = "\(sessionID.uuidString)-\(camera)"
        let destination = fileURL(for: angleID)
        await MainActor.run { _ = shared.inFlight.insert(angleID) }
        do {
            try await encode(videoURL, to: destination)
            await EventLogger.shared.log("crew_preview.made", detail: angleID)
        } catch {
            await EventLogger.shared.log("crew_preview.failed", detail: "\(angleID) \(error.localizedDescription)")
        }
        await MainActor.run {
            shared.inFlight.remove(angleID)
            shared.refreshAvailable()
            shared.post(angleID: angleID, crewID: stamp.crewID)
        }
    }

    private nonisolated static func encode(_ source: URL, to destination: URL) async throws {
        try? FileManager.default.removeItem(at: destination)
        let asset = AVURLAsset(url: source)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetMediumQuality) else {
            throw CocoaError(.featureUnsupported)
        }
        let duration = try await asset.load(.duration)
        let limit = CMTime(seconds: maxPreviewSeconds, preferredTimescale: 600)
        export.timeRange = CMTimeRange(start: .zero, duration: CMTimeMinimum(duration, limit))
        export.shouldOptimizeForNetworkUse = true
        try await export.export(to: destination, as: .mp4)
    }

    /// Posts a preview this phone made, once the angle is in the crew and the
    /// crew has a key. Retried by `sync` for anything not yet posted.
    private func post(angleID: String, crewID: UUID) {
        guard let crew = CrewStore.shared.crew(crewID), let keys = CrewCloud.Keys(crew),
              available.contains(angleID), !posted.contains(angleID) else { return }
        posted.insert(angleID)
        Task {
            do {
                let plain = try Data(contentsOf: Self.fileURL(for: angleID))
                let sealedURL = FileManager.default.temporaryDirectory
                    .appendingPathComponent("\(UUID().uuidString).bin")
                try keys.seal(plain).write(to: sealedURL)
                defer { try? FileManager.default.removeItem(at: sealedURL) }
                let record = CKRecord(recordType: Self.recordType,
                                      recordID: CKRecord.ID(recordName: "\(keys.tag)-\(angleID)"))
                record["tag"] = keys.tag as CKRecordValue
                record["angle"] = try keys.seal(Data(angleID.utf8)) as CKRecordValue
                record["video"] = CKAsset(fileURL: sealedURL)
                let (saved, _) = try await database.modifyRecords(saving: [record], deleting: [], savePolicy: .allKeys)
                if case .failure(let error) = saved[record.recordID] { throw error }
                markPosted(angleID)
            } catch {
                posted.remove(angleID)
                await EventLogger.shared.log("crew_preview.post_failed", detail: "\(angleID) \(error.localizedDescription)")
            }
        }
    }

    /// In memory this run; on disk across runs, so a preview isn't re-sent.
    private var posted: Set<String> = Set(UserDefaults.standard.stringArray(forKey: "crewPreviewsPosted") ?? [])

    private func markPosted(_ angleID: String) {
        var list = UserDefaults.standard.stringArray(forKey: "crewPreviewsPosted") ?? []
        list.append(angleID)
        UserDefaults.standard.set(Array(list.suffix(500)), forKey: "crewPreviewsPosted")
    }

    // MARK: - Fetching

    /// While the crew screen or player is open: posts anything of ours not
    /// yet sent and fetches the others' previews this phone doesn't have.
    func sync(_ crew: Crew) {
        let mine = CrewStore.shared.myMemberID(in: crew.id)
        for angle in crew.angles where angle.memberID == mine {
            post(angleID: angle.id, crewID: crew.id)
        }
        let missing = Set(crew.angles.filter { $0.memberID != mine }.map(\.id))
            .subtracting(available).subtracting(inFlight)
        guard !missing.isEmpty, let keys = CrewCloud.Keys(crew) else { return }
        inFlight.formUnion(missing)
        Task {
            defer { inFlight.subtract(missing); refreshAvailable() }
            do {
                let query = CKQuery(recordType: Self.recordType, predicate: NSPredicate(format: "tag == %@", keys.tag))
                let (results, _) = try await database.records(matching: query, desiredKeys: ["angle"], resultsLimit: 200)
                for case .success(let record) in results.map(\.1) {
                    guard let sealed = record["angle"] as? Data,
                          let angleID = (try? keys.open(sealed)).flatMap({ String(data: $0, encoding: .utf8) }),
                          missing.contains(angleID),
                          record.recordID.recordName == "\(keys.tag)-\(angleID)" else { continue }
                    await download(record.recordID, angleID: angleID, keys: keys)
                }
            } catch {
                await EventLogger.shared.log("crew_preview.fetch_failed", detail: error.localizedDescription)
            }
        }
    }

    private func download(_ id: CKRecord.ID, angleID: String, keys: CrewCloud.Keys) async {
        do {
            let record = try await database.record(for: id)
            guard let asset = record["video"] as? CKAsset, let file = asset.fileURL else { return }
            let plain = try keys.open(try Data(contentsOf: file))
            try plain.write(to: Self.fileURL(for: angleID), options: .atomic)
            refreshAvailable()
        } catch {
            await EventLogger.shared.log("crew_preview.download_failed", detail: "\(angleID) \(error.localizedDescription)")
        }
    }
}
