package com.witnessrecord.app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One JSON manifest per recording session - the chain-of-custody record for
 * the whole encounter. It ties every chunk of every camera to its SHA-256
 * hash, capture window, and location.
 *
 * It is rewritten and re-uploaded over the same Drive file as each new chunk
 * is hashed, so Drive holds current custody records throughout the recording,
 * not only at the end - which is the point, since the phone may be seized
 * mid-encounter.
 */
@Serializable
data class SessionManifest(
    /**
     * Format version of this manifest file itself - for any external tool or
     * person reading it later (a contact, an attorney) to know which shape to
     * expect. Bump alongside `ChunkStore.CURRENT_SCHEMA_VERSION` if this
     * type's on-disk shape changes in an incompatible way.
     */
    val schemaVersion: Int,
    val sessionID: String,
    @Serializable(with = Iso8601Serializer::class) val recordingStartedUTC: Long,
    @Serializable(with = Iso8601Serializer::class) val recordingEndedUTC: Long,
    val deviceTimeZone: String,
    val deviceModel: String,
    val systemVersion: String,
    val appVersion: String,
    val chunkCount: Int,
    val chunks: List<Chunk>,
    val mergedVideos: List<MergedVideo>? = null,

    /**
     * Where the footage was kept, when that isn't the cloud folder this
     * manifest sits in - written for recordings kept only on the phone, whose
     * 15-second pieces were never uploaded or retained.
     */
    val storage: String? = null,

    /**
     * Moments the person marked while recording (a flag tap or a volume
     * button press), as UTC times. Part of the hashed, time-stamped record,
     * so a mark can be shown to have been made during the recording itself.
     */
    val markers: List<@Serializable(with = Iso8601Serializer::class) Long>? = null,

    /**
     * The crew this recording was made for, when several phones were filming
     * one event together, and how this phone's clock relates to the crew's -
     * so the footage itself says which angle it is and how to line it up.
     */
    val crew: CrewStamp? = null,

    /**
     * Where the accompanying RFC 3161 proof came from, and what it is called
     * in this folder. The proof covers the SHA-256 of *this file*, so it
     * cannot name its own hash here - instead it names the file that carries
     * it. Present only once a timestamp was actually obtained: absent means
     * the authority was unreachable, never that the check failed.
     */
    val timestampAuthority: String? = null,
    val timestampFileName: String? = null,
    val timestampVerification: String? = null,
) {
    @Serializable
    data class Chunk(
        val index: Int,
        val camera: String,
        val fileName: String,
        /**
         * "master" for the full-quality recording, "lowRes" for a small
         * stand-in uploaded ahead of it on a failing connection. Optional for
         * records written before stand-ins existed; absent means master.
         */
        val kind: String? = null,
        val sha256: String?,
        @Serializable(with = Iso8601Serializer::class) val captureStartUTC: Long,
        @Serializable(with = Iso8601Serializer::class) val captureEndUTC: Long,
        val latitude: Double?,
        val longitude: Double?,
        val horizontalAccuracyMeters: Double?,
    )

    /**
     * One camera's small chunks consolidated into a single video, once every
     * chunk of that camera was confirmed uploaded. Present only after
     * consolidation - see UploadManager's Drive-stitch pipeline. The [chunks]
     * list above still documents the original per-piece hashes even after the
     * small files themselves are removed from Drive.
     */
    @Serializable
    data class MergedVideo(
        val camera: String,
        val fileName: String,
        val sha256: String?,
    )

    companion object {
        /**
         * Pretty-printed so the manifest is readable by whoever opens it in
         * Drive without any tooling.
         */
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = true
        }

        /**
         * Written into the manifest so the instructions travel with the
         * evidence and stay readable years later, by someone with no access
         * to this app.
         */
        const val VERIFICATION_INSTRUCTIONS =
            "This manifest is counter-signed by an independent RFC 3161 time-stamp " +
            "authority, proving it existed unmodified before the time in the " +
            "accompanying .tsr file. Because every chunk's SHA-256 is listed above, " +
            "that proof extends to the footage itself. To verify: " +
            "`openssl ts -verify -data <this file> -in <timestampFileName> -CAfile <authority CA cert>`. " +
            "Any edit to this manifest, or to any file whose hash it lists, breaks " +
            "the check."
    }
}
