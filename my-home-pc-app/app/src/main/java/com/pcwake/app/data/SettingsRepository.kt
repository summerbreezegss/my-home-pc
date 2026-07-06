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

    fun isConfigured(): Boolean = getRemoteHost().isNotBlank() && getToken().isNotBlank()

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

    companion object {
        private const val PREFS_FILE = "relay_settings_v2"
        private const val KEY_LOCAL_HOST = "local_host"
        private const val KEY_REMOTE_HOST = "remote_host"
        private const val KEY_PORT = "relay_port"
        private const val KEY_TOKEN = "relay_token"
        private const val KEY_DEFAULT_DEVICE_ID = "default_device_id"
        private const val KEY_DEFAULT_DEVICE_NAME = "default_device_name"
    }
}