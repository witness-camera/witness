package com.witnessrecord.app.service

import android.net.Uri
import com.witnessrecord.app.R
import com.witnessrecord.app.WitnessApplication
import com.witnessrecord.app.model.ChunkState
import com.witnessrecord.app.service.storage.StorageProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Keeps a recording from weighing twice on the phone when the gallery holds
 * it, and sends gallery-only recordings to the cloud once an account is
 * connected.
 *
 * Both actions are offered, never automatic. Freeing space deletes files, so it
 * first confirms the gallery still has every video; uploading can mean
 * gigabytes over mobile data, so it waits to be asked.
 *
 * The Android counterpart of the iOS build's `LibraryStorage`.
 */
object LibraryStorage {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Pieces queued for a cloud account that isn't connected, belonging to recordings the gallery already holds. */
    private val _redundantBytes = MutableStateFlow(0L)
    val redundantBytes: StateFlow<Long> = _redundantBytes.asStateFlow()

    private val _freeing = MutableStateFlow(false)
    val freeing: StateFlow<Boolean> = _freeing.asStateFlow()

    /** Recordings kept only in the gallery and not yet uploaded. */
    private val _awaitingUpload = MutableStateFlow<List<String>>(emptyList())
    val awaitingUpload: StateFlow<List<String>> = _awaitingUpload.asStateFlow()

    /** (done, total) while earlier recordings are uploading. */
    private val _uploadProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val uploadProgress: StateFlow<Pair<Int, Int>?> = _uploadProgress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var redundantSessions: Map<String, List<String>> = emptyMap()

    fun refresh() {
        _awaitingUpload.value = LocalSessionRecord.awaitingUpload()
        if (AppSettings.cloudAccountConnected) {
            _redundantBytes.value = 0
            redundantSessions = emptyMap()
            return
        }
        val galleryByItem = CaseLibrary.items.value.mapNotNull { item ->
            val session = item.sessionID ?: return@mapNotNull null
            val uris = item.galleryUris?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            session to uris
        }.toMap()
        scope.launch {
            val waiting = ChunkStore.records { it.state != ChunkState.UPLOADED }
            val sessions = mutableMapOf<String, List<String>>()
            var bytes = 0L
            val held = mutableMapOf<String, Boolean>()
            for (record in waiting) {
                val uris = galleryByItem[record.sessionID] ?: continue
                // Only recordings the gallery really still holds are offered.
                val present = held.getOrPut(record.sessionID) { uris.all { galleryHolds(it) } }
                if (!present) continue
                sessions[record.sessionID] = uris
                bytes += runCatching { record.file.length() }.getOrDefault(0L)
            }
            redundantSessions = sessions
            _redundantBytes.value = bytes
        }
    }

    private fun galleryHolds(uri: String): Boolean =
        runCatching {
            WitnessApplication.appContext.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
        }.getOrDefault(false)

    // MARK: - Free up space

    suspend fun freeUpSpace() {
        if (_freeing.value) return
        _freeing.value = true
        val context = WitnessApplication.appContext
        try {
            var freed = 0
            var kept = 0
            for ((sessionID, uris) in redundantSessions) {
                // Every video the gallery was given must still be there: one
                // deleted from it means these pieces are its only copy.
                if (!uris.all { galleryHolds(it) }) {
                    kept += 1
                    continue
                }
                val records = ChunkStore.records(sessionID)
                val masters = records.filter { it.kind == com.witnessrecord.app.model.ChunkKind.MASTER }
                val chunks = masters.map {
                    LocalSessionRecord.ChunkInfo(
                        index = it.index, camera = it.camera.wire, fileName = it.fileName,
                        startedAt = it.startedAt, endedAt = it.endedAt,
                        latitude = it.latitude, longitude = it.longitude,
                        horizontalAccuracy = it.horizontalAccuracy, sha256 = it.sha256,
                    )
                }
                // Which gallery video is which camera wasn't recorded at the
                // time. One video takes the session's camera; several are numbered.
                val cameras = masters.map { it.camera.wire }.toSet()
                val videos = uris.mapIndexed { i, uri ->
                    LocalSessionRecord.Video(
                        camera = if (uris.size == 1) (cameras.firstOrNull() ?: "back") else (i + 1).toString(),
                        sha256 = null, galleryUri = uri,
                    )
                }
                LocalSessionRecord.adopt(sessionID, chunks, videos)
                LocalSessionRecord.finish(sessionID, LocalSessionRecord.CONVERTED_NOTE)
                ChunkStore.removeAll(sessionID)
                runCatching { UploadManager.manifestFile(sessionID).delete() }
                freed += 1
                EventLogger.log("storage.freed_session", detail = sessionID)
            }
            UploadManager.refreshPendingCount()
            _message.value = if (kept > 0) context.getString(R.string.storage_kept_message, kept) else null
            if (freed == 0 && kept == 0) _message.value = null
        } finally {
            _freeing.value = false
            refresh()
        }
    }

    // MARK: - Upload earlier recordings

    suspend fun uploadEarlierRecordings() {
        if (_uploadProgress.value != null) return
        val sessions = _awaitingUpload.value
        if (sessions.isEmpty()) return
        val context = WitnessApplication.appContext
        _message.value = null
        _uploadProgress.value = 0 to sessions.size
        try {
            var missing = 0
            for ((i, sessionID) in sessions.withIndex()) {
                val manifest = SessionArchive.sessions().firstOrNull { it.sessionID == sessionID } ?: continue
                val gallery = LocalSessionRecord.gallery(sessionID) ?: continue
                val videos = gallery.videos.mapNotNull { exportVideo(it) }
                if (videos.isEmpty()) {
                    missing += 1
                    continue
                }
                try {
                    UploadManager.uploadLibraryRecording(
                        sessionID, manifest, videos,
                        manifestFile = SessionArchive.manifestFile(sessionID),
                        timestampFile = SessionArchive.timestampFile(sessionID).takeIf { it.exists() },
                    )
                    LocalSessionRecord.markUploaded(sessionID)
                    _uploadProgress.value = (i + 1) to sessions.size
                } catch (error: Throwable) {
                    _message.value = context.getString(R.string.storage_upload_paused, error.message ?: "")
                    EventLogger.log("backfill.failed", detail = "$sessionID: ${error.message}")
                    return
                } finally {
                    videos.forEach { it.file.delete() }
                }
            }
            if (missing > 0) _message.value = context.getString(R.string.storage_upload_missing, missing)
        } finally {
            _uploadProgress.value = null
            refresh()
        }
    }

    /** Copies a gallery video out to a temporary file, and checks it against the hash taken when it was saved. */
    private suspend fun exportVideo(video: LocalSessionRecord.Video): UploadManager.BackfillVideo? =
        withContext(Dispatchers.IO) {
            val context = WitnessApplication.appContext
            val temporary = File(context.cacheDir, "backfill-${UUID.randomUUID()}.mp4")
            val copied = runCatching {
                context.contentResolver.openInputStream(Uri.parse(video.galleryUri))?.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                } != null
            }.getOrDefault(false)
            if (!copied) {
                temporary.delete()
                return@withContext null
            }
            if (video.sha256 != null) {
                val actual = runCatching { Hashing.sha256Hex(temporary) }.getOrNull()
                if (actual != video.sha256) {
                    // Uploaded anyway: it is still the person's footage. The
                    // mismatch is on record for whoever checks it against the
                    // manifest.
                    EventLogger.log("backfill.hash_mismatch", detail = "${video.galleryUri} expected=${video.sha256.take(16)}")
                }
            }
            UploadManager.BackfillVideo(UploadManager.mergedRemoteFileName(video.camera), "video/mp4", temporary)
        }

    /** Whether Home should offer "upload earlier recordings": there are some, and an account to send them to. */
    val canOfferUpload: Boolean
        get() = _awaitingUpload.value.isNotEmpty() && StorageProviders.current?.isSignedIn == true
}
