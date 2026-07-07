package com.pcwake.app.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pcwake.app.data.DefaultDeviceRules
import com.pcwake.app.data.Device
import com.pcwake.app.data.NetworkTrustGate
import com.pcwake.app.data.NetworkTrustRepository
import com.pcwake.app.data.NetworkTrustState
import com.pcwake.app.data.RelayClient
import com.pcwake.app.data.RelayConnection
import com.pcwake.app.data.RelayConnectionResolver
import com.pcwake.app.data.SettingsRepository
import com.pcwake.app.widget.WakeWidgetProvider
import kotlinx.coroutines.CancellationException
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

    /**
     * True when the trust gate's current read is [NetworkTrustState.LocationServicesOff] —
     * distinct from [wifiCheckDeclined] because re-requesting the app permission won't fix it;
     * the user needs the system Location screen. Derived from [NetworkTrustGate.evaluate]
     * rather than re-deriving the permission/toggle check independently, so this can never
     * drift from what the resolver is actually doing (e.g. it also inherits the "not on
     * Wi-Fi at all" and "no Local Host configured" exclusions for free).
     */
    private val _locationServicesOff = MutableStateFlow(false)
    val locationServicesOff: StateFlow<Boolean> = _locationServicesOff

    /**
     * Devices available as the tile/widget's default. null = not fetched (unconfigured relay
     * or fetch failed) — the section shows a hint instead of an empty pick list.
     */
    private val _deviceOptions = MutableStateFlow<List<Device>?>(null)
    val deviceOptions: StateFlow<List<Device>?> = _deviceOptions

    private val _defaultDeviceId = MutableStateFlow(repository.getDefaultDeviceId())
    val defaultDeviceId: StateFlow<String> = _defaultDeviceId

    // No init-time load: the screen's own ON_RESUME observer covers first composition too
    // (Android's Lifecycle replays the current state to a newly added observer), matching
    // how DeviceListViewModel has no init-time load either — avoids a redundant double call.

    /** Public so the screen can re-check after the user returns from system Location settings. */
    fun reloadTrust() {
        _trustedNetworks.value = trustRepository.getDecisions()
        _wifiCheckDeclined.value = trustRepository.isPermissionDeclined()
        viewModelScope.launch {
            val trustState = NetworkTrustGate.evaluate(getApplication(), localHost.isNotBlank(), trustRepository)
            _locationServicesOff.value = trustState is NetworkTrustState.LocationServicesOff
        }
        loadDeviceOptions()
    }

    /**
     * Fetches the device list for the Default Device section, against the *saved* settings —
     * unlike Test Connection this isn't about the values currently being typed; the default
     * device only matters once a working relay config has been saved.
     */
    private fun loadDeviceOptions() {
        if (!repository.isConfigured()) {
            _deviceOptions.value = null
            return
        }
        viewModelScope.launch {
            try {
                val trustState = NetworkTrustGate.evaluate(
                    getApplication(),
                    repository.getLocalHost().isNotBlank(),
                    trustRepository
                )
                val api = RelayConnectionResolver.resolve(repository, trustState.allowsLocal).api
                val devices = api.listDevices("Bearer ${repository.getToken()}").devices
                // Same auto-select/rename rule as the device list screen, so the section never
                // shows "nothing chosen" while the widget would in fact wake the only device.
                DefaultDeviceRules.resolveUpdate(
                    repository.getDefaultDeviceId(),
                    repository.getDefaultDeviceName(),
                    devices
                )?.let {
                    repository.setDefaultDevice(it.id, it.name)
                }
                // Keep the widget's cached "can shut down" flag current — see DeviceListViewModel.
                repository.setDefaultDeviceShutdownSupported(
                    DefaultDeviceRules.defaultSupportsShutdown(repository.getDefaultDeviceId(), devices)
                )
                WakeWidgetProvider.updateAll(getApplication())
                _defaultDeviceId.value = repository.getDefaultDeviceId()
                _deviceOptions.value = devices
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _deviceOptions.value = null
            }
        }
    }

    fun setDefaultDevice(device: Device) {
        repository.setDefaultDevice(device.id, device.name)
        repository.setDefaultDeviceShutdownSupported(device.capabilities.contains("shutdown"))
        _defaultDeviceId.value = device.id
        WakeWidgetProvider.updateAll(getApplication())
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
        // Also doubles as the manual refresh affordance for the Trusted Networks hints below —
        // ON_RESUME alone can miss a Location toggle flipped via a quick-settings tile.
        reloadTrust()
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
