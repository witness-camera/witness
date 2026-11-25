package com.witnessrecord.app.service

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Composites a session's two already-merged per-camera videos into one
 * combined picture-in-picture file - rear full-frame, front as a small
 * inset - the Android counterpart of the iOS build's
 * `AVMutableVideoComposition` + `AVAssetExportSession` step.
 *
 * There is no Android equivalent of that one-call API: this hand-rolls the
 * same job as two [MediaCodec] decoders (one per source file) whose output
 * lands on GL textures, composited frame by frame via EGL/GLES onto a third
 * encoder's input surface, muxed together with the rear camera's own audio
 * track copied through unchanged. Structurally the sibling of
 * [com.witnessrecord.app.capture.CameraOverlayRenderer] - same EGL/GLES
 * plumbing - but driven by two decode sources instead of one live camera,
 * and running as a batch job on [SessionStitcher]'s own background scope
 * rather than on the capture thread.
 *
 * A genuine re-encode, unlike [SessionStitcher.merge]'s lossless passthrough:
 * two video tracks have to be decoded and drawn into one frame, which costs
 * real time and battery. That is exactly why this is Pro-gated and opt-in
 * (see [AppSettings.pipExportEnabled]) rather than always on.
 *
 * Every failure here throws, and the caller treats that as "no PiP file this
 * time" - never as a reason to touch the two separate per-camera videos,
 * which this never reads back into and which are what the app's core
 * recording guarantee actually depends on.
 */
object PictureInPictureExporter {

    private const val TIMEOUT_US = 10_000L

    /**
     * Builds [outputFile] from [backFile] and [frontFile]. Blocks the calling
     * coroutine's thread until finished - callers run this on a background
     * dispatcher, same as [SessionStitcher.merge].
     */
    fun export(backFile: File, frontFile: File, outputFile: File) {
        val back = probe(backFile)
        val front = probe(frontFile)
        val durationUs = min(back.durationUs, front.durationUs)
        if (durationUs <= 0) throw IllegalStateException("no overlapping duration to export")

        // The rear camera's own frame shape, after its container rotation is
        // accounted for - matching the live viewfinder's own full-screen
        // layer, so the exported file looks like what was seen while
        // filming. Both dimensions rounded to the nearest even number:
        // H.264 encoders require it and an odd source size would otherwise
        // fail configuration outright.
        val outputWidth = (if (back.rotated) back.height else back.width).let { it - it % 2 }
        val outputHeight = (if (back.rotated) back.width else back.height).let { it - it % 2 }

        val thread = HandlerThread("witness-pip-export").apply { start() }
        val handler = Handler(thread.looper)
        try {
            val latch = CountDownLatch(1)
            var failure: Throwable? = null
            handler.post {
                try {
                    renderAndMux(back, front, durationUs, outputWidth, outputHeight, outputFile)
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    latch.countDown()
                }
            }
            // A long recording's export can run for minutes; this is a
            // generous but finite ceiling so a stuck decoder cannot hang the
            // session-finalize pipeline forever.
            if (!latch.await(10, TimeUnit.MINUTES)) {
                throw IllegalStateException("PiP export timed out")
            }
            failure?.let { throw it }
        } finally {
            thread.quitSafely()
        }
    }

    private class TrackInfo(
        val file: File,
        val trackIndex: Int,
        val format: MediaFormat,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val durationUs: Long,
    ) {
        val rotated: Boolean get() = rotation % 180 != 0
    }

    private fun probe(file: File): TrackInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                return TrackInfo(
                    file = file,
                    trackIndex = i,
                    format = format,
                    width = format.getInteger(MediaFormat.KEY_WIDTH),
                    height = format.getInteger(MediaFormat.KEY_HEIGHT),
                    rotation = runCatching { format.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0),
                    durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L),
                )
            }
            throw IllegalStateException("no video track in ${file.name}")
        } finally {
            extractor.release()
        }
    }

    /**
     * Everything from here down runs on the dedicated GL/decode thread -
     * EGL contexts and [MediaCodec] callbacks are thread-affine, same
     * constraint [com.witnessrecord.app.capture.CameraOverlayRenderer] is
     * built around.
     */
    private fun renderAndMux(
        back: TrackInfo, front: TrackInfo, durationUs: Long,
        outputWidth: Int, outputHeight: Int, outputFile: File,
    ) {
        val partial = File(outputFile.parentFile, "${outputFile.name}.partial")
        partial.delete()

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outputWidth, outputHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // A fixed, generous bitrate for a file that exists to be shared
            // rather than archived - wide compatibility matters more here
            // than matching the source recording's own codec/bitrate.
            setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInputSurface = encoder.createInputSurface()
        encoder.start()

        var egl: EglSurface? = null
        var backSource: DecodeSource? = null
        var frontSource: DecodeSource? = null
        // A mutable holder, not a plain Int local: drainEncoder's onFormat
        // callback sets the track number *during* a drain call, and a later
        // write in that SAME call has to see it - a plain Int parameter
        // captured at the call boundary would still read the stale -1.
        val muxerState = MuxerState()

        try {
            egl = EglSurface(encoderInputSurface)
            backSource = DecodeSource(back, egl.attachTexture())
            frontSource = DecodeSource(front, egl.attachTexture())

            val frameDurationUs = 33_333L // 30fps output, independent of the sources' own frame rate.
            var presentedUs = 0L
            var encoderDone = false
            val pendingOutputFile = File(outputFile.parentFile, "${outputFile.name}.partial")

            while (presentedUs < durationUs && !encoderDone) {
                val haveBack = backSource.advanceTo(presentedUs)
                val haveFront = frontSource.advanceTo(presentedUs)
                if (!haveBack) break // Rear is the frame this export is built around; nothing to draw without it.

                egl.drawFrame(
                    backTextureId = backSource.textureId,
                    backTexMatrix = backSource.texMatrix,
                    backRotation = back.rotation,
                    backW = back.width, backH = back.height,
                    frontTextureId = if (haveFront) frontSource.textureId else null,
                    frontTexMatrix = frontSource.texMatrix,
                    frontRotation = front.rotation,
                    frontW = front.width, frontH = front.height,
                    outputWidth = outputWidth, outputHeight = outputHeight,
                    presentationTimeNs = presentedUs * 1_000,
                )
                presentedUs += frameDurationUs

                // Drain whatever the encoder has produced so far - its
                // internal buffer pool is bounded, and never draining would
                // stall drawFrame's own eglSwapBuffers indefinitely.
                val drained = drainEncoder(encoder, muxerState) { format ->
                    muxerState.start(pendingOutputFile, format)
                }
                if (drained.endOfStream) encoderDone = true
            }

            if (!encoderDone) {
                encoder.signalEndOfInputStream()
                // Drain until the encoder actually reports end-of-stream,
                // rather than a fixed number of iterations - how many
                // buffers that takes is an encoder implementation detail.
                var guard = 0
                while (guard++ < 1_000) {
                    val drained = drainEncoder(encoder, muxerState) { format ->
                        muxerState.start(pendingOutputFile, format)
                    }
                    if (drained.endOfStream) break
                }
            }

            val finalMuxer = muxerState.muxer ?: throw IllegalStateException("encoder never produced an output format")

            // The rear camera's own audio, copied through unchanged - two
            // simultaneous microphones would just be overlapping recordings
            // of the same moment, not a clearer one, and each camera's own
            // separate video still carries its own full audio regardless.
            copyAudioTrack(back.file, finalMuxer, durationUs)

            finalMuxer.stop()
            finalMuxer.release()
            muxerState.finished()

            if (!pendingOutputFile.exists() || pendingOutputFile.length() == 0L) {
                throw IllegalStateException("export produced no output")
            }
            outputFile.delete()
            if (!pendingOutputFile.renameTo(outputFile)) {
                throw IllegalStateException("export output could not be moved into place")
            }
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { encoderInputSurface.release() }
            backSource?.release()
            frontSource?.release()
            egl?.release()
            runCatching { muxerState.muxer?.release() }
            partial.delete()
        }
    }

    /**
     * Holds the muxer and its video track number across [drainEncoder]
     * calls, and across the moment - mid-drain - that the track number
     * actually becomes known. See [renderAndMux]'s call sites for why this
     * can't just be a couple of local `var`s passed by value.
     */
    private class MuxerState {
        var muxer: MediaMuxer? = null
            private set
        var videoTrack = -1
            private set

        /** Called once the caller has stopped and released [muxer] itself. */
        fun finished() {
            muxer = null
        }

        fun start(outputFile: File, format: MediaFormat) {
            if (muxer != null) return
            val created = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrack = created.addTrack(format)
            created.start()
            muxer = created
        }
    }

    private class DrainResult(val endOfStream: Boolean)

    private fun drainEncoder(
        encoder: MediaCodec, muxerState: MuxerState, onFormat: (MediaFormat) -> Unit,
    ): DrainResult {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return DrainResult(endOfStream = false)
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    onFormat(encoder.outputFormat)
                }
                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)
                    val muxer = muxerState.muxer
                    if (buffer != null && info.size > 0 && muxer != null) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        runCatching { muxer.writeSampleData(muxerState.videoTrack, buffer, info) }
                    }
                    runCatching { encoder.releaseOutputBuffer(index, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return DrainResult(endOfStream = true)
                    }
                }
            }
        }
    }

    /**
     * Copies every audio sample up to [durationUs] straight from [file] into
     * [muxer], the same passthrough technique [SessionStitcher.merge] uses
     * for video - no decode, no re-encode, no generational loss.
     */
    private fun copyAudioTrack(file: File, muxer: MediaMuxer, durationUs: Long) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                if (trackFormat.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i
                    format = trackFormat
                    break
                }
            }
            if (audioTrack < 0 || format == null) return // Silent source video; nothing to copy.
            val muxerTrack = muxer.addTrack(format)
            extractor.selectTrack(audioTrack)
            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (pts > durationUs) break // Trimmed to the shorter source, same as the video.
                val isSync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, pts, if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                runCatching { muxer.writeSampleData(muxerTrack, buffer, info) }
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
    }

    // MARK: - Decode source

    /**
     * One input file's decoder, pulling frames onto its own GL texture on
     * demand. [advanceTo] is a synchronous, batch-job-paced pull rather than
     * the free-running push [CameraOverlayRenderer] drives from a live
     * camera: this has no wall clock to keep up with, so it decodes exactly
     * as fast as the composite loop asks for the next frame and no faster.
     */
    private class DecodeSource(track: TrackInfo, val textureId: Int) {
        private val extractor = MediaExtractor().apply {
            setDataSource(track.file.absolutePath)
            selectTrack(track.trackIndex)
        }
        private val surfaceTexture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(track.width, track.height)
        }
        private val surface = Surface(surfaceTexture)
        private val decoder = MediaCodec.createDecoderByType(
            track.format.getString(MediaFormat.KEY_MIME)!!
        ).apply {
            configure(track.format, surface, null, 0)
            start()
        }
        val texMatrix = FloatArray(16)

        private var inputDone = false
        private var lastFrameReadyUs = -1L
        private var released = false

        /**
         * Decodes forward until a frame at or past [targetUs] has landed on
         * [textureId], or the stream is exhausted. Returns false only when
         * there is nothing left to draw at all - once a source runs out,
         * the composite loop simply stops asking for more from it.
         */
        fun advanceTo(targetUs: Long): Boolean {
            if (lastFrameReadyUs >= targetUs) return true
            val info = MediaCodec.BufferInfo()
            var guard = 0
            while (guard++ < 200) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIndex >= 0) {
                    val render = info.size > 0
                    decoder.releaseOutputBuffer(outputIndex, render)
                    if (render) {
                        surfaceTexture.updateTexImage()
                        surfaceTexture.getTransformMatrix(texMatrix)
                        lastFrameReadyUs = info.presentationTimeUs
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return lastFrameReadyUs >= 0
                    }
                    if (lastFrameReadyUs >= targetUs) return true
                }
            }
            // Exhausted the retry budget without reaching targetUs - treat
            // as "nothing new," not as a failure; the caller keeps whatever
            // frame is already on the texture (this source visibly stalls
            // for a few output frames rather than aborting the whole
            // export).
            return lastFrameReadyUs >= 0
        }

        fun release() {
            if (released) return
            released = true
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { surface.release() }
            runCatching { surfaceTexture.release() }
            runCatching { extractor.release() }
        }
    }

    // MARK: - EGL / GL compositor

    /**
     * The EGL context and the two-pass (rear, then front) GL compositor,
     * structurally the same shape as
     * [com.witnessrecord.app.capture.CameraOverlayRenderer]'s camera+overlay
     * passes, generalized to draw two independently-rotated decoded sources
     * instead of one live camera plus a text bitmap.
     */
    private class EglSurface(outputSurface: Surface) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var vertexBuffer: FloatBuffer = quadBuffer()

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no EGL display")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw IllegalStateException("eglInitialize failed")

            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
                throw IllegalStateException("eglChooseConfig failed")
            }
            val config = configs[0]!!
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed")
            surface = EGL14.eglCreateWindowSurface(display, config, outputSurface, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface failed")
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw IllegalStateException("eglMakeCurrent failed")

            program = buildProgram()
        }

        fun attachTexture(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return ids[0]
        }

        fun drawFrame(
            backTextureId: Int, backTexMatrix: FloatArray, backRotation: Int, backW: Int, backH: Int,
            frontTextureId: Int?, frontTexMatrix: FloatArray, frontRotation: Int, frontW: Int, frontH: Int,
            outputWidth: Int, outputHeight: Int, presentationTimeNs: Long,
        ) {
            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Rear fills the whole canvas - its own display size is what
            // outputWidth/outputHeight were derived from, so this is exactly
            // a fit, never a crop.
            drawQuad(
                backTextureId, backTexMatrix, backRotation, backW, backH,
                destX = 0f, destY = 0f, destW = outputWidth.toFloat(), destH = outputHeight.toFloat(),
                outputWidth = outputWidth, outputHeight = outputHeight,
            )

            if (frontTextureId != null) {
                // Front inset at roughly a third of the frame's width, its
                // own aspect ratio preserved, tucked into the bottom-right
                // corner with a small margin - the same layout the live
                // viewfinder's own PiP uses (see CameraPreview.kt), so the
                // exported file looks like what was seen while filming.
                val insetW = outputWidth * 0.32f
                val frontDisplayW = if (frontRotation % 180 != 0) frontH else frontW
                val frontDisplayH = if (frontRotation % 180 != 0) frontW else frontH
                val insetH = insetW * (frontDisplayH.toFloat() / frontDisplayW.toFloat())
                val margin = outputWidth * 0.03f
                drawQuad(
                    frontTextureId, frontTexMatrix, frontRotation, frontW, frontH,
                    destX = outputWidth - insetW - margin, destY = outputHeight - insetH - margin,
                    destW = insetW, destH = insetH,
                    outputWidth = outputWidth, outputHeight = outputHeight,
                )
            }

            EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNs)
            EGL14.eglSwapBuffers(display, surface)
        }

        /**
         * Draws one decoded source, rotated upright by [rotation] and fitted
         * into the destination rect - the GL-quad-vertex equivalent of
         * [com.witnessrecord.app.ui.previewTransform]'s Matrix math, built
         * directly as 4 corner positions in output-pixel space rather than
         * as an android.graphics.Matrix, since GL draws from a vertex buffer
         * rather than through a View's canvas.
         */
        private fun drawQuad(
            textureId: Int, texMatrix: FloatArray, rotation: Int, bufferW: Int, bufferH: Int,
            destX: Float, destY: Float, destW: Float, destH: Float, outputWidth: Int, outputHeight: Int,
        ) {
            val shownW = if (rotation % 180 != 0) bufferH else bufferW
            val shownH = if (rotation % 180 != 0) bufferW else bufferH
            val scale = min(destW / shownW, destH / shownH)
            val drawW = bufferW * scale
            val drawH = bufferH * scale
            val cx = destX + destW / 2f
            val cy = destY + destH / 2f
            val theta = Math.toRadians(rotation.toDouble())
            val cosT = Math.cos(theta).toFloat()
            val sinT = Math.sin(theta).toFloat()

            // Unrotated corners of the source quad, centered at the origin -
            // top-left, bottom-left, top-right, bottom-right, matching the
            // texcoord order below.
            val halfW = drawW / 2f
            val halfH = drawH / 2f
            val corners = arrayOf(
                floatArrayOf(-halfW, -halfH), floatArrayOf(-halfW, halfH),
                floatArrayOf(halfW, -halfH), floatArrayOf(halfW, halfH),
            )
            val vertices = FloatArray(16)
            for ((i, corner) in corners.withIndex()) {
                val x = corner[0]
                val y = corner[1]
                // Clockwise rotation in pixel (y-down) space - see the class
                // doc: this bakes the container's own rotation hint directly
                // into the output pixels, unlike the live capture path,
                // which always leaves that to container metadata instead.
                val rx = x * cosT - y * sinT
                val ry = x * sinT + y * cosT
                val px = cx + rx
                val py = cy + ry
                // Output pixel space -> NDC.
                vertices[i * 4] = (px / outputWidth) * 2f - 1f
                vertices[i * 4 + 1] = 1f - (py / outputHeight) * 2f
                vertices[i * 4 + 2] = if (i == 0 || i == 1) 0f else 1f // u
                vertices[i * 4 + 3] = if (i == 0 || i == 2) 0f else 1f // v
            }
            vertexBuffer.clear()
            vertexBuffer.put(vertices)
            vertexBuffer.position(0)

            GLES20.glUseProgram(program)
            val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            val texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
            val textureHandle = GLES20.glGetUniformLocation(program, "sTexture")

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(positionHandle)
            vertexBuffer.position(2)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)

            GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(textureHandle, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        }

        fun release() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }

        private fun buildProgram(): Int {
            val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw IllegalStateException("PiP program link failed: $log")
            }
            return program
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("PiP shader compile failed: $log")
            }
            return shader
        }

        companion object {
            private fun quadBuffer(): FloatBuffer =
                ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

            private const val VERTEX_SHADER = """
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                uniform mat4 uTexMatrix;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uTexMatrix * aTexCoord).xy;
                }
            """

            private const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }
            """
        }
    }
}
