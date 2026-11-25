package com.witnessrecord.app.service

import com.witnessrecord.app.model.ChunkKind
import com.witnessrecord.app.model.Iso8601Serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What actually reached the user's storage, summarised per recording session.
 *
 * Kept apart from [ChunkStore] because the chunk records do not survive long
 * enough to answer this. A confirmed chunk keeps its record as a receipt, but
 * only until the session's merged video lands - `finishDriveStitch` then
 * deletes the per-chunk records along with the small remote files they
 * describe. A recap built from those records would empty itself at the exact
 * moment the recording finished, which is when someone would look at it.
 *
 * So this is written once per confirmation and outlives all of it: a few
 * counters per session, small enough to keep in preferences.
 */
@Serializable
data class SessionUploadReceipt(
    val sessionID: String,
    /** When the recording began - how the session is named in the UI. */
    @Serializable(with = Iso8601Serializer::class) val startedAt: Long,
    val pieces: Int = 0,
    val previews: Int = 0,
    val bytes: Long = 0,
    @Serializable(with = Iso8601Serializer::class) val lastConfirmedAt: Long,
    /**
     * Set once the per-camera merged videos have landed and the chunks they
     * replace have been deleted remotely, so the recap can say that what is
     * up there now is one video per camera rather than the pieces it counted.
     */
    val mergedVideos: Int = 0,
)

/**
 * Persistence for the above. Every entry point is called from
 * [UploadManager]'s work scope.
 */
object UploadReceipts {
    private const val KEY = "sessionUploadReceipts"

    /**
     * The sheet shows the most recent one. A handful are kept so a recording
     * that follows a short one does not erase it immediately; an unbounded
     * list would grow for the life of the app.
     */
    private const val KEEP = 5

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    fun all(): List<SessionUploadReceipt> {
        val raw = Prefs.getString(KEY) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<SessionUploadReceipt>>(raw)
        }.getOrDefault(emptyList())
    }

    /**
     * The session with the most recent confirmed upload - not the newest
     * session, which may not have landed anything yet and would replace a
     * finished recap with an empty one.
     */
    fun mostRecent(): SessionUploadReceipt? = all().maxByOrNull { it.lastConfirmedAt }

    fun recordConfirmed(sessionID: String, startedAt: Long, kind: ChunkKind, bytes: Long) {
        update(sessionID, startedAt) { receipt ->
            receipt.copy(
                pieces = if (kind == ChunkKind.MASTER) receipt.pieces + 1 else receipt.pieces,
                previews = if (kind == ChunkKind.LOW_RES) receipt.previews + 1 else receipt.previews,
                // Stand-ins are counted separately but their bytes are not:
                // they are deleted remotely the moment their full-quality file
                // lands, so counting them in the total would report more
                // delivered than is actually there.
                bytes = if (kind == ChunkKind.MASTER) receipt.bytes + bytes else receipt.bytes,
                lastConfirmedAt = System.currentTimeMillis(),
            )
        }
    }

    fun recordMerged(sessionID: String, startedAt: Long, videos: Int) {
        update(sessionID, startedAt) { it.copy(mergedVideos = videos) }
    }

    private fun update(
        sessionID: String,
        startedAt: Long,
        change: (SessionUploadReceipt) -> SessionUploadReceipt,
    ) = synchronized(lock) {
        val existing = all()
        val receipt = existing.firstOrNull { it.sessionID == sessionID }
            ?: SessionUploadReceipt(
                sessionID = sessionID,
                startedAt = startedAt,
                lastConfirmedAt = System.currentTimeMillis(),
            )
        val updated = existing.filterNot { it.sessionID == sessionID } + change(receipt)
        val kept = updated.sortedByDescending { it.lastConfirmedAt }.take(KEEP)
        runCatching { Prefs.setString(KEY, json.encodeToString(kept)) }
    }
}
