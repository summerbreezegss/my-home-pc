package com.pcwake.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.pcwake.app.MainActivity
import com.pcwake.app.R
import com.pcwake.app.data.DeviceStateCache
import com.pcwake.app.data.SettingsRepository
import com.pcwake.app.work.StatusWatch

/**
 * Square home-screen remote tile with three tap targets, all bound to the default device:
 * the header opens the app on that device's detail screen; On sends a Wake without opening
 * the app (the result arrives as a notification from DeviceStatusWorker); Off opens the app
 * with the Shutdown confirmation already up. Off deliberately isn't a one-tap fire — shutdown
 * is destructive and a widget can't show its own dialog, so the confirmation lives in the app
 * screen that already handles it. Off is hidden entirely for a device that doesn't report the
 * "shutdown" capability (cached by SettingsRepository so this render needs no network call).
 *
 * Click behavior is baked in at render time, not decided at click time: with no default
 * device chosen yet, the action row is hidden and the header opens Settings instead — a
 * receiver can't legally launch an activity from the background on modern Android, so the
 * "not configured" case must be an activity PendingIntent from the start. Anything that
 * changes the default device or the cached status calls [updateAll] to re-render.
 */
class WakeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, render(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WAKE) {
            val repository = SettingsRepository(context)
            val deviceId = repository.getDefaultDeviceId()
            if (deviceId.isNotBlank()) {
                StatusWatch.wakeAndWatch(context, deviceId, repository.getDefaultDeviceName().ifBlank { deviceId })
                // wakeAndWatch sets the "waking" flag before enqueueing, so this render
                // (and any later re-render while the worker polls) picks it up correctly.
                pushToAll(context, render(context))
            }
        }
    }

    companion object {
        private const val ACTION_WAKE = "com.pcwake.app.widget.ACTION_WAKE"

        /** Re-render every instance of the widget (no-op when none are placed). */
        fun updateAll(context: Context) {
            pushToAll(context, render(context))
        }

        private fun pushToAll(context: Context, views: RemoteViews) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, WakeWidgetProvider::class.java))
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun render(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_wake)
            val repository = SettingsRepository(context)
            val deviceId = repository.getDefaultDeviceId()

            if (deviceId.isBlank()) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_no_default_title))
                views.setTextViewText(R.id.widget_subtitle, context.getString(R.string.widget_no_default_subtitle))
                // No device to act on — hide the On/Off row and send the header to Settings.
                views.setViewVisibility(R.id.widget_action_row, View.GONE)
                views.setViewVisibility(R.id.widget_status_dot, View.GONE)
                views.setOnClickPendingIntent(
                    R.id.widget_header_zone,
                    activityPending(context, MainActivity.ACTION_OPEN_SETTINGS)
                )
                return views
            }

            val name = repository.getDefaultDeviceName().ifBlank { deviceId }
            views.setTextViewText(R.id.widget_title, name)
            val cache = DeviceStateCache(context)
            val subtitle = when {
                cache.isWaking(deviceId) -> context.getString(R.string.widget_subtitle_waking)
                else -> when (cache.isOnline(deviceId)) {
                    true -> context.getString(R.string.widget_subtitle_online)
                    false -> context.getString(R.string.widget_subtitle_offline)
                    null -> context.getString(R.string.widget_subtitle_unknown)
                }
            }
            views.setTextViewText(R.id.widget_subtitle, subtitle)

            // Recolour the status dot to match the subtitle: amber while a wake is pending,
            // green online, grey otherwise.
            val dotColor = when {
                cache.isWaking(deviceId) -> R.color.widget_status_waking
                cache.isOnline(deviceId) == true -> R.color.widget_status_online
                else -> R.color.widget_status_offline
            }
            views.setViewVisibility(R.id.widget_status_dot, View.VISIBLE)
            views.setInt(R.id.widget_status_dot, "setColorFilter", context.getColor(dotColor))

            views.setOnClickPendingIntent(
                R.id.widget_header_zone,
                activityPending(context, MainActivity.ACTION_OPEN_DEFAULT_DEVICE)
            )

            val wakeIntent = Intent(context, WakeWidgetProvider::class.java).setAction(ACTION_WAKE)
            views.setOnClickPendingIntent(
                R.id.widget_on_zone,
                PendingIntent.getBroadcast(
                    context, 0, wakeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            // Off shows only when the PC can actually be shut down remotely; otherwise it's a
            // dead button, so hide it and let On center in the row.
            if (repository.getDefaultDeviceShutdownSupported()) {
                views.setOnClickPendingIntent(
                    R.id.widget_off_zone,
                    activityPending(context, MainActivity.ACTION_SHUTDOWN_DEFAULT_DEVICE)
                )
            } else {
                views.setViewVisibility(R.id.widget_off_zone, View.GONE)
            }
            return views
        }

        private fun activityPending(context: Context, action: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                this.action = action
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            // Distinct requestCode per action — otherwise the two PendingIntents collapse
            // into one and the last-written intent wins for both tap targets.
            return PendingIntent.getActivity(
                context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
