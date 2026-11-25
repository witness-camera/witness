package com.witnessrecord.app.capture

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.witnessrecord.app.service.EventLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sits between the camera and [VideoEncoder], burning a visible
 * timestamp/GPS bar into every frame before it reaches the encoder - the
 * Android counterpart of the iOS build's Core Image compositing step,
 * built on EGL/GLES instead because Android's camera-to-encoder path is a
 * direct GPU buffer handoff (`Surface` to `Surface`) with no CPU-visible
 * pixel buffer to draw into with a 2D API.
 *
 * The camera is pointed at this renderer's [cameraInputSurface] (backed by a
 * `SurfaceTexture`) instead of straight at the encoder's own input surface.
 * Each frame that arrives is drawn back out to the encoder's surface as a
 * full-screen textured quad, with the overlay bar composited on top in a
 * second pass, on a dedicated GL thread that owns the EGL context for the
 * whole recording - EGL contexts are thread-affine, so every GL call here
 * has to happen on that one thread.
 *
 * Two frames of latency are added by nothing here: this still runs at the
 * camera's own frame rate, one GPU-to-GPU copy per frame, which is the same
 * cost every SurfaceTexture-based camera preview already pays.
 *
 * The overlay's own rotation compensates for [orientationDegrees] - the
 * *container* rotation [ChunkWriter] separately writes into the .mp4 to make
 * the raw (sensor-oriented) buffer play back upright. Because that rotation
 * is applied at playback to the whole frame, anything burned into the raw
 * pixels - including this overlay - would otherwise end up sideways or
 * upside down whenever the phone was held anywhere but the orientation the
 * stream opened in. Pre-rotating the overlay's own quad by the same amount,
 * in the opposite rotational sense, cancels that out so it reads upright and
 * bottom-left in the *played-back* video, not in the raw buffer.
 */
class CameraOverlayRenderer(
    private val encoderInputSurface: Surface,
    private val bufferWidth: Int,
    private val bufferHeight: Int,
    private val orientationDegrees: Int,
    /**
     * The camera's own frame size, when it is not the encoder's - which it is
     * not for stealth landscape, where the encoder takes a landscape strip cut
     * from a larger, taller frame. Defaults to the encoder's size.
     */
    private val cameraWidth: Int = bufferWidth,
    private val cameraHeight: Int = bufferHeight,
    /**
     * Non-null for stealth landscape: how far the camera frame has to turn to
     * stand upright. The output is then the centred 16:9 strip of that upright
     * frame, and is already the right way up, so the container needs (and the
     * caller passes) no rotation of its own.
     */
    private val stripRotationDegrees: Int? = null,
) {
    private val thread = HandlerThread("witness-camera-overlay").apply { start() }
    private val handler = Handler(thread.looper)

    // EGL state - touched only on [handler]'s thread.
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var cameraTextureID = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var overlayTextureID = 0

    private var cameraProgram = 0
    private var overlayProgram = 0
    private var cameraVertexBuffer: FloatBuffer? = null
    private var overlayVertexBuffer: FloatBuffer? = null
    private val overlayMvpMatrix = FloatArray(16)
    private val cameraTexMatrix = FloatArray(16)

    private var pendingLines: List<String>? = null
    private var lastRenderedKey: String? = null
    private var overlayAspect = 1f
    private var released = false

    /** What the camera should be pointed at instead of the encoder directly. */
    val cameraInputSurface: Surface

    init {
        val setupLatch = CountDownLatch(1)
        val surfaceRef = arrayOfNulls<Surface>(1)
        handler.post {
            runCatching { setUp() }
                .onFailure { EventLogger.log("capture.overlay_setup_failed", detail = it.message) }
            surfaceRef[0] = cameraSurfaceTexture?.let { Surface(it) }
            setupLatch.countDown()
        }
        setupLatch.await(2, TimeUnit.SECONDS)
        cameraInputSurface = surfaceRef[0]
            ?: throw IllegalStateException("camera overlay EGL setup failed")

        // NDC's x and y axes do not represent equal physical distances on a
        // non-square buffer (1920x1080 is 16:9), so rotating a shape built
        // directly in NDC distorts it - a "square" corner marker would come
        // out as a rotated rectangle. The overlay's quad is instead built in
        // a space where both axes ARE physically equal (x spans
        // [-aspectRatio, aspectRatio], y spans [-1, 1]), rotated there where
        // rotation preserves shape correctly, and only then squashed back
        // into NDC by scaling x by 1/aspectRatio. Built as one matrix,
        // applied scale-after-rotate (`M*v = Scale * (Rotate * v)`), so it
        // costs nothing extra per frame.
        val aspectRatio = bufferWidth.toFloat() / bufferHeight.toFloat()
        val scale = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        Matrix.scaleM(scale, 0, 1f / aspectRatio, 1f, 1f)
        val rotate = FloatArray(16)
        Matrix.setRotateM(rotate, 0, orientationDegrees.toFloat(), 0f, 0f, 1f)
        Matrix.multiplyMM(overlayMvpMatrix, 0, scale, 0, rotate, 0)
    }

    /**
     * Called with fresh text (from [TimestampOverlayText.lines]) roughly once
     * a second. The bitmap is only rebuilt and re-uploaded when the string
     * actually changes - the composite step every other frame is a cheap GPU
     * blend of the cached texture.
     */
    fun updateText(lines: List<String>) {
        handler.post {
            if (released) return@post
            pendingLines = lines
        }
    }

    /**
     * Blocks until the GL thread has actually torn down its EGL surface -
     * which is bound to the encoder's own input surface. Returning early
     * would let the caller (the capture controller, closing out the whole
     * stream) release or reuse that encoder surface while this renderer's
     * EGL surface still referenced it, which is a use-after-free on the
     * native side rather than an ordinary race.
     */
    fun release() {
        val latch = CountDownLatch(1)
        handler.post {
            released = true
            runCatching { cameraSurfaceTexture?.release() }
            tearDownGl()
            latch.countDown()
        }
        latch.await(1_500, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    // MARK: - EGL / GL setup, all on `handler`'s thread

    private fun setUp() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("eglInitialize failed")
        }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0
        ) {
            throw IllegalStateException("eglChooseConfig failed")
        }
        val config = configs[0] ?: throw IllegalStateException("no EGL config")
        eglConfig = config

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed")

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, encoderInputSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface failed")

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("eglMakeCurrent failed")
        }

        cameraProgram = buildProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        overlayProgram = buildProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        cameraVertexBuffer = quadBuffer(
            stripRotationDegrees?.let { stripQuad(it, cameraWidth, cameraHeight) } ?: FULLSCREEN_QUAD
        )

        val textureIDs = IntArray(2)
        GLES20.glGenTextures(2, textureIDs, 0)
        cameraTextureID = textureIDs[0]
        overlayTextureID = textureIDs[1]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureID)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureID)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val surfaceTexture = SurfaceTexture(cameraTextureID)
        surfaceTexture.setDefaultBufferSize(cameraWidth, cameraHeight)
        surfaceTexture.setOnFrameAvailableListener({ handler.post { onFrameAvailable() } }, handler)
        cameraSurfaceTexture = surfaceTexture
    }

    private fun onFrameAvailable() {
        if (released) return
        val surfaceTexture = cameraSurfaceTexture ?: return
        runCatching { drawFrame(surfaceTexture) }
            .onFailure { EventLogger.log("capture.overlay_draw_failed", detail = it.message) }
    }

    private fun drawFrame(surfaceTexture: SurfaceTexture) {
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(cameraTexMatrix)
        val timestampNs = surfaceTexture.timestamp

        GLES20.glViewport(0, 0, bufferWidth, bufferHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_BLEND)
        drawCamera()

        maybeRebuildOverlayTexture()
        if (lastRenderedKey != null) {
            GLES20.glEnable(GLES20.GL_BLEND)
            // The overlay bitmap is premultiplied alpha (standard for an
            // Android Bitmap drawn via Canvas), so the source's own alpha
            // must not be multiplied a second time by the blend function.
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            drawOverlay()
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun drawCamera() {
        val buffer = cameraVertexBuffer ?: return
        GLES20.glUseProgram(cameraProgram)
        val positionHandle = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord")
        val texMatrixHandle = GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix")
        val textureHandle = GLES20.glGetUniformLocation(cameraProgram, "sTexture")

        buffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, STRIDE, buffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        buffer.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, STRIDE, buffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, cameraTexMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureID)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun drawOverlay() {
        val buffer = overlayVertexBuffer ?: return
        GLES20.glUseProgram(overlayProgram)
        val positionHandle = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        val mvpHandle = GLES20.glGetUniformLocation(overlayProgram, "uMvpMatrix")
        val textureHandle = GLES20.glGetUniformLocation(overlayProgram, "sTexture")

        buffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, STRIDE, buffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        buffer.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, STRIDE, buffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, overlayMvpMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureID)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    /**
     * Rebuilds the overlay bitmap/texture and its quad only when the text has
     * actually changed - normally once a second. Every other frame reuses
     * both untouched, which is what keeps this a cheap composite rather than
     * a redraw on every one of 30-60 frames that share the same text.
     */
    private fun maybeRebuildOverlayTexture() {
        val lines = pendingLines ?: return
        val key = TimestampOverlayText.key(lines)
        if (key == lastRenderedKey) return
        val bitmap = TimestampOverlayText.render(lines, bufferHeight)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureID)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        overlayAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        overlayVertexBuffer = quadBuffer(overlayQuad(overlayAspect))
        lastRenderedKey = key
        bitmap.recycle()
    }

    /**
     * The overlay's rectangle, built in the same physically-equal-axis space
     * [overlayMvpMatrix] expects (see its construction), anchored near the
     * bottom-left of the *frame as it will be played back*, at the aspect
     * ratio the rendered text bitmap actually came out at.
     *
     * Deliberately confined to the square physical region x,y ∈ [-1, 1] -
     * not the frame's full physical width, which on a 16:9 buffer reaches
     * out to ±aspectRatio (≈±1.78). That square is exactly the region a
     * rotation about the origin cannot carry outside the frame, whichever of
     * the four multiples of 90° [overlayMvpMatrix] turns out to be - a
     * rectangle sized using the wider room the *unrotated* frame would have
     * allowed came out fine at 0°/180° and clipped its own left edge off
     * the encoded buffer at 90°/270°, which is exactly what a rotation
     * this size-unaware would do to anything wider than the frame is tall.
     * A very long line of text still fits - it just shrinks to, rather than
     * being clipped past, that bound.
     */
    private fun overlayQuad(aspect: Float): FloatArray {
        val margin = 0.06f
        val maxHeight = 0.34f
        val maxWidth = 2f - 2 * margin
        var barHeight = maxHeight
        var barWidth = barHeight * aspect
        if (barWidth > maxWidth) {
            barWidth = maxWidth
            barHeight = barWidth / aspect
        }
        val left = -1f + margin
        val bottom = -1f + margin
        val right = left + barWidth
        val top = bottom + barHeight
        // x, y, u, v - triangle strip: top-left, bottom-left, top-right, bottom-right.
        return floatArrayOf(
            left, top, 0f, 0f,
            left, bottom, 0f, 1f,
            right, top, 1f, 0f,
            right, bottom, 1f, 1f,
        )
    }

    private fun tearDownGl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("program link failed: $log")
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
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }

    private fun quadBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data)
            position(0)
        }

    companion object {
        private const val STRIDE = 4 * 4 // 4 floats per vertex, 4 bytes each.

        /**
         * A full-screen quad whose texture coordinates pick out the centred
         * 16:9 landscape strip of the camera frame once it has been turned
         * upright by [rotationDegrees], so the strip comes out as the encoded
         * picture, upright, with no separate rotation step.
         *
         * Coordinates are in the same top-left-origin image space as
         * [FULLSCREEN_QUAD]; the camera's own transform matrix does the rest.
         * An upright point (x, y) comes from the camera frame's (u, v) as:
         * 0 degrees (x, y); 90 (y, 1-x); 180 (1-x, 1-y); 270 (1-y, x).
         */
        internal fun stripQuad(rotationDegrees: Int, cameraWidth: Int, cameraHeight: Int): FloatArray {
            val turned = rotationDegrees % 180 != 0
            val uprightWidth = if (turned) cameraHeight else cameraWidth
            val uprightHeight = if (turned) cameraWidth else cameraHeight
            val stripFraction = (uprightWidth * 9f / 16f / uprightHeight).coerceAtMost(1f)
            val top = (1f - stripFraction) / 2f
            val bottom = top + stripFraction

            fun source(x: Float, y: Float): Pair<Float, Float> = when ((rotationDegrees % 360 + 360) % 360) {
                90 -> y to 1f - x
                180 -> 1f - x to 1f - y
                270 -> 1f - y to x
                else -> x to y
            }
            val topLeft = source(0f, top)
            val bottomLeft = source(0f, bottom)
            val topRight = source(1f, top)
            val bottomRight = source(1f, bottom)
            // x, y, u, v - triangle strip: top-left, bottom-left, top-right, bottom-right.
            return floatArrayOf(
                -1f, 1f, topLeft.first, topLeft.second,
                -1f, -1f, bottomLeft.first, bottomLeft.second,
                1f, 1f, topRight.first, topRight.second,
                1f, -1f, bottomRight.first, bottomRight.second,
            )
        }

        private val FULLSCREEN_QUAD = floatArrayOf(
            // x, y, u, v - triangle strip: top-left, bottom-left, top-right, bottom-right.
            -1f, 1f, 0f, 0f,
            -1f, -1f, 0f, 1f,
            1f, 1f, 1f, 0f,
            1f, -1f, 1f, 1f,
        )

        private const val CAMERA_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private const val OVERLAY_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uMvpMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = aTexCoord.xy;
            }
        """

        private const val OVERLAY_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
