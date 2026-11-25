package com.witnessrecord.app.service

import com.witnessrecord.app.model.CrewAngle
import com.witnessrecord.app.model.CrewEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrewTimelineTest {
    // A films 1000..1060. B films 1030..1090. C films 1100..1110 (after both).
    private fun angle(id: String, start: Double, duration: Double) = CrewAngle(
        sessionID = id, memberID = "M-$id", camera = "back", startedAt = start, duration = duration, fileName = "f.mp4",
    )
    private val a = angle("A", 1000.0, 60.0)
    private val b = angle("B", 1030.0, 60.0)
    private val c = angle("C", 1100.0, 10.0)
    private val angles = listOf(b, c, a) // deliberately out of order
    private val all = setOf(a.id, b.id, c.id)

    private fun timeline(edit: CrewEdit = CrewEdit(), ready: Set<String> = all) = CrewTimeline(angles, ready, edit)

    @Test fun theTimelineRunsFromTheEarliestStartToTheLatestEnd() {
        val t = timeline()
        assertEquals(1000.0, t.origin, 0.0)
        assertEquals(110.0, t.total, 0.0)
    }

    @Test fun withNoCutsTheEarliestStartedCameraThatWasFilmingShows() {
        val t = timeline()
        assertEquals(a.id, t.effectiveID(1010.0))
        assertEquals("both film: the earlier-started one", a.id, t.effectiveID(1040.0))
        assertEquals("A is done, B carries on", b.id, t.effectiveID(1070.0))
    }

    @Test fun aGapNobodyCoveredHasNoCamera() {
        assertNull(timeline().effectiveID(1095.0))
    }

    @Test fun aCutChoosesTheCameraFromThenOn() {
        val t = timeline(CrewEdit(cuts = listOf(CrewEdit.Cut(1035.0, b.id))))
        assertEquals("before the first cut, the first cut's camera is used if it was filming", a.id, t.effectiveID(1010.0))
        assertEquals(b.id, t.effectiveID(1040.0))
    }

    @Test fun theChosenCameraFallsBackWhenItWasNotFilming() {
        // Cut to C early on: C wasn't filming then, so the edit falls back to a camera that was.
        val t = timeline(CrewEdit(cuts = listOf(CrewEdit.Cut(1000.0, c.id))))
        assertEquals(a.id, t.effectiveID(1010.0))
        assertEquals(c.id, t.effectiveID(1105.0))
    }

    @Test fun footageNotOnThePhoneIsNeverShown() {
        val t = timeline(ready = setOf(b.id))
        assertNull(t.effectiveID(1010.0))
        assertEquals(b.id, t.effectiveID(1040.0))
    }

    @Test fun segmentsSplitWhereACutIsOrACameraStartsOrStops() {
        val edit = CrewEdit(cuts = listOf(CrewEdit.Cut(1045.0, b.id)))
        val s = timeline(edit).segments(0.0, 110.0)
        // Before its first cut the edit uses the first cut's camera (B) wherever B was filming, as on iOS:
        // A alone 0-30, B from 30 to 90, nobody 90-100, C 100-110.
        assertEquals(
            listOf(a.id, b.id, null, c.id),
            s.map { it.angleID },
        )
        assertEquals(listOf(0.0, 30.0, 90.0, 100.0), s.map { it.start })
        assertEquals(listOf(30.0, 90.0, 100.0, 110.0), s.map { it.end })
    }

    @Test fun neighbouringStretchesOfTheSameCameraAreJoined() {
        // A cut to the camera that is already shown changes nothing.
        val edit = CrewEdit(cuts = listOf(CrewEdit.Cut(1010.0, a.id), CrewEdit.Cut(1020.0, a.id)))
        val s = timeline(edit).segments(0.0, 60.0)
        assertEquals(1, s.size)
        assertEquals(a.id, s[0].angleID)
    }

    @Test fun anEmptyOrBackwardsRangeHasNoSegments() {
        assertTrue(timeline().segments(10.0, 10.0).isEmpty())
        assertTrue(timeline().segments(20.0, 10.0).isEmpty())
    }

    @Test fun exportPiecesPointAtTheRightPlaceInEachSource() {
        val edit = CrewEdit(cuts = listOf(CrewEdit.Cut(1045.0, b.id)))
        val t = timeline(edit)
        val uri = { _: String -> null as android.net.Uri? }
        // No uris on the JVM, so only the shape is checked: durations add up to the range.
        val pieces = CrewEditExport.pieces(t, 0.0, 110.0, uri)
        assertEquals(110.0, pieces.sumOf { it.duration }, 1e-9)
        assertEquals(4, pieces.size)
    }

    @Test fun inAndOutNeedToBeInOrderToCount() {
        assertFalse(CrewEdit(inPoint = 1020.0).hasInOut)
        assertFalse(CrewEdit(inPoint = 1030.0, outPoint = 1020.0).hasInOut)
        assertTrue(CrewEdit(inPoint = 1020.0, outPoint = 1030.0).hasInOut)
    }

    @Test fun theExportRangeIsInToOutOnlyWhenAskedForAndSet() {
        val t = timeline(CrewEdit(inPoint = 1020.0, outPoint = 1050.0))
        assertEquals(20.0 to 50.0, t.exportRange(true))
        assertEquals(0.0 to 110.0, t.exportRange(false))
        assertEquals(0.0 to 110.0, timeline().exportRange(true))
    }

    @Test fun inBeforeTheStartIsClampedToIt() {
        val t = timeline(CrewEdit(inPoint = 900.0, outPoint = 5000.0))
        assertEquals(0.0 to 110.0, t.exportRange(true))
    }

    @Test fun theChosenCameraBeforeAnyCutIsTheFirstCut() {
        val edit = CrewEdit(cuts = listOf(CrewEdit.Cut(1050.0, b.id), CrewEdit.Cut(1020.0, a.id)))
        assertEquals(a.id, edit.chosenAngle(1000.0))
        assertEquals(a.id, edit.chosenAngle(1030.0))
        assertEquals(b.id, edit.chosenAngle(1055.0))
        assertNull(CrewEdit().chosenAngle(1000.0))
    }
}
