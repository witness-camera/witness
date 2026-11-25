import Foundation

/// Someone who is automatically granted access to a recording's Drive folder
/// as soon as that recording starts uploading, so the footage reaches a second
/// person even if the phone is taken.
struct EmergencyContact: Codable, Identifiable, Equatable {
    let id: UUID
    var name: String
    /// Email address the session folder is shared with. Contacts without one
    /// are stored but never shared via Drive.
    var email: String?
    /// Phone number for the location-ping SMS. Optional and independent of
    /// `email` — a contact can have either, both, or (until they add one)
    /// neither, and the ping goes out over whichever they have on file.
    var phone: String?

    var hasValidEmail: Bool {
        guard let email else { return false }
        return email.contains("@") && !email.hasPrefix("@") && !email.hasSuffix("@")
    }

    /// Whether Drive should be told to share the session folder with this
    /// contact — the same email-validity check as any other use, named for
    /// this specific call site since that's the only place it's read today.
    var canReceiveFootage: Bool { hasValidEmail }

    var canReceiveSMS: Bool {
        guard let phone else { return false }
        return !phone.trimmingCharacters(in: .whitespaces).isEmpty
    }

    private static let storageKey = "emergencyContacts"

    static func load() -> [EmergencyContact] {
        guard let data = UserDefaults.standard.data(forKey: storageKey) else { return [] }
        // Lenient per-contact decoding: one malformed entry shouldn't erase
        // every other saved contact.
        let decoded = (try? JSONDecoder().decode([FailableDecodable<EmergencyContact>].self, from: data)) ?? []
        return decoded.compactMap(\.value)
    }

    static func save(_ contacts: [EmergencyContact]) {
        if let data = try? JSONEncoder().encode(contacts) {
            UserDefaults.standard.set(data, forKey: storageKey)
        }
    }
}
