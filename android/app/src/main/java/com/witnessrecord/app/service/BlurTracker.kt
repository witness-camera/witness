package com.witnessrecord.app.service

import kotlin.math.max

/**
 * Keeps faces and plates covered steadily from frame to frame. Detection finds
 * them only every few frames and now and then misses one; this carries each
 * box over in between so the blur never flickers or lets a face show through a
 * missed detection.
 *
 * Each face or plate keeps its box for a while after it was last detected (a
 * missed frame never exposes it), boxes glide toward new detections rather
 * than jumping, and they grow at once but shrink only gradually. Each box also
 * has a speed, so between detections - and after a miss - it keeps moving with
 * its subject instead of trailing behind it.
 *
 * Pure geometry on plain numbers, no Android types: the same logic as the iOS
 * build's `DetectionState`, in a form that can be tested on its own.
 */
class BlurTracker(private val frameWidth: Float, private val frameHeight: Float) {

    /** A box by centre and size, in the same pixel space the detections are in. */
    data class Box(val cx: Float, val cy: Float, val width: Float, val height: Float)

    /** One region to hide: [round] for a face (an ellipse), not for a plate (a rounded rectangle). */
    data class Region(val box: Box, val round: Boolean)

    private class Track(
        var box: Box, val round: Boolean, var lastSeen: Int, var vx: Float, var vy: Float,
    )

    private val tracks = mutableListOf<Track>()
    private var frame = 0

    /**
     * Folds this frame's detections ([found], possibly empty when detection
     * did not run) into the tracks and returns what to cover right now.
     */
    fun update(found: List<Region>): List<Region> {
        val n = frame++
        for (region in found) {
            val match = tracks.firstOrNull {
                it.round == region.round && sameThing(predicted(it, n), region.box)
            }
            if (match != null) {
                val frames = max(1, n - match.lastSeen).toFloat()
                val rawVx = (region.box.cx - match.box.cx) / frames
                val rawVy = (region.box.cy - match.box.cy) / frames
                match.vx = match.vx * 0.5f + rawVx * 0.5f
                match.vy = match.vy * 0.5f + rawVy * 0.5f
                match.box = follow(match.box, region.box)
                match.lastSeen = n
            } else {
                tracks.add(Track(region.box, region.round, n, 0f, 0f))
            }
        }
        tracks.removeAll { n - it.lastSeen > HOLD }

        return tracks.map { track ->
            val moved = predicted(track, n)
            val age = (n - track.lastSeen).toFloat()
            // A little extra margin the longer it goes unconfirmed.
            Region(
                Box(
                    moved.cx, moved.cy,
                    moved.width * (1f + 0.04f * age),
                    moved.height * (1f + 0.04f * age),
                ),
                track.round,
            )
        }
    }

    private fun predicted(track: Track, n: Int): Box {
        val frames = (n - track.lastSeen).toFloat()
        return track.box.copy(cx = track.box.cx + track.vx * frames, cy = track.box.cy + track.vy * frames)
    }

    /** Same face or plate if the centres are within about one box size. */
    private fun sameThing(a: Box, b: Box): Boolean {
        val dx = a.cx - b.cx
        val dy = a.cy - b.cy
        val reach = maxOf(a.width, a.height, b.width, b.height) * 0.8f
        return dx * dx + dy * dy < reach * reach
    }

    /**
     * The centre snaps nearly onto the new detection; the size grows at once
     * but shrinks slowly, so the cover never suddenly gets smaller.
     */
    private fun follow(old: Box, new: Box): Box {
        val cx = old.cx + (new.cx - old.cx) * 0.9f
        val cy = old.cy + (new.cy - old.cy) * 0.9f
        val w = if (new.width >= old.width) new.width else old.width + (new.width - old.width) * 0.15f
        val h = if (new.height >= old.height) new.height else old.height + (new.height - old.height) * 0.15f
        return Box(cx, cy, w, h)
    }

    companion object {
        /** About half a second at 30 fps. */
        const val HOLD = 15

        /**
         * Plate-shaped text: 4-10 letters and digits once spaces and dashes are
         * dropped, with at least one of each. Keeps shop signs and street names
         * from being blurred while catching EU, UK and US formats alike.
         */
        fun looksLikePlate(text: String): Boolean {
            val core = text.uppercase().filter { it !in " -·.•" }
            if (core.length !in 4..10) return false
            if (!core.all { it.code < 128 && (it.isLetter() || it.isDigit()) }) return false
            return core.any { it.isDigit() } && core.any { it.isLetter() }
        }
    }
}
