package com.pcwake.app.ui.devicedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pcwake.app.R
import com.pcwake.app.data.RelayApi
import com.pcwake.app.data.RelayConnectionResolver
import com.pcwake.app.data.SettingsRepository
import com.pcwake.app.ui.statusColor
import com.pcwake.app.ui.statusText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 5000L
private const val POLL_MAX_ATTEMPTS = 12
private val ACTION_BUTTON_SIZE = 72.dp
private val ACTION_ICON_SIZE = 28.dp

private sealed class WakeUiState {
    object Idle : WakeUiState()
    object Sending : WakeUiState()
    data class Polling(val attempt: Int, val max: Int) : WakeUiState()
    object Done : WakeUiState()
    data class Error(val message: String) : WakeUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    deviceId: String,
    deviceName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val authHeader = remember { "Bearer ${repository.getToken()}" }
    val scope = rememberCoroutineScope()

    var api by remember { mutableStateOf<RelayApi?>(null) }
    var connectionLabel by remember { mutableStateOf<String?>(null) }
    var online by remember { mutableStateOf<Boolean?>(null) }
    var wakeState by remember { mutableStateOf<WakeUiState>(WakeUiState.Idle) }

    suspend fun refreshStatus() {
        val currentApi = api ?: return
        try {
            val status = currentApi.status(deviceId, authHeader)
            online = status.online
        } catch (e: Exception) {
            // leave previous known status on a transient failure
        }
    }

    suspend fun reconnectAndRefresh() {
        val connection = RelayConnectionResolver.resolve(repository)
        api = connection.api
        connectionLabel = connection.displayName
        refreshStatus()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { reconnectAndRefresh() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(deviceName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { reconnectAndRefresh() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(color = statusColor(online), shape = CircleShape)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = statusText(online), style = MaterialTheme.typography.headlineSmall)
            }
            connectionLabel?.let {
                Text("Connected via $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                PowerActionButton(
                    icon = painterResource(R.drawable.ic_power),
                    label = "Wake",
                    onClick = {
                        scope.launch {
                            val currentApi = api ?: return@launch
                            wakeState = WakeUiState.Sending
                            try {
                                currentApi.wake(deviceId, authHeader)
                                wakeState = WakeUiState.Polling(attempt = 0, max = POLL_MAX_ATTEMPTS)
                                for (i in 1..POLL_MAX_ATTEMPTS) {
                                    delay(POLL_INTERVAL_MS)
                                    refreshStatus()
                                    wakeState = WakeUiState.Polling(attempt = i, max = POLL_MAX_ATTEMPTS)
                                    if (online == true) break
                                }
                                wakeState = WakeUiState.Done
                            } catch (e: Exception) {
                                wakeState = WakeUiState.Error(e.message ?: "Failed to send wake")
                            }
                        }
                    },
                    enabled = api != null && wakeState !is WakeUiState.Sending && wakeState !is WakeUiState.Polling
                )
                // Future actions (Sleep, Restart, Shutdown) go here as additional PowerActionButton entries,
                // gated by the device's capabilities once the PC agent supports them.
            }

            when (val state = wakeState) {
                is WakeUiState.Sending -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Wake signal sent")
                    }
                }
                is WakeUiState.Polling -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Wake signal sent")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Checking... (${state.attempt}/${state.max})")
                        }
                    }
                }
                is WakeUiState.Done -> {
                    val message = if (online == true) "✓ PC is now online" else "Still offline — it may need more time, or check setup"
                    Text(message, color = if (online == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                is WakeUiState.Error -> {
                    Text("✗ ${state.message}", color = MaterialTheme.colorScheme.error)
                }
                WakeUiState.Idle -> {}
            }
        }
    }
}

@Composable
private fun PowerActionButton(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = containerColor),
            modifier = Modifier.size(ACTION_BUTTON_SIZE)
        ) {
            Icon(painter = icon, contentDescription = null, modifier = Modifier.size(ACTION_ICON_SIZE))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
