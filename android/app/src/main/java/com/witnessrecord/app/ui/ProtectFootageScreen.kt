package com.witnessrecord.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PersonAddAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.witnessrecord.app.R
import com.witnessrecord.app.service.storage.StorageProviderID
import com.witnessrecord.app.service.storage.StorageProviders
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.WitnessRed

/**
 * Plain-language explainer of how footage can still be deleted from a seized
 * phone, and the optional steps that guard against it. Guidance only -
 * nothing here is enforced, so users who prefer their everyday account can
 * ignore it.
 *
 * Written per storage backend rather than generically: the actual risk and
 * mitigation differ by account type (an OAuth account reachable through the
 * phone's own apps, versus a WebDAV server with no such phone-level
 * integration and no sharing at all), and naming the wrong app or feature
 * here would be actively misleading rather than merely generic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectFootageScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val selected by StorageProviders.selected.collectAsState()
    val providerName = if (StorageProviders.isImplemented(selected)) {
        stringResource(selected.displayNameRes)
    } else {
        stringResource(R.string.protect_your_cloud_account_fallback)
    }
    val isWebDAV = selected == StorageProviderID.WEBDAV

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                ),
                title = { Text(stringResource(R.string.protect_title), fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                            tint = Color.White,
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
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(stringResource(R.string.protect_what_header))
            Paragraph(stringResource(R.string.protect_what_body))

            if (isWebDAV) {
                SectionHeader(stringResource(R.string.protect_risk_header))
                Paragraph(stringResource(R.string.protect_webdav_risk_body))

                SectionHeader(stringResource(R.string.protect_webdav_mitigation_header))
                Paragraph(stringResource(R.string.protect_webdav_mitigation_body))
            } else {
                SectionHeader(stringResource(R.string.protect_risk_header))
                Paragraph(stringResource(R.string.protect_generic_risk_body, providerName, providerName))
                Paragraph(stringResource(R.string.protect_generic_sharing_note))

                SectionHeader(stringResource(R.string.protect_strongest_header))
                LabelRow(Icons.Filled.PersonAddAlt, stringResource(R.string.protect_dedicated_account_title))
                Paragraph(stringResource(R.string.protect_generic_dedicated_account_body, providerName))

                SectionHeader(stringResource(R.string.protect_backstop_header))
                LabelRow(Icons.Filled.ContentCopy, stringResource(R.string.protect_own_copy))
                Paragraph(stringResource(R.string.protect_own_copy_generic_body))
            }

            Text(
                text = stringResource(R.string.protect_optional_footer_generic),
                fontSize = 13.sp,
                color = SecondaryLabel,
                modifier = Modifier.padding(top = 12.dp, bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = SecondaryLabel,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Paragraph(text: String) {
    Text(text = text, fontSize = 16.sp, color = Color.White.copy(alpha = 0.9f))
}

@Composable
private fun LabelRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = WitnessRed, modifier = Modifier.size(20.dp))
        Text(text = text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}
