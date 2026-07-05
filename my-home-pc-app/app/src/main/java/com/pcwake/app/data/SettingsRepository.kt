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

    companion object {
        private const val PREFS_FILE = "relay_settings_v2"
        private const val KEY_LOCAL_HOST = "local_host"
        private const val KEY_REMOTE_HOST = "remote_host"
        private const val KEY_PORT = "relay_port"
        private const val KEY_TOKEN = "relay_token"
    }
}