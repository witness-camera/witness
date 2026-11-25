import CloudKit
import Combine
import CryptoKit
import Foundation

/// Keeps a crew in step over the internet, so members see who joined, who is
/// filming and what was recorded from anywhere, not only when they meet.
///
/// It uses the app's public iCloud database, which is stored on the app's
/// allowance rather than the member's: any phone signed in to an Apple
/// Account with iCloud on can take part, on the free tier too. Each member
/// writes one record of their own — their name and email, whether they are
/// filming, their recordings — sealed with AES-GCM under the crew's key. That
/// key exists only in the crew's code, link and nearby exchange, so Apple
/// stores only ciphertext and Witness has no server to see anything. Records
/// are found by a tag derived from the key, not by the crew's id, so even
/// the fact that two records belong to one crew is hidden from anyone
/// without it.
///
/// Setup outside the code, once: the container below enabled for the app,
/// and in CloudKit Console the `tag` field of `CrewEvent` marked Queryable,
/// then the schema deployed to Production before release.
@MainActor
final class CrewCloud: ObservableObject {
    static let shared = CrewCloud()
    static let containerID = "iCloud.com.witnessrecord.app"
    private static let recordType = "CrewEvent"
    private static let pollSeconds: UInt64 = 20

    enum Status: Equatable {
        case idle
        case synced(Date)
        case noAccount
        case notSetUp
        case failed(String)
    }

    @Published private(set) var status: Status = .idle

    /// The crew whose screen is open, synced as well as the active one.
    var focusedCrewID: UUID?

    private lazy var container = CKContainer(identifier: Self.containerID)
    private lazy var database = container.publicCloudDatabase
    /// What each crew last posted, so an unchanged state isn't re-sent.
    private var lastPosted: [UUID: Data] = [:]
    private var changes: AnyCancellable?
    private var loop: Task<Void, Never>?

    private init() {}

    static func newSecret() -> String {
        SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }.base64EncodedString()
    }

    /// Called once at launch. Posts as soon as this phone's state changes, and
    /// reads the crew's state on a short interval while the app is open —
    /// which includes recording, when the screen is black but the app is in
    /// front, so members see each other filming.
    func start() {
        guard changes == nil else { return }
        changes = CrewStore.shared.$crews
            .debounce(for: .seconds(1), scheduler: RunLoop.main)
            .sink { [weak self] _ in Task { await self?.postAll() } }
        // Reading is what costs requests, and the public database's request
        // allowance is shared by every phone running Witness, so it reads
        // only when someone is looking or filming: every tick with a crew
        // screen open, every third tick while recording into a crew, never
        // otherwise. Posting happens on change, which is rare.
        loop = Task { [weak self] in
            var tick = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.pollSeconds * 1_000_000_000)
                tick += 1
                guard let self else { return }
                if self.focusedCrewID != nil || (self.recordingIntoCrew && tick % 3 == 0) {
                    await self.syncAll()
                }
            }
        }
    }

    /// Once, on opening the app or a crew screen.
    func syncNow() {
        Task { await syncAll() }
    }

    // MARK: - Sync

    private var recordingIntoCrew: Bool {
        guard let id = CrewStore.shared.activeCrewID,
              let me = CrewStore.shared.myMemberID(in: id) else { return false }
        return CrewStore.shared.crew(id)?.members.first { $0.id == me }?.isRecordingNow ?? false
    }

    private var syncedCrews: [Crew] {
        let ids = Set([CrewStore.shared.activeCrewID, focusedCrewID].compactMap { $0 })
        return CrewStore.shared.crews.filter { ids.contains($0.id) && $0.secret != nil }
    }

    private func syncAll() async {
        guard !syncedCrews.isEmpty, await accountAvailable() else { return }
        await postAll()
        for crew in syncedCrews { await fetch(crew) }
    }

    /// Posts for every crew with a key, not only the ones being read: a
    /// member who joined and closed the screen still needs to be seen.
    private func postAll() async {
        let recent = Date().addingTimeInterval(-30 * 86400)
        for crew in CrewStore.shared.crews where crew.secret != nil
            && (crew.createdAt > recent || crew.id == CrewStore.shared.activeCrewID) {
            await post(crew)
        }
    }

    private func post(_ crew: Crew) async {
        guard let keys = Keys(crew), let state = CrewStore.shared.myState(in: crew.id),
              let plain = try? Self.encoder.encode(Payload(member: state.member, angles: state.angles)),
              plain != lastPosted[crew.id] else { return }
        guard await accountAvailable() else { return }
        do {
            let record = CKRecord(recordType: Self.recordType,
                                  recordID: CKRecord.ID(recordName: "\(keys.tag)-\(state.member.id.uuidString)"))
            record["tag"] = keys.tag as CKRecordValue
            record["payload"] = try keys.seal(plain) as CKRecordValue
            let (saved, _) = try await database.modifyRecords(saving: [record], deleting: [], savePolicy: .allKeys)
            if case .failure(let error) = saved[record.recordID] { throw error }
            lastPosted[crew.id] = plain
            status = .synced(Date())
        } catch {
            report(error)
        }
    }

    private func fetch(_ crew: Crew) async {
        guard let keys = Keys(crew) else { return }
        do {
            let query = CKQuery(recordType: Self.recordType, predicate: NSPredicate(format: "tag == %@", keys.tag))
            let (results, _) = try await database.records(matching: query, resultsLimit: 200)
            for case .success(let record) in results.map(\.1) {
                guard let sealed = record["payload"] as? Data,
                      let plain = try? keys.open(sealed),
                      let payload = try? Self.decoder.decode(Payload.self, from: plain),
                      record.recordID.recordName == "\(keys.tag)-\(payload.member.id.uuidString)" else { continue }
                // A record can only speak for the member it is named after.
                let angles = payload.angles.filter { $0.memberID == payload.member.id }
                CrewStore.shared.receiveCloud(crewID: crew.id, member: payload.member, angles: angles)
            }
            status = .synced(Date())
        } catch {
            report(error)
        }
    }

    private func accountAvailable() async -> Bool {
        let available = (try? await container.accountStatus()) == .available
        if !available { status = .noAccount }
        return available
    }

    private func report(_ error: Error) {
        let ck = error as? CKError
        switch ck?.code {
        case .notAuthenticated:
            status = .noAccount
        case .unknownItem, .invalidArguments, .serverRejectedRequest, .badContainer, .missingEntitlement:
            // A record type or index that doesn't exist yet: the one-time
            // CloudKit setup hasn't been done.
            status = .notSetUp
        default:
            status = .failed(error.localizedDescription)
        }
        Task { await EventLogger.shared.log("crew_cloud.failed", detail: "\(ck?.code.rawValue ?? -1) \(error.localizedDescription)") }
    }

    // MARK: - Payload and keys

    private struct Payload: Codable {
        let member: CrewMember
        let angles: [CrewAngle]
    }

    struct Keys {
        let key: SymmetricKey
        let tag: String

        init?(_ crew: Crew) {
            guard let secret = crew.secret, let data = Data(base64Encoded: secret), data.count == 32 else { return nil }
            key = SymmetricKey(data: data)
            let digest = SHA256.hash(data: Data("witness-crew-tag".utf8) + data)
            tag = digest.prefix(16).map { String(format: "%02x", $0) }.joined()
        }

        func seal(_ plain: Data) throws -> Data {
            guard let combined = try AES.GCM.seal(plain, using: key).combined else {
                throw CocoaError(.coderInvalidValue)
            }
            return combined
        }

        func open(_ sealed: Data) throws -> Data {
            try AES.GCM.open(AES.GCM.SealedBox(combined: sealed), using: key)
        }
    }

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .secondsSince1970
        return e
    }()

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .secondsSince1970
        return d
    }()
}
