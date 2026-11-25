package com.witnessrecord.app.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.system.ErrnoException
import android.system.Os
import com.witnessrecord.app.WitnessApplication
import com.witnessrecord.app.capture.CaptureSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer

/**
 * Assembles the per-session gallery backup. While recording, each finalized
 * chunk is *staged* as a hard link (same inode - no extra disk used), so the
 * upload pipeline can still delete its own copy on confirmed receipt without
 * affecting the stitch. When the session ends, the staged chunks are
 * concatenated - losslessly, by copying already-encoded samples straight from
 * one container into another, never re-encoding - into a single .mp4 per
 * camera.
 */
object SessionStitcher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fired once a session's per-camera videos have been merged, when
     * [AppSettings.driveStitchEnabled] - the merged file(s) stay on disk (not
     * deleted here) until [UploadManager] confirms them in Drive.
     */
    var onSessionMerged: ((String, List<Merge>) -> Unit)? = null

    data class Merge(val camera: String, val file: File)

    /**
     * A merged session video's on-disk name - also how orphan recovery at
     * launch tells a finished merge apart from raw per-chunk staging inputs.
     */
    private fun mergedFileName(sessionID: String, camera: String) = "session_${sessionID}_$camera.mp4"

    /**
     * The extra cuts a session can produce besides its real per-camera
     * videos - gallery-only, rebuilt every session, never part of the
     * chain-of-custody pipeline.
     */
    private val extraCuts = listOf("pip", "highlights", "blurred")

    private fun isExtraCut(sessionID: String, fileName: String) =
        extraCuts.any { fileName.startsWith("session_${sessionID}_$it") }

    /**
     * Extension of the scratch file a merge writes to before it is moved into
     * place. Its presence means an interrupted merge; its absence means the
     * final file, if there is one, is whole.
     */
    private const val PARTIAL = "partial"

    /** Beside a session's staged chunks: when its first one began. */
    private const val ORIGIN_FILE = "origin_started_at"

    private val sessionsRoot: File
        get() = File(WitnessApplication.appContext.filesDir, "sessions").apply { mkdirs() }

    private fun stagingDir(sessionID: String): File =
        File(sessionsRoot, sessionID).apply { mkdirs() }

    /**
     * Cleans up staging directories left behind by a previous app lifetime -
     * e.g. the process was killed mid-encounter, or between a merge finishing
     * and its Drive upload confirming.
     *
     * A directory containing an already-merged `session_*.mp4` is not
     * discarded outright: if Drive-stitch is enabled, that file is the one
     * remaining copy still waiting to reach Drive, so it is handed back to
     * [onSessionMerged] to resume the upload.
     *
     * A directory that still holds raw per-chunk inputs is a merge that never
     * finished - the process was killed partway through. [CaptureViewModel]
     * keeps the recording's foreground service alive until [finishSession]'s
     * completion fires specifically to make this rare, but it is not
     * absolute: a force-stop, or a device aggressive enough to kill a
     * foreground service outright, can still end the process mid-merge. This
     * is the fallback for that. [merge] deletes its inputs on success, so
     * their survival is a reliable signal that it never got that far. Those
     * are re-merged rather than deleted.
     *
     * Deleting them, which is what this used to do, was unrecoverable: the
     * chunks had reached Drive individually, but the inputs needed to build
     * the consolidated video were gone, so the session was left with only
     * fragments and no way back.
     */
    fun cleanupOrphanedStaging() {
        scope.launch {
            val directories = sessionsRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
            for (directory in directories) {
                val sessionID = directory.name
                val contents = directory.listFiles().orEmpty()

                // Scratch output from a merge that died partway. Never usable.
                contents.filter { it.extension == PARTIAL }.forEach { it.delete() }

                val wantsDrive = AppSettings.driveStitchEnabled && !AppSettings.isLocalOnlyCapture

                // The picture-in-picture and highlight cuts are rebuilt from
                // scratch every session and expendable: with Drive upload off
                // a leftover one is simply deleted (it was already offered to
                // the gallery when it was made), and with it on a leftover
                // one is recovered into the upload pipeline below, exactly
                // like the two real per-camera videos - they go to the cloud
                // under their own names, the same as on iOS.
                if (!wantsDrive) {
                    contents.filter {
                        isExtraCut(sessionID, it.name) && it.extension != PARTIAL
                    }.forEach { it.delete() }
                }

                val merged = contents.filter {
                    it.name.startsWith("session_${sessionID}_") &&
                        (wantsDrive || !isExtraCut(sessionID, it.name)) &&
                        it.extension != PARTIAL
                }
                val rawInputs = contents.filter {
                    !it.name.startsWith("session_") && it.extension == "mp4"
                }

                if (merged.isNotEmpty() && !wantsDrive) {
                    // A finished video kept because the gallery refused it
                    // last time (see finishSession): offered again, and
                    // deleted only once it has actually been taken. A user
                    // who has since fixed a permission or freed space gets
                    // the recording back rather than never learning it was
                    // at risk.
                    var retained = false
                    for (file in merged) {
                        val camera = file.nameWithoutExtension.removePrefix("session_${sessionID}_")
                        val uri = MediaStoreService.save(
                            file, displayName = "Witness_${sessionID.take(8)}_$camera.mp4"
                        )
                        if (uri != null) {
                            EventLogger.log("photos.session_saved", detail = "$sessionID $camera (retried)")
                            CaseLibrary.rememberGalleryUri(sessionID, uri.toString())
                            if (LocalSessionRecord.wasLocalOnly(sessionID)) {
                                LocalSessionRecord.recordSavedVideo(
                                    sessionID, camera,
                                    runCatching { Hashing.sha256Hex(file) }.getOrNull(),
                                    uri.toString(),
                                )
                            }
                            file.delete()
                        } else {
                            retained = true
                        }
                    }
                    if (LocalSessionRecord.wasLocalOnly(sessionID)) LocalSessionRecord.finish(sessionID)
                    if (retained) {
                        EventLogger.log("stitch.staging_retained", detail = sessionID)
                    } else if (rawInputs.isNotEmpty()) {
                        finishSession(sessionID)
                    } else {
                        directory.deleteRecursively()
                    }
                } else if (wantsDrive && merged.isNotEmpty()) {
                    val recovered = merged.map { file ->
                        Merge(file.nameWithoutExtension.removePrefix("session_${sessionID}_"), file)
                    }
                    EventLogger.log(
                        "stitch.recovered_pending_merge",
                        detail = "$sessionID cameras=${recovered.joinToString(",") { it.camera }}"
                    )
                    onSessionMerged?.invoke(sessionID, recovered)
                } else if (rawInputs.isNotEmpty()) {
                    EventLogger.log(
                        "stitch.retrying_interrupted_merge",
                        detail = "$sessionID inputs=${rawInputs.size}"
                    )
                    finishSession(sessionID)
                } else {
                    directory.deleteRecursively()
                }
            }
        }
    }

    /**
     * Stage a finalized chunk for later stitching. Synchronous so that a chunk
     * is always staged before the session-finalized signal that follows it.
     */
    fun stage(chunk: CaptureSessionController.FinalizedChunk) {
        // The session's start, kept beside its chunks: a session kept only on
        // the phone has no upload-queue records to ask, and the highlight cut
        // needs it to turn moment marks into positions in the video.
        runCatching {
            val origin = File(stagingDir(chunk.sessionID), ORIGIN_FILE)
            val known = origin.takeIf { it.exists() }?.readText()?.toLongOrNull()
            if (known == null || chunk.startedAt < known) origin.writeText(chunk.startedAt.toString())
        }
        // Name includes the camera so front and back at the same index don't
        // collide, and can be grouped for separate merges.
        val dest = File(
            stagingDir(chunk.sessionID),
            "%06d_%s.mp4".format(chunk.index, chunk.camera.wire)
        )
        dest.delete()
        try {
            // Hard link: no data copied, and it survives the uploader deleting
            // its own copy the moment Drive confirms receipt.
            Os.link(chunk.file.absolutePath, dest.absolutePath)
        } catch (_: ErrnoException) {
            // Link unsupported here - fall back to a real copy.
            runCatching { chunk.file.copyTo(dest, overwrite = true) }
        }
    }

    /**
     * Merge each camera's staged chunks into one .mp4 (so dual-camera sessions
     * yield a rear and a front video). Depending on settings, the merged video
     * is saved to the gallery and/or handed off (via [onSessionMerged]) to the
     * Drive-stitch pipeline, which owns deleting it once its own upload is
     * confirmed. If neither consumer wants it, it - and the whole staging
     * directory - is deleted here. Best-effort and decoupled from the uploader.
     *
     * `completion` fires once this session is fully resolved - merged, saved
     * to the gallery where that was wanted, and either handed to Drive-stitch
     * or cleaned up. [com.witnessrecord.app.viewmodel.CaptureViewModel] uses
     * it to know when it is finally safe to let the recording's foreground
     * service stop: a merge on a long session, or heavy Pro settings, can run
     * well past the moment `stop()` returns, and with the service torn down
     * before then the merge has no protection against the process being
     * reclaimed mid-way. [cleanupOrphanedStaging] is the fallback for
     * whatever this cannot cover - a force-stop, a device that kills the
     * service outright - not the primary mechanism anymore.
     */
    fun finishSession(sessionID: String, completion: (() -> Unit)? = null) {
        scope.launch {
            val directory = stagingDir(sessionID)
            // Raw inputs only. A re-merge after an interrupted attempt runs in
            // a directory that may also hold a merged file from the session's
            // *other* camera, which must not be fed back in as an input.
            val staged = directory.listFiles()
                ?.filter { it.extension == "mp4" && !it.name.startsWith("session_") }
                .orEmpty()
            if (staged.isEmpty()) {
                directory.deleteRecursively()
                completion?.invoke()
                return@launch
            }

            // Group by camera suffix ("000000_back" -> "back").
            val groups = staged.groupBy { it.nameWithoutExtension.substringAfterLast('_') }
            // A local-only session has nothing remote for the merged video to
            // be consolidated *into*, so handing it to the upload pipeline
            // would contradict the setting that made it local-only.
            val wantsDriveUpload = AppSettings.driveStitchEnabled && !AppSettings.isLocalOnlyCapture

            // Not optional in a local-only session. Its chunks were staged and
            // their originals deleted at capture time, so the hard links this
            // is about to merge are the only copy of the footage in existence.
            // With the gallery switched off and nothing uploading, the branch
            // below would delete the staging directory and the recording would
            // be gone - which turning off cloud upload is very obviously not a
            // request to do. The gallery is the only place on the device the
            // user can actually reach it, so that is where it goes.
            val wantsGallery = AppSettings.photosBackupEnabled || AppSettings.isLocalOnlyCapture

            // Each camera's chunks merged in parallel. The raw staging inputs
            // are spent once merged, regardless of what happens to the
            // output afterward, so they're deleted right here rather than
            // deferred to the save/upload decisions below.
            val mergeOutcomes = groups.map { (camera, files) ->
                async {
                    val sorted = files.sortedBy { it.name }
                    runCatching {
                        val output = merge(sorted, sessionID, camera, directory)
                        sorted.forEach { it.delete() }
                        MergeOutcome(camera, output, sorted.size)
                    }.getOrElse { error ->
                        EventLogger.log(
                            "stitch.merge_failed",
                            detail = "$sessionID $camera: ${error.message}"
                        )
                        null
                    }
                }
            }.awaitAll().filterNotNull()

            // A combined picture-in-picture file, resolved here while both
            // per-camera outputs below are still guaranteed to exist on disk
            // - the per-camera save/upload step further down deletes its
            // source file outright when Drive upload is off, so this has to
            // run before that, not after.
            //
            // Unlike the two real per-camera videos, this is a genuine
            // re-encode composited from the other two, not a lossless
            // reassembly of them, and it is labelled as exactly that
            // everywhere: its own file name in the cloud, and its own
            // distinctly-named entry in the manifest. It goes to the gallery
            // if wanted and to the cloud alongside the real videos if that is
            // on; either way nothing about the real per-camera videos waits
            // on it, and a failure here only ever means one file fewer.
            val extraMerges = mutableListOf<Merge>()
            if (AppSettings.pipExportEnabled) {
                val backOutput = mergeOutcomes.firstOrNull { it.camera == "back" }?.output
                val frontOutput = mergeOutcomes.firstOrNull { it.camera == "front" }?.output
                if (backOutput != null && frontOutput != null) {
                    runCatching {
                        val pipFile = File(directory, mergedFileName(sessionID, "pip"))
                        PictureInPictureExporter.export(backOutput, frontOutput, pipFile)
                        if (wantsGallery) {
                            MediaStoreService.save(
                                pipFile, displayName = "Witness_${sessionID.take(8)}_pip.mp4"
                            )
                            EventLogger.log("photos.pip_saved", detail = sessionID)
                        }
                        if (wantsDriveUpload) extraMerges.add(Merge("pip", pipFile)) else pipFile.delete()
                    }.onFailure { error ->
                        File(directory, mergedFileName(sessionID, "pip")).delete()
                        // The two separate per-camera videos below are
                        // entirely unaffected by this failing - it only ever
                        // adds a third file, so there is nothing to roll back.
                        EventLogger.log("stitch.pip_failed", detail = "$sessionID: ${error.message}")
                    }
                }
            }

            // A shorter cut of the rear video with the dead air trimmed out.
            // Handled exactly like picture-in-picture above, and resolved
            // here for the same reason: the per-camera save step below may
            // delete the very file this reads.
            if (AppSettings.highlightExportEnabled) {
                val backOutput = mergeOutcomes.firstOrNull { it.camera == "back" }?.output
                if (backOutput != null) {
                    runCatching {
                        val highlightsFile = File(directory, mergedFileName(sessionID, "highlights"))
                        HighlightExtractor.build(backOutput, highlightsFile, marks = marksInVideoSeconds(sessionID, directory))
                        if (wantsGallery) {
                            MediaStoreService.save(
                                highlightsFile, displayName = "Witness_${sessionID.take(8)}_highlights.mp4"
                            )
                            EventLogger.log("photos.highlights_saved", detail = sessionID)
                        }
                        if (wantsDriveUpload) {
                            extraMerges.add(Merge("highlights", highlightsFile))
                        } else {
                            highlightsFile.delete()
                        }
                    }.onFailure { error ->
                        File(directory, mergedFileName(sessionID, "highlights")).delete()
                        if (error is HighlightExtractor.NoActivity) {
                            // Nothing stood out from the recording's own
                            // quiet: no shorter cut exists, which is not a
                            // failure.
                            EventLogger.log("stitch.highlights_none", detail = sessionID)
                        } else {
                            EventLogger.log("stitch.highlights_failed", detail = "$sessionID: ${error.message}")
                        }
                    }
                }
            }

            // Publish-safe copies with faces and plates blurred (and, if asked,
            // voices disguised): the rear video, and the selfie camera's too
            // when it was recording. Additive and expendable, like the other
            // cuts - the originals are untouched and never wait on this.
            if (AppSettings.blurExportEnabled) {
                for ((camera, name) in listOf("back" to "blurred", "front" to "blurred_front")) {
                    val original = mergeOutcomes.firstOrNull { it.camera == camera }?.output ?: continue
                    val blurredFile = File(directory, mergedFileName(sessionID, name))
                    runCatching {
                        BlurExporter.build(original, blurredFile)
                        if (AppSettings.voiceDisguiseEnabled) {
                            // Best effort: a copy with real voices is still a
                            // useful blurred copy, so a failure here is logged,
                            // not fatal.
                            runCatching { VoiceDisguiser.apply(blurredFile) }.onFailure {
                                EventLogger.log("stitch.voice_failed", detail = "$sessionID $camera: ${it.message}")
                            }
                        }
                        if (wantsGallery) {
                            MediaStoreService.save(
                                blurredFile, displayName = "Witness_${sessionID.take(8)}_$name.mp4"
                            )
                            EventLogger.log("photos.blurred_saved", detail = "$sessionID $camera")
                        }
                        if (wantsDriveUpload) extraMerges.add(Merge(name, blurredFile)) else blurredFile.delete()
                    }.onFailure { error ->
                        blurredFile.delete()
                        EventLogger.log("stitch.blur_failed", detail = "$sessionID $camera: ${error.message}")
                    }
                }
            }

            // Set when something in the staging directory is still the only
            // copy of the recording - a video the gallery refused, or the
            // chunks behind a merge that failed - and the next launch has to
            // find it there to try again. Deleting the directory anyway is
            // how footage used to disappear silently at this point.
            var mustKeepDirectory = mergeOutcomes.size < groups.size

            // A thumbnail and an audio copy for Cases, taken before the video
            // is handed off and deleted below. Best effort.
            mergeOutcomes.firstOrNull { it.camera == "back" }?.let {
                runCatching { CaseLibrary.attachRecordingMedia(sessionID, it.output) }
            }

            // Whether this session's pieces were dropped at capture rather than
            // queued: noted at the time, so a setting changed since cannot
            // change the answer.
            val localOnly = LocalSessionRecord.wasLocalOnly(sessionID)

            val merged = mergeOutcomes.mapNotNull { outcome ->
                var saved = false
                if (wantsGallery) {
                    val uri = MediaStoreService.save(
                        outcome.output,
                        displayName = "Witness_${sessionID.take(8)}_${outcome.camera}.mp4"
                    )
                    saved = uri != null
                    if (uri != null) {
                        EventLogger.log(
                            "photos.session_saved",
                            detail = "$sessionID ${outcome.camera} chunks=${outcome.chunkCount}"
                        )
                        CaseLibrary.rememberGalleryUri(sessionID, uri.toString())
                        // The gallery copy is the only one, so its hash is what
                        // the record attests to.
                        if (localOnly) {
                            LocalSessionRecord.recordSavedVideo(
                                sessionID, outcome.camera,
                                runCatching { Hashing.sha256Hex(outcome.output) }.getOrNull(),
                                uri.toString(),
                            )
                        }
                    }
                }
                when {
                    wantsDriveUpload -> Merge(outcome.camera, outcome.output)
                    // Deleted only once something has taken it. Nothing else
                    // holds this footage once its chunks are spent.
                    saved -> {
                        outcome.output.delete()
                        null
                    }
                    // Not the only copy when the pieces are queued: the queue
                    // keeps each one until the cloud confirms it. Keeping the
                    // merge as well, for a gallery that may never accept it,
                    // would leave hidden gigabytes on the phone.
                    !localOnly -> {
                        outcome.output.delete()
                        EventLogger.log(
                            "stitch.dropped_redundant_merge",
                            detail = "$sessionID ${outcome.camera}: pieces are queued"
                        )
                        null
                    }
                    else -> {
                        EventLogger.log(
                            "stitch.kept_unsaved_merge",
                            detail = "$sessionID ${outcome.camera}: nowhere to send it"
                        )
                        mustKeepDirectory = true
                        null
                    }
                }
            }

            if (localOnly) LocalSessionRecord.finish(sessionID)

            if (wantsDriveUpload && merged.isNotEmpty()) {
                // Directory (and its merged files) stay on disk - owned now by
                // the Drive-stitch pipeline until upload confirms.
                onSessionMerged?.invoke(sessionID, merged + extraMerges)
            } else if (mustKeepDirectory) {
                EventLogger.log("stitch.staging_retained", detail = sessionID)
            } else {
                directory.deleteRecursively()
            }
            completion?.invoke()
        }
    }

    /**
     * The moment marks made during this session, as seconds into its video:
     * their wall-clock times against the session's own start, which the
     * upload queue knows or - for a session kept only on the phone - the
     * staging directory noted.
     */
    private fun marksInVideoSeconds(sessionID: String, directory: File): List<Double> {
        val start = ChunkStore.records(sessionID).minOfOrNull { it.startedAt }
            ?: File(directory, ORIGIN_FILE).takeIf { it.exists() }?.readText()?.toLongOrNull()
            ?: return emptyList()
        return MomentMarkers.between(start, System.currentTimeMillis()).map { (it - start) / 1000.0 }
    }

    private data class MergeOutcome(val camera: String, val output: File, val chunkCount: Int)

    /**
     * Lossless concatenation: every chunk of a session was produced by the
     * same encoder at the same settings, so the already-encoded samples are
     * copied straight into one output container. No decode, no re-encode, no
     * generational quality loss - and fast enough not to matter on a phone.
     */
    private fun merge(inputs: List<File>, sessionID: String, camera: String, directory: File): File {
        // Written under a scratch name and moved into place only on success,
        // so the presence of the final file *means* the merge completed. An
        // attempt interrupted partway - the process killed during a long
        // session's merge - otherwise leaves a truncated file that recovery
        // cannot tell apart from a good one, and uploads as if it were the
        // whole recording. That is the worse of the two failure modes: a
        // missing video is visibly missing, a silently truncated one is not.
        val output = File(directory, mergedFileName(sessionID, camera))
        val partial = File(directory, "${output.name}.$PARTIAL")
        output.delete()
        partial.delete()

        val muxer = MediaMuxer(partial.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var audioTrack = -1
        var started = false

        // Where the next input's samples begin on the output timeline.
        var timeOffsetUs = 0L
        val buffer = ByteBuffer.allocate(1 shl 21)
        val info = MediaCodec.BufferInfo()

        try {
            for (input in inputs) {
                val extractor = MediaExtractor()
                var longestUs = 0L
                try {
                    extractor.setDataSource(input.absolutePath)

                    // Map this input's track indices onto the output's.
                    val trackMap = mutableMapOf<Int, Int>()
                    for (track in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(track)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        when {
                            mime.startsWith("video/") -> {
                                if (videoTrack < 0) {
                                    videoTrack = muxer.addTrack(format)
                                    runCatching {
                                        muxer.setOrientationHint(
                                            format.getInteger(MediaFormat.KEY_ROTATION)
                                        )
                                    }
                                }
                                trackMap[track] = videoTrack
                            }
                            mime.startsWith("audio/") -> {
                                if (audioTrack < 0) audioTrack = muxer.addTrack(format)
                                trackMap[track] = audioTrack
                            }
                        }
                    }

                    if (!started) {
                        muxer.start()
                        started = true
                    }

                    for ((inputTrack, outputTrack) in trackMap) {
                        extractor.selectTrack(inputTrack)
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        while (true) {
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) break
                            val sampleTimeUs = extractor.sampleTime
                            // MediaExtractor and MediaCodec use different flag
                            // vocabularies. Translate rather than passing the
                            // extractor's bitmask straight through: they happen
                            // to agree on the keyframe bit today, but the muxer
                            // is documented against the MediaCodec constants
                            // and the rest of the extractor's bits mean other
                            // things entirely.
                            val isSync = extractor.sampleFlags and
                                MediaExtractor.SAMPLE_FLAG_SYNC != 0
                            info.set(
                                0,
                                size,
                                timeOffsetUs + sampleTimeUs,
                                if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                            )
                            muxer.writeSampleData(outputTrack, buffer, info)
                            if (sampleTimeUs > longestUs) longestUs = sampleTimeUs
                            extractor.advance()
                        }
                        extractor.unselectTrack(inputTrack)
                    }
                } finally {
                    extractor.release()
                }
                // One frame's worth of gap so the next chunk's first sample
                // never collides with this chunk's last on the timeline.
                timeOffsetUs += longestUs + 33_333L
            }
        } finally {
            runCatching {
                if (started) muxer.stop()
                muxer.release()
            }
        }

        if (!partial.exists() || partial.length() == 0L) {
            partial.delete()
            throw IllegalStateException("merge produced no output")
        }
        if (!partial.renameTo(output)) {
            partial.delete()
            throw IllegalStateException("merge output could not be moved into place")
        }
        return output
    }
}
