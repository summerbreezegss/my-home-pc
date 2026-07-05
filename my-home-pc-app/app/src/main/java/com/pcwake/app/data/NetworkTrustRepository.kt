package com.pcwake.app.data

import android.content.Context

/**
 * Remembers, per Wi-Fi network name, whether the user chose to trust it for the Local relay
 * shortcut. Networks the user has never decided on are simply absent from the map — that's
 * what triggers the "Trust this network?" prompt.
 *
 * Stored encrypted like the relay settings: SSIDs reveal where the user has been, so they
 * shouldn't sit in plaintext prefs either.
 */
class NetworkTrustRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** All decided networks: SSID → true (trusted) / false (not trusted). */
    fun getDecisions(): Map<String, Boolean> =
        NetworkTrustCodec.decode(SettingsCrypto.decrypt(prefs.getString(KEY_DECISIONS, null)))

    /** null = never asked about this network. */
    fun getDecision(ssid: String): Boolean? = getDecisions()[ssid]

    fun setDecision(ssid: String, trusted: Boolean) {
        saveDecisions(getDecisions() + (ssid to trusted))
    }

    /**
     * "Not now" on the location-permission rationale — remembered so the app stops asking.
     * Settings offers the way back in.
     */
    fun isPermissionDeclined(): Boolean = prefs.getBoolean(KEY_PERMISSION_DECLINED, false)

    fun setPermissionDeclined(declined: Boolean) {
        prefs.edit().putBoolean(KEY_PERMISSION_DECLINED, declined).apply()
    }

    private fun saveDecisions(decisions: Map<String, Boolean>) {
        prefs.edit()
            .putString(KEY_DECISIONS, SettingsCrypto.encrypt(NetworkTrustCodec.encode(decisions)))
            .apply()
    }

    companion object {
        private const val PREFS_FILE = "network_trust_v1"
        private const val KEY_DECISIONS = "trust_decisions"
        private const val KEY_PERMISSION_DECLINED = "location_permission_declined"
    }
}