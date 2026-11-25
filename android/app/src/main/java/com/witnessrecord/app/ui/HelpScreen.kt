package com.witnessrecord.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.witnessrecord.app.R
import com.witnessrecord.app.ui.theme.SecondaryLabel
import com.witnessrecord.app.ui.theme.Surface
import com.witnessrecord.app.ui.theme.WitnessGreen
import com.witnessrecord.app.ui.theme.WitnessOrange
import com.witnessrecord.app.ui.theme.WitnessRed

private const val SUPPORT_EMAIL = "athingby@gmail.com"

/**
 * Answers for somebody who thinks they have lost a recording.
 *
 * Written for the worst moment to be reading it: an encounter has just ended,
 * the footage is not where they expected, and they are deciding whether it is
 * gone. So the reassurance that nothing is deleted comes first, before any
 * question, and every answer names the exact switch or screen to look at
 * rather than describing the mechanism in general terms.
 *
 * Diverges from the iOS build's copy in one deliberate place: iOS can point
 * someone at the Files app to find still-queued chunks directly, because
 * they are written to a location the OS exposes. Android's staging directory
 * is app-private storage with no Files-app equivalent - there is genuinely
 * nowhere on-device to browse to for an unclaimed chunk here, so this says
 * so rather than pointing at a folder that doesn't exist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onDismiss: () -> Unit) {
    BackHandler { onDismiss() }

    var showProtectFootage by remember { mutableStateOf(false) }
    if (showProtectFootage) {
        ProtectFootageScreen(onBack = { showProtectFootage = false })
        return
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                title = { Text(stringResource(R.string.help_title), fontSize = 18.sp) },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_done), tint = Color.White)
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
            HelpSection {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Shield, contentDescription = null, tint = WitnessGreen, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                    Text(
                        text = stringResource(R.string.help_no_delete_button),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.help_no_delete_body), fontSize = 14.sp, color = SecondaryLabel)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.help_rarely_lost), fontSize = 14.sp, color = SecondaryLabel)
            }

            // Always visible, not collapsed like the FAQ below: this is the
            // single scenario people have actually lost sleep over.
            SectionHeader(stringResource(R.string.help_section_just_lost))
            HelpSection {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = WitnessOrange, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                    Text(
                        text = stringResource(R.string.help_closed_before_stop_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
                Spacer(Modifier.size(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AnswerText(stringResource(R.string.help_closed_before_stop_1))
                    AnswerText(stringResource(R.string.help_closed_before_stop_2))
                    AnswerText(stringResource(R.string.help_closed_before_stop_3))
                }
            }

            SectionHeader(stringResource(R.string.help_section_where))
            HelpSection {
                DestinationRow(Icons.Filled.CloudQueue, stringResource(R.string.help_dest_cloud_title), stringResource(R.string.help_dest_cloud_body))
                Spacer(Modifier.size(14.dp))
                DestinationRow(Icons.Filled.PhotoLibrary, stringResource(R.string.help_dest_gallery_title), stringResource(R.string.help_dest_gallery_body))
            }

            SectionHeader(stringResource(R.string.help_section_questions))
            HelpSection {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HelpQuestion(stringResource(R.string.help_q_not_in_gallery)) {
                        AnswerText(stringResource(R.string.help_a_not_in_gallery_1))
                        AnswerText(stringResource(R.string.help_a_not_in_gallery_2))
                    }
                    HelpDivider()
                    HelpQuestion(stringResource(R.string.help_q_not_in_cloud)) {
                        AnswerText(stringResource(R.string.help_a_not_in_cloud_1))
                        AnswerText(stringResource(R.string.help_a_not_in_cloud_2))
                        AnswerText(stringResource(R.string.help_a_not_in_cloud_3))
                    }
                    HelpDivider()
                    HelpQuestion(stringResource(R.string.help_q_taken_mid_recording)) {
                        AnswerText(stringResource(R.string.help_a_taken_mid_recording_1))
                        AnswerText(stringResource(R.string.help_a_taken_mid_recording_2))
                    }
                    HelpDivider()
                    HelpQuestion(stringResource(R.string.help_q_pieces)) {
                        AnswerText(stringResource(R.string.help_a_pieces_1))
                        AnswerText(stringResource(R.string.help_a_pieces_2))
                    }
                    HelpDivider()
                    HelpQuestion(stringResource(R.string.help_q_stuck_in_queue)) {
                        AnswerText(stringResource(R.string.help_a_stuck_in_queue))
                    }
                    HelpDivider()
                    HelpQuestion(stringResource(R.string.help_crew_q)) {
                        AnswerText(stringResource(R.string.help_crew_a1))
                        AnswerText(stringResource(R.string.help_crew_a2))
                        AnswerText(stringResource(R.string.help_crew_a3))
                    }
                    HelpDivider()
                    HelpQuestion(stringResource(R.string.help_q_who_can_delete)) {
                        AnswerText(stringResource(R.string.help_a_who_can_delete_1))
                        AnswerText(stringResource(R.string.help_a_who_can_delete_2))
                        Spacer(Modifier.size(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showProtectFootage = true }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = WitnessRed, modifier = Modifier.size(18.dp))
                            Text(
                                text = stringResource(R.string.settings_protect_footage),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = WitnessRed,
                            )
                        }
                    }
                }
            }

            SectionHeader(stringResource(R.string.help_section_still_cant_find))
            HelpSection {
                val uriHandler = LocalUriHandler.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri("mailto:$SUPPORT_EMAIL") },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = WitnessRed, modifier = Modifier.size(18.dp))
                    Text(text = stringResource(R.string.help_email_us), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.help_email_hint), fontSize = 12.sp, color = SecondaryLabel)
            }
            Text(
                text = stringResource(R.string.help_footer),
                fontSize = 12.sp,
                color = SecondaryLabel,
                modifier = Modifier.padding(top = 6.dp, bottom = 32.dp),
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
        modifier = Modifier.padding(top = 24.dp, start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun HelpSection(content: @Composable ColumnScopeMarker.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        ColumnScopeMarker.content()
    }
}

@Composable
private fun DestinationRow(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = WitnessRed, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Column {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.size(3.dp))
            Text(text = body, fontSize = 13.sp, color = SecondaryLabel)
        }
    }
}

/**
 * One collapsed question, collapsed by default: someone looking for their
 * footage needs to find their own question quickly, not read the other six.
 */
@Composable
private fun HelpQuestion(title: String, content: @Composable ColumnScopeMarker.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = SecondaryLabel,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ColumnScopeMarker.content()
            }
        }
    }
}

@Composable
private fun HelpDivider() {
    androidx.compose.material3.HorizontalDivider(color = com.witnessrecord.app.ui.theme.Separator)
}

@Composable
private fun AnswerText(text: String) {
    Text(text = text, fontSize = 13.sp, color = SecondaryLabel)
}
