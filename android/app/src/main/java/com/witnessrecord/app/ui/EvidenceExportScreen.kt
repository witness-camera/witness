package com.witnessrecord.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.witnessrecord.app.R
import com.witnessrecord.app.billing.ProEntitlement
import com.witnessrecord.app.model.SessionManifest
import com.witnessrecord.app.service.EvidenceReport
import com.witnessrecord.app.service.SessionArchive
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.WitnessGreen
import com.witnessrecord.app.ui.theme.WitnessRed
import java.text.DateFormat
import java.util.Date

/**
 * Lists finished recordings and turns any of them into an evidence package:
 * a readable PDF report plus the manifest and time-stamp proof it describes.
 *
 * The list is drawn from the local archive rather than from cloud storage, so
 * it keeps working with no network, no connected account, and long after the
 * footage itself has been cleaned off the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvidenceExportScreen(onDismiss: () -> Unit) {
    BackHandler { onDismiss() }
    val context = LocalContext.current
    val isPro by ProEntitlement.isPro.collectAsState()

    var sessions by remember { mutableStateOf(SessionArchive.sessions()) }
    var showPaywall by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

    if (showPaywall) {
        PaywallScreen(onDismiss = { showPaywall = false })
        return
    }

    fun export(manifest: SessionManifest) {
        try {
            val hasTimestamp = SessionArchive.hasTimestamp(manifest.sessionID)
            val pdf = EvidenceReport.pdf(manifest, hasTimestamp)
            val files = mutableListOf(pdf)
            val manifestFile = SessionArchive.manifestFile(manifest.sessionID)
            if (manifestFile.exists()) files.add(manifestFile)
            if (hasTimestamp) files.add(SessionArchive.timestampFile(manifest.sessionID))

            val uris = files.map {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/octet-stream"
                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, null))
        } catch (error: Throwable) {
            exportError = error.message
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                title = { Text(stringResource(R.string.evidence_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_done), tint = Color.White)
                    }
                },
            )
        },
    ) { padding ->
        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.DocumentScanner, contentDescription = null, tint = SecondaryLabel, modifier = Modifier.size(40.dp))
                Text(
                    stringResource(R.string.evidence_none_title),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.evidence_none_body),
                    fontSize = 13.sp, color = SecondaryLabel,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                if (!isPro) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPaywall = true }
                                .padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = WitnessRed)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.pro_unlock_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Text(stringResource(R.string.evidence_export_subtitle), fontSize = 12.sp, color = SecondaryLabel)
                            }
                        }
                        Text(stringResource(R.string.evidence_unlock_footer), fontSize = 12.sp, color = SecondaryLabel, modifier = Modifier.padding(bottom = 12.dp))
                    }
                }
                items(sessions) { manifest ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (isPro) export(manifest) else showPaywall = true }
                            .padding(vertical = 12.dp),
                    ) {
                        Column {
                            Text(
                                DateFormat.getDateTimeInstance().format(Date(manifest.recordingStartedUTC)),
                                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    stringResource(R.string.evidence_segments, manifest.chunkCount),
                                    fontSize = 12.sp, color = SecondaryLabel,
                                )
                                if (SessionArchive.hasTimestamp(manifest.sessionID)) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = WitnessGreen, modifier = Modifier.size(12.dp))
                                    Text(stringResource(R.string.evidence_timestamped), fontSize = 12.sp, color = WitnessGreen)
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.evidence_footer),
                        fontSize = 12.sp, color = SecondaryLabel,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }
        }
    }

    exportError?.let { message ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            confirmButton = { TextButton(onClick = { exportError = null }) { Text("OK") } },
            title = { Text(stringResource(R.string.evidence_build_failed)) },
            text = { Text(message) },
        )
    }
}
