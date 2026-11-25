package com.witnessrecord.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.witnessrecord.app.R
import com.witnessrecord.app.model.CaseItem
import com.witnessrecord.app.model.DetailTemplate
import com.witnessrecord.app.service.CaseLibrary
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.Surface
import com.witnessrecord.app.ui.theme.WitnessGreen
import com.witnessrecord.app.ui.theme.WitnessRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One recording, photo or file: which case it's in, the details and notes
 * added afterwards, and what proves it. [justRecorded] is shown straight after
 * a recording, so the case picker comes first and the close control reads as
 * done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    itemID: String,
    justRecorded: Boolean,
    onBack: () -> Unit,
    onPaywall: () -> Unit,
) {
    val items by CaseLibrary.items.collectAsState()
    val cases by CaseLibrary.cases.collectAsState()
    val item = items.firstOrNull { it.id == itemID }
    val context = LocalContext.current

    var showNewCase by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var casePickerOpen by remember { mutableStateOf(false) }
    var playbackMessage by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    // Audio plays from a player owned by this screen, and is let go with it.
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { runCatching { player?.release() } } }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                title = { Text(item?.let { kindName(it) } ?: stringResource(R.string.item_removed)) },
                navigationIcon = {
                    if (justRecorded) {
                        TextButton(onClick = onBack) { Text(stringResource(R.string.action_done), color = WitnessRed) }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (item == null) return@Scaffold
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            // Header: the media, its name, and when and where.
            CaseSection {
                Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (item.kind) {
                        CaseItem.KIND_PHOTO -> PhotoPreview(item)
                        CaseItem.KIND_VIDEO -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1F1F1F))
                                    .clickable {
                                        val uri = playableGalleryUri(context, item)
                                        if (uri == null) {
                                            playbackMessage = context.getString(R.string.item_video_missing)
                                        } else {
                                            playbackMessage = null
                                            viewMedia(context, uri, "video/*")
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                ItemThumbnail(item, size = androidx.compose.ui.unit.Dp.Unspecified, modifier = Modifier.fillMaxSize())
                                Box(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(14.dp)) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = stringResource(R.string.item_play_recording),
                                        tint = Color.White, modifier = Modifier.size(26.dp),
                                    )
                                }
                            }
                            playbackMessage?.let { Text(it, fontSize = 12.sp, color = SecondaryLabel) }
                        }
                        else -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                ItemThumbnail(item, size = 84.dp)
                                val media = CaseLibrary.mediaFile(item)
                                if (media != null && isMovie(media)) {
                                    TextButton(onClick = { viewMedia(context, fileUri(context, media), "video/*") }) {
                                        Icon(Icons.Filled.PlayArrow, null, tint = WitnessRed)
                                        Text(
                                            stringResource(R.string.item_play_video),
                                            color = WitnessRed, fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    CaseLibrary.audioFile(item)?.let { audio ->
                        TextButton(
                            onClick = {
                                if (playing) {
                                    player?.pause()
                                    playing = false
                                } else {
                                    if (player == null) {
                                        player = runCatching {
                                            MediaPlayer().apply {
                                                setDataSource(audio.absolutePath)
                                                prepare()
                                                setOnCompletionListener { playing = false }
                                            }
                                        }.getOrNull()
                                    }
                                    playing = player?.let { it.start(); true } ?: false
                                }
                            },
                        ) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.GraphicEq,
                                contentDescription = null, tint = WitnessRed,
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                stringResource(if (playing) R.string.item_pause else R.string.item_play_audio),
                                color = WitnessRed, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    DebouncedField(
                        value = item.title.orEmpty(),
                        label = stringResource(R.string.item_name_hint),
                        singleLine = true,
                    ) { value -> CaseLibrary.update(itemID) { it.copy(title = value.ifEmpty { null }) } }
                    Text(subtitle(item), fontSize = 12.sp, color = SecondaryLabel)
                }
            }

            // Case
            CaseSection(header = if (justRecorded) stringResource(R.string.item_recording_saved) else null) {
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { casePickerOpen = true }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Filled.Folder, null, tint = WitnessRed)
                        Text(stringResource(R.string.item_case), color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(
                            cases.firstOrNull { it.id == item.caseID }?.title ?: stringResource(R.string.cases_not_in_a_case),
                            color = SecondaryLabel, fontSize = 15.sp,
                        )
                    }
                    DropdownMenu(expanded = casePickerOpen, onDismissRequest = { casePickerOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cases_not_in_a_case)) },
                            onClick = { casePickerOpen = false; CaseLibrary.move(itemID, null) },
                        )
                        cases.forEach { evidenceCase ->
                            DropdownMenuItem(
                                text = { Text(evidenceCase.title) },
                                onClick = { casePickerOpen = false; CaseLibrary.move(itemID, evidenceCase.id) },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showNewCase = true }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.CreateNewFolder, null, tint = WitnessRed)
                    Text(stringResource(R.string.item_new_case), color = Color.White, fontSize = 16.sp)
                }
            }

            DetailsSection(item)
            NotesSection(item)
            ProofSection(item)

            CaseSection(
                footer = stringResource(
                    if (item.isVideo) R.string.item_remove_footer_video else R.string.item_remove_footer_other
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { confirmDelete = true }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Delete, null, tint = WitnessRed)
                    Text(stringResource(R.string.item_remove), color = WitnessRed, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.size(32.dp))
        }
    }

    if (showNewCase) {
        val untitled = stringResource(R.string.cases_untitled)
        NewCaseDialog(onDismiss = { showNewCase = false }) { title ->
            val created = CaseLibrary.newCase(title, untitled)
            CaseLibrary.move(itemID, created.id)
            showNewCase = false
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Surface,
            title = { Text(stringResource(R.string.item_remove_confirm), color = Color.White) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; CaseLibrary.deleteItem(itemID); onBack() }) {
                    Text(stringResource(R.string.item_remove_action), color = WitnessRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel), color = SecondaryLabel)
                }
            },
        )
    }
}

@Composable
private fun PhotoPreview(item: CaseItem) {
    val bitmap by produceState<ImageBitmap?>(null, item.id, item.fileName) {
        value = withContext(Dispatchers.IO) {
            CaseLibrary.mediaFile(item)?.let { decodeSampled(it, 1600) }
        }
    }
    bitmap?.let {
        Image(
            bitmap = it, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        )
    }
}

private fun decodeSampled(file: File, maxSide: Int): ImageBitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}

private fun isMovie(file: File): Boolean = file.extension.lowercase() in setOf("mp4", "mov", "m4v", "3gp", "webm", "mkv")

private fun fileUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

/** The first gallery copy that is still there, or null. */
private fun playableGalleryUri(context: Context, item: CaseItem): Uri? =
    item.galleryUris.orEmpty().map { Uri.parse(it) }.firstOrNull { uri ->
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
    }

private fun viewMedia(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

private fun subtitle(item: CaseItem): String {
    val parts = mutableListOf(longStamp(item.createdAt))
    val end = item.endedAt
    if (end != null && (item.isVideo || item.kind == CaseItem.KIND_AUDIO)) {
        parts.add(clock((end - item.createdAt) / 1000))
    }
    if (item.latitude != null && item.longitude != null) {
        parts.add("%.4f, %.4f".format(item.latitude, item.longitude))
    }
    return parts.joinToString(" · ")
}

/**
 * A text field that keeps what is typed in step with the library a moment
 * after the last keystroke, rather than rewriting the library file on each one.
 */
@Composable
private fun DebouncedField(
    value: String,
    label: String,
    singleLine: Boolean,
    onSave: (String) -> Unit,
) {
    var text by remember(label) { mutableStateOf(value) }
    LaunchedEffect(text) {
        if (text == value) return@LaunchedEffect
        delay(400)
        onSave(text)
    }
    OutlinedTextField(
        value = text, onValueChange = { text = it },
        label = { Text(label) }, singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DetailsSection(item: CaseItem) {
    val template = DetailTemplate.fromWire(item.template)
    CaseSection(
        header = stringResource(R.string.item_details),
        footer = stringResource(R.string.item_details_footer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DetailTemplate.entries.forEach { choice ->
                val selected = template == choice
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (selected) WitnessRed else Color(0xFF333333))
                        .clickable {
                            CaseLibrary.update(item.id) { it.copy(template = if (selected) null else choice.wire) }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(choice.nameRes), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
        template?.prompts?.forEach { prompt ->
            Spacer(Modifier.size(6.dp))
            DebouncedField(
                value = item.details[prompt.id].orEmpty(),
                label = stringResource(prompt.labelRes),
                singleLine = false,
            ) { value -> CaseLibrary.update(item.id) { it.copy(details = it.details + (prompt.id to value)) } }
        }
        Spacer(Modifier.size(6.dp))
    }
}

@Composable
private fun NotesSection(item: CaseItem) {
    var newNote by remember { mutableStateOf("") }
    CaseSection(header = stringResource(R.string.item_notes)) {
        item.notes.forEach { note ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(note.text, color = Color.White, fontSize = 15.sp)
                    Text(shortStamp(note.createdAt), fontSize = 11.sp, color = SecondaryLabel)
                }
                IconButton(onClick = { CaseLibrary.removeNote(item.id, note.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = SecondaryLabel)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newNote, onValueChange = { newNote = it },
                placeholder = { Text(stringResource(R.string.item_add_note), color = SecondaryLabel) },
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = newNote.isNotBlank(),
                onClick = { CaseLibrary.addNote(item.id, newNote); newNote = "" },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send, contentDescription = null,
                    tint = if (newNote.isBlank()) SecondaryLabel else WitnessRed,
                )
            }
        }
    }
}

@Composable
private fun ProofSection(item: CaseItem) {
    CaseSection(
        header = stringResource(R.string.item_proof),
        footer = when {
            item.isImported -> stringResource(R.string.item_proof_imported_footer)
            item.isVideo -> stringResource(R.string.item_proof_video_footer)
            else -> null
        },
    ) {
        if (item.isVideo) {
            ProofLine(stringResource(R.string.item_proof_manifest))
            item.sessionID?.let {
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(stringResource(R.string.item_recording), color = SecondaryLabel, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(it.take(8), color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }
        } else if (item.sha256 != null) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(stringResource(R.string.item_proof_sha), fontSize = 12.sp, color = SecondaryLabel)
                Text(item.sha256, fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
            }
        }
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (item.timestamped) Icons.Filled.VerifiedUser else Icons.Filled.Schedule, null,
                tint = if (item.timestamped) WitnessGreen else SecondaryLabel, modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(if (item.timestamped) R.string.item_proof_stamped else R.string.item_proof_pending),
                color = if (item.timestamped) WitnessGreen else SecondaryLabel, fontSize = 15.sp,
            )
        }
        if (!item.isVideo) {
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (item.uploaded) Icons.Filled.CloudDone else Icons.Filled.CloudOff, null,
                    tint = if (item.uploaded) Color.White else SecondaryLabel, modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(if (item.uploaded) R.string.item_proof_uploaded else R.string.item_proof_waiting),
                    color = if (item.uploaded) Color.White else SecondaryLabel, fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun ProofLine(text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(stringResource(R.string.item_proof), color = SecondaryLabel, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}
