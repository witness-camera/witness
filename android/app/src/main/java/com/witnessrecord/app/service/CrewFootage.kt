package com.witnessrecord.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.witnessrecord.app.WitnessApplication
import com.witnessrecord.app.model.CrewAngle

/**
 * Which video on this phone stands for each of a crew's angles.
 *
 * iOS shares small encrypted previews of every angle through iCloud, so each
 * phone can play them all. Android has no such channel, and the full footage
 * lives in each member's own cloud folder, so the crew editor works from
 * what is on this phone:
 *
 *  - the person's own recordings are found automatically, from the gallery
 *    copy each one was saved as;
 *  - another member's angle is linked by hand: download its video from the
 *    folder they shared, then pick it for that camera.
 *
 * A link is only a pointer to a file the person chose; nothing is copied.
 */
object CrewFootage {
    private const val KEY = "crew.footage"

    /** The video to use for an angle, or null when none is on this phone (or the file is gone). */
    fun uri(angle: CrewAngle, isMine: Boolean): Uri? {
        val chosen = Prefs.getStringMap(KEY)[angle.id]?.let(Uri::parse)
        if (chosen != null && isReadable(chosen)) return chosen
        if (!isMine) return null
        return ownRecording(angle)?.takeIf { isReadable(it) }
    }

    /** Links a picked file to an angle, and keeps the permission to read it across restarts. */
    fun link(context: Context, angle: CrewAngle, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val map = Prefs.getStringMap(KEY).toMutableMap()
        map[angle.id] = uri.toString()
        Prefs.setStringMap(KEY, map)
    }

    fun unlink(angle: CrewAngle) {
        val map = Prefs.getStringMap(KEY).toMutableMap()
        if (map.remove(angle.id) != null) Prefs.setStringMap(KEY, map)
    }

    fun isLinked(angle: CrewAngle): Boolean = Prefs.getStringMap(KEY).containsKey(angle.id)

    /**
     * This phone's own recording for an angle. The gallery copy that was saved
     * for the camera when it is known; else the recording's only gallery copy.
     * With two copies and no way to tell which camera is which, none is
     * guessed - the person picks.
     */
    private fun ownRecording(angle: CrewAngle): Uri? {
        // Session ids are upper-case on the wire and lower-case here.
        val sessionID = angle.sessionID.lowercase()
        LocalSessionRecord.gallery(sessionID)?.videos?.firstOrNull { it.camera == angle.camera }
            ?.let { return Uri.parse(it.galleryUri) }
        val item = CaseLibrary.items.value.firstOrNull { it.sessionID == sessionID } ?: return null
        return item.galleryUris?.singleOrNull()?.let(Uri::parse)
    }

    private fun isReadable(uri: Uri): Boolean = runCatching {
        WitnessApplication.appContext.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
