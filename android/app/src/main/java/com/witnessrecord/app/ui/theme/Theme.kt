package com.witnessrecord.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app is dark-only, exactly like the iOS build (`UIUserInterfaceStyle:
 * Dark`, `.preferredColorScheme(.dark)`). That is a product decision, not a
 * styling one: every screen here is designed around a black backdrop that a
 * recording can fade into without a visible transition, and a light theme
 * would make the blackout screen conspicuous.
 */
private val WitnessColorScheme = darkColorScheme(
    primary = WitnessRed,
    onPrimary = Color.White,
    secondary = WitnessRed,
    onSecondary = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Surface,
    onSurface = Color.White,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = SecondaryLabel,
    error = WitnessRed,
    onError = Color.White,
    outline = Separator,
)

@Composable
fun WitnessTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = WitnessColorScheme,
        typography = WitnessTypography,
        content = content,
    )
}
