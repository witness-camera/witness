package com.witnessrecord.app.service

import com.witnessrecord.app.model.Crew
import com.witnessrecord.app.model.CrewAngle
import com.witnessrecord.app.model.CrewMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrewTest {
    /** Built by the iOS app's own CrewLink code (Foundation's zlib compression), not by this module. */
    private val iosToken = "lZJdS8MwFIb_ysh1U9Ju3RcIduuEXUwEZTcikqWxRpumpNkQx_67b7o65m7U56ZwPt7zntPsicrJlEw66BjQEaBDQJMzSEAaKax0qBfx-k3oNfvtu3pYXqHPKVdKtK24Fa-9KO7V1jjZOKQgyJ3MU6hGI9aBMG_kMkPLvINGgMaA9gEdnAEZXhWlbMj0cQ-TTaNM1XazKO4PkuGIjifpjM6zxQ31IepjPuQjfi_H7U8TfcbCOAkIjO5ULm2rVhiDKZlVO4mmF1Miccu13yxmUIXBaNC74_bdp1Upu6SVwthcVcUzlrWhrr1jLfWm0406Lta7vL6AmuWo33DhJ-Rbyx02JdNhFCbfhlpFvhFYkxyeAmJswSv1-b9RR3PHe1bHJdKKI9G-lj-KSM1V6c1U_Fp-cF2XMhRGI7Ot84ufHjF2CE6zZgZFp6vdq0rIU2Xsn0drI-2gM0DbR5IBujgDJzh8AQ"

    private val organizer = "11111111-2222-3333-4444-555555555555"
    private val bo = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
    private val session = "01234567-89AB-CDEF-0123-456789ABCDEF"

    private fun angle(sessionID: String = session, member: String = organizer, camera: String = "back",
                      startedAt: Double = 1_700_000_300.25, duration: Double = 61.5,
                      folder: String? = null) = CrewAngle(
        sessionID = sessionID, memberID = member, camera = camera, startedAt = startedAt, duration = duration,
        folderName = "2023-11-14 Park", fileName = "recording_rear.mp4",
        providerID = folder?.let { "googleDrive" }, folderID = folder,
    )

    private fun crew(
        members: List<CrewMember> = listOf(CrewMember(organizer, "Ana", "ana@example.com", 1_700_000_100.0)),
        angles: List<CrewAngle> = emptyList(),
    ) = Crew(
        id = "99999999-8888-7777-6666-555555555555", title = "March 12 protest", createdAt = 1_700_000_000.0,
        organizerID = organizer, members = members, angles = angles,
        caseID = "CCCCCCCC-1111-2222-3333-444444444444", secret = "c2VjcmV0",
    )

    // ---------------------------------------------------------------- the link

    @Test fun readsATokenWrittenByTheIosApp() {
        val c = CrewLink.parseToken(iosToken)
        assertNotNull("an iOS token must decode", c)
        c!!
        assertEquals("March 12 protest", c.title)
        assertEquals(organizer, c.organizerID)
        assertEquals(listOf("Ana", "Bo"), c.members.map { it.name })
        assertEquals("ana@example.com", c.members[0].email)
        assertNull(c.members[1].email)
        assertEquals(1_700_000_200.0, c.members[1].recordingSince!!, 0.0)
        assertEquals(1, c.angles.size)
        assertEquals(1_700_000_300.25, c.angles[0].startedAt, 0.0)
        assertEquals("googleDrive", c.angles[0].providerID)
        assertEquals("c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0MTI=", c.secret)
    }

    @Test fun readsTheIosTokenInsideEveryLinkForm() {
        assertNotNull(CrewLink.parse("https://witnesscamera.com/crew#$iosToken"))
        assertNotNull(CrewLink.parse("witnessrecord://crew#$iosToken"))
        assertNotNull(CrewLink.parse("  https://witnesscamera.com/crew#$iosToken\n"))
        assertNotNull(CrewLink.parse(iosToken))
    }

    @Test fun refusesWhatIsNotACrew() {
        assertNull(CrewLink.parse(""))
        assertNull(CrewLink.parse("https://example.com/crew#$iosToken"))
        assertNull(CrewLink.parse("https://witnesscamera.com/crew"))
        assertNull(CrewLink.parse("https://witnesscamera.com/crew#not-a-token"))
        assertNull(CrewLink.parse("hello there"))
    }

    @Test fun aTokenSurvivesTheRoundTrip() {
        val original = crew(
            members = listOf(
                CrewMember(organizer, "Ana", "ana@example.com", 1_700_000_100.0),
                CrewMember(bo, "Bo – é ü 😀"),
            ),
            angles = listOf(angle(folder = "abc123")),
        )
        val back = CrewLink.parse(CrewLink.url(original))
        assertEquals(original, back)
        // Android's own token for the iOS app to read, if a caller wants to check it there.
        System.getenv("CREW_TOKEN_OUT")?.let { File(it).writeText(CrewLink.token(original)) }
    }

    @Test fun theWireFormIsWhatSwiftsDecoderNeeds() {
        val json = Crew.json.encodeToString(Crew.serializer(), crew())
        // Swift requires the key even when empty, and has no null to read for an absent optional.
        assertTrue(json, json.contains("\"angles\":[]"))
        assertFalse(json, json.contains("null"))
        assertFalse(json, json.contains("\"recordingSince\""))
    }

    @Test fun lowerCaseIdsFromAnotherWriterAreNormalised() {
        val c = crew().let { it.copy(id = it.id.lowercase(), organizerID = organizer.lowercase(),
            members = it.members.map { m -> m.copy(id = m.id.lowercase()) }) }
        val back = CrewLink.parse(CrewLink.url(c))!!
        assertEquals(c.id.uppercase(), back.id)
        assertEquals(organizer, back.members[0].id)
    }

    // ---------------------------------------------------------------- merging

    @Test fun mergingAddsAndNeverRemoves() {
        val mine = crew(angles = listOf(angle()))
        val theirs = crew(
            members = listOf(
                CrewMember(organizer, "Ana", "ana@example.com", 1_700_000_100.0),
                CrewMember(bo, "Bo", "bo@example.com", 1_700_000_500.0),
            ),
            angles = listOf(angle(sessionID = "AAAAAAAA-0000-0000-0000-000000000001", member = bo, startedAt = 1_700_000_320.0)),
        )
        val merged = mine.merged(theirs)
        assertEquals(2, merged.members.size)
        assertEquals(2, merged.angles.size)
        // And the other way round converges on the same content.
        val other = theirs.merged(mine)
        assertEquals(merged.members.toSet(), other.members.toSet())
        assertEquals(merged.angles.toSet(), other.angles.toSet())
        // Merging what is already known changes nothing.
        assertEquals(merged, merged.merged(theirs))
    }

    @Test fun theLaterEditToAMemberWins() {
        val mine = crew(members = listOf(CrewMember(organizer, "Ana", "old@example.com", 100.0)))
        val theirs = crew(members = listOf(CrewMember(organizer, "Ana", "new@example.com", 200.0)))
        assertEquals("new@example.com", mine.merged(theirs).members[0].email)
        assertEquals("new@example.com", theirs.merged(mine).members[0].email)
    }

    @Test fun whereFootageWentIsLearnedLater() {
        val mine = crew(angles = listOf(angle()))
        val theirs = crew(angles = listOf(angle(folder = "folder-1")))
        val merged = mine.merged(theirs)
        assertEquals("folder-1", merged.angles[0].folderID)
        assertEquals("googleDrive", merged.angles[0].providerID)
    }

    @Test fun anotherCrewIsLeftAlone() {
        val mine = crew()
        assertEquals(mine, mine.merged(mine.copy(id = "00000000-0000-0000-0000-000000000000", title = "Other")))
    }

    @Test fun anEmailNeedsAnAtSignWithSomethingEitherSide() {
        assertTrue(CrewMember(bo, "Bo", "bo@example.com").canReceiveFootage)
        assertFalse(CrewMember(bo, "Bo", null).canReceiveFootage)
        assertFalse(CrewMember(bo, "Bo", "bo").canReceiveFootage)
        assertFalse(CrewMember(bo, "Bo", "@example.com").canReceiveFootage)
        assertFalse(CrewMember(bo, "Bo", "bo@").canReceiveFootage)
    }

    @Test fun aPhoneThatNeverSaidItStoppedStopsCounting() {
        val m = CrewMember(bo, "Bo", recordingSince = 1000.0)
        assertTrue(m.isRecordingNow(1000.0 + 3600))
        assertFalse(m.isRecordingNow(1000.0 + 5 * 3600))
        assertFalse(CrewMember(bo, "Bo").isRecordingNow(1000.0))
    }

    @Test fun folderLinksFollowTheBackend() {
        assertEquals("https://drive.google.com/drive/folders/abc", angle(folder = "abc").folderUrl(isMine = false))
        val dropbox = angle().copy(providerID = "dropbox", folderID = "/Witness/Park 1")
        assertEquals("https://www.dropbox.com/home/Witness/Park%201", dropbox.folderUrl(isMine = true))
        assertEquals("https://www.dropbox.com/share", dropbox.folderUrl(isMine = false))
        assertNull(angle().copy(providerID = "webDAV", folderID = "x").folderUrl(isMine = true))
        assertNull(angle().folderUrl(isMine = true))
    }
}
