package com.witnessrecord.app.capture

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Build
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.util.Range
import android.util.Size
import com.witnessrecord.app.WitnessApplication
import com.witnessrecord.app.service.AppSettings
import com.witnessrecord.app.service.LensPreference
import kotlin.math.abs

/**
 * Picks which physical cameras and capture sizes a recording should use.
 *
 * The iOS build reasons in Camera-app lens multipliers (0.5x / 1x) over
 * virtual devices; Android exposes the same idea as a *logical* camera whose
 * `CONTROL_ZOOM_RATIO` range reaches below 1.0 and hands over to the
 * ultra-wide sensor optically as it crosses. Where a device has no such
 * logical camera, the ultra-wide is a separate camera id that can be selected
 * up front but not crossed to live - which is exactly the iOS distinction
 * between a virtual device and a physical lens.
 */
object CameraSelection {

    /** Rear camera the recording should open, with what it can do. */
    data class RearCamera(
        val cameraID: String,
        val characteristics: CameraCharacteristics,
        /** True when this camera can reach 0.5x by zoom ratio alone. */
        val canCrossToUltraWide: Boolean,
        /** True when this camera *is* a fixed ultra-wide sensor. */
        val isDedicatedUltraWide: Boolean,
    ) {
        val describe: String
            get() = when {
                canCrossToUltraWide -> "logical(0.5-1x crossover)"
                isDedicatedUltraWide -> "ultraWide(0.5x fixed)"
                else -> "wide(1x)"
            }
    }

    private fun CameraManager.characteristicsOrNull(id: String): CameraCharacteristics? =
        runCatching { getCameraCharacteristics(id) }.getOrNull()

    private fun CameraCharacteristics.facing(): Int? =
        get(CameraCharacteristics.LENS_FACING)

    private fun CameraCharacteristics.zoomRange(): Range<Float>? =
        get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)

    private fun CameraCharacteristics.minFocalLength(): Float =
        get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: Float.MAX_VALUE

    private fun CameraCharacteristics.supportsBackwardCompatible(): Boolean =
        get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false

    /**
     * Whether a camera can reach the 0.5x ultra-wide field of view by zoom
     * ratio, i.e. the viewfinder's live 0.5x/1x toggle would actually work on
     * it with no gap in the footage.
     */
    private fun CameraCharacteristics.reachesUltraWide(): Boolean =
        (zoomRange()?.lower ?: 1f) <= 0.6f

    fun frontCameraID(manager: CameraManager): String? =
        manager.cameraIdList.firstOrNull { id ->
            val characteristics = manager.characteristicsOrNull(id) ?: return@firstOrNull false
            characteristics.facing() == CameraCharacteristics.LENS_FACING_FRONT &&
                characteristics.supportsBackwardCompatible()
        }

    private fun rearCameras(manager: CameraManager): List<Pair<String, CameraCharacteristics>> =
        manager.cameraIdList.mapNotNull { id ->
            val characteristics = manager.characteristicsOrNull(id) ?: return@mapNotNull null
            if (characteristics.facing() != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            if (!characteristics.supportsBackwardCompatible()) return@mapNotNull null
            id to characteristics
        }

    /**
     * Rear cameras to try, best first, honouring the user's lens preference.
     *
     * A camera that can cross to 0.5x by zoom ratio always wins when 0.5x is
     * wanted, because it gives the gapless live lens toggle. A dedicated
     * ultra-wide sensor is the fallback: it starts at 0.5x but cannot be
     * switched live, so the viewfinder hides the lens control on it.
     */
    fun rearCandidates(manager: CameraManager): List<RearCamera> {
        val cameras = rearCameras(manager)
        if (cameras.isEmpty()) return emptyList()

        val widestFocal = cameras.minOfOrNull { it.second.minFocalLength() } ?: Float.MAX_VALUE
        val defaultID = cameras.firstOrNull()?.first

        val described = cameras.map { (id, characteristics) ->
            val crosses = characteristics.reachesUltraWide()
            // A back camera whose widest focal length is the shortest on the
            // device, and that is not the default camera, is the ultra-wide.
            val dedicatedUltraWide = !crosses &&
                characteristics.minFocalLength() <= widestFocal * 1.05f &&
                id != defaultID
            RearCamera(id, characteristics, crosses, dedicatedUltraWide)
        }

        val wideOnly = described.filter { !it.isDedicatedUltraWide }
        return when (AppSettings.lensPreference) {
            // Prefer the crossover camera, then a fixed ultra-wide, then plain wide.
            LensPreference.AUTO, LensPreference.ULTRA_WIDE ->
                described.sortedWith(
                    compareByDescending<RearCamera> { it.canCrossToUltraWide }
                        .thenByDescending { it.isDedicatedUltraWide }
                )
            // Still prefer a crossover camera so the live control can reach
            // 0.5x if the user wants it, but recording starts at 1x.
            LensPreference.WIDE ->
                wideOnly.sortedByDescending { it.canCrossToUltraWide }.ifEmpty { described }
        }
    }

    /** The lens multiplier each recording should start at. */
    fun startingLens(): Double =
        if (AppSettings.lensPreference == LensPreference.WIDE) 1.0 else 0.5

    /**
     * Maps a Camera-app lens multiplier (0.5 / 1.0) onto the device's
     * `CONTROL_ZOOM_RATIO`. Android's zoom ratio *is* the multiplier, so this
     * only has to clamp into what the camera actually supports.
     */
    fun zoomRatio(multiplier: Double, characteristics: CameraCharacteristics): Float {
        val range = characteristics.zoomRange() ?: return 1f
        return multiplier.toFloat().coerceIn(range.lower, range.upper)
    }

    /**
     * Best recording size for a camera, near [targetHeight].
     *
     * By default a camera prefers true 16:9: without the aspect preference the
     * closest-by-height match is often 4:3, which yields squarish footage
     * rather than HD. That is used where the output shape matters more than
     * the view - the concurrent dual-camera streams.
     *
     * A camera asked for its widest field of view prefers 4:3 instead, which
     * on nearly every phone is the sensor's own shape - a 16:9 size is that
     * sensor cropped. The front camera always does this, and so does a single
     * rear camera. The crop is felt hardest when the phone is held sideways: the sensor
     * delivers one fixed rectangle either way, so turning the phone turns a
     * tall frame into a short one, and a face goes from filling a fraction of
     * the height to filling most of it. At 16:9 that is a 1.78x jump between
     * orientations; at 4:3 it is 1.33x.
     *
     * It cannot be removed altogether without a square image, which no phone
     * sensor delivers and which rotating a rectangular stream cannot produce.
     * The two cameras record to separate files, so nothing downstream cares
     * that they disagree about shape.
     */
    fun recordingSize(
        characteristics: CameraCharacteristics,
        targetHeight: Int = 1080,
        widestFieldOfView: Boolean = false,
    ): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1280, 720)
        val sizes = map.encoderSizes().ifEmpty { return Size(1280, 720) }

        val preferredAspect = if (widestFieldOfView) 4.0 / 3.0 else 16.0 / 9.0
        return sizes.minWithOrNull(
            compareBy<Size>(
                { if (abs(it.width.toDouble() / it.height - preferredAspect) < 0.05) 0 else 1 },
                { abs(it.height - targetHeight) },
            )
        ) ?: Size(1280, 720)
    }

    /**
     * The camera stream stealth landscape records from: the tallest-quality
     * 4:3 frame the camera can hand a [android.graphics.SurfaceTexture], whose
     * short side - the width of the strip cut from it - is as close to
     * [maxShortSide] as it can get without going over. Null when the camera
     * has no 4:3 size at all, in which case there is nothing to cut a strip
     * from.
     *
     * Looked up against the sizes a texture can take, not the encoder's: the
     * strip is what goes to the encoder, the full frame never does.
     */
    fun stealthCameraSize(characteristics: CameraCharacteristics, maxShortSide: Int): Size? {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList().orEmpty()
        return sizes
            .filter { it.width > it.height && abs(it.width.toDouble() / it.height - 4.0 / 3.0) < 0.05 }
            .filter { it.height in 1080..maxShortSide }
            .maxByOrNull { it.height }
    }

    /**
     * The encoded size of a landscape strip cut from an upright frame whose
     * short side is [shortSide]: that wide, 16:9 tall, both even - encoders
     * reject odd dimensions.
     */
    fun stripSize(shortSide: Int): Size {
        val width = shortSide and 1.inv()
        return Size(width, (width * 9 / 16) and 1.inv())
    }

    /**
     * Buffer size to run the viewfinder at.
     *
     * Chosen from the sizes the camera actually offers a [SurfaceTexture], and
     * matched to the recording's shape so the preview frames the same scene the
     * file will. Capped around 1080p on the long edge: the viewfinder is a
     * viewfinder, and a larger buffer costs bandwidth and heat during a
     * recording without being visible on any phone screen.
     */
    fun previewSize(characteristics: CameraCharacteristics, recording: Size): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1280, 720)
        val sizes = map.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.toList().orEmpty().ifEmpty { return Size(1280, 720) }

        val wanted = recording.width.toDouble() / recording.height
        return sizes
            .filter { it.width <= 1920 && it.height <= 1920 }
            .ifEmpty { sizes }
            .minWithOrNull(
                compareBy<Size>(
                    { if (abs(it.width.toDouble() / it.height - wanted) < 0.05) 0 else 1 },
                    { abs(it.width - 1280) },
                )
            ) ?: Size(1280, 720)
    }

    private fun StreamConfigurationMap.encoderSizes(): List<Size> =
        (getOutputSizes(MediaCodec::class.java) ?: getOutputSizes(ImageFormat.PRIVATE))
            ?.toList().orEmpty()

    /**
     * A back/front camera pair the device can genuinely stream at the same
     * time, or null if it cannot. Android publishes this explicitly rather
     * than requiring a trial configuration, so an unsupported device degrades
     * to single-camera without ever opening a session it cannot honour.
     */
    fun concurrentPair(manager: CameraManager, preferredRear: List<RearCamera>): Pair<String, String>? {
        val combinations = runCatching { manager.concurrentCameraIds }.getOrNull().orEmpty()
        if (combinations.isEmpty()) return null

        val front = frontCameraID(manager) ?: return null
        // Honour the lens preference order: the first rear camera that appears
        // in a supported concurrent set alongside the front camera wins.
        for (rear in preferredRear) {
            val supported = combinations.any { it.contains(rear.cameraID) && it.contains(front) }
            if (supported) return rear.cameraID to front
        }
        return null
    }

    /**
     * Concurrent streaming is only guaranteed up to 720p per camera, so
     * dual-camera sessions cap there. Single-camera recordings stay at 1080p.
     */
    const val CONCURRENT_TARGET_HEIGHT = 720

    /**
     * Whether HLG10 10-bit HDR is captured by [characteristics], via the
     * dynamic-range profiles Android publishes from API 33 - the public,
     * vendor-neutral capability closest to the iOS build's Apple Log; see
     * [AppSettings.hdrRecordingEnabled] for why HDR rather than a flat Log
     * curve is what Pro offers here.
     */
    fun supportsHdr(characteristics: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val profiles = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            ?: return false
        return profiles.supportedProfiles.contains(DynamicRangeProfiles.HLG10)
    }

    /**
     * What to ask a camera for, to deliver a stabilization choice.
     *
     * [electronic] is the `CONTROL_VIDEO_STABILIZATION_MODE` to set (null: leave
     * it alone), [optical] whether to switch the lens-shift stabilizer on, and
     * [used] the choice this actually amounts to - which is the nearest one the
     * camera can do when it cannot do the one asked for, so the page can say so
     * rather than look broken.
     */
    data class StabilizationPlan(
        val electronic: Int?,
        val optical: Boolean,
        val opticalOff: Boolean,
        val used: AppSettings.Stabilization,
    )

    fun stabilizationPlan(
        characteristics: CameraCharacteristics,
        choice: AppSettings.Stabilization,
    ): StabilizationPlan {
        val electronicModes = characteristics
            .get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toSet().orEmpty()
        val hasElectronic = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in electronicModes
        val hasPreview = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION in electronicModes
        val hasOptical = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

        // Standard is what every recording used before there was a choice:
        // the lens-shift stabilizer where there is one, else the electronic one.
        val standard = when {
            hasOptical -> StabilizationPlan(null, true, false, AppSettings.Stabilization.STANDARD)
            hasElectronic -> StabilizationPlan(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON, false, false,
                AppSettings.Stabilization.STANDARD,
            )
            else -> StabilizationPlan(null, false, false, AppSettings.Stabilization.STANDARD)
        }
        val cinematic = if (hasElectronic) {
            StabilizationPlan(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON, false, false,
                AppSettings.Stabilization.CINEMATIC,
            )
        } else standard

        return when (choice) {
            AppSettings.Stabilization.OFF -> StabilizationPlan(
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF, false, hasOptical,
                AppSettings.Stabilization.OFF,
            )
            AppSettings.Stabilization.STANDARD -> standard
            AppSettings.Stabilization.CINEMATIC -> cinematic
            AppSettings.Stabilization.EXTREME -> if (hasPreview) {
                StabilizationPlan(
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION, false, false,
                    AppSettings.Stabilization.EXTREME,
                )
            } else cinematic
        }
    }

    /** The choices this camera can do as asked (the rest fall back to a nearer one). */
    fun stabilizationSupport(characteristics: CameraCharacteristics): Set<AppSettings.Stabilization> =
        AppSettings.Stabilization.entries.filter {
            stabilizationPlan(characteristics, it).used == it
        }.toSet()

    /** Whether *any* rear camera on this device can capture HLG10. */
    fun deviceSupportsHdr(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val manager = WitnessApplication.appContext
            .getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        return rearCameras(manager).any { (_, characteristics) -> supportsHdr(characteristics) }
    }
}
