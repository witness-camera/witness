package com.witnessrecord.app.service

import android.graphics.Bitmap
import android.graphics.Matrix
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
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.witnessrecord.app.service.BlurTracker.Box
import com.witnessrecord.app.service.BlurTracker.Region
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Produces a publish-safe copy of a recording with bystanders' faces and
 * vehicle licence plates blurred - in addition to, never instead of, the
 * untouched original, which stays the evidence. The Android counterpart of the
 * iOS build's `BlurExporter`.
 *
 * Everything runs on the phone: ML Kit's bundled face detector for faces, and
 * its on-device text recognition for plates (there is no plate detector),
 * keeping only text shaped like a plate - see [BlurTracker.looksLikePlate].
 * Detection is automatic and can miss a face turned away or a plate at a steep
 * angle, which the setting says plainly; this copy is a head start on
 * redaction, not a guarantee.
 *
 * Each frame is decoded onto a GL texture and drawn to the encoder, with the
 * covered regions replaced by a coarse mosaic of the frame, smoothed. A
 * mosaic is what makes it irreversible: a plain blur of a face can be partly
 * undone, a mosaic a few blocks across has thrown the detail away.
 */
object BlurExporter {

    /** Faces are re-detected every few frames, plates less often; boxes carry over in between. */
    private const val FACE_EVERY = 2
    private const val PLATE_EVERY = 6

    /** The longest side of the picture detection looks at: plenty to find a face, cheap to read back. */
    private const val DETECTION_SIDE = 640

    private const val MAX_REGIONS = 16
    private const val TIMEOUT_US = 10_000L

    /** Builds [output] from [source]. Blocks; callers run it on a background thread. */
    fun build(source: File, output: File) {
        val info = probe(source)
        val thread = HandlerThread("witness-blur-export").apply { start() }
        val handler = Handler(thread.looper)
        try {
            val latch = CountDownLatch(1)
            var failure: Throwable? = null
            handler.post {
                try {
                    render(info, output)
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    latch.countDown()
                }
            }
            // Detection costs real time per frame: allow several times the
            // recording's own length, with a floor, but not forever.
            val minutes = max(15L, info.durationUs / 60_000_000L * 6)
            if (!latch.await(minutes, TimeUnit.MINUTES)) throw IllegalStateException("blur export timed out")
            failure?.let { throw it }
        } finally {
            thread.quitSafely()
        }
    }

    private class VideoInfo(
        val file: File,
        val trackIndex: Int,
        val format: MediaFormat,
        val mime: String,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val durationUs: Long,
        val frameRate: Int,
        val bitRate: Int,
    )

    private fun probe(file: File): VideoInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                val frameRate = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30)
                val bitRate = runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull()
                    ?: (width.toLong() * height * frameRate / 10).toInt()
                return VideoInfo(
                    file, i, format, mime, width, height,
                    rotation = runCatching { format.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0),
                    durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L),
                    frameRate = frameRate.coerceIn(10, 60),
                    bitRate = bitRate.coerceIn(2_000_000, 100_000_000),
                )
            }
            throw IllegalStateException("no video track in ${file.name}")
        } finally {
            extractor.release()
        }
    }

    private fun render(info: VideoInfo, outputFile: File) {
        val partial = File(outputFile.parentFile, "${outputFile.name}.partial")
        partial.delete()

        // The same codec the recording used, so a large or HEVC recording is
        // not forced through a codec that may not take its size.
        val encoder = MediaCodec.createEncoderByType(info.mime)
        val encoderFormat = MediaFormat.createVideoFormat(info.mime, info.width, info.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, info.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, info.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()
        )
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        var gl: BlurGl? = null
        var decoder: Decoder? = null
        var muxer: MediaMuxer? = null
        var videoTrack = -1
        var muxerStarted = false

        try {
            gl = BlurGl(encoderSurface, info.width, info.height, info.rotation)
            decoder = Decoder(info, gl.cameraTextureId)

            // Upright detection frames: the stored frame turned by the
            // container rotation, which is how the footage is actually seen.
            val uprightW = if (info.rotation % 180 != 0) info.height else info.width
            val uprightH = if (info.rotation % 180 != 0) info.width else info.height
            val scale = DETECTION_SIDE.toFloat() / max(uprightW, uprightH)
            val detectW = max(32, (uprightW * scale).toInt())
            val detectH = max(32, (uprightH * scale).toInt())
            val storedDetectW = if (info.rotation % 180 != 0) detectH else detectW
            val storedDetectH = if (info.rotation % 180 != 0) detectW else detectH
            gl.prepareReadback(storedDetectW, storedDetectH)
            val tracker = BlurTracker(detectW.toFloat(), detectH.toFloat())

            val bufferInfo = MediaCodec.BufferInfo()
            var frameIndex = 0
            var encoderDone = false

            fun drain(endOfStream: Boolean) {
                while (true) {
                    val index = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                val created = MediaMuxer(partial.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                                created.setOrientationHint(info.rotation)
                                videoTrack = created.addTrack(encoder.outputFormat)
                                created.start()
                                muxer = created
                                muxerStarted = true
                            }
                        }
                        index >= 0 -> {
                            val buffer = encoder.getOutputBuffer(index)
                            if (buffer != null && bufferInfo.size > 0 && muxerStarted &&
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                muxer?.writeSampleData(videoTrack, buffer, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(index, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderDone = true
                                return
                            }
                        }
                    }
                }
            }

            while (true) {
                val presentationUs = decoder.nextFrame() ?: break
                var found = emptyList<Region>()
                val wantFaces = frameIndex % FACE_EVERY == 0
                val wantPlates = frameIndex % PLATE_EVERY == 0
                if (wantFaces || wantPlates) {
                    val bitmap = gl.readFrame(decoder.texMatrix, info.rotation)
                    found = detect(bitmap, faceDetector, textRecognizer, wantFaces, wantPlates)
                    bitmap.recycle()
                }
                val covers = tracker.update(found)
                gl.drawFrame(decoder.texMatrix, covers, detectW.toFloat(), detectH.toFloat(), presentationUs * 1_000)
                frameIndex++
                drain(endOfStream = false)
            }

            encoder.signalEndOfInputStream()
            var guard = 0
            while (!encoderDone && guard++ < 2_000) drain(endOfStream = true)

            val finalMuxer = muxer ?: throw IllegalStateException("encoder never produced an output format")
            copyAudio(info.file, finalMuxer)
            finalMuxer.stop()
            finalMuxer.release()
            muxer = null

            if (!partial.exists() || partial.length() == 0L) throw IllegalStateException("blur export produced no output")
            outputFile.delete()
            if (!partial.renameTo(outputFile)) throw IllegalStateException("blur export could not be moved into place")
        } finally {
            runCatching { faceDetector.close() }
            runCatching { textRecognizer.close() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { encoderSurface.release() }
            decoder?.release()
            gl?.release()
            runCatching { muxer?.release() }
            partial.delete()
        }
    }

    /**
     * Faces and plates in an upright detection picture, as regions in that
     * picture's pixels. Each detector failing on a frame just means nothing
     * new was found on it - the tracker keeps the cover from earlier frames.
     */
    private fun detect(
        bitmap: Bitmap,
        faces: com.google.mlkit.vision.face.FaceDetector,
        text: com.google.mlkit.vision.text.TextRecognizer,
        wantFaces: Boolean,
        wantPlates: Boolean,
    ): List<Region> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val found = mutableListOf<Region>()
        if (wantFaces) {
            runCatching { Tasks.await(faces.process(image), 5, TimeUnit.SECONDS) }.getOrNull()?.forEach { face ->
                val b = face.boundingBox
                // The detector's box is the inner face; widen for hair, ears and chin.
                val w = b.width() * 1.9f
                val h = b.height() * 1.9f
                found.add(Region(Box(b.exactCenterX(), b.exactCenterY() - b.height() * 0.05f, w, h), round = true))
            }
        }
        if (wantPlates) {
            runCatching { Tasks.await(text.process(image), 5, TimeUnit.SECONDS) }.getOrNull()?.textBlocks
                ?.flatMap { it.lines }
                ?.forEach { line ->
                    val b = line.boundingBox ?: return@forEach
                    if (!BlurTracker.looksLikePlate(line.text)) return@forEach
                    val aspect = b.width().toFloat() / max(b.height(), 1)
                    if (aspect <= 1.4f || aspect >= 9f) return@forEach
                    found.add(
                        Region(
                            Box(b.exactCenterX(), b.exactCenterY(), b.width() * 1.4f, b.height() * 2.4f),
                            round = false,
                        )
                    )
                }
        }
        return found
    }

    /** Copies the source's audio straight across, unchanged: a blurred picture is not a reason to touch the sound. */
    private fun copyAudio(source: File, muxer: MediaMuxer) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return
            val track = muxer.addTrack(extractor.getTrackFormat(index))
            extractor.selectTrack(index)
            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                runCatching { muxer.writeSampleData(track, buffer, info) }
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
    }

    // MARK: - Decoding

    /** Decodes the source one frame at a time onto [textureId]. */
    private class Decoder(info: VideoInfo, textureId: Int) {
        private val extractor = MediaExtractor().apply {
            setDataSource(info.file.absolutePath)
            selectTrack(info.trackIndex)
        }
        private val surfaceTexture = SurfaceTexture(textureId).apply { setDefaultBufferSize(info.width, info.height) }
        private val surface = Surface(surfaceTexture)
        private val decoder = MediaCodec.createDecoderByType(info.mime).apply {
            configure(info.format, surface, null, 0)
            start()
        }
        val texMatrix = FloatArray(16)
        private var inputDone = false
        private var outputDone = false
        private val bufferInfo = MediaCodec.BufferInfo()

        /** Decodes the next frame onto the texture; its time in microseconds, or null at the end. */
        fun nextFrame(): Long? {
            while (!outputDone) {
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
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputIndex >= 0) {
                    val render = bufferInfo.size > 0
                    val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, render)
                    if (endOfStream) outputDone = true
                    if (render) {
                        surfaceTexture.updateTexImage()
                        surfaceTexture.getTransformMatrix(texMatrix)
                        return bufferInfo.presentationTimeUs
                    }
                }
            }
            return null
        }

        fun release() {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { surface.release() }
            runCatching { surfaceTexture.release() }
            runCatching { extractor.release() }
        }
    }

    // MARK: - GL

    /**
     * The EGL context on the encoder's surface, and the shader that draws a
     * decoded frame with its covered regions replaced by a smoothed mosaic.
     * Also able to draw a small copy of the frame to a texture and read it
     * back, which is what detection looks at.
     */
    private class BlurGl(
        outputSurface: Surface,
        private val frameWidth: Int,
        private val frameHeight: Int,
        private val rotation: Int,
    ) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private val quad: FloatBuffer = quadBuffer()
        val cameraTextureId: Int

        private var framebuffer = 0
        private var framebufferTexture = 0
        private var readbackW = 0
        private var readbackH = 0
        private var readbackBytes: ByteBuffer? = null

        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig failed" }
            val config = configs[0]!!
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            surface = EGL14.eglCreateWindowSurface(display, config, outputSurface, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

            program = buildProgram()
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            cameraTextureId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        fun prepareReadback(width: Int, height: Int) {
            readbackW = width
            readbackH = height
            readbackBytes = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            framebufferTexture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, framebufferTexture)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val fbo = IntArray(1)
            GLES20.glGenFramebuffers(1, fbo, 0)
            framebuffer = fbo[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, framebufferTexture, 0,
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        /** The current frame, small and turned upright, for detection. */
        fun readFrame(texMatrix: FloatArray, rotation: Int): Bitmap {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glViewport(0, 0, readbackW, readbackH)
            drawQuad(texMatrix, emptyList(), 1f, 1f)
            val bytes = readbackBytes!!
            bytes.clear()
            GLES20.glReadPixels(0, 0, readbackW, readbackH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            val raw = Bitmap.createBitmap(readbackW, readbackH, Bitmap.Config.ARGB_8888)
            bytes.rewind()
            raw.copyPixelsFromBuffer(bytes)
            // GL's first row is the bottom of the picture, so flip it back as
            // well as turning it upright.
            val matrix = Matrix().apply {
                setScale(1f, -1f)
                postRotate(rotation.toFloat())
            }
            val upright = Bitmap.createBitmap(raw, 0, 0, readbackW, readbackH, matrix, true)
            raw.recycle()
            return upright
        }

        fun drawFrame(
            texMatrix: FloatArray, covers: List<Region>, detectW: Float, detectH: Float, presentationNs: Long,
        ) {
            GLES20.glViewport(0, 0, frameWidth, frameHeight)
            drawQuad(texMatrix, covers, detectW, detectH)
            EGLExt.eglPresentationTimeANDROID(display, surface, presentationNs)
            EGL14.eglSwapBuffers(display, surface)
        }

        private fun drawQuad(texMatrix: FloatArray, covers: List<Region>, detectW: Float, detectH: Float) {
            GLES20.glUseProgram(program)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            quad.position(0)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(position)
            quad.position(2)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(texCoord)

            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)

            // Regions, from the upright detection picture's pixels into the
            // stored frame's 0..1 image space (top-left origin) the shader
            // works in. Radii swap when the frame is stored on its side.
            val data = FloatArray(MAX_REGIONS * 4)
            val shapes = FloatArray(MAX_REGIONS)
            val turned = rotation % 180 != 0
            val shown = covers.take(MAX_REGIONS)
            shown.forEachIndexed { i, region ->
                val ux = region.box.cx / detectW
                val uy = region.box.cy / detectH
                val urx = region.box.width / 2f / detectW
                val ury = region.box.height / 2f / detectH
                val (sx, sy) = when ((rotation % 360 + 360) % 360) {
                    90 -> uy to 1f - ux
                    180 -> 1f - ux to 1f - uy
                    270 -> 1f - uy to ux
                    else -> ux to uy
                }
                data[i * 4] = sx
                data[i * 4 + 1] = sy
                data[i * 4 + 2] = if (turned) ury else urx
                data[i * 4 + 3] = if (turned) urx else ury
                shapes[i] = if (region.round) 1f else 0f
            }
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "uRegion"), MAX_REGIONS, data, 0)
            GLES20.glUniform1fv(GLES20.glGetUniformLocation(program, "uShape"), MAX_REGIONS, shapes, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uCount"), shown.size.toFloat())

            // The mosaic's block size: about twenty across the upright width,
            // never smaller than 26 pixels - coarse enough that a face is a
            // couple of blocks, which is what makes it irreversible.
            val uprightWidthPx = if (turned) frameHeight else frameWidth
            val blockPx = max(26f, uprightWidthPx / 20f)
            GLES20.glUniform2f(
                GLES20.glGetUniformLocation(program, "uBlock"), blockPx / frameWidth, blockPx / frameHeight,
            )

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(position)
            GLES20.glDisableVertexAttribArray(texCoord)
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

        private fun quadBuffer(): FloatBuffer =
            ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                // x, y, u, v - triangle strip: top-left, bottom-left, top-right, bottom-right.
                put(floatArrayOf(-1f, 1f, 0f, 0f, -1f, -1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f, -1f, 1f, 1f))
                position(0)
            }

        private fun buildProgram(): Int {
            fun compile(type: Int, source: String): Int {
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val status = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                if (status[0] == 0) {
                    val log = GLES20.glGetShaderInfoLog(shader)
                    GLES20.glDeleteShader(shader)
                    throw IllegalStateException("shader compile failed: $log")
                }
                return shader
            }
            val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            val handle = GLES20.glCreateProgram()
            GLES20.glAttachShader(handle, vertex)
            GLES20.glAttachShader(handle, fragment)
            GLES20.glLinkProgram(handle)
            val status = IntArray(1)
            GLES20.glGetProgramiv(handle, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(handle)
                GLES20.glDeleteProgram(handle)
                throw IllegalStateException("program link failed: $log")
            }
            return handle
        }
    }

    private const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        varying vec2 vImage;
        void main() {
            gl_Position = aPosition;
            vImage = aTexCoord.xy;
        }
    """

    /**
     * Draws the frame, and where a region covers a pixel, a smoothed mosaic of
     * the frame instead: four neighbouring block-centre colours (each itself
     * an average of four taps, so one noisy pixel cannot leak through),
     * blended bilinearly into a soft, blocky blur that cannot be sharpened
     * back. Faces are soft ellipses and plates soft rectangles, each feathered
     * at its edge so nothing shows a hard outline.
     */
    private const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vImage;
        uniform samplerExternalOES sTexture;
        uniform mat4 uTexMatrix;
        uniform vec4 uRegion[$MAX_REGIONS];
        uniform float uShape[$MAX_REGIONS];
        uniform float uCount;
        uniform vec2 uBlock;

        vec4 tap(vec2 image) {
            return texture2D(sTexture, (uTexMatrix * vec4(image, 0.0, 1.0)).xy);
        }

        vec4 blockColor(vec2 cell) {
            vec2 centre = (cell + 0.5) * uBlock;
            vec2 q = uBlock * 0.25;
            return 0.25 * (tap(centre + vec2(-q.x, -q.y)) + tap(centre + vec2(q.x, -q.y))
                         + tap(centre + vec2(-q.x, q.y)) + tap(centre + vec2(q.x, q.y)));
        }

        void main() {
            vec4 original = tap(vImage);
            float cover = 0.0;
            for (int i = 0; i < $MAX_REGIONS; i++) {
                if (float(i) >= uCount) break;
                vec2 centre = uRegion[i].xy;
                vec2 radius = max(uRegion[i].zw, vec2(0.0001));
                vec2 d = (vImage - centre) / radius;
                float m;
                if (uShape[i] > 0.5) {
                    m = 1.0 - smoothstep(0.88, 1.0, length(d));
                } else {
                    m = 1.0 - smoothstep(0.85, 1.0, max(abs(d.x), abs(d.y)));
                }
                cover = max(cover, m);
            }
            if (cover <= 0.0) {
                gl_FragColor = original;
                return;
            }
            vec2 cell = vImage / uBlock - 0.5;
            vec2 base = floor(cell);
            vec2 f = fract(cell);
            vec4 hidden = mix(
                mix(blockColor(base), blockColor(base + vec2(1.0, 0.0)), f.x),
                mix(blockColor(base + vec2(0.0, 1.0)), blockColor(base + vec2(1.0, 1.0)), f.x),
                f.y);
            gl_FragColor = mix(original, hidden, cover);
        }
    """
}
