package com.witnessrecord.app.service

import androidx.annotation.StringRes
import com.witnessrecord.app.R

/**
 * Capture resolution. Pro-only above 1080p.
 */
enum class VideoResolution(val wire: String, @StringRes val labelRes: Int, val width: Int, val height: Int) {
    HD1080("hd1080", R.string.resolution_hd1080, 1920, 1080),
    UHD4K("uhd4K", R.string.resolution_uhd4k, 3840, 2160);

    val requiresPro: Boolean get() = this != HD1080

    companion object {
        fun fromWire(value: String?): VideoResolution =
            entries.firstOrNull { it.wire == value } ?: HD1080
    }
}

/**
 * Capture frame rate. 30 is the free default; the cinema standards (24 in
 * the US, 25 in Europe) and 60 are Pro.
 */
enum class VideoFrameRate(val fps: Int, @StringRes val labelRes: Int) {
    FPS24(24, R.string.framerate_24),
    FPS25(25, R.string.framerate_25),
    FPS30(30, R.string.framerate_30),
    FPS60(60, R.string.framerate_60);

    val requiresPro: Boolean get() = this != FPS30

    companion object {
        fun fromWire(value: Int?): VideoFrameRate =
            entries.firstOrNull { it.fps == value } ?: FPS30
    }
}

/**
 * Recording codec.
 *
 * H.264 is the free default. HEVC is Pro and can carry the optional 10-bit
 * HDR toggle. MASTERING is a third, local-only tier: a much higher, fixed
 * bit rate than HEVC's upload-friendly one, an order of magnitude heavier -
 * at those rates live upload stops being merely slow and becomes
 * arithmetically impossible, so a mastering recording is kept on the device
 * instead of being promised an upload that could never finish.
 *
 * This is the Android counterpart of the iOS build's ProRes tier. Android has
 * no ProRes encoder anywhere, hardware or software, on any device - there is
 * no way to record the same codec. What is preserved is the *behaviour*: a
 * deliberately heavier, higher-fidelity local-only option with the same
 * upload trade-off stated the same way.
 */
enum class VideoCodec(val wire: String, @StringRes val labelRes: Int) {
    H264("h264", R.string.codec_h264),
    HEVC("hevc", R.string.codec_hevc),
    MASTERING("mastering", R.string.codec_mastering);

    val requiresPro: Boolean get() = this != H264
    val isLocalOnly: Boolean get() = this == MASTERING

    /** Whether this codec can carry the optional 10-bit HDR toggle. */
    val supportsHdr: Boolean get() = this != H264

    /** Whether a recording in this codec uploads live at all. */
    val uploadsLive: Boolean get() = !isLocalOnly

    /**
     * Approximate encoded bit rate in bits per second, used to show the user
     * what a setting actually costs before they commit to it.
     *
     * The mastering figure is a fixed, generous rate rather than the variable
     * one HEVC/H.264 use - the whole point of the tier is a rate high enough
     * that quality is never the limiting factor, the way ProRes's fixed rate
     * works on iOS.
     */
    fun estimatedBitRate(resolution: VideoResolution, frameRate: VideoFrameRate): Double {
        val pixelScale = (resolution.width.toDouble() * resolution.height) / (1920.0 * 1080.0)
        val rateScale = frameRate.fps / 30.0
        return when (this) {
            MASTERING -> 100_000_000.0 * pixelScale * rateScale
            HEVC -> 8_000_000.0 * pixelScale * rateScale * 1.6
            H264 -> 8_000_000.0 * pixelScale * rateScale
        }
    }
}
