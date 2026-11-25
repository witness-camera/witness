package com.witnessrecord.app.service

import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.witnessrecord.app.WitnessApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Google authorization for the Drive upload pipeline.
 *
 * Uses Play Services' [AuthorizationClient] rather than the deprecated
 * GoogleSignIn SDK the iOS build's counterpart wraps. It asks for exactly one
 * scope - `drive.file`, which grants access only to files this app itself
 * creates and none of the user's existing Drive - and hands back a live
 * access token, refreshing it silently on every call once the user has
 * granted consent once.
 *
 * Nothing here ever persists a token: Play Services owns the credential, so a
 * filesystem dump of the app's data never yields one.
 */
object GoogleAuthService {

    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

    // Seeded from the last email actually looked up here, not left at null
    // until a fresh network round trip completes. Two things depended on that
    // gap: the home screen read "not connected" for the length of every
    // cold-start lookup, and - see adopt() below - a lookup that failed once
    // had nothing good to fall back to and reported real, granted
    // authorizations as signed out.
    private val _userEmail = MutableStateFlow(SecureStore.string(SecureStore.Key.GOOGLE_ACCOUNT))
    val userEmailFlow: StateFlow<String?> = _userEmail.asStateFlow()
    val userEmail: String? get() = _userEmail.value

    // A blank string counts as signed in - "authorized, display name not
    // known yet" - never as signed out. Every reader of userEmailFlow already
    // falls back to something sensible when the label itself is blank (the
    // provider's own name, or no "as ..." suffix at all); what none of them
    // could recover from was this flag going back to null after a real,
    // granted authorization, which is what used to happen here.
    val isSignedIn: Boolean get() = _userEmail.value != null

    private val client by lazy {
        Identity.getAuthorizationClient(WitnessApplication.appContext)
    }

    private val request: AuthorizationRequest by lazy {
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .build()
    }

    class NotSignedInException : Exception(
        "Not signed in to Google Drive. Footage stays queued locally until you sign in."
    )

    class ConsentRequiredException(val intentSender: IntentSenderRequest) : Exception(
        "Google Drive access needs your approval."
    )

    /**
     * Call once at launch to restore a previous authorization. Silent: if the
     * user has never granted the scope, this does nothing rather than putting
     * a consent sheet in front of someone who just opened the app.
     */
    suspend fun restore() {
        val result = runCatching { client.authorize(request).await() }.getOrNull() ?: return
        if (result.hasResolution()) return
        adopt(result)
    }

    /**
     * Requests authorization, surfacing Google's consent screen if needed.
     *
     * Throws [ConsentRequiredException] carrying the intent the caller must
     * launch through an activity-result contract; feed the outcome back via
     * [handleAuthorizationResult].
     */
    suspend fun signIn() {
        val result = client.authorize(request).await()
        val pendingIntent = result.pendingIntent
        if (result.hasResolution() && pendingIntent != null) {
            throw ConsentRequiredException(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        }
        if (result.accessToken == null) {
            throw IllegalStateException("Google returned no access token")
        }
        adopt(result)
    }

    /** Completes a sign-in that had to go through the consent screen. */
    suspend fun handleAuthorizationResult(data: Intent?) {
        val result = client.getAuthorizationResultFromIntent(data)
        if (result.accessToken == null) {
            throw IllegalStateException("Google returned no access token")
        }
        adopt(result)
    }

    private suspend fun adopt(result: AuthorizationResult) {
        val token = result.accessToken ?: return

        // Authorization itself has already succeeded the moment there is a
        // token - Play Services granted it. Mark that immediately, before the
        // network call below, so a failure in that call can never again be
        // mistaken for a failed sign-in.
        if (_userEmail.value == null) _userEmail.value = ""

        // `drive.file` is enough to read the signed-in account's own profile
        // from Drive itself, so the account label costs no extra scope - and
        // the app never asks for the user's contacts or profile.
        //
        // Best-effort and never authoritative for whether sign-in succeeded:
        // this is a second, independent network call landing in the same
        // instant the app regains focus from Google's own consent screen -
        // about the highest-risk moment there is for a slow DNS lookup or a
        // dropped connection. It used to be trusted as sign-in itself, so
        // when it failed here it overwrote a real, granted authorization with
        // null and the home screen was stuck reading "not connected" - with
        // no error shown and nothing to retry it - until something else
        // happened to call this again. One retry, then leave it for the next
        // natural call (app relaunch, or the next sign-in attempt) rather
        // than looping.
        var email = GoogleDriveClient.currentUserEmail(token)
        if (email == null) {
            delay(1_500)
            email = GoogleDriveClient.currentUserEmail(token)
        }
        if (email != null) {
            _userEmail.value = email
            SecureStore.set(email, SecureStore.Key.GOOGLE_ACCOUNT)
        }
        EventLogger.log(
            "drive.signed_in",
            detail = email ?: "(email lookup failed - still signed in)"
        )
    }

    fun signOut() {
        runCatching { client.authorize(request) } // no-op; state cleared below
        _userEmail.value = null
        SecureStore.delete(SecureStore.Key.GOOGLE_ACCOUNT)
        SecureStore.delete(SecureStore.Key.DRIVE_FOLDER_ID)
        Prefs.setStringMap("sessionFolderIDs", emptyMap())
        EventLogger.log("drive.signed_out")
    }

    /**
     * Returns a valid access token, refreshing through Play Services if the
     * previous one expired. Throws [NotSignedInException] when the user has
     * never granted the scope, which is what keeps chunks queued on disk
     * instead of being discarded.
     */
    suspend fun freshAccessToken(): String {
        val result = runCatching { client.authorize(request).await() }.getOrNull()
            ?: throw NotSignedInException()
        if (result.hasResolution()) throw NotSignedInException()
        return result.accessToken ?: throw NotSignedInException()
    }
}
