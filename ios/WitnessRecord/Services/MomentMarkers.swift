import Foundation

/// Moments the person marked while recording — a flag tap or a volume-up
/// press. Stored as plain wall-clock times, so a session picks up its own by
/// its start and end without the capture layer having to hand over an ID.
///
/// They go into the session manifest, which is hashed and independently
/// time-stamped, so a mark is part of the evidence record rather than a note
/// that could be added afterwards. The highlight cut always keeps the seconds
/// around each one.
enum MomentMarkers {
    private static let key = "momentMarkers"
    /// Kept long enough for any session to be finalized; older ones are
    /// already in their manifest.
    private static let keepFor: TimeInterval = 30 * 24 * 3600

    static func add(_ date: Date = Date()) {
        let defaults = UserDefaults.standard
        var all = (defaults.array(forKey: key) as? [Double]) ?? []
        let cutoff = Date().addingTimeInterval(-keepFor).timeIntervalSince1970
        all = all.filter { $0 > cutoff }
        all.append(date.timeIntervalSince1970)
        defaults.set(all, forKey: key)
    }

    static func between(_ start: Date, _ end: Date) -> [Date] {
        let all = (UserDefaults.standard.array(forKey: key) as? [Double]) ?? []
        return all.map(Date.init(timeIntervalSince1970:))
            .filter { $0 >= start && $0 <= end.addingTimeInterval(1) }
            .sorted()
    }
}
