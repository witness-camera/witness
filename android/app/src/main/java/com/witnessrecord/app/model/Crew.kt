package com.witnessrecord.app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Several phones recording one event together, so it is covered from more
 * than one angle and can be cut as a single multicam edit afterwards.
 *
 * The shape, field names and wire format are the iOS build's `Crew` exactly:
 * a crew travels phone to phone in a link or QR code, and an Android phone
 * and an iPhone have to read each other's. Dates are seconds since 1970 (a
 * JSON number); ids are upper-case UUID strings, as Swift writes them.
 *
 * Nothing about a crew passes through a Witness server. Each phone keeps
 * uploading to its own cloud as when recording alone; what the crew shares is
 * this small index - who is in it, a common clock, which recordings belong to
 * it - and each member's session folders are shared with the others' emails,
 * the same way an emergency contact's are.
 *
 * Schema discipline: new fields must be optional or defaulted, because crews
 * written by another version (or another platform) travel in links.
 */
@Serializable
data class Crew(
    val id: String,
    val title: String,
    val createdAt: Double,
    /** The member whose clock is the crew's reference: whoever started it. */
    val organizerID: String,
    val members: List<CrewMember>,
    // Always written, never skipped when empty: Swift's decoder requires the
    // key even though the property has a default.
    val angles: List<CrewAngle> = emptyList(),
    /** The case on *this* phone that the crew's recordings are filed in. */
    val caseID: String? = null,
    /**
     * The key iOS's iCloud sync encrypts a crew's state with, base64. Android
     * has no use for it - there is no iCloud here - but it is carried and
     * handed on untouched, so an iPhone that gets the crew through an Android
     * phone can still sync.
     */
    val secret: String? = null,
) {
    val organizer: CrewMember? get() = members.firstOrNull { it.id == organizerID }

    /**
     * Takes in what another phone knows about the same crew. Additive only:
     * a member or recording one side has never heard of is added, never
     * removed, so passing the crew around in any order converges.
     */
    fun merged(other: Crew): Crew {
        if (other.id != id) return this
        val mergedMembers = members.toMutableList()
        for (member in other.members) {
            val i = mergedMembers.indexOfFirst { it.id == member.id }
            if (i < 0) {
                mergedMembers += member
                continue
            }
            // Only a member edits their own details, so the later edit is
            // the current one. Records from before edits were dated only fill
            // in what's missing.
            val mine = mergedMembers[i].updatedAt ?: Double.NEGATIVE_INFINITY
            val theirs = member.updatedAt ?: Double.NEGATIVE_INFINITY
            if (theirs > mine) {
                mergedMembers[i] = member
            } else if (theirs == mine && mergedMembers[i].email == null) {
                mergedMembers[i] = mergedMembers[i].copy(email = member.email)
            }
        }
        val mergedAngles = angles.toMutableList()
        for (angle in other.angles) {
            val i = mergedAngles.indexOfFirst { it.id == angle.id }
            if (i < 0) {
                mergedAngles += angle
            } else if (mergedAngles[i].folderID == null && angle.folderID != null) {
                // Where the footage went is learned after the recording stops.
                mergedAngles[i] = mergedAngles[i].copy(providerID = angle.providerID, folderID = angle.folderID)
            }
        }
        return copy(members = mergedMembers, angles = mergedAngles, secret = secret ?: other.secret)
    }

    /** Ids upper-cased, so a crew that passed through a phone that writes them
     *  lower-case still matches itself and doesn't duplicate on merge. */
    fun normalized(): Crew = copy(
        id = id.uppercase(),
        organizerID = organizerID.uppercase(),
        caseID = caseID?.uppercase(),
        members = members.map { it.copy(id = it.id.uppercase()) },
        angles = angles.map { it.copy(sessionID = it.sessionID.uppercase(), memberID = it.memberID.uppercase()) },
    )

    companion object {
        /** Lenient on the way in (older or newer crews carry other fields),
         *  and never writes a null, as Swift's synthesized Codable doesn't. */
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

@Serializable
data class CrewMember(
    val id: String,
    val name: String,
    /** Session folders are shared with this address as footage arrives. */
    val email: String? = null,
    /** When the member last changed their name or email, so a merge keeps the newest. */
    val updatedAt: Double? = null,
    /** Set while the member is recording into the crew. Android posts nothing
     *  live, but an iPhone's value arrives and is shown. */
    val recordingSince: Double? = null,
) {
    /** Recording, by their last word. A phone that died mid-recording never
     *  says it stopped, so after a few hours it no longer counts. */
    fun isRecordingNow(nowSeconds: Double): Boolean {
        val since = recordingSince ?: return false
        return nowSeconds - since < 4 * 3600
    }

    val canReceiveFootage: Boolean
        get() {
            val address = email ?: return false
            return address.contains("@") && !address.startsWith("@") && !address.endsWith("@")
        }
}

/** One camera of one member's recording, placed on the crew's clock. */
@Serializable
data class CrewAngle(
    val sessionID: String,
    val memberID: String,
    val camera: String,
    /** On the crew's clock: the device's own start plus its measured offset. */
    val startedAt: Double,
    val duration: Double,
    /** Where the video sits in the member's cloud, relative to their Witness
     *  folder, so an editor can relink every angle in one pass. */
    val folderName: String? = null,
    val fileName: String,
    /** The member's backend and the folder's id there, once the first piece has uploaded. */
    val providerID: String? = null,
    val folderID: String? = null,
) {
    /** Session and camera together: a dual-camera recording is two angles. */
    val id: String get() = "$sessionID-$camera"

    /**
     * Where to open the footage. Google Drive opens the folder itself for
     * anyone it was shared with. Dropbox only offers its owner a stable
     * address; everyone else finds it among the folders shared with them.
     */
    fun folderUrl(isMine: Boolean): String? {
        val folder = folderID ?: return null
        return when (providerID) {
            "googleDrive" -> "https://drive.google.com/drive/folders/$folder"
            "dropbox" ->
                if (isMine) "https://www.dropbox.com/home" + java.net.URLEncoder.encode(folder, "UTF-8")
                    .replace("%2F", "/").replace("+", "%20")
                else "https://www.dropbox.com/share"
            else -> null
        }
    }
}

/**
 * Written into a crew recording's manifest, so the footage itself records
 * which crew it was part of and how its clock relates to the others'.
 */
@Serializable
data class CrewStamp(
    val crewID: String,
    val crewTitle: String,
    val memberID: String,
    val memberName: String,
    /** Seconds added to this phone's clock to reach the crew's reference. */
    val clockOffsetSeconds: Double,
    /** How far that offset could be off, in seconds; null when the clock was
     *  never compared with anything and the system time was trusted. */
    val clockUncertaintySeconds: Double? = null,
)
