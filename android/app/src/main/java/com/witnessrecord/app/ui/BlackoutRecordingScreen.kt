package com.witnessrecord.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.witnessrecord.app.R
import com.witnessrecord.app.service.EmergencyPing
import com.witnessrecord.app.service.LocationService
import com.witnessrecord.app.ui.theme.WitnessOrange
import com.witnessrecord.app.ui.theme.WitnessRed
import com.witnessrecord.app.viewmodel.CaptureViewModel
import kotlinx.coroutines.delay

/**
 * Active-recording screen. By default it is solid black with the window's
 * brightness forced to minimum - to an observer the phone looks off. The user
 * can toggle a live viewfinder (back camera full-screen, front camera as a
 * corner PiP) to see what is being filmed.
 *
 * Controls are hidden by default and revealed by a triple-tap, so nothing is
 * pressed by accident. Stopping requires a deliberate press-and-hold. Both
 * gestures are exactly the ones the practice screen drills.
 */
@Composable
fun BlackoutRecordingScreen() {
    val viewfinderVisible by CaptureViewModel.viewfinderVisible.collectAsState()
    val dualActive by CaptureViewModel.dualActive.collectAsState()
    val mainViewSwapped by CaptureViewModel.mainViewSwapped.collectAsState()
    val lensMultiplier by CaptureViewModel.lensMultiplier.collectAsState()
    val lensControlAvailable by CaptureViewModel.lensControlAvailable.collectAsState()
    val lowStorageWarning by CaptureViewModel.lowStorageWarning.collectAsState()
    val recordingState by CaptureViewModel.state.collectAsState()
    val recordingStartedAt = (recordingState as? CaptureViewModel.State.Recording)?.since
    val lastMarkAt by CaptureViewModel.lastMarkAt.collectAsState()
    val isPro by com.witnessrecord.app.billing.ProEntitlement.isPro.collectAsState()

    var controlsVisible by remember { mutableStateOf(false) }
    var revealToken by remember { mutableStateOf(0) }

    // Back must not end a recording. Stopping is deliberately a two-part
    // gesture; a stray back press - or a back-swipe from a hand gripping the
    // phone - must never be enough.
    BackHandler(enabled = true) { revealToken += 1 }

    // Controls fade out again six seconds after the last interaction, so a
    // recording left running goes dark on its own.
    LaunchedEffect(revealToken) {
        if (revealToken == 0) return@LaunchedEffect
        controlsVisible = true
        delay(6_000)
        controlsVisible = false
    }

    val reveal = { revealToken += 1 }

    val context = LocalContext.current
    val pingStep by EmergencyPing.step.collectAsState()
    val noEligibleContacts by EmergencyPing.noEligibleContacts.collectAsState()
    val locationDeniedPrompt by EmergencyPing.locationDeniedPromptNeeded.collectAsState()

    // Asked on the tap itself, so the system prompt appears at the moment the
    // person asks for it. A refusal - including a "don't ask again" one that
    // returns instantly - lands in the same place: the ping offers Settings,
    // or going ahead without a location.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { EmergencyPing.start(context) }

    // The compose screen to open next. Opened from here because only an
    // activity can launch one; the coordinator just decides which.
    LaunchedEffect(pingStep) {
        val step = pingStep ?: return@LaunchedEffect
        val intent = when (step) {
            is EmergencyPing.Step.Sms -> Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("smsto:" + step.recipients.joinToString(",") { Uri.encode(it) }),
            ).putExtra("sms_body", step.body)
            is EmergencyPing.Step.Mail -> Intent(
                Intent.ACTION_SENDTO, Uri.parse("mailto:"),
            ).putExtra(Intent.EXTRA_EMAIL, step.recipients.toTypedArray())
                .putExtra(Intent.EXTRA_SUBJECT, step.subject)
                .putExtra(Intent.EXTRA_TEXT, step.body)
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (launched) EmergencyPing.stepLaunched(step) else EmergencyPing.stepFailed(step)
    }

    // Back from the messaging app: the email half of the ping, if any, is next.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) EmergencyPing.onReturned()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .tripleTapGesture(
                key = viewfinderVisible,
                // With the viewfinder up the screen is already visible, so a
                // single tap is enough to bring the controls back. Blacked
                // out, only the deliberate triple-tap counts - a pocket brush
                // must never surface a stop button.
                onSingleTap = { if (viewfinderVisible) reveal() },
                onTripleTap = { reveal() },
            )
    ) {
        // Always mounted, whether or not it is being shown - see CameraPreview.
        CameraPreview(visible = viewfinderVisible, showFront = dualActive, swapped = mainViewSwapped)

        // "Marked 0:42", for two seconds after each mark, while the
        // viewfinder is on. With the screen black there is nothing to show -
        // the haptic is the confirmation.
        if (viewfinderVisible && lastMarkAt != null && recordingStartedAt != null) {
            MarkToast(mark = lastMarkAt!!, start = recordingStartedAt)
        }

        AnimatedVisibility(
            visible = controlsVisible || viewfinderVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            RecordingControls(
                viewfinderVisible = viewfinderVisible,
                dualActive = dualActive,
                mainViewSwapped = mainViewSwapped,
                lensControlAvailable = lensControlAvailable,
                lensMultiplier = lensMultiplier,
                lowStorageWarning = lowStorageWarning,
                recordingStartedAt = recordingStartedAt,
                isPro = isPro,
                onMarkMoment = {
                    CaptureViewModel.addMarker()
                    reveal()
                },
                onToggleViewfinder = {
                    CaptureViewModel.toggleViewfinder()
                    reveal()
                },
                onToggleLens = {
                    CaptureViewModel.toggleLens()
                    reveal()
                },
                onToggleMainViewSwap = {
                    CaptureViewModel.toggleMainViewSwap()
                    reveal()
                },
                onAlertContacts = {
                    if (LocationService.hasPermission) {
                        EmergencyPing.start(context)
                    } else {
                        locationPermissionLauncher.launch(arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        ))
                    }
                    reveal()
                },
                onStop = { CaptureViewModel.stopRecording() },
            )
        }
    }

    if (locationDeniedPrompt) {
        AlertDialog(
            onDismissRequest = { EmergencyPing.cancelWaiting() },
            title = { Text(stringResource(R.string.ping_location_denied_title)) },
            text = { Text(stringResource(R.string.ping_location_denied_message)) },
            confirmButton = {
                TextButton(onClick = {
                    EmergencyPing.cancelWaiting()
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    }
                }) { Text(stringResource(R.string.ping_open_settings)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { EmergencyPing.sendWithoutLocation(context) }) {
                        Text(stringResource(R.string.ping_send_without_location))
                    }
                    TextButton(onClick = { EmergencyPing.cancelWaiting() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }

    if (noEligibleContacts) {
        AlertDialog(
            onDismissRequest = { EmergencyPing.dismissNoContacts() },
            title = { Text(stringResource(R.string.ping_no_contacts_title)) },
            text = { Text(stringResource(R.string.ping_no_contacts_message)) },
            confirmButton = {
                TextButton(onClick = { EmergencyPing.dismissNoContacts() }) { Text("OK") }
            },
        )
    }
}

/**
 * Counts up from [since] on its own, no polling from outside - the same
 * plain "0:14" look as the system Camera app's own recording clock. Compose
 * has no built-in timer-style text the way SwiftUI does, so this ticks its
 * own `now` once a second via a coroutine tied to [since], which cancels and
 * restarts automatically if a new recording starts.
 */
@Composable
private fun ElapsedTimeText(since: Long) {
    var now by remember(since) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsedSeconds = ((now - since) / 1000).coerceAtLeast(0)
    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    Text(
        text = "%d:%02d".format(minutes, seconds),
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun RecordingControls(
    viewfinderVisible: Boolean,
    dualActive: Boolean,
    mainViewSwapped: Boolean,
    lensControlAvailable: Boolean,
    lensMultiplier: Double,
    lowStorageWarning: String?,
    recordingStartedAt: Long?,
    isPro: Boolean,
    onMarkMoment: () -> Unit,
    onToggleViewfinder: () -> Unit,
    onToggleLens: () -> Unit,
    onToggleMainViewSwap: () -> Unit,
    onAlertContacts: () -> Unit,
    onStop: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (viewfinderVisible) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.Circle,
                        contentDescription = null,
                        tint = WitnessRed,
                        modifier = Modifier.size(10.dp),
                    )
                    Text(
                        text = stringResource(R.string.recording_badge_rec),
                        color = WitnessRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (recordingStartedAt != null) {
                        ElapsedTimeText(since = recordingStartedAt)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (viewfinderVisible && dualActive) {
                // Meaningless outside dual capture - with one camera there
                // is no second view to swap with.
                val swapDescription = stringResource(
                    if (mainViewSwapped) R.string.recording_swap_to_rear
                    else R.string.recording_swap_to_front
                )
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        .pointerInput(Unit) { detectTapGestures(onTap = { onToggleMainViewSwap() }) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .semantics { contentDescription = swapDescription },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.Cameraswitch,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.recording_swap_main_view),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.size(8.dp))
            }
            if (isPro) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .pointerInput(Unit) { detectTapGestures(onTap = { onMarkMoment() }) }
                        .padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Flag,
                        contentDescription = stringResource(R.string.markers_mark_moment),
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.size(8.dp))
            }
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { onAlertContacts() }) }
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.MyLocation,
                    contentDescription = stringResource(R.string.ping_button_description),
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { onToggleViewfinder() }) }
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = if (viewfinderVisible) Icons.Filled.VisibilityOff
                    else Icons.Filled.Visibility,
                    contentDescription = stringResource(
                        if (viewfinderVisible) R.string.recording_hide_viewfinder
                        else R.string.recording_show_viewfinder
                    ),
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (viewfinderVisible && lensControlAvailable) {
                val label = if (lensMultiplier <= 0.5) "0.5×" else "1×"
                Box(
                    modifier = Modifier
                        .widthIn(min = 44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        .pointerInput(Unit) { detectTapGestures(onTap = { onToggleLens() }) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .semantics {
                            contentDescription = "Rear lens, currently $label. Tap to switch."
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Text(
                text = stringResource(
                    if (viewfinderVisible) R.string.recording_hold_to_stop_long
                    else R.string.recording_hold_to_stop_short
                ),
                color = Color.White.copy(alpha = if (viewfinderVisible) 0.8f else 0.6f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )

            Icon(
                imageVector = Icons.Filled.StopCircle,
                contentDescription = stringResource(R.string.recording_stop),
                // Red whether or not the viewfinder is on: these controls only
                // exist for a few seconds after a triple-tap, and a faint grey
                // icon on black was hard to find in exactly that window.
                tint = WitnessRed.copy(alpha = 0.9f),
                modifier = Modifier
                    .size(56.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStop()
                            }
                        )
                    },
            )

            if (lowStorageWarning != null) {
                Text(
                    text = lowStorageWarning,
                    color = WitnessOrange.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }
}

/**
 * "Marked 0:42", shown for two seconds after each mark. Redrawn per mark by
 * keying on its time, so a second mark restarts the two seconds.
 */
@Composable
private fun MarkToast(mark: Long, start: Long) {
    var visible by remember(mark) { mutableStateOf(true) }
    LaunchedEffect(mark) {
        delay(2_000)
        visible = false
    }
    if (!visible) return
    val offset = ((mark - start) / 1000).toInt().coerceAtLeast(0)
    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = 180.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.Flag, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.markers_marked, offset / 60, "%02d".format(offset % 60)),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
