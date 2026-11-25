package com.witnessrecord.app.service

import android.content.Context
import com.witnessrecord.app.R
import com.witnessrecord.app.model.Crew
import com.witnessrecord.app.model.CrewAngle
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Turns a crew's recordings into timelines a video editor opens with every
 * angle already lined up on the crew's shared clock:
 *
 *  - `.fcpxml`, a multicam clip for Final Cut Pro, one angle per recording.
 *  - `.xml` in the older Final Cut 7 format, which Premiere Pro and DaVinci
 *    Resolve import, as a sequence with one video track per angle.
 *
 * The footage itself isn't in the export: each member's videos are in their
 * own cloud, in folders shared with the crew. Both files point at the layout
 * the README describes - one folder per member, holding their session folders
 * as downloaded - and an editor relinks by file name if it differs.
 *
 * A port of the iOS build's `MulticamExport`, producing the same files.
 */
object MulticamExport {
    /** Editors work in frames; 30 fps is what Witness records at by default,
     *  and any offset is still placed to within a frame. */
    private const val FPS = 30

    /** The few words a file carries that depend on the person's language. */
    class Labels(
        val crewMember: String,
        val camera: (String) -> String,
        val readmeIntro: String,
        val readmeRelink: String,
    )

    fun labels(context: Context) = Labels(
        crewMember = context.getString(R.string.crew_member_default),
        camera = { camera ->
            context.getString(
                when (camera) {
                    "front" -> R.string.crew_camera_front
                    "wide" -> R.string.crew_camera_wide
                    "ultrawide" -> R.string.crew_camera_ultrawide
                    "pip" -> R.string.crew_camera_pip
                    else -> R.string.crew_camera_rear
                }
            )
        },
        readmeIntro = context.getString(R.string.crew_export_readme_intro),
        readmeRelink = context.getString(R.string.crew_export_readme_relink),
    )

    /** Writes the files under the cache directory, which the share sheet can reach. */
    fun export(context: Context, crew: Crew): List<File> {
        val directory = File(File(context.cacheDir, "crew-export"), crew.id).apply {
            deleteRecursively()
            mkdirs()
        }
        return build(crew, labels(context)).map { (name, contents) ->
            File(directory, name).apply { writeText(contents, Charsets.UTF_8) }
        }
    }

    /** The files' names and contents, kept apart from the disk so they can be tested. */
    fun build(crew: Crew, labels: Labels): List<Pair<String, String>> {
        val base = safeName(crew.title)
        return listOf(
            "$base.fcpxml" to fcpxml(crew, labels),
            "$base (Premiere, Resolve).xml" to xmeml(crew, labels),
            "README.txt" to readme(crew, labels),
        )
    }

    // MARK: - Layout

    private class Placed(
        val angle: CrewAngle,
        val name: String,
        val path: String,
        /** Seconds from the earliest angle's start. */
        val offset: Double,
    )

    private fun placed(crew: Crew, labels: Labels): List<Placed> {
        val angles = crew.angles.sortedBy { it.startedAt }
        val origin = angles.firstOrNull()?.startedAt ?: return emptyList()
        return angles.map { angle ->
            val member = crew.members.firstOrNull { it.id == angle.memberID }?.name ?: labels.crewMember
            val camera = labels.camera(angle.camera)
            val path = listOfNotNull(safeName(member), angle.folderName?.let(::safeName), angle.fileName)
                .joinToString("/")
            Placed(angle, "$member – $camera", path, angle.startedAt - origin)
        }
    }

    // MARK: - Final Cut Pro

    private fun fcpxml(crew: Crew, labels: Labels): String {
        val angles = placed(crew, labels)
        val total = angles.maxOfOrNull { it.offset + it.angle.duration } ?: 0.0
        val assets = StringBuilder()
        val mcAngles = StringBuilder()
        angles.forEachIndexed { i, p ->
            val assetID = "a${i + 1}"
            assets.append(
                """        <asset id="$assetID" name="${xml(p.name)}" start="0s" duration="${time(p.angle.duration)}" hasVideo="1" hasAudio="1" format="r1">
            <media-rep kind="original-media" src="${xml(fileUrl(p.path))}"/>
        </asset>
"""
            )
            // The angle that starts first needs no gap in front of it.
            val gap = if (time(p.offset) == time(0.0)) "" else
                """<gap name="Gap" offset="0s" duration="${time(p.offset)}"/>
                """
            mcAngles.append(
                """            <mc-angle name="${xml(p.name)}" angleID="${xml(p.angle.id)}">
                $gap<asset-clip ref="$assetID" name="${xml(p.name)}" offset="${time(p.offset)}" duration="${time(p.angle.duration)}"/>
            </mc-angle>
"""
            )
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE fcpxml>
<fcpxml version="1.10">
    <resources>
        <format id="r1" name="FFVideoFormat1080p30" frameDuration="1/${FPS}s" width="1920" height="1080"/>
$assets        <media id="m1" name="${xml(crew.title)}">
            <multicam format="r1" tcStart="0s">
$mcAngles            </multicam>
        </media>
    </resources>
    <library>
        <event name="${xml(crew.title)}">
            <mc-clip ref="m1" name="${xml(crew.title)}" offset="0s" duration="${time(total)}"/>
        </event>
    </library>
</fcpxml>
"""
    }

    /** Rational seconds on the frame grid, as FCPXML expects. */
    private fun time(seconds: Double): String = "${frames(seconds)}/${FPS}s"

    // MARK: - Premiere Pro, DaVinci Resolve (Final Cut 7 XML)

    private fun xmeml(crew: Crew, labels: Labels): String {
        val angles = placed(crew, labels)
        val total = frames(angles.maxOfOrNull { it.offset + it.angle.duration } ?: 0.0)
        val videoTracks = StringBuilder()
        val audioTracks = StringBuilder()
        angles.forEachIndexed { i, p ->
            val start = frames(p.offset)
            val length = frames(p.angle.duration)
            val fileID = "file-${i + 1}"
            videoTracks.append(
                """                <track>
                    <clipitem id="v${i + 1}">
                        <name>${xml(p.name)}</name>
                        <duration>$length</duration>
                        $RATE
                        <start>$start</start>
                        <end>${start + length}</end>
                        <in>0</in>
                        <out>$length</out>
                        <file id="$fileID">
                            <name>${xml(p.angle.fileName)}</name>
                            <pathurl>${xml(fileUrl(p.path))}</pathurl>
                            $RATE
                            <duration>$length</duration>
                            <media><video/><audio/></media>
                        </file>
                    </clipitem>
                </track>
"""
            )
            audioTracks.append(
                """                <track>
                    <clipitem id="aud${i + 1}">
                        <name>${xml(p.name)}</name>
                        <duration>$length</duration>
                        $RATE
                        <start>$start</start>
                        <end>${start + length}</end>
                        <in>0</in>
                        <out>$length</out>
                        <file id="$fileID"/>
                    </clipitem>
                </track>
"""
            )
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE xmeml>
<xmeml version="5">
    <sequence id="crew">
        <name>${xml(crew.title)}</name>
        <duration>$total</duration>
        $RATE
        <media>
            <video>
$videoTracks            </video>
            <audio>
$audioTracks            </audio>
        </media>
    </sequence>
</xmeml>
"""
    }

    private const val RATE = "<rate><timebase>$FPS</timebase><ntsc>FALSE</ntsc></rate>"

    private fun frames(seconds: Double): Int = (maxOf(0.0, seconds) * FPS).roundToInt()

    // MARK: - README

    private fun readme(crew: Crew, labels: Labels): String {
        val angles = placed(crew, labels)
        val lines = mutableListOf(crew.title, "", labels.readmeIntro, "")
        lines += angles.map { "  ${it.path}" }
        lines += listOf("", labels.readmeRelink, "")
        for (p in angles) {
            val start = Instant.ofEpochMilli((p.angle.startedAt * 1000).toLong()).toString()
            lines += "${p.name}: $start, +${String.format(Locale.US, "%.3f", p.offset)} s, " +
                "${String.format(Locale.US, "%.1f", p.angle.duration)} s"
        }
        return lines.joinToString("\n") + "\n"
    }

    // MARK: - Helpers

    /** A relative layout under a placeholder root, so both editors read the
     *  path and fall back to relinking by name. */
    private fun fileUrl(path: String): String {
        val encoded = path.split("/").joinToString("/") { percentEncode(it) }
        return "file:///Witness%20Crew/$encoded"
    }

    private fun percentEncode(part: String): String {
        val out = StringBuilder()
        for (byte in part.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            ) out.append(c.toChar()) else out.append(String.format("%%%02X", c))
        }
        return out.toString()
    }

    /** Fit for a file or folder name on any system an editor might use. */
    fun safeName(name: String): String {
        val cleaned = name.map { if (it in "/\\:<>\"|?*") '-' else it }.joinToString("").trim()
        return cleaned.ifEmpty { "Witness" }
    }

    private fun xml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
