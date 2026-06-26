package com.pcwake.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                            "deviceDetail/{deviceId}/{deviceName}",
                            arguments = listOf(
                                navArgument("deviceId") { type = NavType.StringType },
                                navArgument("deviceName") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
                            val deviceName = Uri.decode(backStackEntry.arguments?.getString("deviceName") ?: "")
                            DeviceDetailScreen(
                                deviceId = deviceId,
                                deviceName = deviceName,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
