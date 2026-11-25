import Foundation
import CoreLocation

/// Append-only JSONL event log, independent of the video files. Records
/// session start/stop, chunk boundaries, interruptions, and upload outcomes
/// with UTC timestamps and location — so timing/location evidence survives
/// even if a video file's embedded metadata is ever stripped.
actor EventLogger {
    static let shared = EventLogger()

    struct Event: Codable {
        let timestampUTC: Date
        let event: String
        let detail: String?
        let latitude: Double?
        let longitude: Double?
        let timeZone: String
    }

    static var logFileURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("witness_events.jsonl")
    }

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    func log(_ event: String, detail: String? = nil, location: CLLocation? = nil) {
        let entry = Event(
            timestampUTC: Date(),
            event: event,
            detail: detail,
            latitude: location?.coordinate.latitude,
            longitude: location?.coordinate.longitude,
            timeZone: TimeZone.current.identifier
        )
        guard var line = try? encoder.encode(entry) else { return }
        line.append(0x0A) // newline

        let url = Self.logFileURL
        if let handle = try? FileHandle(forWritingTo: url) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: line)
        } else {
            try? line.write(to: url, options: .atomic)
        }
    }

    /// Copies the current log to a timestamped snapshot for upload, so the
    /// live file can keep being appended to while the snapshot uploads.
    func snapshotForUpload() -> URL? {
        let source = Self.logFileURL
        guard FileManager.default.fileExists(atPath: source.path) else { return nil }
        let stamp = ISO8601DateFormatter().string(from: Date())
            .replacingOccurrences(of: ":", with: "-")
        let dest = ChunkStore.chunksDirectory
            .appendingPathComponent("witness_events_\(stamp).jsonl")
        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.copyItem(at: source, to: dest)
            return dest
        } catch {
            return nil
        }
    }
}
