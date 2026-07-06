package com.pcwake.app.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pcwake.app.data.DeviceStateCache
import com.pcwake.app.data.NetworkTrustGate
import com.pcwake.app.data.NetworkTrustRepository
import com.pcwake.app.data.RelayConnectionResolver
import com.pcwake.app.data.SettingsRepository
import com.pcwake.app.notifications.DeviceNotifications
import com.pcwake.app.widget.WakeWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Watches a device's `/status` in the background and posts a notification when it reaches the
 * expected state — so a Wake sent from the widget (or an action whose result the user didn't
 * stick around to watch) still reports back. Optionally sends the Wake itself first, for the
 * surfaces that have no UI of their own (widget, QS tile).
 *
 * Same window as DeviceDetailScreen's in-app poll (12 × 5 s); on timeout it gives up silently —
 * no "still offline" nag, the device list shows the truth next open.
 */
class DeviceStatusWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deviceId = inputData.getString(KEY_DEVICE_ID) ?: return Result.failure()
        val deviceName = inputData.getString(KEY_DEVICE_NAME)?.ifBlank { null } ?: deviceId
        val targetOnline = inputData.getBoolean(KEY_TARGET_ONLINE, true)
        val sendWake = inputData.getBoolean(KEY_SEND_WAKE, false)

        // Every early-exit path that follows a sendWake=true request must go through here:
        // it's the widget/tile's only error surface (a notification) and the only way the
        // "waking" flag gets cleared before its own timeout, so the widget/tile stop showing
        // "Wake sent…" for a wake that isn't actually being watched anymore.
        fun failSend(): Result {
            if (sendWake) {
                DeviceNotifications.notifyWakeFailed(applicationContext, deviceId, deviceName)
                DeviceStateCache(applicationContext).clearWaking(deviceId)
                WakeWidgetProvider.updateAll(applicationContext)
            }
            return Result.failure()
        }

        val repository = SettingsRepository(applicationContext)
        if (!repository.isConfigured()) return failSend()
        val authHeader = "Bearer ${repository.getToken()}"

        // Same trust gate as every foreground call site. In the background the SSID is often
        // unreadable, which the gate already treats as "use Remote" — never a crash, never a
        // token sent over an untrusted network.
        val trustState = NetworkTrustGate.evaluate(
            applicationContext,
            repository.getLocalHost().isNotBlank(),
            NetworkTrustRepository(applicationContext)
        )
        val api = try {
            RelayConnectionResolver.resolve(repository, trustState.allowsLocal).api
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not resolve relay connection", e)
            return failSend()
        }

        if (sendWake) {
            try {
                api.wake(deviceId, authHeader)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Widget/tile wake failed to send", e)
                return failSend()
            }
        }

        val cache = DeviceStateCache(applicationContext)
        repeat(POLL_MAX_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            val online = try {
                api.status(deviceId, authHeader).online
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // transient failure — keep polling
            }
            if (online != null) {
                cache.setOnline(deviceId, online)
                if (online == targetOnline) {
                    cache.clearWaking(deviceId)
                    WakeWidgetProvider.updateAll(applicationContext)
                    DeviceNotifications.notifyTransition(applicationContext, deviceId, deviceName, online)
                    return Result.success()
                }
                // Not at target yet — re-render so a real offline/online flip is reflected,
                // but the "waking" flag (still live) keeps the widget on "Wake sent…" during
                // a send-wake watch rather than flashing the stale pre-wake status.
                WakeWidgetProvider.updateAll(applicationContext)
            }
        }
        // Timeout: give up silently (see class doc), but the widget/tile must stop showing
        // "Wake sent…" once the watch is actually over.
        cache.clearWaking(deviceId)
        WakeWidgetProvider.updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_TARGET_ONLINE = "target_online"
        const val KEY_SEND_WAKE = "send_wake"

        // Mirrors DeviceDetailScreen's in-app poll so "the app stopped watching, the worker
        // takes over" covers the same total window the user was promised on-screen.
        const val POLL_INTERVAL_MS = 5_000L
        const val POLL_MAX_ATTEMPTS = 12

        private const val TAG = "DeviceStatusWorker"
    }
}
