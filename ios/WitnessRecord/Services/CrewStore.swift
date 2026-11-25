import Foundation

/// The crews this phone belongs to, and which one new recordings join.
///
/// Persisted in UserDefaults, which the capture and upload queues also read
/// through the `nonisolated` helpers below: a recording has to be stamped the
/// instant it starts, often from an Action Button cold start, without waiting
/// on the main actor.
@MainActor
final class CrewStore: ObservableObject {
    static let shared = CrewStore()

    @Published private(set) var crews: [Crew] = []
    @Published private(set) var activeCrewID: UUID?

    private enum Key {
        static let crews = "crew.crews"
        static let active = "crew.active"
        /// Per crew: this phone's member id.
        static let me = "crew.me"
        /// Per crew: [offset, uncertainty] against the organizer's clock.
        static let clock = "crew.clock"
        /// Per session: the encoded `CrewStamp` it was recorded under.
        static let stamps = "crew.sessionStamps"
        static let myName = "crew.myName"
        static let myEmail = "crew.myEmail"
        /// Per session: [provider id, folder id] once its folder exists.
        static let folders = "crew.sessionFolders"
    }

    private init() { load() }

    var activeCrew: Crew? { crews.first { $0.id == activeCrewID } }

    func crew(_ id: UUID) -> Crew? { crews.first { $0.id == id } }

    func myMemberID(in crewID: UUID) -> UUID? { Self.myMemberID(in: crewID) }

    var myName: String {
        get { UserDefaults.standard.string(forKey: Key.myName) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: Key.myName); objectWillChange.send() }
    }

    var myEmail: String {
        get { UserDefaults.standard.string(forKey: Key.myEmail) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: Key.myEmail); objectWillChange.send() }
    }

    // MARK: - Starting, joining, leaving

    /// Starts a crew with this phone as organizer, whose clock is the
    /// reference everyone else is measured against.
    @discardableResult
    func start(titled title: String) -> Crew {
        let me = CrewMember(id: UUID(), name: displayName, email: emailOrNil, updatedAt: Date())
        let evidenceCase = CaseLibrary.shared.newCase(titled: title)
        let crew = Crew(id: UUID(), title: title, createdAt: Date(), organizerID: me.id,
                        members: [me], caseID: evidenceCase.id, secret: CrewCloud.newSecret())
        setMe(me.id, in: crew.id)
        Self.setClock(offset: 0, uncertainty: 0, for: crew.id)
        crews.append(crew)
        activeCrewID = crew.id
        save()
        return crew
    }

    /// Takes in a crew from a link or a nearby phone: joins it if this phone
    /// isn't in it yet, otherwise merges what the other side knows.
    func receive(_ incoming: Crew) {
        if let i = crews.firstIndex(where: { $0.id == incoming.id }) {
            let before = Set(crews[i].members.compactMap(\.email))
            crews[i].merge(incoming)
            if !Set(crews[i].members.compactMap(\.email)).isSubset(of: before) {
                UploadManager.shared.reshareCrewSessions(crewID: incoming.id)
            }
        } else {
            var crew = incoming
            let me = CrewMember(id: UUID(), name: displayName, email: emailOrNil, updatedAt: Date())
            crew.members.append(me)
            crew.caseID = CaseLibrary.shared.newCase(titled: crew.title).id
            setMe(me.id, in: crew.id)
            crews.append(crew)
            activeCrewID = crew.id
        }
        save()
    }

    /// Stops new recordings joining the crew. The crew, its case and its
    /// shares stay, so the edit can still be exported.
    func leave() {
        activeCrewID = nil
        save()
    }

    func rejoin(_ crewID: UUID) {
        guard crew(crewID) != nil else { return }
        activeCrewID = crewID
        save()
    }

    func delete(_ crewID: UUID) {
        crews.removeAll { $0.id == crewID }
        if activeCrewID == crewID { activeCrewID = nil }
        save()
    }

    /// Updates this phone's own name and email in every crew it belongs to,
    /// so the next link or nearby exchange carries them.
    func updateMyDetails() {
        var changed = false
        for i in crews.indices {
            guard let me = Self.myMemberID(in: crews[i].id),
                  let m = crews[i].members.firstIndex(where: { $0.id == me }) else { continue }
            guard crews[i].members[m].name != displayName || crews[i].members[m].email != emailOrNil else { continue }
            crews[i].members[m].name = displayName
            crews[i].members[m].email = emailOrNil
            crews[i].members[m].updatedAt = Date()
            changed = true
        }
        if changed { save() }
    }

    /// Whether this phone's member still lacks the details the others need
    /// to see who joined and to share footage with them.
    func needsMyDetails(in crewID: UUID) -> Bool {
        guard let me = Self.myMemberID(in: crewID),
              let member = crew(crewID)?.members.first(where: { $0.id == me }) else { return false }
        return myName.trimmingCharacters(in: .whitespaces).isEmpty || !member.canReceiveFootage
    }

    // MARK: - Clock

    /// Keeps a measured offset only when it is tighter than the one held.
    func recordClock(offset: Double, uncertainty: Double, for crewID: UUID) {
        if let held = Self.clock(for: crewID), let u = held.uncertainty, u <= uncertainty { return }
        Self.setClock(offset: offset, uncertainty: uncertainty, for: crewID)
        objectWillChange.send()
    }

    func clock(for crewID: UUID) -> (offset: Double, uncertainty: Double?)? { Self.clock(for: crewID) }

    // MARK: - Recordings

    /// Called when a crew recording stops: files it in the crew's case and
    /// adds its angles, which travel to the other phones on the next exchange.
    func addRecording(sessionID: UUID, stamp: CrewStamp, cameras: [String],
                      startedAt: Date, endedAt: Date, folderName: String?) {
        guard let i = crews.firstIndex(where: { $0.id == stamp.crewID }) else { return }
        let folder = Self.folder(for: sessionID)
        for camera in cameras {
            let angle = CrewAngle(
                sessionID: sessionID, memberID: stamp.memberID, camera: camera,
                startedAt: startedAt.addingTimeInterval(stamp.clockOffsetSeconds),
                duration: endedAt.timeIntervalSince(startedAt),
                folderName: folderName,
                fileName: UploadManager.mergedDriveFileName(camera: camera),
                providerID: folder?.provider, folderID: folder?.id)
            if !crews[i].angles.contains(where: { $0.id == angle.id }) {
                crews[i].angles.append(angle)
            }
        }
        setRecording(false, crewID: stamp.crewID, memberID: stamp.memberID)
        save()
    }

    /// Tells the crew this phone is filming now, or has stopped.
    func setRecording(_ recording: Bool, crewID: UUID, memberID: UUID) {
        guard let i = crews.firstIndex(where: { $0.id == crewID }),
              let m = crews[i].members.firstIndex(where: { $0.id == memberID }) else { return }
        crews[i].members[m].recordingSince = recording ? Date() : nil
        crews[i].members[m].updatedAt = Date()
        save()
    }

    /// Takes in one member's state from the crew's iCloud sync.
    func receiveCloud(crewID: UUID, member: CrewMember, angles: [CrewAngle]) {
        guard var crew = crew(crewID) else { return }
        crew.members = [member]
        crew.angles = angles
        receive(crew)
    }

    /// This phone's member and its own recordings, which are all it posts.
    func myState(in crewID: UUID) -> (member: CrewMember, angles: [CrewAngle])? {
        guard let crew = crew(crewID), let me = Self.myMemberID(in: crewID),
              let member = crew.members.first(where: { $0.id == me }) else { return nil }
        return (member, crew.angles.filter { $0.memberID == me })
    }

    /// Records where a crew recording's footage went, once its folder exists.
    /// Called from the upload queue.
    nonisolated static func noteSessionFolder(_ sessionID: UUID, provider: StorageProviderID, folderID: String) {
        guard stamp(for: sessionID) != nil else { return }
        let defaults = UserDefaults.standard
        var map = defaults.dictionary(forKey: Key.folders) as? [String: [String]] ?? [:]
        guard map[sessionID.uuidString] != [provider.rawValue, folderID] else { return }
        map[sessionID.uuidString] = [provider.rawValue, folderID]
        defaults.set(map, forKey: Key.folders)
        Task { @MainActor in shared.attachFolder(sessionID, provider: provider.rawValue, folderID: folderID) }
    }

    nonisolated static func folder(for sessionID: UUID) -> (provider: String, id: String)? {
        let map = UserDefaults.standard.dictionary(forKey: Key.folders) as? [String: [String]]
        guard let values = map?[sessionID.uuidString], values.count == 2 else { return nil }
        return (values[0], values[1])
    }

    /// This phone's recordings in a crew, for sharing their folders again
    /// with members whose email arrived late.
    nonisolated static func sessions(in crewID: UUID) -> [UUID] {
        let stamps = UserDefaults.standard.dictionary(forKey: Key.stamps) as? [String: Data] ?? [:]
        return stamps.compactMap { key, data in
            guard let stamp = try? JSONDecoder().decode(CrewStamp.self, from: data),
                  stamp.crewID == crewID else { return nil }
            return UUID(uuidString: key)
        }
    }

    private func attachFolder(_ sessionID: UUID, provider: String, folderID: String) {
        var changed = false
        for i in crews.indices {
            for a in crews[i].angles.indices where crews[i].angles[a].sessionID == sessionID
                && crews[i].angles[a].folderID == nil {
                crews[i].angles[a].providerID = provider
                crews[i].angles[a].folderID = folderID
                changed = true
            }
        }
        if changed { save() }
    }

    // MARK: - Read from any queue

    /// Stamps a recording that is starting, if a crew is active. Called from
    /// the capture queue the moment the session id is minted.
    nonisolated static func stampSession(_ sessionID: UUID) {
        let defaults = UserDefaults.standard
        guard let active = defaults.string(forKey: Key.active).flatMap(UUID.init),
              let crew = storedCrews().first(where: { $0.id == active }),
              let me = myMemberID(in: active),
              let member = crew.members.first(where: { $0.id == me }) else { return }
        let clock = clock(for: active)
        let stamp = CrewStamp(crewID: crew.id, crewTitle: crew.title,
                              memberID: me, memberName: member.name,
                              clockOffsetSeconds: clock?.offset ?? 0,
                              clockUncertaintySeconds: clock?.uncertainty)
        guard let data = try? JSONEncoder().encode(stamp) else { return }
        var stamps = defaults.dictionary(forKey: Key.stamps) as? [String: Data] ?? [:]
        stamps[sessionID.uuidString] = data
        defaults.set(stamps, forKey: Key.stamps)
        Task { @MainActor in shared.setRecording(true, crewID: crew.id, memberID: me) }
    }

    nonisolated static func stamp(for sessionID: UUID) -> CrewStamp? {
        let stamps = UserDefaults.standard.dictionary(forKey: Key.stamps) as? [String: Data]
        return stamps?[sessionID.uuidString].flatMap { try? JSONDecoder().decode(CrewStamp.self, from: $0) }
    }

    /// The other members' emails for a crew recording's folder, alongside the
    /// emergency contacts it is already shared with.
    nonisolated static func shareEmails(for sessionID: UUID) -> [String] {
        guard let stamp = stamp(for: sessionID),
              let crew = storedCrews().first(where: { $0.id == stamp.crewID }) else { return [] }
        return crew.members
            .filter { $0.id != stamp.memberID && $0.canReceiveFootage }
            .compactMap(\.email)
    }

    nonisolated static func myMemberID(in crewID: UUID) -> UUID? {
        let map = UserDefaults.standard.dictionary(forKey: Key.me) as? [String: String]
        return map?[crewID.uuidString].flatMap(UUID.init)
    }

    nonisolated static func clock(for crewID: UUID) -> (offset: Double, uncertainty: Double?)? {
        let map = UserDefaults.standard.dictionary(forKey: Key.clock) as? [String: [Double]]
        guard let values = map?[crewID.uuidString], let offset = values.first else { return nil }
        return (offset, values.count > 1 ? values[1] : nil)
    }

    nonisolated private static func setClock(offset: Double, uncertainty: Double?, for crewID: UUID) {
        var map = UserDefaults.standard.dictionary(forKey: Key.clock) as? [String: [Double]] ?? [:]
        map[crewID.uuidString] = uncertainty.map { [offset, $0] } ?? [offset]
        UserDefaults.standard.set(map, forKey: Key.clock)
    }

    nonisolated private static func storedCrews() -> [Crew] {
        guard let data = UserDefaults.standard.data(forKey: Key.crews) else { return [] }
        let decoded = (try? JSONDecoder().decode([FailableDecodable<Crew>].self, from: data)) ?? []
        return decoded.compactMap(\.value)
    }

    // MARK: - Persistence

    private var displayName: String {
        let name = myName.trimmingCharacters(in: .whitespaces)
        return name.isEmpty ? String(localized: "Crew member") : name
    }

    private var emailOrNil: String? {
        let email = myEmail.trimmingCharacters(in: .whitespaces)
        return email.isEmpty ? nil : email
    }

    private func setMe(_ memberID: UUID, in crewID: UUID) {
        var map = UserDefaults.standard.dictionary(forKey: Key.me) as? [String: String] ?? [:]
        map[crewID.uuidString] = memberID.uuidString
        UserDefaults.standard.set(map, forKey: Key.me)
    }

    private func load() {
        crews = Self.storedCrews()
        activeCrewID = UserDefaults.standard.string(forKey: Key.active).flatMap(UUID.init)
        // A crew this phone organized before sync existed gets a key now; the
        // others receive it the next time they scan, open a link or meet.
        var changed = false
        for i in crews.indices where crews[i].secret == nil && Self.myMemberID(in: crews[i].id) == crews[i].organizerID {
            crews[i].secret = CrewCloud.newSecret()
            changed = true
        }
        if changed { save() }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(crews) {
            UserDefaults.standard.set(data, forKey: Key.crews)
        }
        UserDefaults.standard.set(activeCrewID?.uuidString, forKey: Key.active)
    }
}
