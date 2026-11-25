package com.witnessrecord.app.service.storage

import androidx.activity.ComponentActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.witnessrecord.app.R
import com.witnessrecord.app.service.AppSettings
import com.witnessrecord.app.service.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Which cloud account footage is uploaded to.
 *
 * Kept as a small enum rather than free-form strings so the stored choice
 * can't drift out of sync with what the app can actually talk to.
 */
enum class StorageProviderID(
    val wire: String,
    @StringRes val displayNameRes: Int,
    @StringRes val subtitleRes: Int,
    @DrawableRes val iconRes: Int?,
) {
    GOOGLE_DRIVE("googleDrive", R.string.storage_google_drive, R.string.storage_google_drive_subtitle, R.drawable.google_drive_icon),
    DROPBOX("dropbox", R.string.storage_dropbox, R.string.storage_dropbox_subtitle, null),
    WEBDAV("webDAV", R.string.storage_webdav, R.string.storage_webdav_subtitle, null),

    /**
     * Listed for transparency, not because it works: Proton has no public API
     * or WebDAV access, so there is no way for Witness to connect to it. See
     * [StorageProviders.isUnsupported].
     */
    PROTON("proton", R.string.storage_proton, R.string.storage_proton_subtitle, null);

    companion object {
        fun fromWire(value: String?): StorageProviderID =
            entries.firstOrNull { it.wire == value } ?: GOOGLE_DRIVE
    }
}

/**
 * Everything the upload pipeline needs from a storage backend.
 *
 * Every transfer is a plain suspend function the pipeline awaits directly,
 * rather than a request object handed to a background URLSession the way the
 * iOS build does it - Android's upload pipeline already runs its own
 * coroutine work queue plus a WorkManager job for process-death resilience,
 * so there is no OS-level background session to feed requests into.
 */
interface StorageProvider {
    val id: StorageProviderID

    // MARK: Account

    val accountLabelFlow: StateFlow<String?>
    val isSignedIn: Boolean get() = accountLabelFlow.value != null

    suspend fun restore()

    /**
     * Signs in, presenting whatever UI this backend needs from [activity].
     * WebDAV throws instead: it has no hosted sign-in page and is configured
     * through its own setup form.
     */
    suspend fun signIn(activity: ComponentActivity)
    fun signOut()

    /** A token valid right now, refreshed if needed. */
    suspend fun freshAccessToken(): String

    // MARK: Folders

    /**
     * Bytes still free in the account, or null where the backend will not say.
     *
     * Checked so that somebody is told their storage is full *before* they
     * record into it rather than after: a full account does not merely slow
     * uploads down, it blocks them outright - down to refusing to create the
     * folder to upload into. Null means "no answer", never "none left"; most
     * backends are not asked, because WebDAV servers disagree about quota
     * reporting and a wrong number would be worse than none.
     */
    suspend fun availableBytes(token: String): Long? = null

    suspend fun ensureRootFolder(name: String, token: String): String
    suspend fun ensureSubfolder(name: String, parentID: String, token: String): String
    suspend fun findFile(name: String, parentID: String, token: String): String?
    suspend fun deleteFile(fileID: String, token: String)

    // MARK: Transfers

    /** Starts a resumable upload and returns the session URI to resume against. */
    suspend fun beginResumableSession(
        fileName: String, mimeType: String, fileSize: Long, folderID: String, token: String,
    ): String

    /**
     * Uploads [file] against an open resumable session and returns the
     * created remote file ID. [alreadyStarted] asks the backend to resume
     * from wherever it left off rather than starting over.
     *
     * [onProgress] reports bytes sent against bytes expected, for the upload
     * queue sheet. Throttled by the caller's body wrapper, not per write.
     */
    suspend fun uploadResumable(
        sessionURI: String, file: File, mimeType: String, alreadyStarted: Boolean, token: String,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): String

    /** Small files sent in one shot; returns the created remote file ID. */
    suspend fun multipartUpload(
        file: File, uploadName: String, mimeType: String, folderID: String, token: String,
    ): String

    /** Overwrites an existing file's contents in place; returns its ID. */
    suspend fun updateFile(fileID: String, file: File, mimeType: String, token: String): String

    // MARK: Sharing

    /**
     * Whether this backend can grant a named person access programmatically.
     * False for stores with no such API, so the UI can say plainly that
     * emergency contacts won't be notified rather than failing silently.
     */
    val supportsEmailSharing: Boolean
    suspend fun share(fileID: String, email: String, token: String)
}

/**
 * The app's single source of truth for which backend is in use.
 */
object StorageProviders {
    const val SELECTED_PROVIDER_KEY = "storageProviderID"

    private val _selected = MutableStateFlow(
        StorageProviderID.fromWire(Prefs.getString(SELECTED_PROVIDER_KEY))
    )
    val selected: StateFlow<StorageProviderID> = _selected

    private val providers: Map<StorageProviderID, StorageProvider> by lazy {
        mapOf(
            StorageProviderID.GOOGLE_DRIVE to GoogleDriveProvider,
            StorageProviderID.DROPBOX to DropboxProvider,
            StorageProviderID.WEBDAV to WebDAVProvider,
        )
    }

    /** Nil for a provider that isn't implemented, so callers must decide what to do. */
    fun provider(id: StorageProviderID): StorageProvider? = providers[id]

    val current: StorageProvider? get() = providers[_selected.value]

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /**
     * Room left in the connected account, or null where it is unknown -
     * unlimited storage, a backend that will not say, or not asked yet.
     */
    private val _availableBytes = MutableStateFlow<Long?>(null)
    val availableBytes: StateFlow<Long?> = _availableBytes

    /**
     * How long the current capture settings could record before filling an
     * account with [bytes] left. Null when the room left is unknown.
     */
    fun recordableSeconds(bytes: Long?): Double? {
        if (bytes == null) return null
        val bytesPerSecond = AppSettings.videoCodec.estimatedBitRate(
            AppSettings.videoResolution, AppSettings.videoFrameRate
        ) / 8.0
        if (bytesPerSecond <= 0) return null
        return bytes / bytesPerSecond
    }

    /**
     * Asks the connected account how much room is left. Quiet on failure:
     * this is a courtesy, and an account that will not answer should not
     * produce an error in front of somebody about to record.
     */
    fun refreshAvailableSpace() {
        scope.launch {
            val provider = current
            if (provider == null || !provider.isSignedIn) {
                _availableBytes.value = null
                return@launch
            }
            _availableBytes.value = runCatching {
                provider.availableBytes(provider.freshAccessToken())
            }.getOrNull()
        }
    }

    fun isImplemented(id: StorageProviderID): Boolean = providers.containsKey(id)

    /** Whether choosing this provider leads to a setup form rather than a hosted sign-in page. */
    fun usesManualSetup(id: StorageProviderID): Boolean = id == StorageProviderID.WEBDAV

    /**
     * True for a backend that's listed for transparency but genuinely can't
     * be connected - not merely unimplemented, but structurally impossible
     * with what the service exposes. Proton Drive is the case today: every
     * file is encrypted client-side with keys derived from the account
     * before it ever reaches their servers, and they publish no API or
     * WebDAV access an outside app could use. Tapping the row explains this
     * instead of attempting a connection that would only fail, likely at the
     * worst possible moment.
     */
    fun isUnsupported(id: StorageProviderID): Boolean = id == StorageProviderID.PROTON

    fun select(id: StorageProviderID) {
        _selected.value = id
        Prefs.setString(SELECTED_PROVIDER_KEY, id.wire)
    }
}
