package com.witnessrecord.app.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.webkit.MimeTypeMap
import com.witnessrecord.app.BuildConfig
import com.witnessrecord.app.WitnessApplication
import com.witnessrecord.app.model.CaseItem
import com.witnessrecord.app.model.EvidenceCase
import com.witnessrecord.app.model.ItemNote
import com.witnessrecord.app.model.ItemRecord
import com.witnessrecord.app.model.SessionManifest
import com.witnessrecord.app.service.storage.StorageProviders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.util.TimeZone
import java.util.UUID

/**
 * Cases and everything filed in them, kept on the device.
 *
 * Recordings themselves still go where they always went - the gallery and the
 * connected cloud account - and their proof is still the session manifest.
 * What this adds per recording is small: a thumbnail, and a low-bitrate audio
 * copy. Photos, audio recordings and imported files are kept here in full,
 * each with its own hashed and time-stamped record, and also sent to the
 * cloud account.
 *
 * The Android counterpart of the iOS build's `CaseLibrary`.
 */
object CaseLibrary {

    @Serializable
    private data class Stored(
        val cases: List<EvidenceCase> = emptyList(),
        val items: List<CaseItem> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    /** Work that must finish even when the screen that started it is gone: filing, proofs, uploads. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _cases = MutableStateFlow<List<EvidenceCase>>(emptyList())
    val cases: StateFlow<List<EvidenceCase>> = _cases.asStateFlow()

    private val _items = MutableStateFlow<List<CaseItem>>(emptyList())
    val items: StateFlow<List<CaseItem>> = _items.asStateFlow()

    /**
     * The recording that just finished, so Home can offer to add details.
     * Never presented on its own: it is an offer the person can ignore.
     */
    private val _justFinishedItemID = MutableStateFlow<String?>(null)
    val justFinishedItemID: StateFlow<String?> = _justFinishedItemID.asStateFlow()

    fun dismissJustFinished() {
        _justFinishedItemID.value = null
    }

    private val root: File
        get() = File(WitnessApplication.appContext.filesDir, "cases").apply { mkdirs() }

    fun directory(itemID: String): File = File(File(root, "items"), itemID).apply { mkdirs() }
    fun thumbnailFile(itemID: String): File = File(directory(itemID), "thumbnail.jpg")
    fun recordFile(itemID: String): File = File(directory(itemID), "record.json")
    fun timestampFile(itemID: String): File = File(directory(itemID), "record.tsr")
    fun mediaFile(item: CaseItem): File? = item.fileName?.let { File(directory(item.id), it) }

    /** The audio a recording can be listened to from: its own for an audio item, the extracted copy otherwise. */
    fun audioFile(item: CaseItem): File? {
        val file = if (item.kind == CaseItem.KIND_AUDIO) mediaFile(item) else File(directory(item.id), "audio.m4a")
        return file?.takeIf { it.exists() }
    }

    private val storeFile: File get() = File(root, "library.json")

    init {
        runCatching {
            if (storeFile.exists()) {
                val stored = json.decodeFromString(Stored.serializer(), storeFile.readText())
                _cases.value = stored.cases
                _items.value = stored.items
            }
        }.onFailure { EventLogger.log("cases.load_failed", detail = it.message) }
        adoptArchivedRecordings()
    }

    private fun save() {
        synchronized(lock) {
            runCatching {
                val text = json.encodeToString(Stored.serializer(), Stored(_cases.value, _items.value))
                val partial = File(root, "library.json.partial")
                partial.writeText(text)
                partial.renameTo(storeFile)
            }
        }
    }

    /**
     * Recordings made before Cases existed, or whose item was removed, still
     * have their manifest in the archive. Each one without an item gets one, so
     * every past recording can be filed. No thumbnail or audio copy: the
     * footage left the device long ago.
     */
    private fun adoptArchivedRecordings() {
        val known = _items.value.mapNotNull { it.sessionID }.toSet()
        val adoptedKey = "cases.adoptedSessions"
        val adopted = (Prefs.shared.getStringSet(adoptedKey, emptySet()) ?: emptySet()).toMutableSet()
        var changed = false
        val added = mutableListOf<CaseItem>()
        for (manifest in SessionArchive.sessions()) {
            if (manifest.sessionID in known || manifest.sessionID in adopted) continue
            val first = manifest.chunks.minByOrNull { it.captureStartUTC }
            added.add(
                CaseItem(
                    id = UUID.randomUUID().toString(), kind = CaseItem.KIND_VIDEO,
                    sessionID = manifest.sessionID,
                    createdAt = manifest.recordingStartedUTC, endedAt = manifest.recordingEndedUTC,
                    latitude = first?.latitude, longitude = first?.longitude,
                    timestamped = SessionArchive.hasTimestamp(manifest.sessionID), uploaded = true,
                )
            )
            // Remembered, so removing one from Witness doesn't bring it back.
            adopted.add(manifest.sessionID)
            changed = true
        }
        if (changed) {
            _items.value = _items.value + added
            Prefs.shared.edit().putStringSet(adoptedKey, adopted).apply()
            save()
        }
    }

    // MARK: - Lookup

    fun item(id: String): CaseItem? = _items.value.firstOrNull { it.id == id }

    fun items(inCase: String?): List<CaseItem> =
        _items.value.filter { it.caseID == inCase }.sortedByDescending { it.createdAt }

    fun update(id: String, change: (CaseItem) -> CaseItem) {
        synchronized(lock) {
            _items.value = _items.value.map { if (it.id == id) change(it) else it }
        }
        save()
    }

    // MARK: - Cases

    fun newCase(title: String, defaultTitle: String): EvidenceCase {
        val created = EvidenceCase(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifEmpty { defaultTitle },
            createdAt = System.currentTimeMillis(),
        )
        synchronized(lock) { _cases.value = listOf(created) + _cases.value }
        save()
        return created
    }

    fun rename(caseID: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) {
            _cases.value = _cases.value.map { if (it.id == caseID) it.copy(title = trimmed) else it }
        }
        save()
    }

    /** Deletes the case only; its items move back to "Not in a case". */
    fun deleteCase(caseID: String) {
        synchronized(lock) {
            _cases.value = _cases.value.filterNot { it.id == caseID }
            _items.value = _items.value.map { if (it.caseID == caseID) it.copy(caseID = null) else it }
        }
        save()
    }

    fun move(itemID: String, caseID: String?) = update(itemID) { it.copy(caseID = caseID) }

    fun move(itemIDs: Set<String>, caseID: String?) {
        synchronized(lock) {
            _items.value = _items.value.map { if (it.id in itemIDs) it.copy(caseID = caseID) else it }
        }
        save()
    }

    /**
     * Removes the app's own copy. Never touches the gallery or the cloud
     * account, and the screen asking for this says so.
     */
    fun deleteItem(itemID: String) {
        synchronized(lock) { _items.value = _items.value.filterNot { it.id == itemID } }
        runCatching { directory(itemID).deleteRecursively() }
        save()
    }

    // MARK: - Recordings

    /**
     * Called the moment a recording stops, so the item exists for the "add
     * details" offer before its media has been extracted.
     */
    fun registerRecording(sessionID: String, startedAt: Long?, endedAt: Long, latitude: Double?, longitude: Double?) {
        val existing = _items.value.firstOrNull { it.sessionID == sessionID }
        if (existing != null) {
            _justFinishedItemID.value = existing.id
            return
        }
        val item = CaseItem(
            id = UUID.randomUUID().toString(), kind = CaseItem.KIND_VIDEO, sessionID = sessionID,
            createdAt = startedAt ?: endedAt, endedAt = endedAt,
            latitude = latitude, longitude = longitude, timestamped = false, uploaded = true,
        )
        synchronized(lock) { _items.value = _items.value + item }
        _justFinishedItemID.value = item.id
        val adopted = (Prefs.shared.getStringSet("cases.adoptedSessions", emptySet()) ?: emptySet()).toMutableSet()
        adopted.add(sessionID)
        Prefs.shared.edit().putStringSet("cases.adoptedSessions", adopted).apply()
        save()
    }

    /**
     * Takes a thumbnail and an audio copy from a finished recording's video,
     * before the stitcher hands that file off and deletes it. Best effort: a
     * recording without either is still a complete recording.
     */
    suspend fun attachRecordingMedia(sessionID: String, video: File) = withContext(Dispatchers.IO) {
        val itemID = _items.value.firstOrNull { it.sessionID == sessionID }?.id ?: run {
            registerRecording(sessionID, null, System.currentTimeMillis(), null, null)
            _justFinishedItemID.value = null
            _items.value.first { it.sessionID == sessionID }.id
        }
        runCatching { writeVideoThumbnail(video, thumbnailFile(itemID)) }
        runCatching { extractAudio(video, File(directory(itemID), "audio.m4a")) }
            .onFailure { EventLogger.log("cases.audio_copy_failed", detail = "$sessionID: ${it.message}") }
        val stamped = SessionArchive.hasTimestamp(sessionID)
        update(itemID) { it.copy(timestamped = stamped) }
    }

    /**
     * Remembers where in the gallery a recording landed. Saves for two cameras
     * can finish in either order, so each adds its own.
     */
    fun rememberGalleryUri(sessionID: String, uri: String) {
        val item = _items.value.firstOrNull { it.sessionID == sessionID } ?: return
        update(item.id) {
            val known = it.galleryUris.orEmpty()
            if (uri in known) it else it.copy(galleryUris = known + uri)
        }
    }

    /**
     * The session's proof arrives after upload, so the badge is refreshed
     * whenever the list is looked at rather than pushed from the uploader.
     */
    fun refreshRecordingProofs() {
        var changed = false
        synchronized(lock) {
            _items.value = _items.value.map {
                if (it.kind == CaseItem.KIND_VIDEO && !it.timestamped &&
                    it.sessionID != null && SessionArchive.hasTimestamp(it.sessionID)
                ) {
                    changed = true
                    it.copy(timestamped = true)
                } else it
            }
        }
        if (changed) save()
    }

    // MARK: - Photos, audio, imports

    /**
     * Adds [file] - which this library takes ownership of - hashing it, writing
     * and time-stamping its record, and filing it. The item appears straight
     * away; the proof and upload follow.
     */
    suspend fun addFile(
        moveFrom: File, kind: String, imported: Boolean,
        startedAt: Long? = null, title: String? = null, caseID: String?,
    ) {
        val id = UUID.randomUUID().toString()
        val ext = moveFrom.extension.ifEmpty { "bin" }.lowercase()
        val destination = File(directory(id), "original.$ext")
        withContext(Dispatchers.IO) {
            if (!moveFrom.renameTo(destination)) {
                runCatching { moveFrom.copyTo(destination, overwrite = true); moveFrom.delete() }
            }
        }
        if (!destination.exists()) {
            runCatching { directory(id).deleteRecursively() }
            return
        }
        finishAdding(id, destination, kind, imported, startedAt, title, caseID)
    }

    private suspend fun finishAdding(
        id: String, file: File, kind: String, imported: Boolean,
        startedAt: Long?, title: String?, caseID: String?,
    ) {
        val now = System.currentTimeMillis()
        val location = LocationService.lastLocation
        val hash = withContext(Dispatchers.IO) { runCatching { Hashing.sha256Hex(file) }.getOrNull() }
        val mime = mimeType(file)

        val item = CaseItem(
            id = id, kind = kind, caseID = caseID, title = title,
            createdAt = if (imported) now else (startedAt ?: now),
            endedAt = if (kind == CaseItem.KIND_AUDIO) now else null,
            fileName = file.name, sha256 = hash,
            latitude = if (imported) null else location?.latitude,
            longitude = if (imported) null else location?.longitude,
        )
        synchronized(lock) { _items.value = _items.value + item }
        save()

        withContext(Dispatchers.IO) {
            if (kind == CaseItem.KIND_PHOTO || mime.startsWith("image/")) {
                runCatching { writeImageThumbnail(file, thumbnailFile(id)) }
                if (kind == CaseItem.KIND_PHOTO && AppSettings.photosBackupEnabled) {
                    MediaStoreService.saveImage(file, displayName = "Witness_${id.take(8)}.jpg")
                }
            } else if (mime.startsWith("video/")) {
                runCatching { writeVideoThumbnail(file, thumbnailFile(id)) }
                runCatching { extractAudio(file, File(directory(id), "audio.m4a")) }
            }
        }

        if (hash == null) return
        val record = ItemRecord(
            schemaVersion = 1, itemID = id, kind = kind,
            source = if (imported) "imported into Witness" else "captured in Witness",
            fileName = file.name, sha256 = hash,
            capturedUTC = item.createdAt, endedUTC = item.endedAt,
            latitude = item.latitude, longitude = item.longitude,
            horizontalAccuracyMeters = if (imported) null
            else location?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
            deviceTimeZone = TimeZone.getDefault().id,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            systemVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = BuildConfig.VERSION_NAME,
            timestampAuthority = TimestampService.DEFAULT_AUTHORITY.toString(),
            timestampFileName = "record.tsr",
            note = if (imported) {
                "Hashed and time-stamped when imported. This proves the file existed unaltered from that " +
                    "moment, not when or how it was originally made."
            } else null,
        )
        withContext(Dispatchers.IO) {
            runCatching {
                recordFile(id).writeText(SessionManifest.json.encodeToString(ItemRecord.serializer(), record))
            }
        }
        stampAndUpload(id)
    }

    /**
     * Retries proofs and uploads left undone - no network at the time, or no
     * account connected yet. Runs whenever Home appears.
     */
    fun finishPendingWork() {
        for (item in _items.value) {
            if (item.kind != CaseItem.KIND_VIDEO && (!item.timestamped || !item.uploaded)) {
                scope.launch { stampAndUpload(item.id) }
            }
        }
    }

    private val inFlight = mutableSetOf<String>()
    private val inFlightLock = Mutex()

    private suspend fun stampAndUpload(id: String) {
        val item = inFlightLock.withLock {
            if (id in inFlight) return
            val found = item(id) ?: return
            inFlight.add(id)
            found
        }
        try {
            val record = recordFile(id)
            if (!record.exists()) return

            if (!item.timestamped) {
                val token = runCatching {
                    TimestampService.token(MessageDigest.getInstance("SHA-256").digest(record.readBytes()))
                }.getOrNull()
                if (token != null) {
                    runCatching { timestampFile(id).writeBytes(token) }
                    update(id) { it.copy(timestamped = true) }
                }
            }

            if (item.uploaded || AppSettings.isLocalOnlyCapture) return
            val media = mediaFile(item) ?: return
            // Wait for the proof before uploading, so the cloud copy is
            // complete; the next pass sends everything once it exists.
            if (item(id)?.timestamped != true) return
            val files = listOfNotNull(media, record, timestampFile(id).takeIf { it.exists() })
            if (upload(files, id, item.createdAt, item.latitude, item.longitude)) {
                update(id) { it.copy(uploaded = true) }
            }
        } finally {
            inFlightLock.withLock { inFlight.remove(id) }
        }
    }

    private suspend fun upload(
        files: List<File>, itemID: String, date: Long, latitude: Double?, longitude: Double?,
    ): Boolean {
        val provider = StorageProviders.current?.takeIf { it.isSignedIn } ?: return false
        return runCatching {
            val token = provider.freshAccessToken()
            val rootName = Prefs.getString(AppSettings.driveFolderNameKey, AppSettings.defaultDriveFolderName)
                ?: AppSettings.defaultDriveFolderName
            val rootID = provider.ensureRootFolder(rootName, token)
            val folderName = SessionFolderNaming.folderName(itemID, date, latitude, longitude)
            val folderID = provider.ensureSubfolder(folderName, rootID, token)
            for (file in files) {
                provider.multipartUpload(file, file.name, mimeType(file), folderID, token)
            }
            true
        }.getOrElse {
            EventLogger.log("cases.upload_failed", detail = "$itemID: ${it.message}")
            false
        }
    }

    // MARK: - Search

    data class SearchHit(val itemID: String, val text: String, val isNote: Boolean)

    /** Titles, notes and details containing [query], ignoring case and accents. */
    fun search(query: String): List<SearchHit> {
        val q = fold(query.trim())
        if (q.length < 2) return emptyList()
        val hits = mutableListOf<SearchHit>()
        for (item in _items.value.sortedByDescending { it.createdAt }) {
            item.title?.takeIf { fold(it).contains(q) }?.let { hits.add(SearchHit(item.id, it, false)) }
            for (note in item.notes) if (fold(note.text).contains(q)) hits.add(SearchHit(item.id, note.text, true))
            for (value in item.details.values) if (fold(value).contains(q)) hits.add(SearchHit(item.id, value, false))
        }
        return hits
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    // MARK: - Notes

    fun addNote(itemID: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        update(itemID) {
            it.copy(notes = it.notes + ItemNote(UUID.randomUUID().toString(), System.currentTimeMillis(), trimmed))
        }
    }

    fun removeNote(itemID: String, noteID: String) =
        update(itemID) { it.copy(notes = it.notes.filterNot { note -> note.id == noteID }) }

    // MARK: - Media helpers

    private fun mimeType(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

    private fun writeVideoThumbnail(video: File, out: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0) ?: return
            writeThumbnail(frame, out)
        } finally {
            retriever.release()
        }
    }

    private fun writeImageThumbnail(image: File, out: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            image.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return
        writeThumbnail(decoded, out)
    }

    private fun writeThumbnail(bitmap: Bitmap, out: File) {
        val side = 480f
        val scale = minOf(1f, side / maxOf(bitmap.width, bitmap.height))
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        out.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (small !== bitmap) small.recycle()
        bitmap.recycle()
    }

    /**
     * Copies the video's audio track, unchanged, into an audio-only file: the
     * same AAC already recorded, with no re-encoding. A recording with no audio
     * track gets no copy.
     */
    private fun extractAudio(video: File, out: File) {
        out.delete()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(video.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return
            extractor.selectTrack(index)
            val created = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = created
            val track = created.addTrack(extractor.getTrackFormat(index))
            created.start()
            started = true
            val buffer = ByteBuffer.allocate(1 shl 18)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                created.writeSampleData(track, buffer, info)
                extractor.advance()
            }
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
            if (!started) out.delete()
        }
    }
}
