package com.witnessrecord.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.witnessrecord.app.R
import com.witnessrecord.app.model.Crew
import com.witnessrecord.app.model.CrewAngle
import com.witnessrecord.app.service.CrewLink
import com.witnessrecord.app.service.CrewStore
import com.witnessrecord.app.service.MulticamExport
import com.witnessrecord.app.service.storage.StorageProviders
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.Surface
import com.witnessrecord.app.ui.theme.WitnessGreen
import com.witnessrecord.app.ui.theme.WitnessOrange
import com.witnessrecord.app.ui.theme.WitnessRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * Starting, joining and running a crew: several phones recording one event
 * into a shared case on a common clock.
 *
 * There is no live link between Android phones, so a crew is kept up to date
 * the way it was joined: by showing or sending a code. Everyone's code shows
 * their own copy of the crew, and scanning someone else's folds it into yours.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrewScreen(onClose: () -> Unit) {
    val crews by CrewStore.crews.collectAsState()
    val activeID by CrewStore.activeCrewID.collectAsState()
    // Read only so a change to the name, email or clock redraws the screen.
    @Suppress("UNUSED_VARIABLE") val revision by CrewStore.revision.collectAsState()
    var shownID by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var watching by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val shown = crews.firstOrNull { it.id == shownID } ?: crews.firstOrNull { it.id == activeID }
    val others = crews.filter { it.id != shown?.id }.sortedByDescending { it.createdAt }

    BackHandler { onClose() }

    // Opening the screen is when the clock is compared: the phone is online
    // and its owner is about to rely on the crew.
    LaunchedEffect(shown?.id) { shown?.id?.let { CrewStore.measureClock(it) } }

    /** Takes in whatever a scan or paste produced and says what happened. */
    fun take(text: String?) {
        val crew = text?.let { CrewLink.parse(fromText = it) }
        if (crew == null) {
            message = context.getString(R.string.crew_not_a_crew)
            return
        }
        val known = CrewStore.crew(crew.id) != null
        val result = CrewStore.receive(crew)
        shownID = result.id
        message = if (known) context.getString(R.string.crew_updated)
        else context.getString(R.string.crew_joined, result.title)
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                title = { Text(stringResource(R.string.crew_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_close), tint = Color.White,
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
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            message?.let {
                Text(it, color = WitnessOrange, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (shown == null) {
                StartSections(
                    onStarted = { shownID = it.id },
                    onScanned = ::take,
                    onFailed = { message = it },
                )
            } else {
                CrewSections(
                    crew = shown,
                    isActive = activeID == shown.id,
                    onScanned = ::take,
                    onFailed = { message = it },
                    onDeleted = { shownID = null },
                    onWatch = { watching = it },
                    scope = scope,
                )
            }
            if (others.isNotEmpty()) {
                CaseSection(header = stringResource(R.string.crew_earlier_header)) {
                    others.forEach { crew ->
                        Text(
                            crew.title, color = Color.White, fontSize = 16.sp,
                            modifier = Modifier.fillMaxWidth().clickable { shownID = crew.id }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.size(32.dp))
        }
    }
    watching?.let { CrewPlayerScreen(crewID = it, onClose = { watching = null }) }
    }
}

// MARK: - Starting

@Composable
private fun StartSections(
    onStarted: (Crew) -> Unit,
    onScanned: (String?) -> Unit,
    onFailed: (String) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    CaseSection {
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.crew_hero_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(stringResource(R.string.crew_hero_body), fontSize = 15.sp, color = SecondaryLabel)
        }
        Reason(Icons.Filled.VideoLibrary, R.string.crew_reason_angles_title, R.string.crew_reason_angles_body)
        Reason(Icons.Filled.Group, R.string.crew_reason_nobody_title, R.string.crew_reason_nobody_body)
        Reason(Icons.Filled.Schedule, R.string.crew_reason_timeline_title, R.string.crew_reason_timeline_body)
        Reason(Icons.Filled.CloudOff, R.string.crew_reason_server_title, R.string.crew_reason_server_body)
    }
    CaseSection(header = stringResource(R.string.crew_how_header)) {
        Step(1, R.string.crew_step_1)
        Step(2, R.string.crew_step_2)
        Step(3, R.string.crew_step_3)
        Step(4, R.string.crew_step_4)
    }
    MyDetailsSection(first = false)
    CaseSection(footer = stringResource(R.string.crew_start_footer)) {
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            placeholder = { Text(stringResource(R.string.crew_event_hint), color = SecondaryLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
        )
        ActionRow(stringResource(R.string.crew_start), Icons.Filled.Group, WitnessRed) {
            CrewStore.updateMyDetails()
            val crew = CrewStore.start(title)
            title = ""
            onStarted(crew)
        }
    }
    JoinSection(onScanned, onFailed)
}

@Composable
private fun JoinSection(onScanned: (String?) -> Unit, onFailed: (String) -> Unit) {
    val context = LocalContext.current
    var pasted by rememberSaveable { mutableStateOf("") }
    CaseSection(header = stringResource(R.string.crew_join_header)) {
        ActionRow(stringResource(R.string.crew_scan), Icons.Filled.QrCodeScanner, WitnessRed) {
            scanCode(context, onScanned) { onFailed(context.getString(R.string.crew_scan_failed)) }
        }
        OutlinedTextField(
            value = pasted, onValueChange = { pasted = it },
            placeholder = { Text(stringResource(R.string.crew_join_hint), color = SecondaryLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 6.dp)) {
            Text(
                stringResource(R.string.crew_paste), color = WitnessRed, fontSize = 16.sp,
                modifier = Modifier.clickable { pasted = clipboardText(context).orEmpty() }.padding(vertical = 8.dp),
            )
            Text(
                stringResource(R.string.crew_join), color = WitnessRed, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    onScanned(pasted)
                    pasted = ""
                }.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Reason(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, body: Int) {
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = WitnessRed, modifier = Modifier.size(26.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(stringResource(body), fontSize = 13.sp, color = SecondaryLabel)
        }
    }
}

@Composable
private fun Step(number: Int, text: Int) {
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(WitnessRed.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) { Text("$number", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        Text(stringResource(text), fontSize = 14.sp, color = Color.White)
    }
}

@Composable
private fun ActionRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(text, color = Color.White, fontSize = 16.sp)
    }
}

/** The person's own name and email: what the others see, and where footage is shared. */
@Composable
private fun MyDetailsSection(first: Boolean) {
    var name by remember { mutableStateOf(CrewStore.myName) }
    var email by remember { mutableStateOf(CrewStore.myEmail) }
    CaseSection(
        header = stringResource(R.string.crew_you_header),
        footer = stringResource(if (first) R.string.crew_details_footer_first else R.string.crew_details_footer),
    ) {
        // Saved as typed, not on a Done key nobody presses, so the crew
        // learns who has joined the moment the name is there.
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; CrewStore.myName = it; CrewStore.updateMyDetails() },
            placeholder = { Text(stringResource(R.string.crew_your_name), color = SecondaryLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; CrewStore.myEmail = it; CrewStore.updateMyDetails() },
            placeholder = { Text(stringResource(R.string.crew_your_email), color = SecondaryLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
        )
    }
}

// MARK: - A crew

@Composable
private fun CrewSections(
    crew: Crew,
    isActive: Boolean,
    onScanned: (String?) -> Unit,
    onFailed: (String) -> Unit,
    onDeleted: () -> Unit,
    onWatch: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val myID = CrewStore.myMemberID(crew.id)
    val needsDetails = CrewStore.needsMyDetails(crew.id)
    var exported by remember(crew.id) { mutableStateOf<List<File>>(emptyList()) }
    var exportError by remember(crew.id) { mutableStateOf<String?>(null) }
    var matching by remember(crew.id) { mutableStateOf(false) }

    CaseSection {
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(crew.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(
                stringResource(if (isActive) R.string.crew_new_recordings_join else R.string.crew_new_recordings_dont),
                fontSize = 13.sp, color = if (isActive) WitnessGreen else SecondaryLabel,
            )
        }
        Text(
            stringResource(if (isActive) R.string.crew_stop_recording_into else R.string.crew_record_into),
            color = WitnessRed, fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth()
                .clickable { if (isActive) CrewStore.leave() else CrewStore.rejoin(crew.id) }
                .padding(vertical = 12.dp),
        )
    }

    if (needsDetails) MyDetailsSection(first = true)

    // Without an account nothing is uploaded, so there is no folder for the
    // crew to be given - the one way a member's angle never arrives.
    if (StorageProviders.current?.isSignedIn != true) {
        Text(
            stringResource(R.string.crew_connect_storage_warning),
            color = WitnessOrange, fontSize = 13.sp, modifier = Modifier.padding(top = 16.dp, start = 4.dp),
        )
    }

    // The code is this phone's own copy of the crew, so showing it to someone
    // both invites them and, if they were already in, hands over what this
    // phone has learned since.
    val link = remember(crew) { CrewLink.url(crew) }
    CaseSection(
        header = stringResource(R.string.crew_invite_header),
        footer = stringResource(R.string.crew_invite_footer),
    ) {
        val qr = remember(link) { qrBitmap(link) }
        if (qr != null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = qr,
                    contentDescription = stringResource(R.string.crew_code_description),
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                )
            }
        } else {
            Text(stringResource(R.string.crew_code_too_big), color = WitnessOrange, fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp))
        }
        ActionRow(stringResource(R.string.crew_send_link), Icons.Filled.Share, WitnessRed) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, crew.title)
                putExtra(Intent.EXTRA_TEXT, context.getString(R.string.crew_share_message, crew.title) + "\n" + link)
            }
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    CaseSection(
        header = stringResource(R.string.crew_sync_header),
        footer = stringResource(R.string.crew_sync_footer),
    ) {
        ActionRow(stringResource(R.string.crew_scan_member), Icons.Filled.QrCodeScanner, WitnessRed) {
            scanCode(context, onScanned) { onFailed(context.getString(R.string.crew_scan_failed)) }
        }
        val clock = CrewStore.clock(crew.id)
        val uncertainty = clock?.uncertaintySeconds
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Schedule, contentDescription = null, tint = SecondaryLabel, modifier = Modifier.size(20.dp))
            Text(
                when {
                    matching -> stringResource(R.string.crew_clock_matching)
                    uncertainty != null -> stringResource(
                        R.string.crew_clock_matched, (uncertainty * 1000).roundToInt().coerceAtLeast(1),
                    )
                    else -> stringResource(R.string.crew_clock_unmatched)
                },
                fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f),
            )
            if (matching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = WitnessRed)
        }
        Text(
            stringResource(R.string.crew_clock_match_now), color = WitnessRed, fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth().clickable(enabled = !matching) {
                matching = true
                scope.launch {
                    CrewStore.measureClock(crew.id)
                    matching = false
                }
            }.padding(vertical = 12.dp),
        )
    }

    CaseSection(header = stringResource(R.string.crew_members_header)) {
        val now = System.currentTimeMillis() / 1000.0
        crew.members.forEach { member ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(member.name, color = Color.White, fontSize = 16.sp)
                    if (member.id == crew.organizerID) Tag(stringResource(R.string.crew_organizer))
                    if (member.id == myID) Tag(stringResource(R.string.crew_you))
                    if (member.isRecordingNow(now)) {
                        Text(stringResource(R.string.crew_filming_now), color = WitnessRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(member.email ?: stringResource(R.string.crew_no_email), color = SecondaryLabel, fontSize = 13.sp)
            }
        }
    }
    if (!needsDetails && myID != null) MyDetailsSection(first = false)

    CaseSection(
        header = stringResource(R.string.crew_recordings_header),
        footer = stringResource(R.string.crew_recordings_footer),
    ) {
        if (crew.angles.isEmpty()) {
            Text(stringResource(R.string.crew_no_recordings), color = SecondaryLabel, modifier = Modifier.padding(vertical = 12.dp))
        } else {
            ActionRow(stringResource(R.string.crew_watch), Icons.Filled.PlayCircle, WitnessRed) { onWatch(crew.id) }
            crew.angles.sortedBy { it.startedAt }.forEach { angle ->
                AngleRow(angle, crew, isMine = angle.memberID == myID, onOpen = { uriHandler.openUri(it) })
            }
            ActionRow(stringResource(R.string.crew_make_export), Icons.Filled.VideoLibrary, WitnessRed) {
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { MulticamExport.export(context, crew) } }
                        .onSuccess { exported = it; exportError = null }
                        .onFailure { exportError = it.message ?: context.getString(R.string.crew_export_failed) }
                }
            }
            if (exported.isNotEmpty()) {
                ActionRow(stringResource(R.string.crew_share_export), Icons.Filled.Share, WitnessRed) {
                    shareFiles(context, exported)
                }
            }
            exportError?.let { Text(it, color = WitnessRed, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        }
    }

    CaseSection(footer = stringResource(R.string.crew_delete_footer)) {
        Text(
            stringResource(R.string.crew_delete), color = WitnessRed, fontSize = 16.sp,
            modifier = Modifier.fillMaxWidth().clickable {
                CrewStore.delete(crew.id)
                onDeleted()
            }.padding(vertical = 12.dp),
        )
    }
}

@Composable
private fun Tag(text: String) {
    Text(text, fontSize = 11.sp, color = SecondaryLabel)
}

@Composable
private fun AngleRow(angle: CrewAngle, crew: Crew, isMine: Boolean, onOpen: (String) -> Unit) {
    val member = crew.members.firstOrNull { it.id == angle.memberID }?.name.orEmpty()
    val minutes = kotlin.math.ceil(angle.duration / 60).toInt().coerceAtLeast(1)
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((angle.startedAt * 1000).toLong()))
    val url = angle.folderUrl(isMine)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(if (isMine) stringResource(R.string.crew_you) else member, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.crew_angle_time, time, minutes), color = SecondaryLabel, fontSize = 14.sp)
        }
        if (url != null) {
            Text(
                stringResource(
                    when {
                        angle.providerID == "dropbox" && isMine -> R.string.crew_open_dropbox
                        angle.providerID == "dropbox" -> R.string.crew_open_dropbox_shared
                        else -> R.string.crew_open_drive
                    }
                ),
                color = WitnessRed, fontSize = 13.sp,
                modifier = Modifier.clickable { onOpen(url) },
            )
        } else {
            Text(
                stringResource(if (angle.providerID == "webDAV") R.string.crew_own_server_note else R.string.crew_waiting_footage),
                color = SecondaryLabel, fontSize = 12.sp,
            )
        }
    }
}

// MARK: - Helpers

/**
 * Opens Google's code scanner, which has its own camera screen and needs no
 * camera permission from this app. [onFailed] covers a phone without Google
 * Play services, where the invite link can still be pasted.
 */
private fun scanCode(context: Context, onResult: (String?) -> Unit, onFailed: () -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { onResult(it.rawValue) }
        .addOnFailureListener { error ->
            // Backing out of the scanner is not a failure worth a message.
            if (error is com.google.android.gms.common.api.ApiException &&
                error.statusCode == com.google.mlkit.common.MlKitException.CODE_SCANNER_CANCELLED
            ) return@addOnFailureListener
            onFailed()
        }
}

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    return clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

/** Black on white, as a scanner expects; null when the crew is too big for one code. */
private fun qrBitmap(text: String): ImageBitmap? = runCatching {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val scale = (900 / matrix.width).coerceAtLeast(2)
    val side = matrix.width * scale
    val pixels = IntArray(side * side)
    for (y in 0 until side) for (x in 0 until side) {
        pixels[y * side + x] = if (matrix.get(x / scale, y / scale)) AndroidColor.BLACK else AndroidColor.WHITE
    }
    Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

private fun shareFiles(context: Context, files: List<File>) {
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
