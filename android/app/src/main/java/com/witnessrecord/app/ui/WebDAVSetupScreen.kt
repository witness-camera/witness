package com.witnessrecord.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.witnessrecord.app.R
import com.witnessrecord.app.service.storage.NextcloudLoginFlow
import com.witnessrecord.app.service.storage.WebDAVProvider
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.WitnessOrange
import kotlinx.coroutines.launch

/**
 * Collects a WebDAV server and credentials, and refuses to save them until
 * the server has actually accepted them.
 *
 * Verifying before saving matters more here than in a typical settings form:
 * a mistyped address or password wouldn't fail visibly, it would fail as
 * uploads that quietly never arrive - discovered, at the worst possible
 * moment, as an empty folder after an encounter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDAVSetupScreen(onDismiss: () -> Unit, onConnected: () -> Unit) {
    BackHandler { onDismiss() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var serverAddress by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var isSigningIn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val busy = isVerifying || isSigningIn
    val canSubmit = serverAddress.isNotBlank() && username.isNotEmpty() && password.isNotEmpty() && !busy
    val badAddressMessage = stringResource(R.string.webdav_bad_address)
    val requiresHttpsMessage = stringResource(R.string.webdav_requires_https)
    val nextcloudFailedMessage = stringResource(R.string.webdav_nextcloud_failed)

    /** Returns the trimmed address, or null having set the error message. */
    fun validatedAddress(): String? {
        val trimmed = serverAddress.trim()
        val uri = runCatching { android.net.Uri.parse(trimmed) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrEmpty() || uri.host.isNullOrEmpty()) {
            errorMessage = badAddressMessage
            return null
        }
        if (uri.scheme?.lowercase() != "https") {
            errorMessage = requiresHttpsMessage
            return null
        }
        return trimmed
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                title = { Text(stringResource(R.string.webdav_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel), tint = Color.White)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            DarkTextField(
                value = serverAddress,
                onValueChange = { serverAddress = it },
                placeholder = stringResource(R.string.webdav_address_placeholder),
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = stringResource(R.string.webdav_username),
                fontSize = 13.sp, color = SecondaryLabel,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
            )
            DarkTextField(value = username, onValueChange = { username = it }, placeholder = stringResource(R.string.webdav_username))
            Text(
                text = stringResource(R.string.webdav_password),
                fontSize = 13.sp, color = SecondaryLabel,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
            )
            androidx.compose.material3.OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = Color.White.copy(alpha = 0.1f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.1f),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.webdav_server_footer),
                fontSize = 12.sp, color = SecondaryLabel,
                modifier = Modifier.padding(top = 8.dp),
            )

            // Offered above the username and password fields because it
            // replaces them: the server issues Witness its own app password,
            // so the account password is never typed into this app at all -
            // and on a server with two-factor sign-in it is the only way in.
            HeroPillButton(
                text = if (isSigningIn) {
                    stringResource(R.string.webdav_nextcloud_waiting)
                } else {
                    stringResource(R.string.webdav_nextcloud_sign_in)
                },
                enabled = serverAddress.isNotBlank() && !busy,
                onClick = {
                    errorMessage = null
                    val trimmed = validatedAddress() ?: return@HeroPillButton
                    isSigningIn = true
                    scope.launch {
                        try {
                            val credentials = NextcloudLoginFlow.signIn(context, trimmed)
                            // Verified like any other credentials rather than
                            // trusted because the server issued them: the
                            // address that serves the login page is not
                            // necessarily the one that serves WebDAV, and
                            // verify is what resolves it.
                            val resolved = WebDAVProvider.verify(
                                credentials.server, credentials.loginName, credentials.appPassword
                            )
                            WebDAVProvider.store(
                                resolved, credentials.loginName, credentials.appPassword
                            )
                            onConnected()
                            onDismiss()
                        } catch (error: NextcloudLoginFlow.FlowException) {
                            // Not a Nextcloud server, or it refused the flow.
                            // The form below still works, so this says so
                            // rather than presenting it as a dead end.
                            errorMessage = nextcloudFailedMessage
                        } catch (error: Throwable) {
                            errorMessage = error.message
                        } finally {
                            isSigningIn = false
                        }
                    }
                },
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = stringResource(R.string.webdav_nextcloud_footer),
                fontSize = 12.sp, color = SecondaryLabel,
                modifier = Modifier.padding(top = 8.dp),
            )

            errorMessage?.let {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = WitnessOrange, modifier = Modifier.size(16.dp))
                    Text(text = it, fontSize = 13.sp, color = WitnessOrange)
                }
            }

            HeroPillButton(
                text = if (isVerifying) stringResource(R.string.webdav_checking) else stringResource(R.string.webdav_connect),
                enabled = canSubmit,
                onClick = {
                    errorMessage = null
                    val trimmed = validatedAddress() ?: return@HeroPillButton
                    isVerifying = true
                    scope.launch {
                        try {
                            // Stored at the address that actually answered, not
                            // the one that was typed: a Nextcloud web address
                            // is not its WebDAV endpoint, and verify is what
                            // works out which URL underneath it is.
                            val resolved = WebDAVProvider.verify(trimmed, username, password)
                            WebDAVProvider.store(resolved, username, password)
                            onConnected()
                            onDismiss()
                        } catch (error: Throwable) {
                            errorMessage = error.message
                        } finally {
                            isVerifying = false
                        }
                    }
                },
                modifier = Modifier.padding(top = 20.dp),
            )
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 12.dp).size(18.dp),
                    color = Color.White, strokeWidth = 2.dp,
                )
            }
            Text(
                text = stringResource(R.string.webdav_sharing_footer),
                fontSize = 12.sp, color = SecondaryLabel,
                modifier = Modifier.padding(top = 16.dp, bottom = 32.dp),
            )
        }
    }
}
