package com.witnessrecord.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.witnessrecord.app.ui.theme.WitnessRed

/**
 * The white capsule that carries every forward action in the onboarding and
 * practice flows - the Compose counterpart of the iOS build's
 * `HeroPillButtonStyle`, including its press spring.
 */
@Composable
fun HeroPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "hero-pill-scale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .alpha(if (!enabled) 0.5f else if (pressed) 0.88f else 1f)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A tappable translucent card - the shape every actionable row in onboarding
 * uses (permission rows, sign-in, contact fields).
 */
@Composable
fun BounceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "bounce-card-scale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .alpha(if (pressed && onClick != null) 0.8f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        content()
    }
}

/**
 * Full-bleed ambient backdrop: a soft blurred red glow that drifts to a
 * different corner per page, over near-black, with a faint diagonal sheen.
 *
 * Drawn with radial and linear gradient brushes rather than a blurred layer -
 * a real blur would cost a render-pass on every frame of a paging animation,
 * and a wide, soft radial gradient is visually indistinguishable here.
 */
@Composable
fun AmbientBackground(
    modifier: Modifier = Modifier,
    page: Int = 0,
) {
    val offset = when (page % 3) {
        0 -> Offset(-90f, -180f)
        1 -> Offset(110f, -40f)
        else -> Offset(-50f, 140f)
    }

    val glowX by animateFloatAsState(
        targetValue = offset.x,
        animationSpec = spring(stiffness = 60f),
        label = "glow-x",
    )
    val glowY by animateFloatAsState(
        targetValue = offset.y,
        animationSpec = spring(stiffness = 60f),
        label = "glow-y",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .drawBehind {
                val center = Offset(
                    x = size.width / 2f + glowX,
                    y = size.height / 2f + glowY,
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            WitnessRed.copy(alpha = 0.36f),
                            WitnessRed.copy(alpha = 0.05f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = size.minDimension * 1.1f,
                    )
                )
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.03f),
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                )
            }
    )
}
