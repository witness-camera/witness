package com.witnessrecord.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.witnessrecord.app.R
import com.witnessrecord.app.model.CaseItem
import com.witnessrecord.app.service.CaseLibrary
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.Surface
import com.witnessrecord.app.ui.theme.WitnessGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A grouped block in the app's usual dark list style, with an optional header and footer. */
@Composable
internal fun CaseSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
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
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) { content() }
        footer?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                color = SecondaryLabel,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}

/** The kind's plain name: "Recording", "Photo", "Audio recording", "Imported file". */
@Composable
internal fun kindName(item: CaseItem): String = stringResource(kindNameRes(item.kind))

internal fun kindNameRes(kind: String): Int = when (kind) {
    CaseItem.KIND_VIDEO -> R.string.item_recording
    CaseItem.KIND_PHOTO -> R.string.item_photo
    CaseItem.KIND_AUDIO -> R.string.item_audio
    else -> R.string.item_imported_file
}

/** The item's own name, or "Recording · Oct 12, 3:04 PM" when it has none. */
@Composable
internal fun itemTitle(item: CaseItem): String {
    item.title?.takeIf { it.isNotEmpty() }?.let { return it }
    return "${kindName(item)} · ${shortStamp(item.createdAt)}"
}

internal fun shortStamp(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        .format(Date(millis))

internal fun longStamp(millis: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG, java.text.DateFormat.MEDIUM)
        .format(Date(millis))

internal fun dateOnly(millis: Long): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(Date(millis))

/** The plain "0:42" or "1:02:03" clock for a length in seconds. */
internal fun clock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) {
        "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    } else {
        "%d:%02d".format(s / 60, s % 60)
    }
}

internal fun isoStamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(millis))

/** One item in a list: thumbnail, name, and its proof badges. */
@Composable
internal fun ItemRow(item: CaseItem, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ItemThumbnail(item, size = 56.dp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = itemTitle(item),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.timestamped) Badge(Icons.Filled.VerifiedUser, stringResource(R.string.item_time_stamped), WitnessGreen)
                if (item.isImported) Badge(Icons.Filled.SaveAlt, stringResource(R.string.item_imported), SecondaryLabel)
            }
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Text(label, fontSize = 11.sp, color = tint)
    }
}

/** The item's thumbnail, or an icon for its kind where there is none. */
@Composable
internal fun ItemThumbnail(item: CaseItem, size: Dp, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, item.id, item.timestamped, item.uploaded, item.fileName) {
        value = withContext(Dispatchers.IO) { loadBitmap(CaseLibrary.thumbnailFile(item.id)) }
    }
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Color(0xFF262626)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            if (item.isVideo) {
                Box(
                    modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(5.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        } else {
            Icon(
                imageVector = when (item.kind) {
                    CaseItem.KIND_VIDEO -> Icons.Filled.Videocam
                    CaseItem.KIND_PHOTO -> Icons.Filled.Image
                    CaseItem.KIND_AUDIO -> Icons.Filled.GraphicEq
                    else -> Icons.Filled.Description
                },
                contentDescription = null, tint = SecondaryLabel, modifier = Modifier.size(22.dp),
            )
        }
    }
}

internal fun loadBitmap(file: File): ImageBitmap? =
    if (file.exists()) runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull() else null
