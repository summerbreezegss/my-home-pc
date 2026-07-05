package com.pcwake.app.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pcwake.app.data.NetworkTrustGate
import com.pcwake.app.data.NetworkTrustRepository
import com.pcwake.app.data.RelayClient
import com.pcwake.app.data.RelayConnection
import com.pcwake.app.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

sealed class ConnectionTestState {
    object Idle : ConnectionTestState()
    object Testing : ConnectionTestState()
    data class Success(val message: String) : ConnectionTestState()
    data class Failure(val message: String) : ConnectionTestState()
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    private val trustRepository = NetworkTrustRepository(application)

    var localHost by mutableStateOf(repository.getLocalHost())
    var remoteHost by mutableStateOf(repository.getRemoteHost())
    var port by mutableStateOf(repository.getPort())
    var token by mutableStateOf(repository.getToken())

    private val _testState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val testState: StateFlow<ConnectionTestState> = _testState

    private val _savedConfirmation = MutableStateFlow(false)
    val savedConfirmation: StateFlow<Boolean> = _savedConfirmation

    /** Decided networks (SSID → trusted), for the Trusted Networks section. */
    private val _trustedNetworks = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val trustedNetworks: StateFlow<Map<String, Boolean>> = _trustedNetworks

    /** True when the user said "not now" to the Wi-Fi check — Settings offers the way back in. */
    private val _wifiCheckDeclined = MutableStateFlow(false)
    val wifiCheckDeclined: StateFlow<Boolean> = _wifiCheckDeclined

    init {
        reloadTrust()
    }

    private fun reloadTrust() {
        _trustedNetworks.value = trustRepository.getDecisions()
        _wifiCheckDeclined.value = trustRepository.isPermissionDeclined()
    }

    fun setNetworkTrusted(ssid: String, trusted: Boolean) {
        trustRepository.setDecision(ssid, trusted)
        reloadTrust()
    }

    /** Re-enables the Wi-Fi check; the screen follows up with the system permission request. */
    fun enableWifiCheck() {
        trustRepository.setPermissionDeclined(false)
        reloadTrust()
    }

    fun onWifiPermissionResult(granted: Boolean) {
        if (!granted) {
            trustRepository.setPermissionDeclined(true)
        }
        reloadTrust()
    }

    /**
     * Normalizes what people actually paste into a host field — a copied URL with a scheme,
     * a trailing slash, stray whitespace — down to the bare hostname/IP the relay client needs.
     */
    private fun sanitizeHost(raw: String): String {
        var host = raw.trim()
        for (scheme in listOf("https://", "http://")) {
            if (host.startsWith(scheme, ignoreCase = true)) {
                host = host.substring(scheme.length)
            }
        }
        return host.trimEnd('/')
    }

    /**
     * Cleans up the current field values in place (so the user sees what will actually be used)
     * and returns a human-readable problem description, or null if everything is usable.
     * Malformed values would otherwise surface as a confusing Retrofit "Invalid URL" error.
     */
    private fun sanitizeAndValidate(): String? {
        localHost = sanitizeHost(localHost)
        remoteHost = sanitizeHost(remoteHost)
        port = port.trim()
        token = token.trim()

        val portNumber = port.toIntOrNull()
        if (port.isNotBlank() && (portNumber == null || portNumber !in 1..65535)) {
            return "Port must be a number between 1 and 65535"
        }
        listOf("Local Host" to localHost, "Remote Host" to remoteHost).forEach { (label, host) ->
            if (host.any { it.isWhitespace() } || host.contains('/')) {
                return "$label should be just a hostname or IP — no spaces or slashes"
            }
        }
        return null
    }

    fun testConnection() {
        sanitizeAndValidate()?.let {
            _testState.value = ConnectionTestState.Failure(it)
            return
        }
        viewModelScope.launch {
            _testState.value = ConnectionTestState.Testing
            try {
                // Test against whatever is currently typed in, not the saved repository values,
                // so the user can verify edits before committing them with Save. The Local
                // attempt is gated on network trust just like the real resolver — Test
                // Connection sends the token too.
                val trustState = NetworkTrustGate.evaluate(
                    getApplication(),
                    localHost.isNotBlank(),
                    trustRepository
                )
                val connection = if (localHost.isNotBlank() && trustState.allowsLocal) {
                    val localApi = RelayClient.create(localHost, port, connectTimeoutSeconds = 2)
                    try {
                        localApi.health()
                        RelayConnection.Local(localApi)
                    } catch (e: Exception) {
                        RelayConnection.Remote(RelayClient.create(remoteHost, port))
                    }
                } else {
                    RelayConnection.Remote(RelayClient.create(remoteHost, port))
                }

                val api = connection.api
                api.health()
                api.listDevices("Bearer $token")
                _testState.value = ConnectionTestState.Success("Connected via ${connection.displayName}")
            } catch (e: HttpException) {
                val msg = if (e.code() == 401) "Reached relay, but token was rejected" else "Server error: ${e.code()}"
                _testState.value = ConnectionTestState.Failure(msg)
            } catch (e: Exception) {
                _testState.value = ConnectionTestState.Failure("Can't reach relay: ${e.message}")
            }
        }
    }

    /** Returns true if the settings were valid and saved; false if validation blocked the save. */
    fun save(): Boolean {
        sanitizeAndValidate()?.let {
            _testState.value = ConnectionTestState.Failure(it)
            _savedConfirmation.value = false
            return false
        }
        repository.save(localHost, remoteHost, port, token)
        _savedConfirmation.value = true
        return true
    }
}
