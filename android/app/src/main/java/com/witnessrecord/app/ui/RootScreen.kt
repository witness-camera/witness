package com.witnessrecord.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.witnessrecord.app.AppState
import com.witnessrecord.app.service.AppSettings
import com.witnessrecord.app.service.Prefs
import com.witnessrecord.app.service.storage.StorageProvider
import com.witnessrecord.app.viewmodel.CaptureViewModel

/**
 * Chooses which screen the app is on.
 *
 * Mirrors the iOS build's `RootView` exactly, including the ordering of the
 * two one-time gates: onboarding, then the hands-on stop-gesture practice.
 * An instant-start path (tile, widget, assistant, power-button gesture) skips
 * both, because the user is already mid-action and must not be made to read a
 * tutorial before their phone starts recording.
 */
@Composable
fun RootScreen(
    onRequestCapturePermissions: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSignIn: (provider: StorageProvider, onError: (String) -> Unit) -> Unit,
) {
    val recordingState by CaptureViewModel.state.collectAsState()
    val autoStartRequested by AppState.autoStartRequested.collectAsState()

    var onboardingComplete by remember {
        mutableStateOf(Prefs.getBool(AppSettings.onboardingCompleteKey, false))
    }
    var practiceComplete by remember {
        mutableStateOf(Prefs.getBool(AppSettings.practiceCompleteKey, false))
    }

    // Instant-start paths may set the flag before this screen exists, so it is
    // consumed here rather than at the entry point.
    LaunchedEffect(autoStartRequested) {
        if (!autoStartRequested) return@LaunchedEffect
        AppState.consumeAutoStart()
        if (!CaptureViewModel.isRecording) {
            if (!CaptureViewModel.hasCapturePermissions) onRequestCapturePermissions()
            CaptureViewModel.startRecording()
        }
    }

    when {
        recordingState is CaptureViewModel.State.Recording -> BlackoutRecordingScreen()

        !onboardingComplete && !autoStartRequested -> OnboardingScreen(
            onRequestCapturePermissions = onRequestCapturePermissions,
            onRequestLocationPermission = onRequestLocationPermission,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onSignIn = onSignIn,
            onFinished = {
                Prefs.setBool(AppSettings.onboardingCompleteKey, true)
                onboardingComplete = true
            },
        )

        // One-time hands-on practice of the triple-tap-then-hold stop gesture,
        // right after onboarding.
        !practiceComplete && !autoStartRequested -> PracticeRecordingScreen(
            onFinished = {
                Prefs.setBool(AppSettings.practiceCompleteKey, true)
                practiceComplete = true
            },
        )

        else -> HomeScreen(
            onSignIn = onSignIn,
            onRequestCapturePermissions = onRequestCapturePermissions,
            onRequestNotificationPermission = onRequestNotificationPermission,
        )
    }
}
