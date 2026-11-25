import Foundation
import CoreLocation

/// Builds the per-session Drive folder name from a user-configurable template
/// (see `AppSettings.folderNameTemplate`), substituting placeholders for the
/// recording's start time, location, and session ID.
enum SessionFolderNaming {
    /// Placeholders recognized in the template, and their example values —
    /// shown together in Settings as the legend.
    static let placeholders: [(token: String, example: String)] = [
        ("{date}", "2026-07-17"),
        ("{time}", "13-04-22"),
        ("{location}", "San Francisco, CA"),
        ("{session}", "a1b2c3d4"),
    ]

    static func folderName(sessionID: UUID, date: Date, latitude: Double?, longitude: Double?) async -> String {
        let dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd"
        dateFormatter.timeZone = .current
        let timeFormatter = DateFormatter()
        timeFormatter.dateFormat = "HH-mm-ss"
        timeFormatter.timeZone = .current

        let location = await resolveLocation(latitude: latitude, longitude: longitude)
        let template = AppSettings.folderNameTemplate

        let name = template
            .replacingOccurrences(of: "{date}", with: dateFormatter.string(from: date))
            .replacingOccurrences(of: "{time}", with: timeFormatter.string(from: date))
            .replacingOccurrences(of: "{location}", with: location)
            .replacingOccurrences(of: "{session}", with: String(sessionID.uuidString.prefix(8)))

        let trimmed = sanitize(name).trimmingCharacters(in: .whitespaces)
        // A template that resolves to nothing (empty, or edited down to just
        // punctuation) would produce an unusable or colliding folder name —
        // fall back to the default rather than let that happen silently.
        return trimmed.isEmpty ? await defaultName(date: date, location: location) : trimmed
    }

    private static func defaultName(date: Date, location: String) async -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH-mm-ss"
        formatter.timeZone = .current
        return sanitize("Witness \(formatter.string(from: date)), \(location)")
    }

    private static func resolveLocation(latitude: Double?, longitude: Double?) async -> String {
        guard let lat = latitude, let lon = longitude else { return "Unknown location" }
        let coordString = String(format: "%.4f, %.4f", lat, lon)
        let geocoder = CLGeocoder()
        if let placemarks = try? await geocoder.reverseGeocodeLocation(
            CLLocation(latitude: lat, longitude: lon)),
           let placemark = placemarks.first {
            let parts = [placemark.locality, placemark.administrativeArea].compactMap { $0 }
            return parts.isEmpty ? coordString : parts.joined(separator: ", ")
        }
        return coordString
    }

    /// Drive tolerates most characters, but slashes read as path separators in
    /// some clients — strip them so the folder name stays intact.
    private static func sanitize(_ name: String) -> String {
        name.replacingOccurrences(of: "/", with: "-")
    }
}
