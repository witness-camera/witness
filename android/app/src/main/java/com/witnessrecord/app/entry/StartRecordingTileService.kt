package com.witnessrecord.app.entry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.witnessrecord.app.AppState
import com.witnessrecord.app.MainActivity
import com.witnessrecord.app.R

/**
 * The quick-settings tile - Android's closest equivalent to the iPhone Action
 * Button, and the instant-start path onboarding leads with.
 *
 * One pull down and one tap starts a recording from any screen, including
 * over the lock screen, without hunting for the app. The tile launches the
 * app through the same `witnessrecord://start` deep link every other
 * instant-start path uses, because camera capture needs the app in the
 * foreground - there is no way to start recording from the tile alone.
 */
class StartRecordingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (AppState.isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(R.string.tile_start_recording)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()

        val intent = Intent(Intent.ACTION_VIEW, AppState.startUri).apply {
            setClass(this@StartRecordingTileService, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        // The device may be locked: recording still needs the activity, so ask
        // the system to unlock first rather than silently doing nothing.
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                // Deprecated from API 34, which the branch above handles; on
                // 30-33 this is still the only way to launch from a tile.
                @Suppress("DEPRECATION")
                @SuppressLint("StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
    }
}
