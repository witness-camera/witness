import Foundation

/// A cut of a crew's angles into one video, made on the phone: which camera
/// is on from which moment, and an optional In and Out.
///
/// Times are moments on the crew's clock rather than offsets into the
/// timeline, because the timeline's start moves when an angle that began
/// earlier arrives later; the cut stays where it was put. Kept per crew on
/// this phone only.
struct CrewEdit: Codable, Equatable {
    struct Cut: Codable, Equatable {
        let at: Date
        let angleID: String
    }

    var cuts: [Cut] = []
    var inPoint: Date?
    var outPoint: Date?

    var sortedCuts: [Cut] { cuts.sorted { $0.at < $1.at } }

    /// The camera chosen for a moment: the last cut at or before it, or the
    /// first cut's camera before any cut.
    func chosenAngle(at date: Date) -> String? {
        let sorted = sortedCuts
        return (sorted.last { $0.at <= date } ?? sorted.first)?.angleID
    }

    private static func key(_ crewID: UUID) -> String { "crew.edit.\(crewID.uuidString)" }

    static func load(_ crewID: UUID) -> CrewEdit {
        guard let data = UserDefaults.standard.data(forKey: key(crewID)),
              let edit = try? JSONDecoder().decode(CrewEdit.self, from: data) else { return CrewEdit() }
        return edit
    }

    func save(_ crewID: UUID) {
        if let data = try? JSONEncoder().encode(self) {
            UserDefaults.standard.set(data, forKey: Self.key(crewID))
        }
    }
}
