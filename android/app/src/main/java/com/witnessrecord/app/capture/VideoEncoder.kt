package com.witnessrecord.app.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import com.witnessrecord.app.service.EventLogger
import com.witnessrecord.app.service.VideoCodec
import kotlin.concurrent.thread

/**
 * One camera's H.264/HEVC encoder, fed directly from the camera through an
 * input [Surface] so frames never round-trip through application memory.
 *
 * The encoder is created once per recording and runs across every chunk
 * boundary - chunk rotation swaps the muxer underneath it. That is why a
 * rotation costs no frames, unlike stopping and restarting a recorder.
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    frameRate: Int = 30,
    codec: VideoCodec = VideoCodec.H264,
    /**
     * Whether the camera is feeding this encoder 10-bit HLG10 frames - set
     * only when [com.witnessrecord.app.capture.CameraSelection.deviceSupportsHdr]
     * gated it on and the output's dynamic range profile was set accordingly.
     * The encoder input is still a `Surface`, so this only changes which
     * profile the encoder is configured to expect; no pixel format handling
     * is needed here.
     */
    hdr: Boolean = false,
    private val sink: EncoderSink,
) {
    /**
     * Bit rate scales with pixel count and frame rate off the codec's own
     * baseline (see [VideoCodec.estimatedBitRate]), matching what the Pro
     * settings page shows the user before they record.
     */
    private val bitRate: Int = run {
        val resolution = com.witnessrecord.app.service.VideoResolution.entries
            .minByOrNull { kotlin.math.abs(it.width.toLong() * it.height - width.toLong() * height) }
            ?: com.witnessrecord.app.service.VideoResolution.HD1080
        val frameRateOption = com.witnessrecord.app.service.VideoFrameRate.fromWire(frameRate)
        codec.estimatedBitRate(resolution, frameRateOption).toInt()
            .coerceIn(2_000_000, 150_000_000)
    }

    private val mime = if (codec == VideoCodec.H264) MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC
    private val mediaCodec: MediaCodec = MediaCodec.createEncoderByType(mime)
    val inputSurface: Surface

    @Volatile private var draining = false
    private var drainThread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            // One keyframe a second: a chunk rotation can only begin on a
            // keyframe, so this bounds how long a rotation has to wait.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            if (hdr) {
                // Signals the container's transfer characteristics as HLG, so
                // playback and grading tools treat the footage as the wide
                // range it actually captured, rather than as standard-range
                // footage that merely looks washed out.
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            }
        }

        val profile = when {
            hdr && mime == MediaFormat.MIMETYPE_VIDEO_HEVC -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
            else -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        }
        // Falls back to the plainest profile the codec offers if the
        // requested one is rejected, rather than failing configuration
        // outright - a lower profile is a smaller loss than not recording.
        runCatching {
            format.setInteger(MediaFormat.KEY_PROFILE, profile)
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }.onFailure {
            format.setInteger(
                MediaFormat.KEY_PROFILE,
                if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            mediaCodec.reset()
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = mediaCodec.createInputSurface()
    }

    fun start() {
        mediaCodec.start()
        draining = true
        drainThread = thread(name = "witness-video-encoder", isDaemon = true) { drainLoop() }
    }

    /**
     * Asks the encoder for an immediate keyframe. A new chunk can only start
     * on one, so this is what keeps a rotation from waiting out the full
     * keyframe interval.
     */
    fun requestKeyFrame() {
        runCatching {
            mediaCodec.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (draining) {
            // A throwing dequeue means the codec is gone; the loop has to end
            // rather than spin. Kotlin will not let `break` cross the inline
            // lambda boundary, so the failure is carried out as a null.
            val index = try {
                mediaCodec.dequeueOutputBuffer(info, 10_000)
            } catch (error: Throwable) {
                EventLogger.log("encoder.video_drain_failed", detail = error.message)
                return
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink.onFormat(mediaCodec.outputFormat)
                index >= 0 -> {
                    val buffer = mediaCodec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        sink.onSample(buffer, info)
                    }
                    runCatching { mediaCodec.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun stop() {
        draining = false
        drainThread?.join(1_500)
        drainThread = null
        runCatching { mediaCodec.stop() }
        runCatching { mediaCodec.release() }
        runCatching { inputSurface.release() }
    }
}
