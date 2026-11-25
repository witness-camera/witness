package com.witnessrecord.app

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.witnessrecord.app.viewmodel.CaptureViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges the instant-start entry points - quick-settings tile, home screen
 * widget, assistant shortcut, power-button camera gesture - to the capture
 * layer. Any of them can fire before the activity exists, so the request is
 * latched and consumed once the UI is up.
 */
object AppState {

    /**
     * Set by any instant-start path; the root screen consumes it and goes
     * straight to black-screen recording, skipping all UI.
     */
    private val _autoStartRequested = MutableStateFlow(false)
    val autoStartRequested: StateFlow<Boolean> = _autoStartRequested.asStateFlow()

    fun requestImmediateRecording() {
        // A one-press start can show the camera instead of the black screen,
        // if that is what the person chose. Off leaves an already-open
        // viewfinder exactly as it is.
        if (com.witnessrecord.app.service.AppSettings.quickStartViewfinder && !isRecording) {
            CaptureViewModel.setViewfinderPreference(true)
        }
        _autoStartRequested.value = true
    }

    fun consumeAutoStart() {
        _autoStartRequested.value = false
    }

    /**
     * Whether an intent is one of the instant-start paths.
     *
     * The camera-gesture actions are included because a double-press of the
     * power button routes through them - but only when the user has switched
     * that entry point on in Settings, which is what enables the alias that
     * makes those actions reach this app at all.
     */
    fun isInstantStart(intent: Intent?): Boolean {
        val intent = intent ?: return false
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.isWitnessStart == true
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
            MediaStore.INTENT_ACTION_VIDEO_CAMERA -> true
            else -> false
        }
    }

    /**
     * Set when a crew link was opened, so the root screen can show the crew
     * it just joined. Cleared by the screen once shown.
     */
    private val _crewLinkOpened = MutableStateFlow(false)
    val crewLinkOpened: StateFlow<Boolean> = _crewLinkOpened.asStateFlow()

    fun consumeCrewLink() {
        _crewLinkOpened.value = false
    }

    fun handleIntent(intent: Intent?) {
        if (isInstantStart(intent)) {
            requestImmediateRecording()
            return
        }
        // A crew invite: the web link a message carries, or the app's own
        // scheme the web page forwards to. The crew is in the fragment.
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.isCrewLink) {
            val crew = com.witnessrecord.app.service.CrewLink.parse(fromText = data.toString()) ?: return
            com.witnessrecord.app.service.CrewStore.receive(crew)
            _crewLinkOpened.value = true
        }
    }

    private val Uri.isCrewLink: Boolean
        get() = (scheme == "witnessrecord" && host == "crew") ||
            (scheme == "https" && host == "witnesscamera.com" && (path ?: "").startsWith("/crew"))

    private val Uri.isWitnessStart: Boolean
        get() = scheme == "witnessrecord" && host == "start"

    /** The deep link every instant-start entry point launches. */
    val startUri: Uri get() = Uri.parse("witnessrecord://start")

    val isRecording: Boolean get() = CaptureViewModel.isRecording
}
