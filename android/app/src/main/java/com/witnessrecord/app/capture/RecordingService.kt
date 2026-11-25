package com.witnessrecord.app.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.witnessrecord.app.MainActivity
import com.witnessrecord.app.R
import com.witnessrecord.app.service.EventLogger

/**
 * Keeps the recording alive.
 *
 * Android will suspend an app's camera access the moment it stops being
 * foreground, so a recording that must survive the screen going off - or the
 * user being made to put the phone away - has to run inside a typed
 * foreground service. That is a real gain over the iOS build, where capture
 * stops when the app is backgrounded.
 *
 * The cost is that Android requires a foreground service to post a
 * notification. That fights the whole point of a covert black screen, so it is
 * mitigated as far as the platform allows: the channel is
 * [NotificationManager.IMPORTANCE_MIN], which suppresses the status-bar icon
 * and any sound or heads-up banner, and the notification is marked secret so
 * it does not render on the lock screen. It remains visible to anyone who
 * deliberately pulls down the notification shade - Android provides no way to
 * avoid that, and the text is deliberately plain rather than alarming.
 */
class RecordingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // `location` is requested only when the permission is actually held.
        // The platform refuses to start a foreground service declaring a type
        // whose permission is missing - as of the FGS type-permission checks
        // phased in from Android 14 onward, that refusal is a thrown
        // SecurityException, not a silent downgrade. Location is optional
        // onboarding (skippable, and it can be declined outright in system
        // Settings later), so recording must still work without it; asking
        // for the type unconditionally meant every recording attempt on a
        // phone without location granted crashed the whole app right here,
        // before the camera was ever touched. The manifest still declares all
        // three types - `camera|microphone|location` - which is required for
        // any of them to be requestable at runtime; this only ever asks for a
        // subset of that, never more.
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (hasLocationPermission()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)

        try {
            startForeground(NOTIFICATION_ID, buildNotification(), types)
        } catch (error: SecurityException) {
            // Camera or microphone permission revoked between the check in
            // CaptureViewModel and this call actually running - a system
            // Settings change mid-flow, or a platform quirk. Recording cannot
            // proceed without this call succeeding, but crashing the whole
            // app over it is strictly worse than a recording that fails to
            // start cleanly.
            EventLogger.log("service.start_foreground_failed", detail = error.message)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        EventLogger.log("service.started")
        // Recording state is owned by the capture layer, not restored here: a
        // service restarted by the system after a kill must not silently begin
        // recording again without the user asking.
        return START_NOT_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "witness:recording").apply {
            setReferenceCounted(false)
            // Bounded so a wake lock can never be leaked past a plausible
            // recording; the service re-acquires if it is still running.
            runCatching { acquire(4 * 60 * 60 * 1000L) }
        }
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        EventLogger.log("service.stopped")
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_recording),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notification_channel_recording_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(getString(R.string.notification_recording_body))
            .setSmallIcon(R.drawable.ic_record)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "witness_recording"
        private const val NOTIFICATION_ID = 4711
        const val ACTION_STOP = "com.witnessrecord.app.STOP_RECORDING"

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RecordingService::class.java)) }
        }
    }
}
