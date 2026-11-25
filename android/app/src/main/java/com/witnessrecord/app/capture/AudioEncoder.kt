package com.witnessrecord.app.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.SystemClock
import com.witnessrecord.app.service.EventLogger
import kotlin.concurrent.thread

/**
 * Microphone capture and AAC encoding, at the same 44.1 kHz mono / 96 kbps the
 * iOS build writes - so a recording sounds identical whichever platform made
 * it.
 *
 * Like the video encoder, this runs continuously for the whole recording and
 * is never restarted at a chunk boundary. In dual-camera mode the single
 * audio stream is written into *both* cameras' chunks, so each camera's file
 * is independently watchable - matching the iOS build, where one audio output
 * feeds both writers.
 */
class AudioEncoder(
    private val sink: EncoderSink,
    /**
     * Whether the camera stamps frames with `elapsedRealtime`. Audio
     * timestamps must come off the same clock as the camera's, or the two
     * tracks drift apart inside the muxer.
     */
    private val useRealtimeClock: Boolean,
) {
    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 1
        private const val BIT_RATE = 96_000
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private var recorder: AudioRecord? = null

    @Volatile private var running = false
    private var captureThread: Thread? = null
    private var drainThread: Thread? = null

    private val minBufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(4096)

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    private fun nowUs(): Long =
        if (useRealtimeClock) SystemClock.elapsedRealtimeNanos() / 1_000
        else System.nanoTime() / 1_000

    /**
     * Starts capture. Returns false if the microphone could not be opened -
     * the caller keeps recording video regardless, because silent footage is
     * incomparably better than no footage.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize * 2,
            )
        }.getOrNull()

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            EventLogger.log("capture.microphone_unavailable")
            runCatching { codec.release() }
            return false
        }

        recorder = record
        codec.start()
        record.startRecording()
        running = true

        captureThread = thread(name = "witness-audio-capture", isDaemon = true) { captureLoop(record) }
        drainThread = thread(name = "witness-audio-encoder", isDaemon = true) { drainLoop() }
        return true
    }

    private fun captureLoop(record: AudioRecord) {
        val buffer = ByteArray(minBufferSize)
        while (running) {
            val read = runCatching { record.read(buffer, 0, buffer.size) }.getOrDefault(-1)
            if (read <= 0) continue

            val index = runCatching { codec.dequeueInputBuffer(10_000) }.getOrDefault(-1)
            if (index < 0) continue
            val input = runCatching { codec.getInputBuffer(index) }.getOrNull() ?: continue
            input.clear()
            input.put(buffer, 0, read)
            // Stamp the *start* of this PCM block, not the moment it finished
            // being read, or audio would run progressively late against video.
            val framesRead = read / 2L
            val presentationTimeUs = nowUs() - (framesRead * 1_000_000L / SAMPLE_RATE)
            runCatching { codec.queueInputBuffer(index, 0, read, presentationTimeUs, 0) }
        }
        runCatching {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, nowUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            // See VideoEncoder.drainLoop: a throwing dequeue ends the loop,
            // and `break` cannot cross an inline lambda boundary.
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (error: Throwable) {
                EventLogger.log("encoder.audio_drain_failed", detail = error.message)
                return
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sink.onFormat(codec.outputFormat)
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        sink.onSample(buffer, info)
                    }
                    runCatching { codec.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun stop() {
        running = false
        captureThread?.join(1_000)
        drainThread?.join(1_000)
        captureThread = null
        drainThread = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}
