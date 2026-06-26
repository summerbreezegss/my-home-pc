package com.pcwake.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts values with an Android Keystore-backed AES-GCM key before storing them in plain
 * SharedPreferences. Replaces androidx.security.crypto's EncryptedSharedPreferences/MasterKey,
 * which are deprecated with no planned replacement from Google — this implements the same
 * Keystore-backed approach directly instead of depending on the deprecated wrapper.
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // IV is unique per encryption call; prepend it so decrypt() can recover it.
        val combined = cipher.iv + cipherText
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        return try {
            val combined = Base64.decode(stored, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            // Can't decrypt (e.g. leftover data from a different key/scheme) — treat as unset.
            ""
        }
    }

    fun getLocalHost(): String = decrypt(prefs.getString(KEY_LOCAL_HOST, null))
    fun getRemoteHost(): String = decrypt(prefs.getString(KEY_REMOTE_HOST, null))
    fun getPort(): String = decrypt(prefs.getString(KEY_PORT, null)).ifBlank { "8675" }
    fun getToken(): String = decrypt(prefs.getString(KEY_TOKEN, null))

    fun save(localHost: String, remoteHost: String, port: String, token: String) {
        prefs.edit()
            .putString(KEY_LOCAL_HOST, encrypt(localHost))
            .putString(KEY_REMOTE_HOST, encrypt(remoteHost))
            .putString(KEY_PORT, encrypt(port))
            .putString(KEY_TOKEN, encrypt(token))
            .apply()
    }

    fun isConfigured(): Boolean = getRemoteHost().isNotBlank() && getToken().isNotBlank()

    companion object {
        private const val PREFS_FILE = "relay_settings_v2"
        private const val KEY_LOCAL_HOST = "local_host"
        private const val KEY_REMOTE_HOST = "remote_host"
        private const val KEY_PORT = "relay_port"
        private const val KEY_TOKEN = "relay_token"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "pcwake_settings_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
