package com.witnessrecord.app.service.storage

import androidx.activity.ComponentActivity
import com.witnessrecord.app.service.HttpClients
import com.witnessrecord.app.service.Prefs
import com.witnessrecord.app.service.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Base64

/**
 * Uploads to any WebDAV server: Nextcloud, ownCloud, Infomaniak kDrive, a
 * self-hosted box, or anything else speaking the protocol.
 *
 * This is the option for people who won't put footage of a police encounter
 * into an account held by a large US company - which, for the journalists and
 * organisers this app is built for, is often the whole question. It is also
 * the only provider here that needs no app registration by anyone: the user
 * supplies a server, a username and a password, and that is the entire setup.
 *
 * Two consequences worth stating plainly rather than discovering in the
 * field. WebDAV has no file identifiers, so a file's path *is* its identity -
 * which is why remote IDs here are paths. And plain WebDAV has no sharing API
 * at all, so emergency contacts cannot be granted access automatically;
 * [supportsEmailSharing] is false and the UI says so.
 */
object WebDAVProvider : StorageProvider {
    override val id = StorageProviderID.WEBDAV

    const val SERVER_URL_KEY = "webDAVServerURL"
    const val USERNAME_KEY = "webDAVUsername"

    class WebDAVException(
        message: String,
        /**
         * The server understood us and said no. Distinguished because it is
         * the one failure that trying a different path cannot fix.
         */
        val isCredentialFailure: Boolean = false,
    ) : IOException(message)

    private val _accountLabel = MutableStateFlow(computeAccountLabel())
    override val accountLabelFlow: StateFlow<String?> get() = _accountLabel

    override suspend fun restore() { /* Credentials live in Keystore-backed storage; nothing to refresh. */ }

    /** Unused: WebDAV is configured through its own setup screen, not a web sign-in. */
    override suspend fun signIn(activity: ComponentActivity) {
        throw WebDAVException("Set up WebDAV from the storage settings.")
    }

    override fun signOut() {
        Prefs.setString(SERVER_URL_KEY, null)
        Prefs.setString(USERNAME_KEY, null)
        SecureStore.delete(SecureStore.Key.WEBDAV_PASSWORD)
        _accountLabel.value = null
    }

    /**
     * There is no token exchange in WebDAV; every request carries HTTP Basic
     * credentials. The pipeline still asks for a "token" before each batch,
     * so this doubles as the check that the account is still configured.
     */
    override suspend fun freshAccessToken(): String {
        credentials() ?: throw WebDAVException("WebDAV is not set up")
        return ""
    }

    // MARK: Folders

    override suspend fun ensureRootFolder(name: String, token: String): String {
        val path = sanitise(name)
        makeCollection(path)
        return path
    }

    override suspend fun ensureSubfolder(name: String, parentID: String, token: String): String {
        val path = "$parentID/${sanitise(name)}"
        makeCollection(path)
        return path
    }

    override suspend fun findFile(name: String, parentID: String, token: String): String? {
        val path = "$parentID/${sanitise(name)}"
        val request = authorisedRequest(path).newBuilder().method("HEAD", null).build()
        val ok = withContext(Dispatchers.IO) {
            HttpClients.shared.newCall(request).execute().use { it.isSuccessful }
        }
        return if (ok) path else null
    }

    override suspend fun deleteFile(fileID: String, token: String) {
        val request = authorisedRequest(fileID).newBuilder().delete().build()
        val handled = withContext(Dispatchers.IO) {
            HttpClients.shared.newCall(request).execute().use { it.isSuccessful || it.code == 404 }
        }
        if (!handled) throw WebDAVException("Could not delete $fileID")
    }

    // MARK: Transfers

    /**
     * WebDAV uploads are a single PUT, so there is no session to negotiate:
     * the "session URI" is simply where the file will live.
     */
    override suspend fun beginResumableSession(
        fileName: String, mimeType: String, fileSize: Long, folderID: String, token: String,
    ): String = "$folderID/${sanitise(fileName)}"

    override suspend fun uploadResumable(
        sessionURI: String, file: File, mimeType: String, alreadyStarted: Boolean, token: String,
        onProgress: ((sent: Long, total: Long) -> Unit)?,
    ): String = withContext(Dispatchers.IO) {
        val media = mimeType.toMediaType()
        val body = if (onProgress != null) {
            ProgressRequestBody(file, media, onProgress = onProgress)
        } else {
            file.asRequestBody(media)
        }
        val request = authorisedRequest(sessionURI).newBuilder()
            .put(body)
            .build()
        HttpClients.shared.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw WebDAVException("Upload failed (${response.code})")
        }
        // A successful PUT returns no body; the path was already known.
        sessionURI
    }

    override suspend fun multipartUpload(
        file: File, uploadName: String, mimeType: String, folderID: String, token: String,
    ): String = uploadResumable(
        sessionURI = "$folderID/${sanitise(uploadName)}",
        file = file, mimeType = mimeType, alreadyStarted = false, token = token, onProgress = null,
    )

    override suspend fun updateFile(fileID: String, file: File, mimeType: String, token: String): String =
        uploadResumable(fileID, file, mimeType, alreadyStarted = false, token = token, onProgress = null)

    // MARK: Sharing

    /**
     * Plain WebDAV defines no way to grant another person access. Nextcloud
     * and ownCloud each add their own non-standard API for it, so supporting
     * this would mean detecting and special-casing particular servers rather
     * than speaking WebDAV.
     */
    override val supportsEmailSharing = false

    override suspend fun share(fileID: String, email: String, token: String) {
        throw WebDAVException("This server can't share files automatically")
    }

    // MARK: - Credentials

    private fun serverURL(): String? = Prefs.getString(SERVER_URL_KEY)
    private fun username(): String? = Prefs.getString(USERNAME_KEY)

    private fun credentials(): Triple<String, String, String>? {
        val url = serverURL() ?: return null
        val user = username() ?: return null
        val password = SecureStore.string(SecureStore.Key.WEBDAV_PASSWORD) ?: return null
        if (user.isEmpty() || password.isEmpty()) return null
        return Triple(url, user, password)
    }

    private fun basicAuthHeader(): String? {
        val (_, user, password) = credentials() ?: return null
        val encoded = Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        return "Basic $encoded"
    }

    private fun computeAccountLabel(): String? {
        val (url, user, _) = credentials() ?: return null
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: return null
        return "$user @ $host"
    }

    /**
     * Stores a configuration after [verify] has confirmed the server accepts
     * it, so the app never keeps credentials it has never seen work.
     */
    fun store(serverURL: String, username: String, password: String) {
        Prefs.setString(SERVER_URL_KEY, serverURL)
        Prefs.setString(USERNAME_KEY, username)
        SecureStore.set(password, SecureStore.Key.WEBDAV_PASSWORD)
        _accountLabel.value = computeAccountLabel()
    }

    /**
     * The addresses worth trying for what the user typed, in order.
     *
     * People paste the address they use in a browser, because that is the one
     * they have. On Nextcloud and ownCloud that is the web interface, not the
     * WebDAV endpoint, and the two are different paths on the same host - so
     * the obvious thing to enter is the one thing that cannot work. The
     * endpoint is derived by a fixed rule, so rather than explaining the rule
     * in a footer nobody reads, this applies it.
     */
    fun candidateURLs(serverURL: String, username: String): List<String> {
        val candidates = mutableListOf(serverURL)
        // Compared as path segments, not as a substring: a trailing slash
        // would otherwise make ".../dav/" miss and earn a pointless second
        // probe at ".../dav/remote.php/dav/files/...".
        val segments = runCatching { android.net.Uri.parse(serverURL).pathSegments }
            .getOrNull().orEmpty().map { it.lowercase() }.toSet()
        if (segments.intersect(setOf("remote.php", "dav", "webdav")).isNotEmpty()) return candidates
        if (username.isEmpty()) return candidates

        val base = serverURL.trimEnd('/')
        candidates.add("$base/remote.php/dav/files/$username")
        return candidates
    }

    /**
     * Checks a server and credentials before they are saved, so a typo
     * surfaces during setup rather than as silently failing uploads during an
     * encounter.
     *
     * Returns the address that actually answered, which may not be the one
     * that was typed - see [candidateURLs]. The caller stores that one.
     */
    suspend fun verify(serverURL: String, username: String, password: String): String {
        var lastError = WebDAVException("No response from the server")
        for (candidate in candidateURLs(serverURL, username)) {
            try {
                probe(candidate, username, password)
                return candidate
            } catch (error: WebDAVException) {
                // Rejected credentials are rejected at every path on the host,
                // so there is nothing to gain from trying the next one, and
                // "isn't a WebDAV folder" would be the wrong thing to report.
                if (error.isCredentialFailure) throw error
                lastError = error
            }
        }
        throw lastError
    }

    private suspend fun probe(url: String, username: String, password: String) {
        val header = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        val request = Request.Builder()
            .url(url)
            .header("Depth", "0")
            .header("Content-Type", "application/xml")
            .header("Authorization", header)
            .method("PROPFIND", "".toRequestBody("application/xml".toMediaType()))
            .build()
        val code = withContext(Dispatchers.IO) {
            // Redirects are refused while verifying. A Nextcloud web address
            // answers PROPFIND with a 301 to its login page; followed, that
            // can come back 200 and a server that cannot store a single byte
            // looks like a working one - the failure then surfaces
            // mid-encounter instead of during setup, which is the one place
            // this check exists to prevent.
            val client = HttpClients.shared.newBuilder().followRedirects(false).build()
            runCatching { client.newCall(request).execute().use { it.code } }
                .getOrElse { throw WebDAVException("No response from the server") }
        }
        when {
            code in 200..299 -> return
            code == 401 || code == 403 -> throw WebDAVException(
                "The server rejected that username or password. On Nextcloud, use an app " +
                    "password from Settings > Security, not your account password.",
                isCredentialFailure = true,
            )
            code in 300..399 -> throw WebDAVException(
                "That address redirects elsewhere, so it isn't the WebDAV folder. " +
                    "On Nextcloud it is under Settings > WebDAV."
            )
            else -> throw WebDAVException(
                "That address isn't a WebDAV folder (the server returned $code). " +
                    "On Nextcloud it is under Settings > WebDAV."
            )
        }
    }

    // MARK: - Requests

    private fun authorisedRequest(path: String): Request {
        val builder = Request.Builder().url(absoluteURL(path))
        basicAuthHeader()?.let { builder.header("Authorization", it) }
        return builder.build()
    }

    private fun absoluteURL(path: String): String {
        val base = serverURL() ?: throw WebDAVException("WebDAV is not set up")
        val trimmedBase = base.trimEnd('/')
        val trimmedPath = path.trim('/').split("/").joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "$trimmedBase/$trimmedPath"
    }

    /**
     * MKCOL, treating "already exists" as success - the pipeline calls this on
     * every chunk and a folder existing is the normal case, not an error.
     *
     * Servers disagree about how to say "already exists". RFC 4918 specifies
     * 405, and that is what this used to accept. Apache's mod_dav instead
     * reports the underlying `EEXIST` as **403**, which was read as a hard
     * failure - so on those servers the first chunk created the folder and
     * every chunk after it threw. Others use 409, and some redirect.
     *
     * Rather than keep collecting status codes, an unexpected one now asks
     * the server the question that actually matters: is the collection there?
     * If it is, the goal is met however the server phrased it. If it is not,
     * this was a real failure and still throws.
     */
    private suspend fun makeCollection(path: String) {
        val request = authorisedRequest(path).newBuilder().method("MKCOL", null).build()
        val code = withContext(Dispatchers.IO) {
            runCatching { HttpClients.shared.newCall(request).execute().use { it.code } }
                .getOrElse { throw WebDAVException("No response creating $path") }
        }
        // 201 created, 405 already exists per spec.
        if (code in 200..299 || code == 405) return
        if (collectionExists(path)) return
        throw WebDAVException("Could not create folder ($code)")
    }

    /**
     * PROPFIND with `Depth: 0`, which is the WebDAV way to ask whether a
     * single resource exists. HEAD would be simpler but is unreliable here:
     * plenty of servers answer 405 for HEAD on a collection, which is
     * indistinguishable from the collection being absent.
     */
    private suspend fun collectionExists(path: String): Boolean {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = authorisedRequest(path).newBuilder()
            .header("Depth", "0")
            .method("PROPFIND", body)
            .build()
        return withContext(Dispatchers.IO) {
            // 207 Multi-Status is the expected answer; accept any 2xx so a
            // server that answers 200 to a Depth-0 PROPFIND is not called a
            // failure.
            runCatching { HttpClients.shared.newCall(request).execute().use { it.code in 200..299 } }
                .getOrDefault(false)
        }
    }

    /** Strips characters that would change the meaning of a path. */
    private fun sanitise(component: String): String = component.replace("/", "-").replace("\\", "-")
}
