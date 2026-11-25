import Foundation

/// A folder of evidence about one matter — "Flat 4B deposit", "Accident on
/// 12 October" — so a dispute that runs for weeks keeps everything in one
/// place, with one report at the end.
///
/// A case only groups items; it never alters them. Each item keeps its own
/// hash and time-stamp proof, so moving it between cases or out of one changes
/// nothing about what it proves.
struct EvidenceCase: Codable, Identifiable, Equatable {
    let id: UUID
    var title: String
    let createdAt: Date
}

/// Anything documented in Witness: a recording, a verified photo, an audio
/// recording, or a file brought in from elsewhere (a screen recording, a
/// letter). Stored by `CaseLibrary`; the media lives beside it on disk.
///
/// Schema discipline, as for `ChunkRecord`: new fields must be optional or
/// defaulted so libraries written by an older version keep decoding.
struct CaseItem: Codable, Identifiable, Equatable {
    enum Kind: String, Codable {
        /// A Witness recording. The footage itself is in Photos and the cloud;
        /// the app keeps a thumbnail and a small audio copy for transcripts,
        /// and the session's own manifest is its proof.
        case video
        case photo
        case audio
        /// Imported rather than captured here. Its proof starts at the moment
        /// of import, and every place that shows it says so.
        case file
    }

    let id: UUID
    let kind: Kind
    /// Set for `.video`: the recording session this item stands for.
    var sessionID: UUID?
    var caseID: UUID?
    var title: String?
    let createdAt: Date
    var endedAt: Date?
    /// The media file inside the item's folder, for everything but `.video`.
    var fileName: String?
    var sha256: String?
    var latitude: Double?
    var longitude: Double?
    /// Whether an independent time-stamp proof was obtained for the record.
    var timestamped: Bool = false
    var uploaded: Bool = false
    /// For `.video`: the Photos assets the recording was saved as, one per
    /// camera, so it can be watched back in the app.
    var photoAssetIDs: [String]?

    /// The checklist this item's details follow (`DetailTemplate.rawValue`).
    var template: String?
    /// Answers to the checklist, keyed by the prompt's id.
    var details: [String: String] = [:]
    var notes: [ItemNote] = []
    var transcript: Transcript?

    var isImported: Bool { kind == .file }
}

/// A note added after the fact. Kept apart from the hashed record and dated
/// when written, so it reads as what it is: the person's later account, not
/// part of what was captured.
struct ItemNote: Codable, Identifiable, Equatable {
    let id: UUID
    let createdAt: Date
    var text: String
}

struct Transcript: Codable, Equatable {
    struct Segment: Codable, Equatable, Identifiable {
        var id: Double { start }
        /// Seconds from the start of the recording.
        let start: TimeInterval
        let text: String
    }

    let localeIdentifier: String
    let createdAt: Date
    /// Whether every word was recognised on the device. False only when the
    /// person chose Apple's servers for a language the device can't do alone.
    let onDevice: Bool
    let segments: [Segment]

    var fullText: String { segments.map(\.text).joined(separator: " ") }
}

/// The record that is hashed and time-stamped for a photo, an audio recording
/// or an imported file — the counterpart of a recording's session manifest,
/// stored beside the file and uploaded with it.
struct ItemRecord: Codable {
    let schemaVersion: Int
    let itemID: UUID
    let kind: String
    /// "captured in Witness" or "imported into Witness".
    let source: String
    let fileName: String
    let sha256: String
    let capturedUTC: Date
    var endedUTC: Date?
    let latitude: Double?
    let longitude: Double?
    let horizontalAccuracyMeters: Double?
    let deviceTimeZone: String
    let deviceModel: String
    let systemVersion: String
    let appVersion: String
    var timestampAuthority: String?
    var timestampFileName: String?
    var note: String?
}

/// The guided checklists offered after something is recorded. Choosing one is
/// optional, and every prompt can be left empty.
enum DetailTemplate: String, CaseIterable, Identifiable {
    case encounter, accident, property, workplace, other

    var id: String { rawValue }

    var name: String {
        switch self {
        case .encounter: return String(localized: "Police or security encounter")
        case .accident: return String(localized: "Accident")
        case .property: return String(localized: "Property or rental")
        case .workplace: return String(localized: "Workplace")
        case .other: return String(localized: "Something else")
        }
    }

    var symbol: String {
        switch self {
        case .encounter: return "shield.lefthalf.filled"
        case .accident: return "car.side"
        case .property: return "house"
        case .workplace: return "briefcase"
        case .other: return "square.and.pencil"
        }
    }

    struct Prompt: Identifiable { let id: String; let label: String }

    var prompts: [Prompt] {
        switch self {
        case .encounter: return [
            Prompt(id: "names", label: String(localized: "Officer names or badge numbers")),
            Prompt(id: "agency", label: String(localized: "Agency or department")),
            Prompt(id: "vehicles", label: String(localized: "Vehicle or unit numbers")),
            Prompt(id: "reason", label: String(localized: "Reason they gave")),
            Prompt(id: "witnesses", label: String(localized: "Witnesses (names and contacts)")),
            Prompt(id: "reference", label: String(localized: "Report or incident number")),
        ]
        case .accident: return [
            Prompt(id: "otherDriver", label: String(localized: "Other driver's name and contacts")),
            Prompt(id: "plate", label: String(localized: "Other vehicle's plate")),
            Prompt(id: "insurance", label: String(localized: "Insurance company and policy number")),
            Prompt(id: "damage", label: String(localized: "Damage description")),
            Prompt(id: "witnesses", label: String(localized: "Witnesses (names and contacts)")),
            Prompt(id: "reference", label: String(localized: "Police report number")),
        ]
        case .property: return [
            Prompt(id: "address", label: String(localized: "Address and unit")),
            Prompt(id: "areas", label: String(localized: "Rooms or areas covered")),
            Prompt(id: "damage", label: String(localized: "Existing damage noted")),
            Prompt(id: "meters", label: String(localized: "Meter readings")),
            Prompt(id: "present", label: String(localized: "Landlord, agent or contractor present")),
        ]
        case .workplace: return [
            Prompt(id: "people", label: String(localized: "People involved")),
            Prompt(id: "place", label: String(localized: "Location")),
            Prompt(id: "what", label: String(localized: "What happened")),
            Prompt(id: "witnesses", label: String(localized: "Witnesses (names and contacts)")),
            Prompt(id: "reported", label: String(localized: "Reported to (HR, manager)")),
        ]
        case .other: return [
            Prompt(id: "what", label: String(localized: "What this shows")),
            Prompt(id: "people", label: String(localized: "People involved")),
            Prompt(id: "reference", label: String(localized: "Reference number")),
        ]
        }
    }
}

/// For `.sheet(item:)` on a plain item id.
extension UUID: @retroactive Identifiable {
    public var id: UUID { self }
}
