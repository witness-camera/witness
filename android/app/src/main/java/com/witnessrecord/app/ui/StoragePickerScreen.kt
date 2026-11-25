package com.witnessrecord.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.witnessrecord.app.R
import com.witnessrecord.app.service.storage.StorageProviderID
import com.witnessrecord.app.service.storage.StorageProviders
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.WitnessOrange
import com.witnessrecord.app.ui.theme.WitnessRed

/**
 * Where footage gets uploaded: shown when connecting an account, in the same
 * shape as choosing a mail provider during setup.
 *
 * Presented before any sign-in, so the decision is made once and knowingly.
 * Switching later doesn't move footage already uploaded - each account keeps
 * whatever it received - and the page says so rather than leaving the user to
 * discover it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoragePickerScreen(onDismiss: () -> Unit, onSelect: (StorageProviderID) -> Unit) {
    BackHandler { onDismiss() }
    val selected by StorageProviders.selected.collectAsState()
    var unsupportedExplanation by remember { mutableStateOf<StorageProviderID?>(null) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                title = { Text(stringResource(R.string.storage_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel), tint = Color.White)
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
            Text(
                text = stringResource(R.string.storage_pick_header).uppercase(),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = SecondaryLabel,
                modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
            )
            StorageProviderID.entries.forEach { provider ->
                ProviderRow(
                    provider = provider,
                    selected = selected == provider,
                    onClick = {
                        if (StorageProviders.isUnsupported(provider)) {
                            unsupportedExplanation = provider
                        } else if (StorageProviders.isImplemented(provider)) {
                            StorageProviders.select(provider)
                            // Closes straight away, as the iOS picker does:
                            // the caller connects the chosen provider from
                            // its own onDismiss, so leaving the sheet open
                            // left the sign-in waiting for a manual close.
                            onSelect(provider)
                            onDismiss()
                        }
                    },
                )
            }
            Text(
                text = stringResource(R.string.storage_pick_footer),
                fontSize = 13.sp,
                color = SecondaryLabel,
                modifier = Modifier.padding(top = 6.dp, bottom = 32.dp),
            )
        }
    }

    unsupportedExplanation?.let { provider ->
        AlertDialog(
            onDismissRequest = { unsupportedExplanation = null },
            confirmButton = {
                TextButton(onClick = { unsupportedExplanation = null }) { Text("OK") }
            },
            title = { Text(stringResource(R.string.storage_proton_unsupported_title)) },
            text = { Text(stringResource(R.string.storage_proton_unsupported_message)) },
        )
    }
}

@Composable
private fun ProviderRow(provider: StorageProviderID, selected: Boolean, onClick: () -> Unit) {
    val implemented = StorageProviders.isImplemented(provider)
    val unsupported = StorageProviders.isUnsupported(provider)
    val tappable = implemented || unsupported

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = tappable, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (provider.iconRes != null) {
                androidx.compose.foundation.Image(
                    painter = painterResource(provider.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.White.copy(alpha = 0.1f))
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(provider.displayNameRes),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (tappable) Color.White else SecondaryLabel,
            )
            Text(
                text = if (tappable) stringResource(provider.subtitleRes) else stringResource(R.string.storage_coming_soon),
                fontSize = 12.sp,
                color = if (unsupported) WitnessOrange else SecondaryLabel,
            )
        }
        when {
            selected && implemented -> Icon(Icons.Filled.Check, contentDescription = null, tint = WitnessRed, modifier = Modifier.size(16.dp))
            unsupported -> Icon(Icons.Filled.Info, contentDescription = null, tint = SecondaryLabel, modifier = Modifier.size(16.dp))
        }
    }
}
