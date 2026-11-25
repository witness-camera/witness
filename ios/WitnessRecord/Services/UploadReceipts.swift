import Foundation

/// What actually reached the user's storage, summarised per recording session.
///
/// Kept apart from `ChunkStore` because the chunk records do not survive long
/// enough to answer this. A confirmed chunk keeps its record as a receipt, but
/// only until the session's merged video lands — `finishDriveStitch` then
/// deletes the per-chunk records along with the small remote files they
/// describe. A recap built from those records would empty itself at the exact
/// moment the recording finished, which is when someone would look at it.
///
/// So this is written once per confirmation and outlives all of it: a few
/// counters per session, small enough to keep in `UserDefaults`.
struct SessionUploadReceipt: Codable, Equatable, Identifiable {
    let sessionID: UUID
    /// When the recording began — how the session is named in the UI.
    let startedAt: Date
    var pieces: Int = 0
    var previews: Int = 0
    var bytes: Int = 0
    var lastConfirmedAt: Date
    /// Set once the per-camera merged videos have landed and the chunks they
    /// replace have been deleted remotely, so the recap can say that what is
    /// up there now is one video per camera rather than the pieces it counted.
    var mergedVideos: Int = 0

    var id: UUID { sessionID }
}

/// Persistence for the above. Every entry point is called from
/// `UploadManager`'s work queue.
enum UploadReceipts {
    private static let key = "sessionUploadReceipts"
    /// The sheet shows the most recent one. A handful are kept so a recording
    /// that follows a short one does not erase it immediately; an unbounded
    /// list would grow for the life of the app.
    private static let keep = 5

    static func all() -> [SessionUploadReceipt] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let decoded = try? JSONDecoder().decode([SessionUploadReceipt].self, from: data)
        else { return [] }
        return decoded
    }

    /// The session with the most recent confirmed upload — not the newest
    /// session, which may not have landed anything yet and would replace a
    /// finished recap with an empty one.
    static var mostRecent: SessionUploadReceipt? {
        all().max { $0.lastConfirmedAt < $1.lastConfirmedAt }
    }

    static func recordConfirmed(sessionID: UUID, startedAt: Date,
                                kind: ChunkRecord.Kind, bytes: Int) {
        update(sessionID: sessionID, startedAt: startedAt) { receipt in
            switch kind {
            case .master: receipt.pieces += 1
            case .lowRes: receipt.previews += 1
            }
            // Stand-ins are counted separately but their bytes are not: they
            // are deleted remotely the moment their full-quality file lands,
            // so counting them in the total would report more delivered than
            // is actually there.
            if kind == .master { receipt.bytes += bytes }
            receipt.lastConfirmedAt = Date()
        }
    }

    static func recordMerged(sessionID: UUID, startedAt: Date, videos: Int) {
        update(sessionID: sessionID, startedAt: startedAt) { receipt in
            receipt.mergedVideos = videos
        }
    }

    private static func update(sessionID: UUID, startedAt: Date,
                               _ change: (inout SessionUploadReceipt) -> Void) {
        var receipts = all()
        var receipt = receipts.first { $0.sessionID == sessionID }
            ?? SessionUploadReceipt(sessionID: sessionID, startedAt: startedAt,
                                    lastConfirmedAt: Date())
        change(&receipt)
        receipts.removeAll { $0.sessionID == sessionID }
        receipts.append(receipt)
        receipts.sort { $0.lastConfirmedAt > $1.lastConfirmedAt }
        if let data = try? JSONEncoder().encode(Array(receipts.prefix(keep))) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }
}
