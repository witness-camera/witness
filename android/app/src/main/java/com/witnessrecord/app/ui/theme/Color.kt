package com.witnessrecord.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The iOS build draws on system semantic colours over a black background. The
 * values here are those same colours, so the two apps read as one product
 * rather than two ports that drifted apart.
 */

/** iOS `.red` in dark mode. */
val WitnessRed = Color(0xFFFF453A)

/** iOS `.green`, used for confirmed states. */
val WitnessGreen = Color(0xFF30D158)

/** iOS `.orange`, used for warnings that never interrupt recording. */
val WitnessOrange = Color(0xFFFF9F0A)

/** PayPal brand blue (#0070BA), so the donate action reads as what it is. */
val PayPalBlue = Color(0xFF0070BA)

/** Grouped-list surfaces, matching iOS's dark secondary system background. */
val Surface = Color(0xFF1C1C1E)
val SurfaceVariant = Color(0xFF2C2C2E)
val Separator = Color(0xFF38383A)

/** iOS `.secondary` label colour. */
val SecondaryLabel = Color(0x99EBEBF5)
