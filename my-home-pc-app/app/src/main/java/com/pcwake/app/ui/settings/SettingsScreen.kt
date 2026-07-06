package com.pcwake.app.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pcwake.app.R
import com.pcwake.app.data.WifiNetworkMonitor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSaved: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = viewModel()
) {
    val testState by viewModel.testState.collectAsState()
    val savedConfirmation by viewModel.savedConfirmation.collectAsState()
    val trustedNetworks by viewModel.trustedNetworks.collectAsState()
    val wifiCheckDeclined by viewModel.wifiCheckDeclined.collectAsState()
    val locationServicesOff by viewModel.locationServicesOff.collectAsState()
    val deviceOptions by viewModel.deviceOptions.collectAsState()
    val defaultDeviceId by viewModel.defaultDeviceId.collectAsState()
    var tokenVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onWifiPermissionResult(granted) }

    // Picking a Default Device is what actually wires up the tile/widget, which is the one
    // workflow that can produce up/down notifications without ever visiting the device detail
    // screen (where this same permission is otherwise requested). Ask here too, or a
    // widget/tile-only user would never see the prompt and every notification would silently
    // drop (DeviceNotifications.post no-ops without it).
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* default device is selected either way */ }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Location services can only be toggled from the system Settings app, so re-check on
    // return — otherwise the hint above would stay stale until the screen is reopened.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.reloadTrust()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relay Settings") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = viewModel.localHost,
                onValueChange = { viewModel.localHost = it },
                label = { Text("Local Host (optional)") },
                placeholder = { Text("e.g. 192.168.1.50") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = viewModel.remoteHost,
                onValueChange = { viewModel.remoteHost = it },
                label = { Text("Remote Host") },
                placeholder = { Text("e.g. nas.your-vpn.net") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = viewModel.port,
                onValueChange = { viewModel.port = it },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = viewModel.token,
                onValueChange = { viewModel.token = it },
                label = { Text("API Token") },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { tokenVisible = !tokenVisible }) {
                        Text(if (tokenVisible) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { viewModel.testConnection() },
                enabled = testState !is ConnectionTestState.Testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test Connection")
            }

            when (val state = testState) {
                is ConnectionTestState.Testing -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing...")
                    }
                }
                is ConnectionTestState.Success -> {
                    Text("✓ ${state.message}", color = MaterialTheme.colorScheme.primary)
                }
                is ConnectionTestState.Failure -> {
                    Text("✗ ${state.message}", color = MaterialTheme.colorScheme.error)
                }
                ConnectionTestState.Idle -> {}
            }

            Text("Trusted Networks", style = MaterialTheme.typography.titleMedium)

            if (wifiCheckDeclined) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Wi-Fi check is off, so the Local shortcut stays disabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        viewModel.enableWifiCheck()
                        wifiPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }) { Text("Turn on") }
                }
            }

            if (locationServicesOff) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Location services are off on this device, so the Wi-Fi check can't " +
                            "run even though permission is granted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        WifiNetworkMonitor.openLocationSettings(context)
                    }) { Text("Open Settings") }
                }
            }

            if (trustedNetworks.isEmpty()) {
                Text(
                    "Networks appear here after MyHomePC asks about them. The Local shortcut " +
                        "is only used on networks you've marked trusted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                trustedNetworks.entries.sortedBy { it.key.lowercase() }.forEach { (ssid, trusted) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_wifi),
                            contentDescription = null,
                            tint = if (trusted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ssid, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (trusted) "Trusted · Local enabled" else "Not trusted · Remote only",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (trusted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = trusted,
                            onCheckedChange = { viewModel.setNetworkTrusted(ssid, it) }
                        )
                    }
                }
            }

            Text("Default Device", style = MaterialTheme.typography.titleMedium)
            Text(
                "Used by the Quick Settings tile and home screen widget.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when (val options = deviceOptions) {
                null -> Text(
                    "Devices appear here once the relay connection is saved and reachable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    if (options.size > 1 && defaultDeviceId.isBlank()) {
                        Text(
                            "Pick which PC the tile and widget should wake.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    options.forEach { device ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = device.id == defaultDeviceId,
                                onClick = {
                                    requestNotificationPermissionIfNeeded()
                                    viewModel.setDefaultDevice(device)
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.name, style = MaterialTheme.typography.bodyMedium)
                                if (device.id == defaultDeviceId) {
                                    Text(
                                        "Default",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (savedConfirmation) {
                Text("✓ Settings saved", color = MaterialTheme.colorScheme.primary)
            }

            Button(
                onClick = {
                    // Don't navigate away when validation rejected the values — the
                    // failure message is shown via the test-state text above.
                    if (viewModel.save()) {
                        onSaved()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}
