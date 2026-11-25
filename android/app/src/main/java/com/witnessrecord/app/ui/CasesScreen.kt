package com.witnessrecord.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.witnessrecord.app.R
import com.witnessrecord.app.billing.ProEntitlement
import com.witnessrecord.app.model.CaseItem
import com.witnessrecord.app.model.EvidenceCase
import com.witnessrecord.app.service.CaseLibrary
import com.witnessrecord.app.service.CaseReport
import com.witnessrecord.app.service.LocationService
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.Separator
import com.witnessrecord.app.ui.theme.Surface
import com.witnessrecord.app.ui.theme.WitnessRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Where inside Cases the person is. */
private sealed interface Place {
    data class OneCase(val id: String) : Place
    data class OneItem(val id: String) : Place
}

/**
 * Every case, plus whatever isn't filed in one yet. Opened from the Home
 * toolbar as a panel that slides in from the left, where its button is.
 */
@Composable
fun CasesScreen(onClose: () -> Unit) {
    val cases by CaseLibrary.cases.collectAsState()
    val items by CaseLibrary.items.collectAsState()
    var path by remember { mutableStateOf(listOf<Place>()) }
    var showPaywall by remember { mutableStateOf(false) }
    var recordingAudioFor by remember { mutableStateOf<String?>(null) }
    var audioOpen by remember { mutableStateOf(false) }

    BackHandler { if (path.isNotEmpty()) path = path.dropLast(1) else onClose() }

    androidx.compose.runtime.LaunchedEffect(Unit) { CaseLibrary.refreshRecordingProofs() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val place = path.lastOrNull()) {
            is Place.OneCase -> CaseDetail(
                caseID = place.id,
                onBack = { path = path.dropLast(1) },
                onOpenItem = { path = path + Place.OneItem(it) },
                onPaywall = { showPaywall = true },
                onRecordAudio = { recordingAudioFor = place.id; audioOpen = true },
            )
            is Place.OneItem -> ItemDetailScreen(
                itemID = place.id,
                justRecorded = false,
                onBack = { path = path.dropLast(1) },
                onPaywall = { showPaywall = true },
            )
            null -> CasesList(
                cases = cases, items = items,
                onClose = onClose,
                onOpenCase = { path = path + Place.OneCase(it) },
                onOpenItem = { path = path + Place.OneItem(it) },
                onRecordAudio = { recordingAudioFor = null; audioOpen = true },
            )
        }

        if (audioOpen) {
            AudioRecorderScreen(caseID = recordingAudioFor, onDismiss = { audioOpen = false })
        }
        if (showPaywall) PaywallScreen(onDismiss = { showPaywall = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CasesList(
    cases: List<EvidenceCase>,
    items: List<CaseItem>,
    onClose: () -> Unit,
    onOpenCase: (String) -> Unit,
    onOpenItem: (String) -> Unit,
    onRecordAudio: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showNewCase by remember { mutableStateOf(false) }
    var filing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<EvidenceCase?>(null) }
    val unfiled = items.filter { it.caseID == null }.sortedByDescending { it.createdAt }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                title = { Text(stringResource(R.string.cases_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cases_close), tint = Color.White,
                        )
                    }
                },
                actions = {
                    AddEvidenceButton(caseID = null, onRecordAudio = onRecordAudio) { dismiss ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.cases_new)) },
                            leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                            onClick = { dismiss(); showNewCase = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.cases_search_hint), color = SecondaryLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(12.dp),
            )

            if (query.isNotBlank()) {
                SearchResults(query, onOpenItem)
            } else {
                CaseSection(
                    header = stringResource(R.string.cases_title),
                    footer = stringResource(R.string.cases_footer),
                ) {
                    if (cases.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showNewCase = true }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Filled.CreateNewFolder, null, tint = WitnessRed)
                            Text(stringResource(R.string.cases_create_first), color = Color.White, fontSize = 16.sp)
                        }
                    }
                    cases.forEach { evidenceCase ->
                        CaseRow(
                            evidenceCase = evidenceCase,
                            count = items.count { it.caseID == evidenceCase.id },
                            onClick = { onOpenCase(evidenceCase.id) },
                            onLongClick = { deleting = evidenceCase },
                        )
                    }
                }

                if (unfiled.isNotEmpty()) {
                    CaseSection(
                        header = stringResource(R.string.cases_not_in_a_case),
                        footer = stringResource(R.string.cases_not_in_a_case_footer),
                    ) {
                        unfiled.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ItemRow(item, Modifier.weight(1f).clickable { onOpenItem(item.id) })
                                TextButton(onClick = { filing = item.id }) {
                                    Text(stringResource(R.string.cases_add_to_case), color = WitnessRed, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.size(32.dp))
        }
    }

    if (showNewCase) {
        val untitled = stringResource(R.string.cases_untitled)
        NewCaseDialog(onDismiss = { showNewCase = false }) { title ->
            val created = CaseLibrary.newCase(title, untitled)
            showNewCase = false
            onOpenCase(created.id)
        }
    }
    filing?.let { id -> FileIntoCaseDialog(itemIDs = setOf(id), onDismiss = { filing = null }) }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Surface,
            title = { Text(stringResource(R.string.cases_delete_case), color = Color.White) },
            text = { Text(stringResource(R.string.cases_delete_case_message), color = SecondaryLabel) },
            confirmButton = {
                TextButton(onClick = { CaseLibrary.deleteCase(target.id); deleting = null }) {
                    Text(stringResource(R.string.cases_delete_case), color = WitnessRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel), color = SecondaryLabel)
                }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CaseRow(evidenceCase: EvidenceCase, count: Int, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = WitnessRed, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Text(evidenceCase.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(
                "${stringResource(R.string.cases_item_count, count)} · ${dateOnly(evidenceCase.createdAt)}",
                fontSize = 12.sp, color = SecondaryLabel,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = SecondaryLabel)
    }
}

@Composable
private fun SearchResults(query: String, onOpenItem: (String) -> Unit) {
    val hits = CaseLibrary.search(query)
    val items by CaseLibrary.items.collectAsState()
    if (hits.isEmpty()) {
        Text(
            stringResource(R.string.cases_no_matches),
            color = SecondaryLabel, modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        return
    }
    CaseSection(header = stringResource(R.string.cases_matches, hits.size)) {
        hits.forEach { hit ->
            val item = items.firstOrNull { it.id == hit.itemID } ?: return@forEach
            Column(
                modifier = Modifier.fillMaxWidth().clickable { onOpenItem(item.id) }.padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(itemTitle(item), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(hit.text, fontSize = 14.sp, color = SecondaryLabel, maxLines = 3)
            }
        }
    }
}

// MARK: - Case

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaseDetail(
    caseID: String,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
    onPaywall: () -> Unit,
    onRecordAudio: () -> Unit,
) {
    val cases by CaseLibrary.cases.collectAsState()
    val allItems by CaseLibrary.items.collectAsState()
    val isPro by ProEntitlement.isPro.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val evidenceCase = cases.firstOrNull { it.id == caseID }
    val items = allItems.filter { it.caseID == caseID }.sortedByDescending { it.createdAt }
    var showRename by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                title = { Text(evidenceCase?.title ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                    }
                },
                actions = {
                    AddEvidenceButton(caseID = caseID, onRecordAudio = onRecordAudio) { dismiss ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.case_add_recordings)) },
                            onClick = { dismiss(); showPicker = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.case_rename)) },
                            onClick = { dismiss(); showRename = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            if (items.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.case_nothing_yet), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(
                        stringResource(R.string.case_nothing_yet_description),
                        color = SecondaryLabel, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    TextButton(onClick = { showPicker = true }) {
                        Text(stringResource(R.string.case_add_recordings), color = WitnessRed)
                    }
                }
            } else {
                CaseSection {
                    items.forEach { item -> ItemRow(item, Modifier.clickable { onOpenItem(item.id) }) }
                }
                CaseSection(footer = stringResource(R.string.case_export_footer)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !exporting) {
                                if (!isPro) { onPaywall(); return@clickable }
                                val target = evidenceCase ?: return@clickable
                                exporting = true
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) { CaseReport.build(context, target, items) }
                                    }.onSuccess { files ->
                                        shareFiles(context, files)
                                    }.onFailure { exportError = it.message ?: "?" }
                                    exporting = false
                                }
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (exporting) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Folder, null, tint = WitnessRed)
                        }
                        Text(stringResource(R.string.case_export_report), color = Color.White, fontSize = 16.sp)
                    }
                }
            }
            Spacer(Modifier.size(32.dp))
        }
    }

    if (showRename && evidenceCase != null) {
        var title by remember { mutableStateOf(evidenceCase.title) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            containerColor = Surface,
            title = { Text(stringResource(R.string.case_rename), color = Color.White) },
            text = {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text(stringResource(R.string.case_rename_name)) },
                )
            },
            confirmButton = {
                TextButton(onClick = { CaseLibrary.rename(caseID, title); showRename = false }) {
                    Text(stringResource(R.string.action_save), color = WitnessRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(R.string.action_cancel), color = SecondaryLabel)
                }
            },
        )
    }
    exportError?.let { message ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            containerColor = Surface,
            title = { Text(stringResource(R.string.case_report_failed), color = Color.White) },
            text = { Text(message, color = SecondaryLabel) },
            confirmButton = { TextButton(onClick = { exportError = null }) { Text("OK", color = WitnessRed) } },
        )
    }
    if (showPicker) AddToCaseDialog(caseID = caseID, onDismiss = { showPicker = false })
}

private fun shareFiles(context: Context, files: List<File>) {
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

// MARK: - Filing

/**
 * Every recording and item not already in this case, newest first, to tick and
 * add in one go. Those in another case are listed separately and say where
 * they are now, since adding them here moves them.
 */
@Composable
private fun AddToCaseDialog(caseID: String, onDismiss: () -> Unit) {
    val items by CaseLibrary.items.collectAsState()
    val cases by CaseLibrary.cases.collectAsState()
    var selected by remember { mutableStateOf(setOf<String>()) }
    val others = items.filter { it.caseID != caseID }.sortedByDescending { it.createdAt }
    val unfiled = others.filter { it.caseID == null }
    val elsewhere = others.filter { it.caseID != null }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = SecondaryLabel) }
                Text(
                    stringResource(R.string.case_add_recordings_title),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { CaseLibrary.move(selected, caseID); onDismiss() },
                ) {
                    Text(
                        if (selected.isEmpty()) stringResource(R.string.case_add)
                        else stringResource(R.string.case_add_count, selected.size),
                        color = if (selected.isEmpty()) SecondaryLabel else WitnessRed,
                    )
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (others.isEmpty()) {
                    Text(
                        stringResource(R.string.case_nothing_else) + "\n" + stringResource(R.string.case_every_recording_in),
                        color = SecondaryLabel, modifier = Modifier.padding(top = 32.dp),
                    )
                }
                if (unfiled.isNotEmpty()) {
                    CaseSection(header = stringResource(R.string.cases_not_in_a_case)) {
                        unfiled.forEach { PickRow(it, it.id in selected, null) { id -> selected = toggle(selected, id) } }
                    }
                }
                if (elsewhere.isNotEmpty()) {
                    CaseSection(
                        header = stringResource(R.string.case_in_another),
                        footer = stringResource(R.string.case_adding_moves),
                    ) {
                        elsewhere.forEach { item ->
                            PickRow(item, item.id in selected, cases.firstOrNull { it.id == item.caseID }?.title) { id ->
                                selected = toggle(selected, id)
                            }
                        }
                    }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

private fun toggle(set: Set<String>, id: String): Set<String> = if (id in set) set - id else set + id

@Composable
private fun PickRow(item: CaseItem, on: Boolean, caseTitle: String?, onToggle: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onToggle(item.id) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null, tint = if (on) WitnessRed else Separator,
        )
        ItemRow(item, Modifier.weight(1f))
        caseTitle?.let { Text(it, fontSize = 11.sp, color = SecondaryLabel, maxLines = 1) }
    }
}

/** The "Add to case" picker: the existing cases, or a new one. */
@Composable
internal fun FileIntoCaseDialog(itemIDs: Set<String>, onDismiss: () -> Unit) {
    val cases by CaseLibrary.cases.collectAsState()
    var newTitle by remember { mutableStateOf("") }
    val untitled = stringResource(R.string.cases_untitled)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(stringResource(R.string.cases_add_to_case), color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTitle, onValueChange = { newTitle = it }, singleLine = true,
                        placeholder = { Text(stringResource(R.string.cases_new_name_hint), color = SecondaryLabel) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newTitle.isNotBlank(),
                        onClick = {
                            val created = CaseLibrary.newCase(newTitle, untitled)
                            CaseLibrary.move(itemIDs, created.id)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.cases_create), color = if (newTitle.isBlank()) SecondaryLabel else WitnessRed) }
                }
                cases.forEach { evidenceCase ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { CaseLibrary.move(itemIDs, evidenceCase.id); onDismiss() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Filled.Folder, null, tint = WitnessRed)
                        Text(evidenceCase.title, color = Color.White, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = SecondaryLabel) } },
    )
}

@Composable
internal fun NewCaseDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(stringResource(R.string.cases_new), color = Color.White) },
        text = {
            OutlinedTextField(
                value = title, onValueChange = { title = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.cases_new_name_hint), color = SecondaryLabel) },
            )
        },
        confirmButton = { TextButton(onClick = { onCreate(title) }) { Text(stringResource(R.string.cases_create), color = WitnessRed) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = SecondaryLabel) } },
    )
}

// MARK: - Adding

/**
 * The + menu: a verified photo, an audio recording, or an import. [extra]
 * puts the caller's own entries at the top.
 */
@Composable
internal fun AddEvidenceButton(
    caseID: String?,
    onRecordAudio: () -> Unit,
    extra: @Composable (dismiss: () -> Unit) -> Unit = {},
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhoto
        pendingPhoto = null
        if (ok && file != null) {
            CaseLibrary.scope.launch { CaseLibrary.addFile(file, CaseItem.KIND_PHOTO, imported = false, caseID = caseID) }
        } else file?.delete()
    }
    fun launchCamera() {
        val file = File(context.cacheDir, "camera-${UUID.randomUUID()}.jpg")
        pendingPhoto = file
        // A fresh fix, so the photo's record carries where it was taken.
        LocationService.requestOneShotLocation()
        takePicture.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }

    fun importAll(uris: List<Uri>, titled: Boolean) {
        if (uris.isEmpty()) return
        importing = true
        // Not tied to this screen: leaving it must not cut an import short.
        CaseLibrary.scope.launch {
            for (uri in uris) importOne(context, uri, caseID, titled)
            importing = false
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        importAll(it, titled = false)
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        importAll(it, titled = true)
    }

    Box {
        IconButton(onClick = { open = true }) {
            if (importing) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            extra { open = false }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_verified_photo)) },
                onClick = {
                    open = false
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    ) launchCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_audio_only)) },
                onClick = { open = false; onRecordAudio() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_import_media)) },
                onClick = {
                    open = false
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_import_file)) },
                onClick = { open = false; pickFiles.launch(arrayOf("*/*")) },
            )
        }
    }
}

/** Copies one picked file into the app's own storage and files it as an import. */
private suspend fun importOne(context: Context, uri: Uri, caseID: String?, titled: Boolean) {
    val resolver = context.contentResolver
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()
    val extension = name?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 }
        ?: android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri)) ?: "bin"
    val temporary = File(context.cacheDir, "import-${UUID.randomUUID()}.$extension")
    val copied = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            } != null
        }.getOrDefault(false)
    }
    if (!copied) {
        temporary.delete()
        return
    }
    CaseLibrary.addFile(
        temporary, CaseItem.KIND_FILE, imported = true,
        title = if (titled) name else null, caseID = caseID,
    )
}
