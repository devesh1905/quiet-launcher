package app.quiet.launcher.feature.quick

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.quiet.launcher.container
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Quick Settings tile: tap to start a 50-minute focus session with the last chosen apps
 * (or open the running session); the tile shows "Until 21:30" while one runs.
 */
class FocusTileService : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        if (QuickActions.focusRunning(this)) {
            val open = Intent(this, app.quiet.launcher.feature.focus.ui.FocusSessionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(this, 1, open, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                )
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(open)
            }
        } else {
            QuickActions.startFocus(this, 50)
        }
        qsTile?.let { it.state = Tile.STATE_ACTIVE; it.updateTile() }
    }

    private fun refresh() {
        val t = qsTile ?: return
        val session = app.quiet.launcher.feature.focus.FocusRuntime.get(this).repo.snapshot.value.session
        val running = session != null && session.endsMs > System.currentTimeMillis()
        t.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            t.subtitle = if (running) "Until " + app.quiet.launcher.core.platform.Format.clock(session!!.endsMs, android.text.format.DateFormat.is24HourFormat(this)) else "50 min"
        }
        t.updateTile()
    }
}

/**
 * Quick Settings tile: precision mode on or off by hand (for an app that still complains).
 * Without WRITE_SECURE_SETTINGS it opens the accessibility settings instead.
 */
class PrecisionTileService : TileService() {
    private val guard = app.quiet.launcher.feature.focus.PaymentGuard

    override fun onStartListening() { refresh() }

    override fun onClick() {
        val on = guard.precisionOn(this)
        if (!guard.setPrecision(this, !on)) {
            val open = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    android.app.PendingIntent.getActivity(this, 2, open, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                )
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(open)
            }
            return
        }
        qsTile?.let {
            it.state = if (on) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            if (android.os.Build.VERSION.SDK_INT >= 29) it.subtitle = if (on) "Off" else "On"
            it.updateTile()
        }
    }

    private fun refresh() {
        val t = qsTile ?: return
        val on = guard.precisionOn(this)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            t.subtitle = when {
                on -> "On"
                guard.isPaused(this) -> "Paused for payment"
                else -> "Off"
            }
        }
        t.updateTile()
    }
}

/** Quick Settings tile: grayscale for an hour, or off. */
class GrayscaleTileService : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        val on = runBlocking { QuickActions.grayscaleUntil(this@GrayscaleTileService) } > System.currentTimeMillis()
        QuickActions.setGrayscale(this, if (on) 0 else 60)
        qsTile?.let { it.state = if (on) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE; it.updateTile() }
    }

    private fun refresh() {
        val t = qsTile ?: return
        container.appScope.launch {
            val until = QuickActions.grayscaleUntil(this@GrayscaleTileService)
            val on = until > System.currentTimeMillis()
            t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                t.subtitle = if (on) "Until " + app.quiet.launcher.core.platform.Format.clock(until, android.text.format.DateFormat.is24HourFormat(this@GrayscaleTileService)) else "1 hour"
            }
            t.updateTile()
        }
    }
}
