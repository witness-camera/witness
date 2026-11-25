package com.witnessrecord.app.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.witnessrecord.app.R
import com.witnessrecord.app.model.CameraSource
import com.witnessrecord.app.model.ChunkKind
import com.witnessrecord.app.model.ChunkState
import com.witnessrecord.app.service.QueuedChunk
import com.witnessrecord.app.service.SessionUploadReceipt
import com.witnessrecord.app.service.UploadManager
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.Separator
import com.witnessrecord.app.ui.theme.Surface
import com.witnessrecord.app.ui.theme.WitnessGreen
import com.witnessrecord.app.ui.theme.WitnessOrange
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * What is still on the phone, and how far each piece has got.
 *
 * The home screen says "8 pieces queued for upload", which answers how many
 * but not whether anything is actually moving. On a bad connection those are
 * very different questions, and this app exists precisely for the situations
 * where the answer matters. This page shows the queue itself: which pieces,
 * how large, which one is transferring right now and how far through it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadQueueScreen(onDismiss: () -> Unit) {
    BackHandler { onDismiss() }
    val queued by UploadManager.queued.collectAsState()
    val progress by UploadManager.chunkProgress.collectAsState()
    val receipt by UploadManager.lastUpload.collectAsState()
    val lastError by UploadManager.lastError.collectAsState()
    val context = LocalContext.current

    // Retry countdowns are relative to right now, so the rows showing one have
    // to be re-read as it passes. A second's resolution is what they print.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                ),
                title = { Text(stringResource(R.string.queue_title), fontSize = 18.sp) },
                actions = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_done), color = Color.White)
                    }
                },
                navigationIcon = {},
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (queued.isEmpty()) {
                item { EverythingUploaded() }
            } else {
                // Above the per-piece rows, not just on the home screen: a
                // failure that happens before any piece is even attempted -
                // no storage account, a server that refuses to create the
                // folder - would otherwise leave every row below saying only
                // "retrying", with nothing here to say why.
                lastError?.let { item { RootCauseBanner(it) } }
                item {
                    val total = queued.sumOf { it.byteCount ?: 0L }
                    Text(
                        text = when {
                            total > 0 && queued.size == 1 -> stringResource(
                                R.string.queue_header_one_with_size,
                                Formatter.formatFileSize(context, total),
                            )
                            total > 0 -> stringResource(
                                R.string.queue_header_with_size,
                                queued.size,
                                Formatter.formatFileSize(context, total),
                            )
                            queued.size == 1 -> stringResource(R.string.queue_header_one)
                            else -> stringResource(R.string.queue_header, queued.size)
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SecondaryLabel,
                        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                    )
                }
                items(queued, key = { it.id }) { chunk ->
                    QueueRow(chunk = chunk, fraction = progress[chunk.id], now = now)
                }
                item {
                    // Says what the list above actually is: the order things
                    // will go in, one at a time. The preview sentence only
                    // appears when previews exist, since they are only made on
                    // a connection that is losing ground.
                    val hasPreviews = queued.any { it.kind == ChunkKind.LOW_RES }
                    Text(
                        text = if (hasPreviews) {
                            stringResource(R.string.queue_footer_with_previews)
                        } else {
                            stringResource(R.string.queue_footer)
                        },
                        fontSize = 12.sp,
                        color = SecondaryLabel,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            receipt?.let { item { DeliveredSection(it) } }
        }
    }
}

// MARK: - Rows

@Composable
private fun QueueRow(chunk: QueuedChunk, fraction: Float?, now: Long) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                if (chunk.camera == CameraSource.FRONT) Icons.Filled.PersonOutline else Icons.Filled.Videocam,
                contentDescription = null,
                tint = SecondaryLabel,
                modifier = Modifier.size(16.dp),
            )
            // The stand-in is a different thing from the recording it
            // precedes, and someone counting pieces should not have to wonder
            // why there are more than they filmed.
            val piece = if (chunk.camera == CameraSource.FRONT) {
                stringResource(R.string.queue_piece_front, chunk.index + 1)
            } else {
                stringResource(R.string.queue_piece_rear, chunk.index + 1)
            }
            Text(
                text = if (chunk.kind == ChunkKind.LOW_RES) {
                    stringResource(R.string.queue_piece_preview, piece)
                } else {
                    piece
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            chunk.byteCount?.let {
                Text(
                    text = Formatter.formatFileSize(context, it),
                    fontSize = 13.sp,
                    color = SecondaryLabel,
                )
            }
        }

        // A determinate bar only while bytes are actually moving. Showing one
        // at zero for a queued chunk would imply an upload is underway when
        // nothing has started.
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                color = WitnessOrange,
                trackColor = Separator,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.queue_percent_uploaded, (fraction * 100).toInt()),
                fontSize = 12.sp,
                color = WitnessOrange,
            )
        } else {
            val retryAt = chunk.nextRetryAt
            val retrying = retryAt != null && retryAt > now
            Text(
                text = statusText(chunk, now),
                fontSize = 12.sp,
                color = if (retrying) WitnessOrange else SecondaryLabel,
            )
            // What the last attempt actually said, not just that it's being
            // retried. Disappears the moment this piece actually uploads,
            // since an uploaded record drops out of this list entirely.
            chunk.lastErrorMessage?.let {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = SecondaryLabel,
                    maxLines = 2,
                )
            }
        }
    }
}

/** The one message that can explain every row at once. */
@Composable
private fun RootCauseBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = WitnessOrange,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.queue_root_cause_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(text = message, fontSize = 13.sp, color = SecondaryLabel)
        }
    }
}

@Composable
private fun statusText(chunk: QueuedChunk, now: Long): String {
    val retryAt = chunk.nextRetryAt
    if (retryAt != null && retryAt > now) {
        val seconds = ((retryAt - now) / 1_000).toInt().coerceAtLeast(1)
        // Zero attempts means this chunk was never actually sent: setup failed
        // ahead of it - no account, or a server that would not give us the
        // folder - and it was handed back untried. Calling that a retry reads
        // as the upload being attempted and failing, and sends people looking
        // for a fault in their connection rather than their account.
        if (chunk.attemptCount == 0) {
            return stringResource(R.string.queue_waiting_for_storage, seconds)
        }
        if (chunk.attemptCount == 1) {
            return stringResource(R.string.queue_retrying_one, seconds)
        }
        return stringResource(R.string.queue_retrying, seconds, chunk.attemptCount)
    }
    return when (chunk.state) {
        ChunkState.PENDING_HASH -> stringResource(R.string.queue_state_fingerprinting)
        ChunkState.QUEUED -> stringResource(R.string.queue_state_waiting)
        ChunkState.UPLOADING -> stringResource(R.string.queue_state_starting)
        ChunkState.UPLOADED -> stringResource(R.string.queue_state_uploaded)
    }
}

// MARK: - What already landed

/**
 * The counterpart to the queue above: not what is still waiting, but what is
 * confirmed to be on the user's storage right now.
 *
 * The queue answers "is it moving", which is the anxious question during an
 * encounter. This answers the one that matters afterwards - how much of the
 * recording actually got out - and it stays answerable after the queue has
 * emptied and there is nothing else on this screen to see.
 */
@Composable
private fun DeliveredSection(receipt: SessionUploadReceipt) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Absolute for the recording (which one), relative for the
        // confirmation (how stale). Mixed deliberately: the first is an
        // identity, the second is a freshness check.
        val started = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(receipt.startedAt))
        Text(
            text = stringResource(R.string.queue_recording_of, started),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = SecondaryLabel,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DeliveredRow(
                stringResource(R.string.queue_pieces_delivered),
                receipt.pieces.toString(),
            )
            if (receipt.previews > 0) {
                DeliveredRow(
                    stringResource(R.string.queue_previews_sent),
                    receipt.previews.toString(),
                )
            }
            if (receipt.bytes > 0) {
                DeliveredRow(
                    stringResource(R.string.queue_total),
                    Formatter.formatFileSize(context, receipt.bytes),
                )
            }
            DeliveredRow(
                stringResource(R.string.queue_last_confirmed),
                DateUtils.getRelativeTimeSpanString(
                    receipt.lastConfirmedAt,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
            )
            if (receipt.mergedVideos > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Layers, contentDescription = null,
                        tint = SecondaryLabel, modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = if (receipt.mergedVideos == 1) {
                            stringResource(R.string.queue_combined_one)
                        } else {
                            stringResource(R.string.queue_combined_many, receipt.mergedVideos)
                        },
                        fontSize = 13.sp,
                        color = SecondaryLabel,
                    )
                }
            }
        }

        Text(
            text = if (receipt.mergedVideos > 0) {
                stringResource(R.string.queue_delivered_footer_merged)
            } else {
                stringResource(R.string.queue_delivered_footer)
            },
            fontSize = 12.sp,
            color = SecondaryLabel,
            modifier = Modifier.padding(top = 6.dp, bottom = 32.dp),
        )
    }
}

@Composable
private fun DeliveredRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, fontSize = 15.sp, color = Color.White, modifier = Modifier.weight(1f))
        Text(text = value, fontSize = 15.sp, color = SecondaryLabel)
    }
}

// MARK: - Empty

@Composable
private fun EverythingUploaded() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.CheckCircle, contentDescription = null,
                tint = WitnessGreen, modifier = Modifier.size(40.dp),
            )
            Text(
                text = stringResource(R.string.queue_all_uploaded),
                fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
            )
            Text(
                text = stringResource(R.string.queue_nothing_waiting),
                fontSize = 14.sp, color = SecondaryLabel,
            )
        }
    }
}
