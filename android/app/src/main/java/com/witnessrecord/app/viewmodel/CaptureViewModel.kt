package com.witnessrecord.app.viewmodel

import android.Manifest
import android.content.pm.PackageManager
import android.os.StatFs
import android.view.Surface
import androidx.core.content.ContextCompat
import com.witnessrecord.app.R
import com.witnessrecord.app.WitnessApplication
import com.witnessrecord.app.capture.CaptureSessionController
import com.witnessrecord.app.capture.RecordingService
import com.witnessrecord.app.service.AppSettings
import com.witnessrecord.app.service.CaseLibrary
import com.witnessrecord.app.service.ChunkStore
import com.witnessrecord.app.service.CrewStore
import com.witnessrecord.app.service.EventLogger
import com.witnessrecord.app.service.LocalSessionRecord
import com.witnessrecord.app.service.LocationService
import com.witnessrecord.app.service.MomentMarkers
import com.witnessrecord.app.service.Prefs
import com.witnessrecord.app.service.SessionFolderNaming
import com.witnessrecord.app.service.SessionStitcher
import com.witnessrecord.app.service.UploadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Recording state for the whole process.
 *
 * A singleton rather than a per-screen ViewModel because a recording outlives
 * any one screen: it is started from a quick-settings tile or a widget before
 * the UI exists, survives the activity being destroyed, and is stopped from
 * whatever screen happens to be on top - the same role `CaptureViewModel`
 * plays on iOS as a member of the app-wide state.
 */
object CaptureViewModel {

    sealed interface State {
        data object Idle : State
        data class Recording(val since: Long) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _lowStorageWarning = MutableStateFlow<String?>(null)
    val lowStorageWarning: StateFlow<String?> = _lowStorageWarning.asStateFlow()

    /**
     * Whether the live viewfinder is shown instead of a black screen.
     *
     * Deliberately not persisted anywhere - no [AppSettings] key backs this.
     * It exists so Witness can double as an ordinary camera on top of being a
     * safety tool, but that convenience must never outrank the safety
     * default: every fresh launch starts black-screen regardless of what was
     * chosen last time, because a plain in-memory `false` here is what it
     * starts as. Set from Home's own toggle before recording (see
     * [setViewfinderPreference]), and still flippable mid-recording via the
     * existing eye-icon control, which affects only the rest of that one
     * recording.
     */
    private val _viewfinderVisible = MutableStateFlow(false)
    val viewfinderVisible: StateFlow<Boolean> = _viewfinderVisible.asStateFlow()

    /**
     * Whether the front camera is shown full-screen with the rear as the
     * inset, instead of the default rear-main arrangement. Only meaningful in
     * dual-camera mode. Not persisted, same reasoning as [viewfinderVisible]
     * and reset alongside it at the start of every recording - a per-session
     * choice for someone actively using dual capture, never a state that
     * quietly carries over.
     */
    private val _mainViewSwapped = MutableStateFlow(false)
    val mainViewSwapped: StateFlow<Boolean> = _mainViewSwapped.asStateFlow()

    /** Whether both cameras are actually recording (drives PiP visibility). */
    private val _dualActive = MutableStateFlow(false)
    val dualActive: StateFlow<Boolean> = _dualActive.asStateFlow()

    /**
     * Current rear-lens multiplier shown in the viewfinder control
     * (0.5 = ultra-wide, 1.0 = wide).
     */
    private val _lensMultiplier = MutableStateFlow(CaptureSessionController.startingLens())
    val lensMultiplier: StateFlow<Double> = _lensMultiplier.asStateFlow()

    /**
     * Whether the device can switch to 0.5x live (drives whether the lens
     * control is shown at all).
     */
    private val _lensControlAvailable = MutableStateFlow(false)
    val lensControlAvailable: StateFlow<Boolean> = _lensControlAvailable.asStateFlow()

    /** What the camera was actually configured to do, for the Pro page. */
    private val _captureDiagnostics = MutableStateFlow("")
    val captureDiagnostics: StateFlow<String> = _captureDiagnostics.asStateFlow()

    /**
     * What the viewfinder needs to draw each camera correctly: the size of the
     * buffer it receives and how far that buffer has to be turned to put the
     * scene upright. Null until the camera has been configured, which is when
     * either becomes knowable.
     */
    private val _previewGeometry = MutableStateFlow<CaptureSessionController.PreviewGeometry?>(null)
    val previewGeometry: StateFlow<CaptureSessionController.PreviewGeometry?> =
        _previewGeometry.asStateFlow()

    private val _frontPreviewGeometry =
        MutableStateFlow<CaptureSessionController.PreviewGeometry?>(null)
    val frontPreviewGeometry: StateFlow<CaptureSessionController.PreviewGeometry?> =
        _frontPreviewGeometry.asStateFlow()

    private val _lastMarkAt = MutableStateFlow<Long?>(null)
    /** The most recent moment mark, for the brief on-screen confirmation. */
    val lastMarkAt: StateFlow<Long?> = _lastMarkAt.asStateFlow()

    /**
     * Marks this moment of the recording (Pro). Silent apart from a light
     * haptic, so it can be done with the screen black or the phone in a
     * pocket, by the volume button.
     */
    fun addMarker() {
        if (!isRecording || !AppSettings.isProEntitled) return
        val now = System.currentTimeMillis()
        MomentMarkers.add(now)
        _lastMarkAt.value = now
        runCatching {
            val vibrator = WitnessApplication.appContext
                .getSystemService(android.os.Vibrator::class.java)
            vibrator?.vibrate(
                android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK)
            )
        }
        EventLogger.log("recording.marker", detail = null)
    }

    private val _permissionSettingsPromptNeeded = MutableStateFlow(false)
    /**
     * Set when camera or microphone access was refused and the system will not
     * ask again, so a plain error message would be a dead end. Home turns this
     * into an alert with a direct link to the app's Settings page, which is
     * the only way left to grant it.
     */
    val permissionSettingsPromptNeeded: StateFlow<Boolean> = _permissionSettingsPromptNeeded.asStateFlow()

    fun requirePermissionSettings() {
        _permissionSettingsPromptNeeded.value = true
    }

    fun dismissPermissionSettingsPrompt() {
        _permissionSettingsPromptNeeded.value = false
    }

    val isRecording: Boolean get() = _state.value is State.Recording

    private val controller = CaptureSessionController()
    private var storageJob: Job? = null

    init {
        controller.onConfigured = { dual ->
            _dualActive.value = dual
            _lensControlAvailable.value = controller.availableLensMultipliers().size > 1
        }
        controller.onLensChanged = { multiplier ->
            _lensMultiplier.value = multiplier
        }
        controller.onDiagnostics = { text ->
            _captureDiagnostics.value = text
        }
        controller.onPreviewGeometry = { back, front ->
            _previewGeometry.value = back
            _frontPreviewGeometry.value = front
        }
        controller.onChunkFinalized = { chunk ->
            if (AppSettings.isLocalOnlyCapture) {
                // The mastering tier never uploads, so nothing downstream
                // will ever confirm receipt and delete this file. Stage the
                // hard link the merge reads from, then drop the original
                // immediately - otherwise every chunk would be retained
                // twice over, at mastering sizes, until the device filled up.
                SessionStitcher.stage(chunk)
                // Its timing and place go into the session's own record
                // first: the piece is about to be deleted, and the manifest
                // for a recording kept only on the phone is built from these.
                LocalSessionRecord.noteChunk(chunk)
                chunk.file.delete()
            } else {
                // Stage a hard link for later stitching before the uploader
                // can delete the file on confirmed receipt, then hand the
                // chunk to the upload pipeline. Staging is skipped only when
                // *neither* the gallery backup nor the Drive-stitch feature
                // wants a merged video - the per-chunk upload is unaffected
                // either way.
                if (AppSettings.photosBackupEnabled || AppSettings.driveStitchEnabled) {
                    SessionStitcher.stage(chunk)
                }
                UploadManager.ingest(chunk)
            }
        }
        controller.onSessionFinalized = { sessionID ->
            // Merge the session's staged chunks into one video per camera -
            // saved to the gallery and/or handed to the Drive-stitch pipeline
            // depending on which of those features are enabled.
            //
            // The completion - not this closure's own start - is what tells
            // us the recording is genuinely done, since for a merge this
            // fires only once the merge itself (and the gallery save it may
            // trigger) has actually finished. Only then is it safe to let the
            // foreground service stop; stopping it eagerly (the old
            // behaviour) tore down the one thing protecting a still-running
            // merge from the process being reclaimed while backgrounded.
            // Filed under "Not in a case" at once, so Home can offer to add
            // details while the video is still being finished.
            val queued = ChunkStore.records(sessionID)
            val local = LocalSessionRecord.chunks(sessionID)
            val located = queued.firstOrNull { it.latitude != null }
            val locatedLocal = local.firstOrNull { it.latitude != null }
            CaseLibrary.registerRecording(
                sessionID = sessionID,
                startedAt = queued.minOfOrNull { it.startedAt } ?: local.minOfOrNull { it.startedAt },
                endedAt = queued.maxOfOrNull { it.endedAt } ?: local.maxOfOrNull { it.endedAt }
                    ?: System.currentTimeMillis(),
                latitude = located?.latitude ?: locatedLocal?.latitude,
                longitude = located?.longitude ?: locatedLocal?.longitude,
            )
            // A crew recording goes straight into the crew's case and onto its
            // timeline, under the folder name its footage is sent to.
            CrewStore.stamp(sessionID)?.let { stamp -> scope.launch {
                val startedAt = queued.minOfOrNull { it.startedAt } ?: local.minOfOrNull { it.startedAt }
                if (startedAt != null) {
                    val endedAt = queued.maxOfOrNull { it.endedAt } ?: local.maxOfOrNull { it.endedAt }
                        ?: System.currentTimeMillis()
                    CrewStore.crew(stamp.crewID)?.caseID?.let { caseID ->
                        CaseLibrary.items.value.firstOrNull { it.sessionID == sessionID }
                            ?.let { CaseLibrary.move(it.id, caseID.lowercase()) }
                    }
                    val cameras = (queued.filter { it.kind == com.witnessrecord.app.model.ChunkKind.MASTER }
                        .map { it.camera.wire } + local.map { it.camera }).distinct().sorted()
                    CrewStore.addRecording(
                        sessionID = sessionID, stamp = stamp, cameras = cameras.ifEmpty { listOf("back") },
                        startedAtMillis = startedAt, endedAtMillis = endedAt,
                        folderName = SessionFolderNaming.folderName(
                            sessionID, startedAt, located?.latitude ?: locatedLocal?.latitude,
                            located?.longitude ?: locatedLocal?.longitude,
                        ),
                    )
                }
            } }
            SessionStitcher.finishSession(sessionID) {
                // Nothing new has started in the meantime, or this would kill
                // the *new* recording's service out from under it.
                if (!isRecording) stopService()
            }
            // A local-only session has nothing remote for a manifest to
            // describe, so the backend is left untouched entirely rather than
            // receiving a receipt for footage that was never sent.
            if (!AppSettings.isLocalOnlyCapture) {
                // Write and upload the session's chain-of-custody manifest.
                UploadManager.finalizeSession(sessionID)
            }
        }
        controller.onError = { message ->
            _state.value = State.Failed(message)
            stopService()
        }
    }

    // MARK: - Preview plumbing

    /**
     * Preview outputs are handed to the capture layer before a recording
     * starts and stay attached for its whole duration, so toggling the
     * viewfinder never reconfigures the camera and never interrupts footage.
     */
    fun setPreviewSurfaces(back: Surface?, front: Surface?) {
        controller.setPreviewSurfaces(back, front)
    }

    // MARK: - Viewfinder

    /**
     * Toggles the live viewfinder on/off during (or before) recording and
     * remembers the choice as the default for next time.
     */
    fun toggleViewfinder() {
        _viewfinderVisible.value = !_viewfinderVisible.value
    }

    /**
     * Sets the pre-recording camera-vs-safety choice from Home's own toggle.
     * No screen/preview side effects - those only mean anything once actually
     * recording, which [toggleViewfinder] above still handles for
     * mid-recording changes.
     */
    fun setViewfinderPreference(isOn: Boolean) {
        _viewfinderVisible.value = isOn
    }

    /**
     * Swaps which camera is the main view and which is the small inset. Only
     * shown to the user while both the viewfinder is on and dual capture is
     * active - meaningless otherwise, since there is only one view to have an
     * inset over.
     */
    fun toggleMainViewSwap() {
        _mainViewSwapped.value = !_mainViewSwapped.value
    }

    // MARK: - Lens

    /**
     * Toggles the rear lens between 0.5x and 1x live, with no gap in the
     * recording. Only meaningful when [lensControlAvailable].
     */
    fun toggleLens() {
        val next = if (_lensMultiplier.value <= 0.5) 1.0 else 0.5
        _lensMultiplier.value = next // optimistic; controller confirms via callback
        controller.setLensMultiplier(next)
    }

    // MARK: - Start / stop

    val hasCapturePermissions: Boolean
        get() {
            val context = WitnessApplication.appContext
            val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            return camera == PackageManager.PERMISSION_GRANTED &&
                mic == PackageManager.PERMISSION_GRANTED
        }

    fun startRecording() {
        if (isRecording) return

        if (!hasCapturePermissions) {
            requirePermissionSettings()
            return
        }

        LocationService.startUpdating()

        // The foreground service is what lets capture survive the screen going
        // off or the app being backgrounded, so it must be up before the
        // camera opens.
        RecordingService.start(WitnessApplication.appContext)

        // Not reset here: whatever Home's toggle is currently showing is
        // exactly what this recording should start with.
        //
        // mainViewSwapped does start fresh every recording, unlike
        // viewfinderVisible - it has no Home-screen control setting it in
        // advance, so a swap chosen last time has nowhere to be read from.
        _mainViewSwapped.value = false
        controller.start()
        _state.value = State.Recording(System.currentTimeMillis())
        startStorageMonitor()

        EventLogger.log("recording.start", location = LocationService.lastLocation)
        UploadManager.recordingDidStart()
    }

    fun stopRecording() {
        if (!isRecording) return
        EventLogger.log("recording.stop", location = LocationService.lastLocation)

        // The foreground service is deliberately left running here - see
        // onSessionFinalized above. It stops itself once the session's merge
        // has actually finished, not when this function returns.
        controller.stop {
            UploadManager.recordingDidStop()
        }
        LocationService.stopUpdating()
        stopStorageMonitor()
        _state.value = State.Idle
    }

    fun resetFailure() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    private fun stopService() {
        RecordingService.stop(WitnessApplication.appContext)
    }

    /**
     * Called when the app is backgrounded mid-recording. Capture keeps running
     * inside the foreground service, but the in-flight chunk is finalized now
     * so everything captured so far starts uploading immediately rather than
     * waiting out the remainder of a 15-second window the user may never see
     * the end of.
     */
    fun appMovedToBackground() {
        if (!isRecording) return
        controller.flushCurrentChunk()
        EventLogger.log("app.backgrounded_while_recording")
    }

    fun appMovedToForeground() {
        if (!isRecording) return
        EventLogger.log("app.foregrounded_while_recording")
    }

    // MARK: - Storage monitoring (warn, never interrupt)

    private fun startStorageMonitor() {
        storageJob?.cancel()
        storageJob = scope.launch {
            while (true) {
                checkStorage()
                delay(30_000)
            }
        }
    }

    private fun stopStorageMonitor() {
        storageJob?.cancel()
        storageJob = null
        _lowStorageWarning.value = null
    }

    private fun checkStorage() {
        val context = WitnessApplication.appContext
        val stat = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull() ?: return
        val availableMB = stat.availableBytes / 1_000_000
        if (availableMB < 500) {
            _lowStorageWarning.value =
                context.getString(R.string.warning_storage_low, availableMB)
            EventLogger.log("storage.low", detail = "$availableMB MB")
        } else {
            _lowStorageWarning.value = null
        }
    }
}
