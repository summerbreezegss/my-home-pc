package com.pcwake.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads the name (SSID) of the Wi-Fi network the phone is currently on, which Android gates
 * behind the fine-location permission — knowing the network you're on reveals where you are.
 *
 * Returns null whenever the SSID genuinely can't be known: permission missing, location
 * services off, on mobile data, or lookup timeout. Callers treat null as "not a trusted
 * network" and fall back to Remote, so every failure mode here fails safe.
 */
object WifiNetworkMonitor {
    private const val SSID_LOOKUP_TIMEOUT_MS = 2_000L

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun currentSsid(context: Context): String? {
        if (!hasLocationPermission(context)) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            currentSsidApi31(context.applicationContext)
        } else {
            currentSsidLegacy(context.applicationContext)
        }
    }

    /**
     * API 31+: the SSID is only delivered to a NetworkCallback registered with
     * FLAG_INCLUDE_LOCATION_INFO — the synchronous getNetworkCapabilities() path always
     * redacts it. For an already-connected Wi-Fi network the callback fires immediately;
     * if we're not on Wi-Fi it never fires and the timeout returns null.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun currentSsidApi31(context: Context): String? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return withTimeoutOrNull(SSID_LOOKUP_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        // Can fire more than once; only the first result matters.
                        runCatching { connectivityManager.unregisterNetworkCallback(this) }
                        if (continuation.isActive) {
                            continuation.resume(normalizeSsid((capabilities.transportInfo as? WifiInfo)?.ssid))
                        }
                    }
                }
                connectivityManager.registerNetworkCallback(request, callback)
                continuation.invokeOnCancellation {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                }
            }
        }
    }

    // Only reached below API 31, where connectionInfo isn't deprecated yet and is the
    // platform-sanctioned way to read the SSID.
    @Suppress("DEPRECATION")
    private fun currentSsidLegacy(context: Context): String? {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return normalizeSsid(wifiManager.connectionInfo?.ssid)
    }

    /**
     * The framework wraps real SSIDs in quotes and substitutes <unknown ssid> when it
     * won't reveal one (location off, permission missing at the framework's level).
     */
    fun normalizeSsid(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == WifiManager.UNKNOWN_SSID) return null
        val unquoted = if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
        return unquoted.ifBlank { null }
    }
}
