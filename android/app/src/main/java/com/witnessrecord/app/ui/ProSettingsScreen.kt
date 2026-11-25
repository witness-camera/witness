package com.witnessrecord.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.witnessrecord.app.R
import com.witnessrecord.app.billing.ProEntitlement
import com.witnessrecord.app.capture.CaptureSessionController
import com.witnessrecord.app.service.AppSettings
import com.witnessrecord.app.service.Prefs
import com.witnessrecord.app.service.VideoCodec
import com.witnessrecord.app.service.VideoFrameRate
import com.witnessrecord.app.service.VideoResolution
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.Surface
import com.witnessrecord.app.ui.theme.WitnessOrange
import com.witnessrecord.app.ui.theme.WitnessRed
import com.witnessrecord.app.viewmodel.CaptureViewModel
import java.text.DecimalFormat

/**
 * Pro capture settings on their own page, reached from the PRO badge on the
 * Home screen.
 *
 * The controls are always shown, never hidden behind the purchase: they are
 * simply inert until the subscription is active, so a filmmaker can see
 * exactly what Pro does before paying for it.
 *
 * Every choice states its own cost. The size estimate is the point of the
 * page: these settings differ by more than an order of magnitude in file
 * size, and the difference decides whether footage can leave the device at
 * all - so the numbers are shown rather than buried in a footnote.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProSettingsScreen(onDismiss: () -> Unit) {
    BackHandler { onDismiss() }
    val context = LocalContext.current
    val isPro by ProEntitlement.isPro.collectAsState()
    val diagnostics by CaptureViewModel.captureDiagnostics.collectAsState()
    val storeDiagnostics by ProEntitlement.productDiagnostics.collectAsState()
    val entitlementDiagnostics by ProEntitlement.entitlementDiagnostics.collectAsState()
    val isLifetime by ProEntitlement.isLifetime.collectAsState()

    var resolution by remember { mutableStateOf(AppSettings.videoResolution) }
    var frameRate by remember { mutableStateOf(AppSettings.videoFrameRate) }
    var codec by remember { mutableStateOf(VideoCodec.entries.firstOrNull { it.wire == Prefs.getString(AppSettings.videoCodecKey) } ?: VideoCodec.H264) }
    var hdrEnabled by remember { mutableStateOf(Prefs.getBool(AppSettings.hdrRecordingKey, false)) }
    var stabilization by remember { mutableStateOf(AppSettings.Stabilization.fromWire(Prefs.getString(AppSettings.stabilizationKey))) }
    var pipExport by remember { mutableStateOf(Prefs.getBool(AppSettings.pipExportKey, false)) }
    var highlightExport by remember { mutableStateOf(Prefs.getBool(AppSettings.highlightExportKey, false)) }
    var stealthLandscape by remember { mutableStateOf(Prefs.getBool(AppSettings.stealthLandscapeKey, false)) }
    var blurExport by remember { mutableStateOf(Prefs.getBool(AppSettings.blurExportKey, false)) }
    var voiceDisguise by remember { mutableStateOf(Prefs.getBool(AppSettings.voiceDisguiseKey, false)) }
    var showPaywall by remember { mutableStateOf(false) }
    var showEvidenceExport by remember { mutableStateOf(false) }
    val dualSupported = remember { CaptureSessionController.isDualCameraSupported(context) }

    if (showPaywall) {
        PaywallScreen(onDismiss = { showPaywall = false })
        return
    }
    if (showEvidenceExport) {
        EvidenceExportScreen(onDismiss = { showEvidenceExport = false })
        return
    }

    val usesProCapture = resolution != VideoResolution.HD1080 || frameRate != VideoFrameRate.FPS30 ||
        hdrEnabled || codec != VideoCodec.H264

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                title = { Text(stringResource(R.string.pro_title)) },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_done), tint = Color.White)
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
            // A short silent loop of what Pro adds, shown in the product's own
            // screens.
            val promoDescription = stringResource(R.string.pro_promo_description)
            PromoVideo(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .semantics { contentDescription = promoDescription },
            )

            if (!isPro) {
                ProSection(footer = stringResource(R.string.pro_unlock_footer)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showPaywall = true }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = WitnessRed)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.pro_unlock_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(stringResource(R.string.pro_unlock_subtitle), fontSize = 12.sp, color = SecondaryLabel)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SecondaryLabel)
                    }
                }
            }

            val proEnabled = isPro
            ProSection(
                header = stringResource(R.string.pro_section_capture),
                footer = codecFooter(codec),
            ) {
                PickerRow(stringResource(R.string.pro_resolution), enabled = proEnabled) {
                    VideoResolution.entries.forEach { value ->
                        RadioOption(stringResource(value.labelRes), resolution == value, proEnabled) {
                            resolution = value
                            Prefs.setString(AppSettings.videoResolutionKey, value.wire)
                        }
                    }
                }
                PickerRow(stringResource(R.string.pro_frame_rate), enabled = proEnabled) {
                    VideoFrameRate.entries.forEach { value ->
                        RadioOption(stringResource(value.labelRes), frameRate == value, proEnabled) {
                            frameRate = value
                            Prefs.shared.edit().putInt(AppSettings.videoFrameRateKey, value.fps).apply()
                        }
                    }
                }
                PickerRow(stringResource(R.string.pro_codec), enabled = proEnabled) {
                    VideoCodec.entries.forEach { value ->
                        RadioOption(stringResource(value.labelRes), codec == value, proEnabled) {
                            codec = value
                            Prefs.setString(AppSettings.videoCodecKey, value.wire)
                            if (!value.supportsHdr) {
                                hdrEnabled = false
                                Prefs.setBool(AppSettings.hdrRecordingKey, false)
                            }
                        }
                    }
                }
            }

            ProSection(header = stringResource(R.string.pro_section_hdr), footer = hdrFooter(codec, isPro, hdrEnabled)) {
                ToggleRow(
                    title = stringResource(R.string.pro_hdr_toggle),
                    checked = hdrEnabled,
                    enabled = isPro && codec.supportsHdr,
                    onCheckedChange = {
                        hdrEnabled = it
                        Prefs.setBool(AppSettings.hdrRecordingKey, it)
                    },
                )
            }

            ProSection(footer = stabilizationFooter(stabilization)) {
                PickerRow(stringResource(R.string.stabilization_title), enabled = proEnabled) {
                    AppSettings.Stabilization.entries.forEach { value ->
                        RadioOption(stringResource(value.labelRes), stabilization == value, proEnabled) {
                            stabilization = value
                            Prefs.setString(AppSettings.stabilizationKey, value.wire)
                        }
                    }
                }
            }

            if (dualSupported) {
                ProSection(footer = stringResource(R.string.pro_pip_export_footer)) {
                    ToggleRow(
                        title = stringResource(R.string.pro_pip_export_toggle),
                        checked = pipExport,
                        enabled = proEnabled,
                        onCheckedChange = {
                            pipExport = it
                            Prefs.setBool(AppSettings.pipExportKey, it)
                        },
                    )
                }
            }

            ProSection(footer = stringResource(R.string.pro_stealth_footer)) {
                ToggleRow(
                    title = stringResource(R.string.pro_stealth_title),
                    checked = stealthLandscape,
                    enabled = isPro && !hdrEnabled,
                    onCheckedChange = {
                        stealthLandscape = it
                        Prefs.setBool(AppSettings.stealthLandscapeKey, it)
                    },
                )
            }

            ProSection(
                footer = stringResource(R.string.pro_blur_footer) + "\n\n" + stringResource(R.string.pro_voice_footer),
            ) {
                ToggleRow(
                    title = stringResource(R.string.pro_blur_title),
                    checked = blurExport,
                    enabled = proEnabled,
                    onCheckedChange = {
                        blurExport = it
                        Prefs.setBool(AppSettings.blurExportKey, it)
                    },
                )
                ToggleRow(
                    title = stringResource(R.string.pro_voice_title),
                    checked = voiceDisguise,
                    enabled = proEnabled && blurExport,
                    onCheckedChange = {
                        voiceDisguise = it
                        Prefs.setBool(AppSettings.voiceDisguiseKey, it)
                    },
                )
            }

            ProSection(footer = stringResource(R.string.pro_highlight_footer)) {
                ToggleRow(
                    title = stringResource(R.string.pro_highlight_title),
                    checked = highlightExport,
                    enabled = proEnabled,
                    onCheckedChange = {
                        highlightExport = it
                        Prefs.setBool(AppSettings.highlightExportKey, it)
                    },
                )
            }

            ProSection(header = stringResource(R.string.pro_section_weight)) {
                val bytesPerSecond = codec.estimatedBitRate(resolution, frameRate) / 8.0
                ValueRow(stringResource(R.string.pro_every_15s), formatBytes(bytesPerSecond * 15))
                ValueRow(stringResource(R.string.pro_every_minute), formatBytes(bytesPerSecond * 60))
                Text(stringResource(R.string.pro_weight_footer), fontSize = 12.sp, color = SecondaryLabel, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            }

            if (codec.isLocalOnly) {
                ProSection {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = WitnessOrange, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.pro_local_only_label), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = WitnessOrange)
                    }
                    Text(stringResource(R.string.codec_footer_mastering), fontSize = 12.sp, color = SecondaryLabel, modifier = Modifier.padding(top = 6.dp))
                }
            }

            if (isPro && usesProCapture) {
                ProSection(footer = stringResource(R.string.pro_single_camera_footer)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = WitnessOrange, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.pro_single_camera_label), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = WitnessOrange)
                    }
                    Text(
                        text = stringResource(R.string.pro_restore_dual_camera),
                        fontSize = 14.sp, color = WitnessRed,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .clickable {
                                resolution = VideoResolution.HD1080
                                frameRate = VideoFrameRate.FPS30
                                codec = VideoCodec.H264
                                hdrEnabled = false
                                Prefs.setString(AppSettings.videoResolutionKey, VideoResolution.HD1080.wire)
                                Prefs.shared.edit().putInt(AppSettings.videoFrameRateKey, VideoFrameRate.FPS30.fps).apply()
                                Prefs.setString(AppSettings.videoCodecKey, VideoCodec.H264.wire)
                                Prefs.setBool(AppSettings.hdrRecordingKey, false)
                            },
                    )
                }
            }

            ProSection(footer = stringResource(R.string.pro_evidence_packages_footer)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showEvidenceExport = true }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.DocumentScanner, contentDescription = null, tint = WitnessRed)
                    Text(stringResource(R.string.pro_evidence_packages), fontSize = 15.sp, color = Color.White, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SecondaryLabel)
                }
            }

            ProSection(header = stringResource(R.string.pro_diagnostics), footer = stringResource(R.string.pro_diagnostics_footer)) {
                val text = diagnostics.ifEmpty { stringResource(R.string.pro_diagnostics_placeholder) }
                Text(text = text, fontSize = 11.sp, color = SecondaryLabel, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }

            ProSection(
                header = stringResource(R.string.pro_store_diagnostics_header),
                footer = stringResource(R.string.pro_store_diagnostics_footer),
            ) {
                Text(
                    text = storeDiagnostics,
                    fontSize = 11.sp, color = SecondaryLabel,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
                Text(
                    text = entitlementDiagnostics,
                    fontSize = 11.sp, color = SecondaryLabel,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (isPro) {
                if (isLifetime) {
                    // Nothing renews, so there is no subscription page to send
                    // them to - offering one would be an invitation to look
                    // for a cancellation that does not exist.
                    ProSection {
                        Text(
                            text = stringResource(R.string.pro_lifetime_active),
                            fontSize = 13.sp, color = SecondaryLabel,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                } else {
                    ProSection(footer = stringResource(R.string.pro_manage_subscription_footer)) {
                        Text(
                            text = stringResource(R.string.pro_manage_subscription),
                            fontSize = 15.sp, color = WitnessRed,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { ProEntitlement.openSubscriptionManagement(context) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.size(40.dp))
        }
    }
}

@Composable
private fun codecFooter(codec: VideoCodec): String = when (codec) {
    VideoCodec.H264 -> stringResource(R.string.codec_footer_h264)
    VideoCodec.HEVC -> stringResource(R.string.codec_footer_hevc)
    VideoCodec.MASTERING -> stringResource(R.string.codec_footer_mastering)
}

@Composable
private fun hdrFooter(codec: VideoCodec, isPro: Boolean, hdrEnabled: Boolean): String = when {
    !com.witnessrecord.app.capture.CameraSelection.deviceSupportsHdr() -> stringResource(R.string.pro_hdr_footer_unsupported_device)
    !codec.supportsHdr -> stringResource(R.string.pro_hdr_footer_needs_codec)
    !isPro -> stringResource(R.string.pro_hdr_footer_locked)
    hdrEnabled -> stringResource(R.string.pro_hdr_footer_on)
    else -> stringResource(R.string.pro_hdr_footer_off)
}

/**
 * Says plainly what a stabilization choice will actually do on this phone: the
 * camera may not offer the strongest modes, in which case the nearest one it
 * does offer is used, and silence there would make the choice look broken.
 */
@Composable
private fun stabilizationFooter(choice: AppSettings.Stabilization): String {
    val context = LocalContext.current
    var text = stringResource(R.string.stabilization_footer)
    val supported = remember { CaptureSessionController.rearStabilizationSupport(context) }
    if (supported != null && choice !in supported) {
        text += "\n\n" + stringResource(R.string.stabilization_unsupported)
    }
    return text
}

private fun formatBytes(bytes: Double): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1000) "${DecimalFormat("#.#").format(mb / 1000.0)} GB" else "${DecimalFormat("#").format(mb)} MB"
}

@Composable
private fun ProSection(header: String? = null, footer: String? = null, content: @Composable ColumnScopeMarker.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        header?.let {
            Text(it.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SecondaryLabel, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            ColumnScopeMarker.content()
        }
        footer?.let {
            Text(it, fontSize = 12.sp, color = SecondaryLabel, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
    }
}

@Composable
private fun PickerRow(title: String, enabled: Boolean, options: @Composable () -> Unit) {
    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else SecondaryLabel, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
    options()
    Spacer(Modifier.size(4.dp))
}

@Composable
private fun RadioOption(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label, fontSize = 14.sp, color = if (enabled) Color.White else SecondaryLabel)
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, color = if (enabled) Color.White else SecondaryLabel, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = WitnessRed))
    }
}

@Composable
private fun ValueRow(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, color = Color.White, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = SecondaryLabel)
    }
}
