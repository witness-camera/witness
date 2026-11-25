package com.witnessrecord.app.service

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.witnessrecord.app.WitnessApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves a local copy of each recorded session into the device gallery. This is
 * redundancy independent of the Drive upload: footage survives on-device (and
 * in whatever cloud photo service the user syncs with) even if the app is
 * removed.
 *
 * Writes go through MediaStore under scoped storage, which grants an app
 * insert access to its *own* media without any storage permission and without
 * any read access to the user's existing library - the same
 * add-but-never-read posture the iOS build gets from add-only Photos access.
 *
 * Unlike iOS, where an album lookup would need a library *read* the app
 * deliberately never has, MediaStore lets an app name its own destination
 * directory on insert, so recordings land in a dedicated `Movies/Witness`
 * folder rather than loose in the camera roll.
 */
object MediaStoreService {

    private val RELATIVE_PATH = "${Environment.DIRECTORY_MOVIES}/Witness"

    /**
     * Nothing to request: inserting the app's own media needs no runtime
     * permission on Android 10+. Kept so the onboarding permission list can
     * describe the gallery backup in the same shape as the other rows.
     */
    val isEnabled: Boolean get() = true

    /**
     * Saves the video at [file] to the gallery. Best-effort: failures are
     * logged but never block recording or upload.
     */
    suspend fun save(file: File, displayName: String = file.name): Uri? =
        withContext(Dispatchers.IO) {
            val resolver = WitnessApplication.appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                // Hidden from the gallery until the copy is complete, so a
                // half-written video is never surfaced or synced anywhere.
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = runCatching {
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()

            if (uri == null) {
                EventLogger.log("photos.save_failed", detail = "$displayName: no MediaStore row")
                return@withContext null
            }

            val copied = runCatching {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output, 1 shl 16) }
                } ?: throw IllegalStateException("no output stream")
            }

            if (copied.isFailure) {
                runCatching { resolver.delete(uri, null, null) }
                EventLogger.log(
                    "photos.save_failed",
                    detail = "$displayName: ${copied.exceptionOrNull()?.message}"
                )
                return@withContext null
            }

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            runCatching { resolver.update(uri, values, null, null) }
            uri
        }

    /**
     * Saves the photo at [file] to the gallery, in a `Pictures/Witness` folder.
     * Best-effort, like [save].
     */
    suspend fun saveImage(file: File, displayName: String = file.name): Uri? =
        withContext(Dispatchers.IO) {
            val resolver = WitnessApplication.appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Witness")
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = runCatching {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrNull() ?: return@withContext null
            val copied = runCatching {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output, 1 shl 16) }
                } ?: throw IllegalStateException("no output stream")
            }
            if (copied.isFailure) {
                runCatching { resolver.delete(uri, null, null) }
                EventLogger.log("photos.save_failed", detail = "$displayName: ${copied.exceptionOrNull()?.message}")
                return@withContext null
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            runCatching { resolver.update(uri, values, null, null) }
            uri
        }
}
