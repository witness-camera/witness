package com.witnessrecord.app.service

import android.os.Build
import com.witnessrecord.app.BuildConfig
import com.witnessrecord.app.capture.CaptureSessionController
import com.witnessrecord.app.model.ChunkKind
import com.witnessrecord.app.model.SessionManifest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.TimeZone

/**
 * The chain-of-custody record for a recording kept only on the phone - no
 * cloud account connected, or uploads switched off, or a format too large to
 * send.
 *
 * Such a session drops each 15-second piece as soon as it is staged, so the
 * manifest can't list hashes for files that no longer exist. What it lists
 * instead is each finished video's SHA-256, taken from the exact file handed to
 * the gallery, alongside the timing and location of every piece. The video is
 * the evidence; the record proves it was not altered.
 *
 * It also remembers where in the gallery each camera's video went, so the
 * recording can be uploaded later - once an account is connected - without
 * ever keeping a second copy on the phone.
 */
object LocalSessionRecord {

    @Serializable
    data class ChunkInfo(
        val index: Int,
        val camera: String,
        val fileName: String,
        val startedAt: Long,
        val endedAt: Long,
        val latitude: Double?,
        val longitude: Double?,
        val horizontalAccuracy: Double?,
        /** Only for pieces that were hashed for upload before being removed. */
        val sha256: String? = null,
    )

    @Serializable
    data class Video(
        val camera: String,
        /** Null for a recording converted after the fact, saved before anything hashed it. */
        val sha256: String?,
        val galleryUri: String,
    )

    @Serializable
    data class Gallery(
        val videos: List<Video> = emptyList(),
        /**
         * True when the recording stayed local only because no account was
         * connected - not because uploads were switched off or the format was
         * too large, which were choices not to upload and shouldn't be
         * second-guessed later.
         */
        val offerUpload: Boolean? = null,
        /** Set once every video, the manifest and its proof are confirmed in the cloud. */
        val uploaded: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val CHUNKS_NAME = "local-chunks.json"
    private const val GALLERY_NAME = "gallery.json"
    private val lock = Any()

    // MARK: - While recording

    /** Called for each piece as it is finalized, before its file is deleted. */
    fun noteChunk(chunk: CaptureSessionController.FinalizedChunk) {
        synchronized(lock) {
            val infos = readChunks(chunk.sessionID).toMutableList()
            infos.add(
                ChunkInfo(
                    index = chunk.index, camera = chunk.camera.wire, fileName = chunk.file.name,
                    startedAt = chunk.startedAt, endedAt = chunk.endedAt,
                    latitude = chunk.location?.latitude, longitude = chunk.location?.longitude,
                    horizontalAccuracy = chunk.location?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
                )
            )
            write(CHUNKS_NAME, chunk.sessionID, json.encodeToString(ListSerializerOfChunks, infos))
        }
    }

    fun chunks(sessionID: String): List<ChunkInfo> = synchronized(lock) { readChunks(sessionID) }

    /** Whether this session's pieces were dropped at capture rather than queued. */
    fun wasLocalOnly(sessionID: String): Boolean = chunks(sessionID).isNotEmpty()

    // MARK: - Once the gallery has the video

    /** Records one camera's video as saved. Cameras finish in any order. */
    fun recordSavedVideo(sessionID: String, camera: String, sha256: String?, galleryUri: String) {
        synchronized(lock) {
            val current = readGallery(sessionID) ?: Gallery()
            val videos = current.videos.filterNot { it.camera == camera } + Video(camera, sha256, galleryUri)
            val updated = current.copy(
                videos = videos,
                offerUpload = current.offerUpload ?: keptLocalForWantOfAccount,
            )
            write(GALLERY_NAME, sessionID, json.encodeToString(Gallery.serializer(), updated))
        }
    }

    fun gallery(sessionID: String): Gallery? = synchronized(lock) { readGallery(sessionID) }

    /**
     * Takes over a recording whose pieces were queued for a cloud that never
     * came, once the gallery is confirmed to hold its videos.
     */
    fun adopt(sessionID: String, chunks: List<ChunkInfo>, videos: List<Video>) {
        synchronized(lock) {
            write(CHUNKS_NAME, sessionID, json.encodeToString(ListSerializerOfChunks, chunks))
            write(
                GALLERY_NAME, sessionID,
                json.encodeToString(Gallery.serializer(), Gallery(videos = videos, offerUpload = true)),
            )
        }
    }

    /** Whether the current settings would upload, if only an account were connected. */
    private val keptLocalForWantOfAccount: Boolean
        get() = !AppSettings.cloudAccountConnected && !AppSettings.isLocalOnlyCapture(
            codec = AppSettings.videoCodec,
            cloudUploadEnabled = AppSettings.cloudUploadEnabled,
            cloudAccountConnected = true,
        )

    fun markUploaded(sessionID: String) {
        synchronized(lock) {
            val current = readGallery(sessionID) ?: return
            write(GALLERY_NAME, sessionID, json.encodeToString(Gallery.serializer(), current.copy(uploaded = true)))
        }
    }

    const val SAVED_LOCALLY_NOTE =
        "Kept in this phone's gallery. The recording's 15-second pieces were not uploaded or kept; " +
            "each finished video's SHA-256 above is what this record attests to."
    const val CONVERTED_NOTE =
        "Kept in this phone's gallery. The 15-second pieces listed above were hashed while recording, " +
            "then removed from the phone to free space before any cloud account was connected. The " +
            "gallery copy was saved before Witness hashed finished videos, so it has no hash here."

    /**
     * Writes and time-stamps the session's manifest from what was recorded
     * above. Safe to call again: a later call (a camera saved on a retry at
     * the next launch) rewrites it with the fuller list.
     */
    suspend fun finish(sessionID: String, storageNote: String = SAVED_LOCALLY_NOTE) {
        val infos = chunks(sessionID)
        val gallery = gallery(sessionID)?.takeIf { it.videos.isNotEmpty() } ?: return

        val started = infos.minOfOrNull { it.startedAt } ?: System.currentTimeMillis()
        val ended = infos.maxOfOrNull { it.endedAt } ?: System.currentTimeMillis()
        val manifest = SessionManifest(
            schemaVersion = 1,
            sessionID = sessionID,
            recordingStartedUTC = started,
            recordingEndedUTC = ended,
            deviceTimeZone = TimeZone.getDefault().id,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            systemVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = BuildConfig.VERSION_NAME,
            chunkCount = infos.size,
            // Timing and place of every piece, kept even though the pieces
            // themselves were not: no hash, because there is no file left for
            // one to describe.
            chunks = infos.sortedWith(compareBy({ it.index }, { it.camera })).map {
                SessionManifest.Chunk(
                    index = it.index, camera = it.camera, fileName = it.fileName,
                    kind = "not_kept", sha256 = it.sha256,
                    captureStartUTC = it.startedAt, captureEndUTC = it.endedAt,
                    latitude = it.latitude, longitude = it.longitude,
                    horizontalAccuracyMeters = it.horizontalAccuracy,
                )
            },
            mergedVideos = gallery.videos.map {
                SessionManifest.MergedVideo(
                    camera = it.camera,
                    fileName = UploadManager.mergedRemoteFileName(it.camera),
                    sha256 = it.sha256,
                )
            },
            storage = storageNote,
            markers = MomentMarkers.between(started, ended).ifEmpty { null },
            crew = CrewStore.stamp(sessionID),
            timestampAuthority = TimestampService.DEFAULT_AUTHORITY.toString(),
            timestampFileName = "timestamp.tsr",
            timestampVerification = SessionManifest.VERIFICATION_INSTRUCTIONS,
        )

        val data = SessionManifest.json.encodeToString(SessionManifest.serializer(), manifest).toByteArray()
        val token = runCatching {
            TimestampService.token(MessageDigest.getInstance("SHA-256").digest(data))
        }.onFailure {
            EventLogger.log("local_record.timestamp_failed", detail = it.message)
        }.getOrNull()
        SessionArchive.store(sessionID, data, token)
        EventLogger.log(
            "local_record.written",
            detail = "$sessionID videos=${gallery.videos.size} stamped=${token != null}",
        )
    }

    /**
     * Proofs that couldn't be obtained at the time, usually for want of a
     * connection. The manifest is already final, so its bytes are stamped as
     * they are.
     */
    suspend fun retryMissingTimestamps() {
        for (manifest in SessionArchive.sessions()) {
            val id = manifest.sessionID
            if (gallery(id) == null || SessionArchive.hasTimestamp(id)) continue
            val data = runCatching { SessionArchive.manifestFile(id).readBytes() }.getOrNull() ?: continue
            val token = runCatching {
                TimestampService.token(MessageDigest.getInstance("SHA-256").digest(data))
            }.getOrNull() ?: continue
            runCatching { SessionArchive.timestampFile(id).writeBytes(token) }
        }
    }

    /** Recordings that are only in the gallery and haven't been uploaded. */
    fun awaitingUpload(): List<String> =
        SessionArchive.sessions().map { it.sessionID }.filter { id ->
            val gallery = gallery(id) ?: return@filter false
            gallery.offerUpload == true && !gallery.uploaded && gallery.videos.isNotEmpty()
        }

    // MARK: - Storage

    private val ListSerializerOfChunks = kotlinx.serialization.builtins.ListSerializer(ChunkInfo.serializer())

    private fun readChunks(sessionID: String): List<ChunkInfo> {
        val file = SessionArchive.path(CHUNKS_NAME, sessionID)
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializerOfChunks, file.readText()) }.getOrDefault(emptyList())
    }

    private fun readGallery(sessionID: String): Gallery? {
        val file = SessionArchive.path(GALLERY_NAME, sessionID)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(Gallery.serializer(), file.readText()) }.getOrNull()
    }

    private fun write(name: String, sessionID: String, text: String) {
        runCatching { SessionArchive.directory(sessionID).resolve(name).writeText(text) }
    }
}
