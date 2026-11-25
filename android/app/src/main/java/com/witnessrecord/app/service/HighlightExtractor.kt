package com.witnessrecord.app.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Produces a shorter "highlights" cut of a session's rear-camera video: the
 * stretches where someone is actually talking, at ordinary conversational
 * volume, with the quiet dead air in between trimmed out. The Android
 * counterpart of the iOS build's `HighlightExtractor`, tuned the same way.
 *
 * Deliberately tuned to catch normal speech, not just shouting or sudden loud
 * spikes - most of what an encounter recording needs to keep is ordinary
 * conversation, and a threshold that only fired on loud moments would
 * silently drop everything that matters most.
 *
 * Unlike the iOS build's composition export, nothing is re-encoded: the kept
 * stretches are copied sample-for-sample into a new file. That makes it fast
 * and lossless, at one cost - video can only be cut on a keyframe, so each
 * kept stretch may begin a moment before the speech does (never after it).
 */
object HighlightExtractor {

    /**
     * How long each analysis window is. Short enough that a segment's
     * boundaries land close to where speech actually starts and stops, long
     * enough that a window's level is a meaningful average rather than noise
     * from a single sample.
     */
    private const val WINDOW_SECONDS = 0.5

    /**
     * How much louder than this recording's own quiet stretches counts as
     * speech. A fixed offset above an adaptive floor, not an absolute volume,
     * because mic gain and distance from the speaker both shift what "quiet"
     * and "talking" measure as from one recording to the next.
     */
    private const val ACTIVE_MARGIN_DB = 8f

    /** Kept on each side of an active stretch so a word at the edge isn't clipped. */
    private const val PADDING_SECONDS = 1.0

    /** Two stretches closer than this join into one instead of jump-cutting. */
    private const val MERGE_GAP_SECONDS = 2.5

    /** What a flagged moment keeps either side of it, whether or not anyone is talking. */
    private const val MARK_BEFORE_SECONDS = 8.0
    private const val MARK_AFTER_SECONDS = 5.0

    /** Shorter than this is almost certainly a stray click, not speech. */
    private const val MINIMUM_SEGMENT_SECONDS = 1.0

    private const val TIMEOUT_US = 10_000L

    /**
     * Thrown when nothing in the recording ever rose above its own ambient
     * floor - silence throughout, or no audio track at all. Not a failure:
     * there is simply no shorter cut worth producing.
     */
    class NoActivity : Exception("No stretch of this recording stands out from its own quiet")

    /** One kept stretch, in microseconds on the source's timeline. */
    private data class Segment(val startUs: Long, val endUs: Long)

    /**
     * Builds [outputFile] from [source]. Blocks; callers run it on a background
     * thread. [marks] are seconds into the recording that the person flagged;
     * the cut always keeps a few seconds either side of each, talking or not.
     */
    fun build(source: File, outputFile: File, marks: List<Double> = emptyList()) {
        val segments = activeSegments(source, marks)
        if (segments.isEmpty()) throw NoActivity()

        // Written to a partial name and moved into place, like every other
        // output in this pipeline: the final file's presence is what means the
        // export actually finished.
        val partial = File(outputFile.parentFile, outputFile.name + ".partial")
        partial.delete()
        outputFile.delete()
        try {
            copySegments(source, partial, segments)
            if (!partial.renameTo(outputFile)) throw IllegalStateException("Could not move highlights into place")
        } finally {
            partial.delete()
        }
    }

    // MARK: - Analysis

    /**
     * Reads the audio track, scores each half-second window's loudness in
     * decibels, and keeps whichever windows sit clearly above this recording's
     * own quiet stretches.
     */
    private fun activeSegments(source: File, marks: List<Double>): List<Segment> {
        val levels = windowLevels(source)
        if (levels.isEmpty()) return emptyList()

        // The 20th-percentile level stands in for "this recording's own
        // ambient quiet" - adaptive to this specific mic gain and background
        // noise, rather than a fixed number that would be too strict in a loud
        // room and too loose in a silent one.
        val floor = percentile(levels.sorted(), 0.2)
        val threshold = floor + ACTIVE_MARGIN_DB
        val active = levels.map { it >= threshold }

        val ranges = mutableListOf<Pair<Double, Double>>()
        var i = 0
        while (i < active.size) {
            if (active[i]) {
                val start = i * WINDOW_SECONDS
                var j = i
                while (j < active.size && active[j]) j++
                ranges.add(start to j * WINDOW_SECONDS)
                i = j
            } else {
                i++
            }
        }
        val durationSeconds = levels.size * WINDOW_SECONDS
        for (mark in marks) {
            if (mark >= 0 && mark <= durationSeconds) {
                ranges.add(max(0.0, mark - MARK_BEFORE_SECONDS) to (mark + MARK_AFTER_SECONDS))
            }
        }
        ranges.sortBy { it.first }
        if (ranges.isEmpty()) return emptyList()

        val padded = ranges.map {
            max(0.0, it.first - PADDING_SECONDS) to min(durationSeconds, it.second + PADDING_SECONDS)
        }

        val merged = mutableListOf(padded[0])
        for (range in padded.drop(1)) {
            val last = merged.last()
            if (range.first - last.second <= MERGE_GAP_SECONDS) {
                merged[merged.size - 1] = last.first to max(last.second, range.second)
            } else {
                merged.add(range)
            }
        }

        return merged
            .filter { it.second - it.first >= MINIMUM_SEGMENT_SECONDS }
            .map { Segment((it.first * 1_000_000).toLong(), (it.second * 1_000_000).toLong()) }
    }

    /** Loudness, in dBFS, of each consecutive window of the source's audio. Empty if there is no audio. */
    private fun windowLevels(source: File): List<Float> {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return emptyList()
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()

            var samplesPerWindow = 0
            val levels = mutableListOf<Float>()
            var sumSquares = 0.0
            var inWindow = 0

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
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
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outputIndex >= 0 -> {
                        if (samplesPerWindow == 0) {
                            // Known only once the decoder has produced its
                            // output format; interleaved channels count as one
                            // stream of samples, which scores the same as the
                            // average of them.
                            val out = codec.outputFormat
                            val rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            samplesPerWindow = max(1, (rate * channels * WINDOW_SECONDS).toInt())
                        }
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                            while (shorts.hasRemaining()) {
                                val sample = shorts.get() / 32768.0
                                sumSquares += sample * sample
                                inWindow++
                                if (inWindow >= samplesPerWindow) {
                                    levels.add(dbfs(sumSquares, inWindow))
                                    sumSquares = 0.0
                                    inWindow = 0
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (inWindow > 0) levels.add(dbfs(sumSquares, inWindow))
            return levels
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    private fun dbfs(sumSquares: Double, count: Int): Float {
        if (count <= 0) return -120f
        val rms = sqrt(sumSquares / count)
        if (rms <= 0.0) return -120f
        return (20 * log10(rms)).toFloat()
    }

    private fun percentile(sorted: List<Float>, p: Double): Float {
        if (sorted.isEmpty()) return -120f
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    // MARK: - Assembly

    /**
     * Copies each kept stretch's video and audio samples into [output],
     * laid end to end on a fresh timeline. Each stretch starts at the video
     * keyframe at or just before its start, so it is always decodable from its
     * first frame.
     */
    private fun copySegments(source: File, output: File, segments: List<Segment>) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            videoExtractor.setDataSource(source.absolutePath)
            audioExtractor.setDataSource(source.absolutePath)

            val videoTrack = (0 until videoExtractor.trackCount).first {
                videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val audioTrack = (0 until audioExtractor.trackCount).firstOrNull {
                audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            videoExtractor.selectTrack(videoTrack)
            audioTrack?.let { audioExtractor.selectTrack(it) }

            val newMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = newMuxer

            // The rotation rides in the container, so it has to be carried
            // across or the cut plays on its side.
            val retriever = MediaMetadataRetriever()
            val rotation = try {
                retriever.setDataSource(source.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }
            newMuxer.setOrientationHint(rotation)

            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val muxVideo = newMuxer.addTrack(videoFormat)
            val muxAudio = audioTrack?.let { newMuxer.addTrack(audioExtractor.getTrackFormat(it)) }
            newMuxer.start()
            muxerStarted = true

            val bufferSize = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                max(videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 2 * 1024 * 1024)
            } else {
                4 * 1024 * 1024
            }
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()

            var cursorUs = 0L
            for (segment in segments) {
                videoExtractor.seekTo(segment.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val firstVideo = videoExtractor.sampleTime
                if (firstVideo < 0) continue
                val startUs = min(firstVideo, segment.startUs)
                if (audioTrack != null) {
                    audioExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    // Closest may land a frame early; audio before the
                    // video's own start would play over nothing.
                    while (audioExtractor.sampleTime in 0 until startUs) {
                        if (!audioExtractor.advance()) break
                    }
                }

                // Interleaved by time, so the file plays and seeks smoothly.
                while (true) {
                    val videoTime = videoExtractor.sampleTime
                    val audioTime = if (audioTrack != null) audioExtractor.sampleTime else -1L
                    val videoReady = videoTime in 0 until segment.endUs
                    val audioReady = audioTime in 0 until segment.endUs
                    if (!videoReady && !audioReady) break

                    val takeVideo = videoReady && (!audioReady || videoTime <= audioTime)
                    val extractor = if (takeVideo) videoExtractor else audioExtractor
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val time = if (takeVideo) videoTime else audioTime
                    info.set(
                        0, size, cursorUs + (time - startUs),
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else 0
                    )
                    newMuxer.writeSampleData(if (takeVideo) muxVideo else muxAudio!!, buffer, info)
                    extractor.advance()
                }
                cursorUs += segment.endUs - startUs
            }
        } finally {
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }
}
