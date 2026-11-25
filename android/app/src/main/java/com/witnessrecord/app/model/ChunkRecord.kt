package com.witnessrecord.app.model

import com.witnessrecord.app.service.ChunkStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Which physical camera produced a chunk. In dual-camera mode both are
 * recorded simultaneously; in single-camera mode only [BACK] is used.
 *
 * The wire value is lowercase to match the iOS build's `CameraSource` raw
 * values, so a manifest reads the same on both platforms.
 */
@Serializable
enum class CameraSource(val wire: String) {
    @SerialName("back") BACK("back"),
    @SerialName("front") FRONT("front");

    companion object {
        fun fromWire(value: String): CameraSource? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
enum class ChunkState {
    /** File finalized on disk, hash not yet computed. */
    @SerialName("pendingHash") PENDING_HASH,

    /** Hashed, waiting for upload. */
    @SerialName("queued") QUEUED,

    /** Upload in flight. */
    @SerialName("uploading") UPLOADING,

    /** Drive confirmed receipt; local file deleted. */
    @SerialName("uploaded") UPLOADED,
}

/** Whether a chunk record is the full-quality recording or a stand-in. */
@Serializable
enum class ChunkKind {
    @SerialName("master") MASTER,
    @SerialName("lowRes") LOW_RES,
}

/**
 * One recorded video chunk, tracked from finalization on disk until Google
 * Drive confirms receipt. Persisted as JSON by [ChunkStore] so the upload
 * queue survives process death - including across an app update, possibly
 * with chunks from an in-progress recording still queued.
 *
 * Schema discipline: new fields must be nullable (or carry a default) so
 * records written by an older app version keep decoding after an update.
 * [ChunkStore] also decodes each record independently, so even a record that
 * fails to decode only drops that one chunk rather than the whole queue - but
 * a rename or type change of an *existing* field still needs a real
 * migration; see `ChunkStore.CURRENT_SCHEMA_VERSION`.
 */
@Serializable
data class ChunkRecord(
    val id: String,
    /**
     * File name inside the chunks directory (relative, so the record stays
     * valid if the app's data directory path changes between launches).
     */
    val fileName: String,
    val sessionID: String,
    val index: Int,
    @Serializable(with = Iso8601Serializer::class) val startedAt: Long,
    @Serializable(with = Iso8601Serializer::class) val endedAt: Long,
    val camera: CameraSource = CameraSource.BACK,

    val sha256: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val horizontalAccuracy: Double? = null,

    /**
     * Optional with a default so records written before low-res existed keep
     * decoding after an update.
     */
    val kind: ChunkKind = ChunkKind.MASTER,
    /**
     * Set only on a low-res record: the full-quality chunk it stands in for.
     * Once that chunk is confirmed uploaded, this one is deleted remotely -
     * the stand-in has served its purpose and would only clutter the folder.
     */
    val masterChunkID: String? = null,

    /**
     * Size on disk, measured once when the chunk is finalized.
     *
     * Stored rather than read back, because the upload sheet needs it for
     * every outstanding chunk and reading it meant a `stat` per record on
     * every refresh - and a refresh happens each time a chunk confirms. Over
     * a two-hour session that is hundreds of thousands of syscalls to
     * re-answer a question whose answer never changes. Nullable, per the
     * schema rule above: records written before this existed decode fine and
     * fall back to the disk.
     */
    val byteCount: Long? = null,

    val state: ChunkState = ChunkState.PENDING_HASH,
    val attemptCount: Int = 0,
    val nextRetryAt: Long? = null,
    /**
     * What the last failed attempt actually said - an HTTP status or the
     * error text from the network/provider layer. Shown next to the retry
     * countdown in the upload queue so "retrying" comes with a reason
     * instead of leaving that to a single global message that a different
     * chunk's failure could overwrite a moment later. Cleared implicitly: a
     * record that uploads successfully leaves the queue rather than needing
     * its error reset.
     */
    val lastErrorMessage: String? = null,
    /** Drive resumable-session URI, kept so an interrupted upload can resume. */
    val resumableSessionURI: String? = null,
    val driveFileID: String? = null,
) {
    val file: File get() = File(ChunkStore.chunksDirectory, fileName)

    companion object {
        /**
         * The one definition of upload order - used both to pick what uploads
         * next and to draw the queue.
         *
         * It used to be written twice, in `ChunkStore.claimUploadable` and in
         * the upload sheet's snapshot, and the two disagreed. The sheet
         * grouped a stand-in next to its master by index; the uploader took
         * every stand-in first. So the list showed a plan the uploader was not
         * following, which is worse than showing nothing.
         *
         * Stand-ins go first - all of them, not just the one whose turn it is.
         * They exist only because the connection is failing, they are a tenth
         * the size, and the point is to get *something* of every moment off
         * the device before spending minutes on any single full-quality file.
         * Then oldest first, so what remains drains in the order it was filmed.
         *
         * A stand-in inherits its master's `startedAt` and `index`, so the two
         * tie on both and need a last tiebreak - without one a pair would swap
         * places between refreshes.
         */
        val uploadOrder: Comparator<ChunkRecord> = Comparator { a, b ->
            when {
                (a.kind == ChunkKind.LOW_RES) != (b.kind == ChunkKind.LOW_RES) ->
                    if (a.kind == ChunkKind.LOW_RES) -1 else 1
                a.startedAt != b.startedAt -> a.startedAt.compareTo(b.startedAt)
                a.index != b.index -> a.index.compareTo(b.index)
                a.camera != b.camera -> if (a.camera == CameraSource.BACK) -1 else 1
                else -> a.id.compareTo(b.id)
            }
        }
    }
}
