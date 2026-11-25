package com.witnessrecord.app.service

import androidx.annotation.StringRes
import com.witnessrecord.app.R

/**
 * User's preferred rear-camera focal length. Persisted as a raw string, with
 * the same three cases and the same wire values as the iOS build.
 */
enum class LensPreference(val wire: String, @StringRes val labelRes: Int) {
    /** Ultra-wide (0.5x) if available, else wide (1x). */
    AUTO("auto", R.string.lens_auto),

    /** Lock to 0.5x. */
    ULTRA_WIDE("ultraWide", R.string.lens_ultra_wide),

    /** Lock to 1x. */
    WIDE("wide", R.string.lens_wide);

    companion object {
        fun fromWire(value: String?): LensPreference =
            entries.firstOrNull { it.wire == value } ?: AUTO
    }
}

/**
 * Which instant-start entry points are switched on. Android has no Action
 * Button, so the iOS build's single "bind the Action Button" story becomes a
 * set of native equivalents the user picks from; the quick-settings tile is
 * the closest match and what onboarding leads with.
 */
object InstantStart {
    const val powerButtonKey = "instantStartPowerButton"

    /**
     * Whether the camera-gesture activity-alias is enabled, letting a
     * double-press of the power button start a recording. Off by default: the
     * gesture is reserved for camera apps, so enabling it also offers Witness
     * as a default camera app, which most users do not want.
     */
    val powerButtonEnabled: Boolean
        get() = Prefs.getBool(powerButtonKey, false)
}

/**
 * Non-view access to user-facing settings that also have a Compose binding in
 * the UI. Keys and defaults are kept in one place so the view and the
 * capture/stitch layers agree.
 */
object AppSettings {
    const val photosBackupKey = "photosBackupEnabled"
    const val dualCameraKey = "dualCameraEnabled"
    const val lensPreferenceKey = "lensPreference"
    const val driveStitchKey = "driveStitchEnabled"
    const val folderNameTemplateKey = "folderNameTemplate"
    const val driveFolderNameKey = "driveFolderName"

    const val onboardingCompleteKey = "onboardingComplete"
    const val practiceCompleteKey = "practiceComplete"

    const val defaultFolderNameTemplate = "Witness {date} {time}, {location}"
    const val defaultDriveFolderName = "Witness"

    /**
     * Preferred rear lens. Defaults to Auto (0.5x when the device allows it,
     * otherwise 1x).
     */
    val lensPreference: LensPreference
        get() = LensPreference.fromWire(Prefs.getString(lensPreferenceKey))

    /**
     * Whether the stitched per-session video is saved to the device gallery.
     * Defaults to on; Drive upload is independent of this.
     */
    val photosBackupEnabled: Boolean
        get() = Prefs.getBool(photosBackupKey, true)

    /**
     * Whether to record the front camera alongside the rear one. Defaults to
     * on; only takes effect on devices that support simultaneous capture.
     */
    val dualCameraEnabled: Boolean
        get() = Prefs.getBool(dualCameraKey, true)

    /**
     * Whether, once every chunk of a session is confirmed uploaded, the app
     * merges them into one video per camera, uploads that to the session's
     * Drive folder, and then deletes the small chunk files from Drive (they
     * already served their purpose - getting footage off the device during
     * recording). Defaults to on.
     */
    val driveStitchEnabled: Boolean
        get() = Prefs.getBool(driveStitchKey, true)

    /** Name of the single top-level Drive folder everything uploads into. */
    val driveFolderName: String
        get() = Prefs.getString(driveFolderNameKey, defaultDriveFolderName)
            ?.takeIf { it.isNotBlank() } ?: defaultDriveFolderName

    /**
     * Template for each session's Drive subfolder name. Supports `{date}`,
     * `{time}`, `{location}`, and `{session}` placeholders - see
     * [SessionFolderNaming.placeholders]. Falls back to the default if unset
     * or if it resolves to an empty name.
     */
    val folderNameTemplate: String
        get() = Prefs.getString(folderNameTemplateKey)
            ?.takeIf { it.isNotBlank() } ?: defaultFolderNameTemplate

    // MARK: - Pro capture

    const val proEntitledKey = "proEntitled"
    const val videoResolutionKey = "videoResolution"
    const val videoFrameRateKey = "videoFrameRate"
    const val videoCodecKey = "videoCodec"
    const val hdrRecordingKey = "hdrRecordingEnabled"
    const val lowResFallbackKey = "lowResFallbackEnabled"

    /**
     * Mirror of the live Play Billing entitlement, refreshed by
     * [com.witnessrecord.app.billing.ProEntitlement]. Play Billing is the
     * source of truth and is re-checked at every launch; this copy exists
     * only so the capture pipeline can read the gate synchronously off the
     * UI thread.
     */
    val isProEntitled: Boolean
        get() = Prefs.getBool(proEntitledKey, false)

    /**
     * Capture resolution, falling back to 1080p when a Pro-only value is
     * stored but the subscription has lapsed, so an expired subscriber
     * silently returns to the free tier instead of keeping 4K.
     */
    val videoResolution: VideoResolution
        get() {
            val stored = VideoResolution.fromWire(Prefs.getString(videoResolutionKey))
            return if (stored.requiresPro && !isProEntitled) VideoResolution.HD1080 else stored
        }

    val videoFrameRate: VideoFrameRate
        get() {
            val raw = Prefs.shared.getInt(videoFrameRateKey, VideoFrameRate.FPS30.fps)
            val stored = VideoFrameRate.fromWire(raw)
            return if (stored.requiresPro && !isProEntitled) VideoFrameRate.FPS30 else stored
        }

    /**
     * Recording codec, falling back to H.264 when a Pro codec is stored but
     * the subscription has lapsed.
     */
    val videoCodec: VideoCodec
        get() {
            val stored = VideoCodec.entries.firstOrNull { it.wire == Prefs.getString(videoCodecKey) }
                ?: VideoCodec.H264
            return if (stored.requiresPro && !isProEntitled) VideoCodec.H264 else stored
        }

    const val cloudUploadKey = "cloudUploadEnabled"

    /**
     * Whether new recordings are sent to cloud storage at all.
     *
     * Read only when a chunk is finalized, and deliberately never consulted
     * by the upload queue. Turning it off stops the *next* recording from
     * being queued; it cannot reach into footage already recorded, and no
     * combination of settings will cancel an upload that is under way.
     *
     * That asymmetry is the point. Someone who takes an unlocked phone during
     * an encounter would otherwise have a switch that deletes the evidence of
     * it - which is precisely the outcome the app exists to make impossible.
     * Off is a decision about what to record next, not a way to unsend what
     * already happened.
     */
    val cloudUploadEnabled: Boolean
        get() = Prefs.getBool(cloudUploadKey, true)

    /**
     * Whether this session's footage is kept on the device rather than
     * uploaded - because the mastering codec is far too large to send live,
     * because uploading has been switched off for new recordings, or because
     * no cloud account is connected to send it to. In the last case, queuing
     * chunks for a cloud that isn't there kept every recording on the phone
     * twice: once as chunks waiting indefinitely, once as the gallery copy.
     */
    val isLocalOnlyCapture: Boolean
        get() = isLocalOnlyCapture(
            codec = videoCodec,
            cloudUploadEnabled = cloudUploadEnabled,
            cloudAccountConnected = cloudAccountConnected,
        )

    const val cloudAccountConnectedKey = "cloudAccountConnected"

    /**
     * Whether a cloud account is connected for the selected backend.
     *
     * Read by the capture pipeline, which can run from a quick-start cold
     * start before the account's sign-in has been restored - so it can't ask
     * the live sign-in state alone. The stored credentials answer
     * synchronously; the mirrored flag covers a sign-in that has not finished
     * restoring yet. Unknown counts as connected: wrongly assuming an account
     * keeps chunks queued for a cloud that never arrives, which wastes space,
     * while wrongly assuming none would keep footage off the cloud during an
     * encounter.
     */
    val cloudAccountConnected: Boolean
        get() {
            if (hasStoredCloudCredentials) return true
            return Prefs.getBool(cloudAccountConnectedKey, true)
        }

    val hasStoredCloudCredentials: Boolean
        get() = com.witnessrecord.app.service.storage.StorageProviders.current?.isSignedIn == true

    /**
     * Mirrors the stored credentials into the flag above. Called while the
     * app is in the foreground and after any sign-in or sign-out, when the
     * answer is certain.
     */
    fun refreshCloudAccountConnected() {
        Prefs.setBool(cloudAccountConnectedKey, hasStoredCloudCredentials)
    }

    /**
     * The same answer as a pure function of its inputs, so Settings and the
     * capture pipeline reach it the same way instead of each deciding for
     * itself - a screen has to *read* the values it renders from for the row
     * to recompose when they change, which reading a global that goes to
     * preferences behind its back would not do.
     */
    fun isLocalOnlyCapture(
        codec: VideoCodec,
        cloudUploadEnabled: Boolean,
        cloudAccountConnected: Boolean,
    ): Boolean = !codec.uploadsLive || !cloudUploadEnabled || !cloudAccountConnected

    /**
     * Whether to capture 10-bit HDR. Pro-only, and additionally requires
     * hardware support - the capture layer checks the camera's declared
     * dynamic-range profiles and silently stays in standard 8-bit when the
     * device can't do it.
     *
     * This is the Android counterpart of the iOS build's Apple Log toggle.
     * Android's public Camera2 API has no vendor-neutral flat/Log profile the
     * way Apple Log is on iPhone 15 Pro+; HLG10 10-bit HDR is the closest
     * genuine capability gain (more tonal data than 8-bit) available through
     * a public API on any Android device, so that is what Pro offers instead
     * - honestly labelled as HDR, not as Log.
     */
    val hdrRecordingEnabled: Boolean
        get() {
            if (!isProEntitled || !videoCodec.supportsHdr) return false
            return Prefs.getBool(hdrRecordingKey, false)
        }

    const val timestampBurnInKey = "timestampBurnInEnabled"

    /**
     * Whether to burn a visible timestamp and GPS coordinate into the picture
     * itself, on top of the invisible metadata (SHA-256 hash, manifest, RFC
     * 3161 timestamp) every recording already carries regardless of this
     * setting.
     *
     * Free for everyone. Without Pro the overlay also carries a "Recorded
     * with Witness" line; Pro leaves only the time and place. Silently stays
     * off with HDR recording, since compositing onto a 10-bit HLG buffer is a
     * materially different - and unverified - rendering path; HDR is the rarer
     * combination of the two, so it is HDR that yields here rather than the
     * setting refusing to engage at all.
     */
    val timestampBurnInEnabled: Boolean
        get() {
            if (hdrRecordingEnabled) return false
            return Prefs.getBool(timestampBurnInKey, false)
        }

    /** Whether the on-screen stamp carries the "Recorded with Witness" line. */
    val timestampBranded: Boolean get() = !isProEntitled

    const val quickStartViewfinderKey = "quickStartViewfinder"

    /**
     * Whether a recording started from the tile, widget, shortcut or power
     * button shows the camera. Off by default: the black screen is the point
     * of a one-press start. Off also leaves an already-open viewfinder as it
     * is.
     */
    val quickStartViewfinder: Boolean
        get() = Prefs.getBool(quickStartViewfinderKey, false)

    const val stabilizationKey = "videoStabilization"

    /**
     * Pro: how hard the camera smooths out shake. Free recordings use
     * Standard, as before. The Android modes this maps to are in
     * [com.witnessrecord.app.capture.CaptureSessionController].
     */
    enum class Stabilization(val wire: String, @androidx.annotation.StringRes val labelRes: Int) {
        OFF("off", R.string.stabilization_off),
        STANDARD("standard", R.string.stabilization_standard),
        CINEMATIC("cinematic", R.string.stabilization_cinematic),
        EXTREME("extreme", R.string.stabilization_extreme);

        companion object {
            fun fromWire(value: String?): Stabilization =
                entries.firstOrNull { it.wire == value } ?: STANDARD
        }
    }

    val stabilization: Stabilization
        get() {
            if (!isProEntitled) return Stabilization.STANDARD
            return Stabilization.fromWire(Prefs.getString(stabilizationKey))
        }

    const val stealthLandscapeKey = "stealthLandscapeEnabled"

    /**
     * Record a landscape 16:9 video while holding the phone upright: the
     * camera's tall 4:3 frame with a landscape strip cut from the middle of
     * each. Single camera, so it takes priority over dual-camera; not
     * available with HDR.
     */
    val stealthLandscapeEnabled: Boolean
        get() {
            if (!isProEntitled || hdrRecordingEnabled) return false
            return Prefs.getBool(stealthLandscapeKey, false)
        }

    const val blurExportKey = "blurExportEnabled"

    /**
     * Whether a session also produces a copy with faces and licence plates
     * blurred, for publishing - in addition to, never instead of, the
     * untouched original, which remains the evidence. Pro, as a publishing
     * workflow; nothing about the recording itself changes.
     */
    val blurExportEnabled: Boolean
        get() {
            if (!isProEntitled) return false
            return Prefs.getBool(blurExportKey, false)
        }

    const val voiceDisguiseKey = "voiceDisguiseEnabled"

    /**
     * Whether the blurred, publish-safe copy also disguises voices. Only
     * meaningful with [blurExportEnabled]; the original's audio is never
     * touched.
     */
    val voiceDisguiseEnabled: Boolean
        get() {
            if (!isProEntitled) return false
            return Prefs.getBool(voiceDisguiseKey, false)
        }

    const val pipExportKey = "pipExportEnabled"

    /**
     * Whether a dual-camera session also produces one combined
     * picture-in-picture video (rear full-frame, front inset) in addition to
     * - never instead of - the two separate per-camera videos it already
     * produces. Purely a convenience for sharing one file instead of two;
     * nothing about the underlying two recordings changes.
     */
    val pipExportEnabled: Boolean
        get() {
            if (!isProEntitled) return false
            return Prefs.getBool(pipExportKey, false)
        }

    const val highlightExportKey = "highlightExportEnabled"

    /**
     * Whether a session also produces a shorter "highlights" cut - the
     * stretches with someone actually talking, dead air trimmed out - in
     * addition to, never instead of, the full per-camera video it already
     * produces. Built from the rear camera's own audio and video, so it
     * exists regardless of whether dual capture or picture-in-picture is on.
     */
    val highlightExportEnabled: Boolean
        get() {
            if (!isProEntitled) return false
            return Prefs.getBool(highlightExportKey, false)
        }

    /**
     * Whether, when uploads are demonstrably falling behind, Witness sends a
     * small stand-in of each new chunk ahead of the full-quality file.
     *
     * Only ever used while the pipeline is actually losing ground: on a
     * healthy connection the original goes up directly and no stand-in is
     * ever produced. Defaults to on - on a failing network a low-resolution
     * copy that arrives beats a pristine one that doesn't.
     */
    val lowResFallbackEnabled: Boolean
        get() = Prefs.getBool(lowResFallbackKey, true)

    /** One capture setting that is keeping the front camera off. */
    enum class DualCameraBlocker { RESOLUTION, FRAME_RATE, CODEC, HDR }

    /**
     * Which settings are ruling out simultaneous front+rear capture.
     *
     * Simultaneous capture is limited to the formats the hardware can run on
     * two cameras at once - in practice 1080p30 H.264, never 10-bit - so
     * these are mutually exclusive. Pro wins: silently ignoring settings
     * somebody is paying for is worse than dropping the second camera.
     *
     * Pure, and takes its inputs, for two reasons. It used to be written
     * three times - here, in Settings and in the Pro page - and the copies
     * disagreed about the HDR flag, so the front camera could be reported off
     * with nothing actually keeping it off. And a Compose screen has to
     * *read* the values it renders from for the row to recompose when they
     * change, which reading a global that goes to preferences behind its back
     * would not do. Passing them in gives both callers the same answer and
     * the screen its dependency.
     */
    fun dualCameraBlockers(
        resolution: VideoResolution,
        frameRate: VideoFrameRate,
        codec: VideoCodec,
        hdrRecording: Boolean,
        isPro: Boolean,
    ): List<DualCameraBlocker> {
        // Without the subscription every Pro setting reverts to its free
        // default, so none of them blocks anything.
        if (!isPro) return emptyList()
        val blockers = mutableListOf<DualCameraBlocker>()
        if (resolution != VideoResolution.HD1080) blockers.add(DualCameraBlocker.RESOLUTION)
        if (frameRate != VideoFrameRate.FPS30) blockers.add(DualCameraBlocker.FRAME_RATE)
        if (codec != VideoCodec.H264) blockers.add(DualCameraBlocker.CODEC)
        // HDR needs a 10-bit codec, so the stored flag can outlive a switch
        // back to H.264 and mean nothing. A setting that is not doing anything
        // must not keep the second camera off.
        if (hdrRecording && codec.supportsHdr) blockers.add(DualCameraBlocker.HDR)
        return blockers
    }

    /** The same question asked of the stored settings, for the capture layer. */
    val dualCameraBlockers: List<DualCameraBlocker>
        get() = dualCameraBlockers(
            resolution = videoResolution,
            frameRate = videoFrameRate,
            codec = videoCodec,
            hdrRecording = Prefs.getBool(hdrRecordingKey, false),
            isPro = isProEntitled,
        )

    /**
     * Whether any capture setting departs from the free defaults. Derived
     * from the list above, so "is the front camera off" and "what is keeping
     * it off" cannot give contradictory answers.
     */
    val usesProCapture: Boolean
        get() = dualCameraBlockers.isNotEmpty()
}
