package com.autogram.app.tiles

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.autogram.app.ui.phone.MainActivity

/**
 * Quick Settings tile that launches Autogram with an "auto-record" extra so
 * the user can start a voice message to their primary target straight from
 * the notification shade (or lock screen, with the system's unlock prompt).
 *
 * Tap behaviour:
 *   1. Tile is tapped from the QS panel.
 *   2. The system unlocks the device if needed (calls
 *      [startActivityAndCollapse] which is the standard QS pattern).
 *   3. MainActivity opens, sees [EXTRA_AUTO_RECORD] in its intent and asks
 *      `MainViewModel.startRecording(primaryId)` immediately so the user can
 *      hold-to-record without a second tap on the in-app circle.
 *
 * The tile stays in [Tile.STATE_INACTIVE] — we don't keep a recording
 * indicator on the QS panel because recording state lives in the
 * foreground service / activity, not the tile itself.
 */
class RecordVoiceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_AUTO_RECORD
            putExtra(EXTRA_AUTO_RECORD, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // Android 14+ requires PendingIntent variant of startActivityAndCollapse.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        const val ACTION_AUTO_RECORD = "com.autogram.app.action.AUTO_RECORD"
        const val EXTRA_AUTO_RECORD = "com.autogram.app.extra.AUTO_RECORD"
    }
}
