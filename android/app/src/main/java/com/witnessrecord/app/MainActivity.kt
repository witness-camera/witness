package com.witnessrecord.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ApiException
import com.witnessrecord.app.service.AppSettings
import com.witnessrecord.app.service.ChunkStore
import com.witnessrecord.app.service.EventLogger
import com.witnessrecord.app.service.GoogleAuthService
import com.witnessrecord.app.service.UploadManager
import com.witnessrecord.app.service.storage.DropboxAuthService
import com.witnessrecord.app.service.storage.StorageProvider
import com.witnessrecord.app.service.storage.StorageProviders
import com.witnessrecord.app.ui.RootScreen
import com.witnessrecord.app.ui.theme.WitnessTheme
import com.witnessrecord.app.viewmodel.CaptureViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * Google's consent screen, when the Drive scope has not been granted yet.
     * Held here rather than in the auth service because only an activity can
     * launch it.
     */
    private val authorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val onError = pendingSignInError
        pendingSignInError = null
        lifecycleScope.launch {
            if (result.resultCode != RESULT_OK && result.data == null) {
                // Backed out of the account or consent sheet: not an error.
                EventLogger.log("drive.sign_in_cancelled", detail = "resultCode=${result.resultCode}")
            } else {
                try {
                    GoogleAuthService.handleAuthorizationResult(result.data)
                } catch (error: Throwable) {
                    // Used to be swallowed, which left the sheet closing and
                    // nothing else happening - no connection, no message.
                    EventLogger.log("drive.sign_in_failed", detail = describe(error))
                    onError?.invoke(signInFailureMessage(error))
                }
            }
            UploadManager.pump()
        }
    }

    /** Where a Google consent result reports a failure; set while that sheet is up. */
    private var pendingSignInError: ((String) -> Unit)? = null

    private fun describe(error: Throwable): String =
        (error as? ApiException)?.let { "ApiException ${it.statusCode}: ${it.message}" }
            ?: "${error.javaClass.simpleName}: ${error.message}"

    private fun signInFailureMessage(error: Throwable): String {
        val base = getString(R.string.error_sign_in_failed)
        val code = (error as? ApiException)?.statusCode
        return if (code != null) "$base ($code)" else base
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Outcome is read back from the system, not from this callback. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark-only, so the bars always want light icons - left to
        // follow the system theme, a phone in light mode drew a black clock
        // on the black screen.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        handleIncomingIntent(intent)

        lifecycleScope.launch {
            StorageProviders.provider(com.witnessrecord.app.service.storage.StorageProviderID.GOOGLE_DRIVE)?.restore()
            StorageProviders.provider(com.witnessrecord.app.service.storage.StorageProviderID.DROPBOX)?.restore()
            StorageProviders.provider(com.witnessrecord.app.service.storage.StorageProviderID.WEBDAV)?.restore()
            AppSettings.refreshCloudAccountConnected()
            UploadManager.pump()
        }

        setContent {
            WitnessTheme {
                val recordingState by CaptureViewModel.state.collectAsState()
                val viewfinderVisible by CaptureViewModel.viewfinderVisible.collectAsState()
                val isRecording = recordingState is CaptureViewModel.State.Recording

                // Screen handling is a window concern, so it lives here rather
                // than in the capture layer: black out and hold the screen
                // awake while recording, restore normal behaviour otherwise.
                applyScreenMode(isRecording = isRecording, viewfinderVisible = viewfinderVisible)

                RootScreen(
                    onRequestCapturePermissions = ::requestCapturePermissions,
                    onRequestLocationPermission = ::requestLocationPermission,
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    onSignIn = ::signIn,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Routes an incoming VIEW intent to whichever flow owns its URI. */
    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data
        if (uri != null && uri.scheme == "witnessrecord" && uri.host == "dropbox-auth") {
            DropboxAuthService.handleRedirect(uri)
            return
        }
        AppState.handleIntent(intent)
    }

    /**
     * Volume buttons mark the moment (Pro) - works with the screen black and
     * the phone in a pocket, no looking needed. Only while recording, and only
     * for Pro: otherwise the buttons do what they always do.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) &&
            CaptureViewModel.isRecording && AppSettings.isProEntitled
        ) {
            if (event.repeatCount == 0) CaptureViewModel.addMarker()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) &&
            CaptureViewModel.isRecording && AppSettings.isProEntitled
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        CaptureViewModel.appMovedToBackground()
        // Queue writes are coalesced while the app is in use, so the last few
        // seconds of them may still be pending. Backgrounding is the point
        // after which the process can be killed without warning, so the queue
        // is put beyond doubt here rather than left to a timer that may not
        // get to run.
        ChunkStore.flush()
    }

    override fun onStart() {
        super.onStart()
        CaptureViewModel.appMovedToForeground()
        AppSettings.refreshCloudAccountConnected()
    }

    // MARK: - Screen

    /**
     * Blacks the screen out when recording without the viewfinder - to an
     * observer the phone looks off - and restores normal brightness when the
     * viewfinder is shown so the user can see what they are filming.
     *
     * `screenBrightness` is a per-window override, so it never touches the
     * user's system brightness setting and is undone automatically the moment
     * this window goes away.
     */
    private fun applyScreenMode(isRecording: Boolean, viewfinderVisible: Boolean) {
        val blackout = isRecording && !viewfinderVisible

        window.attributes = window.attributes.apply {
            screenBrightness = if (blackout) 0f else
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }

        if (isRecording) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isRecording) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // MARK: - Permissions

    private fun requestCapturePermissions() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    private fun requestLocationPermission() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    /**
     * Android 13+ requires notification permission before a foreground service
     * may post its notification - and without that notification the OS will
     * not let the service run at all, which would stop recording surviving the
     * screen going off.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    // MARK: - Cloud storage

    /**
     * Signs in to [provider]. Only Google's flow can throw
     * [GoogleAuthService.ConsentRequiredException] - Dropbox handles its own
     * Custom Tab round trip inside [StorageProvider.signIn], and WebDAV
     * throws immediately, routed to [onError] like any other failure.
     */
    private fun signIn(provider: StorageProvider, onError: (String) -> Unit) {
        lifecycleScope.launch {
            try {
                provider.signIn(this@MainActivity)
                AppSettings.refreshCloudAccountConnected()
                UploadManager.pump()
            } catch (consent: GoogleAuthService.ConsentRequiredException) {
                pendingSignInError = onError
                authorizationLauncher.launch(consent.intentSender)
            } catch (error: Throwable) {
                EventLogger.log("drive.sign_in_failed", detail = describe(error))
                onError(
                    if (error is ApiException) signInFailureMessage(error)
                    else error.message ?: getString(R.string.error_sign_in_failed)
                )
            }
        }
    }
}
