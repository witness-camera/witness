import Foundation

/// Keeps a permanent local copy of each finished session's chain-of-custody
/// record, so an evidence package can still be produced long after the
/// footage itself has left the device.
///
/// This exists because nothing else survives: chunk records are receipts that
/// get cleared once a session is consolidated, and the manifest is deleted
/// locally as soon as Drive confirms it. Both are correct behaviours for the
/// upload pipeline and both would leave a user with no way to document, weeks
/// later, what was recorded and that it wasn't altered.
///
/// Only JSON and a small proof file are kept — never video — so the archive
/// stays negligible in size no matter how much was filmed.
enum SessionArchive {

    private static var root: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("archive", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func directory(for sessionID: UUID) -> URL {
        let dir = root.appendingPathComponent(sessionID.uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// A file's place in a session's archive, without creating the directory —
    /// for reads, so looking something up never leaves empty folders behind.
    static func path(_ name: String, for sessionID: UUID) -> URL {
        root.appendingPathComponent(sessionID.uuidString, isDirectory: true)
            .appendingPathComponent(name)
    }

    static func manifestURL(for sessionID: UUID) -> URL {
        directory(for: sessionID).appendingPathComponent("manifest.json")
    }

    static func timestampURL(for sessionID: UUID) -> URL {
        directory(for: sessionID).appendingPathComponent("timestamp.tsr")
    }

    /// Stores the exact bytes that were hashed and uploaded. Byte-identical is
    /// the whole point: a re-encoded copy would no longer match the timestamp
    /// proof, and the package would fail its own verification instructions.
    static func store(manifestData: Data, timestampToken: Data?, for sessionID: UUID) {
        try? manifestData.write(to: manifestURL(for: sessionID), options: .atomic)
        if let timestampToken {
            try? timestampToken.write(to: timestampURL(for: sessionID), options: .atomic)
        }
    }

    /// Archived sessions, most recent first.
    static func sessions() -> [SessionManifest] {
        let contents = (try? FileManager.default.contentsOfDirectory(
            at: root, includingPropertiesForKeys: nil)) ?? []
        return contents
            .compactMap { dir -> SessionManifest? in
                let url = dir.appendingPathComponent("manifest.json")
                guard let data = try? Data(contentsOf: url) else { return nil }
                return try? SessionManifest.decoder.decode(SessionManifest.self, from: data)
            }
            .sorted { $0.recordingStartedUTC > $1.recordingStartedUTC }
    }

    static func hasTimestamp(_ sessionID: UUID) -> Bool {
        FileManager.default.fileExists(atPath: timestampURL(for: sessionID).path)
    }

    static func delete(_ sessionID: UUID) {
        try? FileManager.default.removeItem(at: directory(for: sessionID))
    }
}
