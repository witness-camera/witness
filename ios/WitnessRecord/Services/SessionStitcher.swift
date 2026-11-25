import Foundation
import AVFoundation
import UIKit

/// Assembles the per-session Photos backup. While recording, each finalized
/// chunk is *staged* as a hardlink (same inode — no extra disk use on APFS),
/// so the upload pipeline can still delete its own copy on confirmed receipt
/// without affecting the stitch. When the session ends, the staged chunks are
/// concatenated — losslessly, via passthrough export since every chunk shares
/// the same H.264/AAC format — into a single .mp4 that is saved to Photos.
final class SessionStitcher {
    static let shared = SessionStitcher()

    private let queue = DispatchQueue(label: "com.witnessrecord.stitcher")

    /// Fired once a session's per-camera videos have been merged, when
    /// `AppSettings.driveStitchEnabled` — the merged file(s) stay on disk
    /// (not deleted here) until `UploadManager` confirms them in Drive.
    var onSessionMerged: ((UUID, [(camera: String, url: URL)]) -> Void)?

    /// A merged session video's on-disk name — also how orphan recovery at
    /// launch tells a finished merge apart from raw per-chunk staging inputs.
    private static func mergedFileName(sessionID: UUID, camera: String) -> String {
        "session_\(sessionID.uuidString)_\(camera).\(AppSettings.videoCodec.fileExtension)"
    }

    private static var sessionsRoot: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("sessions", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// What one camera's video leaves behind once it has been dealt with.
    private struct Disposition {
        /// Set when the file is now the Drive-stitch pipeline's to upload, and
        /// must not be touched here.
        var driveVideo: (camera: String, url: URL)?
        /// Set when something in the staging directory is still the only copy
        /// of the recording — a video the photo library would not take, or the
        /// chunks behind a merge that failed — and the next launch has to find
        /// it there to try again.
        var mustKeepDirectory = false
    }

    private func stagingDir(for sessionID: UUID) -> URL {
        let dir = Self.sessionsRoot.appendingPathComponent(sessionID.uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Where the capture layer writes a session video live, and where it moves
    /// it once the writer has finalized it.
    ///
    /// It lands on exactly the name a merge would have produced, so everything
    /// downstream — Photos, the Drive-stitch upload, orphan recovery at
    /// launch — treats the two identically and neither has to know which one
    /// made the file.
    ///
    /// Writing it under `.partial` until it is finalized is what keeps a
    /// recording the app never got to finish from being mistaken for a whole
    /// one. An asset writer that is killed mid-file leaves no index, so the
    /// file is unplayable however complete it looks; recovery deletes stray
    /// `.partial` files and falls back to merging the chunks, which survived.
    func liveSessionVideoDestination(sessionID: UUID, camera: String) -> (partial: URL, final: URL) {
        let final = stagingDir(for: sessionID)
            .appendingPathComponent(Self.mergedFileName(sessionID: sessionID, camera: camera))
        return (final.appendingPathExtension("partial"), final)
    }

    /// Cleans up staging directories left behind by a previous app lifetime —
    /// e.g. the app was killed mid-encounter, or between a merge finishing and
    /// its Drive upload confirming.
    ///
    /// A directory containing an already-merged `session_*` file is not
    /// discarded outright: if Drive-stitch is enabled, that file is the one
    /// remaining copy still waiting to reach Drive, so it's handed back to
    /// `onSessionMerged` to resume the upload.
    ///
    /// A directory that still holds raw per-chunk inputs is a merge that never
    /// finished, which is ordinary for a long session: the export runs in an
    /// in-process task, and backgrounding the app suspends it. `merge` deletes
    /// its inputs on success, so their survival is a reliable signal that it
    /// did not get that far. Those are re-merged rather than deleted.
    ///
    /// Deleting them, which is what this used to do, was unrecoverable: the
    /// chunks had reached Drive individually, but the inputs needed to build
    /// the consolidated video were gone, so the session was stuck with only
    /// fragments and no way back.
    /// Whether any session is still sitting in staging, merged or not.
    ///
    /// Read rather than tracked, because the directory is the state: a merge
    /// that was interrupted leaves its inputs, and one that completed but
    /// could not be delivered leaves its output.
    var hasUnfinishedSessions: Bool {
        let dirs = (try? FileManager.default.contentsOfDirectory(
            at: Self.sessionsRoot, includingPropertiesForKeys: nil)) ?? []
        return dirs.contains { dir in
            let contents = (try? FileManager.default.contentsOfDirectory(
                at: dir, includingPropertiesForKeys: nil)) ?? []
            return contents.contains { $0.pathExtension == "mp4" || $0.pathExtension == "mov" }
        }
    }

    func cleanupOrphanedStaging() {
        queue.async { [weak self] in
            guard let self else { return }
            let root = Self.sessionsRoot
            let dirs = (try? FileManager.default.contentsOfDirectory(
                at: root, includingPropertiesForKeys: nil)) ?? []
            for dir in dirs {
                guard let sessionID = UUID(uuidString: dir.lastPathComponent) else {
                    try? FileManager.default.removeItem(at: dir)
                    continue
                }
                let contents = (try? FileManager.default.contentsOfDirectory(
                    at: dir, includingPropertiesForKeys: nil)) ?? []

                // Scratch output from an export that died partway. Never usable.
                for partial in contents where partial.pathExtension == "partial" {
                    try? FileManager.default.removeItem(at: partial)
                }

                let mergedPrefix = "session_\(sessionID.uuidString)_"
                let merged = contents.filter {
                    $0.lastPathComponent.hasPrefix(mergedPrefix) && $0.pathExtension != "partial"
                }
                let rawInputs = contents.filter {
                    !$0.lastPathComponent.hasPrefix("session_")
                        && ($0.pathExtension == "mp4" || $0.pathExtension == "mov")
                }

                if !merged.isEmpty, AppSettings.driveStitchEnabled,
                   !AppSettings.isLocalOnlyCapture {
                    let recovered = merged.map { url -> (camera: String, url: URL) in
                        let camera = url.deletingPathExtension().lastPathComponent
                            .split(separator: "_").last.map(String.init) ?? "back"
                        return (camera, url)
                    }
                    Task { await EventLogger.shared.log(
                        "stitch.recovered_pending_merge",
                        detail: "\(sessionID.uuidString) cameras=\(recovered.map(\.camera).joined(separator: ","))") }
                    self.onSessionMerged?(sessionID, recovered)
                } else if !merged.isEmpty {
                    // A finished merge with nothing uploading: either the
                    // library refused it last time, or uploads have been
                    // switched off since. Either way this file is the
                    // recording — a local-only session has no originals left —
                    // so it is offered to the library again rather than swept
                    // up by the branch below.
                    self.retryPhotosSave(merged, sessionID: sessionID, dir: dir)
                } else if !rawInputs.isEmpty {
                    Task { await EventLogger.shared.log(
                        "stitch.retrying_interrupted_merge",
                        detail: "\(sessionID.uuidString) inputs=\(rawInputs.count)") }
                    self.finishSession(sessionID)
                } else {
                    try? FileManager.default.removeItem(at: dir)
                }
            }
        }
    }

    /// Offers a merged session to the photo library again, and keeps it if the
    /// library still will not take it.
    ///
    /// The staging directory is only removed once every camera's video has
    /// actually been saved. A refused library means the file stays, and this
    /// runs again at the next launch — which is the difference between a user
    /// who fixes a permission getting their recording back, and a user who
    /// never knew it had been deleted.
    ///
    /// The exception is a session whose pieces went to the upload queue: the
    /// queue keeps them until the cloud confirms each one, so a refused merge
    /// is a redundant second copy, and keeping it for a retry that may never
    /// succeed only fills the phone with footage nobody can see.
    private func retryPhotosSave(_ merged: [URL], sessionID: UUID, dir: URL) {
        Task {
            let local = Self.wasLocalOnly(sessionID)
            var allSaved = true
            for url in merged {
                let identifier = await PhotoLibraryService.shared.saveVideo(fileURL: url)
                if let identifier {
                    await CaseLibrary.shared.rememberPhotoAsset(identifier, sessionID: sessionID)
                    if local, let hash = try? Hashing.sha256Hex(of: url) {
                        LocalSessionRecord.recordSavedVideo(
                            sessionID: sessionID, camera: Self.camera(of: url),
                            sha256: hash, photoAssetID: identifier)
                    }
                    try? FileManager.default.removeItem(at: url)
                } else if ChunkStore.shared.records(forSession: sessionID).isEmpty {
                    allSaved = false
                } else {
                    try? FileManager.default.removeItem(at: url)
                    await EventLogger.shared.log(
                        "stitch.dropped_redundant_merge", detail: "\(sessionID.uuidString): pieces are queued")
                }
            }
            if local { await LocalSessionRecord.finish(sessionID: sessionID) }
            await EventLogger.shared.log(
                allSaved ? "stitch.recovered_to_photos" : "stitch.still_unsaved",
                detail: sessionID.uuidString)
            if allSaved { try? FileManager.default.removeItem(at: dir) }
        }
    }

    /// Whether a session's pieces were dropped at capture rather than queued.
    /// Sessions from before this was noted have no note, and are treated as
    /// queued only when the queue actually holds their records.
    private static func wasLocalOnly(_ sessionID: UUID) -> Bool {
        !LocalSessionRecord.chunks(for: sessionID).isEmpty
    }

    private static func camera(of url: URL) -> String {
        url.deletingPathExtension().lastPathComponent.split(separator: "_").last.map(String.init) ?? "back"
    }

    /// Stage a finalized chunk for later stitching. Synchronous so that a chunk
    /// is always staged before the session-finalized signal that follows it.
    func stage(chunk: CaptureSessionController.FinalizedChunk) {
        // Name includes the camera so front and back at the same index don't
        // collide, and can be grouped for separate merges.
        let dest = stagingDir(for: chunk.sessionID)
            .appendingPathComponent(String(format: "%06d_%@.%@", chunk.index, chunk.camera.rawValue,
                                           chunk.fileURL.pathExtension))
        try? FileManager.default.removeItem(at: dest)
        do {
            // Hardlink: no data copied, survives deletion of the upload copy.
            try FileManager.default.linkItem(at: chunk.fileURL, to: dest)
        } catch {
            // Different volume or link unsupported — fall back to a real copy.
            try? FileManager.default.copyItem(at: chunk.fileURL, to: dest)
        }
    }

    /// Merge each camera's staged chunks into one .mp4 (so dual-camera sessions
    /// yield a rear and a front video). Depending on settings, the merged
    /// video is saved to Photos and/or handed off (via `onSessionMerged`) to
    /// the Drive-stitch pipeline, which owns deleting it once its own upload
    /// is confirmed. If neither consumer wants it, it — and the whole staging
    /// directory — is deleted here. Best-effort and decoupled from the uploader.
    ///
    /// `liveVideos` carries the session videos the capture layer already wrote
    /// live, one per camera. A camera that has one skips the merge entirely —
    /// its file is finished the moment recording stopped — and only a camera
    /// without one (heavy capture settings, or a writer that failed partway)
    /// pays for the export. Both kinds are handled identically from there on.
    /// `completion` fires once this session is fully resolved — merged (or
    /// not, if it arrived pre-merged from a live writer), saved to Photos
    /// where that was wanted, and either hashed off to Drive-stitch or
    /// cleaned up. Not on every intermediate step, and not on
    /// `onSessionFinalized` (which only means the chunks are closed) — this
    /// is the actual "nothing left to do for this recording" signal, which is
    /// what `BackgroundSaveNotifier` needs to know whether to still warn
    /// someone who backgrounded the app that reopening it would help.
    func finishSession(_ sessionID: UUID,
                       liveVideos: [(camera: String, url: URL)] = [],
                       completion: (@Sendable () -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self else { completion?(); return }
            let dir = self.stagingDir(for: sessionID)
            let contents = (try? FileManager.default.contentsOfDirectory(
                at: dir, includingPropertiesForKeys: nil)) ?? []
            // `session_` files are finished session videos, not inputs. One is
            // in this directory before this method even runs now that they can
            // be written live, and grouping it with the chunks would feed a
            // whole recording back into its own merge.
            let staged = contents.filter {
                !$0.lastPathComponent.hasPrefix("session_")
                    && ($0.pathExtension == "mp4" || $0.pathExtension == "mov")
            }
            var live = Dictionary(liveVideos.map { ($0.camera, $0.url) },
                                  uniquingKeysWith: { first, _ in first })

            guard !staged.isEmpty || !live.isEmpty else {
                try? FileManager.default.removeItem(at: dir)
                completion?()
                return
            }

            // Group by camera suffix ("000000_back" → "back").
            var groups = Dictionary(grouping: staged) { url -> String in
                url.deletingPathExtension().lastPathComponent
                    .split(separator: "_").last.map(String.init) ?? "back"
            }
            // A camera whose whole recording went to a live writer may have no
            // staged chunks of its own to be grouped under.
            for camera in live.keys where groups[camera] == nil { groups[camera] = [] }
            let wantsDriveUpload = AppSettings.driveStitchEnabled && !AppSettings.isLocalOnlyCapture
            // Forced when nothing is uploading, because then the library is
            // the only place the recording can go.
            //
            // A local-only session deletes each chunk's original as soon as it
            // is staged, so the hardlinks here are the footage. With uploads
            // off and the library declined, this method used to merge them,
            // delete the inputs, delete the merge, and delete the directory —
            // the recording ceased to exist, silently. It needed ProRes to
            // reach before; the cloud-upload switch puts it one tap away for
            // anyone.
            let localOnly = AppSettings.isLocalOnlyCapture
            let wantsPhotos = AppSettings.photosBackupEnabled || localOnly

            Task {
                let assertion = BackgroundAssertion()
                await assertion.begin("witness.session-merge")

                // Resolved ahead of the per-camera task group below so that
                // group can be handed a plain third "camera" entry — like
                // any other pre-resolved live-written video — and treat it
                // identically for saving, uploading, and cleanup. No
                // special-casing needed downstream, and no double merge:
                // once this sets live["back"]/live["front"], the task
                // group's own per-camera task sees them already resolved.
                if AppSettings.pipExportEnabled,
                   let backChunks = groups["back"], let frontChunks = groups["front"] {
                    do {
                        let backURL = try await self.resolvedVideo(
                            camera: "back", chunkURLs: backChunks, live: live,
                            sessionID: sessionID, in: dir)
                        let frontURL = try await self.resolvedVideo(
                            camera: "front", chunkURLs: frontChunks, live: live,
                            sessionID: sessionID, in: dir)
                        live["back"] = backURL
                        live["front"] = frontURL
                        let pipURL = try await self.buildPictureInPicture(
                            backURL: backURL, frontURL: frontURL, sessionID: sessionID, in: dir)
                        live["pip"] = pipURL
                        groups["pip"] = []
                    } catch {
                        // The two separate per-camera videos are entirely
                        // unaffected by this failing — this only ever adds a
                        // third file, so there is nothing to roll back.
                        await EventLogger.shared.log(
                            "stitch.pip_failed",
                            detail: "\(sessionID.uuidString): \(error.localizedDescription)")
                    }
                }

                // Same pre-resolution approach as picture-in-picture above,
                // and only needs the rear camera: that is the one video every
                // session produces, dual capture or not, and — same reasoning
                // as picture-in-picture using its audio — the one whose audio
                // track this can score for speech without a second, likely
                // near-duplicate track to reconcile.
                // The primary view: the rear camera, or the 1× lens when two
                // lenses are being recorded and neither is simply "the rear".
                let primaryKey = groups["back"] != nil ? "back" : "wide"
                if AppSettings.highlightExportEnabled, let backChunks = groups[primaryKey] {
                    do {
                        let backURL = try await self.resolvedVideo(
                            camera: primaryKey, chunkURLs: backChunks, live: live,
                            sessionID: sessionID, in: dir)
                        live[primaryKey] = backURL
                        let start = ChunkStore.shared.records(forSession: sessionID)
                            .map(\.startedAt).min()
                        let marks = start.map { s in
                            MomentMarkers.between(s, Date()).map { $0.timeIntervalSince(s) }
                        } ?? []
                        let highlightsURL = try await HighlightExtractor.build(
                            from: backURL, sessionID: sessionID, in: dir, marks: marks)
                        live["highlights"] = highlightsURL
                        groups["highlights"] = []
                    } catch is HighlightExtractor.NoActivity {
                        // Nothing in the recording ever rose above its own
                        // quiet stretches — no shorter cut exists to make,
                        // not a failure.
                    } catch {
                        await EventLogger.shared.log(
                            "stitch.highlights_failed",
                            detail: "\(sessionID.uuidString): \(error.localizedDescription)")
                    }
                }

                // Publish-safe copies with faces and plates blurred: the
                // primary video, and the selfie camera's too when it was
                // recording. Additive: the originals are untouched.
                if AppSettings.blurExportEnabled {
                    for (key, name) in [(primaryKey, "blurred"), ("front", "blurred_front")] {
                        guard let chunks = groups[key] else { continue }
                        do {
                            let sourceURL = try await self.resolvedVideo(
                                camera: key, chunkURLs: chunks, live: live,
                                sessionID: sessionID, in: dir)
                            live[key] = sourceURL
                            let blurredURL = try await BlurExporter.build(
                                from: sourceURL, sessionID: sessionID, in: dir, name: name)
                            if AppSettings.voiceDisguiseEnabled {
                                // Best effort: a copy with real voices is still a
                                // useful blurred copy, so a failure here is logged,
                                // not fatal.
                                do { try await VoiceDisguiser.apply(to: blurredURL) } catch {
                                    await EventLogger.shared.log(
                                        "stitch.voice_failed", detail: "\(sessionID.uuidString) \(key): \(error)")
                                }
                            }
                            live[name] = blurredURL
                            groups[name] = []
                        } catch {
                            await EventLogger.shared.log(
                                "stitch.blur_failed",
                                detail: "\(sessionID.uuidString) \(key): \(error.localizedDescription)")
                        }
                    }
                }

                // A thumbnail and an audio copy for Cases, taken before the
                // video is handed off and deleted below.
                if let chunks = groups[primaryKey],
                   let primaryURL = try? await self.resolvedVideo(
                       camera: primaryKey, chunkURLs: chunks, live: live,
                       sessionID: sessionID, in: dir) {
                    live[primaryKey] = primaryURL
                    await CaseLibrary.shared.attachRecordingMedia(sessionID: sessionID, videoURL: primaryURL)
                }

                // A crew recording leaves a small preview of each camera, so
                // the crew can watch every angle in sync on their phones.
                if CrewStore.stamp(for: sessionID) != nil {
                    let derived: Set<String> = ["pip", "highlights", "blurred", "blurred_front"]
                    for (camera, chunks) in groups where !derived.contains(camera) {
                        guard let url = try? await self.resolvedVideo(
                            camera: camera, chunkURLs: chunks, live: live,
                            sessionID: sessionID, in: dir) else { continue }
                        live[camera] = url
                        await CrewPreviews.prepare(sessionID: sessionID, camera: camera, from: url)
                    }
                }

                // Each child task returns its own (camera, url) pair rather
                // than mutating shared state, so results collect safely even
                // though the merges run concurrently.
                let outcomes: [Disposition] = await withTaskGroup(
                    of: Disposition.self
                ) { taskGroup -> [Disposition] in
                    for (camera, urls) in groups {
                        taskGroup.addTask {
                            let sorted = urls.sorted { $0.lastPathComponent < $1.lastPathComponent }
                            do {
                                let mergedURL: URL
                                if let liveURL = live[camera] {
                                    mergedURL = liveURL
                                } else {
                                    mergedURL = try await self.merge(
                                        sorted, sessionID: sessionID, camera: camera, in: dir)
                                }
                                var savedToPhotos = false
                                if wantsPhotos {
                                    let identifier = await PhotoLibraryService.shared.saveVideo(fileURL: mergedURL)
                                    savedToPhotos = identifier != nil
                                    if let identifier {
                                        await CaseLibrary.shared.rememberPhotoAsset(identifier, sessionID: sessionID)
                                        // The library copy is the only one, so
                                        // its hash is what the record attests to.
                                        if localOnly, let hash = try? Hashing.sha256Hex(of: mergedURL) {
                                            LocalSessionRecord.recordSavedVideo(
                                                sessionID: sessionID, camera: camera,
                                                sha256: hash, photoAssetID: identifier)
                                        }
                                    }
                                    await EventLogger.shared.log(
                                        savedToPhotos ? "photos.session_saved" : "photos.session_save_failed",
                                        detail: "\(sessionID.uuidString) \(camera) chunks=\(sorted.count)")
                                }
                                // The raw per-chunk staging inputs are spent
                                // once the session video exists, whether it was
                                // merged from them or written alongside them;
                                // only that output persists.
                                for input in sorted { try? FileManager.default.removeItem(at: input) }
                                if wantsDriveUpload {
                                    return Disposition(driveVideo: (camera, mergedURL))
                                }
                                // Deleted only once something has taken it.
                                // Nothing else holds this footage — a
                                // local-only session's originals are already
                                // gone — so if the library refused it, it stays
                                // here and the next launch tries again.
                                if savedToPhotos {
                                    try? FileManager.default.removeItem(at: mergedURL)
                                    return Disposition()
                                }
                                // Not the only copy when the pieces are queued:
                                // the queue keeps each one until the cloud
                                // confirms it. Keeping the merge as well, for
                                // a library that may never accept it, left
                                // hidden gigabytes on phones.
                                if !localOnly {
                                    try? FileManager.default.removeItem(at: mergedURL)
                                    await EventLogger.shared.log(
                                        "stitch.dropped_redundant_merge",
                                        detail: "\(sessionID.uuidString) \(camera): pieces are queued")
                                    return Disposition()
                                }
                                await EventLogger.shared.log(
                                    "stitch.kept_unsaved_merge",
                                    detail: "\(sessionID.uuidString) \(camera): nowhere to send it")
                                return Disposition(mustKeepDirectory: true)
                            } catch {
                                await EventLogger.shared.log(
                                    "stitch.merge_failed",
                                    detail: "\(sessionID.uuidString) \(camera): \(error.localizedDescription)")
                                // The inputs are still there — a failed merge
                                // deletes nothing — so this session is retried
                                // at the next launch rather than discarded.
                                return Disposition(mustKeepDirectory: true)
                            }
                        }
                    }
                    var collected: [Disposition] = []
                    for await result in taskGroup { collected.append(result) }
                    return collected
                }

                if localOnly { await LocalSessionRecord.finish(sessionID: sessionID) }

                let driveVideos = outcomes.compactMap(\.driveVideo)
                if !driveVideos.isEmpty {
                    // Directory (and its session videos) stay on disk — owned
                    // now by the Drive-stitch pipeline until upload confirms.
                    self.onSessionMerged?(sessionID, driveVideos)
                } else if outcomes.contains(where: \.mustKeepDirectory) {
                    // Something here is still the only copy of a recording:
                    // a video the photo library refused, or the chunks of a
                    // merge that failed. Deleting the directory anyway is how
                    // footage used to disappear silently at this point.
                    await EventLogger.shared.log(
                        "stitch.staging_retained", detail: sessionID.uuidString)
                } else {
                    try? FileManager.default.removeItem(at: dir)
                }

                completion?()
                await assertion.end()
            }
        }
    }

    private func merge(_ urls: [URL], sessionID: UUID, camera: String, in dir: URL) async throws -> URL {
        let composition = AVMutableComposition()
        let videoTrack = composition.addMutableTrack(
            withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)
        let audioTrack = composition.addMutableTrack(
            withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)

        var cursor = CMTime.zero
        for url in urls {
            let asset = AVURLAsset(url: url)
            let duration = try await asset.load(.duration)
            let range = CMTimeRange(start: .zero, duration: duration)

            if let srcVideo = try await asset.loadTracks(withMediaType: .video).first {
                try videoTrack?.insertTimeRange(range, of: srcVideo, at: cursor)
                // Carry the source orientation onto the first segment.
                if cursor == .zero {
                    videoTrack?.preferredTransform = try await srcVideo.load(.preferredTransform)
                }
            }
            if let srcAudio = try await asset.loadTracks(withMediaType: .audio).first {
                try? audioTrack?.insertTimeRange(range, of: srcAudio, at: cursor)
            }
            cursor = CMTimeAdd(cursor, duration)
        }

        // Exported under a scratch name and moved into place only on success,
        // so the presence of the final file *means* the merge completed. An
        // export interrupted partway (the app suspended mid-merge, which is
        // ordinary for a long session) otherwise leaves a truncated file that
        // recovery cannot tell apart from a good one, and uploads as if it
        // were the whole recording.
        let outputURL = dir.appendingPathComponent(
            Self.mergedFileName(sessionID: sessionID, camera: camera))
        let partialURL = outputURL.appendingPathExtension("partial")
        try? FileManager.default.removeItem(at: outputURL)
        try? FileManager.default.removeItem(at: partialURL)

        guard let export = AVAssetExportSession(
            asset: composition, presetName: AVAssetExportPresetPassthrough) else {
            throw NSError(domain: "WitnessRecord", code: 10,
                          userInfo: [NSLocalizedDescriptionKey: "Could not create export session"])
        }

        let outputType: AVFileType = AppSettings.videoCodec.isProRes ? .mov : .mp4
        if #available(iOS 18, *) {
            try await export.export(to: partialURL, as: outputType)
        } else {
            export.outputURL = partialURL
            export.outputFileType = outputType
            // Used serially within this function; safe to hand to the closure.
            nonisolated(unsafe) let session = export
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                session.exportAsynchronously {
                    if session.status == .completed {
                        continuation.resume()
                    } else {
                        continuation.resume(throwing: session.error ?? NSError(
                            domain: "WitnessRecord", code: 11,
                            userInfo: [NSLocalizedDescriptionKey: "Export failed"]))
                    }
                }
            }
        }

        try FileManager.default.moveItem(at: partialURL, to: outputURL)
        return outputURL
    }

    /// Either the already-resolved live-written video for `camera`, or a
    /// freshly merged one from its staged chunks — the same choice the
    /// per-camera task group below makes for every other camera. Used by
    /// the picture-in-picture step to get both cameras' finished videos
    /// *before* that task group runs, without duplicating the actual merge:
    /// once this sets `live[camera]`, the task group sees it already
    /// resolved and skips merging it a second time.
    private func resolvedVideo(camera: String, chunkURLs: [URL], live: [String: URL],
                               sessionID: UUID, in dir: URL) async throws -> URL {
        if let liveURL = live[camera] { return liveURL }
        let sorted = chunkURLs.sorted { $0.lastPathComponent < $1.lastPathComponent }
        return try await merge(sorted, sessionID: sessionID, camera: camera, in: dir)
    }

    /// Composites the two cameras' finished videos into one shareable file —
    /// rear full-frame, front as a picture-in-picture inset — in addition to,
    /// never instead of, the two separate videos each camera already
    /// produces on its own.
    ///
    /// Unlike `merge`, which reassembles same-format chunks losslessly via
    /// passthrough, this is a genuine re-encode: two video tracks have to be
    /// laid out and drawn into one frame, which `AVAssetExportSession` can
    /// only do by decoding and re-encoding. That is real, unavoidable cost —
    /// noticeably more time and battery than every other export step in this
    /// pipeline — which is why it is Pro-gated and opt-in rather than always
    /// on.
    private func buildPictureInPicture(backURL: URL, frontURL: URL,
                                       sessionID: UUID, in dir: URL) async throws -> URL {
        let backAsset = AVURLAsset(url: backURL)
        let frontAsset = AVURLAsset(url: frontURL)

        async let backDurationLoad = backAsset.load(.duration)
        async let frontDurationLoad = frontAsset.load(.duration)
        async let backVideoLoad = backAsset.loadTracks(withMediaType: .video).first
        async let frontVideoLoad = frontAsset.loadTracks(withMediaType: .video).first
        async let backAudioLoad = backAsset.loadTracks(withMediaType: .audio).first

        let (backDuration, frontDuration, backSrcVideo, frontSrcVideo, backSrcAudio) =
            try await (backDurationLoad, frontDurationLoad, backVideoLoad, frontVideoLoad, backAudioLoad)

        guard let backSrcVideo, let frontSrcVideo else {
            throw NSError(domain: "WitnessRecord", code: 12,
                          userInfo: [NSLocalizedDescriptionKey: "Missing a video track to composite"])
        }

        // Trimmed to the shorter of the two rather than the longer: an
        // instruction covering a stretch where one camera has no samples
        // left is undefined territory, and the two cameras already rotate
        // and stop together, so the difference is normally a few frames at
        // most, not something worth padding for.
        let duration = min(backDuration, frontDuration)
        let range = CMTimeRange(start: .zero, duration: duration)

        let composition = AVMutableComposition()
        guard let backTrack = composition.addMutableTrack(
                withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              let frontTrack = composition.addMutableTrack(
                withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)
        else {
            throw NSError(domain: "WitnessRecord", code: 13,
                          userInfo: [NSLocalizedDescriptionKey: "Could not create composition tracks"])
        }
        try backTrack.insertTimeRange(range, of: backSrcVideo, at: .zero)
        try frontTrack.insertTimeRange(range, of: frontSrcVideo, at: .zero)

        // Only the rear camera's audio: playing both microphones back at
        // once would just be two overlapping recordings of the same
        // moment, not a clearer one. Each camera's own separate video still
        // carries its own full audio, unaffected by this choice.
        if let backSrcAudio,
           let audioTrack = composition.addMutableTrack(
               withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) {
            try? audioTrack.insertTimeRange(range, of: backSrcAudio, at: .zero)
        }

        let backTransform = try await backSrcVideo.load(.preferredTransform)
        let frontTransform = try await frontSrcVideo.load(.preferredTransform)
        let backNaturalSize = try await backSrcVideo.load(.naturalSize)
        let frontNaturalSize = try await frontSrcVideo.load(.naturalSize)

        // `naturalSize` is pre-rotation; a 90°-rotated track's actual
        // on-screen shape only comes from applying its own transform, which
        // is exactly what every downstream reader (this composition
        // included) does before displaying it.
        func displaySize(natural: CGSize, transform: CGAffineTransform) -> CGSize {
            let applied = natural.applying(transform)
            return CGSize(width: abs(applied.width), height: abs(applied.height))
        }
        let backDisplaySize = displaySize(natural: backNaturalSize, transform: backTransform)
        let frontDisplaySize = displaySize(natural: frontNaturalSize, transform: frontTransform)

        let renderSize = backDisplaySize
        // Front inset at roughly a third of the frame's width, its own
        // aspect ratio preserved, tucked into the bottom-right corner with
        // a small margin — the same layout the live viewfinder's own PiP
        // already uses, so the exported file looks like what was seen
        // while filming.
        let insetWidth = renderSize.width * 0.32
        let insetHeight = insetWidth * (frontDisplaySize.height / frontDisplaySize.width)
        let margin = renderSize.width * 0.03
        let insetOrigin = CGPoint(x: renderSize.width - insetWidth - margin,
                                  y: margin)

        let backLayer = AVMutableVideoCompositionLayerInstruction(assetTrack: backTrack)
        backLayer.setTransform(
            Self.fitTransform(naturalSize: backNaturalSize, sourceTransform: backTransform,
                              into: CGRect(origin: .zero, size: renderSize)),
            at: .zero)

        let frontLayer = AVMutableVideoCompositionLayerInstruction(assetTrack: frontTrack)
        frontLayer.setTransform(
            Self.fitTransform(naturalSize: frontNaturalSize, sourceTransform: frontTransform,
                              into: CGRect(origin: insetOrigin, size: CGSize(width: insetWidth, height: insetHeight))),
            at: .zero)

        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = range
        // Front on top: it is drawn second, over the rear layer beneath it.
        instruction.layerInstructions = [frontLayer, backLayer]

        let videoComposition = AVMutableVideoComposition()
        videoComposition.instructions = [instruction]
        videoComposition.renderSize = renderSize
        videoComposition.frameDuration = CMTime(value: 1, timescale: 30)

        let outputURL = dir.appendingPathComponent(
            Self.mergedFileName(sessionID: sessionID, camera: "pip"))
        let partialURL = outputURL.appendingPathExtension("partial")
        try? FileManager.default.removeItem(at: outputURL)
        try? FileManager.default.removeItem(at: partialURL)

        // A real preset, not passthrough — passthrough only reassembles an
        // asset's own existing samples unchanged, and there is no such thing
        // as "unchanged" for two tracks being drawn into one frame. Always
        // .mp4/H.264-ish regardless of the recording's own codec: this file
        // exists purely to be shared, not archived, so the wide-compatibility
        // format is the right default even for a ProRes or HEVC recording.
        guard let export = AVAssetExportSession(
            asset: composition, presetName: AVAssetExportPresetHighestQuality) else {
            throw NSError(domain: "WitnessRecord", code: 14,
                          userInfo: [NSLocalizedDescriptionKey: "Could not create export session"])
        }
        export.videoComposition = videoComposition

        if #available(iOS 18, *) {
            try await export.export(to: partialURL, as: .mp4)
        } else {
            export.outputURL = partialURL
            export.outputFileType = .mp4
            nonisolated(unsafe) let session = export
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                session.exportAsynchronously {
                    if session.status == .completed {
                        continuation.resume()
                    } else {
                        continuation.resume(throwing: session.error ?? NSError(
                            domain: "WitnessRecord", code: 15,
                            userInfo: [NSLocalizedDescriptionKey: "Picture-in-picture export failed"]))
                    }
                }
            }
        }

        try FileManager.default.moveItem(at: partialURL, to: outputURL)
        return outputURL
    }

    /// The transform that places a source frame — after its own recording
    /// orientation is applied — scaled to fit and centered within `rect`,
    /// for use as one layer's instruction in a composited export.
    private static func fitTransform(naturalSize: CGSize, sourceTransform: CGAffineTransform,
                                     into rect: CGRect) -> CGAffineTransform {
        let displaySize = naturalSize.applying(sourceTransform)
        let width = abs(displaySize.width), height = abs(displaySize.height)
        guard width > 0, height > 0 else { return sourceTransform }
        let scale = min(rect.width / width, rect.height / height)
        let scaledWidth = width * scale, scaledHeight = height * scale
        let offsetX = rect.minX + (rect.width - scaledWidth) / 2
        let offsetY = rect.minY + (rect.height - scaledHeight) / 2
        return sourceTransform
            .concatenating(CGAffineTransform(scaleX: scale, y: scale))
            .concatenating(CGAffineTransform(translationX: offsetX, y: offsetY))
    }
}

/// Keeps the app alive briefly after it is backgrounded.
///
/// A merge is not resumable: suspend it partway and the whole export has to be
/// redone. iOS grants only a short window, so this does not rescue a long
/// session — those are re-merged at next launch by `cleanupOrphanedStaging` —
/// but it is usually enough for a short one to finish rather than being thrown
/// away and repeated.
private final class BackgroundAssertion {
    private var id: UIBackgroundTaskIdentifier = .invalid

    @MainActor func begin(_ name: String) {
        id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            self?.end()
        }
    }

    @MainActor func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}
