package com.witnessrecord.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.witnessrecord.app.BuildConfig
import com.witnessrecord.app.WitnessApplication
import com.witnessrecord.app.capture.CaptureSessionController
import com.witnessrecord.app.model.CameraSource
import com.witnessrecord.app.model.ChunkKind
import com.witnessrecord.app.model.ChunkRecord
import com.witnessrecord.app.model.ChunkState
import com.witnessrecord.app.model.EmergencyContact
import com.witnessrecord.app.model.SessionManifest
import com.witnessrecord.app.service.storage.StorageProvider
import com.witnessrecord.app.service.GoogleDriveClient
import com.witnessrecord.app.service.storage.StorageProviders
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.TimeZone
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow

/**
 * Drives the chunk upload pipeline:
 *   finalized chunk -> SHA-256 hash -> live manifest revision -> resumable
 *   upload -> local file deleted.
 *
 * Uses a persisted queue ([ChunkStore]) so nothing is lost across process
 * death, exponential backoff, a network callback so the queue flushes the
 * moment connectivity returns, and a WorkManager job so retries survive the
 * app being killed entirely. Cellular is always allowed - footage needs to
 * leave the device as fast as possible.
 *
 * Storage-backend-agnostic: every transfer goes through whichever
 * [StorageProvider] is currently selected ([StorageProviders.current]), never
 * a hardcoded backend. What used to be Drive-only calls now ask the active
 * provider for a folder ID, an upload, or a token, so switching accounts in
 * settings changes where the next chunk actually lands.
 *
 * Local chunk files are deleted only after the backend confirms receipt.
 * Nothing is ever discarded before then, including while the user is signed
 * out.
 */
object UploadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards every piece of coordination state below. */
    private val stateLock = Mutex()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * How many full-quality chunks may be in flight at once.
     *
     * One, not several. This used to be unbounded: every claimable chunk got a
     * transfer immediately, so nine 50 MB files shared one uplink and all sat
     * at 30%. Aggregate throughput is unchanged by that - the link is the
     * limit either way - but *when* each file lands is not, and here only
     * landed files count. Split nine ways, nothing is safe until nearly the
     * end; taken in order, one is safe every minute or so. A phone taken
     * mid-queue is the case this app exists for, and a half-uploaded file left
     * on the server is worth nothing.
     *
     * Order is not strictly serial even so: a chunk that fails goes to the
     * back on a backoff and the next one proceeds, rather than the queue
     * stopping dead on one file a server keeps rejecting.
     */
    const val MAX_CONCURRENT_CHUNK_UPLOADS = 1

    /**
     * Stand-ins get their own slot, alongside the full-quality file rather
     * than behind it.
     *
     * Priority in the queue was not enough on its own. A stand-in is only made
     * once uploads are visibly losing ground, by which time a 60 MB master is
     * usually already transferring - and with one slot for everything, a 5 MB
     * stand-in whose entire purpose is to be safe within seconds sat behind
     * minutes of a file it exists to substitute for.
     *
     * A second lane rather than cancelling that master: the bytes it has
     * already sent cannot be recovered on every backend - a WebDAV PUT has no
     * resume at all - so pre-empting would throw away real progress on exactly
     * the failing connection that prompted the stand-in.
     */
    const val MAX_CONCURRENT_STANDIN_UPLOADS = 1

    /** The outstanding queue, in the order it will actually go. */
    private val _queued = MutableStateFlow<List<QueuedChunk>>(emptyList())
    val queued: StateFlow<List<QueuedChunk>> = _queued.asStateFlow()

    /** What the most recent recording has actually landed; see [UploadReceipts]. */
    private val _lastUpload = MutableStateFlow<SessionUploadReceipt?>(null)
    val lastUpload: StateFlow<SessionUploadReceipt?> = _lastUpload.asStateFlow()

    /**
     * Fraction uploaded, 0..1, for chunks currently in flight. Absent means
     * the chunk has not started, and it is removed once the transfer ends, so
     * the map never grows with finished work.
     */
    private val _chunkProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val chunkProgress: StateFlow<Map<String, Float>> = _chunkProgress.asStateFlow()

    /**
     * Chunk ids with a transfer running right now.
     *
     * Unlike the iOS build, which has to ask a background `URLSession` what it
     * is holding, every transfer here is a coroutine this object started, so
     * the authoritative answer is simply what it put in this set. A process
     * death clears it, which is correct: [ChunkStore] resets `UPLOADING` back
     * to `QUEUED` on load, so nothing is left stranded.
     */
    private val inFlight = mutableSetOf<String>()

    // Session-manifest state. The manifest is rewritten as chunks arrive and
    // PATCHed over the same remote file, so versions track what the backend
    // has actually confirmed.
    private val manifestVersion = mutableMapOf<String, Int>()
    private val manifestConfirmedVersion = mutableMapOf<String, Int>()
    private val manifestUploadingVersion = mutableMapOf<String, Int>()
    private val manifestInFlight = mutableSetOf<String>()
    private val manifestFinalized = mutableSetOf<String>()

    // Backend-stitch state: once every chunk of a camera in a session is
    // confirmed uploaded, its merged video (built by SessionStitcher) is
    // uploaded, and - once every registered camera's merge is confirmed - the
    // small chunks it superseded are deleted remotely and the manifest is
    // rewritten to document the merge.
    private data class MergedUploadEntry(
        val file: File,
        var remoteFileID: String? = null,
        var sha256: String? = null,
        var confirmed: Boolean = false,
    )

    private val mergedUploads = mutableMapOf<String, MutableMap<String, MergedUploadEntry>>()
    private val mergedInFlight = mutableSetOf<String>()
    private val mergedFinishInFlight = mutableSetOf<String>()

    @Volatile private var networkAvailable = true
    private var retryJob: Job? = null
    @Volatile private var started = false

    private const val MANIFEST_REMOTE_ID_KEY = "sessionManifestFileIDs"
    private const val SESSION_SHARES_KEY = "sessionSharesGranted"

    class UploadException(message: String) : IOException(message)

    /**
     * The active backend, off the main actor. Everything below treats a null
     * result the same as "not signed in": chunks stay queued and retry later
     * rather than being dropped.
     */
    private fun requireProvider(): StorageProvider =
        StorageProviders.current ?: throw UploadException("No storage account is connected")

    /**
     * A cache key scoped to both the session and the backend that resolved
     * it. A session's remote folder ID (or Dropbox/WebDAV path) is only
     * meaningful against the provider that created it - reusing it after the
     * user switches accounts would either fail outright or, worse, silently
     * write into the wrong account.
     */
    private fun scopedKey(provider: StorageProvider, sessionID: String) = "${provider.id.wire}:$sessionID"

    // MARK: - Startup

    @Synchronized
    private fun startIfNeeded() {
        // Reached from the capture thread, the UI, and WorkManager alike;
        // registering the network callback twice would leak a listener and
        // double every pump.
        if (started) return
        started = true

        SessionStitcher.onSessionMerged = { sessionID, merges ->
            registerMergedVideos(sessionID, merges)
        }
        observeNetwork()
        scope.launch {
            restorePendingManifests()
            refreshPendingCount()
        }
    }

    private fun observeNetwork() {
        val connectivity = WitnessApplication.appContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching {
            connectivity.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val cameBack = !networkAvailable
                    networkAvailable = true
                    if (cameBack) {
                        EventLogger.log("network.restored")
                        pump()
                    }
                }

                override fun onLost(network: Network) {
                    networkAvailable = connectivity.activeNetwork != null
                }
            })
        }
    }

    // MARK: - Intake

    /** Entry point from the capture layer: a chunk file is finalized on disk. */
    fun ingest(chunk: CaptureSessionController.FinalizedChunk) {
        startIfNeeded()
        scope.launch {
            var record = ChunkRecord(
                id = UUID.randomUUID().toString(),
                fileName = chunk.file.name,
                sessionID = chunk.sessionID,
                index = chunk.index,
                startedAt = chunk.startedAt,
                endedAt = chunk.endedAt,
                camera = chunk.camera,
                latitude = chunk.location?.latitude,
                longitude = chunk.location?.longitude,
                horizontalAccuracy = chunk.location?.accuracy?.toDouble(),
                byteCount = chunk.file.length().takeIf { it > 0 },
            )
            ChunkStore.add(record)

            // Hash before anything ships - the hash is what the manifest
            // attests to.
            val hash = runCatching { Hashing.sha256Hex(chunk.file) }.getOrNull()
            if (hash != null) {
                record = record.copy(sha256 = hash, state = ChunkState.QUEUED)
                ChunkStore.update(record)
                EventLogger.log(
                    "chunk.finalized",
                    detail = "${record.fileName} sha256=${hash.take(16)}...",
                    location = chunk.location,
                )
                // Keep the live manifest current so the backend always holds
                // custody records for everything captured so far.
                writeManifest(chunk.sessionID)
                attemptManifestUpload(chunk.sessionID)
            } else {
                // Keep it queued for a later hash retry rather than losing it.
                ChunkStore.update(record.copy(nextRetryAt = System.currentTimeMillis() + 10_000))
            }

            // Only once the pipeline is visibly losing ground - on a healthy
            // connection the original goes up directly and nothing else is
            // encoded or sent.
            if (hash != null && shouldSendLowResAhead()) {
                enqueueLowRes(master = record, location = chunk.location)
            }

            refreshPendingCount()
            pump()
        }
    }

    /**
     * Whether uploads are demonstrably failing to keep up.
     *
     * Measured from the pipeline's own behaviour rather than inferred from
     * the radio: "on cellular" says nothing useful, since 5G outruns plenty
     * of Wi-Fi, while a queue that keeps growing means precisely that
     * footage is accumulating on the device instead of leaving it.
     */
    private fun shouldSendLowResAhead(): Boolean {
        if (!AppSettings.lowResFallbackEnabled) return false
        // The mastering tier never uploads at all, so a stand-in would have
        // nowhere to go.
        if (AppSettings.isLocalOnlyCapture) return false

        val outstanding = ChunkStore.records { it.state != ChunkState.UPLOADED }
        // Three chunks outstanding is 45 seconds of footage that hasn't left
        // the device - past the point where the next one should wait its turn.
        if (outstanding.count { it.kind == ChunkKind.MASTER } >= 3) return true
        // Or uploads are actively failing rather than merely slow.
        return outstanding.any { it.attemptCount >= 2 }
    }

    /** Transcodes a small stand-in for [master] and queues it ahead of the full file. */
    private fun enqueueLowRes(master: ChunkRecord, location: android.location.Location?) {
        val lowResName = LowResEncoder.lowResFileName(master.fileName)
        val destination = File(ChunkStore.chunksDirectory, lowResName)
        val sourceFile = master.file

        scope.launch {
            val encoded = runCatching { LowResEncoder.encode(sourceFile, destination) }
                .onFailure { EventLogger.log("lowres.encode_failed", detail = it.message) }
                .isSuccess
            if (!encoded) return@launch

            // The master may already have landed while this was encoding, in
            // which case the stand-in is pointless.
            val current = ChunkStore.record(master.id)
            if (current == null || current.state == ChunkState.UPLOADED) {
                destination.delete()
                return@launch
            }

            var record = ChunkRecord(
                id = UUID.randomUUID().toString(),
                fileName = lowResName,
                sessionID = master.sessionID,
                index = master.index,
                startedAt = master.startedAt,
                endedAt = master.endedAt,
                camera = master.camera,
                latitude = master.latitude,
                longitude = master.longitude,
                horizontalAccuracy = master.horizontalAccuracy,
                kind = ChunkKind.LOW_RES,
                masterChunkID = master.id,
            )
            record = record.copy(sha256 = runCatching { Hashing.sha256Hex(destination) }.getOrNull())
            record = record.copy(state = ChunkState.QUEUED, byteCount = destination.length().takeIf { it > 0 })
            ChunkStore.add(record)
            EventLogger.log("lowres.queued", detail = lowResName, location = location)
            refreshPendingCount()
            pump()
        }
    }

    /**
     * Removes the stand-in for a chunk whose full-quality file is now
     * confirmed remotely.
     *
     * Ordered deliberately: the master is confirmed *before* the stand-in is
     * touched, so there is no instant at which neither copy is uploaded. If
     * the delete fails the folder merely keeps a redundant low-res file,
     * which is the harmless direction to fail in.
     */
    private fun supersedeLowRes(masterID: String) {
        val standIns = ChunkStore.records { it.kind == ChunkKind.LOW_RES && it.masterChunkID == masterID }
        for (standIn in standIns) retire(standIn)
    }

    /**
     * Disposes of one stand-in whose master has landed.
     *
     * A stand-in that is mid-upload is left completely alone. `UPLOADING` is
     * set when a chunk is claimed and stays set for the whole of
     * [startUpload] - the folder lookup, the resumable-session round trip, and
     * the transfer itself. Deleting its file inside that window pulls the file
     * out from under an active read.
     *
     * Nothing needs to happen now: whichever way that upload ends,
     * [retireStandInIfMasterLanded] supersedes it then, by which point the
     * file is no longer in anyone's hands.
     */
    private fun retire(standIn: ChunkRecord) {
        if (standIn.state == ChunkState.UPLOADING) return

        // Never started, or waiting to retry: no transfer exists to race, so
        // it can go straight away.
        if (standIn.state != ChunkState.UPLOADED) {
            ChunkStore.remove(standIn.id)
            return
        }
        val remoteFileID = standIn.driveFileID
        if (remoteFileID == null) {
            ChunkStore.remove(standIn.id)
            return
        }
        scope.launch {
            runCatching {
                val provider = requireProvider()
                val token = provider.freshAccessToken()
                provider.deleteFile(remoteFileID, token)
                EventLogger.log("lowres.superseded", detail = standIn.fileName)
                ChunkStore.remove(standIn.id)
            }.onFailure {
                EventLogger.log("lowres.supersede_failed", detail = it.message)
            }
        }
    }

    /**
     * The other half of the deferral above: a stand-in whose upload has just
     * ended, one way or the other, and whose master landed while it was in
     * flight. Nothing else would ever come back for it - [supersedeLowRes]
     * fires once, on the master's confirmation, and by then this one was
     * untouchable.
     */
    private fun retireStandInIfMasterLanded(id: String) {
        val standIn = ChunkStore.record(id) ?: return
        if (standIn.kind != ChunkKind.LOW_RES) return
        val masterID = standIn.masterChunkID ?: return
        if (ChunkStore.record(masterID)?.state != ChunkState.UPLOADED) return
        retire(standIn)
    }

    // MARK: - Recording lifecycle hooks

    fun recordingDidStart() {
        startIfNeeded()
        pump()
    }

    /** After a recording stops, also ship a snapshot of the event log. */
    fun recordingDidStop() {
        scope.launch {
            EventLogger.snapshotForUpload()?.let { snapshot ->
                uploadAuxiliaryFile(snapshot, "application/x-ndjson")
            }
        }
        pump()
    }

    // MARK: - Session manifest

    fun manifestFileName(sessionID: String) = "manifest_$sessionID.json"

    fun manifestFile(sessionID: String) = File(ChunkStore.chunksDirectory, manifestFileName(sessionID))

    /** Called once a recording session's chunks are all finalized. */
    fun finalizeSession(sessionID: String) {
        refreshSessionManifest(sessionID, finalize = true)
    }

    /**
     * Rewrites the session's manifest and pushes it to the backend,
     * overwriting the same remote file each time. The folder therefore holds
     * exactly one manifest per session, which stays current as the recording
     * proceeds - so custody records exist remotely even if the session is
     * never stopped normally (e.g. the phone is seized mid-encounter).
     */
    fun refreshSessionManifest(sessionID: String, finalize: Boolean = false) {
        startIfNeeded()
        scope.launch {
            writeManifest(sessionID)
            if (finalize) stateLock.withLock { manifestFinalized.add(sessionID) }
            attemptManifestUpload(sessionID)
        }
    }

    private suspend fun writeManifest(sessionID: String) {
        val records = ChunkStore.records(sessionID)
        if (records.isEmpty()) return

        val manifest = buildManifest(sessionID, records, mergedVideos = null, timestamped = null)
        val file = manifestFile(sessionID)
        val written = runCatching {
            file.writeText(SessionManifest.json.encodeToString(manifest))
        }.isSuccess
        if (!written) return

        // A newer revision exists locally than whatever the backend last
        // confirmed.
        stateLock.withLock {
            manifestVersion[sessionID] = (manifestVersion[sessionID] ?: 0) + 1
        }
        EventLogger.log("manifest.written", detail = "$sessionID chunks=${records.size}")
    }

    private fun buildManifest(
        sessionID: String,
        records: List<ChunkRecord>,
        mergedVideos: List<SessionManifest.MergedVideo>?,
        timestamped: Triple<String, String, String>?,
    ) = SessionManifest(
        schemaVersion = 1,
        sessionID = sessionID,
        recordingStartedUTC = records.minOfOrNull { it.startedAt } ?: System.currentTimeMillis(),
        recordingEndedUTC = records.maxOfOrNull { it.endedAt } ?: System.currentTimeMillis(),
        deviceTimeZone = TimeZone.getDefault().id,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        systemVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        appVersion = BuildConfig.VERSION_NAME,
        // Counts what was filmed, so a stand-in doesn't read as an extra 15
        // seconds of footage that never existed.
        chunkCount = records.count { it.kind == ChunkKind.MASTER },
        chunks = records.map {
            SessionManifest.Chunk(
                index = it.index,
                camera = it.camera.wire,
                fileName = it.fileName,
                kind = if (it.kind == ChunkKind.LOW_RES) "lowRes" else "master",
                sha256 = it.sha256,
                captureStartUTC = it.startedAt,
                captureEndUTC = it.endedAt,
                latitude = it.latitude,
                longitude = it.longitude,
                horizontalAccuracyMeters = it.horizontalAccuracy,
            )
        },
        mergedVideos = mergedVideos,
        markers = MomentMarkers.between(
            records.minOfOrNull { it.startedAt } ?: System.currentTimeMillis(),
            records.maxOfOrNull { it.endedAt } ?: System.currentTimeMillis(),
        ).ifEmpty { null },
        crew = CrewStore.stamp(sessionID),
        timestampAuthority = timestamped?.first,
        timestampFileName = timestamped?.second,
        timestampVerification = timestamped?.third,
    )

    /**
     * Creates the manifest remotely the first time, then PATCHes the same
     * file on every later revision.
     */
    private suspend fun attemptManifestUpload(sessionID: String) {
        if (!networkAvailable) return
        val file = manifestFile(sessionID)
        if (!file.exists()) return

        val proceed = stateLock.withLock {
            // Nothing new since the last confirmed upload.
            if ((manifestVersion[sessionID] ?: 0) <= (manifestConfirmedVersion[sessionID] ?: 0)) {
                return@withLock false
            }
            // One upload in flight at a time; a newer revision re-runs on
            // completion.
            if (manifestInFlight.contains(sessionID)) return@withLock false
            manifestInFlight.add(sessionID)
            manifestUploadingVersion[sessionID] = manifestVersion[sessionID] ?: 0
            true
        }
        if (!proceed) return

        val result = runCatching {
            val provider = requireProvider()
            val token = provider.freshAccessToken()
            val existingFileID = Prefs.getStringMap(MANIFEST_REMOTE_ID_KEY)[scopedKey(provider, sessionID)]
            if (existingFileID != null) {
                // Overwrite in place - same remote file, new contents.
                provider.updateFile(existingFileID, file, "application/json", token)
            } else {
                val folderID = resolveSessionFolder(sessionID, provider, token, fallback = null)
                shareSessionFolderIfNeeded(sessionID, provider, folderID, token)
                provider.multipartUpload(file, file.name, "application/json", folderID, token)
            }
        }
        handleManifestCompletion(sessionID, result.getOrNull())
    }

    private suspend fun handleManifestCompletion(sessionID: String, remoteFileID: String?) {
        var runStitchCheck = false

        stateLock.withLock {
            manifestInFlight.remove(sessionID)

            if (remoteFileID != null) {
                StorageProviders.current?.let { provider ->
                    val map = Prefs.getStringMap(MANIFEST_REMOTE_ID_KEY).toMutableMap()
                    map[scopedKey(provider, sessionID)] = remoteFileID
                    Prefs.setStringMap(MANIFEST_REMOTE_ID_KEY, map)
                }

                manifestConfirmedVersion[sessionID] = manifestUploadingVersion[sessionID] ?: 0
                EventLogger.log("manifest.confirmed", detail = sessionID)

                // Session is over and the backend holds the final revision -
                // done.
                if (manifestFinalized.contains(sessionID) &&
                    (manifestConfirmedVersion[sessionID] ?: 0) >= (manifestVersion[sessionID] ?: 0)
                ) {
                    manifestFile(sessionID).delete()
                    manifestFinalized.remove(sessionID)
                    manifestVersion.remove(sessionID)
                    manifestConfirmedVersion.remove(sessionID)
                    manifestUploadingVersion.remove(sessionID)
                    // The manifest settling does not by itself mean the
                    // footage landed: it is rewritten as chunks are
                    // *ingested*, so its last revision can confirm while
                    // chunks are still uploading - far more easily now that
                    // they go one at a time. Checked explicitly, so the
                    // prompt follows the moment the app demonstrably did its
                    // job rather than a moment that merely tends to follow it.
                    //
                    // Only *earns* a prompt; the Home screen decides whether
                    // to ask, so nothing here can surface UI mid-recording.
                    if (ChunkStore.records(sessionID).all { it.state == ChunkState.UPLOADED }) {
                        ReviewPrompt.sessionFullyPreserved()
                    }
                    // The stitch pipeline waits for the ordinary manifest to
                    // settle before consolidating chunks, so it can only
                    // start now.
                    runStitchCheck = true
                }
            }
        }

        if (runStitchCheck) {
            checkAndUploadMergedIfReady(sessionID)
            return
        }
        // Either it failed, or newer content landed while this was in flight.
        if (remoteFileID == null) return // retried by a later pump()
        attemptManifestUpload(sessionID)
    }

    /**
     * Re-queues manifests left on disk by a previous launch (e.g. the process
     * was killed before the final revision reached the backend).
     */
    private suspend fun restorePendingManifests() {
        val files = ChunkStore.chunksDirectory.listFiles()
            ?.filter { it.name.startsWith("manifest_") && it.extension == "json" }
            .orEmpty()
        stateLock.withLock {
            for (file in files) {
                val sessionID = file.nameWithoutExtension.removePrefix("manifest_")
                // Unknown whether the backend has this revision - treat it as
                // pending.
                manifestVersion[sessionID] = (manifestVersion[sessionID] ?: 0) + 1
                manifestFinalized.add(sessionID)
            }
        }
    }

    // MARK: - Backend stitch (merged session video)

    /**
     * One distinct name per kind of video in a session's folder. The upload
     * first looks for a file of this name and, finding one, counts the work
     * as already done - so two videos sharing a name never both reach the
     * cloud: the second is silently taken for the first.
     */
    fun mergedRemoteFileName(camera: String) = when (camera) {
        "back" -> "recording_rear.mp4"
        "front" -> "recording_front.mp4"
        "pip" -> "recording_picture_in_picture.mp4"
        "highlights" -> "recording_highlights.mp4"
        "blurred" -> "recording_faces_plates_blurred.mp4"
        "blurred_front" -> "recording_front_faces_plates_blurred.mp4"
        else -> "recording_$camera.mp4"
    }

    /**
     * Called by [SessionStitcher] once a camera's chunks are merged into one
     * video - either right after a session ends, or at launch recovering a
     * merge a prior app lifetime didn't finish uploading.
     */
    fun registerMergedVideos(sessionID: String, merges: List<SessionStitcher.Merge>) {
        scope.launch {
            if (!AppSettings.driveStitchEnabled) {
                // Feature is off (possibly toggled off since these were made
                // for) - the caller has no further use for them.
                merges.forEach { it.file.delete() }
                return@launch
            }
            stateLock.withLock {
                val entries = mergedUploads.getOrPut(sessionID) { mutableMapOf() }
                for (merge in merges) {
                    if (entries[merge.camera] == null) {
                        entries[merge.camera] = MergedUploadEntry(merge.file)
                    }
                }
            }
            checkAndUploadMergedIfReady(sessionID)
        }
    }

    /**
     * Starts uploading any not-yet-confirmed registered merges for a session,
     * once it's safe to: every one of its chunks is individually confirmed
     * remotely (or already superseded by an earlier, partial run), *and* the
     * ordinary per-chunk manifest has finished its own upload - avoiding a
     * race where both write the manifest at once.
     */
    private suspend fun checkAndUploadMergedIfReady(sessionID: String) {
        if (!AppSettings.driveStitchEnabled) return

        val cameras = stateLock.withLock {
            val entries = mergedUploads[sessionID] ?: return@withLock emptyList()
            if (entries.isEmpty()) return@withLock emptyList()
            if (manifestFinalized.contains(sessionID)) return@withLock emptyList()
            entries.filterValues { !it.confirmed }.keys.toList()
        }
        if (cameras.isEmpty()) return

        val records = ChunkStore.records(sessionID)
        if (records.isNotEmpty() && !records.all { it.state == ChunkState.UPLOADED }) return

        for (camera in cameras) attemptMergedUpload(sessionID, camera)
    }

    private suspend fun attemptMergedUpload(sessionID: String, camera: String) {
        if (!networkAvailable) return
        val key = "$sessionID:$camera"

        val entry = stateLock.withLock {
            val candidate = mergedUploads[sessionID]?.get(camera) ?: return@withLock null
            if (candidate.confirmed) return@withLock null
            if (mergedInFlight.contains(key)) return@withLock null
            if (!candidate.file.exists()) {
                // Should not happen - drop this camera's entry rather than
                // retry forever.
                mergedUploads[sessionID]?.remove(camera)
                return@withLock null
            }
            mergedInFlight.add(key)
            candidate
        } ?: return

        // Hashed outside the lock - a multi-minute merged video is far larger
        // than a single chunk, so hashing it must not stall everything else.
        val hash = runCatching { Hashing.sha256Hex(entry.file) }.getOrNull()

        val remoteFileID = runCatching {
            val provider = requireProvider()
            val token = provider.freshAccessToken()
            val folderID = resolveSessionFolder(sessionID, provider, token, fallback = null)
            val fileName = mergedRemoteFileName(camera)

            // Idempotent: a prior attempt may have already uploaded this
            // (e.g. the process died between confirmation and local
            // cleanup) - reuse it instead of creating a duplicate.
            provider.findFile(fileName, folderID, token) ?: run {
                val sessionURI = provider.beginResumableSession(
                    fileName, "video/mp4", entry.file.length(), folderID, token
                )
                provider.uploadResumable(sessionURI, entry.file, "video/mp4", alreadyStarted = false, token = token)
            }
        }.getOrNull()

        stateLock.withLock {
            mergedUploads[sessionID]?.get(camera)?.sha256 = hash
            mergedInFlight.remove(key)
        }
        handleMergedUploadCompletion(sessionID, camera, remoteFileID)
    }

    private suspend fun handleMergedUploadCompletion(sessionID: String, camera: String, remoteFileID: String?) {
        if (remoteFileID == null) {
            EventLogger.log("merged.upload_failed", detail = "$sessionID $camera")
            return // retried by a later pump()
        }

        val entries = stateLock.withLock {
            mergedUploads[sessionID]?.get(camera)?.apply {
                this.remoteFileID = remoteFileID
                this.confirmed = true
            }
            val all = mergedUploads[sessionID]
            if (all != null && all.isNotEmpty() && all.values.all { it.confirmed }) all.toMap() else null
        }

        EventLogger.log("merged.upload_confirmed", detail = "$sessionID $camera remoteID=$remoteFileID")
        if (entries != null) finishDriveStitch(sessionID, entries)
    }

    /**
     * Once every registered camera's merged video is confirmed: delete the
     * small chunks it superseded remotely (only for cameras that actually got
     * a confirmed merge - a camera whose merge failed keeps its chunks, so
     * nothing is ever lost), write the manifest's final revision documenting
     * the merge - anchored with an independent time-stamp proof and archived
     * locally for good - then clean up locally. Idempotent and safe to
     * re-invoke if an earlier attempt didn't finish.
     */
    private suspend fun finishDriveStitch(sessionID: String, entries: Map<String, MergedUploadEntry>) {
        val proceed = stateLock.withLock {
            if (mergedFinishInFlight.contains(sessionID)) return@withLock false
            mergedFinishInFlight.add(sessionID)
            true
        }
        if (!proceed) return

        // Captured before any deletion, so the final manifest (and the delete
        // list) reflect exactly what existed at this point.
        val records = ChunkStore.records(sessionID)
        val toDelete = records.filter { entries.containsKey(it.camera.wire) }

        val ok = runCatching {
            val provider = requireProvider()
            val token = provider.freshAccessToken()
            for (fileID in toDelete.mapNotNull { it.driveFileID }) {
                provider.deleteFile(fileID, token)
            }
            uploadFinalManifest(sessionID, records, entries, provider, token)
            true
        }.getOrDefault(false)

        stateLock.withLock { mergedFinishInFlight.remove(sessionID) }
        if (!ok) return // retried by a later pump()

        for (camera in entries.keys) {
            CameraSource.fromWire(camera)?.let { ChunkStore.removeAll(sessionID, it) }
        }
        entries.values.forEach { it.file.delete() }
        entries.values.firstOrNull()?.file?.parentFile?.deleteRecursively()
        stateLock.withLock { mergedUploads.remove(sessionID) }

        records.firstOrNull()?.let { first ->
            // Only the real per-camera videos count: the extra cuts are
            // additions to a session, not parts of it.
            UploadReceipts.recordMerged(
                sessionID, first.startedAt, entries.keys.count { CameraSource.fromWire(it) != null }
            )
        }
        EventLogger.log(
            "drive.session_consolidated",
            detail = "$sessionID chunks_removed=${toDelete.size} merged=${entries.size}"
        )
        refreshPendingCount()
    }

    /**
     * Writes the session's definitive final manifest - the ordinary chunk
     * list (from the snapshot taken before any deletion) plus the
     * merged-video entries - directly to the backend. A one-shot upload
     * rather than going through the versioned PATCH cycle above: by this
     * point that cycle has already finished and torn down its own state, so
     * this is the one remaining write for this session's manifest.
     *
     * Before it goes anywhere, its SHA-256 is counter-signed by an
     * independent RFC 3161 time-stamp authority and the exact bytes plus
     * proof are archived locally for good - the manifest is deleted from the
     * chunks directory once uploaded, and the archive is the only place this
     * session's chain-of-custody record survives from then on.
     */
    private suspend fun uploadFinalManifest(
        sessionID: String,
        records: List<ChunkRecord>,
        entries: Map<String, MergedUploadEntry>,
        provider: StorageProvider,
        token: String,
    ) {
        // Named before hashing, so the manifest that gets anchored is the
        // same one that tells a reader where the proof is and how to check
        // it. The digest is taken over the final bytes below.
        val timestampName = timestampFileName(sessionID)
        val manifest = buildManifest(
            sessionID,
            records,
            // The picture-in-picture and highlight cuts are listed under their
            // own names, so a reader can see exactly what each file is - a
            // re-encoded composite and a trimmed cut are not the same footage
            // as the two lossless per-camera videos, and this document is
            // where that distinction has to be precise.
            mergedVideos = entries.map { (camera, entry) ->
                SessionManifest.MergedVideo(camera, mergedRemoteFileName(camera), entry.sha256)
            },
            timestamped = Triple(
                TimestampService.DEFAULT_AUTHORITY.toString(),
                timestampName,
                SessionManifest.VERIFICATION_INSTRUCTIONS,
            ),
        )
        val data = SessionManifest.json.encodeToString(manifest).toByteArray()

        // Best-effort: an unreachable authority must never cost the user the
        // manifest itself. The proof is an addition to the evidence, not a
        // precondition for keeping it.
        val timestampToken = runCatching {
            TimestampService.token(MessageDigest.getInstance("SHA-256").digest(data))
        }.onFailure {
            EventLogger.log("timestamp.failed", detail = it.message)
        }.getOrNull()

        // Kept locally for good: these exact bytes are what the proof covers,
        // and everything else about this session is about to be cleaned up.
        SessionArchive.store(sessionID, manifestData = data, timestampToken = timestampToken)

        val temp = File.createTempFile("manifest-final-", ".json", WitnessApplication.appContext.cacheDir)
        try {
            temp.writeBytes(data)
            val existingID = Prefs.getStringMap(MANIFEST_REMOTE_ID_KEY)[scopedKey(provider, sessionID)]
            if (existingID != null) {
                provider.updateFile(existingID, temp, "application/json", token)
            } else {
                val folderID = resolveSessionFolder(sessionID, provider, token, fallback = null)
                provider.multipartUpload(temp, manifestFileName(sessionID), "application/json", folderID, token)
            }
        } finally {
            temp.delete()
        }

        // Uploaded after the manifest it covers, so the folder is never left
        // holding a timestamp for a file that isn't there.
        if (timestampToken != null) {
            val proof = File.createTempFile("ts-", ".tsr", WitnessApplication.appContext.cacheDir)
            try {
                proof.writeBytes(timestampToken)
                val folderID = resolveSessionFolder(sessionID, provider, token, fallback = null)
                provider.multipartUpload(proof, timestampName, "application/timestamp-reply", folderID, token)
                EventLogger.log("timestamp.anchored", detail = timestampName)
            } catch (error: Throwable) {
                EventLogger.log("timestamp.anchor_upload_failed", detail = error.message)
            } finally {
                proof.delete()
            }
        }

        manifestFile(sessionID).delete()
    }

    fun timestampFileName(sessionID: String) = "timestamp_$sessionID.tsr"

    // MARK: - Queue pump

    /**
     * Starts uploads for every eligible chunk. Safe to call often - chunks are
     * claimed atomically, so overlapping calls never upload the same file
     * twice.
     */
    fun pump() {
        startIfNeeded()
        scope.launch { pumpNow() }
    }

    private suspend fun pumpNow() {
        if (!networkAvailable) return

        // Retry any manifest revision the backend hasn't confirmed yet.
        val pendingManifests = stateLock.withLock { manifestVersion.keys.toList() }
        for (sessionID in pendingManifests) attemptManifestUpload(sessionID)

        // Retry any stitch merge still waiting to upload, and any
        // fully-confirmed session whose chunk deletion/cleanup didn't finish.
        val mergeSessions = stateLock.withLock { mergedUploads.keys.toList() }
        for (sessionID in mergeSessions) checkAndUploadMergedIfReady(sessionID)
        val finished = stateLock.withLock {
            mergedUploads.filterValues { it.isNotEmpty() && it.values.all { e -> e.confirmed } }
                .mapValues { it.value.toMap() }
        }
        for ((sessionID, entries) in finished) finishDriveStitch(sessionID, entries)

        // Counted per kind: the two lanes fill independently, so a master in
        // flight must not close the stand-in lane.
        val live = stateLock.withLock { inFlight.toSet() }
        val running = ChunkStore.records { live.contains(it.id) }
        val limits = mapOf(
            ChunkKind.MASTER to MAX_CONCURRENT_CHUNK_UPLOADS -
                running.count { it.kind == ChunkKind.MASTER },
            ChunkKind.LOW_RES to MAX_CONCURRENT_STANDIN_UPLOADS -
                running.count { it.kind == ChunkKind.LOW_RES },
        )
        if (limits.values.none { it > 0 }) {
            scheduleRetryTimer()
            return
        }

        val claimed = ChunkStore.claimUploadable(limits = limits, inFlight = live)
        if (claimed.isEmpty()) {
            refreshPendingCount()
            scheduleRetryTimer()
            return
        }

        val provider = try {
            requireProvider()
        } catch (error: Throwable) {
            _lastError.value = error.message
            // Not a failed attempt - hand them back for a later pump.
            claimed.forEach { ChunkStore.release(it.id, retryAfterMillis = 30_000) }
            scheduleRetryTimer()
            return
        }
        val token = try {
            provider.freshAccessToken()
        } catch (error: Throwable) {
            _lastError.value = error.message
            claimed.forEach { ChunkStore.release(it.id, retryAfterMillis = 30_000) }
            scheduleRetryTimer()
            return
        }

        // Resolved once for the batch. A backend that refuses the root folder
        // fails every chunk before any of them is attempted, so the queue
        // would otherwise show "retrying, after 0 attempts" and nothing else,
        // for as long as the user left it - reported from a WebDAV account
        // whose server answered folder creation with a 403 and was never
        // named as the cause.
        try {
            provider.ensureRootFolder(AppSettings.driveFolderName, token)
        } catch (error: Throwable) {
            _lastError.value = error.message
            EventLogger.log(
                "upload.root_folder_failed",
                detail = "${AppSettings.driveFolderName}: ${error.message}",
            )
            claimed.forEach { ChunkStore.release(it.id, retryAfterMillis = 30_000) }
            scheduleRetryTimer()
            return
        }

        // Both steps above that can set lastError just succeeded - clear a
        // stale one now rather than leaving it to describe a problem that
        // stopped being true. Without this, a fixed account kept showing its
        // old failure on the one screen someone would check to see whether
        // the fix had worked, until some other chunk happened to confirm.
        _lastError.value = null

        // Each claimed chunk gets its own coroutine so the two lanes actually
        // run alongside each other: a stand-in exists to be safe within
        // seconds and must not wait out the master it stands in for.
        for (record in claimed) {
            stateLock.withLock { inFlight.add(record.id) }
            scope.launch {
                try {
                    // A folder that cannot be resolved is this chunk's problem
                    // alone: it goes back on the queue with a backoff while
                    // the rest of the batch carries on.
                    val folderID = try {
                        resolveSessionFolder(record.sessionID, provider, token, record)
                    } catch (error: Throwable) {
                        fail(record.id, error.message ?: "Storage folder unavailable")
                        null
                    }
                    if (folderID != null) {
                        // Footage is reaching the backend - make sure emergency
                        // contacts can see it as it arrives.
                        CrewStore.noteSessionFolder(record.sessionID, provider.id.wire, folderID)
                        shareSessionFolderIfNeeded(record.sessionID, provider, folderID, token)
                        startUpload(record, provider, token, folderID)
                    }
                } finally {
                    stateLock.withLock { inFlight.remove(record.id) }
                    clearProgress(record.id)
                    // Re-pumped as each one finishes, since the lane it was
                    // holding is now free and nothing else would come back for
                    // whatever is next.
                    pump()
                }
            }
        }
        refreshPendingCount()
        scheduleRetryTimer()
    }

    private fun clearProgress(id: String) {
        _chunkProgress.update { it - id }
    }

    private suspend fun startUpload(claimed: ChunkRecord, provider: StorageProvider, token: String, folderID: String) {
        var record = claimed

        if (record.sha256 == null) {
            // Hash failed earlier - retry it now.
            val hash = runCatching { Hashing.sha256Hex(record.file) }.getOrNull()
            if (hash == null) {
                // Put it back in the queue; leaving it UPLOADING would strand
                // it until the next launch.
                ChunkStore.release(record.id, retryAfterMillis = 30_000)
                return
            }
            record = record.copy(sha256 = hash)
            ChunkStore.update(record)
        }

        if (!record.file.exists() || record.file.length() == 0L) {
            // File vanished (should not happen) - log and drop the record.
            EventLogger.log("upload.file_missing", detail = record.fileName)
            ChunkStore.markUploaded(record.id, driveFileID = null)
            refreshPendingCount()
            return
        }

        try {
            val existingURI = record.resumableSessionURI
            val sessionURI = existingURI ?: provider.beginResumableSession(
                record.fileName, "video/mp4", record.file.length(), folderID, token
            )
            if (existingURI == null) {
                record = record.copy(resumableSessionURI = sessionURI)
                ChunkStore.update(record)
            }

            // Weighed before the upload, which deletes the file on
            // confirmation - afterwards there is nothing left to measure.
            val deliveredBytes = record.byteCount ?: record.file.length()

            val remoteFileID = provider.uploadResumable(
                sessionURI = sessionURI, file = record.file, mimeType = "video/mp4",
                alreadyStarted = existingURI != null, token = token,
                onProgress = { sent, total ->
                    if (total > 0) {
                        // `update`, not a read-then-write of `.value`: the
                        // master and its stand-in upload on separate lanes and
                        // report from separate threads, so a plain assignment
                        // would sometimes drop one of the two entries.
                        _chunkProgress.update {
                            it + (record.id to (sent.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                },
            )

            // Confirmed remotely -> safe to delete the local copy. The
            // chunk's hash lives on in the session manifest.
            UploadReceipts.recordConfirmed(
                sessionID = record.sessionID, startedAt = record.startedAt,
                kind = record.kind, bytes = deliveredBytes,
            )
            ChunkStore.markUploaded(record.id, remoteFileID)
            EventLogger.log("upload.chunk_confirmed", detail = "${record.fileName} remoteID=$remoteFileID")
            _lastError.value = null
            clearProgress(record.id)
            refreshPendingCount()
            if (record.kind == ChunkKind.MASTER) {
                // The full file has landed, so its stand-in is now redundant.
                supersedeLowRes(record.id)
            } else {
                // Or this *is* a stand-in, finishing after the master it
                // stands in for. It was skipped while in flight; now it can go.
                retireStandInIfMasterLanded(record.id)
            }
            checkAndUploadMergedIfReady(record.sessionID)
        } catch (error: Throwable) {
            clearProgress(record.id)
            fail(record.id, error.message ?: "upload failed")
            // Its master may have landed while this was in flight, in which
            // case retrying it would upload something already redundant - and
            // supersedeLowRes has been and gone.
            retireStandInIfMasterLanded(record.id)
        }
    }

    // MARK: - Emergency-contact sharing

    /**
     * Grants every emergency contact (with a valid email) read access to the
     * session's folder, on backends that support it. Grants are recorded per
     * session+email, so this is safe to call on every pump: already-granted
     * contacts are skipped, failed grants retry, and a contact added
     * mid-recording still gets access.
     */
    private suspend fun shareSessionFolderIfNeeded(
        sessionID: String, provider: StorageProvider, folderID: String, token: String,
    ) {
        if (!provider.supportsEmailSharing) return
        // Emergency contacts, plus the rest of the crew when this was a crew
        // recording, so every member can reach every angle.
        val eligible = (
            EmergencyContact.load(WitnessApplication.appContext).filter { it.canReceiveFootage }
                .mapNotNull { it.email } + CrewStore.shareEmails(sessionID)
            ).distinct()
        if (eligible.isEmpty()) return

        val grantedMap = Prefs.getStringListMap(SESSION_SHARES_KEY).toMutableMap()
        val granted = grantedMap[sessionID].orEmpty().toMutableSet()

        for (email in eligible) {
            if (granted.contains(email)) continue
            runCatching { provider.share(folderID, email, token) }
                .onSuccess {
                    granted.add(email)
                    grantedMap[sessionID] = granted.toList()
                    Prefs.setStringListMap(SESSION_SHARES_KEY, grantedMap)
                    EventLogger.log("share.granted", detail = "$sessionID -> $email")
                }
                .onFailure {
                    // Not granted - retried on the next pump.
                    EventLogger.log("share.failed", detail = "$sessionID -> $email: ${it.message}")
                }
        }
    }

    /**
     * Shares this phone's crew recordings with members whose email arrived
     * after the footage had finished uploading. Sharing otherwise happens only
     * as each piece uploads, so a member learned later would never get access.
     * Already-granted addresses are skipped, so this is safe to repeat.
     */
    fun reshareCrewSessions(crewID: String) {
        scope.launch {
            val provider = runCatching { requireProvider() }.getOrNull() ?: return@launch
            val token = runCatching { provider.freshAccessToken() }.getOrNull() ?: return@launch
            for (sessionID in CrewStore.sessions(crewID)) {
                val (providerWire, folderID) = CrewStore.folder(sessionID) ?: continue
                if (providerWire != provider.id.wire) continue
                shareSessionFolderIfNeeded(sessionID, provider, folderID, token)
            }
        }
    }

    // MARK: - Uploading a gallery-only recording later

    /** A video copied out of the gallery for upload, deleted by the caller afterwards. */
    class BackfillVideo(val fileName: String, val mimeType: String, val file: File)

    /**
     * Sends a recording that was kept only in the gallery to the folder it
     * would have had: its videos, then its manifest, then the manifest's proof
     * - the same order a live session ends in, so the folder never holds a
     * proof for a file that isn't there. Videos already in the folder are
     * skipped, so running it again after an interruption only sends what is
     * missing.
     */
    suspend fun uploadLibraryRecording(
        sessionID: String,
        manifest: SessionManifest,
        videos: List<BackfillVideo>,
        manifestFile: File,
        timestampFile: File?,
    ) {
        val provider = requireProvider()
        val token = provider.freshAccessToken()
        val rootName = Prefs.getString(AppSettings.driveFolderNameKey, AppSettings.defaultDriveFolderName)
            ?: AppSettings.defaultDriveFolderName
        val rootID = provider.ensureRootFolder(rootName, token)
        // Named for when and where the recording started, which only the
        // manifest knows: none of its pieces were ever queued.
        val located = manifest.chunks.firstOrNull { it.latitude != null }
        val folderName = SessionFolderNaming.folderName(
            sessionID, manifest.recordingStartedUTC, located?.latitude, located?.longitude,
        )
        val folderID = provider.ensureSubfolder(folderName, rootID, token)
        CrewStore.noteSessionFolder(sessionID, provider.id.wire, folderID)
        shareSessionFolderIfNeeded(sessionID, provider, folderID, token)

        for (video in videos) {
            if (provider.findFile(video.fileName, folderID, token) != null) continue
            val sessionURI = provider.beginResumableSession(
                video.fileName, video.mimeType, video.file.length(), folderID, token,
            )
            provider.uploadResumable(sessionURI, video.file, video.mimeType, alreadyStarted = false, token = token)
            EventLogger.log("backfill.video_confirmed", detail = "$sessionID ${video.fileName}")
        }

        val extras = listOfNotNull(
            Triple(manifestFile, manifestFileName(sessionID), "application/json"),
            timestampFile?.let { Triple(it, timestampFileName(sessionID), "application/timestamp-reply") },
        )
        for ((file, name, mime) in extras) {
            if (provider.findFile(name, folderID, token) != null) continue
            provider.multipartUpload(file, name, mime, folderID, token)
        }
        EventLogger.log("backfill.session_uploaded", detail = sessionID)
    }

    // MARK: - Folder resolution

    /**
     * Resolves the session subfolder for a recording, named for where and
     * when it started. Resolved once per session+provider and cached
     * (persisted across relaunches), so every chunk and the manifest land in
     * the same folder.
     *
     * Self-healing: if the backend reports the parent folder doesn't exist -
     * most often because the user manually deleted it - the stale root and
     * session cache entries are evicted and resolution is retried once
     * against a freshly created folder, instead of failing forever on the
     * same dead ID.
     */
    private suspend fun resolveSessionFolder(
        sessionID: String, provider: StorageProvider, token: String, fallback: ChunkRecord?,
    ): String {
        val folderName = AppSettings.driveFolderName
        return try {
            val rootID = provider.ensureRootFolder(folderName, token)
            sessionFolder(sessionID, provider, rootID, token, fallback)
        } catch (error: GoogleDriveClient.DriveException) {
            if (!error.isNotFound) throw error
            DriveFolderCache.sharedRoot.evict("root")
            DriveFolderCache.shared.evict(scopedKey(provider, sessionID))
            val freshRootID = provider.ensureRootFolder(folderName, token)
            sessionFolder(sessionID, provider, freshRootID, token, fallback)
        }
    }

    private suspend fun sessionFolder(
        sessionID: String, provider: StorageProvider, rootID: String, token: String, fallback: ChunkRecord?,
    ): String = DriveFolderCache.shared.folderID(scopedKey(provider, sessionID)) {
        // Name from the session's *first* chunk, not whichever uploads first
        // - otherwise each chunk would name its own folder.
        val origin = ChunkStore.sessionOrigin(sessionID)
        val date = origin?.date ?: fallback?.startedAt ?: System.currentTimeMillis()
        var latitude = origin?.latitude ?: fallback?.latitude
        var longitude = origin?.longitude ?: fallback?.longitude
        // Still none: use the phone's current fix if it is from around the
        // recording, as the folder is made while it is under way.
        if (latitude == null) {
            LocationService.lastLocation
                ?.takeIf { kotlin.math.abs(it.time - date) < 600_000 }
                ?.let {
                    latitude = it.latitude
                    longitude = it.longitude
                }
        }
        val name = SessionFolderNaming.folderName(
            sessionID = sessionID,
            date = date,
            latitude = latitude,
            longitude = longitude,
        )
        provider.ensureSubfolder(name, rootID, token)
    }

    /**
     * Uploads a standalone file (event-log snapshot) into the app's root
     * folder. Best-effort; deleted locally only on confirmed receipt.
     */
    private suspend fun uploadAuxiliaryFile(file: File, mimeType: String) {
        runCatching {
            val provider = requireProvider()
            val token = provider.freshAccessToken()
            val folderID = provider.ensureRootFolder(AppSettings.driveFolderName, token)
            provider.multipartUpload(file, file.name, mimeType, folderID, token)
        }.onSuccess {
            file.delete()
        }
        // On failure it stays on disk; retried on a later pump or next launch.
    }

    // MARK: - Retry / backoff

    private suspend fun fail(recordID: String, message: String) {
        val record = ChunkStore.record(recordID) ?: return
        val attemptCount = record.attemptCount + 1
        val delayMillis = min(300_000.0, 5_000.0 * 2.0.pow(attemptCount - 1)).toLong()

        ChunkStore.update(
            record.copy(
                attemptCount = attemptCount,
                state = ChunkState.QUEUED,
                // Re-initiate the resumable session next attempt only after
                // repeated failure; a transient network drop should resume,
                // not restart a nearly-complete upload.
                resumableSessionURI = if (attemptCount >= 3) null else record.resumableSessionURI,
                nextRetryAt = System.currentTimeMillis() + delayMillis,
                lastErrorMessage = message,
            )
        )
        EventLogger.log(
            "upload.failed",
            detail = "${record.fileName} attempt=$attemptCount retryIn=${delayMillis / 1000}s: $message"
        )
        _lastError.value = message
        scheduleRetryTimer()
    }

    private fun scheduleRetryTimer() {
        val next = ChunkStore.earliestRetryDate ?: return
        val delayMillis = (next - System.currentTimeMillis()).coerceAtLeast(1_000L)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMillis)
            pumpNow()
        }
        // A coroutine dies with the process; WorkManager is what makes the
        // retry survive the app being killed outright mid-encounter.
        UploadWorker.enqueueRetry(WitnessApplication.appContext, delayMillis)
    }

    fun refreshPendingCount() {
        _pendingCount.value = ChunkStore.pendingCount
        _queued.value = queueSnapshot()
        _lastUpload.value = UploadReceipts.mostRecent()
    }

    /**
     * The outstanding queue, flattened for display.
     *
     * Built here rather than read from [ChunkStore] by the screen, so the UI
     * never touches the store while the upload scope is mutating it. Sorted by
     * the same comparator the uploader claims with, so the list is the actual
     * plan - top row next - rather than a second opinion about it.
     */
    private fun queueSnapshot(): List<QueuedChunk> =
        ChunkStore.records { it.state != ChunkState.UPLOADED }
            .sortedWith(ChunkRecord.uploadOrder)
            .map { record ->
                QueuedChunk(
                    id = record.id,
                    index = record.index,
                    camera = record.camera,
                    kind = record.kind,
                    state = record.state,
                    attemptCount = record.attemptCount,
                    nextRetryAt = record.nextRetryAt,
                    lastErrorMessage = record.lastErrorMessage,
                    // Recorded at ingest; the disk is only consulted for
                    // records written before that field existed.
                    byteCount = record.byteCount ?: record.file.length().takeIf { it > 0 },
                )
            }
}

/**
 * One outstanding chunk, as the upload queue screen needs it. An immutable
 * snapshot rather than the record itself: the store is mutated off the UI
 * thread, and handing Compose a live reference to it would be a data race.
 */
data class QueuedChunk(
    val id: String,
    val index: Int,
    val camera: CameraSource,
    val kind: ChunkKind,
    val state: ChunkState,
    val attemptCount: Int,
    val nextRetryAt: Long?,
    val lastErrorMessage: String?,
    val byteCount: Long?,
)
