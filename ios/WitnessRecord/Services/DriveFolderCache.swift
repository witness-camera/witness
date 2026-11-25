import Foundation

/// Resolves a Drive folder ID once per key, even when many chunk uploads race
/// to use it. Without this, concurrent uploads from the same recording session
/// each create their own folder, scattering one session across several.
///
/// Results are persisted (backend depends on the instance), so a folder is
/// reused across relaunches — and can be `evict`ed if Drive reports it no
/// longer exists (e.g. the user deleted it), so the next call re-resolves a
/// fresh one instead of retrying the same dead ID forever.
actor DriveFolderCache {
    /// Session subfolders: many keys (one per session), fine to lose on
    /// reinstall since a session belongs to one install's lifetime anyway.
    static let shared = DriveFolderCache(persistence: .userDefaults(storeKey: "sessionFolderIDs"))
    /// The single top-level "Witness" folder: Keychain-backed so it survives
    /// a reinstall without spawning a second root folder.
    static let sharedRoot = DriveFolderCache(persistence: .keychain(.driveFolderID))

    enum Persistence {
        case userDefaults(storeKey: String)
        case keychain(KeychainService.Key)
    }

    private let persistence: Persistence
    /// In-progress resolutions, so simultaneous callers await one shared task.
    private var inFlight: [String: Task<String, Error>] = [:]

    init(persistence: Persistence) {
        self.persistence = persistence
    }

    /// Returns the cached folder ID for `key`, otherwise runs `create` exactly
    /// once and caches its result.
    func folderID(for key: String, create: @escaping () async throws -> String) async throws -> String {
        if let cached = readCached(key: key) {
            return cached
        }
        if let running = inFlight[key] {
            return try await running.value
        }

        let task = Task { try await create() }
        inFlight[key] = task
        defer { inFlight[key] = nil }

        let id = try await task.value
        writeCached(key: key, id: id)
        return id
    }

    /// Drops a cached entry, e.g. after Drive reports the folder no longer
    /// exists, so the next `folderID(for:create:)` call resolves a fresh one.
    func evict(key: String) {
        switch persistence {
        case .userDefaults(let storeKey):
            var map = Self.persistedMap(storeKey)
            map.removeValue(forKey: key)
            UserDefaults.standard.set(map, forKey: storeKey)
        case .keychain(let kcKey):
            KeychainService.shared.delete(kcKey)
        }
    }

    private func readCached(key: String) -> String? {
        switch persistence {
        case .userDefaults(let storeKey):
            return Self.persistedMap(storeKey)[key]
        case .keychain(let kcKey):
            return KeychainService.shared.string(for: kcKey)
        }
    }

    private func writeCached(key: String, id: String) {
        switch persistence {
        case .userDefaults(let storeKey):
            var map = Self.persistedMap(storeKey)
            map[key] = id
            UserDefaults.standard.set(map, forKey: storeKey)
        case .keychain(let kcKey):
            KeychainService.shared.set(id, for: kcKey)
        }
    }

    private static func persistedMap(_ storeKey: String) -> [String: String] {
        UserDefaults.standard.dictionary(forKey: storeKey) as? [String: String] ?? [:]
    }
}
