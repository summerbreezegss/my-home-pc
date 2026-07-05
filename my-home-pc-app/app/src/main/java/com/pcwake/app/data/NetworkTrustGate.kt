package com.pcwake.app.data

import android.content.Context

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

    /** SSID unreadable (mobile data, location off, permission declined) — Remote, silently. */
    object Unavailable : NetworkTrustState()

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

        val ssid = WifiNetworkMonitor.currentSsid(context) ?: return NetworkTrustState.Unavailable

        return when (trust.getDecision(ssid)) {
            true -> NetworkTrustState.Trusted
            false -> NetworkTrustState.NotTrusted
            null -> NetworkTrustState.Unknown(ssid)
        }
    }
}
