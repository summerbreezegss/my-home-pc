package com.pcwake.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pcwake.app.MainActivity
import com.pcwake.app.R

/**
 * Posts the device up/down notifications. All posting funnels through [post], which checks
 * the runtime notification permission (required on Android 13+) — when it's missing the
 * notification is simply dropped rather than crashing with a SecurityException; the action
 * itself already happened, only the announcement is lost.
 */
object DeviceNotifications {
    private const val CHANNEL_ID = "device_status"

    /** Stable per-device id so a newer status update replaces the older notification. */
    private fun notificationId(deviceId: String) = deviceId.hashCode()

    fun transitionTitle(deviceName: String, online: Boolean): String =
        if (online) "$deviceName is now online" else "$deviceName is now offline"

    fun notifyTransition(context: Context, deviceId: String, deviceName: String, online: Boolean) {
        post(context, deviceId, deviceName, transitionTitle(deviceName, online), null)
    }

    fun notifyWakeFailed(context: Context, deviceId: String, deviceName: String) {
        post(
            context,
            deviceId,
            deviceName,
            "Couldn't wake $deviceName",
            "The relay couldn't be reached. Open MyHomePC to check the connection."
        )
    }

    private fun post(context: Context, deviceId: String, deviceName: String, title: String, text: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(context)

        // Tapping the notification lands on the device the update is about — carried as
        // extras, not "the default device": a watch handed off from the detail screen can be
        // about any device, and the title is the freshest name we have for it.
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_DEVICE
            putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId)
            putExtra(MainActivity.EXTRA_DEVICE_NAME, deviceName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            context,
            notificationId(deviceId),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .setContentIntent(tapPending)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(deviceId), notification)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_device_status),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
}
