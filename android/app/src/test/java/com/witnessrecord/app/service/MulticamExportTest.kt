package com.witnessrecord.app.service

import com.witnessrecord.app.model.Crew
import com.witnessrecord.app.model.CrewAngle
import com.witnessrecord.app.model.CrewMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MulticamExportTest {
    private val labels = MulticamExport.Labels(
        crewMember = "Crew member",
        camera = { if (it == "front") "front camera" else "rear camera" },
        readmeIntro = "INTRO", readmeRelink = "RELINK",
    )

    private val ana = "11111111-2222-3333-4444-555555555555"
    private val bo = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"

    private fun crew() = Crew(
        id = "99999999-8888-7777-6666-555555555555", title = "Park & Ride: \"march\"", createdAt = 0.0,
        organizerID = ana,
        members = listOf(CrewMember(ana, "Ana"), CrewMember(bo, "Bo/B")),
        angles = listOf(
            // Listed out of order on purpose: the export sorts by start.
            CrewAngle("2".repeat(8) + "-0000-0000-0000-000000000002", bo, "back", 1010.0, 20.0, "Bo folder", "recording_rear.mp4"),
            CrewAngle("1".repeat(8) + "-0000-0000-0000-000000000001", ana, "back", 1000.0, 30.0, "Ana folder", "recording_rear.mp4"),
        ),
    )

    private fun files() = MulticamExport.build(crew(), labels).toMap()

    @Test fun producesTheThreeFilesAnEditorNeeds() {
        val names = MulticamExport.build(crew(), labels).map { it.first }
        assertEquals(listOf("Park & Ride- -march-.fcpxml", "Park & Ride- -march- (Premiere, Resolve).xml", "README.txt"), names)
    }

    @Test fun laterAnglesAreOffsetFromTheEarliest() {
        val fcp = files().getValue("Park & Ride- -march-.fcpxml")
        // Bo started 10 s after Ana: 300 frames at 30 fps.
        assertTrue(fcp, fcp.contains("""<gap name="Gap" offset="0s" duration="300/30s"/>"""))
        assertTrue(fcp, fcp.contains("""offset="300/30s" duration="600/30s""""))
        // The whole clip runs to the end of the last angle to finish (Ana: 30 s, Bo: 10 + 20 s).
        assertTrue(fcp, fcp.contains("""<mc-clip ref="m1" name="Park &amp; Ride: &quot;march&quot;" offset="0s" duration="900/30s"/>"""))
    }

    @Test fun premiereAndResolveGetOneTrackPerAngle() {
        val xml = files().getValue("Park & Ride- -march- (Premiere, Resolve).xml")
        assertEquals(2, Regex("<clipitem id=\"v").findAll(xml).count())
        assertTrue(xml, xml.contains("<start>300</start>"))
        assertTrue(xml, xml.contains("<end>900</end>"))
        assertTrue(xml, xml.contains("<name>Ana – rear camera</name>"))
    }

    @Test fun footagePathsAreOneFolderPerMember() {
        val xml = files().getValue("Park & Ride- -march- (Premiere, Resolve).xml")
        assertTrue(xml, xml.contains("file:///Witness%20Crew/Ana/Ana%20folder/recording_rear.mp4"))
        // A slash in a name must not become a folder.
        assertTrue(xml, xml.contains("file:///Witness%20Crew/Bo-B/Bo%20folder/recording_rear.mp4"))
    }

    @Test fun theReadmeListsEveryAngleOnTheSharedClock() {
        val readme = files().getValue("README.txt")
        assertTrue(readme, readme.contains("INTRO") && readme.contains("RELINK"))
        assertTrue(readme, readme.contains("+10.000 s, 20.0 s"))
        assertTrue(readme, readme.contains("1970-01-01T00:16:50Z"))
    }

    @Test fun aCrewWithNoRecordingsStillExports() {
        val empty = crew().copy(angles = emptyList())
        val out = MulticamExport.build(empty, labels).toMap()
        assertTrue(out.getValue("Park & Ride- -march-.fcpxml").contains("duration=\"0/30s\""))
    }
}
