package com.witnessrecord.app.service

import android.location.Location
import com.witnessrecord.app.WitnessApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Append-only JSONL event log, independent of the video files. Records
 * session start/stop, chunk boundaries, interruptions, and upload outcomes
 * with UTC timestamps and location - so timing/location evidence survives
 * even if a video file's embedded metadata is ever stripped.
 *
 * Writes run on a single-threaded executor, which both keeps appends ordered
 * and makes [log] safe to call from the capture data thread without ever
 * blocking a frame.
 */
object EventLogger {

    @Serializable
    private data class Event(
        val timestampUTC: String,
        val event: String,
        val detail: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val timeZone: String,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    val logFile: File
        get() = File(WitnessApplication.appContext.filesDir, "witness_events.jsonl")

    fun log(event: String, detail: String? = null, location: Location? = null) {
        val entry = Event(
            timestampUTC = DateTimeFormatter.ISO_INSTANT.format(
                Instant.ofEpochSecond(System.currentTimeMillis() / 1000)
            ),
            event = event,
            detail = detail,
            latitude = location?.latitude,
            longitude = location?.longitude,
            timeZone = TimeZone.getDefault().id,
        )
        val line = runCatching { json.encodeToString(entry) }.getOrNull() ?: return
        executor.execute {
            runCatching { logFile.appendText(line + "\n") }
        }
    }

    /**
     * Copies the current log to a timestamped snapshot for upload, so the live
     * file can keep being appended to while the snapshot uploads.
     */
    fun snapshotForUpload(): File? {
        val source = logFile
        if (!source.exists()) return null
        val stamp = DateTimeFormatter.ISO_INSTANT.format(
            Instant.ofEpochSecond(System.currentTimeMillis() / 1000)
        ).replace(":", "-")
        val dest = File(ChunkStore.chunksDirectory, "witness_events_$stamp.jsonl")
        dest.delete()
        return runCatching { source.copyTo(dest, overwrite = true) }.getOrNull()
    }
}
