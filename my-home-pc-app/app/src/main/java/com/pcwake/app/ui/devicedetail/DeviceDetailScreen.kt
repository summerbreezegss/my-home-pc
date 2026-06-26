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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

private enum class PowerAction(val label: String, val verbPhrase: String, val targetOnline: Boolean) {
    WAKE("Wake my PC", "wake", targetOnline = true),
    RESTART("Restart", "restart", targetOnline = false),
    SHUTDOWN("Shutdown", "shut down", targetOnline = false)
}

private sealed class DeviceActionState {
    object Idle : DeviceActionState()
    data class Sending(val action: PowerAction) : DeviceActionState()
    data class Polling(val action: PowerAction, val attempt: Int, val max: Int) : DeviceActionState()
    data class Done(val action: PowerAction, val reachedTarget: Boolean) : DeviceActionState()
    data class Error(val action: PowerAction, val message: String) : DeviceActionState()
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
    var capabilities by remember { mutableStateOf<List<String>>(emptyList()) }
    var actionState by remember { mutableStateOf<DeviceActionState>(DeviceActionState.Idle) }
    var pendingConfirmation by remember { mutableStateOf<PowerAction?>(null) }

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
        try {
            capabilities = connection.api.listDevices(authHeader).devices
                .firstOrNull { it.id == deviceId }?.capabilities ?: emptyList()
        } catch (e: Exception) {
            // leave previously known capabilities on a transient failure
        }
    }

    fun performAction(action: PowerAction) {
        val currentApi = api ?: return
        scope.launch {
            actionState = DeviceActionState.Sending(action)
            try {
                when (action) {
                    PowerAction.WAKE -> currentApi.wake(deviceId, authHeader)
                    PowerAction.RESTART -> currentApi.restart(deviceId, authHeader)
                    PowerAction.SHUTDOWN -> currentApi.shutdown(deviceId, authHeader)
                }
                actionState = DeviceActionState.Polling(action, attempt = 0, max = POLL_MAX_ATTEMPTS)
                for (i in 1..POLL_MAX_ATTEMPTS) {
                    delay(POLL_INTERVAL_MS)
                    refreshStatus()
                    actionState = DeviceActionState.Polling(action, attempt = i, max = POLL_MAX_ATTEMPTS)
                    if (online == action.targetOnline) break
                }
                actionState = DeviceActionState.Done(action, reachedTarget = online == action.targetOnline)
            } catch (e: Exception) {
                actionState = DeviceActionState.Error(action, e.message ?: "Failed to send ${action.label}")
            }
        }
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

    val isBusy = actionState is DeviceActionState.Sending || actionState is DeviceActionState.Polling

    pendingConfirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text("${action.label} $deviceName?") },
            text = { Text("This will ${action.verbPhrase} the PC now.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingConfirmation = null
                    performAction(action)
                }) { Text(action.label) }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) { Text("Cancel") }
            }
        )
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
                    label = PowerAction.WAKE.label,
                    onClick = { performAction(PowerAction.WAKE) },
                    enabled = api != null && !isBusy
                )
                if (capabilities.contains("restart")) {
                    PowerActionButton(
                        icon = painterResource(R.drawable.ic_restart),
                        label = PowerAction.RESTART.label,
                        onClick = { pendingConfirmation = PowerAction.RESTART },
                        enabled = api != null && !isBusy
                    )
                }
                if (capabilities.contains("shutdown")) {
                    PowerActionButton(
                        icon = painterResource(R.drawable.ic_power),
                        label = PowerAction.SHUTDOWN.label,
                        onClick = { pendingConfirmation = PowerAction.SHUTDOWN },
                        enabled = api != null && !isBusy,
                        containerColor = MaterialTheme.colorScheme.error
                    )
                }
            }

            when (val state = actionState) {
                is DeviceActionState.Sending -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("${state.action.label} signal sent")
                    }
                }
                is DeviceActionState.Polling -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${state.action.label} signal sent")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Checking... (${state.attempt}/${state.max})")
                        }
                    }
                }
                is DeviceActionState.Done -> {
                    val message = if (state.reachedTarget) {
                        if (state.action.targetOnline) "✓ PC is now online" else "✓ PC is now offline"
                    } else {
                        "Still ${if (state.action.targetOnline) "offline" else "online"} — it may need more time, or check setup"
                    }
                    Text(message, color = if (state.reachedTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
                is DeviceActionState.Error -> {
                    Text("✗ ${state.message}", color = MaterialTheme.colorScheme.error)
                }
                DeviceActionState.Idle -> {}
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
