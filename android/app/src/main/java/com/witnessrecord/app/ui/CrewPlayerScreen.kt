package com.witnessrecord.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.witnessrecord.app.R
import com.witnessrecord.app.model.Crew
import com.witnessrecord.app.model.CrewAngle
import com.witnessrecord.app.model.CrewEdit
import com.witnessrecord.app.service.CrewEditExport
import com.witnessrecord.app.service.CrewFootage
import com.witnessrecord.app.service.CrewStore
import com.witnessrecord.app.service.CrewTimeline
import com.witnessrecord.app.service.MediaStoreService
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.Surface
import com.witnessrecord.app.ui.theme.WitnessRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

private val palette = listOf(
    Color(0xFFFF9500), Color(0xFF0A84FF), Color(0xFF30D158), Color(0xFFFF2D92), Color(0xFFBF5AF2),
    Color(0xFF40C8E0), Color(0xFFFFD60A), Color(0xFF5E5CE6), Color(0xFF63E6BE), Color(0xFFA2845E),
)

/**
 * Every angle of a crew on one timeline, on the phone: play, scrub, and cut
 * between cameras into one edit, then export it as a single video. The point
 * is to see straight away what filming together bought - the moment one phone
 * missed, another caught - and to hand someone that moment as one video.
 *
 * The iOS build plays small previews shared through iCloud. Here it plays the
 * footage on this phone: the person's own recordings, and any other member's
 * angle they have downloaded and linked (see [CrewFootage]). Only the shown
 * camera plays; a cut costs a seek.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun CrewPlayerScreen(crewID: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val crews by CrewStore.crews.collectAsState()
    val crew = crews.firstOrNull { it.id == crewID }
    BackHandler { onClose() }
    if (crew == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val myID = CrewStore.myMemberID(crew.id)

    // The footage on this phone for each angle. Re-read when a link changes
    // or the crew gains an angle.
    var footageVersion by remember { mutableIntStateOf(0) }
    val footage: Map<String, Uri> = remember(crew.angles, footageVersion) {
        crew.angles.mapNotNull { angle ->
            CrewFootage.uri(angle, isMine = angle.memberID == myID)?.let { angle.id to it }
        }.toMap()
    }

    var edit by remember(crew.id) { mutableStateOf(CrewEdit.load(crew.id)) }
    val timeline = remember(crew.angles, footage.keys, edit) { CrewTimeline(crew.angles, footage.keys, edit) }
    var position by remember { mutableDoubleStateOf(0.0) }
    var isPlaying by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var selectedID by remember { mutableStateOf<String?>(null) }
    var loadedID by remember { mutableStateOf<String?>(null) }

    val player = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = false } }
    DisposableEffect(Unit) { onDispose { player.release() } }

    fun now(): Double = timeline.origin + position
    fun save(updated: CrewEdit) {
        edit = updated
        updated.save(crew.id)
        if (!updated.hasInOut) zoomed = false
    }

    /** Puts the edit's angle for the playhead on screen. Only the shown angle plays and is heard. */
    fun follow() {
        val id = timeline.effectiveID(now())
        selectedID = id
        val angle = crew.angles.firstOrNull { it.id == id }
        val uri = id?.let { footage[it] }
        if (angle == null || uri == null || !timeline.covers(angle, now())) {
            player.playWhenReady = false
            return
        }
        if (loadedID != id) {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            loadedID = id
        }
        player.seekTo(((now() - angle.startedAt) * 1000).toLong().coerceAtLeast(0))
        player.playWhenReady = isPlaying
    }

    fun pause() {
        isPlaying = false
        player.playWhenReady = false
    }

    fun seek(seconds: Double) {
        position = seconds.coerceIn(0.0, timeline.total)
        follow()
    }

    fun play() {
        if (position >= timeline.total) position = 0.0
        isPlaying = true
        follow()
    }

    // Follow the edit when the footage or the cuts change under the playhead.
    LaunchedEffect(timeline) { follow() }

    // The clock the timeline runs on: the wall clock, not the player's, so a
    // cut or a gap never stalls it. Corrects the player if it drifts.
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        val startedAtMs = System.currentTimeMillis() - (position * 1000).toLong()
        var ticks = 0
        while (true) {
            delay(100)
            val wasCovered = selectedID?.let { id -> crew.angles.firstOrNull { it.id == id }?.let { timeline.covers(it, now()) } }
            position = minOf((System.currentTimeMillis() - startedAtMs) / 1000.0, timeline.total)
            if (position >= timeline.total) { pause(); break }
            val id = timeline.effectiveID(now())
            val angle = crew.angles.firstOrNull { it.id == id }
            val covered = angle?.let { timeline.covers(it, now()) }
            if (id != selectedID || covered != wasCovered) {
                follow()
            } else if (angle != null && ++ticks % 20 == 0) {
                val expected = ((now() - angle.startedAt) * 1000).toLong()
                if (abs(player.currentPosition - expected) > 400) player.seekTo(expected)
            }
        }
    }

    // Footage the person picks for another member's camera.
    var linking by remember { mutableStateOf<CrewAngle?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = linking
        linking = null
        if (uri != null && target != null) {
            CrewFootage.link(context, target, uri)
            footageVersion++
        }
    }

    var exportRangeInOut by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<File?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var savedToGallery by remember { mutableStateOf(false) }
    if (!edit.hasInOut && exportRangeInOut) exportRangeInOut = false

    fun window(): Pair<Double, Double> {
        val i = edit.inPoint
        val o = edit.outPoint
        if (zoomed && edit.hasInOut && i != null && o != null) {
            val a = i - timeline.origin
            val b = o - timeline.origin
            val pad = (b - a) * 0.05
            return maxOf(0.0, a - pad) to minOf(timeline.total, b + pad)
        }
        return 0.0 to timeline.total
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                title = { Text(crew.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close), tint = Color.White)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // The screen: the shown camera, or a note when none is filming.
            val sorted = crew.angles.sortedBy { it.startedAt }
            val selected = crew.angles.firstOrNull { it.id == selectedID }
            val covered = selected != null && timeline.covers(selected, now())
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                if (covered) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                this.player = player
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.VideocamOff, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(36.dp))
                        Text(
                            stringResource(if (footage.isEmpty()) R.string.crew_player_no_footage else R.string.crew_no_camera_now),
                            color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (selected != null && covered) {
                        Chip(label(selected, crew, myID), dot = colorOf(selected.id, sorted))
                    } else Spacer(Modifier.size(1.dp))
                    Chip(clockText(now()), mono = true)
                }
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Transport.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val enabled = crew.angles.isNotEmpty()
                    IconButton({ seek(position - 10) }, enabled = enabled) { Icon(Icons.Filled.Replay10, null, tint = Color.White) }
                    IconButton({ seek(position - 1) }, enabled = enabled) {
                        Icon(Icons.Filled.KeyboardArrowLeft, stringResource(R.string.crew_back_1s), tint = Color.White)
                    }
                    IconButton({ if (isPlaying) pause() else play() }, enabled = enabled) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            stringResource(if (isPlaying) R.string.crew_pause else R.string.crew_play),
                            tint = Color.White, modifier = Modifier.size(34.dp),
                        )
                    }
                    IconButton({ seek(position + 1) }, enabled = enabled) {
                        Icon(Icons.Filled.KeyboardArrowRight, stringResource(R.string.crew_fwd_1s), tint = Color.White)
                    }
                    IconButton({ seek(position + 10) }, enabled = enabled) { Icon(Icons.Filled.Forward10, null, tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Text("${duration(position)} / ${duration(timeline.total)}", fontSize = 13.sp, color = SecondaryLabel, fontFamily = FontFamily.Monospace)
                }

                // The edit on top, one lane per angle below.
                val (winStart, winEnd) = window()
                fun fraction(seconds: Double) = ((seconds - winStart) / maxOf(winEnd - winStart, 0.001)).toFloat()
                val segments = remember(timeline, winStart, winEnd) { timeline.segments(winStart, winEnd) }
                val lanes = maxOf(sorted.size, 1)
                val height = 18 + 8 + lanes * 14
                Canvas(
                    Modifier.fillMaxWidth().height(height.dp)
                        .pointerInput(winStart, winEnd) {
                            detectTapGestures { offset ->
                                seek(winStart + (winEnd - winStart) * (offset.x / size.width).coerceIn(0f, 1f))
                            }
                        }
                        .pointerInput(winStart, winEnd) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                seek(winStart + (winEnd - winStart) * (change.position.x / size.width).coerceIn(0f, 1f))
                            }
                        },
                ) {
                    val w = size.width
                    val editH = 18.dp.toPx()
                    drawRoundRect(Color.White.copy(alpha = 0.12f), Offset.Zero, Size(w, editH), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                    for (segment in segments) {
                        val id = segment.angleID ?: continue
                        val x = fraction(segment.start) * w
                        drawRect(colorOf(id, sorted), Offset(x, 0f), Size(maxOf(fraction(segment.end) * w - x, 1f), editH))
                    }
                    for (cut in edit.cuts) {
                        val t = cut.at - timeline.origin
                        if (t < winStart || t > winEnd) continue
                        val x = fraction(t) * w
                        drawRect(Color.White, Offset(x - 1f, 0f), Size(2.dp.toPx(), editH))
                    }
                    sorted.forEachIndexed { i, angle ->
                        val top = editH + 8.dp.toPx() + i * 14.dp.toPx()
                        drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(0f, top), Size(w, 10.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                        val start = angle.startedAt - timeline.origin
                        val x0 = (fraction(start) * w).coerceIn(0f, w)
                        val x1 = (fraction(start + angle.duration) * w).coerceIn(0f, w)
                        drawRoundRect(
                            colorOf(angle.id, sorted).copy(alpha = if (timeline.isReady(angle.id)) 0.85f else 0.3f),
                            Offset(x0, top), Size(maxOf(x1 - x0, 4.dp.toPx()), 10.dp.toPx()),
                            androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
                        )
                    }
                    val total = size.height
                    edit.inPoint?.let {
                        val x = fraction(it - timeline.origin) * w
                        drawRect(Color.Black.copy(alpha = 0.45f), Offset.Zero, Size(x.coerceIn(0f, w), total))
                        drawRect(Color(0xFFFFD60A), Offset(x - 1f, 0f), Size(2.dp.toPx(), total))
                    }
                    edit.outPoint?.let {
                        val x = fraction(it - timeline.origin) * w
                        drawRect(Color.Black.copy(alpha = 0.45f), Offset(x.coerceIn(0f, w), 0f), Size(w - x.coerceIn(0f, w), total))
                        drawRect(Color(0xFFFFD60A), Offset(x - 1f, 0f), Size(2.dp.toPx(), total))
                    }
                    val px = fraction(position) * w
                    drawRect(Color.Red, Offset(px - 1f, 0f), Size(2.dp.toPx(), total))
                }

                // In, Out, zoom, cuts.
                var menu by remember { mutableStateOf(false) }
                val cutNear = run {
                    val reach = maxOf(0.5, (winEnd - winStart) * 0.015)
                    edit.cuts.filter { abs(it.at - now()) <= reach }.minByOrNull { abs(it.at - now()) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton(stringResource(R.string.crew_in)) {
                        val out = edit.outPoint
                        save(edit.copy(inPoint = now(), outPoint = if (out != null && out <= now()) null else out))
                    }
                    SmallButton(stringResource(R.string.crew_out)) {
                        val i = edit.inPoint
                        save(edit.copy(outPoint = now(), inPoint = if (i != null && i >= now()) null else i))
                    }
                    IconButton({ zoomed = !zoomed }, enabled = edit.hasInOut) {
                        Icon(
                            if (zoomed) Icons.Filled.ZoomOut else Icons.Filled.ZoomIn,
                            stringResource(if (zoomed) R.string.crew_zoom_whole else R.string.crew_zoom_inout),
                            tint = if (edit.hasInOut) Color.White else SecondaryLabel,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    SmallButton(stringResource(R.string.crew_remove_cut), enabled = cutNear != null) {
                        cutNear?.let { cut -> save(edit.copy(cuts = edit.cuts - cut)) }
                    }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.crew_more_options), tint = Color.White) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.crew_go_in)) }, enabled = edit.inPoint != null,
                                onClick = { menu = false; edit.inPoint?.let { seek(it - timeline.origin) } },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.crew_go_out)) }, enabled = edit.outPoint != null,
                                onClick = { menu = false; edit.outPoint?.let { seek(it - timeline.origin) } },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.crew_clear_inout)) },
                                enabled = edit.inPoint != null || edit.outPoint != null,
                                onClick = { menu = false; save(edit.copy(inPoint = null, outPoint = null)) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.crew_clear_cuts)) }, enabled = edit.cuts.isNotEmpty(),
                                onClick = { menu = false; save(edit.copy(cuts = emptyList())) },
                            )
                        }
                    }
                }

                // The cameras filming at the playhead, side by side, for cutting with one tap.
                val live = sorted.filter { timeline.isReady(it.id) && timeline.covers(it, now()) }
                if (live.size > 1) {
                    Text(
                        stringResource(R.string.crew_cameras_now, live.size),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SecondaryLabel,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        live.forEach { angle ->
                            Text(
                                label(angle, crew, myID), fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(if (angle.id == selectedID) colorOf(angle.id, sorted) else Color.Gray.copy(alpha = 0.5f))
                                    .clickable { cutTo(edit, angle.id, now(), ::save, ::follow) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                Text(stringResource(R.string.crew_cameras_header), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SecondaryLabel)
                sorted.forEach { angle ->
                    val ready = timeline.isReady(angle.id)
                    val mine = angle.memberID == myID
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (angle.id == selectedID) colorOf(angle.id, sorted).copy(alpha = 0.18f) else Surface)
                            .clickable(enabled = ready) { cutTo(edit, angle.id, now(), ::save, ::follow) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(colorOf(angle.id, sorted)))
                        Column(Modifier.weight(1f)) {
                            Text(label(angle, crew, myID), color = Color.White, fontSize = 16.sp)
                            Text(
                                stringResource(
                                    when {
                                        !ready -> R.string.crew_status_no_footage
                                        timeline.covers(angle, now()) -> R.string.crew_status_filming
                                        else -> R.string.crew_status_not_filming
                                    }
                                ),
                                color = SecondaryLabel, fontSize = 12.sp,
                            )
                        }
                        if (!ready || CrewFootage.isLinked(angle)) {
                            Text(
                                stringResource(if (ready) R.string.crew_remove_footage else R.string.crew_add_footage),
                                color = WitnessRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    if (ready) { CrewFootage.unlink(angle); footageVersion++ }
                                    else { linking = angle; picker.launch(arrayOf("video/*")) }
                                }.padding(6.dp),
                            )
                        }
                    }
                }

                // Export.
                Text(stringResource(R.string.crew_export_header), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = SecondaryLabel)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RangeChoice(stringResource(R.string.crew_range_whole), !exportRangeInOut, true) { exportRangeInOut = false }
                    RangeChoice(stringResource(R.string.crew_range_inout), exportRangeInOut, edit.hasInOut) { exportRangeInOut = true }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (exporting || footage.isEmpty()) WitnessRed.copy(alpha = 0.4f) else WitnessRed)
                        .clickable(enabled = !exporting && footage.isNotEmpty()) {
                            pause()
                            exporting = true
                            exportError = null
                            savedToGallery = false
                            exported = null
                            scope.launch {
                                val (a, b) = timeline.exportRange(exportRangeInOut)
                                runCatching {
                                    CrewEditExport.export(context, CrewEditExport.pieces(timeline, a, b) { footage[it] }, crew.title)
                                }.onSuccess { exported = it }
                                    .onFailure { exportError = it.message ?: context.getString(R.string.crew_export_start_failed) }
                                exporting = false
                            }
                        }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (exporting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    if (exporting) Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(if (exporting) R.string.crew_exporting else R.string.crew_export_video),
                        color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    )
                }
                exported?.let { file ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(R.string.crew_share), color = WitnessRed, fontSize = 16.sp,
                            modifier = Modifier.clickable { shareVideo(context, file) }.padding(vertical = 8.dp),
                        )
                        Text(
                            stringResource(if (savedToGallery) R.string.crew_saved_gallery else R.string.crew_save_gallery),
                            color = if (savedToGallery) SecondaryLabel else WitnessRed, fontSize = 16.sp,
                            modifier = Modifier.clickable(enabled = !savedToGallery) {
                                scope.launch {
                                    savedToGallery = MediaStoreService.save(file) != null
                                    if (!savedToGallery) exportError = context.getString(R.string.crew_save_gallery_failed)
                                }
                            }.padding(vertical = 8.dp),
                        )
                    }
                }
                exportError?.let { Text(it, color = WitnessRed, fontSize = 12.sp) }
                Text(stringResource(R.string.crew_export_note), fontSize = 12.sp, color = SecondaryLabel)
                Text(stringResource(R.string.crew_player_help), fontSize = 12.sp, color = SecondaryLabel)
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

/** Cuts to a camera at the playhead. A cut already within a fraction of a second is replaced rather than stacked. */
private fun cutTo(edit: CrewEdit, angleID: String, now: Double, save: (CrewEdit) -> Unit, follow: () -> Unit) {
    save(edit.copy(cuts = edit.cuts.filter { abs(it.at - now) >= 0.3 } + CrewEdit.Cut(now, angleID)))
    follow()
}

private fun colorOf(angleID: String, sorted: List<CrewAngle>): Color =
    palette[(sorted.indexOfFirst { it.id == angleID }.coerceAtLeast(0)) % palette.size]

@Composable
private fun label(angle: CrewAngle, crew: Crew, myID: String?): String {
    val name = if (angle.memberID == myID) stringResource(R.string.crew_you)
    else crew.members.firstOrNull { it.id == angle.memberID }?.name ?: stringResource(R.string.crew_phone)
    val same = crew.angles.filter { it.memberID == angle.memberID }.sortedBy { it.startedAt }
    return if (same.size > 1) "$name · ${same.indexOfFirst { it.id == angle.id } + 1}" else name
}

@Composable
private fun Chip(text: String, dot: Color? = null, mono: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}

@Composable
private fun SmallButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = if (enabled) Color.White else SecondaryLabel,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Surface)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun RangeChoice(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = when { !enabled -> SecondaryLabel; selected -> Color.White; else -> Color.White.copy(alpha = 0.8f) },
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) WitnessRed else Surface)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun duration(seconds: Double): String {
    val s = seconds.toInt()
    return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format("%d:%02d", s / 60, s % 60)
}

private fun clockText(moment: Double): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date((moment * 1000).toLong()))

private fun shareVideo(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
