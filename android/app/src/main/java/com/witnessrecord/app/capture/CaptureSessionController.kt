package com.witnessrecord.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.location.Location
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import com.witnessrecord.app.R
import com.witnessrecord.app.WitnessApplication
import com.witnessrecord.app.model.CameraSource
import com.witnessrecord.app.service.AppSettings
import com.witnessrecord.app.service.ChunkStore
import com.witnessrecord.app.service.CrewStore
import com.witnessrecord.app.service.EventLogger
import com.witnessrecord.app.service.LocationService
import com.witnessrecord.app.service.Prefs
import com.witnessrecord.app.service.VideoResolution
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns the camera session and rotates muxer-backed chunks every ~15 seconds so
 * footage uploads incrementally during the encounter.
 *
 * Two modes:
 *  - Single-camera: one rear camera plus the microphone.
 *  - Dual-camera: rear and front recorded simultaneously into two parallel
 *    chunk streams (each carrying the same audio), when the user enables it
 *    and the device reports the pair as concurrently streamable.
 *
 * Configuration degrades gracefully: dual, then single, honouring the user's
 * lens preference at each step - so a device that cannot do a particular
 * combination falls back instead of failing.
 *
 * Unlike the iOS build, the encoders here live for the whole recording and
 * only the muxer is swapped at a chunk boundary, and a rotation waits for the
 * next keyframe before switching. No frame is ever dropped between chunks.
 */
class CaptureSessionController {

    data class FinalizedChunk(
        val file: File,
        val sessionID: String,
        val index: Int,
        val camera: CameraSource,
        val startedAt: Long,
        val endedAt: Long,
        val location: Location?,
    )

    /** Called every time a chunk file is finalized on disk. */
    var onChunkFinalized: ((FinalizedChunk) -> Unit)? = null

    /**
     * Called once a stop() has fully finalized its last chunk(s), carrying the
     * just-ended recording session's ID. Fires after every chunk for the
     * session is on disk (both cameras in dual mode).
     */
    var onSessionFinalized: ((String) -> Unit)? = null

    /** Called when the session hits an unrecoverable error. */
    var onError: ((String) -> Unit)? = null

    /**
     * Called after configuration, reporting whether dual camera is actually
     * active (may be false even when requested, if the device fell back).
     */
    var onConfigured: ((Boolean) -> Unit)? = null

    /** Called whenever the effective rear lens changes. */
    var onLensChanged: ((Double) -> Unit)? = null

    /**
     * Called after configuration with a human-readable description of what
     * the camera actually did - read back from the device, not from the
     * settings that were asked for. See [publishDiagnostics].
     */
    var onDiagnostics: ((String) -> Unit)? = null

    /**
     * Everything the viewfinder needs to draw a camera's frames correctly.
     *
     * The buffer arrives in the sensor's own orientation, which on nearly every
     * phone is a quarter turn from the way the screen is held, and the view it
     * is drawn into is whatever shape the layout gives it. Both numbers are
     * needed: the size to scale from, the rotation to put the scene upright.
     */
    data class PreviewGeometry(
        val bufferSize: Size,
        /** Clockwise turn the footage gets for playback: the recording's orientation hint. */
        val rotationDegrees: Int,
        /** The sensor's own mounting, which the platform has already undone in the preview texture. */
        val sensorOrientation: Int,
        /** Stealth landscape: the recording is only the centred 16:9 strip of this frame. */
        val stripGuide: Boolean = false,
    )

    /**
     * Called after configuration with each active camera's preview geometry.
     * See [publishPreviewGeometry].
     */
    var onPreviewGeometry: ((back: PreviewGeometry, front: PreviewGeometry?) -> Unit)? = null

    // Shorter chunks mean less footage is ever in the "not yet uploaded" gap -
    // worth it even though it roughly doubles chunk/manifest-revision count.
    private val chunkSeconds = 15.0

    private val cameraThread = HandlerThread("witness-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    /**
     * Where Camera2 delivers its open/configure callbacks - deliberately *not*
     * [cameraHandler].
     *
     * Configuration runs on [cameraHandler] and waits on a latch for each of
     * those callbacks. Dispatching them to the same thread deadlocks it
     * against itself: the thread blocks in `await`, so the post carrying
     * `onOpened` can never be serviced, and every open and every session
     * configuration fails on a five-second timeout. That is exactly what
     * happened on the first device this was ever run on - the camera opened
     * fine at the system level and the app never heard about it.
     *
     * A second thread is the whole fix. Camera2 places no requirement that
     * these callbacks share a thread with the caller.
     */
    private val callbackThread = HandlerThread("witness-camera-callbacks").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val cameraExecutor: Executor = Executor { callbackHandler.post(it) }
    private val finalizeExecutor = Executors.newSingleThreadExecutor()

    private val manager: CameraManager
        get() = WitnessApplication.appContext
            .getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** Guards every writer swap and every sample write. */
    private val writerLock = Any()

    private var backStream: Stream? = null
    private var frontStream: Stream? = null
    private var audioEncoder: AudioEncoder? = null
    private var audioFormat: MediaFormat? = null

    /** Released once the audio encoder publishes its output format. */
    @Volatile private var audioFormatGate: CountDownLatch? = null

    private var recordingSessionID: String = UUID.randomUUID().toString()

    /**
     * Which chunk of the recording every camera is currently writing.
     * Advanced once per rotation - never per camera - so that a dual-camera
     * session's rear and front chunk N always cover the same slice of the
     * encounter regardless of which camera reaches its keyframe first.
     */
    private var rotationGeneration = 0

    /** Whether a rotation has been asked for and not every stream has rolled. */
    private var rotationPending = false
    @Volatile private var isCapturing = false
    private var chunkStartLocation: Location? = null
    private var dualMode = false

    private var rearCharacteristics: CameraCharacteristics? = null
    private var rearCameraSupportsCrossover = false

    /**
     * Current effective rear-lens multiplier in the Camera-app sense
     * (0.5 = ultra-wide, 1.0 = wide).
     */
    @Volatile var currentLensMultiplier: Double = CameraSelection.startingLens()
        private set

    // MARK: - Preview surfaces

    @Volatile private var backPreviewSurface: Surface? = null
    @Volatile private var frontPreviewSurface: Surface? = null

    /**
     * Opened per recording and released once the UI has handed over its
     * preview surfaces, so configuration waits for them instead of racing the
     * first composition. See [start].
     */
    @Volatile private var previewGate: CountDownLatch? = null

    /**
     * Preview outputs are attached at session-configuration time and stay
     * attached for the whole recording, so toggling the viewfinder is purely a
     * matter of what the UI draws on top - it never reconfigures the camera,
     * and therefore never interrupts the footage.
     */
    fun setPreviewSurfaces(back: Surface?, front: Surface?) {
        backPreviewSurface = back
        frontPreviewSurface = front
        if (back != null) previewGate?.countDown()
    }

    // MARK: - Public control

    val isDualActive: Boolean get() = dualMode

    fun start() {
        // Started before anything else, so the accelerometer has the preview
        // gate's wait below to report in. Recording at the angle the phone is
        // actually held is only possible if something has been watching.
        beginOrientationObserving()

        // The recording screen mounts a frame or two after this is called, so
        // its preview surfaces do not exist yet. Configuration briefly waits
        // for them rather than opening a session without them: a preview
        // output cannot be added later without reconfiguring the session,
        // which would interrupt the footage the moment the user asked to see
        // the viewfinder. If they never arrive, recording proceeds without a
        // preview rather than not at all.
        val gate = CountDownLatch(1)
        previewGate = gate
        if (backPreviewSurface != null) gate.countDown()

        cameraHandler.post {
            gate.await(1_500, TimeUnit.MILLISECONDS)
            previewGate = null
            configureAndStart()
        }
    }

    /**
     * Stops capture, finalizing the in-flight chunk(s) first, then reports the
     * ended session once every chunk is on disk.
     */
    fun stop(completion: (() -> Unit)? = null) {
        cameraHandler.post {
            val sessionID = recordingSessionID
            // Order matters: closing a stream finishes its writer silently, so
            // the in-flight chunks must be finalized and reported *first* or
            // the last seconds of every recording would never reach the upload
            // queue or the manifest.
            isCapturing = false
            synchronized(writerLock) { finalizeWriters(sessionEnding = true) }
            teardown()
            endOrientationObserving()
            onSessionFinalized?.invoke(sessionID)
            completion?.invoke()
        }
    }

    /**
     * Finalizes the current chunk(s) without stopping the session - used when
     * the app is interrupted so already-captured footage becomes uploadable
     * immediately.
     */
    fun flushCurrentChunk() {
        synchronized(writerLock) { requestRotation() }
    }

    /**
     * Which discrete lens multipliers the active rear camera can reach. Used
     * to decide whether to show the viewfinder lens control at all (a device
     * whose rear camera cannot cross to 0.5x can only do 1x).
     */
    fun availableLensMultipliers(): List<Double> =
        if (rearCameraSupportsCrossover) listOf(0.5, 1.0) else listOf(currentLensMultiplier)

    /**
     * Moves the rear lens to [multiplier] (0.5 or 1.0) with no interruption to
     * recording - the stream stays continuous, so there is no gap in the
     * footage. On a logical camera that reaches below 1.0 this crosses the
     * real ultra-wide/wide handoff optically, just like the stock camera app.
     */
    fun setLensMultiplier(multiplier: Double) {
        cameraHandler.post {
            val stream = backStream ?: return@post
            val characteristics = rearCharacteristics ?: return@post
            val ratio = CameraSelection.zoomRatio(multiplier, characteristics)
            val applied = runCatching {
                stream.applyZoom(ratio)
                true
            }.getOrElse {
                EventLogger.log("capture.lens_change_failed", detail = it.message)
                false
            }
            if (applied) {
                currentLensMultiplier = multiplier
                onLensChanged?.invoke(multiplier)
                EventLogger.log("capture.lens_changed", detail = "${multiplier}x ratio=$ratio")
            }
        }
    }

    // MARK: - Configuration

    @SuppressLint("MissingPermission")
    private fun configureAndStart() {
        val cameraManager = manager
        val rearCandidates = CameraSelection.rearCandidates(cameraManager)
        // Carries one line per rear camera actually tried, so a total failure
        // has something more specific to show than "couldn't start the
        // camera" - this is the only account of what the hardware itself said
        // no to, and until now it only ever reached EventLogger, which no one
        // reporting a problem by hand has access to. See publishFailureDiagnostics.
        val attempts = mutableListOf<String>()

        if (rearCandidates.isEmpty()) {
            reportError(R_STRING_CAMERA_SETUP_FAILED)
            publishFailureDiagnostics(rearCount = 0, attempts = attempts)
            return
        }

        // Single camera only while stealth landscape is on.
        val wantDual = AppSettings.dualCameraEnabled && !AppSettings.stealthLandscapeEnabled
        var configured = false

        if (wantDual) {
            val pair = CameraSelection.concurrentPair(cameraManager, rearCandidates)
            if (pair != null) {
                val rear = rearCandidates.first { it.cameraID == pair.first }
                configured = runCatching { configureDual(rear, frontID = pair.second) }
                    .onFailure {
                        EventLogger.log("capture.dual_fallback", detail = it.message)
                        attempts += "dual camera=${rear.cameraID}: threw ${it.message}"
                        teardown()
                    }
                    .getOrDefault(false)
            } else {
                EventLogger.log("capture.dual_fallback", detail = "no concurrent camera pair")
            }
        }

        if (!configured) {
            for (rear in rearCandidates) {
                // `configureSingle` reports a refusal by *returning false*, not
                // by throwing, so a runCatching-only check logged nothing at
                // all on the commonest failure and left the user with a
                // message about permissions they had already granted.
                configured = runCatching { configureSingle(rear) }
                    .getOrElse { error ->
                        EventLogger.log(
                            "capture.single_attempt_threw",
                            detail = "camera=${rear.cameraID}: ${error.message}"
                        )
                        attempts += "camera=${rear.cameraID} (${rear.describe}): threw ${error.message}"
                        false
                    }
                if (configured) break
                if (attempts.lastOrNull()?.startsWith("camera=${rear.cameraID}") != true) {
                    EventLogger.log(
                        "capture.single_attempt_failed",
                        detail = "camera=${rear.cameraID} declined the configuration"
                    )
                    attempts += "camera=${rear.cameraID} (${rear.describe}): declined the configuration"
                }
                teardown()
            }
        }

        if (!configured) {
            reportError(R_STRING_CAMERA_SETUP_FAILED)
            publishFailureDiagnostics(rearCount = rearCandidates.size, attempts = attempts)
            return
        }

        recordingSessionID = UUID.randomUUID().toString()
        // Part of the active crew, if there is one: stamped now, before the
        // first piece exists, so every piece of the session carries it.
        CrewStore.stampSession(recordingSessionID)
        rotationGeneration = 0
        rotationPending = false
        chunkStartLocation = null
        isCapturing = true
        onConfigured?.invoke(dualMode)
        publishPreviewGeometry()
        publishDiagnostics()
    }

    /**
     * The Pro page's diagnostics text on a total setup failure.
     *
     * [publishDiagnostics] reads back from the active stream, which on a
     * total failure never existed - so it silently leaves whatever was on
     * screen before (usually nothing, on a first launch), and the on-screen
     * error's own advice to "see Diagnostics" led nowhere. This is what
     * should have been there instead: which cameras this device reported,
     * what was asked of each one, and specifically why each attempt was
     * refused - everything short of a live device, for a report made by
     * someone who cannot attach one.
     */
    private fun publishFailureDiagnostics(rearCount: Int, attempts: List<String>) {
        val text = buildString {
            appendLine("Camera setup failed.")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Rear cameras found: $rearCount")
            appendLine(
                "Requested: ${AppSettings.videoResolution.width}x${AppSettings.videoResolution.height} " +
                    "@ ${AppSettings.videoFrameRate.fps}fps, ${AppSettings.videoCodec.wire}" +
                    if (AppSettings.hdrRecordingEnabled) ", HDR" else ""
            )
            if (attempts.isEmpty()) {
                appendLine("No attempt reached the camera - none passed basic capability checks.")
            } else {
                appendLine("Attempts:")
                attempts.forEach { appendLine("  $it") }
            }
        }.trim()
        onDiagnostics?.invoke(text)
    }

    /**
     * Reports what the camera was *actually* configured to do, read back from
     * the active stream rather than from the settings that were asked for.
     * HDR has several independent ways to end up not running - the
     * subscription, the codec, the hardware, the chosen camera - and each
     * looks identical in the footage otherwise: this makes the outcome
     * legible instead.
     */
    /**
     * The shape each camera's frames arrive on screen at.
     *
     * Deliberately *not* derived from [DeviceOrientation]. The viewfinder shows
     * the frame being recorded and does nothing else: it does not turn with the
     * phone, and it does not behave differently depending on how the phone was
     * held when recording started. A viewfinder attached to a camera has never
     * needed to adapt - turning the camera turns the viewfinder with it, and
     * what it shows stays what is being filmed.
     *
     * What it does need is the right shape. The platform composites a camera
     * preview into a [Surface] already turned to match the display, and the
     * interface here is locked to portrait, so a sensor mounted at a quarter
     * turn - which is to say nearly every phone - delivers a landscape buffer
     * that lands on screen as a portrait picture. Left to fill the view, it is
     * shown squashed into a shape it does not have.
     */
    private fun publishPreviewGeometry() {
        fun geometry(stream: Stream?): PreviewGeometry? {
            val s = stream ?: return null
            // The turn the footage itself is given, not just the sensor's
            // mounting: the interface is locked to portrait but the recording
            // follows how the phone is held, so a phone held on its side
            // films a landscape frame. The viewfinder shows that frame the
            // right way up, at its own proportions - rotating by the sensor
            // alone would leave it lying on its side while the file is fine.
            return PreviewGeometry(
                s.previewSize, s.uprightRotation % 360, s.sensorOrientation % 360,
                stripGuide = s.stealth,
            )
        }
        val back = geometry(backStream) ?: return
        onPreviewGeometry?.invoke(back, geometry(frontStream))
    }

    private fun publishDiagnostics() {
        val stream = backStream ?: return
        val text = buildString {
            appendLine("Pro active: ${if (AppSettings.isProEntitled) "yes" else "no"}")
            appendLine("Codec in use: ${AppSettings.videoCodec.wire}")
            appendLine("HDR switched on: ${if (Prefs.getBool(AppSettings.hdrRecordingKey, false)) "yes" else "no"}")
            appendLine("HDR actually requested: ${if (AppSettings.hdrRecordingEnabled) "yes" else "no"}")
            appendLine("HDR active: ${if (stream.hdrActive) "yes" else "no"}")
            appendLine("Camera: ${if (rearCameraSupportsCrossover) "logical(0.5-1x crossover)" else "wide(1x)"}${if (dualMode) " (dual)" else ""}")
            appendLine(
                "Lens: ${if (rearCameraSupportsCrossover) "0.5x reachable" else "1x only"} " +
                    "now at ${String.format(java.util.Locale.US, "%.2f", currentLensMultiplier)}x"
            )
            appendLine("${stream.size.width}x${stream.size.height} @ ${AppSettings.videoFrameRate.fps} fps")
            if (stream.stealth) {
                appendLine(
                    "Stealth landscape: ${stream.outputSize.width}x${stream.outputSize.height} strip " +
                        "cut from the ${stream.size.width}x${stream.size.height} frame"
                )
            } else if (AppSettings.stealthLandscapeEnabled) {
                appendLine("Stealth landscape: asked for, not used (phone on its side, or no 4:3 frame)")
            }
            appendLine(
                "Stabilization: asked ${AppSettings.stabilization.wire}, " +
                    "using ${stream.stabilizationUsed?.wire ?: "none"}"
            )
            appendLine("This device offers HDR: ${if (CameraSelection.deviceSupportsHdr()) "yes" else "no"}")
            appendLine(rotationSummary())
        }.trim()
        onDiagnostics?.invoke(text)
    }

    /**
     * What the rotation actually came out as, for the Pro page.
     *
     * Orientation is a thing that can only really be confirmed in the field -
     * an emulator has no accelerometer worth the name and no camera at all -
     * so this prints the three numbers that decide it: each sensor's own
     * mounting, the quarter turn the phone was at when recording began, and
     * the hint those two produced.
     */
    private fun rotationSummary(): String {
        val parts = activeStreams.map { stream ->
            val name = if (stream.camera == CameraSource.FRONT) "front" else "rear"
            "$name sensor ${stream.sensorOrientation}deg -> wrote ${stream.orientationDegrees}deg"
        }
        return "Rotation: ${parts.joinToString(" - ")} - phone ${DeviceOrientation.heldDescription()}"
    }

    private fun configureSingle(rear: CameraSelection.RearCamera): Boolean {
        // Stealth landscape first, when it is on and the phone is upright. A
        // refusal anywhere in it falls back to an ordinary recording rather
        // than costing the person their recording altogether.
        val stealthSize = stealthCameraSize(rear)
        if (stealthSize != null) {
            if (configureSingleWith(rear, stealthSize)) return true
            EventLogger.log("capture.stealth_fallback", detail = "camera=${rear.cameraID}")
            teardown()
        }
        return configureSingleWith(rear, stealthSize = null)
    }

    /**
     * The camera frame stealth landscape would record from, or null when it
     * should not apply: it is off, the phone is held on its side (the file is
     * landscape already, so there is nothing to cut), or the camera has no
     * 4:3 frame to cut a strip from.
     */
    private fun stealthCameraSize(rear: CameraSelection.RearCamera): Size? {
        if (!AppSettings.stealthLandscapeEnabled) return null
        val held = DeviceOrientation.snapshot
        if (held == 90 || held == 270) return null
        val maxShortSide = if (AppSettings.videoResolution == VideoResolution.UHD4K) 2160 else 1920
        return CameraSelection.stealthCameraSize(rear.characteristics, maxShortSide)
    }

    private fun configureSingleWith(rear: CameraSelection.RearCamera, stealthSize: Size?): Boolean {
        // Widest field of view the sensor offers: a 16:9 stream is a crop of
        // the (4:3) sensor, so the frame loses a quarter of its width when
        // held upright. See CameraSelection.recordingSize.
        val size = stealthSize ?: CameraSelection.recordingSize(
            rear.characteristics,
            targetHeight = AppSettings.videoResolution.height,
            widestFieldOfView = true,
        )
        startAudio(rear.characteristics)

        val stream = openStream(
            camera = CameraSource.BACK,
            cameraID = rear.cameraID,
            characteristics = rear.characteristics,
            size = size,
            previewSurface = backPreviewSurface,
            outputSize = stealthSize?.let { CameraSelection.stripSize(it.height) } ?: size,
            stealth = stealthSize != null,
        ) ?: return false

        backStream = stream
        frontStream = null
        dualMode = false
        adoptRear(rear)
        applyStartingLens(rear)
        logChosenCamera(rear, dual = false, size = size)
        return true
    }

    private fun configureDual(rear: CameraSelection.RearCamera, frontID: String): Boolean {
        val frontCharacteristics = runCatching { manager.getCameraCharacteristics(frontID) }
            .getOrNull() ?: return false

        val height = CameraSelection.CONCURRENT_TARGET_HEIGHT
        val rearSize = CameraSelection.recordingSize(rear.characteristics, targetHeight = height)
        val frontSize = CameraSelection.recordingSize(
            frontCharacteristics, targetHeight = height, widestFieldOfView = true
        )

        startAudio(rear.characteristics)

        val back = openStream(
            camera = CameraSource.BACK,
            cameraID = rear.cameraID,
            characteristics = rear.characteristics,
            size = rearSize,
            previewSurface = backPreviewSurface,
        ) ?: return false

        val front = openStream(
            camera = CameraSource.FRONT,
            cameraID = frontID,
            characteristics = frontCharacteristics,
            size = frontSize,
            previewSurface = frontPreviewSurface,
        ) ?: run {
            back.close()
            return false
        }

        backStream = back
        frontStream = front
        dualMode = true
        adoptRear(rear)
        applyStartingLens(rear)
        logChosenCamera(rear, dual = true, size = rearSize)
        return true
    }

    private fun adoptRear(rear: CameraSelection.RearCamera) {
        rearCharacteristics = rear.characteristics
        rearCameraSupportsCrossover = rear.canCrossToUltraWide
    }

    /**
     * Each recording starts at the preferred default lens, even when a prior
     * recording left the live control on a different setting.
     */
    private fun applyStartingLens(rear: CameraSelection.RearCamera) {
        val starting = CameraSelection.startingLens()
        currentLensMultiplier = if (rear.canCrossToUltraWide) {
            starting
        } else {
            // A fixed ultra-wide sensor already *is* 0.5x; anything else is 1x.
            if (rear.isDedicatedUltraWide) 0.5 else 1.0
        }
        if (rear.canCrossToUltraWide) {
            runCatching {
                backStream?.applyZoom(CameraSelection.zoomRatio(starting, rear.characteristics))
            }
        }
        onLensChanged?.invoke(currentLensMultiplier)
    }

    private fun startAudio(rearCharacteristics: CameraCharacteristics) {
        val timestampSource = rearCharacteristics
            .get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        val realtime = timestampSource ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

        val encoder = AudioEncoder(sink = audioSink, useRealtimeClock = realtime)
        val gate = CountDownLatch(1)
        audioFormatGate = gate
        audioEncoder = if (encoder.start()) encoder else null

        // A muxer's tracks must all be declared before it starts, so a chunk
        // opened before the audio encoder has published its format would have
        // no audio track at all - a silent first 15 seconds. Audio is started
        // before the camera and briefly waited on here, so by the time the
        // first video keyframe arrives the format is known. If the microphone
        // never produces one, recording proceeds silently rather than not at
        // all.
        if (audioEncoder != null && !gate.await(1_500, TimeUnit.MILLISECONDS)) {
            EventLogger.log("capture.audio_format_timeout")
        }
    }

    private fun logChosenCamera(rear: CameraSelection.RearCamera, dual: Boolean, size: Size) {
        EventLogger.log(
            "capture.configured",
            detail = "${if (dual) "dual" else "single"} rear=${rear.describe} " +
                "${size.width}x${size.height} lens=${currentLensMultiplier}x"
        )
    }

    /**
     * Feeds [CameraOverlayRenderer.updateText] roughly once a second for as
     * long as the stream runs. The renderer itself only re-renders and
     * re-uploads when the text actually changes, so ticking this at a
     * plain fixed interval - rather than trying to align it to whole
     * seconds - costs nothing extra.
     */
    private fun scheduleOverlayTextTick(stream: Stream) {
        val renderer = stream.overlayRenderer ?: return
        // Without Pro the stamp also reads "Recorded with Witness".
        val brandLine = if (AppSettings.timestampBranded) {
            WitnessApplication.appContext.getString(R.string.recorded_with_witness)
        } else null
        val runnable = object : Runnable {
            override fun run() {
                val current = stream.overlayRenderer ?: return
                current.updateText(
                    TimestampOverlayText.lines(
                        java.util.Date(), LocationService.lastLocation, brandLine = brandLine,
                    )
                )
                cameraHandler.postDelayed(this, 1_000)
            }
        }
        stream.overlayTickRunnable = runnable
        cameraHandler.post(runnable)
    }

    private fun reportError(message: String) {
        endOrientationObserving()
        EventLogger.log("capture.setup_failed", detail = message)
        onError?.invoke(message)
    }

    /**
     * Paired through a flag rather than by matching calls: setup can fail on
     * several paths, and an unbalanced release would leave the accelerometer
     * subscribed for the life of the process.
     */
    private var observingOrientation = false

    private fun beginOrientationObserving() {
        if (observingOrientation) return
        observingOrientation = true
        DeviceOrientation.beginObserving()
    }

    private fun endOrientationObserving() {
        if (!observingOrientation) return
        observingOrientation = false
        DeviceOrientation.endObserving()
    }

    // MARK: - Stream lifecycle

    @SuppressLint("MissingPermission")
    private fun openStream(
        camera: CameraSource,
        cameraID: String,
        characteristics: CameraCharacteristics,
        size: Size,
        previewSurface: Surface?,
        outputSize: Size = size,
        stealth: Boolean = false,
    ): Stream? {
        val stream = Stream(camera, characteristics, size, outputSize, stealth)
        val device = openDevice(cameraID) ?: run {
            EventLogger.log(
                "capture.open_device_failed",
                detail = "camera=$cameraID ${camera.wire}"
            )
            return null
        }
        stream.device = device

        val wantsHdr = AppSettings.hdrRecordingEnabled && CameraSelection.supportsHdr(characteristics)
        val encoder = VideoEncoder(
            outputSize.width, outputSize.height,
            frameRate = AppSettings.videoFrameRate.fps,
            codec = AppSettings.videoCodec,
            hdr = wantsHdr,
            sink = stream,
        )
        stream.encoder = encoder
        encoder.start()

        // Inserted between the camera and the encoder only when the setting
        // is on; a failure here falls back to feeding the camera straight
        // into the encoder, exactly as if the setting were off, rather than
        // failing the whole recording over a cosmetic overlay.
        //
        // Stealth landscape always needs it - the strip is cut here - and has
        // no fallback of its own: the caller retries as an ordinary recording.
        val overlayRenderer = if (AppSettings.timestampBurnInEnabled || stealth) {
            runCatching {
                CameraOverlayRenderer(
                    encoder.inputSurface, outputSize.width, outputSize.height, stream.orientationDegrees,
                    cameraWidth = size.width, cameraHeight = size.height,
                    stripRotationDegrees = if (stealth) stream.uprightRotation else null,
                )
            }.onFailure {
                EventLogger.log("capture.overlay_unavailable", detail = "${camera.wire}: ${it.message}")
            }.getOrNull()
        } else null
        if (stealth && overlayRenderer == null) {
            stream.close()
            return null
        }
        stream.overlayRenderer = overlayRenderer
        val cameraTargetSurface = overlayRenderer?.cameraInputSurface ?: encoder.inputSurface

        val session = createSession(
            device, cameraTargetSurface, previewSurface, hdr = wantsHdr, videoSize = size,
        ) ?: run {
            // Already logged by createSession, which knows which of refused,
            // timed out, or threw it was.
            stream.close()
            return null
        }
        stream.session = session
        stream.targets = listOfNotNull(cameraTargetSurface, previewSurface)
        stream.hdrActive = wantsHdr
        if (overlayRenderer != null && AppSettings.timestampBurnInEnabled) scheduleOverlayTextTick(stream)

        if (stream.startRepeating()) return stream
        // startRepeating logs its own reason.
        stream.close()
        return null
    }

    @SuppressLint("MissingPermission")
    private fun openDevice(cameraID: String): CameraDevice? {
        val latch = CountDownLatch(1)
        var opened: CameraDevice? = null

        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                opened = device
                latch.countDown()
            }

            override fun onDisconnected(device: CameraDevice) {
                // Another app took the camera, or the device was unplugged.
                // Finalize what we have so it uploads even if this never ends.
                EventLogger.log("capture.interrupted", detail = "camera $cameraID disconnected")
                device.close()
                latch.countDown()
                flushCurrentChunk()
            }

            override fun onError(device: CameraDevice, error: Int) {
                EventLogger.log("capture.runtime_error", detail = "camera $cameraID error $error")
                device.close()
                latch.countDown()
                flushCurrentChunk()
            }
        }

        return try {
            manager.openCamera(cameraID, cameraExecutor, callback)
            latch.await(5, TimeUnit.SECONDS)
            opened
        } catch (error: CameraAccessException) {
            EventLogger.log("capture.open_failed", detail = "$cameraID: ${error.message}")
            null
        } catch (error: SecurityException) {
            EventLogger.log("capture.permission_denied", detail = error.message)
            null
        }
    }

    /**
     * Builds the session's outputs. Only the *video* output carries the HLG10
     * dynamic-range profile when HDR is requested; the preview surface stays
     * on the standard profile, which Android supports mixing within one
     * session - there is no reason the on-screen viewfinder needs the wider
     * range the recording itself does.
     */
    private fun createSession(
        device: CameraDevice, videoSurface: Surface, previewSurface: Surface?, hdr: Boolean,
        videoSize: Size,
    ): CameraCaptureSession? {
        val latch = CountDownLatch(1)
        var configured: CameraCaptureSession? = null

        // What was asked of the camera, kept for the failure paths below. A
        // refused configuration says nothing about *which* output it could not
        // honour, so the only way to tell a preview problem from an encoder
        // one is to record what was in the request.
        val requested = buildString {
            append("video=${videoSize.width}x${videoSize.height}")
            append(" preview=${if (previewSurface != null) "yes" else "no"}")
            append(" hdr=$hdr")
        }

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                configured = session
                latch.countDown()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                // The camera refused this combination of outputs outright.
                EventLogger.log("capture.session_configure_failed", detail = requested)
                latch.countDown()
            }
        }

        val videoOutput = OutputConfiguration(videoSurface).apply {
            if (hdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                dynamicRangeProfile = android.hardware.camera2.params.DynamicRangeProfiles.HLG10
            }
        }
        val outputs = listOfNotNull(videoOutput, previewSurface?.let { OutputConfiguration(it) })

        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outputs, cameraExecutor, callback,
        )
        return runCatching {
            device.createCaptureSession(configuration)
            if (!latch.await(5, TimeUnit.SECONDS)) {
                EventLogger.log("capture.session_configure_timeout", detail = requested)
            }
            configured
        }.getOrElse {
            EventLogger.log(
                "capture.session_create_threw", detail = "$requested: ${it.message}"
            )
            null
        }
    }

    private fun teardown() {
        audioEncoder?.stop()
        audioEncoder = null
        audioFormat = null
        backStream?.close()
        frontStream?.close()
        backStream = null
        frontStream = null
    }

    // MARK: - Chunk rotation (writerLock only)

    private val activeStreams: List<Stream>
        get() = listOfNotNull(backStream, frontStream)

    /**
     * Marks every stream for rotation. Each one actually swaps at its own next
     * keyframe, so the two cameras stay aligned to within one keyframe
     * interval while neither drops a frame.
     */
    private fun requestRotation() {
        val streams = activeStreams
        if (streams.isEmpty() || rotationPending) return

        rotationPending = true
        rotationGeneration += 1
        // The location that will be stamped into the chunks about to open.
        chunkStartLocation = LocationService.lastLocation

        for (stream in streams) {
            stream.rotationRequested = true
            stream.encoder?.requestKeyFrame()
        }
    }

    /** Closes the current writers and reports each finished chunk. */
    private fun finalizeWriters(@Suppress("UNUSED_PARAMETER") sessionEnding: Boolean) {
        val sessionID = recordingSessionID
        val location = chunkStartLocation

        for (stream in activeStreams) {
            val writer = stream.writer ?: continue
            stream.writer = null
            val endedAt = writer.finish() ?: continue
            onChunkFinalized?.invoke(
                FinalizedChunk(
                    file = writer.outputFile,
                    sessionID = sessionID,
                    index = stream.writerIndex,
                    camera = stream.camera,
                    startedAt = writer.startedAt,
                    endedAt = endedAt,
                    location = location,
                )
            )
        }
    }

    /**
     * Swaps one stream onto a fresh chunk at a keyframe boundary. The other
     * stream keeps writing into its own current chunk until its own keyframe
     * arrives, which is why each writer is finalized independently here rather
     * than through [finalizeWriters].
     */
    private fun rollStream(stream: Stream) {
        val sessionID = recordingSessionID
        // The chunk being closed carries the number it was opened with, not
        // whatever the counter has since moved on to.
        val index = stream.writerIndex
        val location = chunkStartLocation

        val previous = stream.writer
        stream.writer = null
        stream.rotationRequested = false
        // The rotation is complete once every stream has reached its keyframe.
        if (activeStreams.none { it.rotationRequested }) rotationPending = false

        if (previous != null) {
            // Muxer finalization touches the filesystem; keeping it off the
            // encoder's drain thread means a slow write can never stall
            // encoding and drop frames.
            finalizeExecutor.execute {
                val endedAt = previous.finish()
                if (endedAt != null) {
                    onChunkFinalized?.invoke(
                        FinalizedChunk(
                            file = previous.outputFile,
                            sessionID = sessionID,
                            index = index,
                            camera = stream.camera,
                            startedAt = previous.startedAt,
                            endedAt = endedAt,
                            location = location,
                        )
                    )
                }
            }
        }

    }

    private fun newWriter(stream: Stream): ChunkWriter? {
        if (chunkStartLocation == null) chunkStartLocation = LocationService.lastLocation
        // Stamped onto the stream so the chunk keeps this number when it is
        // closed, however far the counter has advanced by then.
        stream.writerIndex = rotationGeneration
        val name = "chunk_%s_%04d_%s.mp4".format(
            recordingSessionID, stream.writerIndex, stream.camera.wire
        )
        val file = File(ChunkStore.chunksDirectory, name)
        file.delete()

        val writer = runCatching {
            ChunkWriter(file, chunkStartLocation, stream.orientationDegrees)
        }.getOrElse {
            EventLogger.log("chunk.writer_failed", detail = it.message)
            return null
        }

        val videoFormat = stream.videoFormat ?: return null
        writer.addVideoTrack(videoFormat)
        audioFormat?.let { writer.addAudioTrack(it) }
        return if (writer.startIfReady()) writer else null
    }

    // MARK: - Audio routing

    /**
     * One microphone stream, written into every active camera's chunk, so each
     * camera's file is independently usable.
     */
    private val audioSink = object : EncoderSink {
        override fun onFormat(format: MediaFormat) {
            synchronized(writerLock) { audioFormat = format }
            audioFormatGate?.countDown()
        }

        override fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (!isCapturing) return
            synchronized(writerLock) {
                for (stream in activeStreams) {
                    val writer = stream.writer ?: continue
                    val position = buffer.position()
                    val limit = buffer.limit()
                    writer.writeAudio(buffer, info)
                    // Rewind for the next camera's writer - two muxers read the
                    // same buffer in dual-camera mode.
                    buffer.position(position)
                    buffer.limit(limit)
                }
            }
        }
    }

    // MARK: - One camera's pipeline

    private inner class Stream(
        val camera: CameraSource,
        val characteristics: CameraCharacteristics,
        /** The camera's own frame size. */
        val size: Size,
        /** What the encoder is given: [size], or the landscape strip cut from it. */
        val outputSize: Size = size,
        /** Stealth landscape: [outputSize] is a strip cut from the upright [size] frame. */
        val stealth: Boolean = false,
    ) : EncoderSink {

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var encoder: VideoEncoder? = null
        var targets: List<Surface> = emptyList()
        var overlayRenderer: CameraOverlayRenderer? = null
        var overlayTickRunnable: Runnable? = null

        /** Whether this stream's video output is actually running the HLG10 profile. */
        var hdrActive = false

        var writer: ChunkWriter? = null
        var videoFormat: MediaFormat? = null
        var rotationRequested = false

        /** Which chunk of the recording [writer] is producing. */
        var writerIndex = 0

        private var requestBuilder: CaptureRequest.Builder? = null
        private var zoomRatio: Float = 1f

        val sensorOrientation: Int =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        /** Buffer size the viewfinder's SurfaceTexture is driven at. */
        val previewSize: Size = CameraSelection.previewSize(characteristics, size)

        /**
         * How far the footage must be turned for playback, written into the
         * container rather than baked into the pixels.
         *
         * A snapshot, taken when the stream opens and fixed for the whole
         * recording. Deliberately not a subscription: the encoder is
         * configured once at a fixed frame size and lives across every chunk
         * boundary, so a mid-recording change could only be a container hint
         * that disagrees with the pixels it describes - and the session merge
         * would then have to letterbox whichever half disagreed with the
         * first. Fixing it at the start is also the better behaviour for a
         * phone being carried through an encounter.
         */
        /** The turn that puts this camera's frame upright for how the phone is held. */
        val uprightRotation: Int = DeviceOrientation.orientationHint(
            sensorOrientation, isFront = camera == CameraSource.FRONT
        )

        /**
         * What the container is told to rotate by at playback. Stealth
         * landscape cuts its strip from the already-upright frame, so the
         * file is landscape as written and needs no rotation.
         */
        val orientationDegrees: Int = if (stealth) 0 else uprightRotation

        fun startRepeating(): Boolean {
            val device = device ?: return false
            val session = session ?: return false
            val builder = runCatching {
                device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            }.getOrNull() ?: return false

            targets.forEach { builder.addTarget(it) }
            builder.set(CaptureRequest.CONTROL_MODE, android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO)
            val fps = AppSettings.videoFrameRate.fps
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            // Stabilization to match the stock camera app, where offered.
            applyStabilization(builder)
            if (zoomRatio != 1f) {
                builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            }
            requestBuilder = builder

            return runCatching {
                session.setRepeatingRequest(builder.build(), null, cameraHandler)
                true
            }.getOrElse {
                EventLogger.log("capture.repeating_failed", detail = it.message)
                false
            }
        }

        /**
         * What stabilization this stream is actually using, for the
         * diagnostics: the choice asked for can fall back to a nearer one.
         */
        var stabilizationUsed: AppSettings.Stabilization? = null

        private fun applyStabilization(builder: CaptureRequest.Builder) {
            val plan = CameraSelection.stabilizationPlan(characteristics, AppSettings.stabilization)
            stabilizationUsed = plan.used
            plan.electronic?.let {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it)
            }
            if (plan.optical) {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
            } else if (plan.opticalOff) {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
            }
        }

        /** Applies a new zoom ratio to the live repeating request - no gap. */
        fun applyZoom(ratio: Float) {
            zoomRatio = ratio
            val builder = requestBuilder ?: return
            val session = session ?: return
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        }

        override fun onFormat(format: MediaFormat) {
            synchronized(writerLock) { videoFormat = format }
        }

        override fun onSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (!isCapturing) return
            val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

            synchronized(writerLock) {
                if (writer == null || (rotationRequested && isKeyFrame)) {
                    // A chunk can only open on a keyframe, or it would start
                    // with undecodable frames.
                    if (!isKeyFrame) {
                        encoder?.requestKeyFrame()
                        return
                    }
                    if (writer != null) rollStream(this)
                    writer = newWriter(this)
                    rotationRequested = false
                }

                val active = writer ?: return
                active.writeVideo(buffer, info)

                // The back camera drives the clock; when its chunk is full,
                // every camera is asked to rotate so the streams stay aligned.
                if (camera == CameraSource.BACK &&
                    !rotationRequested &&
                    active.elapsedSeconds >= chunkSeconds
                ) {
                    requestRotation()
                }
            }
        }

        fun close() {
            overlayTickRunnable?.let { cameraHandler.removeCallbacks(it) }
            overlayTickRunnable = null
            overlayRenderer?.release()
            overlayRenderer = null
            runCatching { session?.close() }
            runCatching { device?.close() }
            encoder?.stop()
            session = null
            device = null
            encoder = null
            synchronized(writerLock) {
                writer?.finish()
                writer = null
            }
        }
    }

    companion object {
        /**
         * Whether this device can record both cameras at once. Android
         * publishes concurrent-camera support explicitly, so this is a real
         * answer rather than a guess.
         */
        /**
         * Which stabilization choices the rear camera can deliver as asked, or
         * null when there is no rear camera to ask.
         */
        fun rearStabilizationSupport(context: Context): Set<AppSettings.Stabilization>? {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
            val rear = CameraSelection.rearCandidates(manager).firstOrNull() ?: return null
            return CameraSelection.stabilizationSupport(rear.characteristics)
        }

        fun isDualCameraSupported(context: Context): Boolean {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return false
            return runCatching { manager.concurrentCameraIds.isNotEmpty() }.getOrDefault(false)
        }

        fun startingLens(): Double = CameraSelection.startingLens()

        /**
         * Set by [RecordingService] at startup so capture failures can be
         * reported in the user's language without this layer depending on a
         * Context.
         */
        /**
         * Deliberately does not blame permissions.
         *
         * It used to, and on the first device this was ever run on it was
         * wrong: camera permission was granted and the camera had opened
         * fine - the *capture session* was refused further down. Naming a
         * cause the app has not established sends the user to fix something
         * that is not broken, and the app cannot tell from here which of
         * several things the camera declined. The Pro page's diagnostics
         * carry the specifics; this just says what happened.
         */
        var R_STRING_CAMERA_SETUP_FAILED: String =
            "Couldn't start the camera. See Diagnostics on the Witness Pro page."
    }
}
