package com.witnessrecord.app.ui

import android.content.ComponentName
import androidx.activity.compose.BackHandler
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.witnessrecord.app.R
import com.witnessrecord.app.capture.CaptureSessionController
import com.witnessrecord.app.model.EmergencyContact
import com.witnessrecord.app.service.AppSettings
import com.witnessrecord.app.service.storage.StorageProvider
import com.witnessrecord.app.service.storage.StorageProviderID
import com.witnessrecord.app.service.storage.StorageProviders
import com.witnessrecord.app.service.UploadManager
import com.witnessrecord.app.service.InstantStart
import com.witnessrecord.app.service.LensPreference
import com.witnessrecord.app.service.Prefs
import com.witnessrecord.app.service.SecureStore
import com.witnessrecord.app.service.SessionFolderNaming
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.billing.ProEntitlement
import com.witnessrecord.app.service.VideoCodec
import com.witnessrecord.app.service.VideoFrameRate
import com.witnessrecord.app.service.VideoResolution
import com.witnessrecord.app.ui.theme.Surface
import com.witnessrecord.app.ui.theme.WitnessOrange
import com.witnessrecord.app.ui.theme.WitnessRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    onSignIn: (provider: StorageProvider, onError: (String) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val selectedProviderID by StorageProviders.selected.collectAsState()
    val provider = StorageProviders.provider(selectedProviderID)
    val accountLabel by (provider?.accountLabelFlow ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsState()

    BackHandler { onDismiss() }

    var showProtectFootage by remember { mutableStateOf(false) }
    var showStoragePicker by remember { mutableStateOf(false) }
    var showWebDAVSetup by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf<StorageProviderID?>(null) }
    var signInError by remember { mutableStateOf<String?>(null) }
    if (showHelp) {
        HelpScreen(onDismiss = { showHelp = false })
        return
    }
    if (showWebDAVSetup) {
        WebDAVSetupScreen(onDismiss = { showWebDAVSetup = false }, onConnected = { UploadManager.pump() })
        return
    }
    if (showProtectFootage) {
        ProtectFootageScreen(onBack = { showProtectFootage = false })
        return
    }
    if (showStoragePicker) {
        StoragePickerScreen(
            onDismiss = {
                showStoragePicker = false
                // Connected once the picker has closed, same as on Home.
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

    var driveFolderName by remember {
        mutableStateOf(Prefs.getString(AppSettings.driveFolderNameKey, AppSettings.defaultDriveFolderName)!!)
    }
    var folderNameTemplate by remember { mutableStateOf(AppSettings.folderNameTemplate) }
    var lensPreference by remember { mutableStateOf(AppSettings.lensPreference) }
    var dualCameraEnabled by remember { mutableStateOf(AppSettings.dualCameraEnabled) }
    var photosBackupEnabled by remember { mutableStateOf(AppSettings.photosBackupEnabled) }
    var cloudUploadEnabled by remember { mutableStateOf(AppSettings.cloudUploadEnabled) }
    var driveStitchEnabled by remember { mutableStateOf(AppSettings.driveStitchEnabled) }
    var lowResFallbackEnabled by remember { mutableStateOf(AppSettings.lowResFallbackEnabled) }
    var powerButtonEnabled by remember { mutableStateOf(InstantStart.powerButtonEnabled) }
    var quickStartViewfinder by remember { mutableStateOf(AppSettings.quickStartViewfinder) }
    var timestampBurnIn by remember { mutableStateOf(Prefs.getBool(AppSettings.timestampBurnInKey, false)) }

    var contacts by remember { mutableStateOf(EmergencyContact.load(context)) }
    var newContactName by remember { mutableStateOf("") }
    var newContactEmail by remember { mutableStateOf("") }
    var newContactPhone by remember { mutableStateOf("") }

    val dualSupported = remember { CaptureSessionController.isDualCameraSupported(context) }

    // Read from the same function the capture layer acts on, recomputed from
    // the values this screen holds so the row updates the moment one of them
    // changes rather than next launch.
    val isPro by ProEntitlement.isPro.collectAsState()
    var videoResolution by remember { mutableStateOf(AppSettings.videoResolution) }
    var videoFrameRate by remember { mutableStateOf(AppSettings.videoFrameRate) }
    var videoCodec by remember { mutableStateOf(AppSettings.videoCodec) }
    var hdrRecording by remember { mutableStateOf(Prefs.getBool(AppSettings.hdrRecordingKey, false)) }
    var showDualBlocked by remember { mutableStateOf(false) }
    val dualBlockers = AppSettings.dualCameraBlockers(
        resolution = videoResolution, frameRate = videoFrameRate, codec = videoCodec,
        hdrRecording = hdrRecording, isPro = isPro,
    )
    val proCaptureBlocksDual = dualBlockers.isNotEmpty()
    val localOnly = AppSettings.isLocalOnlyCapture(
        codec = videoCodec,
        cloudUploadEnabled = cloudUploadEnabled,
        // Read here so the row follows signing in or out; the label flow is
        // collected above and recomposes this screen when it changes.
        cloudAccountConnected = provider?.isSignedIn == true || AppSettings.cloudAccountConnected,
    )
    // Resolved here rather than inside the footer expression: joinToString's
    // transform is a noinline lambda, which cannot call stringResource.
    val dualBlockerNames = dualBlockers
        .map { blockerLabel(it, videoResolution, videoFrameRate, videoCodec) }
        .joinToString(", ")

    val folderNamePreview = remember(folderNameTemplate) {
        var name = folderNameTemplate.trim().ifEmpty { AppSettings.defaultFolderNameTemplate }
        SessionFolderNaming.placeholders.forEach { (token, example) ->
            name = name.replace(token, example)
        }
        name.replace("/", "-")
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                ),
                title = { Text(stringResource(R.string.settings_title)) },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_done),
                            tint = Color.White,
                        )
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
                .padding(horizontal = 16.dp),
        ) {
            // First, so somebody who thinks a recording is lost finds it
            // without scrolling.
            SettingsSection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showHelp = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = null,
                        tint = WitnessRed,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_help_row),
                        fontSize = 16.sp,
                        color = Color.White,
                    )
                }
            }

            // MARK: Cloud storage account
            SettingsSection(
                header = provider?.let { stringResource(it.id.displayNameRes) } ?: stringResource(R.string.storage_title)
            ) {
                if (provider != null && provider.isSignedIn) {
                    // A blank (non-null) label means signed in with the name
                    // not resolved yet, not signed out - fall back to the
                    // provider's own name rather than showing nothing.
                    ValueRow(
                        stringResource(R.string.settings_account),
                        accountLabel?.takeIf { it.isNotBlank() } ?: stringResource(provider.id.displayNameRes),
                    )
                    TextRow(
                        text = stringResource(R.string.settings_sign_out),
                        color = WitnessRed,
                        onClick = {
                            provider.signOut()
                            AppSettings.refreshCloudAccountConnected()
                        },
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_not_signed_in),
                        color = SecondaryLabel,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    signInError?.let {
                        Text(
                            text = it,
                            color = WitnessRed,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                }
                TextRow(
                    text = stringResource(R.string.storage_title),
                    color = Color.White,
                    onClick = { showStoragePicker = true },
                )
                DarkTextField(
                    value = driveFolderName,
                    onValueChange = {
                        driveFolderName = it
                        Prefs.setString(AppSettings.driveFolderNameKey, it)
                        // Folder is resolved fresh on next upload.
                        SecureStore.delete(SecureStore.Key.DRIVE_FOLDER_ID)
                    },
                    placeholder = stringResource(R.string.settings_upload_folder_name),
                )
            }

            // MARK: Session folder naming
            SettingsSection(
                header = stringResource(R.string.settings_section_folder_naming),
                footer = stringResource(R.string.settings_folder_naming_footer),
            ) {
                DarkTextField(
                    value = folderNameTemplate,
                    onValueChange = {
                        folderNameTemplate = it
                        Prefs.setString(AppSettings.folderNameTemplateKey, it)
                    },
                    placeholder = stringResource(R.string.settings_folder_template),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.settings_preview),
                        fontSize = 13.sp,
                        color = SecondaryLabel,
                    )
                    Text(
                        text = folderNamePreview,
                        fontSize = 13.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                if (folderNameTemplate != AppSettings.defaultFolderNameTemplate) {
                    TextRow(
                        text = stringResource(R.string.settings_reset_to_default),
                        color = WitnessRed,
                        onClick = {
                            folderNameTemplate = AppSettings.defaultFolderNameTemplate
                            Prefs.setString(
                                AppSettings.folderNameTemplateKey,
                                AppSettings.defaultFolderNameTemplate
                            )
                        },
                    )
                }
            }

            // MARK: Camera
            SettingsSection(
                header = stringResource(R.string.settings_section_camera),
                footer = lensFooter(lensPreference) + "\n\n" + dualCameraFooter(dualSupported) +
                    if (proCaptureBlocksDual) {
                        "\n\n" + stringResource(
                            R.string.settings_dual_blocked_by,
                            dualBlockerNames,
                        )
                    } else {
                        ""
                    },
            ) {
                Text(
                    text = stringResource(R.string.settings_starting_lens),
                    fontSize = 16.sp,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                LensPreference.entries.forEach { preference ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                lensPreference = preference
                                Prefs.setString(AppSettings.lensPreferenceKey, preference.wire)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = lensPreference == preference,
                            onClick = {
                                lensPreference = preference
                                Prefs.setString(AppSettings.lensPreferenceKey, preference.wire)
                            },
                        )
                        Text(
                            text = stringResource(preference.labelRes),
                            fontSize = 16.sp,
                            color = Color.White,
                        )
                    }
                }

                if (dualSupported) {
                    // Shows off while Pro capture rules both cameras out,
                    // without discarding the stored preference - turning 4K or
                    // 60 fps back down restores it rather than making the user
                    // remember to switch this on again. Trying to switch it on
                    // explains what is in the way instead of silently refusing.
                    ToggleRow(
                        title = stringResource(R.string.settings_record_front_camera),
                        checked = dualCameraEnabled && !proCaptureBlocksDual,
                        onCheckedChange = { wanted ->
                            if (wanted && proCaptureBlocksDual) {
                                showDualBlocked = true
                            } else {
                                dualCameraEnabled = wanted
                                Prefs.setBool(AppSettings.dualCameraKey, wanted)
                            }
                        },
                    )
                } else {
                    ValueRow(
                        stringResource(R.string.settings_dual_camera),
                        stringResource(R.string.settings_not_supported),
                    )
                }
                // The viewfinder toggle itself moved to the Home screen,
                // above Start Recording - this section's footer stays as
                // context for the lens/dual-camera behavior it affects.
            }

            // MARK: Instant start (Android-specific)
            SettingsSection(
                header = stringResource(R.string.settings_section_instant_start),
                footer = stringResource(R.string.settings_instant_start_footer) + "\n\n" +
                    stringResource(R.string.settings_quick_start_footer_screen),
            ) {
                Text(
                    text = stringResource(R.string.settings_instant_start_body),
                    fontSize = 15.sp,
                    color = SecondaryLabel,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                ToggleRow(
                    title = stringResource(R.string.settings_power_button),
                    checked = powerButtonEnabled,
                    onCheckedChange = { enabled ->
                        powerButtonEnabled = enabled
                        Prefs.setBool(InstantStart.powerButtonKey, enabled)
                        setCameraGestureEnabled(context, enabled)
                    },
                )
                Text(
                    text = stringResource(R.string.settings_quick_start_screen),
                    fontSize = 16.sp,
                    color = Color.White,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                listOf(false to R.string.quick_start_black, true to R.string.quick_start_camera).forEach { (showCamera, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                quickStartViewfinder = showCamera
                                Prefs.setBool(AppSettings.quickStartViewfinderKey, showCamera)
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = quickStartViewfinder == showCamera,
                            onClick = {
                                quickStartViewfinder = showCamera
                                Prefs.setBool(AppSettings.quickStartViewfinderKey, showCamera)
                            },
                        )
                        Text(stringResource(label), fontSize = 15.sp, color = Color.White)
                    }
                }
            }

            // MARK: Cloud upload
            SettingsSection(
                header = stringResource(R.string.settings_section_cloud_upload),
                footer = stringResource(R.string.settings_cloud_upload_footer) + "\n\n" +
                    stringResource(R.string.settings_cloud_upload_asymmetry_footer),
            ) {
                ToggleRow(
                    title = stringResource(R.string.settings_upload_new_recordings),
                    checked = cloudUploadEnabled,
                    onCheckedChange = {
                        cloudUploadEnabled = it
                        Prefs.setBool(AppSettings.cloudUploadKey, it)
                    },
                )
            }

            // MARK: Visible timestamp (free; the Witness line only without Pro)
            SettingsSection(
                footer = stringResource(R.string.settings_timestamp_footer) +
                    (if (isPro) "" else " " + stringResource(R.string.settings_timestamp_footer_branded)) +
                    (if (hdrRecording) "\n\n" + stringResource(R.string.settings_timestamp_hdr_note) else ""),
            ) {
                ToggleRow(
                    title = stringResource(R.string.settings_timestamp_toggle),
                    checked = timestampBurnIn,
                    onCheckedChange = {
                        timestampBurnIn = it
                        Prefs.setBool(AppSettings.timestampBurnInKey, it)
                    },
                )
            }

            // MARK: On-device backup
            SettingsSection(
                header = stringResource(R.string.settings_section_device_backup),
                footer = stringResource(R.string.settings_gallery_footer) +
                    if (localOnly) "\n\n" + stringResource(R.string.settings_gallery_held_footer) else "",
            ) {
                // Held on, and disabled, while nothing is uploading: a
                // session nothing uploads deletes each chunk's original as it
                // is staged, so the gallery is then the only copy and the
                // pipeline forces the save. A switch reading "off" while the
                // app saves anyway would be a lie, and one that could really
                // be turned off would delete the recording. The stored
                // preference is untouched, so it returns when uploading does.
                ToggleRow(
                    title = stringResource(R.string.settings_save_to_gallery),
                    checked = photosBackupEnabled || localOnly,
                    enabled = !localOnly,
                    onCheckedChange = {
                        photosBackupEnabled = it
                        Prefs.setBool(AppSettings.photosBackupKey, it)
                    },
                )
            }

            // MARK: Drive stitch
            SettingsSection(
                header = stringResource(R.string.settings_section_drive),
                footer = stringResource(R.string.settings_drive_stitch_footer),
            ) {
                ToggleRow(
                    title = stringResource(R.string.settings_combine_videos),
                    checked = driveStitchEnabled,
                    onCheckedChange = {
                        driveStitchEnabled = it
                        Prefs.setBool(AppSettings.driveStitchKey, it)
                    },
                )
            }

            // MARK: Low-res fallback
            SettingsSection(footer = stringResource(R.string.settings_low_res_fallback_footer)) {
                ToggleRow(
                    title = stringResource(R.string.settings_low_res_fallback),
                    checked = lowResFallbackEnabled,
                    onCheckedChange = {
                        lowResFallbackEnabled = it
                        Prefs.setBool(AppSettings.lowResFallbackKey, it)
                    },
                )
            }

            // MARK: Emergency contacts
            SettingsSection(
                header = stringResource(R.string.settings_section_contacts),
                footer = (
                    if (provider?.supportsEmailSharing == false) {
                        stringResource(
                            R.string.settings_contacts_footer_unsupported,
                            provider.id.let { stringResource(it.displayNameRes) },
                        )
                    } else {
                        stringResource(R.string.settings_contacts_footer)
                    }
                    ) + stringResource(R.string.settings_contacts_footer_ping),
            ) {
                contacts.forEach { contact ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(text = contact.name, fontSize = 16.sp, color = Color.White)
                            contact.email?.takeIf { it.isNotEmpty() }?.let {
                                Text(text = it, fontSize = 13.sp, color = SecondaryLabel)
                            }
                            contact.phone?.takeIf { it.isNotBlank() }?.let {
                                Text(text = it, fontSize = 13.sp, color = SecondaryLabel)
                            }
                            if (!contact.canReceiveFootage) {
                                Text(
                                    text = stringResource(R.string.settings_contact_no_email),
                                    fontSize = 12.sp,
                                    color = WitnessOrange,
                                )
                            }
                            if (!contact.canReceiveSMS && !contact.hasValidEmail) {
                                Text(
                                    text = stringResource(R.string.settings_contact_no_channel),
                                    fontSize = 12.sp,
                                    color = WitnessOrange,
                                )
                            }
                        }
                        IconButton(onClick = {
                            contacts = contacts.filterNot { it.id == contact.id }
                            EmergencyContact.save(context, contacts)
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = WitnessRed,
                            )
                        }
                    }
                }

                Spacer(Modifier.size(8.dp))
                DarkTextField(
                    value = newContactName,
                    onValueChange = { newContactName = it },
                    placeholder = stringResource(R.string.contact_name),
                )
                Spacer(Modifier.size(8.dp))
                DarkTextField(
                    value = newContactEmail,
                    onValueChange = { newContactEmail = it },
                    placeholder = stringResource(R.string.contact_email),
                    keyboardType = KeyboardType.Email,
                )
                Spacer(Modifier.size(8.dp))
                DarkTextField(
                    value = newContactPhone,
                    onValueChange = { newContactPhone = it },
                    placeholder = stringResource(R.string.contact_phone),
                    keyboardType = KeyboardType.Phone,
                )
                Spacer(Modifier.size(8.dp))
                // Either channel is enough to save a contact: email used to
                // be required because it was all this list did anything with,
                // but the location ping can reach someone by phone alone.
                val canAddContact = newContactName.isNotBlank() &&
                    (newContactEmail.contains("@") || newContactPhone.isNotBlank())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canAddContact) {
                            contacts = contacts + EmergencyContact.newContact(
                                newContactName.trim(), newContactEmail.trim(), newContactPhone.trim()
                            )
                            EmergencyContact.save(context, contacts)
                            newContactName = ""
                            newContactEmail = ""
                            newContactPhone = ""
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val enabled = canAddContact
                    Icon(
                        Icons.Filled.AddCircle,
                        contentDescription = null,
                        tint = if (enabled) WitnessRed else SecondaryLabel,
                    )
                    Text(
                        text = stringResource(R.string.contact_add),
                        fontSize = 16.sp,
                        color = if (enabled) Color.White else SecondaryLabel,
                    )
                }
            }

            // MARK: Protect footage
            SettingsSection(footer = stringResource(R.string.settings_protect_footer)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showProtectFootage = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = WitnessRed)
                    Text(
                        text = stringResource(R.string.settings_protect_footage),
                        fontSize = 16.sp,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = SecondaryLabel,
                    )
                }
            }

            Spacer(Modifier.size(40.dp))
        }
    }

    if (showDualBlocked) {
        AlertDialog(
            onDismissRequest = { showDualBlocked = false },
            containerColor = Surface,
            title = { Text(stringResource(R.string.settings_dual_blocked_title), color = Color.White) },
            text = {
                Text(stringResource(R.string.settings_dual_blocked_message), color = SecondaryLabel)
            },
            confirmButton = {
                TextButton(onClick = {
                    // Puts the capture settings back where both cameras can
                    // run. The stored dual-camera preference is left alone: it
                    // was never turned off, only overridden, so it comes back
                    // on its own.
                    videoResolution = VideoResolution.HD1080
                    videoFrameRate = VideoFrameRate.FPS30
                    videoCodec = VideoCodec.H264
                    hdrRecording = false
                    Prefs.setString(AppSettings.videoResolutionKey, VideoResolution.HD1080.wire)
                    Prefs.shared.edit().putInt(AppSettings.videoFrameRateKey, VideoFrameRate.FPS30.fps).apply()
                    Prefs.setString(AppSettings.videoCodecKey, VideoCodec.H264.wire)
                    Prefs.setBool(AppSettings.hdrRecordingKey, false)
                    showDualBlocked = false
                }) {
                    Text(stringResource(R.string.settings_dual_blocked_restore), color = WitnessRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDualBlocked = false }) {
                    Text(stringResource(R.string.settings_dual_blocked_leave), color = SecondaryLabel)
                }
            },
        )
    }
}

/**
 * Enables or disables the camera-gesture activity-alias. Android routes a
 * power-button double-press only to apps registered as camera apps, so the
 * alias exists but ships disabled - flipping it here is what makes the
 * gesture reach Witness, and turning it off removes the app from the camera
 * chooser again.
 */
private fun setCameraGestureEnabled(context: Context, enabled: Boolean) {
    runCatching {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, "com.witnessrecord.app.CameraGestureActivity"),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}

/**
 * The picker label for whatever a blocker refers to, so the reason shown to
 * the user is always the wording on the control they need to change.
 */
@Composable
private fun blockerLabel(
    blocker: AppSettings.DualCameraBlocker,
    resolution: VideoResolution,
    frameRate: VideoFrameRate,
    codec: VideoCodec,
): String = when (blocker) {
    AppSettings.DualCameraBlocker.RESOLUTION -> stringResource(resolution.labelRes)
    AppSettings.DualCameraBlocker.FRAME_RATE -> stringResource(frameRate.labelRes)
    AppSettings.DualCameraBlocker.CODEC -> stringResource(codec.labelRes)
    AppSettings.DualCameraBlocker.HDR -> stringResource(R.string.pro_hdr_toggle)
}

@Composable
private fun lensFooter(preference: LensPreference): String {
    val base = stringResource(
        when (preference) {
            LensPreference.AUTO -> R.string.settings_lens_footer_auto
            LensPreference.ULTRA_WIDE -> R.string.settings_lens_footer_ultra_wide
            LensPreference.WIDE -> R.string.settings_lens_footer_wide
        }
    )
    return base + " " + stringResource(R.string.settings_lens_footer_live)
}

@Composable
private fun dualCameraFooter(supported: Boolean): String = if (supported) {
    stringResource(R.string.settings_dual_footer_supported) + "\n\n" +
        stringResource(R.string.settings_viewfinder_footer_supported)
} else {
    stringResource(R.string.settings_dual_footer_unsupported) + "\n\n" +
        stringResource(R.string.settings_viewfinder_footer_unsupported)
}

// MARK: - Grouped-list building blocks

@Composable
private fun SettingsSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScopeMarker.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        header?.let {
            Text(
                text = it.uppercase(),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = SecondaryLabel,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            ColumnScopeMarker.content()
        }
        footer?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                color = SecondaryLabel,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
            // MARK: Support - moved here from its own page on the Home toolbar
            // (which Cases replaced on iOS).
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            SettingsSection(
                header = stringResource(R.string.settings_section_support),
                footer = stringResource(R.string.settings_support_footer),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri("mailto:athingby@gmail.com") }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Email, contentDescription = null, tint = WitnessRed, modifier = Modifier.size(22.dp))
                    Text("athingby@gmail.com", fontSize = 16.sp, color = Color.White)
                }
                // PayPal's own brand blue, so the donate action reads as what
                // it is at a glance.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(com.witnessrecord.app.ui.theme.PayPalBlue)
                        .clickable { uriHandler.openUri("https://paypal.me/athingby") }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Filled.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(10.dp))
                    Text(
                        stringResource(R.string.support_donate),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    )
                }
            }

        }
    }
}

/** Marker so section bodies read as a scoped builder, like SwiftUI's `Section`. */
object ColumnScopeMarker

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title, fontSize = 16.sp,
            color = if (enabled) Color.White else SecondaryLabel,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = WitnessRed),
        )
    }
}

@Composable
private fun ValueRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, fontSize = 16.sp, color = Color.White, modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 15.sp,
            color = SecondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TextRow(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp)
    ) {
        Text(text = text, fontSize = 16.sp, color = color)
    }
}
