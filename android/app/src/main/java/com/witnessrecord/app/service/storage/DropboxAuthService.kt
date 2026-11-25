package com.witnessrecord.app.service.storage

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import com.witnessrecord.app.service.HttpClients
import com.witnessrecord.app.service.Prefs
import com.witnessrecord.app.service.SecureStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Dropbox sign-in over OAuth 2 with PKCE.
 *
 * PKCE rather than a client secret because this is a public client: the app
 * key below ships inside the APK and anyone can read it out. That is
 * expected and safe - the key alone authorises nothing. Each sign-in mints a
 * random verifier on the device and sends only its hash to Dropbox, so an
 * intercepted authorisation code is worthless without the verifier, which
 * never leaves the phone.
 *
 * The redirect leaves the app entirely (a Custom Tab) and returns through a
 * manifest intent-filter rather than an activity-result contract, so the
 * verifier is written to disk for the round trip - Android can and does kill
 * the process while Chrome is in front.
 */
object DropboxAuthService {

    /**
     * The same public app key the iOS build uses - Dropbox app keys are not
     * platform-scoped, only their registered redirect URIs are. Reusing it
     * needs exactly one addition in the Dropbox App Console: this app's
     * "witnessrecord://dropbox-auth" redirect URI (the iOS build already
     * registers the identical URI, so this is very likely a no-op).
     */
    val APP_KEY: String = com.witnessrecord.app.BuildConfig.DROPBOX_APP_KEY

    private const val REDIRECT_URI = "witnessrecord://dropbox-auth"
    private const val AUTHORIZE_ENDPOINT = "https://www.dropbox.com/oauth2/authorize"
    private const val TOKEN_ENDPOINT = "https://api.dropboxapi.com/oauth2/token"
    private const val ACCOUNT_ENDPOINT = "https://api.dropboxapi.com/2/users/get_current_account"
    private const val VERIFIER_PREF_KEY = "dropboxPendingVerifier"
    private const val ACCOUNT_EMAIL_PREF_KEY = "dropboxAccountEmail"

    /** Least privilege, matching Google's `drive.file`: App-folder access confines everything to the app's own directory. */
    private val scopes = listOf(
        "account_info.read", "files.content.write", "files.content.read", "sharing.write",
    )

    class DropboxException(message: String) : IOException(message)

    private val _accountEmail = MutableStateFlow(Prefs.getString(ACCOUNT_EMAIL_PREF_KEY))
    val accountEmail: StateFlow<String?> = _accountEmail

    @Volatile private var accessToken: String? = null
    @Volatile private var accessTokenExpiryMillis: Long = 0

    /** Resolved by [handleRedirect] once the Custom Tab returns. */
    @Volatile private var pendingSignIn: CompletableDeferred<String>? = null

    val isSignedIn: Boolean get() = refreshToken != null
    private val refreshToken: String? get() = SecureStore.string(SecureStore.Key.DROPBOX_REFRESH_TOKEN)

    fun restore() {
        _accountEmail.value = Prefs.getString(ACCOUNT_EMAIL_PREF_KEY)
    }

    fun signOut() {
        SecureStore.delete(SecureStore.Key.DROPBOX_REFRESH_TOKEN)
        Prefs.setString(ACCOUNT_EMAIL_PREF_KEY, null)
        accessToken = null
        accessTokenExpiryMillis = 0
        _accountEmail.value = null
    }

    // MARK: - Sign in

    suspend fun signIn(activity: ComponentActivity) {
        if (APP_KEY.isBlank()) throw DropboxException("Dropbox isn't configured in this build")

        val verifier = makeCodeVerifier()
        val challenge = codeChallenge(verifier)
        // Persisted, not held in memory: the process may not survive the trip
        // through Chrome, and losing the verifier here would strand the flow
        // with an authorisation code that can never be redeemed.
        Prefs.setString(VERIFIER_PREF_KEY, verifier)

        val url = Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", APP_KEY)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            // Without this Dropbox issues only a short-lived token, and the
            // app would silently stop uploading a few hours into the day.
            .appendQueryParameter("token_access_type", "offline")
            .appendQueryParameter("scope", scopes.joinToString(" "))
            .build()

        val deferred = CompletableDeferred<String>()
        pendingSignIn = deferred
        CustomTabsIntent.Builder().build().launchUrl(activity, url)

        val code = try {
            withTimeout(5 * 60 * 1000L) { deferred.await() }
        } finally {
            pendingSignIn = null
        }

        exchange(code = code, verifier = verifier)
        runCatching { fetchAccountEmail() }
    }

    /**
     * Called from [com.witnessrecord.app.MainActivity] when the OAuth
     * redirect intent arrives. Safe to call even with nothing pending - a
     * cold-started activity re-delivering the same intent, or a stray deep
     * link, both no-op here.
     */
    fun handleRedirect(uri: Uri) {
        val deferred = pendingSignIn ?: return
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
        when {
            code != null -> deferred.complete(code)
            error != null -> deferred.completeExceptionally(DropboxException(error))
            else -> deferred.completeExceptionally(
                DropboxException("Dropbox didn't return an authorisation code")
            )
        }
    }

    private suspend fun exchange(code: String, verifier: String) {
        val json = postForm(
            mapOf(
                "code" to code,
                "grant_type" to "authorization_code",
                "client_id" to APP_KEY,
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier,
            )
        )
        val refresh = json.optString("refresh_token").takeIf { it.isNotEmpty() }
            ?: throw DropboxException("Dropbox didn't return a refresh token")
        SecureStore.set(refresh, SecureStore.Key.DROPBOX_REFRESH_TOKEN)
        cache(accessTokenJson = json)
    }

    // MARK: - Tokens

    suspend fun freshAccessToken(): String {
        val token = accessToken
        if (token != null && System.currentTimeMillis() < accessTokenExpiryMillis - 60_000) {
            return token
        }
        val refresh = refreshToken ?: throw DropboxException("Not signed in to Dropbox")
        val json = postForm(
            mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to APP_KEY)
        )
        cache(accessTokenJson = json)
        return accessToken ?: throw DropboxException("Dropbox didn't return an access token")
    }

    private fun cache(accessTokenJson: JSONObject) {
        accessToken = accessTokenJson.optString("access_token").takeIf { it.isNotEmpty() }
        val lifetimeSeconds = accessTokenJson.optDouble("expires_in", 14_400.0)
        accessTokenExpiryMillis = System.currentTimeMillis() + (lifetimeSeconds * 1000).toLong()
    }

    private suspend fun postForm(fields: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val body = fields.entries
            .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(body).build()
        HttpClients.shared.newCall(request).execute().use { response ->
            val text = response.body?.string()
            if (!response.isSuccessful || text == null) {
                throw DropboxException("Dropbox rejected the sign-in request")
            }
            JSONObject(text)
        }
    }

    private suspend fun fetchAccountEmail() {
        val token = freshAccessToken()
        val request = Request.Builder()
            .url(ACCOUNT_ENDPOINT)
            .header("Authorization", "Bearer $token")
            .post("null".toRequestBody("application/json".toMediaType()))
            .build()
        val email = withContext(Dispatchers.IO) {
            HttpClients.shared.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: return@use null
                runCatching { JSONObject(text).optString("email") }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
            }
        } ?: return
        _accountEmail.value = email
        Prefs.setString(ACCOUNT_EMAIL_PREF_KEY, email)
    }

    // MARK: - PKCE

    private fun makeCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return base64UrlEncode(bytes)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return base64UrlEncode(digest)
    }

    /** base64url per RFC 7636: standard base64's '+', '/' and '=' are not URL-safe. */
    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
