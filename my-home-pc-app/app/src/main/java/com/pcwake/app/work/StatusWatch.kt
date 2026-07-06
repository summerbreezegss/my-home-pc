package com.pcwake.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.pcwake.app.data.DeviceStateCache
import com.pcwake.app.widget.WakeWidgetProvider

/**
 * The one place that enqueues/cancels [DeviceStatusWorker]. Unique-per-device so a newer
 * action's watch replaces an older one instead of both racing to notify.
 */
object StatusWatch {

    /** Watch an already-sent action's outcome (DeviceDetailScreen hands off to this on pause). */
    fun watch(context: Context, deviceId: String, deviceName: String, targetOnline: Boolean) {
        // Supersedes any watch already running for this device (e.g. a Restart replacing a
        // still-pending Wake watch) — that previous watch's "waking" flag no longer applies,
        // and WorkManager's own REPLACE would otherwise leave it stuck until its own timeout,
        // since a cancelled worker never reaches its own cleanup code.
        clearWaking(context, deviceId)
        enqueue(context, deviceId, deviceName, targetOnline, sendWake = false)
    }

    /** Send a Wake and watch for it coming online (widget and QS tile path). */
    fun wakeAndWatch(context: Context, deviceId: String, deviceName: String) {
        DeviceStateCache(context).setWaking(deviceId)
        enqueue(context, deviceId, deviceName, targetOnline = true, sendWake = true)
    }

    /** Called when the app is watching this device on-screen again — the worker would only duplicate it. */
    fun cancel(context: Context, deviceId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(deviceId))
        // No new watch follows — same reasoning as watch() above: a cancelled worker never
        // reaches its own clearWaking(), so this is the only place left to do it.
        clearWaking(context, deviceId)
    }

    private fun clearWaking(context: Context, deviceId: String) {
        DeviceStateCache(context).clearWaking(deviceId)
        WakeWidgetProvider.updateAll(context)
    }

    private fun enqueue(
        context: Context,
        deviceId: String,
        deviceName: String,
        targetOnline: Boolean,
        sendWake: Boolean
    ) {
        val request = OneTimeWorkRequestBuilder<DeviceStatusWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    DeviceStatusWorker.KEY_DEVICE_ID to deviceId,
                    DeviceStatusWorker.KEY_DEVICE_NAME to deviceName,
                    DeviceStatusWorker.KEY_TARGET_ONLINE to targetOnline,
                    DeviceStatusWorker.KEY_SEND_WAKE to sendWake
                )
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName(deviceId), ExistingWorkPolicy.REPLACE, request)
    }

    private fun uniqueName(deviceId: String) = "status-watch-$deviceId"
}
