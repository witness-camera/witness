package com.witnessrecord.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.ui.input.pointer.pointerInput
import com.witnessrecord.app.service.CaseLibrary
import com.witnessrecord.app.service.CrewStore
import com.witnessrecord.app.service.LibraryStorage
import com.witnessrecord.app.service.LocalSessionRecord
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.witnessrecord.app.R
import com.witnessrecord.app.billing.ProEntitlement
import com.witnessrecord.app.service.AppSettings
import com.witnessrecord.app.service.ProUpsellPrompt
import com.witnessrecord.app.service.ReviewPrompt
import com.witnessrecord.app.service.UploadManager
import com.witnessrecord.app.service.storage.StorageProvider
import com.witnessrecord.app.service.storage.StorageProviderID
import com.witnessrecord.app.service.storage.StorageProviders
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.WitnessGreen
import com.witnessrecord.app.ui.theme.WitnessOrange
import com.witnessrecord.app.ui.theme.WitnessRed
import com.witnessrecord.app.viewmodel.CaptureViewModel
import kotlinx.coroutines.delay

/**
 * The idle screen: one enormous target that starts a recording, a plain
 * statement of what will happen when it does, and the storage connection
 * status. Support sits top-left mirroring the gear top-right, so asking for
 * help is in plain sight rather than buried a level down inside Settings. A
 * PRO badge sits between them, always visible, outlined until subscribed and
 * filled once active.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSignIn: (provider: StorageProvider, onError: (String) -> Unit) -> Unit,
    onRequestCapturePermissions: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    val selectedProviderID by StorageProviders.selected.collectAsState()
    val provider = StorageProviders.provider(selectedProviderID)
    val accountLabel by (provider?.accountLabelFlow ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()
    val pendingCount by UploadManager.pendingCount.collectAsState()
    val lastError by UploadManager.lastError.collectAsState()
    val recordingState by CaptureViewModel.state.collectAsState()
    val permissionSettingsPromptNeeded by CaptureViewModel.permissionSettingsPromptNeeded.collectAsState()

    // Starting a recording with camera or microphone access missing: the
    // system's own prompt when it can still be shown, and the Settings alert
    // below when it cannot - a refusal that is final leaves nothing to ask.
    val capturePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (CaptureViewModel.hasCapturePermissions) CaptureViewModel.startRecording()
        else CaptureViewModel.requirePermissionSettings()
    }
    val viewfinderVisible by CaptureViewModel.viewfinderVisible.collectAsState()
    val isPro by ProEntitlement.isPro.collectAsState()
    val localOnlyCapture = AppSettings.isLocalOnlyCapture
    val availableBytes by StorageProviders.availableBytes.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showCases by remember { mutableStateOf(false) }
    var showCrew by remember { mutableStateOf(false) }
    val crewLinkOpened by com.witnessrecord.app.AppState.crewLinkOpened.collectAsState()
    val crews by CrewStore.crews.collectAsState()
    val activeCrewID by CrewStore.activeCrewID.collectAsState()
    val activeCrew = crews.firstOrNull { it.id == activeCrewID }
    // An invite opened from a message or a scan lands on the crew it joined.
    androidx.compose.runtime.LaunchedEffect(crewLinkOpened) {
        if (crewLinkOpened) {
            showCrew = true
            com.witnessrecord.app.AppState.consumeCrewLink()
        }
    }
    var detailsItemID by remember { mutableStateOf<String?>(null) }
    val justFinishedItemID by CaseLibrary.justFinishedItemID.collectAsState()
    var swipeDistance by remember { mutableStateOf(0f) }
    var showPro by remember { mutableStateOf(false) }
    var showProUpsell by remember { mutableStateOf(false) }
    var showPaywallFromUpsell by remember { mutableStateOf(false) }
    var showStoragePicker by remember { mutableStateOf(false) }
    var showWebDAVSetup by remember { mutableStateOf(false) }
    var showUploadQueue by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf<StorageProviderID?>(null) }
    var signInError by remember { mutableStateOf<String?>(null) }

    if (showSettings) {
        SettingsScreen(onDismiss = { showSettings = false }, onSignIn = onSignIn)
        return
    }
    if (showPro) {
        ProSettingsScreen(onDismiss = { showPro = false })
        return
    }
    if (showPaywallFromUpsell) {
        PaywallScreen(onDismiss = { showPaywallFromUpsell = false })
        return
    }
    if (showStoragePicker) {
        StoragePickerScreen(
            onDismiss = {
                showStoragePicker = false
                // The chosen provider is connected only once this sheet has
                // actually closed - starting a sign-in flow (or the WebDAV
                // form) while it's still dismissing means presenting on a
                // transitioning screen.
                val toConnect = pendingConnect
                pendingConnect = null
                if (toConnect != null) {
                    if (StorageProviders.usesManualSetup(toConnect)) {
                        showWebDAVSetup = true
                    } else {
                        StorageProviders.provider(toConnect)?.let { onSignIn(it) { message -> signInError = message } }
                    }
                }
            },
            onSelect = { pendingConnect = it },
        )
        return
    }
    if (showWebDAVSetup) {
        WebDAVSetupScreen(onDismiss = { showWebDAVSetup = false }, onConnected = { UploadManager.pump() })
        return
    }
    if (showUploadQueue) {
        UploadQueueScreen(onDismiss = { showUploadQueue = false })
        return
    }

    // Asked for from Home, and only from Home. The recording screen is what is
    // on top during an encounter, so nothing on the capture or upload path can
    // put a Play rating dialog over a blacked-out screen mid-stop - which
    // would be worse than never being rated at all. The recording check and
    // the settle delay are belt and braces on top of that.
    //
    // One effect, in order - two separate effects run concurrently, which
    // could show both the review dialog and the Pro upsell on the same
    // visit. Awaiting the first before starting the second is what actually
    // guarantees they never stack.
    val activity = LocalContext.current as? Activity

    // Asked on appear and whenever the app comes back to the front, so a full
    // account is something you are told before recording into it rather than
    // after. Re-asked when the account changes too.
    LaunchedEffect(selectedProviderID, accountLabel) { StorageProviders.refreshAvailableSpace() }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                StorageProviders.refreshAvailableSpace()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(recordingState) {
        if (activity == null) return@LaunchedEffect
        val showedReview = run {
            if (!ReviewPrompt.shouldPresent(isRecording = CaptureViewModel.isRecording)) return@run false
            // Let the screen settle first; a dialog arriving in the same
            // frame as the screen reads as a glitch and gets dismissed
            // unread.
            delay(1_500)
            if (CaptureViewModel.isRecording) return@run false
            ReviewPrompt.didRequest()
            ReviewPrompt.present(activity)
            true
        }
        if (showedReview) return@LaunchedEffect
        if (showProUpsell) return@LaunchedEffect
        if (!ProUpsellPrompt.shouldPresent(isRecording = CaptureViewModel.isRecording, isPro = isPro)) {
            return@LaunchedEffect
        }
        delay(1_500)
        if (CaptureViewModel.isRecording) return@LaunchedEffect
        ProUpsellPrompt.didPresent()
        showProUpsell = true
    }

    // Retries proofs and uploads left undone - no network at the time, or no
    // account yet - and looks for recordings kept only on the phone.
    LaunchedEffect(Unit) {
        CaseLibrary.finishPendingWork()
        LibraryStorage.refresh()
        LocalSessionRecord.retryMissingTimestamps()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // A swipe to the right pulls Cases in, from the side it slides in
            // from. Only a mostly-horizontal drag counts.
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { swipeDistance = 0f },
                    onDragCancel = { swipeDistance = 0f },
                    onDragEnd = {
                        if (swipeDistance > size.width * 0.3f) showCases = true
                        swipeDistance = 0f
                    },
                ) { _, delta -> swipeDistance += delta }
            },
    ) {
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                title = {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(text = stringResource(R.string.app_name), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                },
                // Top left, so the panel slides in from the side its button is
                // on. Support and Help moved into Settings.
                navigationIcon = {
                    IconButton(onClick = { showCases = true }) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = stringResource(R.string.home_cases_button),
                            tint = Color.White,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showCrew = true }) {
                        Icon(
                            Icons.Filled.Group,
                            contentDescription = stringResource(R.string.crew_button_description),
                            tint = if (activeCrew == null) Color.White else WitnessRed,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .clip(CircleShape)
                            .let {
                                if (isPro) it.background(WitnessRed) else it.border(1.5.dp, WitnessRed, CircleShape)
                            }
                            .clickable { showPro = true }
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.pro_badge),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = if (isPro) Color.Black else WitnessRed,
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title), tint = Color.White)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.clickable {
                    if (CaptureViewModel.hasCapturePermissions) {
                        // Notifications gate the foreground service that
                        // keeps capture alive when the screen goes off, so
                        // they are requested here rather than failing
                        // silently later.
                        onRequestNotificationPermission()
                        CaptureViewModel.startRecording()
                    } else {
                        // Never asked, or refused before: the system shows
                        // its own prompt when it still can, and answers at
                        // once when it cannot - recording starts on a yes,
                        // and a no leads to the Settings alert below.
                        capturePermissionLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                        )
                    }
                },
            ) {
                Image(
                    painter = painterResource(R.drawable.witness_mark),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(140.dp),
                )
                Text(text = stringResource(R.string.home_start_recording), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }

            Spacer(Modifier.size(32.dp))

            Text(
                text = stringResource(
                    if (viewfinderVisible) R.string.home_blackout_hint_viewfinder
                    else R.string.home_blackout_hint
                ),
                fontSize = 12.sp, color = SecondaryLabel, textAlign = TextAlign.Center,
            )

            // Says which crew new recordings join, so nobody records into one by accident.
            activeCrew?.let { crew ->
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.crew_recording_with, crew.title),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = WitnessRed, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp).clickable { showCrew = true },
                )
            }

            Spacer(Modifier.size(16.dp))

            ViewfinderToggle(
                checked = viewfinderVisible,
                onCheckedChange = { CaptureViewModel.setViewfinderPreference(it) },
            )

            (recordingState as? CaptureViewModel.State.Failed)?.let { failed ->
                Spacer(Modifier.size(16.dp))
                Text(
                    text = failed.message, fontSize = 12.sp, color = WitnessRed, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp).clickable { CaptureViewModel.resetFailure() },
                )
            }

            Spacer(Modifier.weight(1f))

            // Offered once after a recording, never forced: one tap to add
            // details or file it in a case, or dismiss it.
            val savedItem = justFinishedItemID
            if (savedItem != null && recordingState !is CaptureViewModel.State.Recording) {
                SavedBanner(
                    onOpen = { detailsItemID = savedItem; CaseLibrary.dismissJustFinished() },
                    onDismiss = { CaseLibrary.dismissJustFinished() },
                )
                Spacer(Modifier.size(12.dp))
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                if (localOnlyCapture) {
                    // Saying "uploading" here would be untrue, and this is
                    // the one claim of the app nobody should be wrong about.
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = WitnessOrange, modifier = Modifier.size(14.dp))
                            Text(stringResource(R.string.home_local_only_recording), fontSize = 12.sp, color = WitnessOrange)
                        }
                        Text(stringResource(R.string.home_local_only_body), fontSize = 11.sp, color = SecondaryLabel, textAlign = TextAlign.Center)
                    }
                } else {
                    StorageStatus(
                        provider = provider,
                        accountLabel = accountLabel,
                        signInError = signInError,
                        spaceWarning = storageSpaceWarning(StorageProviders.recordableSeconds(availableBytes)),
                        onConnect = { showStoragePicker = true },
                    )
                }

                LibraryStorageActions()

                if (pendingCount > 0) {
                    // Tappable: the count says how many are waiting but not
                    // whether anything is moving, which on a poor connection
                    // is the question people actually have.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showUploadQueue = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null, tint = WitnessOrange, modifier = Modifier.size(14.dp))
                        Text(text = stringResource(R.string.home_chunks_queued, pendingCount), fontSize = 12.sp, color = WitnessOrange)
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.queue_title),
                            tint = WitnessOrange,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                lastError?.let {
                    Text(
                        text = stringResource(R.string.home_last_upload_error, it), fontSize = 11.sp, color = SecondaryLabel,
                        maxLines = 2, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        visible = showCases,
        enter = slideInHorizontally(initialOffsetX = { -it }),
        exit = slideOutHorizontally(targetOffsetX = { -it }),
    ) {
        CasesScreen(onClose = { showCases = false })
    }
    AnimatedVisibility(
        visible = showCrew,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
    ) {
        CrewScreen(onClose = { showCrew = false })
    }
    detailsItemID?.let { id ->
        ItemDetailScreen(
            itemID = id, justRecorded = true,
            onBack = { detailsItemID = null },
            onPaywall = { showPaywallFromUpsell = true },
        )
    }
    }

    if (permissionSettingsPromptNeeded) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { CaptureViewModel.dismissPermissionSettingsPrompt() },
            containerColor = com.witnessrecord.app.ui.theme.Surface,
            title = { Text(stringResource(R.string.permission_needed_title), color = Color.White) },
            text = {
                Text(stringResource(R.string.permission_needed_message), color = SecondaryLabel)
            },
            confirmButton = {
                TextButton(onClick = {
                    CaptureViewModel.dismissPermissionSettingsPrompt()
                    runCatching {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    }
                }) {
                    Text(stringResource(R.string.ping_open_settings), color = WitnessRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { CaptureViewModel.dismissPermissionSettingsPrompt() }) {
                    Text(stringResource(R.string.action_cancel), color = SecondaryLabel)
                }
            },
        )
    }

    if (showProUpsell) {
        AlertDialog(
            onDismissRequest = { showProUpsell = false },
            containerColor = com.witnessrecord.app.ui.theme.Surface,
            title = { Text(stringResource(R.string.pro_upsell_title), color = Color.White) },
            text = {
                // Reuses the paywall's own free-app-stays-free line verbatim,
                // so the two places making that promise say the same thing.
                Text(
                    stringResource(R.string.pro_upsell_message, stringResource(R.string.paywall_footer_note)),
                    color = SecondaryLabel,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showProUpsell = false
                    showPaywallFromUpsell = true
                }) {
                    Text(stringResource(R.string.pro_upsell_see_pro), color = WitnessRed)
                }
            },
            dismissButton = {
                // No action needed beyond dismissing: ProUpsellPrompt.didPresent()
                // already marked this shown for good, whichever button gets tapped.
                TextButton(onClick = { showProUpsell = false }) {
                    Text(stringResource(R.string.pro_upsell_not_now), color = SecondaryLabel)
                }
            },
        )
    }
}

/**
 * Lets Witness double as an ordinary camera, on top of being a safety tool -
 * but never at the expense of the safety default. Off every time the app is
 * opened fresh, regardless of what was chosen last time: nothing backs this
 * with a stored setting, so there is nothing for a relaunch to remember.
 * Turning it on is a conscious choice made new each time, never a forgotten
 * state that quietly carries over into a moment it wasn't meant for.
 */
@Composable
private fun ViewfinderToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.home_show_viewfinder),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = WitnessRed),
        )
    }
}

@Composable
private fun StorageStatus(
    provider: StorageProvider?,
    accountLabel: String?,
    signInError: String?,
    spaceWarning: String?,
    onConnect: () -> Unit,
) {
    if (provider != null && provider.isSignedIn) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.CloudDone, contentDescription = null, tint = WitnessGreen, modifier = Modifier.size(14.dp))
            Text(
                // A blank (non-null) label means signed in with the display
                // name not resolved yet - not signed out - so this still
                // shows the connected state, just without "as ...".
                text = if (!accountLabel.isNullOrBlank()) {
                    stringResource(R.string.home_uploading_to_as, stringResource(provider.id.displayNameRes), accountLabel)
                } else {
                    stringResource(R.string.home_uploading_to, stringResource(provider.id.displayNameRes))
                },
                fontSize = 12.sp, color = WitnessGreen,
            )
        }
        spaceWarning?.let {
            Text(
                text = it, fontSize = 11.sp, color = WitnessOrange, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        return
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Filled.CloudQueue, contentDescription = null, tint = SecondaryLabel, modifier = Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.home_connect_cloud_storage),
            fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.clickable { onConnect() },
        )
        Text(text = stringResource(R.string.home_local_only_until_sign_in), fontSize = 11.sp, color = SecondaryLabel, textAlign = TextAlign.Center)
        signInError?.let {
            Text(text = it, fontSize = 11.sp, color = WitnessRed, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Warns while there is still time to do something about it.
 *
 * Stated as minutes of recording rather than gigabytes, because that is the
 * decision being made - "2.1 GB free" does not tell anyone whether they can
 * film the thing they are about to film, and at 4K it is four minutes. Below
 * ten minutes is worth interrupting for; above it, silence.
 */
@Composable
private fun storageSpaceWarning(recordableSeconds: Double?): String? {
    val seconds = recordableSeconds ?: return null
    if (seconds >= 10 * 60) return null
    val minutes = (seconds / 60).toInt()
    return if (minutes < 1) {
        stringResource(R.string.home_storage_full)
    } else {
        stringResource(R.string.home_storage_low, minutes)
    }
}

/** "Recording saved - add details or file it in a case", with a way to dismiss it. */
@Composable
private fun SavedBanner(onOpen: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(com.witnessrecord.app.ui.theme.Surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = com.witnessrecord.app.ui.theme.WitnessGreen)
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(stringResource(R.string.home_saved_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(stringResource(R.string.home_saved_subtitle), fontSize = 12.sp, color = SecondaryLabel)
        }
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.home_saved_dismiss),
            tint = SecondaryLabel,
            modifier = Modifier.size(18.dp).clickable(onClick = onDismiss),
        )
    }
}

/**
 * Both are offers the person taps, never things that happen to them: one
 * deletes files from the phone, the other can send gigabytes over mobile data.
 */
@Composable
private fun LibraryStorageActions() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val redundantBytes by LibraryStorage.redundantBytes.collectAsState()
    val freeing by LibraryStorage.freeing.collectAsState()
    val awaiting by LibraryStorage.awaitingUpload.collectAsState()
    val progress by LibraryStorage.uploadProgress.collectAsState()
    val message by LibraryStorage.message.collectAsState()
    val provider = StorageProviders.current

    if (redundantBytes > 0) {
        TextButton(enabled = !freeing, onClick = { scope.launch { LibraryStorage.freeUpSpace() } }) {
            Text(
                text = if (freeing) stringResource(R.string.storage_freeing)
                else stringResource(R.string.storage_free_up, android.text.format.Formatter.formatFileSize(context, redundantBytes)),
                fontSize = 12.sp, textAlign = TextAlign.Center, color = WitnessRed,
            )
        }
    }
    if (awaiting.isNotEmpty() && provider?.isSignedIn == true) {
        TextButton(enabled = progress == null, onClick = { scope.launch { LibraryStorage.uploadEarlierRecordings() } }) {
            val shown = progress
            Text(
                text = if (shown != null) stringResource(R.string.storage_uploading_earlier, shown.first, shown.second)
                else stringResource(R.string.storage_upload_earlier, awaiting.size),
                fontSize = 12.sp, textAlign = TextAlign.Center, color = WitnessRed,
            )
        }
    }
    message?.let {
        Text(it, fontSize = 11.sp, color = SecondaryLabel, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
    }
}
