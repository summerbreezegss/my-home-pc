package com.pcwake.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * The one place that decides whether the Local relay probe is allowed right now, and what —
 * if anything — the UI should ask the user. Every state except [Trusted] means "use Remote",
 * which works from anywhere, so nothing here can make the app unusable.
 */
sealed class NetworkTrustState {
    /** On a network the user trusts — Local probe allowed. */
    object Trusted : NetworkTrustState()

    /** User said no to this network — Remote only, no prompt, they already decided. */
    object NotTrusted : NetworkTrustState()

    /** On a Wi-Fi network never decided on — Remote for now, ask "Trust this network?". */
    data class Unknown(val ssid: String) : NetworkTrustState()

    /** No Local Host configured — the whole feature is inert, never ask for anything. */
    object NotApplicable : NetworkTrustState()

    /** Location permission missing and the user hasn't said "not now" yet — show the rationale. */
    object NeedsPermission : NetworkTrustState()

    /** SSID unreadable (not on Wi-Fi, permission declined, or a platform lookup failure) — Remote, silently. */
    object Unavailable : NetworkTrustState()

    /**
     * On Wi-Fi, permission granted, but the phone's system-wide Location toggle is off — the
     * SSID can't be read regardless of app permission. Split out from [Unavailable] because,
     * unlike the other silent cases, this one is worth surfacing: the user already did their
     * part (granted the permission), they're actually on a Wi-Fi network so there's something
     * to gain, and there's a concrete fix (flip the system toggle).
     */
    object LocationServicesOff : NetworkTrustState()

    /**
     * NotApplicable also allows Local: with no Local Host configured the resolver skips the
     * probe anyway, and this keeps that long-standing behavior owned by the resolver alone.
     */
    val allowsLocal: Boolean
        get() = this is Trusted || this is NotApplicable
}

object NetworkTrustGate {

    /**
     * [localHostConfigured] rather than the repository itself, because the Settings screen
     * needs to evaluate against a typed-in-but-unsaved Local Host during Test Connection.
     */
    suspend fun evaluate(
        context: Context,
        localHostConfigured: Boolean,
        trust: NetworkTrustRepository
    ): NetworkTrustState {
        if (!localHostConfigured) return NetworkTrustState.NotApplicable

        if (!WifiNetworkMonitor.hasLocationPermission(context)) {
            return if (trust.isPermissionDeclined()) {
                NetworkTrustState.Unavailable
            } else {
                NetworkTrustState.NeedsPermission
            }
        }

        // Everything from here on touches the platform Wi-Fi/location stack, which has shown
        // real OEM/OS-version quirks (this whole gate exists because of one). A failure
        // anywhere in this sequence must not abort the caller's whole refresh or escape
        // uncaught — some callers (e.g. DeviceDetailScreen) have no outer try/catch of their
        // own and would crash; others would just show a misleading "can't reach relay" error.
        // Every unreadable-SSID case fails safe to Remote instead, same as the checks above.
        val ssid = try {
            if (!WifiNetworkMonitor.isConnectedToWifi(context)) {
                // Not on Wi-Fi at all (e.g. mobile data) — Location being off is irrelevant
                // here and telling the user to flip it wouldn't fix anything.
                return NetworkTrustState.Unavailable
            }
            if (!WifiNetworkMonitor.isLocationServicesEnabled(context)) {
                return NetworkTrustState.LocationServicesOff
            }
            WifiNetworkMonitor.currentSsid(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Wi-Fi network check failed — falling back to Remote", e)
            null
        } ?: return NetworkTrustState.Unavailable

        return when (trust.getDecision(ssid)) {
            true -> NetworkTrustState.Trusted
            false -> NetworkTrustState.NotTrusted
            null -> NetworkTrustState.Unknown(ssid)
        }
    }

    private const val TAG = "NetworkTrustGate"
}
