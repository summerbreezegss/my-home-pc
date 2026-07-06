package com.pcwake.app.data

import android.content.Context

/**
 * Last-known online/offline status per device, written whenever anything in the app polls
 * `/status`. Read by surfaces that render without a network call of their own (the QS tile,
 * the home-screen widget). Plain SharedPreferences — an online/offline flag isn't a secret,
 * and unlike relay_settings_v2 it's harmless if a backup restores it stale: the next poll
 * overwrites it.
 */
class DeviceStateCache(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** null = never observed. */
    fun isOnline(deviceId: String): Boolean? {
        val key = KEY_ONLINE_PREFIX + deviceId
        if (!prefs.contains(key)) return null
        return prefs.getBoolean(key, false)
    }

    fun setOnline(deviceId: String, online: Boolean) {
        prefs.edit().putBoolean(KEY_ONLINE_PREFIX + deviceId, online).apply()
    }

    /**
     * True while a just-sent Wake's outcome is still pending — lets the widget/tile keep
     * showing "Wake sent…" instead of the (still-offline) cached status during that window.
     * Time-bounded as a safety net in case the watching worker never clears it explicitly
     * (e.g. its process is killed): expires slightly after DeviceStatusWorker's own poll
     * window (12 x 5s) would have given up anyway.
     */
    fun isWaking(deviceId: String): Boolean {
        val until = prefs.getLong(KEY_WAKING_UNTIL_PREFIX + deviceId, 0L)
        return until > System.currentTimeMillis()
    }

    fun setWaking(deviceId: String) {
        prefs.edit().putLong(KEY_WAKING_UNTIL_PREFIX + deviceId, System.currentTimeMillis() + WAKING_WINDOW_MS).apply()
    }

    fun clearWaking(deviceId: String) {
        prefs.edit().remove(KEY_WAKING_UNTIL_PREFIX + deviceId).apply()
    }

    companion object {
        private const val PREFS_FILE = "device_state_cache"
        private const val KEY_ONLINE_PREFIX = "online_"
        private const val KEY_WAKING_UNTIL_PREFIX = "waking_until_"
        private const val WAKING_WINDOW_MS = 75_000L
    }
}
