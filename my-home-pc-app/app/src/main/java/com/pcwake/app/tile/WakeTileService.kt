package com.pcwake.app.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.pcwake.app.MainActivity
import com.pcwake.app.R
import com.pcwake.app.data.DeviceStateCache
import com.pcwake.app.data.SettingsRepository
import com.pcwake.app.work.StatusWatch

/**
 * One-tap Wake of the default device from Quick Settings. Deliberately single-purpose (no
 * "open app" second action — a tile has exactly one tap). Tile state mirrors the last-known
 * online/offline status from [DeviceStateCache]; the wake result itself arrives as a
 * notification from DeviceStatusWorker, since a collapsed QS panel can't show progress.
 */
class WakeTileService : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        val repository = SettingsRepository(this)
        val deviceId = repository.getDefaultDeviceId()

        if (deviceId.isBlank()) {
            tile.label = getString(R.string.tile_label_no_default)
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_subtitle_no_default)
            }
        } else {
            tile.label = getString(
                R.string.tile_label_wake,
                repository.getDefaultDeviceName().ifBlank { deviceId }
            )
            // Same "waking" flag the widget checks (DeviceStateCache) — without this, a wake
            // fired from this tile looks like it did nothing if the Quick Settings panel is
            // closed and reopened while DeviceStatusWorker is still polling for it to come up.
            val cache = DeviceStateCache(this)
            if (cache.isWaking(deviceId)) {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.tile_subtitle_wake_sent)
                }
            } else {
                tile.state = if (cache.isOnline(deviceId) == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = ""
                }
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        val repository = SettingsRepository(this)
        val deviceId = repository.getDefaultDeviceId()

        if (deviceId.isBlank()) {
            openAppForSetup()
            return
        }

        StatusWatch.wakeAndWatch(this, deviceId, repository.getDefaultDeviceName().ifBlank { deviceId })
        qsTile?.let { tile ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.tile_subtitle_wake_sent)
            }
            tile.updateTile()
        }
    }

    private fun openAppForSetup() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_SETTINGS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            // Plain-Intent overload; deprecated in favor of the PendingIntent one, but that
            // variant only exists on API 34+.
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
