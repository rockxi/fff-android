package ru.rockxi.fff.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ru.rockxi.fff.navigation.Destination
import ru.rockxi.fff.ui.finance.FinanceScreen
import ru.rockxi.fff.ui.harness.HarnessScreen
import ru.rockxi.fff.ui.remote.RemoteScreen

@Composable
fun FffApp() {
    val navController = rememberNavController()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        NavHost(navController = navController, startDestination = Destination.Launcher.route,
            enterTransition = { fadeIn(tween(160)) }, exitTransition = { fadeOut(tween(140)) },
            popEnterTransition = { fadeIn(tween(160)) }, popExitTransition = { fadeOut(tween(140)) }) {
            composable(Destination.Launcher.route) {
                LauncherScreen(onOpen = { navController.navigate(it.route) })
            }
            composable(Destination.Finance.route) {
                FinanceScreen(onBack = navController::popBackStack)
            }
            composable(Destination.RemoteControl.route) {
                RemoteScreen(onBack = navController::popBackStack)
            }
            composable(Destination.Harness.route) { HarnessScreen(onBack = navController::popBackStack) }
        }
    }
}
