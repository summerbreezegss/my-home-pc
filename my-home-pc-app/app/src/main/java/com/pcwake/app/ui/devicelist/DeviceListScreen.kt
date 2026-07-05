package com.pcwake.app.ui.devicelist

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pcwake.app.R
import com.pcwake.app.ui.statusColor
import com.pcwake.app.ui.statusText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    onOpenSettings: () -> Unit,
    onDeviceClick: (String, String) -> Unit,
    viewModel: DeviceListViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val trustDialog by viewModel.trustDialog.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onPermissionResult(granted) }

    when (val dialog = trustDialog) {
        TrustDialog.PermissionRationale -> AlertDialog(
            onDismissRequest = { viewModel.onRationaleDismissed() },
            icon = { Icon(painterResource(R.drawable.ic_wifi), contentDescription = null) },
            title = { Text("Check Wi-Fi network?") },
            text = {
                Text(
                    "To safely use the Local shortcut, MyHomePC needs to see which Wi-Fi " +
                        "network you're on. Android requires location permission for this — " +
                        "MyHomePC doesn't track or store your location."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onRationaleAccepted()
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onPermissionDeclined() }) { Text("Not now") }
            }
        )
        is TrustDialog.TrustNetwork -> AlertDialog(
            onDismissRequest = { viewModel.onTrustPromptDismissed(dialog.ssid) },
            icon = { Icon(painterResource(R.drawable.ic_wifi), contentDescription = null) },
            title = { Text("Trust this network?") },
            text = {
                Text(
                    "You're on \"${dialog.ssid}\", a network MyHomePC hasn't seen before. " +
                        "Trust it to enable the faster Local connection here. Until then, " +
                        "MyHomePC keeps using Remote — nothing else changes."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.onTrustDecision(dialog.ssid, trusted = true) }) {
                    Text("Trust this network")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onTrustDecision(dialog.ssid, trusted = false) }) {
                    Text("Not now")
                }
            }
        )
        TrustDialog.None -> {}
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Devices")
                        val connectionLabel = (uiState as? DeviceListUiState.Loaded)?.connectionLabel
                        if (connectionLabel != null) {
                            Text(
                                "Connected via $connectionLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            when (val state = uiState) {
                is DeviceListUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is DeviceListUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        Button(onClick = onOpenSettings) {
                            Text("Open Settings")
                        }
                    }
                }
                is DeviceListUiState.Loaded -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.devices) { deviceState ->
                            DeviceRow(
                                deviceState = deviceState,
                                onClick = { onDeviceClick(deviceState.device.id, deviceState.device.name) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(deviceState: DeviceUiState, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color = statusColor(deviceState.online), shape = CircleShape)
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column {
                Text(deviceState.device.name, style = MaterialTheme.typography.titleMedium)
                Text(statusText(deviceState.online), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
