package com.witnessrecord.app.service.storage

import androidx.activity.ComponentActivity
import com.witnessrecord.app.service.GoogleAuthService
import com.witnessrecord.app.service.GoogleDriveClient
import java.io.File

/**
 * Google Drive behind the common [StorageProvider] interface.
 *
 * Purely an adapter: the request-building and auth logic still live in
 * [GoogleDriveClient] and [GoogleAuthService], which were already correct and
 * well tested. Nothing about Google's behaviour changes by being reached
 * through the interface.
 */
object GoogleDriveProvider : StorageProvider {
    override val id = StorageProviderID.GOOGLE_DRIVE

    override val accountLabelFlow get() = GoogleAuthService.userEmailFlow

    override suspend fun restore() = GoogleAuthService.restore()

    /** Google's consent screen is launched from the Activity by the caller; this only performs the token exchange. */
    override suspend fun signIn(activity: ComponentActivity) = GoogleAuthService.signIn()

    override fun signOut() = GoogleAuthService.signOut()

    override suspend fun freshAccessToken(): String = GoogleAuthService.freshAccessToken()

    override suspend fun availableBytes(token: String): Long? =
        GoogleDriveClient.availableBytes(token)

    override suspend fun ensureRootFolder(name: String, token: String): String =
        GoogleDriveClient.ensureFolder(name, token)

    override suspend fun ensureSubfolder(name: String, parentID: String, token: String): String =
        GoogleDriveClient.ensureSubfolder(name, parentID, token)

    override suspend fun findFile(name: String, parentID: String, token: String): String? =
        GoogleDriveClient.findFile(name, parentID, token)

    override suspend fun deleteFile(fileID: String, token: String) =
        GoogleDriveClient.deleteFile(fileID, token)

    override suspend fun beginResumableSession(
        fileName: String, mimeType: String, fileSize: Long, folderID: String, token: String,
    ): String = GoogleDriveClient.beginResumableSession(fileName, mimeType, fileSize, folderID, token)

    override suspend fun uploadResumable(
        sessionURI: String, file: File, mimeType: String, alreadyStarted: Boolean, token: String,
        onProgress: ((sent: Long, total: Long) -> Unit)?,
    ): String = GoogleDriveClient.uploadResumable(sessionURI, file, mimeType, alreadyStarted, onProgress)

    override suspend fun multipartUpload(
        file: File, uploadName: String, mimeType: String, folderID: String, token: String,
    ): String = GoogleDriveClient.multipartUpload(file, uploadName, mimeType, folderID, token)

    override suspend fun updateFile(fileID: String, file: File, mimeType: String, token: String): String =
        GoogleDriveClient.updateFile(fileID, file, mimeType, token)

    override val supportsEmailSharing = true

    override suspend fun share(fileID: String, email: String, token: String) =
        GoogleDriveClient.shareFile(fileID, email, token)
}
