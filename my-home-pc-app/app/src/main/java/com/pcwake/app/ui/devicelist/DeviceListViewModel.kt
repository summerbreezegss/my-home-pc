package com.pcwake.app.ui.devicelist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pcwake.app.data.DefaultDeviceRules
import com.pcwake.app.data.Device
import com.pcwake.app.data.DeviceStateCache
import com.pcwake.app.data.NetworkTrustGate
import com.pcwake.app.data.NetworkTrustRepository
import com.pcwake.app.data.NetworkTrustState
import com.pcwake.app.data.RelayConnectionResolver
import com.pcwake.app.data.SettingsRepository
import com.pcwake.app.widget.WakeWidgetProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DeviceUiState(
    val device: Device,
    val online: Boolean? = null
)

sealed class DeviceListUiState {
    object Loading : DeviceListUiState()
    data class Loaded(val devices: List<DeviceUiState>, val connectionLabel: String) : DeviceListUiState()
    data class Error(val message: String) : DeviceListUiState()
}

/** Which trust-related dialog the device list should show, if any. */
sealed class TrustDialog {
    object None : TrustDialog()
    object PermissionRationale : TrustDialog()
    data class TrustNetwork(val ssid: String) : TrustDialog()
    object LocationServicesOff : TrustDialog()
}

class DeviceListViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)
    private val trustRepository = NetworkTrustRepository(application)
    private val stateCache = DeviceStateCache(application)

    private val _uiState = MutableStateFlow<DeviceListUiState>(DeviceListUiState.Loading)
    val uiState: StateFlow<DeviceListUiState> = _uiState

    private val _trustDialog = MutableStateFlow<TrustDialog>(TrustDialog.None)
    val trustDialog: StateFlow<TrustDialog> = _trustDialog

    // Networks whose prompt was dismissed without a decision (tap outside/back) —
    // don't re-ask until the next app start.
    private val dismissedThisSession = mutableSetOf<String>()

    // The Location-services-off notice has no per-network identity and no permanent "no" —
    // it's live system state, so dismissing it just quiets it for this session rather than
    // recording a decision.
    private var locationServicesOffDismissedThisSession = false

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = DeviceListUiState.Loading
            try {
                val trustState = NetworkTrustGate.evaluate(
                    getApplication(),
                    repository.getLocalHost().isNotBlank(),
                    trustRepository
                )
                _trustDialog.value = when {
                    trustState is NetworkTrustState.NeedsPermission ->
                        TrustDialog.PermissionRationale
                    trustState is NetworkTrustState.Unknown && trustState.ssid !in dismissedThisSession ->
                        TrustDialog.TrustNetwork(trustState.ssid)
                    trustState is NetworkTrustState.LocationServicesOff && !locationServicesOffDismissedThisSession ->
                        TrustDialog.LocationServicesOff
                    else -> TrustDialog.None
                }

                val connection = RelayConnectionResolver.resolve(repository, trustState.allowsLocal)
                val api = connection.api
                val authHeader = "Bearer ${repository.getToken()}"
                val devices = api.listDevices(authHeader).devices
                _uiState.value = DeviceListUiState.Loaded(devices.map { DeviceUiState(it) }, connection.displayName)

                // Keep the tile/widget's default device in step with the live list: auto-pick
                // when there's exactly one device, refresh a stored name after a rename.
                DefaultDeviceRules.resolveUpdate(
                    repository.getDefaultDeviceId(),
                    repository.getDefaultDeviceName(),
                    devices
                )?.let {
                    repository.setDefaultDevice(it.id, it.name)
                }
                // Refresh the widget's cached "can shut down" flag on every fetch — capabilities
                // can change without a rename, so this can't ride on resolveUpdate above.
                repository.setDefaultDeviceShutdownSupported(
                    DefaultDeviceRules.defaultSupportsShutdown(repository.getDefaultDeviceId(), devices)
                )
                WakeWidgetProvider.updateAll(getApplication())

                devices.forEach { device ->
                    launch {
                        val online = try {
                            api.status(device.id, authHeader).online
                        } catch (e: Exception) {
                            null
                        }
                        if (online != null) {
                            stateCache.setOnline(device.id, online)
                            WakeWidgetProvider.updateAll(getApplication())
                        }
                        val current = _uiState.value
                        if (current is DeviceListUiState.Loaded) {
                            _uiState.value = DeviceListUiState.Loaded(
                                current.devices.map {
                                    if (it.device.id == device.id) it.copy(online = online) else it
                                },
                                current.connectionLabel
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = DeviceListUiState.Error("Can't reach relay: ${e.message}")
            }
        }
    }

    /** Trust / Not now on the network prompt — both are recorded; Settings can reverse either. */
    fun onTrustDecision(ssid: String, trusted: Boolean) {
        trustRepository.setDecision(ssid, trusted)
        _trustDialog.value = TrustDialog.None
        if (trusted) {
            refresh() // switch to the Local path right away
        }
    }

    /** Dismissed without deciding (tap outside/back) — no record kept; ask again next session. */
    fun onTrustPromptDismissed(ssid: String) {
        dismissedThisSession += ssid
        _trustDialog.value = TrustDialog.None
    }

    /** Dismissed or "Not now" on the Location-services notice — quiet for this session only. */
    fun onLocationServicesOffDismissed() {
        locationServicesOffDismissedThisSession = true
        _trustDialog.value = TrustDialog.None
    }

    /** "Continue" on the rationale — the screen launches the system dialog; hide ours. */
    fun onRationaleAccepted() {
        _trustDialog.value = TrustDialog.None
    }

    /** Rationale dismissed without deciding (tap outside/back) — it may return next refresh. */
    fun onRationaleDismissed() {
        _trustDialog.value = TrustDialog.None
    }

    /**
     * "Not now" on the rationale, or a denial in the system dialog — remembered so the app
     * stops asking. The Trusted Networks section in Settings is the way back in.
     */
    fun onPermissionDeclined() {
        trustRepository.setPermissionDeclined(true)
        _trustDialog.value = TrustDialog.None
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            refresh()
        } else {
            onPermissionDeclined()
        }
    }
}
