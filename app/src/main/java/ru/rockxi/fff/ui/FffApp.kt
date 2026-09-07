package ru.rockxi.fff.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.rockxi.fff.navigation.Destination
import ru.rockxi.fff.ui.finance.FinanceScreen

@Composable
fun FffApp() {
    val navController = rememberNavController()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(navController = navController, startDestination = Destination.Launcher.route) {
            composable(Destination.Launcher.route) {
                LauncherScreen(onOpen = { navController.navigate(it.route) })
            }
            composable(Destination.Finance.route) {
                FinanceScreen(onBack = navController::popBackStack)
            }
            composable(Destination.RemoteControl.route) {
                ModulePlaceholder(
                    kicker = "INFRASTRUCTURE",
                    title = "Remote Control",
                    description = "Подключения к хостам и защищённые терминальные сессии.",
                    onBack = navController::popBackStack,
                )
            }
        }
    }
}
