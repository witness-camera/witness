package com.witnessrecord.app.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Formats and draws the visible timestamp/GPS overlay text - the Android
 * counterpart of the iOS build's `TimestampOverlayRenderer`.
 *
 * Kept separate from the GL plumbing in [CameraOverlayRenderer]: this is
 * plain Canvas drawing into a [Bitmap], which [CameraOverlayRenderer] then
 * uploads as a GL texture. Neither side needs to know about the other's
 * concerns.
 */
object TimestampOverlayText {

    private val localFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val utcFormatter = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Formats the on-screen text the way a dashcam's own overlay reads:
     * absolute local time first (what anyone glancing at the video wants),
     * UTC alongside it (what a chain-of-custody comparison actually needs),
     * and coordinates only when a location is actually available - an
     * overlay claiming a location it doesn't have would undermine the exact
     * provenance this feature exists to show.
     *
     * Two lines, not one: [CameraOverlayRenderer] has to keep this bar's
     * rendered size safe under any rotation the phone might be held at (see
     * its own doc comment), which bounds how wide a single-line bar can be
     * before it starts shrinking to fit. A single line long enough to carry
     * a full timestamp plus a GPS coordinate shrank far enough to become
     * hard to read; splitting it in two keeps each line short enough that
     * the bar stays a normal, legible size instead.
     */
    fun lines(date: Date, location: Location?, brandLine: String? = null): List<String> {
        val local = localFormatter.format(date)
        val utc = utcFormatter.format(date)
        val body = if (location == null) {
            listOf(local, "$utc UTC")
        } else {
            val lat = String.format(Locale.US, "%.5f°%s", Math.abs(location.latitude), if (location.latitude >= 0) "N" else "S")
            val lon = String.format(Locale.US, "%.5f°%s", Math.abs(location.longitude), if (location.longitude >= 0) "E" else "W")
            listOf(local, "$utc UTC  ·  $lat $lon")
        }
        // The tag a free recording carries; Pro leaves only time and place.
        return if (brandLine != null) body + brandLine else body
    }

    /**
     * A stable key for [lines]' output, so [CameraOverlayRenderer] can tell
     * "the text changed, rebuild the texture" apart from "still the same
     * second" without comparing lists.
     */
    fun key(lines: List<String>): String = lines.joinToString("\n")

    /**
     * Draws [lines] white on a translucent black backing bar, sized relative
     * to [refHeight] (the recording's own frame height) so it reads the same
     * way at 1080p and at 4K rather than becoming illegibly small or
     * comically large as resolution changes - matching the iOS build's own
     * sizing rule.
     */
    fun render(lines: List<String>, refHeight: Int): Bitmap {
        val fontSize = (refHeight * 0.032f).coerceAtLeast(26f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = fontSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val metrics = paint.fontMetrics
        val lineHeight = metrics.descent - metrics.ascent
        val lineSpacing = fontSize * 0.25f
        val textWidth = lines.maxOf { paint.measureText(it) }
        val textHeight = lineHeight * lines.size + lineSpacing * (lines.size - 1)

        val padding = fontSize * 0.4f
        val width = (textWidth + padding * 2).coerceAtLeast(1f)
        val height = (textHeight + padding * 2).coerceAtLeast(1f)

        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(115, 0, 0, 0)
        }
        canvas.drawRoundRect(RectF(0f, 0f, width, height), padding, padding, barPaint)

        var baseline = padding - metrics.ascent
        for (line in lines) {
            canvas.drawText(line, padding, baseline, paint)
            baseline += lineHeight + lineSpacing
        }

        return bitmap
    }
}
