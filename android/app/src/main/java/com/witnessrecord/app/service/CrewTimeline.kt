package com.witnessrecord.app.service

import com.witnessrecord.app.model.CrewAngle
import com.witnessrecord.app.model.CrewEdit

/**
 * Every angle of a crew on one clock, and what the edit shows at each moment.
 * The logic of the iOS build's `CrewPlayerModel`, kept free of any player or
 * screen so it can be tested.
 *
 * At every moment the angle shown and heard is the one cut to, or, where it
 * wasn't filming (or its footage isn't on this phone), one that was.
 *
 * Moments are seconds on the crew's clock; positions are seconds since the
 * earliest angle began ([origin]).
 */
class CrewTimeline(
    val angles: List<CrewAngle>,
    /** Angles whose footage is on this phone. */
    val ready: Set<String>,
    val edit: CrewEdit,
) {
    class Segment(val start: Double, val end: Double, val angleID: String?)

    val origin: Double = angles.minOfOrNull { it.startedAt } ?: 0.0
    val total: Double = maxOf((angles.maxOfOrNull { it.startedAt + it.duration } ?: origin) - origin, 1.0)

    fun isReady(id: String) = id in ready

    fun covers(angle: CrewAngle, moment: Double): Boolean {
        val t = moment - angle.startedAt
        return t >= 0 && t < angle.duration
    }

    /**
     * The angle the edit shows at a moment: the one cut to if it was filming
     * and its footage is here, otherwise the earliest-started one that was.
     * Null when no camera covers it.
     */
    fun effectiveID(moment: Double): String? {
        val usable = angles.filter { isReady(it.id) }
        val chosen = edit.chosenAngle(moment)
        if (chosen != null) {
            val angle = usable.firstOrNull { it.id == chosen }
            if (angle != null && covers(angle, moment)) return chosen
        }
        return usable.filter { covers(it, moment) }.minByOrNull { it.startedAt }?.id
    }

    /**
     * The edit between two timeline positions, as stretches of one angle each
     * (or none), split wherever a cut is or a camera starts or stops.
     */
    fun segments(from: Double, to: Double): List<Segment> {
        if (to <= from) return emptyList()
        val bounds = sortedSetOf(from, to)
        for (cut in edit.cuts) {
            val t = cut.at - origin
            if (t > from && t < to) bounds += t
        }
        for (angle in angles.filter { isReady(it.id) }) {
            val start = angle.startedAt - origin
            for (t in listOf(start, start + angle.duration)) if (t > from && t < to) bounds += t
        }
        val sorted = bounds.toList()
        val result = mutableListOf<Segment>()
        for (i in 0 until sorted.size - 1) {
            val t0 = sorted[i]
            val t1 = sorted[i + 1]
            if (t1 - t0 <= 0.001) continue
            val id = effectiveID(origin + (t0 + t1) / 2)
            val last = result.lastOrNull()
            if (last != null && last.angleID == id) result[result.size - 1] = Segment(last.start, t1, id)
            else result += Segment(t0, t1, id)
        }
        return result
    }

    /** The range an export covers: In to Out when both are set and asked for, else everything. */
    fun exportRange(useInOut: Boolean): Pair<Double, Double> {
        val i = edit.inPoint
        val o = edit.outPoint
        return if (useInOut && edit.hasInOut && i != null && o != null) {
            maxOf(i - origin, 0.0) to minOf(o - origin, total)
        } else 0.0 to total
    }
}
