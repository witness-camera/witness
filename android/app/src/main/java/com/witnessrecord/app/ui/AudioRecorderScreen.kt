package com.witnessrecord.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.witnessrecord.app.R
import com.witnessrecord.app.model.CaseItem
import com.witnessrecord.app.service.CaseLibrary
import com.witnessrecord.app.service.LocationService
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.WitnessRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Audio only: discreet, light on battery, for meetings and calls where
 * recording them is lawful. Hashed and time-stamped like video.
 *
 * A recording in progress is never thrown away: leaving the screen, going
 * back or pressing Cancel while it runs all stop it and keep what was
 * recorded.
 */
@Composable
fun AudioRecorderScreen(caseID: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current

    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableStateOf(0f) }
    var failed by remember { mutableStateOf(false) }

    /** Stops, and files what was recorded. Safe to call when nothing is recording. */
    fun stopAndSave() {
        val active = recorder ?: return
        val target = file
        val started = startedAt
        recorder = null
        view.keepScreenOn = false
        runCatching { active.stop() }
        runCatching { active.release() }
        if (target != null && target.exists() && target.length() > 0) {
            CaseLibrary.scope.launch {
                CaseLibrary.addFile(
                    target, CaseItem.KIND_AUDIO, imported = false, startedAt = started, caseID = caseID,
                )
            }
        }
    }

    fun start() {
        val output = File(context.cacheDir, "audio-${UUID.randomUUID()}.m4a")
        val created = runCatching {
            (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(96_000)
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }
        }.getOrNull()
        if (created == null) {
            failed = true
            return
        }
        failed = false
        recorder = created
        file = output
        startedAt = System.currentTimeMillis()
        view.keepScreenOn = true
        LocationService.requestOneShotLocation()
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else failed = true
    }
    fun toggle() {
        if (recorder != null) {
            stopAndSave()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) start() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Never thrown away silently: leaving mid-recording keeps it.
    DisposableEffect(Unit) { onDispose { stopAndSave() } }
    BackHandler { stopAndSave(); onDismiss() }

    LaunchedEffect(recorder) {
        while (recorder != null) {
            elapsed = (System.currentTimeMillis() - (startedAt ?: System.currentTimeMillis())) / 1000
            level = ((recorder?.let { runCatching { it.maxAmplitude }.getOrDefault(0) } ?: 0) / 12_000f).coerceIn(0f, 1f)
            delay(80)
        }
        level = 0f
    }

    val ring by animateDpAsState(targetValue = (120 + level * 90).dp, label = "level")

    Box(Modifier.fillMaxSize().background(Color.Black).clickable(enabled = false) {}) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(stringResource(R.string.audio_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(clock(elapsed), fontSize = 52.sp, fontWeight = FontWeight.Light, color = Color.White)
            Box(Modifier.height(220.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(ring).clip(CircleShape).background(WitnessRed.copy(alpha = 0.25f)))
                Box(
                    modifier = Modifier.size(96.dp).clip(CircleShape).background(WitnessRed).clickable { toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (recorder == null) Icons.Filled.Mic else Icons.Filled.Stop,
                        contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp),
                    )
                }
            }
            Text(
                stringResource(if (recorder == null) R.string.audio_tap_start else R.string.audio_tap_stop),
                fontSize = 15.sp, color = SecondaryLabel,
            )
            Text(
                stringResource(R.string.audio_consent_note),
                fontSize = 12.sp, color = SecondaryLabel, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (failed) Text(stringResource(R.string.audio_failed), color = WitnessRed)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { stopAndSave(); onDismiss() }) {
                Text(stringResource(R.string.action_cancel), color = SecondaryLabel)
            }
        }
    }
}
