package com.witnessrecord.app.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Google Drive REST API client.
 *
 * Every call is a plain HTTPS request rather than the Drive SDK: the app only
 * ever needs a handful of endpoints, and keeping them explicit means the
 * exact bytes sent on the user's behalf are auditable in one file.
 */
object GoogleDriveClient {

    private const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
    private const val ABOUT_ENDPOINT = "https://www.googleapis.com/drive/v3/about"

    /**
     * No call timeout: a chunk upload over a bad cell connection mid-encounter
     * should keep trying rather than be cancelled on a stopwatch. Read/write
     * timeouts still catch a genuinely dead socket.
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    class DriveException(
        message: String,
        val statusCode: Int? = null,
    ) : IOException(message) {
        /**
         * Whether Drive is reporting that a referenced file/folder - most
         * often a cached parent ID - no longer exists, so the caller should
         * evict its cache and re-resolve rather than keep retrying the same
         * dead ID forever.
         */
        val isNotFound: Boolean get() = statusCode == 404
    }

    private fun Request.Builder.bearer(token: String) = header("Authorization", "Bearer $token")

    private suspend fun call(request: Request): Response = withContext(Dispatchers.IO) {
        http.newCall(request).execute()
    }

    /** Drive's query language uses single quotes; escape any inside a name. */
    private fun escape(name: String) = name.replace("'", "\\'")

    // MARK: - Identity

    /**
     * The signed-in account's email. Reachable under `drive.file` alone, so
     * showing which account footage is going to costs no additional scope.
     */
    suspend fun currentUserEmail(accessToken: String): String? {
        val url = ABOUT_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("fields", "user(emailAddress)")
            .build()
        val request = Request.Builder().url(url).bearer(accessToken).build()
        return runCatching {
            call(request).use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                JSONObject(body).optJSONObject("user")?.optString("emailAddress")
                    ?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    /**
     * How much room is left in the account. `limit` is absent on accounts
     * with unlimited storage, which is not an error and not a shortage - null
     * means "no answer", never "none left".
     */
    suspend fun availableBytes(accessToken: String): Long? {
        val url = ABOUT_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("fields", "storageQuota")
            .build()
        val request = Request.Builder().url(url).bearer(accessToken).build()
        return runCatching {
            call(request).use { response ->
                if (!response.isSuccessful) return null
                val quota = JSONObject(response.body?.string() ?: return null)
                    .optJSONObject("storageQuota") ?: return null
                val limit = quota.optString("limit").toLongOrNull() ?: return null
                val used = quota.optString("usage").toLongOrNull() ?: 0L
                maxOf(0L, limit - used)
            }
        }.getOrNull()
    }

    /**
     * Turns a failed folder creation into something a person can act on.
     *
     * "Could not create Drive folder" used to be the whole message, and it
     * cost a journalist an afternoon: her queue stopped at six hundred
     * segments and nothing said whether the problem was her account, her
     * connection or the app. Drive says exactly what is wrong in the reply
     * body, and the status code was already being captured and thrown away.
     *
     * A full Drive is called out by name because it is both the likeliest
     * cause of a long recording stalling and the only one fixable in a minute.
     */
    private fun folderFailureMessage(status: Int, body: String?): String {
        val context = com.witnessrecord.app.WitnessApplication.appContext
        val error = runCatching { JSONObject(body ?: "").optJSONObject("error") }.getOrNull()
        val reason = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
        val detail = error?.optString("message")?.takeIf { it.isNotEmpty() }

        if (reason == "storageQuotaExceeded" || detail?.contains("quota", ignoreCase = true) == true) {
            return context.getString(com.witnessrecord.app.R.string.drive_error_full)
        }
        if (status == 401 || status == 403) {
            return context.getString(com.witnessrecord.app.R.string.drive_error_refused, status)
        }
        var text = context.getString(com.witnessrecord.app.R.string.drive_error_folder_failed)
        text += " ($status)"
        if (detail != null) text += ": $detail"
        return text
    }

    // MARK: - Folders

    /**
     * Finds or creates the destination folder, caching its ID in the
     * Keystore-backed store. Routed through [DriveFolderCache.sharedRoot] so
     * concurrent callers (several chunks uploading at once, right after
     * sign-in) share one resolution instead of each independently creating a
     * folder.
     */
    suspend fun ensureFolder(name: String, accessToken: String): String =
        DriveFolderCache.sharedRoot.folderID("root") {
            val query = "name = '${escape(name)}' and " +
                "mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            findFirst(query, accessToken)
                ?: createFolder(name, parentID = null, accessToken = accessToken)
        }

    /**
     * Finds or creates a subfolder with [name] inside [parentID]. Searches
     * first so repeated calls (and relaunches) reuse the same folder rather
     * than creating duplicates. Not cached here - the caller caches the
     * per-session mapping.
     */
    suspend fun ensureSubfolder(name: String, parentID: String, accessToken: String): String {
        val query = "name = '${escape(name)}' and '$parentID' in parents and " +
            "mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        return findFirst(query, accessToken)
            ?: createFolder(name, parentID = parentID, accessToken = accessToken)
    }

    /**
     * Finds a file or folder named [name] directly inside [parentID], or null
     * if none exists. Used to make uploads idempotent - safe to retry after a
     * crash without creating a duplicate.
     */
    suspend fun findFile(name: String, parentID: String, accessToken: String): String? =
        findFirst("name = '${escape(name)}' and '$parentID' in parents and trashed = false", accessToken)

    private suspend fun findFirst(query: String, accessToken: String): String? {
        val url = FILES_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("fields", "files(id)")
            .build()
        val request = Request.Builder().url(url).bearer(accessToken).build()
        call(request).use { response ->
            if (response.code == 404) throw DriveException("Drive folder not found", 404)
            val body = response.body?.string() ?: return null
            if (!response.isSuccessful) {
                throw DriveException("Drive search failed (${response.code})", response.code)
            }
            val files = JSONObject(body).optJSONArray("files") ?: return null
            if (files.length() == 0) return null
            return files.getJSONObject(0).optString("id").takeIf { it.isNotEmpty() }
        }
    }

    private suspend fun createFolder(name: String, parentID: String?, accessToken: String): String {
        val payload = JSONObject().apply {
            put("name", name)
            put("mimeType", "application/vnd.google-apps.folder")
            if (parentID != null) put("parents", org.json.JSONArray().put(parentID))
        }
        val request = Request.Builder()
            .url(FILES_ENDPOINT)
            .bearer(accessToken)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        call(request).use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                throw DriveException(folderFailureMessage(response.code, body), response.code)
            }
            return JSONObject(body).optString("id").takeIf { it.isNotEmpty() }
                ?: throw DriveException("Drive folder response had no id", response.code)
        }
    }

    /**
     * Permanently deletes a file the app created. Used only after a
     * superseding artifact (a merged session video) is confirmed uploaded -
     * never as part of normal chunk handling.
     */
    suspend fun deleteFile(fileID: String, accessToken: String) {
        val request = Request.Builder()
            .url("$FILES_ENDPOINT/$fileID")
            .bearer(accessToken)
            .delete()
            .build()
        call(request).use { response ->
            // 404 means it is already gone, which is the outcome we wanted.
            if (!response.isSuccessful && response.code != 404) {
                throw DriveException("Could not delete Drive file $fileID (${response.code})", response.code)
            }
        }
    }

    // MARK: - Sharing

    /**
     * Grants [email] read access to a file/folder the app created. Drive sends
     * the recipient a notification email, which doubles as the "footage is
     * being recorded" alert to the emergency contact.
     */
    suspend fun shareFile(fileID: String, email: String, accessToken: String) {
        val url = "$FILES_ENDPOINT/$fileID/permissions".toHttpUrl().newBuilder()
            .addQueryParameter("sendNotificationEmail", "true")
            .build()
        val payload = JSONObject().apply {
            put("role", "reader")
            put("type", "user")
            put("emailAddress", email)
        }
        val request = Request.Builder()
            .url(url)
            .bearer(accessToken)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        call(request).use { response ->
            if (!response.isSuccessful) {
                throw DriveException("Sharing failed (${response.code}) for $email", response.code)
            }
        }
    }

    // MARK: - Resumable upload (large video chunks and merged session videos)

    /**
     * Initiates a resumable upload session; returns the session URI to PUT the
     * file body to. Persisting that URI is what lets an upload interrupted
     * mid-encounter resume from where it stopped instead of re-sending bytes
     * over a connection that may not last.
     */
    suspend fun beginResumableSession(
        fileName: String,
        mimeType: String,
        fileSize: Long,
        folderID: String,
        accessToken: String,
    ): String {
        val url = UPLOAD_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "resumable")
            .build()
        val payload = JSONObject().apply {
            put("name", fileName)
            put("parents", org.json.JSONArray().put(folderID))
        }
        val request = Request.Builder()
            .url(url)
            .bearer(accessToken)
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", fileSize.toString())
            .post(payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()
        call(request).use { response ->
            val location = response.header("Location")
            if (!response.isSuccessful || location == null) {
                throw DriveException(
                    "Resumable session initiation failed (${response.code})", response.code
                )
            }
            return location
        }
    }

    /**
     * Uploads [file] to an open resumable session and returns the created
     * Drive file ID.
     *
     * If [alreadyStarted] is set, the server is first asked how many bytes it
     * actually holds and only the remainder is sent - so a chunk interrupted
     * at 90% resumes at 90%.
     */
    suspend fun uploadResumable(
        sessionURI: String,
        file: File,
        mimeType: String,
        alreadyStarted: Boolean,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): String {
        val size = file.length()

        val offset = if (alreadyStarted) queryResumeOffset(sessionURI, size) else 0L
        if (offset == COMPLETE_OFFSET) {
            // The server already has the whole file from a previous attempt;
            // its status response carried the finished file's metadata.
            return completedFileID(sessionURI, size)
                ?: throw DriveException("Resumed upload complete but no file id returned")
        }

        val body = when {
            onProgress != null -> com.witnessrecord.app.service.storage.ProgressRequestBody(
                file, mimeType.toMediaType(), offset = offset, onProgress = onProgress
            )
            offset == 0L -> file.asRequestBody(mimeType.toMediaType())
            else -> PartialFileBody(file, offset, mimeType)
        }
        val builder = Request.Builder().url(sessionURI).put(body)
        if (offset > 0) {
            builder.header("Content-Range", "bytes $offset-${size - 1}/$size")
        }
        call(builder.build()).use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful || responseBody == null) {
                throw DriveException("Chunk upload failed (${response.code})", response.code)
            }
            return JSONObject(responseBody).optString("id").takeIf { it.isNotEmpty() }
                ?: throw DriveException("Upload succeeded but no file id returned", response.code)
        }
    }

    private const val COMPLETE_OFFSET = -1L

    /**
     * Asks the resumable session how many bytes it holds. Returns the offset
     * to continue from, or [COMPLETE_OFFSET] if the upload already finished.
     */
    private suspend fun queryResumeOffset(sessionURI: String, size: Long): Long {
        val request = Request.Builder()
            .url(sessionURI)
            .header("Content-Range", "bytes */$size")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        call(request).use { response ->
            if (response.isSuccessful) return COMPLETE_OFFSET
            if (response.code != 308) {
                // Session expired or was never valid - start over from zero.
                return 0L
            }
            val range = response.header("Range") ?: return 0L
            // "bytes=0-524287" -> the server holds through byte 524287.
            val lastByte = range.substringAfter("-", "").toLongOrNull() ?: return 0L
            return lastByte + 1
        }
    }

    private suspend fun completedFileID(sessionURI: String, size: Long): String? {
        val request = Request.Builder()
            .url(sessionURI)
            .header("Content-Range", "bytes */$size")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        call(request).use { response ->
            val body = response.body?.string() ?: return null
            return runCatching { JSONObject(body).optString("id") }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Streams a file from [offset] to its end, so a resumed upload re-sends
     * only what the server is missing.
     */
    private class PartialFileBody(
        private val file: File,
        private val offset: Long,
        private val mimeType: String,
    ) : RequestBody() {
        override fun contentType() = mimeType.toMediaType()
        override fun contentLength(): Long = file.length() - offset

        override fun writeTo(sink: okio.BufferedSink) {
            file.inputStream().use { stream ->
                stream.skip(offset)
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                }
            }
        }
    }

    // MARK: - Multipart upload (small manifest / log files)

    /**
     * Uploads a small file in a single multipart request and returns its Drive
     * file ID. Used for the session manifest and the event-log snapshot, which
     * are far too small to be worth a resumable session.
     */
    suspend fun multipartUpload(
        file: File,
        uploadName: String,
        mimeType: String,
        folderID: String,
        accessToken: String,
    ): String {
        val url = UPLOAD_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .build()
        val metadata = JSONObject().apply {
            put("name", uploadName)
            put("parents", org.json.JSONArray().put(folderID))
        }
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(file.asRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder().url(url).bearer(accessToken).post(body).build()
        call(request).use { response ->
            val responseBody = response.body?.string()
            if (!response.isSuccessful || responseBody == null) {
                throw DriveException("Upload of $uploadName failed (${response.code})", response.code)
            }
            return JSONObject(responseBody).optString("id").takeIf { it.isNotEmpty() }
                ?: throw DriveException("Upload of $uploadName returned no id", response.code)
        }
    }

    /**
     * PATCHes new contents over an existing Drive file, keeping the same file
     * ID. Used to keep one live session manifest current rather than
     * accumulating a new file per revision.
     */
    suspend fun updateFile(
        fileID: String,
        file: File,
        mimeType: String,
        accessToken: String,
    ): String {
        val url = "$UPLOAD_ENDPOINT/$fileID".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "media")
            .build()
        val request = Request.Builder()
            .url(url)
            .bearer(accessToken)
            .patch(file.asRequestBody(mimeType.toMediaType()))
            .build()
        call(request).use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful) {
                throw DriveException("Manifest update failed (${response.code})", response.code)
            }
            return body?.let { runCatching { JSONObject(it).optString("id") }.getOrNull() }
                ?.takeIf { it.isNotEmpty() } ?: fileID
        }
    }
}
