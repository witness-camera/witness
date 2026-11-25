package com.witnessrecord.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The stop gesture, in one place.
 *
 * Recording is ended by a triple-tap followed by a press-and-hold, and the
 * practice screen drills exactly that - so both screens must recognise it
 * identically or the muscle memory the practice builds would not transfer.
 *
 * Compose has no built-in triple-tap, so taps are counted inside a rolling
 * window. [TRIPLE_TAP_WINDOW_MS] is deliberately generous: a user reaching for
 * this gesture is probably under stress, holding the phone low, and not
 * looking at the screen.
 */
private const val TRIPLE_TAP_WINDOW_MS = 700L

@Composable
fun Modifier.tripleTapGesture(
    key: Any? = Unit,
    onSingleTap: (() -> Unit)? = null,
    onTripleTap: () -> Unit,
): Modifier {
    val taps = remember { mutableListOf<Long>() }

    return this.pointerInput(key) {
        detectTapGestures(
            onTap = {
                val now = System.currentTimeMillis()
                taps.removeAll { now - it > TRIPLE_TAP_WINDOW_MS }
                taps.add(now)
                if (taps.size >= 3) {
                    taps.clear()
                    onTripleTap()
                } else {
                    onSingleTap?.invoke()
                }
            }
        )
    }
}
