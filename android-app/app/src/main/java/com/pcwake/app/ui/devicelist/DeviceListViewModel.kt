package com.pcwake.app.ui.devicelist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pcwake.app.data.Device
import com.pcwake.app.data.RelayConnectionResolver
import com.pcwake.app.data.SettingsRepository
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

class DeviceListViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SettingsRepository(application)

    private val _uiState = MutableStateFlow<DeviceListUiState>(DeviceListUiState.Loading)
    val uiState: StateFlow<DeviceListUiState> = _uiState

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = DeviceListUiState.Loading
            try {
                val connection = RelayConnectionResolver.resolve(repository)
                val api = connection.api
                val authHeader = "Bearer ${repository.getToken()}"
                val devices = api.listDevices(authHeader).devices
                _uiState.value = DeviceListUiState.Loaded(devices.map { DeviceUiState(it) }, connection.displayName)

                devices.forEach { device ->
                    launch {
                        val online = try {
                            api.status(device.id, authHeader).online
                        } catch (e: Exception) {
                            null
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
}
