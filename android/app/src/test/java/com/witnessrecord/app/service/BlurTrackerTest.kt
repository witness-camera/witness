package com.witnessrecord.app.service

import com.witnessrecord.app.service.BlurTracker.Box
import com.witnessrecord.app.service.BlurTracker.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurTrackerTest {

    private fun face(cx: Float, cy: Float, size: Float = 100f) = Region(Box(cx, cy, size, size), round = true)

    @Test
    fun aDetectedFaceIsCoveredAndStaysCoveredThroughMissedFrames() {
        val tracker = BlurTracker(1080f, 1920f)
        assertEquals(1, tracker.update(listOf(face(500f, 800f))).size)
        // Detection finds nothing for 14 frames: the cover must hold.
        repeat(14) { assertEquals("still covered", 1, tracker.update(emptyList()).size) }
    }

    @Test
    fun aFaceThatNeverReturnsIsReleasedAfterTheHoldTime() {
        val tracker = BlurTracker(1080f, 1920f)
        tracker.update(listOf(face(500f, 800f)))
        var last = 1
        repeat(BlurTracker.HOLD + 2) { last = tracker.update(emptyList()).size }
        assertEquals("released", 0, last)
    }

    @Test
    fun theSameFaceMovingIsOneTrackNotTwo() {
        val tracker = BlurTracker(1080f, 1920f)
        tracker.update(listOf(face(500f, 800f)))
        val regions = tracker.update(listOf(face(530f, 810f)))
        assertEquals(1, regions.size)
    }

    @Test
    fun twoFarApartFacesAreTwoTracks() {
        val tracker = BlurTracker(1080f, 1920f)
        val regions = tracker.update(listOf(face(200f, 300f), face(900f, 1500f)))
        assertEquals(2, regions.size)
    }

    @Test
    fun theCoverGrowsAtOnceButShrinksOnlyGradually() {
        val tracker = BlurTracker(1080f, 1920f)
        tracker.update(listOf(face(500f, 800f, size = 100f)))
        val bigger = tracker.update(listOf(face(500f, 800f, size = 200f))).single().box.width
        assertEquals("grows at once", 200f, bigger, 0.01f)
        val smaller = tracker.update(listOf(face(500f, 800f, size = 100f))).single().box.width
        assertTrue("shrinks slowly, was $smaller", smaller > 150f)
    }

    @Test
    fun aFaceAndAPlateAtTheSamePlaceAreNotMerged() {
        val tracker = BlurTracker(1080f, 1920f)
        val regions = tracker.update(
            listOf(face(500f, 800f), Region(Box(500f, 800f, 200f, 60f), round = false))
        )
        assertEquals(2, regions.size)
    }

    @Test
    fun plateShapedTextIsRecognised() {
        for (plate in listOf("AB12 CDE", "7ABC123", "ABC-1234", "1234 AB", "XY 99 ZZ")) {
            assertTrue("$plate should look like a plate", BlurTracker.looksLikePlate(plate))
        }
    }

    @Test
    fun signsAndWordsAreNotMistakenForPlates() {
        for (text in listOf("STOP", "PHARMACY", "12345", "Main Street 12 North", "HI", "café 12")) {
            assertFalse("$text should not look like a plate", BlurTracker.looksLikePlate(text))
        }
    }
}
