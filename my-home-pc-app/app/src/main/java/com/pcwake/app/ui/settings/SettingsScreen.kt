package com.pcwake.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onSaved: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = viewModel()
) {
    val testState by viewModel.testState.collectAsState()
    val savedConfirmation by viewModel.savedConfirmation.collectAsState()
    var tokenVisible by remember { mutableStateOf(false) }

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
                .fillMaxSize(),
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

            Spacer(modifier = Modifier.weight(1f))

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
