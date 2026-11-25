package com.witnessrecord.app.capture

import android.hardware.SensorManager
import android.view.OrientationEventListener
import com.witnessrecord.app.WitnessApplication

/**
 * How the phone is being held, to the nearest quarter turn.
 *
 * Recording used to be wired at the sensor's own orientation and nothing
 * else - always portrait, whatever the phone was doing - so filming with the
 * phone held sideways produced footage lying on its side. Most obvious on the
 * front camera, where the subject is a face, but it applied to both.
 *
 * Read from the accelerometer rather than from the window's display rotation:
 * the activity is locked to portrait, so the display rotation is *always*
 * portrait and describes the window rather than the phone. The accelerometer
 * is what the stock camera app uses for the same reason, and it keeps working
 * with the interface pinned where this app wants it.
 *
 * [snapshot] is null when there is no answer - flat on a table, face down,
 * still settling. Those are ordinary positions for this app (on a dashboard,
 * in a pocket, tossed on a seat) and there is no horizon to level against in
 * any of them, so callers fall back to portrait, which is both what the app
 * did unconditionally before and what nearly everyone films in.
 */
object DeviceOrientation {

    /**
     * Nearest quarter turn clockwise from the phone's natural orientation, or
     * null if the accelerometer cannot say.
     */
    @Volatile
    var snapshot: Int? = null
        private set

    private var listener: OrientationEventListener? = null

    /**
     * Reference-counted, because the activity and the recording service both
     * want this observed and neither knows about the other. The listener is
     * an accelerometer subscription, so it is not left running for the life of
     * the process just because starting it is easy.
     */
    private var observers = 0

    @Synchronized
    fun beginObserving() {
        observers += 1
        if (listener != null) return
        val created = object : OrientationEventListener(
            WitnessApplication.appContext, SensorManager.SENSOR_DELAY_NORMAL
        ) {
            override fun onOrientationChanged(orientation: Int) {
                snapshot = if (orientation == ORIENTATION_UNKNOWN) {
                    // Lying flat: the phone has an orientation, but not one
                    // that says which way up the scene is.
                    null
                } else {
                    // Snapped to a quarter turn. Anything finer would only
                    // mean a tilted phone recorded at a rounded-off angle.
                    ((orientation + 45) / 90 * 90) % 360
                }
            }
        }
        if (created.canDetectOrientation()) {
            created.enable()
            listener = created
        }
    }

    @Synchronized
    fun endObserving() {
        observers = (observers - 1).coerceAtLeast(0)
        if (observers > 0) return
        listener?.disable()
        listener = null
        // Deliberately not cleared: a recording that starts before the
        // accelerometer has reported again is better served by where the phone
        // last was than by portrait-by-default.
    }

    /**
     * The container rotation that makes footage from this camera play back the
     * right way up, given how the phone is being held.
     *
     * This is the mapping Android documents for `setOrientationHint`, used
     * as-is rather than derived by hand: the sense of the rotation inverts
     * between the front and rear sensors, and getting that backwards gives
     * upside-down video, which is worse than sideways video.
     *
     * Written into the container, never baked into the pixels - so it costs
     * nothing and is losslessly carried through the session merge.
     */
    fun orientationHint(sensorOrientation: Int, isFront: Boolean): Int {
        val held = snapshot ?: 0
        return if (isFront) {
            (sensorOrientation - held + 360) % 360
        } else {
            (sensorOrientation + held) % 360
        }
    }

    /** How the phone is being held, for the diagnostics page. */
    fun heldDescription(): String = when (snapshot) {
        0 -> "upright"
        90 -> "on its left side"
        180 -> "upside down"
        270 -> "on its right side"
        else -> "flat or unknown"
    }
}
