package com.witnessrecord.app.service

import com.witnessrecord.app.WitnessApplication
import com.witnessrecord.app.model.SessionManifest
import java.io.File

/**
 * Keeps a permanent local copy of each finished session's chain-of-custody
 * record, so an evidence package can still be produced long after the
 * footage itself has left the device.
 *
 * This exists because nothing else survives: chunk records are receipts that
 * get cleared once a session is consolidated, and the manifest is deleted
 * locally as soon as the backend confirms it. Both are correct behaviours for
 * the upload pipeline and both would leave a user with no way to document,
 * weeks later, what was recorded and that it wasn't altered.
 *
 * Only JSON and a small proof file are kept - never video - so the archive
 * stays negligible in size no matter how much was filmed.
 */
object SessionArchive {

    private val root: File
        get() = File(WitnessApplication.appContext.filesDir, "archive").apply { mkdirs() }

    fun directory(sessionID: String): File =
        File(root, sessionID).apply { mkdirs() }

    /**
     * A file's place in a session's archive without creating the directory -
     * for reads, so looking something up never leaves empty folders behind.
     */
    fun path(name: String, sessionID: String): File = File(File(root, sessionID), name)

    fun manifestFile(sessionID: String): File = File(directory(sessionID), "manifest.json")
    fun timestampFile(sessionID: String): File = File(directory(sessionID), "timestamp.tsr")

    /**
     * Stores the exact bytes that were hashed and uploaded. Byte-identical is
     * the whole point: a re-encoded copy would no longer match the timestamp
     * proof, and the package would fail its own verification instructions.
     */
    fun store(sessionID: String, manifestData: ByteArray, timestampToken: ByteArray?) {
        runCatching { manifestFile(sessionID).writeBytes(manifestData) }
        if (timestampToken != null) {
            runCatching { timestampFile(sessionID).writeBytes(timestampToken) }
        }
    }

    /** Archived sessions, most recent first. */
    fun sessions(): List<SessionManifest> =
        (root.listFiles()?.toList() ?: emptyList())
            .mapNotNull { dir ->
                val file = File(dir, "manifest.json")
                if (!file.exists()) return@mapNotNull null
                runCatching {
                    SessionManifest.json.decodeFromString(SessionManifest.serializer(), file.readText())
                }.getOrNull()
            }
            .sortedByDescending { it.recordingStartedUTC }

    fun hasTimestamp(sessionID: String): Boolean = timestampFile(sessionID).exists()

    fun delete(sessionID: String) {
        directory(sessionID).deleteRecursively()
    }
}
