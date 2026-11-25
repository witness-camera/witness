package com.witnessrecord.app.model

import com.witnessrecord.app.service.Prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A cut of a crew's angles into one video, made on the phone: which camera is
 * on from which moment, and an optional In and Out. The iOS build's `CrewEdit`.
 *
 * Times are moments on the crew's clock (seconds since 1970) rather than
 * offsets into the timeline, because the timeline's start moves when an angle
 * that began earlier arrives later; the cut stays where it was put. Kept per
 * crew on this phone only.
 */
@Serializable
data class CrewEdit(
    val cuts: List<Cut> = emptyList(),
    val inPoint: Double? = null,
    val outPoint: Double? = null,
) {
    @Serializable
    data class Cut(val at: Double, val angleID: String)

    val sortedCuts: List<Cut> get() = cuts.sortedBy { it.at }

    val hasInOut: Boolean get() = inPoint != null && outPoint != null && outPoint > inPoint

    /** The camera chosen for a moment: the last cut at or before it, or the
     *  first cut's camera before any cut. */
    fun chosenAngle(at: Double): String? {
        val sorted = sortedCuts
        return (sorted.lastOrNull { it.at <= at } ?: sorted.firstOrNull())?.angleID
    }

    fun save(crewID: String) {
        Prefs.setString(key(crewID), json.encodeToString(serializer(), this))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private fun key(crewID: String) = "crew.edit.$crewID"

        fun load(crewID: String): CrewEdit {
            val raw = Prefs.getString(key(crewID)) ?: return CrewEdit()
            return runCatching { json.decodeFromString(serializer(), raw) }.getOrDefault(CrewEdit())
        }
    }
}
