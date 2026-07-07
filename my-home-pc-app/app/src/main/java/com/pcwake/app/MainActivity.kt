package com.pcwake.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pcwake.app.data.SettingsRepository
import com.pcwake.app.ui.devicedetail.DeviceDetailScreen
import com.pcwake.app.ui.devicelist.DeviceListScreen
import com.pcwake.app.ui.settings.SettingsScreen
import com.pcwake.app.ui.theme.PCWakeTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Widget/tile "not configured yet" targets — land on Settings to pick a default device. */
        const val ACTION_OPEN_SETTINGS = "com.pcwake.app.OPEN_SETTINGS"

        /** Widget "Open" button / header — land on the default device's detail (resolved at open time). */
        const val ACTION_OPEN_DEFAULT_DEVICE = "com.pcwake.app.OPEN_DEFAULT_DEVICE"

        /**
         * Widget "Off" button — land on the default device's detail with the Shutdown
         * confirmation already up. Off is a deep-link, not a one-tap shutdown, so an accidental
         * tap costs a glance and a Cancel rather than the PC.
         */
        const val ACTION_SHUTDOWN_DEFAULT_DEVICE = "com.pcwake.app.SHUTDOWN_DEFAULT_DEVICE"

        /** Status notifications — land on the specific device the notification was about. */
        const val ACTION_OPEN_DEVICE = "com.pcwake.app.OPEN_DEVICE"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_DEVICE_NAME = "device_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Read once per activity instance: the launching surfaces use CLEAR_TOP, so a re-launch
        // recreates the activity and a fresh intent lands here.
        val launchAction = intent?.action
        val launchDeviceId = intent?.getStringExtra(EXTRA_DEVICE_ID)
        val launchDeviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME)
        setContent {
            PCWakeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val repository = remember { SettingsRepository(applicationContext) }
                    val startDestination = if (repository.isConfigured()) "deviceList" else "settings"

                    NavHost(navController = navController, startDestination = startDestination) {
                        composable("settings") {
                            val canGoBack = navController.previousBackStackEntry != null
                            SettingsScreen(
                                onSaved = {
                                    navController.navigate("deviceList") {
                                        popUpTo("settings") { inclusive = true }
                                    }
                                },
                                onBack = if (canGoBack) {
                                    { navController.popBackStack() }
                                } else {
                                    null
                                }
                            )
                        }
                        composable("deviceList") {
                            DeviceListScreen(
                                onOpenSettings = { navController.navigate("settings") },
                                onDeviceClick = { id, name ->
                                    navController.navigate("deviceDetail/$id/${Uri.encode(name)}")
                                }
                            )
                        }
                        composable(
                            "deviceDetail/{deviceId}/{deviceName}?promptShutdown={promptShutdown}",
                            arguments = listOf(
                                navArgument("deviceId") { type = NavType.StringType },
                                navArgument("deviceName") { type = NavType.StringType },
                                navArgument("promptShutdown") {
                                    type = NavType.BoolType
                                    defaultValue = false
                                }
                            )
                        ) { backStackEntry ->
                            val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
                            val deviceName = Uri.decode(backStackEntry.arguments?.getString("deviceName") ?: "")
                            val promptShutdown = backStackEntry.arguments?.getBoolean("promptShutdown") ?: false
                            DeviceDetailScreen(
                                deviceId = deviceId,
                                deviceName = deviceName,
                                promptShutdownOnLaunch = promptShutdown,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }

                    // Deep links from the widget, QS tile, and status notifications. Navigated
                    // on top of the normal start destination so Back behaves as if the user
                    // got there themselves.
                    LaunchedEffect(Unit) {
                        when (launchAction) {
                            ACTION_OPEN_SETTINGS -> {
                                if (startDestination != "settings") navController.navigate("settings")
                            }
                            ACTION_OPEN_DEFAULT_DEVICE -> {
                                val id = repository.getDefaultDeviceId()
                                if (id.isNotBlank()) {
                                    val name = repository.getDefaultDeviceName().ifBlank { id }
                                    navController.navigate("deviceDetail/$id/${Uri.encode(name)}")
                                } else if (startDestination != "settings") {
                                    // Default was cleared/never set — the closest useful screen.
                                    navController.navigate("settings")
                                }
                            }
                            ACTION_SHUTDOWN_DEFAULT_DEVICE -> {
                                val id = repository.getDefaultDeviceId()
                                if (id.isNotBlank()) {
                                    val name = repository.getDefaultDeviceName().ifBlank { id }
                                    // Same detail screen as Open, but ask it to raise the Shutdown
                                    // confirmation on arrival.
                                    navController.navigate("deviceDetail/$id/${Uri.encode(name)}?promptShutdown=true")
                                } else if (startDestination != "settings") {
                                    navController.navigate("settings")
                                }
                            }
                            ACTION_OPEN_DEVICE -> {
                                if (!launchDeviceId.isNullOrBlank()) {
                                    val name = launchDeviceName?.ifBlank { null } ?: launchDeviceId
                                    navController.navigate("deviceDetail/$launchDeviceId/${Uri.encode(name)}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
