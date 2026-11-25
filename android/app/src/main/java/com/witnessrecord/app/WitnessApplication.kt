package com.witnessrecord.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import com.witnessrecord.app.billing.ProEntitlement
import com.witnessrecord.app.capture.CaptureSessionController
import com.witnessrecord.app.service.ProUpsellPrompt
import com.witnessrecord.app.service.SessionStitcher
import com.witnessrecord.app.service.UploadManager

/**
 * Holds the process-wide application context so the service layer can be
 * written as singletons, mirroring the iOS build's `.shared` services
 * one-for-one instead of threading a Context through every call site.
 */
class WitnessApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // Once per process launch - exactly what the Pro upsell's "second
        // (and twentieth) time the app is opened" needs to count against.
        ProUpsellPrompt.recordAppOpened()

        // The capture layer has no Context of its own, and its setup failure is
        // user-facing. Resolved here rather than in RecordingService, whose
        // onCreate can lose the race with an instant-start recording.
        CaptureSessionController.R_STRING_CAMERA_SETUP_FAILED =
            getString(R.string.error_camera_setup_failed)

        ProEntitlement.initialize(this)

        WorkManager.initialize(
            this,
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()
        )

        // Order matters: recovery hands a pending merge straight to
        // UploadManager's callback, which only exists once the manager has
        // started. Sweeping first would drop a merge that a previous app
        // lifetime died before uploading - the one copy of that footage that
        // has not reached Drive.
        UploadManager.pump()
        SessionStitcher.cleanupOrphanedStaging()
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
