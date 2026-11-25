package com.witnessrecord.app.service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Disguises the voices in a video's audio, in place: pitched down and
 * re-shaped tonally, so speech stays intelligible but the speakers are hard to
 * recognise. Used on the publish-safe copy only - the original recording keeps
 * its real audio as evidence.
 *
 * The signal processing itself is [VoiceDisguiseDsp]. This is the plumbing
 * around it, built to hold only a few seconds of audio in memory however long
 * the recording is: the audio is decoded to a file on disk, processed in
 * overlapping blocks whose seams are crossfaded, and re-encoded from there.
 *
 * A pitch shift on its own can be partly reversed by someone shifting it back,
 * so the tone is reshaped as well; even so, this makes voices hard to
 * recognise for an ordinary listener rather than defeating specialist
 * analysis, and the setting says so.
 */
object VoiceDisguiser {

    private const val TIMEOUT_US = 10_000L

    /** Seconds processed per block, and the extra either side that is processed but discarded. */
    private const val BLOCK_SECONDS = 20
    private const val OVERLAP_SECONDS = 0.25

    /** The crossfade at each seam, in seconds. */
    private const val FADE_SECONDS = 0.02

    /** Replaces [video]'s audio with its disguised version. A video with no audio is left alone. */
    fun apply(video: File) {
        val directory = video.parentFile
        val stem = video.nameWithoutExtension
        val rawIn = File(directory, "${stem}_pcm_in.tmp")
        val rawOut = File(directory, "${stem}_pcm_out.tmp")
        val audioOnly = File(directory, "${stem}_voice.m4a.tmp")
        val remuxed = File(directory, "${stem}_remux.mp4.partial")
        val scratch = listOf(rawIn, rawOut, audioOnly, remuxed)
        scratch.forEach { it.delete() }
        try {
            val sampleRate = decodeToMonoPcm(video, rawIn) ?: return
            processFile(rawIn, rawOut, sampleRate)
            encodeAac(rawOut, sampleRate, audioOnly)
            remux(video, audioOnly, remuxed)
            if (!remuxed.exists() || remuxed.length() == 0L) throw IllegalStateException("voice remux produced nothing")
            video.delete()
            if (!remuxed.renameTo(video)) throw IllegalStateException("could not put the disguised video back")
        } finally {
            scratch.forEach { it.delete() }
        }
    }

    // MARK: - Decode

    /** The sample rate, or null when [video] has no audio. Writes 16-bit mono PCM to [out]. */
    private fun decodeToMonoPcm(video: File, out: File): Int? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(video.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            java.io.BufferedOutputStream(out.outputStream(), 1 shl 16).use { stream ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        outputIndex >= 0 -> {
                            val buffer = codec.getOutputBuffer(outputIndex)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                                val frames = shorts.remaining() / channels
                                val bytes = ByteArray(frames * 2)
                                val target = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                                for (f in 0 until frames) {
                                    var sum = 0
                                    for (c in 0 until channels) sum += shorts.get()
                                    target.putShort((sum / channels).toShort())
                                }
                                stream.write(bytes)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
            return sampleRate
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    // MARK: - Process

    /**
     * Pitch-shifts [input] block by block into [output], crossfading each seam,
     * then shapes the tone over the whole stream in one pass so the filters'
     * memory carries across.
     */
    private fun processFile(input: File, output: File, sampleRate: Int) {
        val total = (input.length() / 2).toInt()
        val block = BLOCK_SECONDS * sampleRate
        val overlap = (OVERLAP_SECONDS * sampleRate).toInt()
        val fade = max(2, (FADE_SECONDS * sampleRate).toInt())
        val tone = VoiceDisguiseDsp.Tone(sampleRate)

        RandomAccessFile(input, "r").use { reader ->
            java.io.BufferedOutputStream(output.outputStream(), 1 shl 16).use { writer ->
                // What the previous block's own processing made of the moments
                // just past its end: the same moments this block starts with,
                // so the two can be mixed across the seam without the picture
                // of the waveform jumping.
                var continuation: FloatArray? = null
                var start = 0
                while (start < total) {
                    val end = min(total, start + block)
                    val from = max(0, start - overlap)
                    val to = min(total, end + overlap)
                    val shifted = VoiceDisguiseDsp.pitchShift(
                        readFloats(reader, from, to - from), sampleRate, VoiceDisguiseDsp.pitchRatio,
                    )

                    // This block's own span within [shifted].
                    val own = start - from
                    val ownEnd = own + (end - start)
                    val body = shifted.copyOfRange(own, min(shifted.size, ownEnd))
                    continuation?.let { before ->
                        val n = min(fade, min(before.size, body.size))
                        for (i in 0 until n) {
                            val weight = (i + 1f) / (n + 1f)
                            body[i] = before[i] * (1f - weight) + body[i] * weight
                        }
                    }
                    continuation = if (to > end) {
                        shifted.copyOfRange(min(shifted.size, ownEnd), min(shifted.size, ownEnd + fade))
                    } else null

                    writePcm(writer, body, tone)
                    start = end
                }
            }
        }
    }

    private fun readFloats(reader: RandomAccessFile, from: Int, count: Int): FloatArray {
        val bytes = ByteArray(count * 2)
        reader.seek(from * 2L)
        reader.readFully(bytes)
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(count) { shorts.get(it) / 32768f }
    }

    private fun writePcm(writer: java.io.OutputStream, samples: FloatArray, tone: VoiceDisguiseDsp.Tone) {
        tone.process(samples)
        val bytes = ByteArray(samples.size * 2)
        val target = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (value in samples) target.putShort((value * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        writer.write(bytes)
    }

    // MARK: - Encode

    /** Encodes 16-bit mono PCM from [pcm] to AAC in an audio-only MP4 at [out]. */
    private fun encodeAac(pcm: File, sampleRate: Int, out: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        var samplesFed = 0L
        var inputDone = false
        var outputDone = false
        try {
            RandomAccessFile(pcm, "r").use { reader ->
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = encoder.getInputBuffer(inputIndex)!!
                            buffer.clear()
                            val chunk = ByteArray(min(buffer.capacity(), 16 * 1024))
                            val read = reader.read(chunk)
                            if (read <= 0) {
                                encoder.queueInputBuffer(
                                    inputIndex, 0, 0, samplesFed * 1_000_000L / sampleRate,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                buffer.put(chunk, 0, read)
                                encoder.queueInputBuffer(inputIndex, 0, read, samplesFed * 1_000_000L / sampleRate, 0)
                                samplesFed += read / 2
                            }
                        }
                    }
                    val outputIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            started = true
                        }
                        outputIndex >= 0 -> {
                            val buffer = encoder.getOutputBuffer(outputIndex)
                            if (buffer != null && info.size > 0 && started &&
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            encoder.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    // MARK: - Remux

    /** Writes [video]'s picture untouched together with [audio]'s sound, to [out]. */
    private fun remux(video: File, audio: File, out: File) {
        val videoExtractor = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val videoIndex = (0 until videoExtractor.trackCount).first {
                videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val audioIndex = (0 until audioExtractor.trackCount).first {
                audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            videoExtractor.selectTrack(videoIndex)
            audioExtractor.selectTrack(audioIndex)

            val retriever = MediaMetadataRetriever()
            val rotation = try {
                retriever.setDataSource(video.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }

            val created = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = created
            created.setOrientationHint(rotation)
            val outVideo = created.addTrack(videoExtractor.getTrackFormat(videoIndex))
            val outAudio = created.addTrack(audioExtractor.getTrackFormat(audioIndex))
            created.start()
            started = true

            val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val videoTime = videoExtractor.sampleTime
                val audioTime = audioExtractor.sampleTime
                if (videoTime < 0 && audioTime < 0) break
                val takeVideo = videoTime >= 0 && (audioTime < 0 || videoTime <= audioTime)
                val extractor = if (takeVideo) videoExtractor else audioExtractor
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(
                    0, size, if (takeVideo) videoTime else audioTime,
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                )
                created.writeSampleData(if (takeVideo) outVideo else outAudio, buffer, info)
                extractor.advance()
            }
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }
}
