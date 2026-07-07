package com.pcwake.app.data

import android.content.Context

/**
 * Relay connection settings, encrypted at rest via [SettingsCrypto] (Keystore-backed AES-GCM
 * over plain SharedPreferences).
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun getLocalHost(): String = SettingsCrypto.decrypt(prefs.getString(KEY_LOCAL_HOST, null))
    fun getRemoteHost(): String = SettingsCrypto.decrypt(prefs.getString(KEY_REMOTE_HOST, null))
    fun getPort(): String = SettingsCrypto.decrypt(prefs.getString(KEY_PORT, null)).ifBlank { "8675" }
    fun getToken(): String = SettingsCrypto.decrypt(prefs.getString(KEY_TOKEN, null))

    fun save(localHost: String, remoteHost: String, port: String, token: String) {
        prefs.edit()
            .putString(KEY_LOCAL_HOST, SettingsCrypto.encrypt(localHost))
            .putString(KEY_REMOTE_HOST, SettingsCrypto.encrypt(remoteHost))
            .putString(KEY_PORT, SettingsCrypto.encrypt(port))
            .putString(KEY_TOKEN, SettingsCrypto.encrypt(token))
            .apply()
    }

    // A token plus at least one host (Local or Remote) is a usable setup. Requiring Remote
    // specifically would strand a Local-only user: the background worker (widget/tile) gates on
    // this, so with a blank Remote it would bail before ever trying the Local host they do have.
    fun isConfigured(): Boolean =
        (getRemoteHost().isNotBlank() || getLocalHost().isNotBlank()) && getToken().isNotBlank()

    /**
     * The device the Quick Settings tile and home-screen widget act on. The name is stored
     * alongside the id so those surfaces can render it without a network call; it's refreshed
     * from the live device list whenever one is fetched (see [DefaultDeviceRules]).
     */
    fun getDefaultDeviceId(): String = SettingsCrypto.decrypt(prefs.getString(KEY_DEFAULT_DEVICE_ID, null))
    fun getDefaultDeviceName(): String = SettingsCrypto.decrypt(prefs.getString(KEY_DEFAULT_DEVICE_NAME, null))

    fun setDefaultDevice(id: String, name: String) {
        prefs.edit()
            .putString(KEY_DEFAULT_DEVICE_ID, SettingsCrypto.encrypt(id))
            .putString(KEY_DEFAULT_DEVICE_NAME, SettingsCrypto.encrypt(name))
            .apply()
    }

    /**
     * Whether the default device reports the "shutdown" capability, cached so the widget can
     * decide at render time whether to show its Off button without a network call. Refreshed
     * from the live device list on every fetch (see [DefaultDeviceRules.defaultSupportsShutdown]).
     * Not a secret, so it's stored in the clear; defaults to false so a device whose capability
     * isn't known yet simply hides Off rather than offering an action that would error.
     */
    fun getDefaultDeviceShutdownSupported(): Boolean = prefs.getBoolean(KEY_DEFAULT_DEVICE_SHUTDOWN, false)

    fun setDefaultDeviceShutdownSupported(supported: Boolean) {
        prefs.edit().putBoolean(KEY_DEFAULT_DEVICE_SHUTDOWN, supported).apply()
    }

    companion object {
        private const val PREFS_FILE = "relay_settings_v2"
        private const val KEY_LOCAL_HOST = "local_host"
        private const val KEY_REMOTE_HOST = "remote_host"
        private const val KEY_PORT = "relay_port"
        private const val KEY_TOKEN = "relay_token"
        private const val KEY_DEFAULT_DEVICE_ID = "default_device_id"
        private const val KEY_DEFAULT_DEVICE_NAME = "default_device_name"
        private const val KEY_DEFAULT_DEVICE_SHUTDOWN = "default_device_shutdown_supported"
    }
}