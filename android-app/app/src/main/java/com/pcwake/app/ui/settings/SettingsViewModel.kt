package com.pcwake.app.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    var localHost by mutableStateOf(repository.getLocalHost())
    var remoteHost by mutableStateOf(repository.getRemoteHost())
    var port by mutableStateOf(repository.getPort())
    var token by mutableStateOf(repository.getToken())

    private val _testState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val testState: StateFlow<ConnectionTestState> = _testState

    private val _savedConfirmation = MutableStateFlow(false)
    val savedConfirmation: StateFlow<Boolean> = _savedConfirmation

    fun testConnection() {
        viewModelScope.launch {
            _testState.value = ConnectionTestState.Testing
            try {
                // Test against whatever is currently typed in, not the saved repository values,
                // so the user can verify edits before committing them with Save.
                val connection = if (localHost.isNotBlank()) {
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
                val health = api.health()
                api.listDevices("Bearer $token")
                _testState.value = ConnectionTestState.Success("Connected via ${connection.displayName} — relay v${health.version}")
            } catch (e: HttpException) {
                val msg = if (e.code() == 401) "Reached relay, but token was rejected" else "Server error: ${e.code()}"
                _testState.value = ConnectionTestState.Failure(msg)
            } catch (e: Exception) {
                _testState.value = ConnectionTestState.Failure("Can't reach relay: ${e.message}")
            }
        }
    }

    fun save() {
        repository.save(localHost, remoteHost, port, token)
        _savedConfirmation.value = true
    }
}
