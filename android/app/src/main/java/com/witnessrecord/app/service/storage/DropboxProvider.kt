package com.witnessrecord.app.service.storage

import androidx.activity.ComponentActivity
import com.witnessrecord.app.service.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Dropbox as an upload destination.
 *
 * Registered with **App folder** access, Dropbox's equivalent of Google's
 * `drive.file`: everything the app reads or writes is confined to its own
 * directory, and the rest of the user's Dropbox is invisible to it.
 *
 * Unlike Google, Dropbox has no resumable-session negotiation for files this
 * size - a chunk goes up in one request. The interface's resumable shape is
 * honoured anyway by treating the destination path as the "session", which
 * keeps this provider's call shape identical to the others.
 */
object DropboxProvider : StorageProvider {
    override val id = StorageProviderID.DROPBOX

    private const val UPLOAD_ENDPOINT = "https://content.dropboxapi.com/2/files/upload"
    private const val RPC_BASE = "https://api.dropboxapi.com/2"

    class DropboxException(message: String) : IOException(message)

    override val accountLabelFlow get() = DropboxAuthService.accountEmail
    override suspend fun restore() = DropboxAuthService.restore()
    override fun signOut() = DropboxAuthService.signOut()

    override suspend fun signIn(activity: ComponentActivity) = DropboxAuthService.signIn(activity)

    override suspend fun freshAccessToken(): String = DropboxAuthService.freshAccessToken()

    // MARK: Folders

    /** Dropbox paths are the identity of a file, and always absolute from the app folder's root. */
    override suspend fun ensureRootFolder(name: String, token: String): String {
        val path = "/${sanitise(name)}"
        createFolder(path, token)
        return path
    }

    override suspend fun ensureSubfolder(name: String, parentID: String, token: String): String {
        val path = "$parentID/${sanitise(name)}"
        createFolder(path, token)
        return path
    }

    override suspend fun findFile(name: String, parentID: String, token: String): String? {
        val path = "$parentID/${sanitise(name)}"
        return try {
            rpc("files/get_metadata", mapOf("path" to path), token)
            path
        } catch (_: DropboxException) {
            // Absent is the normal answer here, not a failure: this is what
            // makes retrying an upload idempotent.
            null
        }
    }

    override suspend fun deleteFile(fileID: String, token: String) {
        rpc("files/delete_v2", mapOf("path" to fileID), token)
    }

    // MARK: Transfers

    /**
     * No session to negotiate: the returned "session URI" is simply the
     * destination path. Chunks are far below Dropbox's 150 MB single-request
     * ceiling, and the mastering tier - the only thing that would exceed it
     * - never uploads at all.
     */
    override suspend fun beginResumableSession(
        fileName: String, mimeType: String, fileSize: Long, folderID: String, token: String,
    ): String = "$folderID/${sanitise(fileName)}"

    override suspend fun uploadResumable(
        sessionURI: String, file: File, mimeType: String, alreadyStarted: Boolean, token: String,
        onProgress: ((sent: Long, total: Long) -> Unit)?,
    ): String = withContext(Dispatchers.IO) {
        val octetStream = "application/octet-stream".toMediaType()
        val body = if (onProgress != null) {
            ProgressRequestBody(file, octetStream, onProgress = onProgress)
        } else {
            file.asRequestBody(octetStream)
        }
        val request = Request.Builder()
            .url(UPLOAD_ENDPOINT)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/octet-stream")
            .header("Dropbox-API-Arg", uploadArgument(sessionURI))
            .post(body)
            .build()
        HttpClients.shared.newCall(request).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                throw DropboxException("Dropbox upload failed (${response.code})")
            }
            fileID(body) ?: throw DropboxException("Dropbox upload returned no path")
        }
    }

    override suspend fun multipartUpload(
        file: File, uploadName: String, mimeType: String, folderID: String, token: String,
    ): String = uploadResumable(
        sessionURI = "$folderID/${sanitise(uploadName)}",
        file = file, mimeType = mimeType, alreadyStarted = false, token = token, onProgress = null,
    )

    override suspend fun updateFile(fileID: String, file: File, mimeType: String, token: String): String =
        uploadResumable(fileID, file, mimeType, alreadyStarted = false, token = token, onProgress = null)

    private fun fileID(responseBody: String): String? =
        runCatching { JSONObject(responseBody) }.getOrNull()
            // path_lower is what every other endpoint here accepts, so it is
            // more useful downstream than Dropbox's opaque id.
            ?.let { it.optString("path_lower").takeIf(String::isNotEmpty) ?: it.optString("id").takeIf(String::isNotEmpty) }

    /**
     * `"mode": "overwrite"` matters: a retried upload must land on the same
     * path rather than becoming "file (1)", or a duplicate would appear in
     * the folder every time a flaky connection forced a retry.
     */
    private fun uploadArgument(path: String): String {
        val argument = JSONObject().apply {
            put("path", path)
            put("mode", "overwrite")
            put("autorename", false)
            put("mute", true)
        }
        return argument.toString()
    }

    // MARK: Sharing

    override val supportsEmailSharing = true

    /**
     * Two steps, because Dropbox will only add members to a folder that has
     * been turned into a shared folder first. Sharing an already-shared
     * folder returns an error that means "already done", which is treated as
     * success here.
     */
    override suspend fun share(fileID: String, email: String, token: String) {
        var sharedFolderID: String? = null
        runCatching {
            val result = rpc("sharing/share_folder", mapOf("path" to fileID, "force_async" to false), token)
            sharedFolderID = result.optString("shared_folder_id").takeIf(String::isNotEmpty)
                ?: result.optJSONObject("complete")?.optString("shared_folder_id")?.takeIf(String::isNotEmpty)
        }
        if (sharedFolderID == null) {
            runCatching {
                val metadata = rpc("sharing/get_folder_metadata", mapOf("shared_folder_id" to fileID), token)
                sharedFolderID = metadata.optString("shared_folder_id").takeIf(String::isNotEmpty)
            }
        }
        val resolvedID = sharedFolderID ?: throw DropboxException("Could not share that folder")

        val member = JSONObject().apply {
            put("member", JSONObject().apply { put(".tag", "email"); put("email", email) })
            put("access_level", JSONObject().apply { put(".tag", "viewer") })
        }
        rpc(
            "sharing/add_folder_member",
            mapOf("shared_folder_id" to resolvedID, "members" to JSONArray().put(member), "quiet" to false),
            token,
        )
    }

    // MARK: - Plumbing

    private suspend fun createFolder(path: String, token: String) {
        try {
            rpc("files/create_folder_v2", mapOf("path" to path), token)
        } catch (error: DropboxException) {
            // Existing is the normal case: this runs before every batch.
            if (error.message?.contains("conflict") != true) throw error
        }
    }

    private suspend fun rpc(path: String, body: Map<String, Any>, token: String): JSONObject =
        withContext(Dispatchers.IO) {
            val payload = JSONObject(body)
            val request = Request.Builder()
                .url("$RPC_BASE/$path")
                .header("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            HttpClients.shared.newCall(request).execute().use { response ->
                val text = response.body?.string()
                val json = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
                if (!response.isSuccessful) {
                    val summary = json.optString("error_summary").takeIf(String::isNotEmpty) ?: text ?: "unknown"
                    throw DropboxException(summary)
                }
                json
            }
        }

    /** Dropbox rejects a path containing its reserved characters outright, so a user-typed name is cleaned first. */
    private fun sanitise(component: String): String = component
        .replace("/", "-").replace("\\", "-").replace(":", "-")
        .replace("?", "").replace("*", "")
        .replace("\"", "'").replace("<", "(").replace(">", ")")
        .replace("|", "-")
}
