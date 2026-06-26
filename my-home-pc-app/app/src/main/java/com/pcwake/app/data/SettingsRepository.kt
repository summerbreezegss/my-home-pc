package com.pcwake.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsRepository(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "relay_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getLocalHost(): String = prefs.getString(KEY_LOCAL_HOST, "") ?: ""
    fun getRemoteHost(): String = prefs.getString(KEY_REMOTE_HOST, "") ?: ""
    fun getPort(): String = prefs.getString(KEY_PORT, "8080") ?: "8080"
    fun getToken(): String = prefs.getString(KEY_TOKEN, "") ?: ""

    fun save(localHost: String, remoteHost: String, port: String, token: String) {
        prefs.edit()
            .putString(KEY_LOCAL_HOST, localHost)
            .putString(KEY_REMOTE_HOST, remoteHost)
            .putString(KEY_PORT, port)
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun isConfigured(): Boolean = getRemoteHost().isNotBlank() && getToken().isNotBlank()

    companion object {
        private const val KEY_LOCAL_HOST = "local_host"
        private const val KEY_REMOTE_HOST = "remote_host"
        private const val KEY_PORT = "relay_port"
        private const val KEY_TOKEN = "relay_token"
    }
}
