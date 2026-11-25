package com.witnessrecord.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.witnessrecord.app.R
import com.witnessrecord.app.ui.theme.WitnessGreen
import com.witnessrecord.app.ui.theme.WitnessOrange
import com.witnessrecord.app.ui.theme.WitnessRed

/**
 * One-time, hands-on walkthrough of the exact gesture used to stop a real
 * recording - triple-tap, then press-and-hold - shown once right after
 * onboarding, before it is ever needed for real.
 *
 * It reproduces the real black screen and reuses the same recognisers (see
 * [tripleTapGesture], same 1-second hold) so the muscle memory actually
 * transfers. Nothing here is a real recording: no camera, no capture, no
 * upload - just the gesture.
 */
@Composable
fun PracticeRecordingScreen(onFinished: () -> Unit) {
    var step by remember { mutableStateOf(Step.INTRO) }
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .tripleTapGesture(key = step) {
                if (step == Step.WAITING_FOR_TRIPLE_TAP) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    step = Step.WAITING_FOR_HOLD
                }
            }
    ) {
        // Bars are drawn over by default from Android 15; the card content
        // and its buttons must stay clear of them.
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
        when (step) {
            Step.INTRO -> IntroCard(
                onStart = { step = Step.WAITING_FOR_TRIPLE_TAP },
                onSkip = onFinished,
            )
            Step.WAITING_FOR_TRIPLE_TAP -> TripleTapCoaching()
            Step.WAITING_FOR_HOLD -> HoldCoaching(
                onHeld = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    step = Step.SUCCESS
                }
            )
            Step.SUCCESS -> SuccessCard(onContinue = onFinished)
        }
        }
    }
}

private enum class Step { INTRO, WAITING_FOR_TRIPLE_TAP, WAITING_FOR_HOLD, SUCCESS }

@Composable
private fun IntroCard(onStart: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.TouchApp,
            contentDescription = null,
            tint = WitnessRed,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(R.string.practice_intro_title),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            text = stringResource(R.string.practice_intro_body),
            fontSize = 17.sp,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 36.dp),
        )
        Spacer(Modifier.weight(1f))

        HeroPillButton(
            text = stringResource(R.string.practice_start),
            onClick = onStart,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        Text(
            text = stringResource(R.string.practice_skip),
            fontSize = 15.sp,
            color = Color.White.copy(alpha = 0.4f),
            modifier = Modifier
                .clickable { onSkip() }
                .padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun TripleTapCoaching() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.weight(1f))
        PulsingTarget(ringSize = 120.dp) {
            Icon(
                Icons.Filled.TouchApp,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(34.dp),
            )
        }
        Text(
            text = stringResource(R.string.practice_this_is_recording),
            fontSize = 15.sp,
            color = Color.White.copy(alpha = 0.4f),
        )
        Text(
            text = stringResource(R.string.practice_triple_tap_prompt),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
        Spacer(Modifier.weight(2f))
    }
}

@Composable
private fun HoldCoaching(onHeld: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Circle,
                    contentDescription = null,
                    tint = WitnessOrange,
                    modifier = Modifier.size(10.dp),
                )
                Text(
                    text = stringResource(R.string.practice_badge),
                    color = WitnessOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.practice_hold_prompt),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 30.dp),
            )

            PulsingTarget(ringSize = 90.dp) {
                Icon(
                    Icons.Filled.StopCircle,
                    contentDescription = stringResource(R.string.recording_stop),
                    tint = WitnessRed.copy(alpha = 0.9f),
                    modifier = Modifier
                        .size(56.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(onLongPress = { onHeld() })
                        },
                )
            }
        }
    }
}

@Composable
private fun SuccessCard(onContinue: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = WitnessGreen,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = stringResource(R.string.practice_success_title),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            text = stringResource(R.string.practice_success_body),
            fontSize = 17.sp,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 36.dp),
        )
        Spacer(Modifier.weight(1f))
        HeroPillButton(
            text = stringResource(R.string.practice_continue),
            onClick = onContinue,
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .padding(bottom = 24.dp),
        )
    }
}

/** The expanding ring that marks where to tap or hold. */
@Composable
private fun PulsingTarget(
    ringSize: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_300),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse-progress",
    )

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(ringSize)
                .scale(0.9f + progress * 0.35f)
                .alpha((1f - progress) * 0.8f)
                .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
        )
        content()
    }
}
