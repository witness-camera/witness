import Foundation

/// Several phones recording one event together — a protest, a raid — so it
/// is covered from more than one angle and can be cut as a single multicam
/// edit afterwards.
///
/// Nothing about a crew passes through a Witness server. Every phone keeps
/// uploading to its own cloud, exactly as when recording alone; what the
/// crew shares is this small index — who is in it, a common clock, and which
/// recordings belong to it — passed phone to phone in person or by link.
/// Each member's session folders are shared with the others' emails, the
/// same way an emergency contact's are, so the footage reaches everyone.
///
/// Schema discipline, as for `CaseItem`: new fields must be optional or
/// defaulted, because crews written by an older version travel in links.
struct Crew: Codable, Identifiable, Equatable {
    let id: UUID
    var title: String
    let createdAt: Date
    /// The member whose clock is the crew's reference: whoever started it.
    let organizerID: UUID
    var members: [CrewMember]
    var angles: [CrewAngle] = []
    /// The case on this phone that the crew's recordings are filed in.
    var caseID: UUID?
    /// The key the crew's iCloud sync is encrypted with, base64. It travels
    /// only inside the crew's code, link and nearby exchange, so Apple, which
    /// stores the sync, and Witness, which has no server, can't read it.
    /// Nil for crews started before 1.1.12, which sync nearby only.
    var secret: String?

    var organizer: CrewMember? { members.first { $0.id == organizerID } }

    /// Takes in what another phone knows about the same crew. Additive only:
    /// a member or recording one side has never heard of is added, never
    /// removed, so passing the crew around in any order converges.
    mutating func merge(_ other: Crew) {
        guard other.id == id else { return }
        for member in other.members {
            if let i = members.firstIndex(where: { $0.id == member.id }) {
                // Only a member edits their own details, so the later edit is
                // the current one. Records from before edits were dated only
                // fill in what's missing.
                let mine = members[i].updatedAt ?? .distantPast
                let theirs = member.updatedAt ?? .distantPast
                if theirs > mine {
                    members[i] = member
                } else if theirs == mine, members[i].email == nil {
                    members[i].email = member.email
                }
            } else {
                members.append(member)
            }
        }
        for angle in other.angles {
            if let i = angles.firstIndex(where: { $0.id == angle.id }) {
                // Where the footage went is learned after the recording stops.
                if angles[i].folderID == nil, angle.folderID != nil {
                    angles[i].folderID = angle.folderID
                    angles[i].providerID = angle.providerID
                }
            } else {
                angles.append(angle)
            }
        }
        if secret == nil { secret = other.secret }
    }
}

struct CrewMember: Codable, Identifiable, Equatable {
    let id: UUID
    var name: String
    /// Session folders are shared with this address as footage arrives.
    var email: String?
    /// When the member last changed their name or email, so a merge keeps the
    /// newest. Optional: crews from 1.1.11 travel without it.
    var updatedAt: Date? = nil
    /// Set while the member is recording into the crew, so the others see
    /// who is filming right now.
    var recordingSince: Date? = nil

    /// Recording, by their last word. A phone that died mid-recording never
    /// says it stopped, so after a few hours it no longer counts.
    var isRecordingNow: Bool {
        guard let recordingSince else { return false }
        return Date().timeIntervalSince(recordingSince) < 4 * 3600
    }

    var canReceiveFootage: Bool {
        guard let email else { return false }
        return email.contains("@") && !email.hasPrefix("@") && !email.hasSuffix("@")
    }
}

/// One camera of one member's recording, placed on the crew's clock.
struct CrewAngle: Codable, Identifiable, Equatable {
    /// Session and camera together: a dual-camera recording is two angles.
    var id: String { "\(sessionID.uuidString)-\(camera)" }
    let sessionID: UUID
    let memberID: UUID
    let camera: String
    /// On the crew's clock: the device's own start plus its measured offset.
    let startedAt: Date
    let duration: TimeInterval
    /// Where the video sits in the member's cloud, relative to their Witness
    /// folder, so an editor can relink every angle in one pass.
    var folderName: String?
    let fileName: String
    /// The member's backend and the folder's id there (a path on Dropbox),
    /// once the first piece has uploaded, so the others can open it.
    var providerID: String? = nil
    var folderID: String? = nil

    /// Where to open the footage. Google Drive opens the folder itself for
    /// anyone it was shared with. Dropbox only offers its owner a stable
    /// address; everyone else finds it among the folders shared with them.
    func folderURL(isMine: Bool) -> URL? {
        guard let folderID, let provider = providerID.flatMap(StorageProviderID.init(rawValue:)) else { return nil }
        switch provider {
        case .googleDrive:
            return URL(string: "https://drive.google.com/drive/folders/\(folderID)")
        case .dropbox:
            if isMine, let path = folderID.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) {
                return URL(string: "https://www.dropbox.com/home\(path)")
            }
            return URL(string: "https://www.dropbox.com/share")
        case .webDAV, .proton:
            return nil
        }
    }
}

/// Written into a crew recording's manifest, so the footage itself records
/// which crew it was part of and how its clock relates to the others'.
struct CrewStamp: Codable, Equatable {
    let crewID: UUID
    let crewTitle: String
    let memberID: UUID
    let memberName: String
    /// Seconds added to this phone's clock to reach the crew's reference.
    let clockOffsetSeconds: Double
    /// How far that offset could be off, in seconds; nil when the clock was
    /// never compared with another phone's and the system time was trusted.
    let clockUncertaintySeconds: Double?
}
