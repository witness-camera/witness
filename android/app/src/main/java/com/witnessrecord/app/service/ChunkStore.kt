package com.witnessrecord.app.service

import com.witnessrecord.app.WitnessApplication
import com.witnessrecord.app.model.CameraSource
import com.witnessrecord.app.model.ChunkKind
import com.witnessrecord.app.model.ChunkRecord
import com.witnessrecord.app.model.ChunkState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * Owns the on-disk chunk directory and the persisted upload queue.
 * Chunk files are only deleted here, and only after Drive confirms receipt.
 *
 * Every mutation runs under one lock and rewrites the queue file, so the
 * queue on disk always reflects the queue in memory - a process killed at any
 * instant (the phone seized, the app force-stopped) resumes exactly where it
 * left off.
 */
object ChunkStore {

    val chunksDirectory: File
        get() = File(WitnessApplication.appContext.filesDir, "chunks").apply { mkdirs() }

    private val queueFile: File
        get() = File(WitnessApplication.appContext.filesDir, "upload_queue.json")

    private val lock = Any()
    private var records: MutableList<ChunkRecord> = mutableListOf()
    private var loaded = false

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun ensureLoaded() {
        if (loaded) return
        records = loadFromDisk().toMutableList()
        // Anything that was mid-upload when the process died goes back to queued.
        for (i in records.indices) {
            if (records[i].state == ChunkState.UPLOADING) {
                records[i] = records[i].copy(state = ChunkState.QUEUED)
            }
        }
        loaded = true
        persistLocked()
    }

    // MARK: - Queue operations

    fun add(record: ChunkRecord) = synchronized(lock) {
        ensureLoaded()
        records.add(record)
        persistLocked()
    }

    fun update(record: ChunkRecord) = synchronized(lock) {
        ensureLoaded()
        val i = records.indexOfFirst { it.id == record.id }
        if (i >= 0) {
            records[i] = record
            persistLocked(Durability.SOON)
        }
    }

    /**
     * Marks a chunk uploaded and deletes its local video file. The record
     * itself is kept (without the file) as a receipt of what went where.
     */
    fun markUploaded(id: String, driveFileID: String?) = synchronized(lock) {
        ensureLoaded()
        val i = records.indexOfFirst { it.id == id }
        if (i < 0) return@synchronized
        records[i] = records[i].copy(state = ChunkState.UPLOADED, driveFileID = driveFileID)
        records[i].file.delete()
        persistLocked()
    }

    fun record(id: String): ChunkRecord? = synchronized(lock) {
        ensureLoaded()
        records.firstOrNull { it.id == id }
    }

    /**
     * Atomically selects the chunks ready for upload (oldest first, honoring
     * backoff deadlines) and marks them `UPLOADING` in the same locked step.
     *
     * Claiming and marking must be atomic: uploads are kicked off across
     * suspension points, and `pump()` runs from many triggers. If a chunk
     * stayed `QUEUED` while its upload was being set up, a second pump would
     * claim it too and upload the same file twice.
     */
    fun claimUploadable(
        now: Long = System.currentTimeMillis(),
        limits: Map<ChunkKind, Int>,
        inFlight: Set<String> = emptySet(),
    ): List<ChunkRecord> = synchronized(lock) {
        ensureLoaded()
        val eligible = records.indices.filter { i ->
            val record = records[i]
            record.state == ChunkState.QUEUED &&
                !inFlight.contains(record.id) &&
                (record.nextRetryAt == null || record.nextRetryAt <= now)
        }
        // Ordered before the limits are applied, not after: truncating first
        // would hand back whichever chunks happen to sit earliest in the list
        // rather than the ones that should go next.
        val ordered = eligible.sortedWith(compareBy(ChunkRecord.uploadOrder) { records[it] })

        val remaining = limits.toMutableMap()
        val claimed = mutableListOf<ChunkRecord>()
        for (i in ordered) {
            val left = remaining[records[i].kind] ?: continue
            if (left <= 0) continue
            remaining[records[i].kind] = left - 1
            records[i] = records[i].copy(state = ChunkState.UPLOADING)
            claimed.add(records[i])
        }
        if (claimed.isNotEmpty()) persistLocked(Durability.SOON)
        claimed
    }

    /**
     * Returns a claimed chunk to the queue without counting a failed attempt -
     * used when setup fails before any upload was actually started.
     */
    fun release(id: String, retryAfterMillis: Long? = null) = synchronized(lock) {
        ensureLoaded()
        val i = records.indexOfFirst { it.id == id }
        if (i < 0 || records[i].state != ChunkState.UPLOADING) return@synchronized
        records[i] = records[i].copy(
            state = ChunkState.QUEUED,
            nextRetryAt = retryAfterMillis?.let { System.currentTimeMillis() + it }
        )
        persistLocked(Durability.SOON)
    }

    val pendingCount: Int
        get() = synchronized(lock) {
            ensureLoaded()
            records.count { it.state != ChunkState.UPLOADED }
        }

    /**
     * Removes a session's records - used once its small chunks have been
     * deleted from Drive because a merged video now supersedes them. Safe: the
     * merged video's own upload is confirmed before this is ever called, so
     * nothing is dropped while still the only copy anywhere.
     *
     * When [camera] is given, only that camera's records are removed - e.g.
     * the rear camera's merge succeeded and its chunks were deleted from
     * Drive, but the front camera's merge failed, so its small chunks are
     * still the only copy and must stay tracked.
     */
    fun removeAll(sessionID: String, camera: CameraSource? = null) = synchronized(lock) {
        ensureLoaded()
        val matches: (ChunkRecord) -> Boolean = {
            it.sessionID == sessionID && (camera == null || it.camera == camera)
        }
        records.filter(matches).forEach { it.file.delete() }
        records.removeAll(matches)
        persistLocked()
    }

    /**
     * Every record belonging to a session, ordered for the manifest. Uploaded
     * records are retained as receipts, so this stays complete for the whole
     * session even after files are deleted.
     */
    fun records(sessionID: String): List<ChunkRecord> = synchronized(lock) {
        ensureLoaded()
        records
            .filter { it.sessionID == sessionID }
            .sortedWith(compareBy({ it.index }, { it.camera.wire }))
    }

    /** Every record matching an arbitrary predicate, e.g. for the low-res gate. */
    fun records(matching: (ChunkRecord) -> Boolean): List<ChunkRecord> = synchronized(lock) {
        ensureLoaded()
        records.filter(matching)
    }

    /**
     * Removes a single record outright - used to drop a low-res stand-in once
     * its master is confirmed, or one that was cancelled before it uploaded.
     * Unlike [markUploaded], this never keeps the record as a receipt: a
     * stand-in has no evidentiary value once superseded.
     */
    fun remove(id: String) = synchronized(lock) {
        ensureLoaded()
        val i = records.indexOfFirst { it.id == id }
        if (i < 0) return@synchronized
        records[i].file.delete()
        records.removeAt(i)
        persistLocked()
    }

    /**
     * When and where a recording session began, taken from its lowest-indexed
     * chunk. Every chunk of a session resolves to the same origin, so the
     * session's Drive folder name is identical no matter which chunk uploads
     * first. Uploaded records are retained as receipts, so this stays stable
     * for the whole session.
     */
    fun sessionOrigin(sessionID: String): SessionOrigin? = synchronized(lock) {
        ensureLoaded()
        // The date from the first chunk; the place from the first chunk that
        // has one, since a cold start (tile, widget) often begins recording
        // before the phone has a location fix.
        val chunks = records.filter { it.sessionID == sessionID }.sortedBy { it.index }
        val first = chunks.firstOrNull() ?: return@synchronized null
        val located = chunks.firstOrNull { it.latitude != null && it.longitude != null }
        SessionOrigin(first.startedAt, located?.latitude, located?.longitude)
    }

    data class SessionOrigin(val date: Long, val latitude: Double?, val longitude: Double?)

    val earliestRetryDate: Long?
        get() = synchronized(lock) {
            ensureLoaded()
            records.filter { it.state == ChunkState.QUEUED }.mapNotNull { it.nextRetryAt }.minOrNull()
        }

    /** Session IDs with at least one record still tracked. */
    fun knownSessionIDs(): Set<String> = synchronized(lock) {
        ensureLoaded()
        records.map { it.sessionID }.toSet()
    }

    // MARK: - Persistence

    /**
     * Bump when [ChunkRecord]'s on-disk shape changes in a way lenient
     * decoding can't absorb on its own - a field rename or type change, not
     * just adding a new nullable field (which decodes fine on old data as-is).
     * When that happens, branch on the *stored* version inside [loadFromDisk]
     * to transform the raw JSON before decoding, so a user who updates
     * mid-recording doesn't lose chunks still waiting to upload.
     */
    private const val CURRENT_SCHEMA_VERSION = 1

    @Serializable
    private data class PersistedQueue(val schemaVersion: Int, val records: List<ChunkRecord>)

    /**
     * How soon a change has to be on disk.
     *
     * Every write re-encodes the entire queue, so its cost grows with the
     * session: a two-hour recording ends up rewriting a third of a megabyte
     * on each change, several times per chunk. That is not only wasted work -
     * it puts a growing encode in front of every state change, including the
     * file deletions that other uploads are racing.
     *
     * So changes that a relaunch can reconstruct are coalesced, and only the
     * ones that cannot be are written immediately.
     */
    private enum class Durability {
        /**
         * The record is the only thing that knows this file exists, or that it
         * no longer does. Losing it strands a chunk on disk forever - nothing
         * scans the directory for files without records.
         */
        IMMEDIATELY,

        /**
         * Recoverable at launch: hashes recompute, upload states are reset to
         * QUEUED anyway, and backoff deadlines are advisory.
         */
        SOON,
    }

    private const val COALESCE_DELAY_MILLIS = 2_000L

    private val writeScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "witness-chunkstore-write").apply { isDaemon = true }
    }
    private var pendingWrite: java.util.concurrent.ScheduledFuture<*>? = null

    private fun persistLocked(durability: Durability = Durability.IMMEDIATELY) {
        pendingWrite?.cancel(false)
        pendingWrite = null

        if (durability == Durability.SOON) {
            pendingWrite = writeScheduler.schedule(
                { synchronized(lock) { pendingWrite = null; writeLocked() } },
                COALESCE_DELAY_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
            return
        }
        writeLocked()
    }

    /** Caller must hold [lock]. */
    private fun writeLocked() {
        val payload = PersistedQueue(CURRENT_SCHEMA_VERSION, records.toList())
        runCatching {
            val temp = File(queueFile.parentFile, queueFile.name + ".tmp")
            temp.writeText(json.encodeToString(payload))
            // Atomic swap: a process killed mid-write leaves the previous
            // complete queue intact rather than a truncated one.
            temp.renameTo(queueFile)
        }
    }

    /**
     * Writes anything still coalesced. Called when the app is backgrounded or
     * the recording stops, so a pending change is never lost to a two second
     * window the process did not survive.
     */
    fun flush() = synchronized(lock) {
        if (pendingWrite == null) return@synchronized
        pendingWrite?.cancel(false)
        pendingWrite = null
        writeLocked()
    }

    /**
     * Decodes each record independently, so one unreadable record can't blank
     * out the whole queue.
     */
    private fun loadFromDisk(): List<ChunkRecord> {
        if (!queueFile.exists()) return emptyList()
        val text = runCatching { queueFile.readText() }.getOrNull() ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()

        val elements = when {
            root is JsonObject -> root["records"]?.jsonArray
            root != null -> runCatching { root.jsonArray }.getOrNull() // legacy bare array
            else -> null
        }
        if (elements == null) {
            EventLogger.log("chunkstore.load_failed", detail = "queue file unreadable, starting empty")
            return emptyList()
        }

        val decoded = elements.mapNotNull {
            runCatching { json.decodeFromJsonElement(ChunkRecord.serializer(), it) }.getOrNull()
        }
        val dropped = elements.size - decoded.size
        if (dropped > 0) {
            EventLogger.log(
                "chunkstore.records_dropped",
                detail = "$dropped unreadable record(s) on load - see file for others"
            )
        }
        return decoded
    }
}
