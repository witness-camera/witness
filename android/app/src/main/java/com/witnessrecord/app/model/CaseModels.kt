package com.witnessrecord.app.model

import androidx.annotation.StringRes
import com.witnessrecord.app.R
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A folder of evidence about one matter - "Flat 4B deposit", "Accident on
 * 12 October" - so a dispute that runs for weeks keeps everything in one place,
 * with one report at the end.
 *
 * A case only groups items; it never alters them. Each item keeps its own hash
 * and time-stamp proof, so moving it between cases or out of one changes
 * nothing about what it proves.
 */
@Serializable
data class EvidenceCase(
    val id: String,
    val title: String,
    val createdAt: Long,
)

/**
 * Anything documented in Witness: a recording, a verified photo, an audio
 * recording, or a file brought in from elsewhere (a screen recording, a
 * letter). Stored by [com.witnessrecord.app.service.CaseLibrary]; the media
 * lives beside it on disk.
 *
 * Schema discipline, as for `ChunkRecord`: new fields must be optional or
 * defaulted so libraries written by an older version keep decoding.
 */
@Serializable
data class CaseItem(
    val id: String,
    /** One of [KIND_VIDEO], [KIND_PHOTO], [KIND_AUDIO], [KIND_FILE]. */
    val kind: String,
    /** Set for a video: the recording session this item stands for. */
    val sessionID: String? = null,
    val caseID: String? = null,
    val title: String? = null,
    val createdAt: Long,
    val endedAt: Long? = null,
    /** The media file inside the item's folder, for everything but a video. */
    val fileName: String? = null,
    val sha256: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Whether an independent time-stamp proof was obtained for the record. */
    val timestamped: Boolean = false,
    val uploaded: Boolean = false,
    /**
     * For a video: where in the gallery the recording was saved, one per
     * camera, so it can be watched back from the app.
     */
    val galleryUris: List<String>? = null,
    /** The checklist this item's details follow ([DetailTemplate.wire]). */
    val template: String? = null,
    /** Answers to the checklist, keyed by the prompt's id. */
    val details: Map<String, String> = emptyMap(),
    val notes: List<ItemNote> = emptyList(),
) {
    val isImported: Boolean get() = kind == KIND_FILE
    val isVideo: Boolean get() = kind == KIND_VIDEO

    companion object {
        /**
         * A Witness recording. The footage itself is in the gallery and the
         * cloud; the app keeps a thumbnail and a small audio copy, and the
         * session's own manifest is its proof.
         */
        const val KIND_VIDEO = "video"
        const val KIND_PHOTO = "photo"
        const val KIND_AUDIO = "audio"

        /** Imported rather than captured here: its proof starts at the moment of import. */
        const val KIND_FILE = "file"
    }
}

/**
 * A note added after the fact. Kept apart from the hashed record and dated
 * when written, so it reads as what it is: the person's later account, not
 * part of what was captured.
 */
@Serializable
data class ItemNote(
    val id: String,
    val createdAt: Long,
    val text: String,
)

/**
 * The record that is hashed and time-stamped for a photo, an audio recording
 * or an imported file - the counterpart of a recording's session manifest,
 * stored beside the file and uploaded with it.
 */
@Serializable
data class ItemRecord(
    val schemaVersion: Int,
    val itemID: String,
    val kind: String,
    /** "captured in Witness" or "imported into Witness". */
    val source: String,
    val fileName: String,
    val sha256: String,
    @Serializable(with = Iso8601Serializer::class) val capturedUTC: Long,
    @Serializable(with = NullableIso8601Serializer::class) val endedUTC: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val horizontalAccuracyMeters: Double? = null,
    val deviceTimeZone: String,
    val deviceModel: String,
    val systemVersion: String,
    val appVersion: String,
    val timestampAuthority: String? = null,
    val timestampFileName: String? = null,
    val note: String? = null,
)

/** [Iso8601Serializer] for a value that may be absent. */
object NullableIso8601Serializer : KSerializer<Long?> {
    private val delegate = Iso8601Serializer.nullable
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Long?) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): Long? = delegate.deserialize(decoder)
}

/**
 * The guided checklists offered after something is recorded. Choosing one is
 * optional, and every prompt can be left empty.
 */
enum class DetailTemplate(
    val wire: String,
    @StringRes val nameRes: Int,
    val prompts: List<Prompt>,
) {
    ENCOUNTER(
        "encounter", R.string.template_encounter,
        listOf(
            Prompt("names", R.string.prompt_officer_names),
            Prompt("agency", R.string.prompt_agency),
            Prompt("vehicles", R.string.prompt_vehicles),
            Prompt("reason", R.string.prompt_reason),
            Prompt("witnesses", R.string.prompt_witnesses),
            Prompt("reference", R.string.prompt_report_number),
        ),
    ),
    ACCIDENT(
        "accident", R.string.template_accident,
        listOf(
            Prompt("otherDriver", R.string.prompt_other_driver),
            Prompt("plate", R.string.prompt_plate),
            Prompt("insurance", R.string.prompt_insurance),
            Prompt("damage", R.string.prompt_damage),
            Prompt("witnesses", R.string.prompt_witnesses),
            Prompt("reference", R.string.prompt_police_report_number),
        ),
    ),
    PROPERTY(
        "property", R.string.template_property,
        listOf(
            Prompt("address", R.string.prompt_address),
            Prompt("areas", R.string.prompt_areas),
            Prompt("damage", R.string.prompt_existing_damage),
            Prompt("meters", R.string.prompt_meters),
            Prompt("present", R.string.prompt_present),
        ),
    ),
    WORKPLACE(
        "workplace", R.string.template_workplace,
        listOf(
            Prompt("people", R.string.prompt_people),
            Prompt("place", R.string.prompt_location),
            Prompt("what", R.string.prompt_what_happened),
            Prompt("witnesses", R.string.prompt_witnesses),
            Prompt("reported", R.string.prompt_reported_to),
        ),
    ),
    OTHER(
        "other", R.string.template_other,
        listOf(
            Prompt("what", R.string.prompt_what_shows),
            Prompt("people", R.string.prompt_people),
            Prompt("reference", R.string.prompt_reference),
        ),
    );

    data class Prompt(val id: String, @StringRes val labelRes: Int)

    companion object {
        fun fromWire(value: String?): DetailTemplate? = entries.firstOrNull { it.wire == value }
    }
}
