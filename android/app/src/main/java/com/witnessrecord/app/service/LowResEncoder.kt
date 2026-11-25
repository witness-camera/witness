package com.witnessrecord.app.service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Produces the small stand-in copy of a chunk that is sent ahead of the
 * full-quality file when the connection is failing.
 *
 * The point is arrival, not fidelity: a recognisable copy that reaches the
 * backend during the encounter is worth more than a pristine one still
 * sitting on a phone that may be taken. The full file follows and replaces
 * it as soon as the network allows.
 *
 * The video track is decoded straight onto the encoder's input surface -
 * Android wires the two `Surface`s together at the OS level, so no manual
 * GL rendering is needed - and re-encoded at a small fixed bit rate. The
 * audio track is copied sample-for-sample rather than decoded and
 * re-encoded, the same technique [SessionStitcher] uses to merge chunks.
 *
 * Unlike the iOS build's stand-in, which is spatially downscaled to
 * 640x480, this keeps the source resolution and shrinks the file purely by
 * bit rate. Resampling *pixels* down would need a GL scaling pass between
 * the two `Surface`s - real, well-trodden code, but real EGL/GL state
 * machinery that is very easy to get subtly wrong and had no device
 * available here to verify it against. A same-resolution, heavily
 * bit-rate-reduced re-encode is a smaller engineering surface, and it
 * delivers the same behaviour that matters: a small file that reaches the
 * backend fast on a struggling connection.
 */
object LowResEncoder {

    /** ~330 kbps: comparable to the data the iOS stand-in's spatial downscale achieves. */
    private const val BIT_RATE = 330_000
    private const val I_FRAME_INTERVAL = 2
    private const val TIMEOUT_US = 10_000L

    class EncodeException(message: String) : IOException(message)

    fun lowResFileName(masterFileName: String): String {
        val stem = File(masterFileName).nameWithoutExtension
        return "${stem}_lowres.mp4"
    }

    /** Transcodes [source] to a small MP4 at [destination]. */
    suspend fun encode(source: File, destination: File): Unit = withContext(Dispatchers.Default) {
        destination.delete()

        val probe = MediaExtractor()
        probe.setDataSource(source.absolutePath)
        val videoTrack = (0 until probe.trackCount).firstOrNull {
            probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: run { probe.release(); throw EncodeException("No video track in $source") }
        val sourceVideoFormat = probe.getTrackFormat(videoTrack)
        val sourceMime = sourceVideoFormat.getString(MediaFormat.KEY_MIME)!!
        val width = sourceVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = sourceVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)
        probe.release()

        val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()
        encoder.start()

        // The decoder's output *is* the encoder's input: Android pipes frames
        // between the two `Surface`s directly, with no application-visible
        // buffer to touch.
        val decoder = MediaCodec.createDecoderByType(sourceMime)
        decoder.configure(sourceVideoFormat, encoderInputSurface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var muxerVideoTrack = -1

        try {
            transcodeVideo(
                source = source,
                trackIndex = videoTrack,
                decoder = decoder,
                encoder = encoder,
                onFormat = { format ->
                    muxerVideoTrack = muxer.addTrack(format)
                    muxerStarted = true
                    muxer.start()
                },
                onSample = { info, buffer ->
                    if (muxerStarted) muxer.writeSampleData(muxerVideoTrack, buffer, info)
                },
            )
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { encoderInputSurface.release() }
        }

        copyAudioTrack(source, muxer, muxerStarted)

        runCatching { muxer.stop() }
        runCatching { muxer.release() }

        if (!destination.exists() || destination.length() == 0L) {
            destination.delete()
            throw EncodeException("Low-res encode produced no output")
        }
    }

    /**
     * Drives the decode -> (implicit surface hand-off) -> encode -> mux loop
     * for the video track. `onFormat` fires once, when the encoder publishes
     * its output format, which is also when the muxer can finally start -
     * MediaMuxer requires every track to be added before the first sample is
     * written.
     */
    private fun transcodeVideo(
        source: File,
        trackIndex: Int,
        decoder: MediaCodec,
        encoder: MediaCodec,
        onFormat: (MediaFormat) -> Unit,
        onSample: (MediaCodec.BufferInfo, ByteBuffer) -> Unit,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        extractor.selectTrack(trackIndex)

        var extractorDone = false
        var decoderDone = false
        var encoderDone = false
        val info = MediaCodec.BufferInfo()

        while (!encoderDone) {
            // Feed the decoder.
            if (!extractorDone) {
                val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inputIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractorDone = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Drain the decoder straight onto the encoder's input surface.
            if (!decoderDone) {
                when (val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, info.size > 0)
                        if (eos) {
                            decoderDone = true
                            encoder.signalEndOfInputStream()
                        }
                    }
                }
            }

            // Drain the encoder into the muxer.
            when (val outputIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(encoder.outputFormat)
                else -> if (outputIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buffer = encoder.getOutputBuffer(outputIndex)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        onSample(info, buffer)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (eos) encoderDone = true
                }
            }
        }
        extractor.release()
    }

    /**
     * Copies the source's audio track sample-for-sample into [muxer], adding
     * the track first if the video pass hasn't started the muxer yet (a
     * video-only source, in principle).
     */
    private fun copyAudioTrack(source: File, muxer: MediaMuxer, muxerAlreadyStarted: Boolean) {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.absolutePath)
        val audioTrack = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (audioTrack == null) {
            extractor.release()
            return
        }

        var started = muxerAlreadyStarted
        val muxerAudioTrack = muxer.addTrack(extractor.getTrackFormat(audioTrack))
        if (!started) {
            muxer.start()
            started = true
        }

        extractor.selectTrack(audioTrack)
        val buffer = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            // MediaExtractor and MediaCodec use different flag vocabularies;
            // translate rather than pass the extractor's bitmask straight
            // through - see SessionStitcher.merge for the same fix.
            val isSync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            info.set(0, size, extractor.sampleTime, if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(muxerAudioTrack, buffer, info)
            extractor.advance()
        }
        extractor.release()
    }
}
