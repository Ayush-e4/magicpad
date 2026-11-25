package app.magicpad

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Whether a pad session is live right now, for the tile. In-process: a dead app means not live. */
object PadState {
    @Volatile var live = false
        private set

    fun set(
        context: Context,
        live: Boolean,
    ) {
        if (this.live == live) return
        this.live = live
        TrackpadTile.refresh(context)
    }
}

/** Quick Settings "Trackpad": opens MagicPad and auto-connects to the last Mac. */
class TrackpadTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.label = getString(R.string.trackpad)
        tile.state = if (PadState.live) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = Store(this).lastMacName
        tile.updateTile()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { openTrackpad() } else openTrackpad()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // Only below API 34, where the PendingIntent overload is missing.
    private fun openTrackpad() {
        val intent = MainActivity.trackpadIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, flags))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Ask the system to call [onStartListening] so the tile shows the current state. */
        fun refresh(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, TrackpadTile::class.java))
            } catch (_: IllegalArgumentException) {
                // Not allowed for this user/profile; the tile updates next time it's shown.
            }
        }
    }
}
