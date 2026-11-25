package com.witnessrecord.app.capture

import android.location.Location
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.witnessrecord.app.service.EventLogger
import java.io.File
import java.nio.ByteBuffer

/**
 * Wraps one [MediaMuxer] writing a single ~15 second .mp4 chunk - the
 * counterpart of the iOS build's `AVAssetWriter`-per-chunk.
 *
 * The encoders keep running across chunk boundaries; only the muxer is
 * swapped. That is what makes rotation genuinely gapless: no encoder is torn
 * down and restarted, so no frames are lost between one chunk's last frame
 * and the next chunk's first.
 *
 * Created and driven entirely on the capture worker thread.
 */
class ChunkWriter(
    val outputFile: File,
    location: Location?,
    orientationDegrees: Int,
) {
    val startedAt: Long = System.currentTimeMillis()

    private val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var stopped = false

    /** Set once the first video sample lands, so every chunk starts at t=0. */
    private var firstVideoPtsUs: Long = -1
    private var lastVideoPtsUs: Long = -1
    private var lastWrittenPtsUs = LongArray(2) { -1 }

    /**
     * Each track's own first timestamp, indexed by the slots used in [write].
     *
     * Per track, not one shared video base, because the camera and the
     * microphone do not stamp their samples from the same clock. Measured on
     * a real device: within one chunk, video ran 0 - 15s while audio ran
     * 3886 - 3901s, the two epochs differing by just over an hour. Rebasing
     * audio onto the video base put every chunk's sound an hour past its
     * picture, which is to say there was effectively no audio at all - and it
     * then set the *container* duration to 3901s, so the session merge, which
     * takes each input's last timestamp as its length, stacked those into a
     * three-hour video of a one-minute recording.
     *
     * Rebasing each track on itself costs the sub-frame alignment between
     * them. That is bounded by a single audio buffer - about 23ms at 44.1kHz -
     * because audio is only admitted once this chunk's first video frame has
     * been written, so the two first samples are separated by one buffer at
     * most. An imperceptible offset, against sound that was previously lost.
     */
    private var firstPtsUs = LongArray(2) { -1 }

    /** Whether any video sample has actually been written. */
    var hasVideo = false
        private set

    init {
        // GPS and orientation go into the container, exactly like the iOS
        // build's metadata items - no burned-in overlay, the frame stays clean.
        if (location != null) {
            runCatching {
                muxer.setLocation(location.latitude.toFloat(), location.longitude.toFloat())
            }
        }
        runCatching { muxer.setOrientationHint(orientationDegrees) }
    }

    /**
     * Seconds of video written so far, used by the controller to decide when
     * to rotate to the next chunk.
     */
    val elapsedSeconds: Double
        get() {
            if (firstVideoPtsUs < 0 || lastVideoPtsUs < 0) return 0.0
            return (lastVideoPtsUs - firstVideoPtsUs) / 1_000_000.0
        }

    fun addVideoTrack(format: MediaFormat) {
        if (started || videoTrack >= 0) return
        videoTrack = muxer.addTrack(format)
    }

    fun addAudioTrack(format: MediaFormat) {
        if (started || audioTrack >= 0) return
        audioTrack = muxer.addTrack(format)
    }

    /**
     * Starts the muxer once its tracks are declared. A chunk with no audio
     * track (microphone unavailable) is still perfectly valid video, so audio
     * is not required here.
     */
    fun startIfReady(): Boolean {
        if (started) return true
        if (videoTrack < 0) return false
        return runCatching {
            muxer.start()
            started = true
            true
        }.getOrElse {
            EventLogger.log("chunk.muxer_start_failed", detail = it.message)
            false
        }
    }

    val isStarted: Boolean get() = started

    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || stopped || videoTrack < 0) return
        if (firstVideoPtsUs < 0) firstVideoPtsUs = info.presentationTimeUs
        lastVideoPtsUs = info.presentationTimeUs
        write(videoTrack, 0, buffer, info)
        hasVideo = true
    }

    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || stopped || audioTrack < 0) return
        // Audio that arrives before this chunk's first video frame belongs to
        // the previous chunk's tail; dropping it keeps the two tracks aligned
        // rather than prepending sound to a picture that hasn't started.
        if (firstVideoPtsUs < 0) return
        write(audioTrack, 1, buffer, info)
    }

    /**
     * Rebases the sample onto this chunk's own timeline and writes it.
     * MediaMuxer requires per-track presentation timestamps to be
     * non-decreasing; a duplicate or out-of-order timestamp is nudged forward
     * by a microsecond rather than being allowed to abort the whole chunk.
     */
    private fun write(track: Int, slot: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (info.size <= 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return

        // Against this track's own first sample; see [firstPtsUs].
        if (firstPtsUs[slot] < 0) firstPtsUs[slot] = info.presentationTimeUs
        var pts = info.presentationTimeUs - firstPtsUs[slot]
        if (pts < 0) pts = 0
        if (pts <= lastWrittenPtsUs[slot]) pts = lastWrittenPtsUs[slot] + 1
        lastWrittenPtsUs[slot] = pts

        val rebased = MediaCodec.BufferInfo().apply {
            set(info.offset, info.size, pts, info.flags)
        }
        runCatching { muxer.writeSampleData(track, buffer, rebased) }
            .onFailure { EventLogger.log("chunk.write_failed", detail = it.message) }
    }

    /**
     * Finalizes the file. Returns the end wall-clock time, or null if the
     * chunk was empty or failed - in which case the stub file is removed, so
     * an empty chunk never enters the upload queue or the manifest.
     */
    fun finish(): Long? {
        if (stopped) return null
        stopped = true
        val endedAt = System.currentTimeMillis()

        if (!started || !hasVideo) {
            runCatching { muxer.release() }
            outputFile.delete()
            return null
        }

        val ok = runCatching {
            muxer.stop()
            muxer.release()
        }.isSuccess

        if (!ok || !outputFile.exists() || outputFile.length() == 0L) {
            runCatching { muxer.release() }
            outputFile.delete()
            return null
        }
        return endedAt
    }
}
