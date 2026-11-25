package com.witnessrecord.app.service.storage

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.witnessrecord.app.service.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Signs in to Nextcloud the way Nextcloud's own clients do, so nobody has to
 * go and make an app password by hand.
 *
 * The friction this removes is real: Nextcloud rejects an account password for
 * WebDAV whenever two-factor authentication is on, which is the configuration
 * anyone running their own server for this kind of footage is likely to have.
 * The fix has been "create an app password first", which is a detour through a
 * settings page most people have never opened, on a server they set up once.
 *
 * Login Flow v2 removes the detour without weakening anything. The app asks
 * the server to start a flow, opens the server's *own* login page, and polls
 * until the server hands back a device-specific app password. The account
 * password is typed into Nextcloud's page and never reaches this app - which
 * is better than what it replaces, not merely easier: there is no moment at
 * which Witness holds a credential that could unlock the whole account, and
 * the user can revoke this one device from Nextcloud without changing
 * anything else.
 *
 * https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/
 */
object NextcloudLoginFlow {

    class FlowException(message: String) : IOException(message)

    data class Credentials(val server: String, val loginName: String, val appPassword: String)

    /**
     * Named so the credential shows up as "Witness" in Nextcloud's Devices &
     * sessions list, where it can be recognised and revoked on its own.
     */
    private const val USER_AGENT = "Witness"

    /**
     * The server keeps the flow open for 20 minutes. This gives up sooner:
     * past a couple of minutes the browser has been abandoned, and a poll
     * running on in the background is a surprise rather than a convenience.
     */
    private const val POLL_TIMEOUT_MILLIS = 150_000L
    private const val POLL_INTERVAL_MILLIS = 1_000L

    private data class Flow(val login: String, val token: String, val endpoint: String)

    suspend fun signIn(context: Context, serverURL: String): Credentials {
        val flow = begin(serverURL)
        present(context, flow.login)
        return awaitCredentials(token = flow.token, endpoint = flow.endpoint)
    }

    private suspend fun begin(serverURL: String): Flow {
        val url = serverURL.trimEnd('/') + "/index.php/login/v2"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(ByteArray(0).toRequestBody(null))
            .build()

        val body = withContext(Dispatchers.IO) {
            HttpClients.shared.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful || text == null) {
                    throw FlowException(
                        "That server didn't offer a Nextcloud sign-in (it returned " +
                            "${response.code}). Check the address, or enter a username and " +
                            "app password instead."
                    )
                }
                text
            }
        }

        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw FlowException("That server answered, but not like a Nextcloud server.")
        val poll = json.optJSONObject("poll")
        val login = json.optString("login").takeIf { it.isNotEmpty() }
        val token = poll?.optString("token")?.takeIf { it.isNotEmpty() }
        val endpoint = poll?.optString("endpoint")?.takeIf { it.isNotEmpty() }
        if (login == null || token == null || endpoint == null) {
            throw FlowException("That server answered, but not like a Nextcloud server.")
        }
        return Flow(login, token, endpoint)
    }

    /**
     * The server's own login page, in a Custom Tab. Nothing redirects back
     * here: Login Flow v2 finishes on the server and the result is collected
     * by polling, so the browser is simply left showing the server's
     * "account connected" page.
     */
    private fun present(context: Context, login: String) {
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(login))
    }

    private suspend fun awaitCredentials(token: String, endpoint: String): Credentials {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MILLIS

        while (System.currentTimeMillis() < deadline) {
            val request = Request.Builder()
                .url(endpoint)
                .header("User-Agent", USER_AGENT)
                .post("token=$token".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()

            val (status, body) = withContext(Dispatchers.IO) {
                HttpClients.shared.newCall(request).execute().use { it.code to it.body?.string() }
            }

            if (status == 200) {
                val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: throw FlowException(
                        "The server confirmed the sign-in but sent back something unreadable."
                    )
                val server = json.optString("server").takeIf { it.isNotEmpty() }
                val loginName = json.optString("loginName").takeIf { it.isNotEmpty() }
                val appPassword = json.optString("appPassword").takeIf { it.isNotEmpty() }
                if (server == null || loginName == null || appPassword == null) {
                    throw FlowException(
                        "The server confirmed the sign-in but sent back something unreadable."
                    )
                }
                return Credentials(server, loginName, appPassword)
            }

            // 404 is the documented "not finished yet"; anything else is the
            // flow having expired or been refused, and polling on would only
            // delay saying so.
            if (status != 404) throw FlowException("The sign-in was refused ($status). Try again.")
            delay(POLL_INTERVAL_MILLIS)
        }
        throw FlowException("The sign-in wasn't completed in time. Try again.")
    }
}
